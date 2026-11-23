package io.floci.aws.services.identitystore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.identitystore.model.Group;
import io.floci.aws.services.identitystore.model.Membership;
import io.floci.aws.services.identitystore.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Identity Store answers IAM with the group and the user of a membership its id names, and with the group, user or
 * membership a lookup's members find, as its handlers find them.
 */
class IdentityStoreStoredResourceArnsTest {

    private static final String STORE = "d-1234567890";
    private static final String GLOBAL_PARTITION = "000000000000";
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private IdentityStoreService service;
    private IdentityStoreStoredResourceArns stored;
    private Group group;
    private User user;
    private Membership membership;

    @BeforeEach
    void setUp() {
        service = new IdentityStoreService(AccountAwareStorageBackend.inMemory(GLOBAL_PARTITION),
                AccountAwareStorageBackend.inMemory(GLOBAL_PARTITION),
                AccountAwareStorageBackend.inMemory(GLOBAL_PARTITION), mapper);
        stored = new IdentityStoreStoredResourceArns(service, new RegionResolver(REGION, GLOBAL_PARTITION));
        group = service.createGroup(mapper.createObjectNode().put("IdentityStoreId", STORE)
                .put("DisplayName", "Admins"));
        user = service.createUser(mapper.createObjectNode().put("IdentityStoreId", STORE).put("UserName", "alice")
                .put("DisplayName", "Alice"));
        ObjectNode request = mapper.createObjectNode().put("IdentityStoreId", STORE).put("GroupId", group.groupId());
        request.putObject("MemberId").put("UserId", user.userId());
        membership = service.createMembership(request);
    }

    @Test
    void aMembershipReachesItsGroupAndItsUser() {
        assertEquals(List.of("arn:aws:identitystore:::group/" + group.groupId(),
                        "arn:aws:identitystore:::user/" + user.userId()),
                stored.through("MembershipId", membership.membershipId(), REGION));
        assertEquals(List.of(), stored.through("MembershipId", "1234567890-missing", REGION));
        assertEquals(List.of(), stored.through("GroupId", group.groupId(), REGION));
    }

    @Test
    void aLookupFindsWhatItsHandlerFinds() {
        assertEquals(Optional.of("arn:aws:identitystore:::membership/" + membership.membershipId()),
                stored.findByMembers("GroupMembership", List.of("IdentityStoreId", "GroupId", "MemberId.UserId"),
                        List.of(STORE, group.groupId(), user.userId()), REGION));
        assertEquals(Optional.of("arn:aws:identitystore:::user/" + user.userId()),
                stored.findByMembers("User", List.of("IdentityStoreId",
                                "AlternateIdentifier.UniqueAttribute.AttributePath",
                                "AlternateIdentifier.UniqueAttribute.AttributeValue"),
                        List.of(STORE, "userName", "alice"), REGION));
        assertEquals(Optional.of("arn:aws:identitystore:::group/" + group.groupId()),
                stored.findByMembers("Group", List.of("IdentityStoreId",
                                "AlternateIdentifier.UniqueAttribute.AttributePath",
                                "AlternateIdentifier.UniqueAttribute.AttributeValue"),
                        List.of(STORE, "displayName", "Admins"), REGION));
    }

    @Test
    void aLookupFindingNothingFindsNone() {
        assertEquals(Optional.empty(), stored.findByMembers("User", List.of("IdentityStoreId",
                        "AlternateIdentifier.UniqueAttribute.AttributePath",
                        "AlternateIdentifier.UniqueAttribute.AttributeValue"),
                List.of(STORE, "userName", "nobody"), REGION));
        assertEquals(Optional.empty(), stored.findByMembers("Group", List.of("IdentityStoreId",
                        "AlternateIdentifier.ExternalId.Issuer", "AlternateIdentifier.ExternalId.Id"),
                List.of(STORE, "issuer", "none"), REGION));
        assertEquals(Optional.empty(), stored.find("User", List.of("alice"), REGION));
    }
}
