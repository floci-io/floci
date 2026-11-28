package io.floci.aws.services.identitystore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What IAM asks Identity Store of the resources a request names: the identity store's ARN in the account whose IAM
 * Identity Center instance holds it, the group and the user of the membership a {@code MembershipId} names, and the
 * group, user or membership a lookup's request names by other members (an alternate identifier, or a group and a
 * member), which the Service Authorization Reference requires those actions on. Each is found as its handler finds
 * it.
 */
@ApplicationScoped
public class IdentityStoreStoredResourceArns implements StoredResourceArns {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IdentityStoreService identityStore;
    private final SsoAdminService ssoAdmin;
    private final RegionResolver regions;

    @Inject
    public IdentityStoreStoredResourceArns(IdentityStoreService identityStore, SsoAdminService ssoAdmin,
                                           RegionResolver regions) {
        this.identityStore = identityStore;
        this.ssoAdmin = ssoAdmin;
        this.regions = regions;
    }

    @Override
    public String namespace() {
        return "identitystore";
    }

    /**
     * An identity store's ARN in the account whose IAM Identity Center instance holds it, as every ARN names the
     * account owning its resource, so another account of its organization reading it is checked on the store where it
     * is; none for a store no instance holds, which stays in the caller's account. Identity Store finds its other
     * resources by the members a lookup names them by, never by one name alone.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"Identitystore".equals(type) || names.size() != 1 || names.getFirst() == null) {
            return Optional.empty();
        }
        String storeId = names.getFirst();
        return ssoAdmin.identityStoreOwner(storeId).map(owner -> RequestResources.idArn(namespace(), type, storeId,
                regions.partitionForRegion(region), region, owner));
    }

    /**
     * The group, user or membership the lookup the members make up finds, as {@code GetGroupId}, {@code GetUserId} and
     * {@code GetGroupMembershipId} find it; empty when it finds none.
     */
    @Override
    public Optional<String> findByMembers(String type, List<String> members, List<String> names, String region) {
        if (members.size() != names.size()) {
            return Optional.empty();
        }
        ObjectNode request = MAPPER.createObjectNode();
        for (int i = 0; i < members.size(); i++) {
            ObjectNode node = request;
            String[] path = members.get(i).split("\\.");
            for (int j = 0; j < path.length - 1; j++) {
                node = node.has(path[j]) ? (ObjectNode) node.get(path[j]) : node.putObject(path[j]);
            }
            node.put(path[path.length - 1], names.get(i));
        }
        try {
            String id = switch (type) {
                case "Group" -> identityStore.getGroupId(request).groupId();
                case "User" -> identityStore.getUserId(request).userId();
                case "GroupMembership" -> identityStore.getMembershipId(request).membershipId();
                default -> null;
            };
            return Optional.ofNullable(id).map(found -> arn(type, found, region));
        } catch (AwsException e) {
            return Optional.empty();
        }
    }

    /** The group and the user of the membership a {@code MembershipId} names; none when there is no such membership. */
    @Override
    public List<String> through(String member, String value, String region) {
        if (!"MembershipId".equals(member)) {
            return List.of();
        }
        return identityStore.findMembership(value)
                .map(membership -> Stream.of(arn("Group", membership.groupId(), region),
                        arn("User", membership.userId(), region)).toList())
                .orElse(List.of());
    }

    private String arn(String type, String id, String region) {
        return RequestResources.idArn(namespace(), type, id, regions.partitionForRegion(region), region, "");
    }
}
