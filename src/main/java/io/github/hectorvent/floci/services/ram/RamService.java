package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ram.model.PrincipalAssociation;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Resource Access Manager (RAM) business logic.
 *
 * <p>Covers {@code EnableSharingWithAwsOrganization} plus the resource-share reads LZA's
 * TGW share flow performs: the owning account registers a share (from the
 * {@code AWS::RAM::ResourceShare} CFN provisioner), and accepting accounts page
 * GetResourceShareInvitations (always empty here — organization sharing auto-accepts, matching
 * real AWS), find the share via GetResourceShares(OTHER-ACCOUNTS), and read the shared ARNs via
 * ListResources.
 *
 * <p>Principals are stored but not enforced for visibility: floci's org is the only org, and
 * launched containers call in with placeholder credentials that resolve to the default account,
 * so OTHER-ACCOUNTS simply means "every share the caller does not own". Mutations are the
 * opposite: they are owner-only, and a non-owner gets the same UnknownResourceException a
 * never-created ARN gets.
 */
@ApplicationScoped
public class RamService {

    private static final String ORGANIZATION_SHARING_KEY = "sharing-with-organization-enabled";
    /** The modeled ResourceShareStatus enum. */
    private static final List<String> SHARE_STATUSES =
            List.of("PENDING", "ACTIVE", "FAILED", "DELETING", "DELETED");

    private final StorageFactory storageFactory;
    private StorageBackend<String, ResourceShare> shares;
    private StorageBackend<String, Boolean> settings;

