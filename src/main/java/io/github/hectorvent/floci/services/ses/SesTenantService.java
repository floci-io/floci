package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>This is Phase 1 (tenant CRUD); resource associations, tenant suppression, and tenant-scoped
 * sending are separate follow-ups.
 */
@ApplicationScoped
public class SesTenantService {

    private static final Logger LOG = Logger.getLogger(SesTenantService.class);

    private static final int TENANT_NAME_MAX = 64;
    private static final Pattern TENANT_NAME_CHARS = Pattern.compile("[A-Za-z0-9_-]+");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final StorageBackend<String, Tenant> tenantStore;
    private final Clock clock;
    private final SecureRandom random;
    // Serializes the per-name check-then-put so concurrent creates for the same tenant can't both
    // succeed (InMemoryStorage only makes each individual operation thread-safe).
    private final Object tenantMutationLock = new Object();

    @Inject
    public SesTenantService(StorageFactory storageFactory, Clock clock) {
        this(storageFactory.create("ses", "ses-tenants.json",
                new TypeReference<Map<String, Tenant>>() {}), clock, new SecureRandom());
    }

    SesTenantService(StorageBackend<String, Tenant> tenantStore, Clock clock, SecureRandom random) {
        this.tenantStore = tenantStore;
        this.clock = clock;
        this.random = random;
    }

    public Tenant createTenant(String tenantName, List<Tag> tags, String accountId, String region) {
        validateTenantName(tenantName);
        SesTags.validate(tags);
        String key = tenantKey(region, tenantName);
        String tenantId = generateTenantId();
        String tenantArn = "arn:aws:ses:" + region + ":" + accountId + ":tenant/" + tenantName + "/" + tenantId;
        Tenant tenant = new Tenant(tenantName, tenantId, tenantArn, Instant.now(clock), tags, "ENABLED");
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
        validateTenantName(tenantName);
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
        validateTenantName(tenantName);
        String key = tenantKey(region, tenantName);
        synchronized (tenantMutationLock) {
            if (tenantStore.get(key).isEmpty()) {
                throw tenantNotFound(tenantName);
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

    // Validation order and messages verified against real AWS (2026-08-22): an empty string is the
    // Smithy min-length violation, a whitespace-only value is "cannot be empty", then length, then the
    // character-set rule.
    private static void validateTenantName(String name) {
        if (name == null) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'tenantName' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        if (name.isEmpty()) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'tenantName' failed to satisfy constraint: "
                            + "Member must have length greater than or equal to 1", 400);
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
