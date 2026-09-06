package io.github.hectorvent.floci.services.identitystore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.identitystore.model.Group;
import io.github.hectorvent.floci.services.identitystore.model.Membership;
import io.github.hectorvent.floci.services.identitystore.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityStoreServiceTest {
    private static final String STORE = "d-1234567890";
    private static final String GLOBAL_PARTITION = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<Group> groups;
    private AccountAwareStorageBackend<User> users;
    private AccountAwareStorageBackend<Membership> memberships;
    private IdentityStoreService service;

    @BeforeEach
    void setUp() {
        groups = AccountAwareStorageBackend.inMemory(GLOBAL_PARTITION);
        users = AccountAwareStorageBackend.inMemory(GLOBAL_PARTITION);
        memberships = AccountAwareStorageBackend.inMemory(GLOBAL_PARTITION);
        service = new IdentityStoreService(groups, users, memberships, mapper);
    }

    @Test
    void generatedIdsUseIdentityStorePrefix() {
        Group group = service.createGroup(request("DisplayName", "PlatformAdmins"));
        User user = service.createUser(request("UserName", "admin@example.com"));

        ObjectNode membershipRequest = mapper.createObjectNode();
        membershipRequest.put("IdentityStoreId", STORE);
        membershipRequest.put("GroupId", group.groupId());
        membershipRequest.putObject("MemberId").put("UserId", user.userId());
        Membership membership = service.createMembership(membershipRequest);

        assertTrue(group.groupId().startsWith("1234567890-"));
        assertTrue(user.userId().startsWith("1234567890-"));
        assertTrue(membership.membershipId().startsWith("1234567890-"));
    }

    @Test
    void optionalNamesRemainOptionalPerApiModel() {
        ObjectNode groupRequest = mapper.createObjectNode().put("IdentityStoreId", STORE);
        ObjectNode userRequest = mapper.createObjectNode().put("IdentityStoreId", STORE);

        Group group = service.createGroup(groupRequest);
        User user = service.createUser(userRequest);

        assertEquals(1, service.listGroups(groupRequest).items().size());
        assertEquals(1, service.listUsers(userRequest).items().size());
        assertEquals(null, group.displayName());
        assertEquals(null, user.userName());
    }

    @Test
    void alternateIdentifiersResolveExternalIds() {
        String now = Instant.now().toString();
        String groupId = "1234567890-11111111-1111-1111-1111-111111111111";
        String userId = "1234567890-22222222-2222-2222-2222-222222222222";

        ObjectNode groupAttributes = mapper.createObjectNode().put("DisplayName", "ExternalGroup");
        groupAttributes.putArray("ExternalIds").addObject().put("Issuer", "https://idp.example.com").put("Id", "group-42");
        groups.putForAccount(GLOBAL_PARTITION, STORE + "::" + groupId,
                new Group(groupId, STORE, groupAttributes, now, now));

        ObjectNode userAttributes = mapper.createObjectNode().put("UserName", "external@example.com");
        userAttributes.putArray("ExternalIds").addObject().put("Issuer", "https://idp.example.com").put("Id", "user-42");
        users.putForAccount(GLOBAL_PARTITION, STORE + "::" + userId,
                new User(userId, STORE, userAttributes, now, now));

        assertEquals(groupId, service.getGroupId(externalLookup("group-42")).groupId());
        assertEquals(userId, service.getUserId(externalLookup("user-42")).userId());
    }

    @Test
    void updateUserSupportsNestedAndEnterpriseAttributes() {
        User user = service.createUser(request("UserName", "nested@example.com"));
        ObjectNode update = mapper.createObjectNode();
        update.put("IdentityStoreId", STORE);
        update.put("UserId", user.userId());
        ArrayNode operations = update.putArray("Operations");
        operations.addObject()
                .put("AttributePath", "name.familyName")
                .put("AttributeValue", "García");
        ObjectNode department = operations.addObject();
        department.put("AttributePath", "aws:identitystore:enterprise.department");
        department.put("AttributeValue", "Platform");

        service.updateUser(update);
        User updated = service.describeUser(describeUser(user.userId()));

        assertEquals("García", updated.attributes().path("Name").path("FamilyName").asText());
        assertEquals("Platform", updated.attributes().path("Extensions")
                .path("aws:identitystore:enterprise").path("department").asText());
    }

    @Test
    void updateUserRejectsImmutableAndUnsupportedAttributePaths() {
        User user = service.createUser(request("UserName", "immutable@example.com"));

        for (String path : new String[]{"UserId", "userId", "identityStoreId", "createdAt", "updatedBy"}) {
            ObjectNode update = updateRequest("UserId", user.userId(), path, "forbidden");
            AwsException error = assertThrows(AwsException.class, () -> service.updateUser(update));
            assertEquals("ValidationException", error.getErrorCode());
        }

        User unchanged = service.describeUser(describeUser(user.userId()));
        assertEquals(user.userId(), unchanged.userId());
        assertTrue(!unchanged.attributes().has("UserId"));
    }

    @Test
    void updateGroupRejectsUserExtensionsAndUnsupportedAttributes() {
        Group group = service.createGroup(request("DisplayName", "ImmutableGroup"));

        for (String path : new String[]{"GroupId", "groupId", "createdAt", "name", "aws:identitystore:enterprise.department"}) {
            ObjectNode update = updateRequest("GroupId", group.groupId(), path, "forbidden");
            AwsException error = assertThrows(AwsException.class, () -> service.updateGroup(update));
            assertEquals("ValidationException", error.getErrorCode());
        }

        Group unchanged = service.describeGroup(describeGroup(group.groupId()));
        assertEquals(group.groupId(), unchanged.groupId());
        assertEquals("ImmutableGroup", unchanged.displayName());
    }

    @Test
    void clearRemovesAllPersistedState() {
        service.createGroup(request("DisplayName", "PlatformAdmins"));
        service.createUser(request("UserName", "admin@example.com"));

        service.clear();

        ObjectNode listRequest = mapper.createObjectNode().put("IdentityStoreId", STORE);
        assertTrue(service.listGroups(listRequest).items().isEmpty());
        assertTrue(service.listUsers(listRequest).items().isEmpty());
    }

    private ObjectNode request(String field, String value) {
        ObjectNode request = mapper.createObjectNode();
        request.put("IdentityStoreId", STORE);
        request.put(field, value);
        return request;
    }

    private ObjectNode externalLookup(String id) {
        ObjectNode request = mapper.createObjectNode().put("IdentityStoreId", STORE);
        request.putObject("AlternateIdentifier").putObject("ExternalId")
                .put("Issuer", "https://idp.example.com")
                .put("Id", id);
        return request;
    }

    private ObjectNode describeUser(String userId) {
        ObjectNode request = mapper.createObjectNode().put("IdentityStoreId", STORE);
        request.put("UserId", userId);
        return request;
    }

    private ObjectNode describeGroup(String groupId) {
        ObjectNode request = mapper.createObjectNode().put("IdentityStoreId", STORE);
        request.put("GroupId", groupId);
        return request;
    }

    private ObjectNode updateRequest(String idField, String id, String path, String value) {
        ObjectNode request = mapper.createObjectNode().put("IdentityStoreId", STORE);
        request.put(idField, id);
        request.putArray("Operations").addObject()
                .put("AttributePath", path)
                .put("AttributeValue", value);
        return request;
    }
}