    @Inject
    public RamService(StorageFactory storageFactory) {
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void initializeStorage() {
        shares = storageFactory.create("ram", "ram-resource-shares.json",
                new TypeReference<Map<String, ResourceShare>>() {});
        settings = storageFactory.create("ram", "ram-settings.json",
                new TypeReference<Map<String, Boolean>>() {});
    }

    public boolean enableSharingWithAwsOrganization() {
        settings.put(ORGANIZATION_SHARING_KEY, true);
        return true;
    }

    public boolean isSharingWithOrganizationEnabled() {
        return settings.get(ORGANIZATION_SHARING_KEY).orElse(false);
    }

    public ResourceShare createResourceShare(String name, List<String> principals,
                                             List<String> resourceArns, boolean allowExternalPrincipals,
                                             String region, String owningAccountId) {
        String arn = "arn:aws:ram:" + region + ":" + owningAccountId
                + ":resource-share/" + UUID.randomUUID();
        ResourceShare share = new ResourceShare(
                arn, name, owningAccountId, principals, resourceArns, allowExternalPrincipals);
        return putForOwner(share);
    }

    /** The unfiltered read: every share visible to the caller under {@code resourceOwner}. */
    public List<ResourceShare> getResourceShares(String callerAccountId, String resourceOwner) {
        return getResourceShares(callerAccountId, resourceOwner, null, List.of(), null);
    }

    /**
     * @param resourceOwner {@code SELF} (shares the caller owns) or {@code OTHER-ACCOUNTS}
     *                      (shares other accounts made visible to the caller)
     * @param name exact share name to match, or null for any
     * @param resourceShareArns share ARNs to restrict the result to, or empty for any
     * @param resourceShareStatus one {@code ResourceShareStatus} value to match, or null for any
     */
    public List<ResourceShare> getResourceShares(String callerAccountId, String resourceOwner,
                                                 String name, List<String> resourceShareArns,
                                                 String resourceShareStatus) {
        requireResourceOwner(resourceOwner);
        requireResourceShareStatus(resourceShareStatus);
        List<ResourceShare> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
                continue;
            }
            if (name != null && !name.equals(share.getName())) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            if (resourceShareStatus != null && !resourceShareStatus.equals(share.getStatus())) {
                continue;
            }
            result.add(share);
        }
        return result;
    }

    /** Organization sharing never creates invitations — resources become visible directly. */
    public List<Object> getResourceShareInvitations(String callerAccountId) {
        return List.of();
    }

    public List<SharedResource> listResources(String callerAccountId, String resourceOwner,
                                              List<String> resourceShareArns) {
        requireResourceOwner(resourceOwner);
        List<SharedResource> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
                continue;
            }
            // A deleted share stays readable via GetResourceShares (status DELETED)
            // but its contents stop being consumable.
            if ("DELETED".equals(share.getStatus())) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            for (String resourceArn : share.getResourceArns()) {
                result.add(new SharedResource(resourceArn, ramResourceType(resourceArn),
                        share.getResourceShareArn(), "AVAILABLE"));
            }
        }
        return result;
    }

    public ResourceShare deleteResourceShare(String resourceShareArn, String callerAccountId) {
        return putForOwner(
                requireOwnedShare(resourceShareArn, callerAccountId).withStatus("DELETED"));
    }

    public ResourceShare updateResourceShare(String resourceShareArn, String name,
                                             Boolean allowExternalPrincipals, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        if (name != null) {
            share = share.withName(name);
        }
        if (allowExternalPrincipals != null) {
            share = share.withAllowExternalPrincipals(allowExternalPrincipals);
        }
        return putForOwner(share);
    }

    public ResourceShare associateResourceShare(String resourceShareArn, List<String> resourceArns,
                                                List<String> principals, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        ResourceShare updated = share.withPrincipalsAndResources(
                mergeDistinct(share.getPrincipals(), principals),
                mergeDistinct(share.getResourceArns(), resourceArns));
        return putForOwner(updated);
    }

    public ResourceShare disassociateResourceShare(String resourceShareArn, List<String> resourceArns,
                                                    List<String> principals, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        ResourceShare updated = share.withPrincipalsAndResources(
                withoutAll(share.getPrincipals(), principals),
                withoutAll(share.getResourceArns(), resourceArns));
        return putForOwner(updated);
    }

    /**
     * @param resourceOwner {@code SELF} or {@code OTHER-ACCOUNTS}, same visibility rule as
     *                      {@link #getResourceShares}
     */
    public List<PrincipalAssociation> listPrincipals(String callerAccountId, String resourceOwner,
                                                      List<String> resourceShareArns) {
        requireResourceOwner(resourceOwner);
        List<PrincipalAssociation> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
                continue;
            }
            // A deleted share stays readable via GetResourceShares (status DELETED)
            // but its contents stop being consumable.
            if ("DELETED".equals(share.getStatus())) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            for (String principal : share.getPrincipals()) {
                result.add(new PrincipalAssociation(principal, share.getResourceShareArn(),
                        share.getCreationTime(), share.getLastUpdatedTime(), false));
            }
        }
        return result;
    }

    public void tagResource(String resourceShareArn, Map<String, String> newTags, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        Map<String, String> merged = new LinkedHashMap<>(share.getTags());
        merged.putAll(newTags);
        putForOwner(share.withTags(merged));
    }

    public void untagResource(String resourceShareArn, List<String> tagKeys, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        Map<String, String> remaining = new LinkedHashMap<>(share.getTags());
        tagKeys.forEach(remaining::remove);
        putForOwner(share.withTags(remaining));
    }

    /**
     * Resolves a share the caller may mutate. A share owned by another account gets the same
     * UnknownResourceException as one that was never created: AWS resolves a share ARN within
     * the caller's own account, so a non-owner must not learn that the ARN exists — let alone
     * be able to rename, retag, or delete it.
     */
    private ResourceShare requireOwnedShare(String resourceShareArn, String callerAccountId) {
        return findOwnedShare(resourceShareArn, callerAccountId)
                .orElseThrow(() -> new AwsException("UnknownResourceException",
                        "ResourceShare " + resourceShareArn + " does not exist.", 400));
    }

    private Optional<ResourceShare> findOwnedShare(String resourceShareArn, String callerAccountId) {
        return allShares().stream()
                .filter(share -> share.getResourceShareArn().equals(resourceShareArn))
                .filter(share -> share.getOwningAccountId().equals(callerAccountId))
                // DELETED is terminal: the share stays readable via GetResourceShares for the
                // retention window, but mutations resolve it like an ARN that never existed.
                .filter(share -> !"DELETED".equals(share.getStatus()))
                .findFirst();
    }

    private static List<String> mergeDistinct(List<String> existing, List<String> additions) {
        Set<String> merged = new LinkedHashSet<>(existing);
        merged.addAll(additions);
        return List.copyOf(merged);
    }

    private static List<String> withoutAll(List<String> existing, List<String> removals) {
        List<String> remaining = new ArrayList<>(existing);
        remaining.removeAll(removals);
        return List.copyOf(remaining);
    }

    /**
     * The single write seam: every create and every mutation lands here, so stamping
     * lastUpdatedTime once at this point keeps it advancing without six call sites having to
     * remember to do it. Returns the stamped share so callers respond with what was stored.
     */
    private ResourceShare putForOwner(ResourceShare share) {
        ResourceShare stamped = share.withLastUpdatedTime(Instant.now());
        if (shares instanceof AccountAwareStorageBackend<ResourceShare> accountAware) {
            accountAware.putForAccount(
                    stamped.getOwningAccountId(), stamped.getResourceShareArn(), stamped);
            return stamped;
        }
        shares.put(stamped.getResourceShareArn(), stamped);
        return stamped;
    }

    private List<ResourceShare> allShares() {
        if (shares instanceof AccountAwareStorageBackend<ResourceShare> accountAware) {
            return accountAware.scanAllAccounts();
        }
        return shares.scan(key -> true);
    }

    /**
     * The model enumerates {@code resourceOwner} as {@code SELF} or {@code OTHER-ACCOUNTS} on every
     * read operation that takes it. The visibility fork below is a two-way branch, so an unmodelled
     * value silently means OTHER-ACCOUNTS — a caller who sent {@code "self"} would be handed every
     * share they do NOT own. AWS answers InvalidParameterException, which all three operations list.
     *
     * <p>It is also a required member, so a null one is rejected rather than defaulted: guessing
     * SELF answers a question the caller never asked, with the caller's own shares.
     */
    /** {@code ResourceShareStatus} is optional on GetResourceShares, but enumerated when sent. */
    private static void requireResourceShareStatus(String resourceShareStatus) {
        if (resourceShareStatus != null && !SHARE_STATUSES.contains(resourceShareStatus)) {
            throw new AwsException("InvalidParameterException",
                    "resourceShareStatus must be one of " + SHARE_STATUSES + ".", 400);
        }
    }

    private static void requireResourceOwner(String resourceOwner) {
        if (resourceOwner == null) {
            throw new AwsException("InvalidParameterException",
                    "resourceOwner is required and must be one of [SELF, OTHER-ACCOUNTS].", 400);
        }
        if (!"SELF".equals(resourceOwner) && !"OTHER-ACCOUNTS".equals(resourceOwner)) {
            throw new AwsException("InvalidParameterException",
                    "resourceOwner must be one of [SELF, OTHER-ACCOUNTS].", 400);
        }
    }

    private boolean isVisible(ResourceShare share, String callerAccountId, String resourceOwner) {
        boolean owned = share.getOwningAccountId().equals(callerAccountId);
        if ("SELF".equals(resourceOwner)) {
            return owned;
        }
        // OTHER-ACCOUNTS: every non-owned share is visible, regardless of principals.
        // Launched-container credentials resolve to the emulator default account, so a
        // principal-based check would hide account-principal shares from the very Lambda
        // they were shared with (LZA's Custom::GetResourceShare filters client-side by
        // owningAccountId + name anyway).
        return !owned;
    }

    /** {@code arn:aws:ec2:...:transit-gateway/tgw-1} → {@code ec2:TransitGateway}. */
    private static String ramResourceType(String resourceArn) {
        String[] parts = resourceArn.split(":", 6);
        if (parts.length < 6) {
            return "";
        }
        String service = parts[2];
        String resource = parts[5];
        int slash = resource.indexOf('/');
        String typeSegment = slash >= 0 ? resource.substring(0, slash) : resource;
        StringBuilder camel = new StringBuilder();
        for (String word : typeSegment.split("-")) {
            if (!word.isEmpty()) {
                camel.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return service + ":" + camel;
    }
}
