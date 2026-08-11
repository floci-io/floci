package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private volatile boolean sharingWithOrganizationEnabled;

    private final Map<String, ResourceShare> sharesByArn = new ConcurrentHashMap<>();

    public boolean enableSharingWithAwsOrganization() {
        sharingWithOrganizationEnabled = true;
        return true;
    }

    public boolean isSharingWithOrganizationEnabled() {
        return sharingWithOrganizationEnabled;
    }

    public ResourceShare createResourceShare(String name, List<String> principals,
                                             List<String> resourceArns, boolean allowExternalPrincipals,
                                             String region, String owningAccountId) {
        String arn = "arn:aws:ram:" + region + ":" + owningAccountId
                + ":resource-share/" + UUID.randomUUID();
        ResourceShare share = new ResourceShare(
                arn, name, owningAccountId, principals, resourceArns, allowExternalPrincipals);
        sharesByArn.put(arn, share);
        return share;
    }

    /**
     * @param resourceOwner {@code SELF} (shares the caller owns) or {@code OTHER-ACCOUNTS}
     *                      (shares other accounts made visible to the caller)
     */
    public List<ResourceShare> getResourceShares(String callerAccountId, String resourceOwner) {
        List<ResourceShare> result = new ArrayList<>();
        for (ResourceShare share : sharesByArn.values()) {
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
        for (ResourceShare share : sharesByArn.values()) {
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
