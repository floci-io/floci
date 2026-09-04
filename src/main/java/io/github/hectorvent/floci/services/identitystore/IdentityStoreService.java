package io.github.hectorvent.floci.services.identitystore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.identitystore.model.Group;
import io.github.hectorvent.floci.services.identitystore.model.Membership;
import io.github.hectorvent.floci.services.identitystore.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class IdentityStoreService {
    private static final Pattern STORE_ID = Pattern.compile("d-[0-9a-f]{10}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern RESOURCE_ID = Pattern.compile("([0-9a-f]{10}-|)[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}");
    private static final Set<String> RESERVED_NAMES = Set.of("Administrator", "AWSAdministrators");
    private static final int MAX_GROUPS = 100_000;
    private static final int MAX_USERS = 200_000;

    private final StorageBackend<String, Group> groups;
    private final StorageBackend<String, User> users;
    private final StorageBackend<String, Membership> memberships;

    @Inject
    public IdentityStoreService(StorageFactory storageFactory) {
        this(
                storageFactory.create("identitystore", "identitystore-groups.json", new TypeReference<Map<String, Group>>() {}),
                storageFactory.create("identitystore", "identitystore-users.json", new TypeReference<Map<String, User>>() {}),
                storageFactory.create("identitystore", "identitystore-memberships.json", new TypeReference<Map<String, Membership>>() {}));
    }

    IdentityStoreService(StorageBackend<String, Group> groups, StorageBackend<String, User> users,
                         StorageBackend<String, Membership> memberships) {
        this.groups = groups;
        this.users = users;
        this.memberships = memberships;
    }

    public synchronized Group createGroup(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String name = requireTextLength(request, "DisplayName", 1, 1024);
        requireNotReserved(name);
        String description = optionalTextLength(request, "Description", 1, 1024);
        if (listGroupsAll(storeId, name).size() > 0) {
            throw conflict("A group with DisplayName " + name + " already exists.");
        }
        if (groups.scan(key -> key.startsWith(storeId + "::")).size() >= MAX_GROUPS) {
            throw quota("The identity store group quota has been exceeded.");
        }
        Group group = new Group(id(), storeId, name, description);
        groups.put(groupKey(storeId, group.groupId()), group);
        return group;
    }

    public PaginatedResult<Group> listGroups(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String displayName = filterValue(request, "DisplayName");
        Integer maxResults = optionalMaxResults(request);
        String nextToken = text(request, "NextToken");
        return Pagination.paginate(listGroupsAll(storeId, displayName), Group::groupId,
                maxResults, nextToken, 50, 100, "ValidationException");
    }

    public List<Group> listGroups(String storeId, String displayName) {
        return listGroupsAll(requireStore(storeId), displayName);
    }

    private List<Group> listGroupsAll(String storeId, String displayName) {
        return groups.scan(key -> key.startsWith(storeId + "::")).stream()
                .filter(group -> displayName == null || displayName.equals(group.displayName()))
                .sorted(Comparator.comparing(Group::displayName).thenComparing(Group::groupId)).toList();
    }

    public synchronized User createUser(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String userName = requireTextLength(request, "UserName", 1, 128);
        requireNotReserved(userName);
        String displayName = optionalTextLength(request, "DisplayName", 1, 1024);
        if (listUsersAll(storeId, userName).size() > 0) {
            throw conflict("A user with UserName " + userName + " already exists.");
        }
        if (users.scan(key -> key.startsWith(storeId + "::")).size() >= MAX_USERS) {
            throw quota("The identity store user quota has been exceeded.");
        }
        User user = new User(id(), storeId, userName, displayName);
        users.put(userKey(storeId, user.userId()), user);
        return user;
    }

    public PaginatedResult<User> listUsers(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String userName = filterValue(request, "UserName");
        return Pagination.paginate(listUsersAll(storeId, userName), User::userId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public List<User> listUsers(String storeId, String userName) {
        return listUsersAll(requireStore(storeId), userName);
    }

    private List<User> listUsersAll(String storeId, String userName) {
        return users.scan(key -> key.startsWith(storeId + "::")).stream()
                .filter(user -> userName == null || userName.equals(user.userName()))
                .sorted(Comparator.comparing(User::userName).thenComparing(User::userId)).toList();
    }

    public synchronized Membership createMembership(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String groupId = requireResourceId(required(request, "GroupId"), "GroupId");
        String userId = memberUserId(request.get("MemberId"));
        requireGroup(storeId, groupId);
        requireUser(storeId, userId);
        String key = membershipPairKey(storeId, userId, groupId);
        if (memberships.get(key).isPresent()) {
            throw conflict("The user is already a member of the group.");
        }
        Membership membership = new Membership(id(), storeId, groupId, userId);
        memberships.put(key, membership);
        return membership;
    }

    public boolean isMember(String storeId, String userId, String groupId) {
        storeId = requireStore(storeId);
        userId = requireResourceId(userId, "MemberId.UserId");
        groupId = requireResourceId(groupId, "GroupId");
        requireUser(storeId, userId);
        requireGroup(storeId, groupId);
        return memberships.get(membershipPairKey(storeId, userId, groupId)).isPresent();
    }

    public List<String> validateGroupIds(JsonNode groupIds) {
        if (groupIds == null || !groupIds.isArray() || groupIds.size() < 1 || groupIds.size() > 100) {
            throw validation("GroupIds must contain between 1 and 100 identifiers.");
        }
        return java.util.stream.StreamSupport.stream(groupIds.spliterator(), false)
                .map(node -> {
                    if (!node.isTextual()) throw validation("GroupIds must contain strings.");
                    return requireResourceId(node.textValue(), "GroupId");
                }).toList();
    }

    private void requireGroup(String storeId, String groupId) {
        if (groups.get(groupKey(storeId, groupId)).isEmpty()) {
            throw notFound("Group not found: " + groupId);
        }
    }

    private void requireUser(String storeId, String userId) {
        if (users.get(userKey(storeId, userId)).isEmpty()) {
            throw notFound("User not found: " + userId);
        }
    }

    static String filterValue(JsonNode request) {
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters == null || !filters.isArray() || filters.isEmpty()) return null;
        JsonNode value = filters.get(0).get("AttributeValue");
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String filterValue(JsonNode request, String allowedPath) {
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters == null || filters.isNull() || (filters.isArray() && filters.isEmpty())) return null;
        if (!filters.isArray() || filters.size() > 1) throw validation("Filters must contain at most one filter.");
        JsonNode filter = filters.get(0);
        String path = text(filter, "AttributePath");
        String value = text(filter, "AttributeValue");
        if (!allowedPath.equals(path) || value == null || value.isBlank() || value.length() > 1024) {
            throw validation("The filter is invalid for this operation.");
        }
        return value;
    }

    static String memberUserId(JsonNode member) {
        if (member == null || !member.isObject() || member.size() != 1 || !member.path("UserId").isTextual()) {
            throw validation("MemberId must contain exactly one UserId string.");
        }
        return requireResourceId(member.path("UserId").textValue(), "MemberId.UserId");
    }

    static String required(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) throw validation(field + " must be a non-empty string.");
        return value;
    }

    static String text(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String requireTextLength(JsonNode request, String field, int min, int max) {
        String value = required(request, field);
        if (value.length() < min || value.length() > max) throw validation(field + " length is invalid.");
        return value;
    }

    private static String optionalTextLength(JsonNode request, String field, int min, int max) {
        if (request == null || !request.has(field) || request.get(field).isNull()) return null;
        String value = text(request, field);
        if (value == null || value.length() < min || value.length() > max) throw validation(field + " length is invalid.");
        return value;
    }

    private static Integer optionalMaxResults(JsonNode request) {
        JsonNode node = request == null ? null : request.get("MaxResults");
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber()) throw validation("MaxResults must be an integer.");
        return node.intValue();
    }

    private static String requireStore(String storeId) {
        if (storeId == null || !STORE_ID.matcher(storeId).matches()) throw validation("IdentityStoreId is invalid.");
        return storeId;
    }

    private static String requireResourceId(String value, String field) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) throw validation(field + " is invalid.");
        return value;
    }

    private static void requireNotReserved(String value) {
        if (RESERVED_NAMES.contains(value)) throw validation(value + " is a reserved identity name.");
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static String groupKey(String store, String id) { return store + "::" + id; }
    private static String userKey(String store, String id) { return store + "::" + id; }
    private static String membershipPairKey(String store, String user, String group) { return store + "::" + user + "::" + group; }
    private static AwsException conflict(String message) { return new AwsException("ConflictException", message, 400); }
    private static AwsException quota(String message) { return new AwsException("ServiceQuotaExceededException", message, 400); }
    private static AwsException notFound(String message) { return new AwsException("ResourceNotFoundException", message, 400); }
    private static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }

}
