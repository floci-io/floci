package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.services.ses.model.TenantResourceAssociation;
import io.github.hectorvent.floci.services.ses.model.TenantSuppressionAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * SES v2 tenants (multi-tenancy), owning the {@code tenantStore}. The domain owns id/ARN generation,
 * the synthetic sending status, and the name validation so they can't be bypassed; the controller
 * only parses the REST JSON. Reached through the {@code SesService} facade, which delegates here.
 *
 * <p>Account isolation comes from the account-aware store returned by {@code StorageFactory}: keys
 * built here carry only region and tenant name. The caller's account is used for the ARN (and the
 * AlreadyExists message), not the key.
 *
 * <p>Phase 2 adds tenant→resource associations, owned here as well ({@code associationStore}); the
 * facade validates that the referenced resource exists before delegating. Phase 3 adds the tenant
 * suppression attributes (stored on the tenant record) — the tenant-scoped suppression list itself
 * lives in {@code SesSuppressionService} with the account list, and cascades through the
 * {@code DeleteTenant} callback. Tenant-scoped sending is a separate follow-up.
 */
@ApplicationScoped
public class SesTenantService {

    private static final Logger LOG = Logger.getLogger(SesTenantService.class);

    private static final int TENANT_NAME_MAX = 64;
    private static final Pattern TENANT_NAME_CHARS = Pattern.compile("[A-Za-z0-9_-]+");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // The wire values for ResourceType — the ARN segment, not the SDK enum spelling. Real AWS both
    // returns these and requires them as Filter values; the SDK's EMAIL_IDENTITY-style enum constants
    // are rejected by the service (probe-confirmed 2026-08-28).
    static final String RESOURCE_TYPE_IDENTITY = "identity";
    static final String RESOURCE_TYPE_CONFIGURATION_SET = "configuration-set";
    static final String RESOURCE_TYPE_TEMPLATE = "template";
    private static final List<String> SUPPORTED_RESOURCE_TYPES =
            List.of(RESOURCE_TYPE_IDENTITY, RESOURCE_TYPE_CONFIGURATION_SET, RESOURCE_TYPE_TEMPLATE);

    /**
     * A parsed, format-validated SES resource ARN for the association operations; {@code type} is one
     * of the wire resource types. Deliberately separate from the tag dispatch's {@code parseSesArn}
     * in {@code SesService}: the association APIs have their own probe-confirmed error messages and
     * validation precedence (type before region before account), so the parsers must not be merged.
     */
    record AssociationResource(String type, String name, String arn) {
    }

    private final StorageBackend<String, Tenant> tenantStore;
    private final StorageBackend<String, TenantResourceAssociation> associationStore;
    private final Clock clock;
    private final SecureRandom random;
    // Serializes the per-name check-then-put so concurrent creates for the same tenant can't both
    // succeed (InMemoryStorage only makes each individual operation thread-safe).
    private final Object tenantMutationLock = new Object();

    @Inject
    public SesTenantService(StorageFactory storageFactory, Clock clock) {
        this(storageFactory.create("ses", "ses-tenants.json",
                        new TypeReference<Map<String, Tenant>>() {}),
                storageFactory.create("ses", "ses-tenant-associations.json",
                        new TypeReference<Map<String, TenantResourceAssociation>>() {}),
                clock, new SecureRandom());
    }

    SesTenantService(StorageBackend<String, Tenant> tenantStore,
                     StorageBackend<String, TenantResourceAssociation> associationStore,
                     Clock clock, SecureRandom random) {
        this.tenantStore = tenantStore;
        this.associationStore = associationStore;
        this.clock = clock;
        this.random = random;
    }

    public Tenant createTenant(String tenantName, List<Tag> tags, String accountId, String region) {
        return createTenant(tenantName, tags, null, null, accountId, region);
    }

