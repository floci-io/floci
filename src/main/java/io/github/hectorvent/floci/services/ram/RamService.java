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
 * so OTHER-ACCOUNTS simply means "every share the caller does not own".
 */
@ApplicationScoped
public class RamService {

    private static final String ORGANIZATION_SHARING_KEY = "sharing-with-organization-enabled";

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
        putForOwner(share);
        return share;
    }

    /**
     * @param resourceOwner {@code SELF} (shares the caller owns) or {@code OTHER-ACCOUNTS}
     *                      (shares other accounts made visible to the caller)
     */
    public List<ResourceShare> getResourceShares(String callerAccountId, String resourceOwner) {
        List<ResourceShare> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (isVisible(share, callerAccountId, resourceOwner)) {
                result.add(share);
            }
        }
        return result;
    }

    /** Organization sharing never creates invitations — resources become visible directly. */
    public List<Object> getResourceShareInvitations(String callerAccountId) {
        return List.of();
    }

    public List<SharedResource> listResources(String callerAccountId, String resourceOwner,
                                              List<String> resourceShareArns) {
        List<SharedResource> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
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

    public ResourceShare deleteResourceShare(String resourceShareArn) {
        ResourceShare deleted = requireShare(resourceShareArn).withStatus("DELETED");
        putForOwner(deleted);
        return deleted;
    }

    public ResourceShare updateResourceShare(String resourceShareArn, String name,
                                             Boolean allowExternalPrincipals) {
        ResourceShare share = requireShare(resourceShareArn);
        if (name != null) {
            share = share.withName(name);
        }
        if (allowExternalPrincipals != null) {
            share = share.withAllowExternalPrincipals(allowExternalPrincipals);
        }
        putForOwner(share);
        return share;
    }

    public ResourceShare associateResourceShare(String resourceShareArn,
                                                List<String> resourceArns, List<String> principals) {
        ResourceShare share = requireShare(resourceShareArn);
        ResourceShare updated = share.withPrincipalsAndResources(
                mergeDistinct(share.getPrincipals(), principals),
                mergeDistinct(share.getResourceArns(), resourceArns));
        putForOwner(updated);
        return updated;
    }

    public ResourceShare disassociateResourceShare(String resourceShareArn,
                                                    List<String> resourceArns, List<String> principals) {
        ResourceShare share = requireShare(resourceShareArn);
        ResourceShare updated = share.withPrincipalsAndResources(
                withoutAll(share.getPrincipals(), principals),
                withoutAll(share.getResourceArns(), resourceArns));
        putForOwner(updated);
        return updated;
    }

    /**
     * @param resourceOwner {@code SELF} or {@code OTHER-ACCOUNTS}, same visibility rule as
     *                      {@link #getResourceShares}
     */
    public List<PrincipalAssociation> listPrincipals(String callerAccountId, String resourceOwner,
                                                      List<String> resourceShareArns) {
        List<PrincipalAssociation> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            for (String principal : share.getPrincipals()) {
                result.add(new PrincipalAssociation(principal, share.getResourceShareArn(),
                        share.getCreationTime(), share.getCreationTime(), false));
            }
        }
        return result;
    }

    public void tagResource(String resourceShareArn, Map<String, String> newTags) {
        ResourceShare share = requireShare(resourceShareArn);
        Map<String, String> merged = new LinkedHashMap<>(share.getTags());
        merged.putAll(newTags);
        putForOwner(share.withTags(merged));
    }

    public void untagResource(String resourceShareArn, List<String> tagKeys) {
        ResourceShare share = requireShare(resourceShareArn);
        Map<String, String> remaining = new LinkedHashMap<>(share.getTags());
        tagKeys.forEach(remaining::remove);
        putForOwner(share.withTags(remaining));
    }

    private ResourceShare requireShare(String resourceShareArn) {
        return findShare(resourceShareArn)
                .orElseThrow(() -> new AwsException("UnknownResourceException",
                        "ResourceShare " + resourceShareArn + " does not exist.", 400));
    }

    private Optional<ResourceShare> findShare(String resourceShareArn) {
        return allShares().stream()
                .filter(share -> share.getResourceShareArn().equals(resourceShareArn))
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

    private void putForOwner(ResourceShare share) {
        if (shares instanceof AccountAwareStorageBackend<ResourceShare> accountAware) {
            accountAware.putForAccount(
                    share.getOwningAccountId(), share.getResourceShareArn(), share);
            return;
        }
        shares.put(share.getResourceShareArn(), share);
    }

    private List<ResourceShare> allShares() {
        if (shares instanceof AccountAwareStorageBackend<ResourceShare> accountAware) {
            return accountAware.scanAllAccounts();
        }
        return shares.scan(key -> true);
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