    public Tenant createTenant(String tenantName, List<Tag> tags, List<String> suppressedReasons,
                               String suppressionScope, String accountId, String region) {
        validateTenantName(tenantName, true);
        SesTags.validate(tags);
        TenantSuppressionAttributes attrs =
                validateSuppressionAttributesPair(suppressedReasons, suppressionScope);
        String key = tenantKey(region, tenantName);
        String tenantId = generateTenantId();
        String tenantArn = "arn:aws:ses:" + region + ":" + accountId + ":tenant/" + tenantName + "/" + tenantId;
        Tenant tenant = new Tenant(tenantName, tenantId, tenantArn, Instant.now(clock), tags,
                "ENABLED", attrs);
        // Only the check-then-put needs to be atomic, so two concurrent creates for the same name can't
        // both observe the key as absent; the id/ARN/record are built outside the lock.
        synchronized (tenantMutationLock) {
            if (tenantStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExistsException",
                        "Tenant with name " + tenantName + " already exists in account " + accountId, 400);
            }
            tenantStore.put(key, tenant);
        }
        LOG.infov("Created SES tenant {0} ({1}) in account {2} region {3}",
                tenantName, tenantId, accountId, region);
        return tenant;
    }

    public Tenant getTenant(String tenantName, String region) {
        // TenantName is a required, min-length-1 member, so a malformed name is a BadRequest, not a
        // lookup miss.
        validateTenantName(tenantName, false);
        return tenantStore.get(tenantKey(region, tenantName))
                .orElseThrow(() -> tenantNotFound(tenantName));
    }

    public List<Tenant> listTenants(String region) {
        String prefix = tenantKeyPrefix(region);
        return tenantStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(Tenant::createdTimestamp,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Tenant::tenantName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public void deleteTenant(String tenantName, String region) {
        deleteTenant(tenantName, region, tenant -> { });
    }

    /**
     * Deletes the tenant. {@code dependentCascade} runs inside the lock with the resolved tenant so
     * the facade can cascade state held by other domains (the tenant suppression list); like the
     * associations, it runs before the tenant record is removed — persistent/wal backends apply each
     * deletion durably, so a crash mid-cascade must leave the tenant record (a retryable
     * DeleteTenant) rather than orphans no API call can remove.
     */
    public void deleteTenant(String tenantName, String region, Consumer<Tenant> dependentCascade) {
        validateTenantName(tenantName, false);
        String key = tenantKey(region, tenantName);
        synchronized (tenantMutationLock) {
            Tenant tenant = tenantStore.get(key).orElseThrow(() -> tenantNotFound(tenantName));
            dependentCascade.accept(tenant);
            // AWS cascades: deleting a tenant silently removes its resource associations
            // (probe-confirmed 2026-08-28). Keys carry the TenantId, so a recreated same-name tenant
            // never sees the old associations.
            String assocPrefix = associationKeyPrefix(region, tenant.tenantId());
            for (String assocKey : associationStore.keys().stream()
                    .filter(k -> k.startsWith(assocPrefix)).toList()) {
                associationStore.delete(assocKey);
            }
            tenantStore.delete(key);
        }
        LOG.infov("Deleted SES tenant {0} in region {1}", tenantName, region);
    }

    /** Reads a tenant without throwing, so later phases (associations, tenant-scoped send) can check
     * existence through the facade without duplicating the key derivation. */
    public Optional<Tenant> find(String tenantName, String region) {
        return tenantStore.get(tenantKey(region, tenantName));
    }

    /**
     * Resolves a tenant by its TenantId, for the ARN-dispatched tag operations: AWS resolves a
     * tenant tag ARN by the id segment alone; the name segment is not matched (probe-confirmed).
     */
    public Optional<Tenant> findByTenantId(String tenantId, String region) {
        String prefix = tenantKeyPrefix(region);
        return tenantStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(t -> tenantId != null && tenantId.equals(t.tenantId()))
                .findFirst();
    }

    /**
     * The name/id decomposition of a tenant tag ARN's resource remainder ({@code <name>/<tenantId>}
     * after the generic dispatch split off the {@code tenant/} type segment). AWS parses a remainder
     * without a slash as a null name with the whole segment as the id, and resolves by the id alone
     * (probe-confirmed): tenant-domain knowledge, so it lives here, not in the tag dispatch.
     */
    record TenantTagArn(String name, String tenantId) {
        static TenantTagArn parse(String resourceRemainder) {
            int slash = resourceRemainder.indexOf('/');
            return new TenantTagArn(slash < 0 ? null : resourceRemainder.substring(0, slash),
                    slash < 0 ? resourceRemainder : resourceRemainder.substring(slash + 1));
        }
    }

    /** Resolves a tenant from a tag ARN's resource remainder, for the facade's tag listing. */
    public Tenant tenantForTagArn(String resourceRemainder, String region) {
        TenantTagArn arn = TenantTagArn.parse(resourceRemainder);
        return findByTenantId(arn.tenantId(), region)
                .orElseThrow(() -> tagArnTenantNotFound(arn));
    }

    /**
     * Applies a tag mutation for the facade's ARN-dispatched tagging. The tenant is re-resolved by
     * TenantId and mutated INSIDE the shared lock, so concurrent tag calls can't lose each other's
     * merges, a concurrent suppression-attribute update isn't overwritten by a stale copy, and a
     * delete/recreate between the caller's lookup and this write can't resurrect the old record.
     */
    public void mutateTags(String resourceRemainder, String region, UnaryOperator<List<Tag>> mutation) {
        TenantTagArn arn = TenantTagArn.parse(resourceRemainder);
        synchronized (tenantMutationLock) {
            Tenant current = findByTenantId(arn.tenantId(), region)
                    .orElseThrow(() -> tagArnTenantNotFound(arn));
            // The record's list may be a mutable one (Jackson-built); hand the callback an immutable
            // copy so an in-place mutation can't reach the stored record, and store an immutable copy
            // so the persisted list can't be aliased either.
            List<Tag> tags = current.tags() == null ? List.of() : List.copyOf(current.tags());
            tenantStore.put(tenantKey(region, current.tenantName()),
                    current.withTags(List.copyOf(mutation.apply(tags))));
        }
    }

    /** The tag-dispatch not-found error: the missing space before "with" is AWS's own. */
    private static AwsException tagArnTenantNotFound(TenantTagArn arn) {
        return new AwsException("NotFoundException",
                "No Tenant present with name: " + arn.name() + "with tenantId: " + arn.tenantId(), 404);
    }

    // ──────────────────────── Resource associations (Phase 2) ────────────────────────
    // Behavior and messages probe-confirmed against real AWS us-east-1, 2026-08-28.

    /**
     * Resolves the tenant for the association operations. Unlike the CRUD operations, the association
     * request shapes carry no Smithy min-length on TenantName, so an empty value gets the
     * service-level message instead of the Smithy one.
     */
    public Tenant tenantForAssociation(String tenantName, String region) {
        // Absent and empty both get the service-level message here (probe-confirmed 2026-08-30) —
        // the Smithy not-null variant exists only on CreateTenant.
        if (tenantName == null || tenantName.isBlank()) {
            throw new AwsException("BadRequestException", "TenantName cannot be empty", 400);
        }
        return tenantStore.get(tenantKey(region, tenantName))
                .orElseThrow(() -> tenantNotFound(tenantName));
    }

    // ──────────────────────── Tenant-scoped sending (Phase 4) ────────────────────────
    // Behavior and messages probe-confirmed against real AWS us-east-1, 2026-08-30.

    /**
     * Resolves the tenant for a send. The not-found wording differs from the management operations:
     * no angle brackets, and the account id is included.
     */
    public Tenant tenantForSending(String tenantName, String region, String accountId) {
        // Unreachable through the facade (which treats a null TenantName as a non-tenant send), but
        // a public helper should fail the AWS way — same guard as runWithTenant.
        if (tenantName == null || tenantName.isBlank()) {
            throw new AwsException("BadRequestException", "TenantName cannot be empty", 400);
        }
        return tenantStore.get(tenantKey(region, tenantName))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Tenant " + tenantName + " for AwsAccountId " + accountId + " not found.", 404));
    }

    /**
     * The send gate: every resource a tenant send uses (From identity, configuration set, template)
     * must be associated with the tenant, or AWS refuses the send with a 403. The bracket list
     * carries every missing ARN (the plural is AWS's own wording even for a single resource; on the
     * wire AWS spells the body key "Message" — Floci renders its usual error shape instead).
     */
    public void requireResourcesAssociated(Tenant tenant, List<AssociationResource> resources,
                                           String region) {
        List<String> missing = resources.stream()
                .filter(ref -> associationStore
                        .get(associationKey(region, tenant.tenantId(), ref)).isEmpty())
                .map(AssociationResource::arn)
                .toList();
        if (!missing.isEmpty()) {
            throw new AwsException("AccessDeniedException",
                    "Tenant not associated with resources [" + String.join(", ", missing) + "].", 403);
        }
    }

    /**
     * Parses and format-validates a resource ARN for the association operations. Check order matches
     * the observed AWS precedence: not an ARN at all, then not an SES ARN, then an unsupported SES
     * resource type, then region and account mismatches. Existence is the facade's job.
     */
    public static AssociationResource parseResourceArn(String resourceArn, String accountId, String region) {
        if (resourceArn == null) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'resourceArn' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        String[] parts = resourceArn.split(":", 6);
        if (parts.length < 6 || !"arn".equals(parts[0])) {
            throw new AwsException("BadRequestException",
                    "Provided resource identifier is not an SES resource", 400);
        }
        if (!"ses".equals(parts[2])) {
            throw new AwsException("BadRequestException",
                    "Provided ARN is not in SES resource ARN format", 400);
        }
        String resource = parts[5];
        int slash = resource.indexOf('/');
        String type = slash < 0 ? resource : resource.substring(0, slash);
        if (!SUPPORTED_RESOURCE_TYPES.contains(type)) {
            throw new AwsException("BadRequestException", "Unsupported resource type: " + type, 400);
        }
        if (slash < 0 || slash == resource.length() - 1) {
            // A supported type with no name segment can never reference a resource; reject it as
            // malformed rather than letting it 404 with an empty name.
            throw new AwsException("BadRequestException",
                    "Provided resource identifier is not an SES resource", 400);
        }
        if (!region.equals(parts[3])) {
            throw new AwsException("BadRequestException",
                    "Resource <" + resourceArn + "> must be in the same region", 400);
        }
        if (!accountId.equals(parts[4])) {
            throw new AwsException("BadRequestException",
                    "Resource <" + resourceArn + "> must be in the same account", 400);
        }
        return new AssociationResource(type, resource.substring(slash + 1), resourceArn);
    }

    /**
     * Creates the association under the shared lock. The tenant is revalidated (a concurrent
     * {@code DeleteTenant} may have cascaded since the caller resolved it) and
     * {@code resourceExistenceCheck} — the facade's throwing existence check — runs inside the lock
     * too, so a backing resource cannot slip through {@link #deleteBackingResource} concurrently and
     * leave an association pointing at a deleted resource.
     */
    public void associate(Tenant tenant, AssociationResource ref, String region,
                          Runnable resourceExistenceCheck) {
        String key = associationKey(region, tenant.tenantId(), ref);
        synchronized (tenantMutationLock) {
            Tenant current = tenantStore.get(tenantKey(region, tenant.tenantName())).orElse(null);
            if (current == null || !tenant.tenantId().equals(current.tenantId())) {
                throw tenantNotFound(tenant.tenantName());
            }
            resourceExistenceCheck.run();
            if (associationStore.get(key).isPresent()) {
                // "Resources" is AWS's own grammar for this message.
                throw new AwsException("AlreadyExistsException",
                        "Resources " + ref.arn() + " has already been associated with tenant "
                                + tenant.tenantName(), 400);
            }
            associationStore.put(key, new TenantResourceAssociation(tenant.tenantName(),
                    tenant.tenantId(), ref.arn(), ref.type(), Instant.now(clock)));
        }
        LOG.infov("Associated SES resource {0} with tenant {1} in region {2}",
                ref.arn(), tenant.tenantName(), region);
    }

    /**
     * Runs a backing-resource deletion under the same lock as association creation: AWS refuses to
     * delete an identity, configuration set, or template that still has tenant associations, and the
     * shared lock keeps that guard atomic with {@link #associate}'s existence check.
     */
    public void deleteBackingResource(String resourceType, String resourceName, String region,
                                      Runnable deleteAction) {
        synchronized (tenantMutationLock) {
            findAssociationForResource(resourceType, resourceName, region).ifPresent(a -> {
                throw new AwsException("BadRequestException",
                        "Cannot delete <" + a.resourceArn() + "> because it has tenant associations. "
                                + "Remove all tenant associations and try again.", 400);
            });
            deleteAction.run();
        }
    }

    /** Removing an association that does not exist is a silent success on AWS. */
    public void disassociate(Tenant tenant, AssociationResource ref, String region) {
        associationStore.delete(associationKey(region, tenant.tenantId(), ref));
        LOG.infov("Disassociated SES resource {0} from tenant {1} in region {2}",
                ref.arn(), tenant.tenantName(), region);
    }

    /** AWS returns the tenant's resources ordered by ARN. */
    public List<TenantResourceAssociation> listTenantResources(Tenant tenant, String typeFilter,
                                                               String region) {
        String prefix = associationKeyPrefix(region, tenant.tenantId());
        return associationStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(a -> typeFilter == null || typeFilter.equals(a.resourceType()))
                .sorted(Comparator.comparing(TenantResourceAssociation::resourceArn,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // The resource lookups match on the stored record, not on a key suffix: a resource name may
    // itself contain the "::" delimiter (Floci barely restricts identity and template names), so a
    // suffix match on the key could alias one resource's associations to another's.

    /** AWS returns a resource's tenants ordered by association time. */
    public List<TenantResourceAssociation> listResourceTenants(AssociationResource ref, String region) {
        String regionPrefix = "tenantAssoc::" + region + "::";
        return associationStore.scan(k -> k.startsWith(regionPrefix)).stream()
                .filter(a -> ref.arn().equals(a.resourceArn()))
                .sorted(Comparator.comparing(TenantResourceAssociation::associatedTimestamp,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TenantResourceAssociation::tenantName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Finds any association for the given resource, for the facade's delete guards: AWS refuses to
     * delete an identity, configuration set, or template that still has tenant associations.
     */
    public Optional<TenantResourceAssociation> findAssociationForResource(String resourceType,
                                                                          String resourceName,
                                                                          String region) {
        String regionPrefix = "tenantAssoc::" + region + "::";
        return associationStore.scan(k -> k.startsWith(regionPrefix)).stream()
                .filter(a -> resourceType.equals(a.resourceType())
                        && resourceName.equals(resourceNameFromArn(a.resourceArn())))
                .findFirst();
    }

    // Stored ARNs were validated by parseResourceArn, so the resource part always has a name segment.
    private static String resourceNameFromArn(String arn) {
        String resource = arn.split(":", 6)[5];
        return resource.substring(resource.indexOf('/') + 1);
    }

    public static void validateResourceTypeFilter(String value) {
        if (value != null && !SUPPORTED_RESOURCE_TYPES.contains(value)) {
            throw new AwsException("BadRequestException",
                    "Invalid resource type " + value + " specified.", 400);
        }
    }

    /**
     * The list operations return everything in one page, so any client-supplied NextToken is invalid —
     * which is also what AWS answers for a token it cannot decrypt. PageSize is still range-checked.
     */
    public static void validateListPaging(Integer pageSize, String nextToken) {
        if (pageSize != null && pageSize < 1) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value '" + pageSize + "' at 'pageSize' failed to "
                            + "satisfy constraint: Member must have value greater than or equal to 1", 400);
        }
        if (nextToken != null) {
            throw new AwsException("BadRequestException", "Invalid Next Token", 400);
        }
    }

    private static String associationKey(String region, String tenantId, AssociationResource ref) {
        return associationKeyPrefix(region, tenantId) + ref.type() + "::" + ref.name();
    }

    private static String associationKeyPrefix(String region, String tenantId) {
        return "tenantAssoc::" + region + "::" + tenantId + "::";
    }

    // ──────────────────────── Suppression attributes (Phase 3) ────────────────────────
    // Behavior and messages probe-confirmed against real AWS us-east-1, 2026-08-30.

    /**
     * {@code PutTenantSuppressionAttributes}: both members set the block (an empty reason list is a
     * valid state), neither member clears it. Observed precedence: empty TenantName, then the Smithy
     * enum checks, then duplicates, then the pair rules, then tenant existence — a put on a missing
     * tenant still gets its request validated first.
     */
    public void putSuppressionAttributes(String tenantName, List<String> suppressedReasons,
                                         String suppressionScope, String region) {
        // Unlike CreateTenant, an absent TenantName gets the same service-level message as an empty
        // one here (probe-confirmed 2026-08-30) — no Smithy not-null variant on this operation.
        if (tenantName == null || tenantName.isBlank()) {
            throw new AwsException("BadRequestException", "TenantName cannot be empty", 400);
        }
        TenantSuppressionAttributes attrs =
                validateSuppressionAttributesPair(suppressedReasons, suppressionScope);
        String key = tenantKey(region, tenantName);
        synchronized (tenantMutationLock) {
            Tenant tenant = tenantStore.get(key).orElseThrow(() -> tenantNotFound(tenantName));
            tenantStore.put(key, tenant.withSuppressionAttributes(attrs));
        }
        LOG.infov("Updated suppression attributes of SES tenant {0} in region {1}: {2}",
                tenantName, region, attrs);
    }

    /**
     * Resolves the tenant and runs {@code action} under the shared lock — the tenant-scoped
     * suppression-list operations go through this so a concurrent {@code DeleteTenant} cascade
     * cannot interleave and leave entries for a deleted tenant.
     */
    public <T> T runWithTenant(String tenantName, String region, Function<Tenant, T> action) {
        // Same service-level message for null and blank: on the operations served here the probed
        // AWS behavior collapses an absent TenantName to "cannot be empty" (only CreateTenant has
        // the Smithy not-null variant).
        if (tenantName == null || tenantName.isBlank()) {
            throw new AwsException("BadRequestException", "TenantName cannot be empty", 400);
        }
        synchronized (tenantMutationLock) {
            Tenant tenant = tenantStore.get(tenantKey(region, tenantName))
                    .orElseThrow(() -> tenantNotFound(tenantName));
            return action.apply(tenant);
        }
    }

    /**
     * Validates the SuppressedReasons/SuppressionScope pair (shared by CreateTenant and
     * PutTenantSuppressionAttributes) and returns the block to store — {@code null} when neither
     * member was given. AWS rejects half a pair with member-specific messages, and an empty reason
     * list without a scope gets its own third wording.
     */
    private static TenantSuppressionAttributes validateSuppressionAttributesPair(
            List<String> suppressedReasons, String suppressionScope) {
        if (suppressedReasons != null) {
            for (String reason : suppressedReasons) {
                SesSuppressionService.validateSuppressionReason(reason, "suppressedReasons", true);
            }
        }
        if (suppressionScope != null && !"TENANT".equals(suppressionScope)
                && !"ACCOUNT".equals(suppressionScope)) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'suppressionScope' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: [TENANT, ACCOUNT]", 400);
        }
        if (suppressedReasons != null) {
            Set<String> seen = new HashSet<>();
            for (String reason : suppressedReasons) {
                if (!seen.add(reason)) {
                    throw new AwsException("BadRequestException",
                            "Each suppressed reason can only be specified at most once", 400);
                }
            }
        }
        if (suppressedReasons != null && suppressionScope == null) {
            throw new AwsException("BadRequestException", suppressedReasons.isEmpty()
                    ? "SuppressionScope is required when SuppressedReasons are provided. "
                            + "Valid values are: TENANT, ACCOUNT"
                    : "SuppressedReasons cannot be specified without SuppressionScope.", 400);
        }
        if (suppressedReasons == null && suppressionScope != null) {
            throw new AwsException("BadRequestException",
                    "SuppressionScope cannot be specified without SuppressedReasons.", 400);
        }
        if (suppressedReasons == null) {
            return null;
        }
        return new TenantSuppressionAttributes(List.copyOf(suppressedReasons), suppressionScope);
    }

    // Validation order and messages verified against real AWS (2026-08-22 and 2026-08-30): the two
    // Smithy wordings (not-null for an absent name, min-length for an empty one) exist ONLY on
    // CreateTenant; every other tenant operation collapses both to "TenantName cannot be empty".
    // Then a whitespace-only value is "cannot be empty", then length, then the character-set rule.
    private static void validateTenantName(String name, boolean createTenantSmithyVariants) {
        if (name == null) {
            if (createTenantSmithyVariants) {
                throw new AwsException("BadRequestException",
                        "1 validation error detected: Value at 'tenantName' failed to satisfy "
                                + "constraint: Member must not be null", 400);
            }
            throw new AwsException("BadRequestException", "TenantName cannot be empty", 400);
        }
        if (name.isEmpty()) {
            if (createTenantSmithyVariants) {
                throw new AwsException("BadRequestException",
                        "1 validation error detected: Value at 'tenantName' failed to satisfy "
                                + "constraint: Member must have length greater than or equal to 1", 400);
            }
            throw new AwsException("BadRequestException", "TenantName cannot be empty", 400);
        }
        if (name.isBlank()) {
            throw new AwsException("BadRequestException", "TenantName cannot be empty", 400);
        }
        if (name.length() > TENANT_NAME_MAX) {
            throw new AwsException("BadRequestException",
                    "TenantName cannot exceed " + TENANT_NAME_MAX + " characters.", 400);
        }
        if (!TENANT_NAME_CHARS.matcher(name).matches()) {
            throw new AwsException("BadRequestException",
                    "Invalid tenant name <" + name + ">: only alphanumeric ASCII characters, '_', and "
                            + "'-' are allowed.", 400);
        }
    }

    private static AwsException tenantNotFound(String tenantName) {
        return new AwsException("NotFoundException",
                "The requested tenant <" + tenantName + "> does not exist.", 404);
    }

    private String generateTenantId() {
        // AWS tenant ids look like "tn-" followed by 30 lowercase hex characters.
        byte[] bytes = new byte[15];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("tn-");
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    // The store is account-scoped transparently by AccountAwareStorageBackend (StorageFactory wraps
    // every store), so the key only needs region + name — the same convention as the other SES stores.
    private static String tenantKey(String region, String tenantName) {
        return tenantKeyPrefix(region) + tenantName;
    }

    private static String tenantKeyPrefix(String region) {
        return "tenant::" + region + "::";
    }
}
