package io.github.hectorvent.floci.services.identitystore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.identitystore.model.Group;
import io.github.hectorvent.floci.services.identitystore.model.Membership;
import io.github.hectorvent.floci.services.identitystore.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class IdentityStoreService implements Resettable {
    private static final String GLOBAL_PARTITION = "000000000000";
    private static final Pattern STORE_ID = Pattern.compile(
            "d-[0-9a-f]{10}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern RESOURCE_ID = Pattern.compile(
            "([0-9a-f]{10}-|)[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}");
    private static final Set<String> RESERVED_NAMES = Set.of("Administrator", "AWSAdministrators");
    private static final Set<String> GROUP_WRITABLE_ATTRIBUTES = Set.of(
            "description", "displayName", "externalIds");
    private static final Set<String> USER_WRITABLE_ATTRIBUTES = Set.of(
            "addresses", "birthdate", "displayName", "emails", "externalIds", "locale", "name", "nickName",
            "phoneNumbers", "photos", "preferredLanguage", "profileUrl", "roles", "timezone", "title", "userName",
            "userStatus", "userType", "website");
    private static final String ENTERPRISE_EXTENSION = "aws:identitystore:enterprise";
    private static final Pattern ATTRIBUTE_PATH = Pattern.compile(
            "(?:\\p{L}+:\\p{L}+:\\p{L}+(?:\\.\\p{L}+){0,3}|\\p{L}+(?:\\.\\p{L}+){0,2})");
    private static final int MAX_GROUPS = 100_000;
    private static final int MAX_USERS = 200_000;

    private final AccountAwareStorageBackend<Group> groups;
    private final AccountAwareStorageBackend<User> users;
    private final AccountAwareStorageBackend<Membership> memberships;
    private final ObjectMapper mapper;

    @Inject
    public IdentityStoreService(StorageFactory storageFactory, ObjectMapper mapper) {
        this(
                storageFactory.create("identitystore", "identitystore-groups.json",
                        new TypeReference<Map<String, Group>>() {}),
                storageFactory.create("identitystore", "identitystore-users.json",
                        new TypeReference<Map<String, User>>() {}),
                storageFactory.create("identitystore", "identitystore-memberships.json",
                        new TypeReference<Map<String, Membership>>() {}),
                mapper);
    }

    IdentityStoreService(AccountAwareStorageBackend<Group> groups,
                         AccountAwareStorageBackend<User> users,
                         AccountAwareStorageBackend<Membership> memberships,
                         ObjectMapper mapper) {
        this.groups = groups;
        this.users = users;
        this.memberships = memberships;
        this.mapper = mapper;
    }

    public synchronized Group createGroup(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        ObjectNode attributes = copyAttributes(request, Set.of("IdentityStoreId"));
        String displayName = optionalTextLength(attributes, "DisplayName", 1, 1024);
        if (displayName != null) {
            requireNotReserved(displayName);
            if (!listGroupsAll(storeId, displayName).isEmpty()) {
                throw conflict("A group with DisplayName " + displayName + " already exists.");
            }
        }
        optionalTextLength(attributes, "Description", 1, 1024);
        if (scanGroups(storeId).size() >= MAX_GROUPS) {
            throw quota("The identity store group quota has been exceeded.");
        }
        String now = Instant.now().toString();
        Group group = new Group(resourceId(storeId), storeId, attributes, now, now);
        groups.putForAccount(GLOBAL_PARTITION, groupKey(storeId, group.groupId()), group);
        return group;
    }

    public PaginatedResult<Group> listGroups(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String displayName = filterValue(request, "DisplayName");
        return Pagination.paginate(listGroupsAll(storeId, displayName), Group::groupId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public Group describeGroup(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        return requireGroup(storeId, requireResourceId(required(request, "GroupId"), "GroupId"));
    }

    public synchronized void updateGroup(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String groupId = requireResourceId(required(request, "GroupId"), "GroupId");
        Group group = requireGroup(storeId, groupId);
        ObjectNode updated = group.attributes().deepCopy();
        applyOperations(updated, request.get("Operations"), false);
        String newDisplayName = optionalTextLength(updated, "DisplayName", 1, 1024);
        if (newDisplayName != null) {
            requireNotReserved(newDisplayName);
            boolean duplicate = listGroupsAll(storeId, newDisplayName).stream()
                    .anyMatch(candidate -> !candidate.groupId().equals(groupId));
            if (duplicate) {
                throw conflict("A group with DisplayName " + newDisplayName + " already exists.");
            }
        }
        group.setAttributes(updated);
        group.setUpdatedAt(Instant.now().toString());
        groups.putForAccount(GLOBAL_PARTITION, groupKey(storeId, groupId), group);
    }

    public synchronized void deleteGroup(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String groupId = requireResourceId(required(request, "GroupId"), "GroupId");
        requireGroup(storeId, groupId);
        listMembershipsAll(storeId).stream()
                .filter(membership -> groupId.equals(membership.groupId()))
                .forEach(membership -> memberships.deleteForAccount(GLOBAL_PARTITION,
                        membershipKey(storeId, membership.membershipId())));
        groups.deleteForAccount(GLOBAL_PARTITION, groupKey(storeId, groupId));
    }

    public Group getGroupId(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        JsonNode alternate = requireObject(request, "AlternateIdentifier");
        return findGroupByAlternate(storeId, alternate);
    }

    public synchronized User createUser(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        ObjectNode attributes = copyAttributes(request, Set.of("IdentityStoreId"));
        String userName = optionalTextLength(attributes, "UserName", 1, 128);
        if (userName != null) {
            requireNotReserved(userName);
            if (!listUsersAll(storeId, userName).isEmpty()) {
                throw conflict("A user with UserName " + userName + " already exists.");
            }
        }
        optionalTextLength(attributes, "DisplayName", 1, 1024);
        if (scanUsers(storeId).size() >= MAX_USERS) {
            throw quota("The identity store user quota has been exceeded.");
        }
        String now = Instant.now().toString();
        User user = new User(resourceId(storeId), storeId, attributes, now, now);
        users.putForAccount(GLOBAL_PARTITION, userKey(storeId, user.userId()), user);
        return user;
    }

    public PaginatedResult<User> listUsers(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String userName = filterValue(request, "UserName");
        return Pagination.paginate(listUsersAll(storeId, userName), User::userId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public User describeUser(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        return requireUser(storeId, requireResourceId(required(request, "UserId"), "UserId"));
    }

    public synchronized void updateUser(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String userId = requireResourceId(required(request, "UserId"), "UserId");
        User user = requireUser(storeId, userId);
        ObjectNode updated = user.attributes().deepCopy();
        applyOperations(updated, request.get("Operations"), true);
        String newUserName = optionalTextLength(updated, "UserName", 1, 128);
        if (newUserName != null) {
            requireNotReserved(newUserName);
            boolean duplicate = listUsersAll(storeId, newUserName).stream()
                    .anyMatch(candidate -> !candidate.userId().equals(userId));
            if (duplicate) {
                throw conflict("A user with UserName " + newUserName + " already exists.");
            }
        }
        user.setAttributes(updated);
        user.setUpdatedAt(Instant.now().toString());
        users.putForAccount(GLOBAL_PARTITION, userKey(storeId, userId), user);
    }

    public synchronized void deleteUser(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String userId = requireResourceId(required(request, "UserId"), "UserId");
        requireUser(storeId, userId);
        listMembershipsAll(storeId).stream()
                .filter(membership -> userId.equals(membership.userId()))
                .forEach(membership -> memberships.deleteForAccount(GLOBAL_PARTITION,
                        membershipKey(storeId, membership.membershipId())));
        users.deleteForAccount(GLOBAL_PARTITION, userKey(storeId, userId));
    }

    public User getUserId(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        JsonNode alternate = requireObject(request, "AlternateIdentifier");
        return findUserByAlternate(storeId, alternate);
    }

    public synchronized Membership createMembership(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String groupId = requireResourceId(required(request, "GroupId"), "GroupId");
        String userId = memberUserId(request.get("MemberId"));
        requireGroup(storeId, groupId);
        requireUser(storeId, userId);
        boolean exists = listMembershipsAll(storeId).stream()
                .anyMatch(membership -> groupId.equals(membership.groupId()) && userId.equals(membership.userId()));
        if (exists) {
            throw conflict("The user is already a member of the group.");
        }
        String now = Instant.now().toString();
        Membership membership = new Membership(resourceId(storeId), storeId, groupId, userId, now, now);
        memberships.putForAccount(GLOBAL_PARTITION, membershipKey(storeId, membership.membershipId()), membership);
        return membership;
    }

    public Membership describeMembership(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String membershipId = requireResourceId(required(request, "MembershipId"), "MembershipId");
        return requireMembership(storeId, membershipId);
    }

    public Membership getMembershipId(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String groupId = requireResourceId(required(request, "GroupId"), "GroupId");
        String userId = memberUserId(request.get("MemberId"));
        requireGroup(storeId, groupId);
        requireUser(storeId, userId);
        return listMembershipsAll(storeId).stream()
                .filter(membership -> groupId.equals(membership.groupId()) && userId.equals(membership.userId()))
                .findFirst()
                .orElseThrow(() -> notFound("Group membership not found."));
    }

    public synchronized void deleteMembership(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String membershipId = requireResourceId(required(request, "MembershipId"), "MembershipId");
        requireMembership(storeId, membershipId);
        memberships.deleteForAccount(GLOBAL_PARTITION, membershipKey(storeId, membershipId));
    }

    public PaginatedResult<Membership> listGroupMemberships(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String groupId = requireResourceId(required(request, "GroupId"), "GroupId");
        requireGroup(storeId, groupId);
        List<Membership> matching = listMembershipsAll(storeId).stream()
                .filter(membership -> groupId.equals(membership.groupId()))
                .sorted(Comparator.comparing(Membership::membershipId))
                .toList();
        return paginateMemberships(matching, request);
    }

    public PaginatedResult<Membership> listGroupMembershipsForMember(JsonNode request) {
        String storeId = requireStore(required(request, "IdentityStoreId"));
        String userId = memberUserId(request.get("MemberId"));
        requireUser(storeId, userId);
        List<Membership> matching = listMembershipsAll(storeId).stream()
                .filter(membership -> userId.equals(membership.userId()))
                .sorted(Comparator.comparing(Membership::membershipId))
                .toList();
        return paginateMemberships(matching, request);
    }

    public boolean isMember(String storeId, String userId, String groupId) {
        storeId = requireStore(storeId);
        userId = requireResourceId(userId, "MemberId.UserId");
        groupId = requireResourceId(groupId, "GroupId");
        requireUser(storeId, userId);
        requireGroup(storeId, groupId);
        String finalStoreId = storeId;
        String finalUserId = userId;
        String finalGroupId = groupId;
        return listMembershipsAll(finalStoreId).stream()
                .anyMatch(membership -> finalUserId.equals(membership.userId())
                        && finalGroupId.equals(membership.groupId()));
    }

    public List<String> validateGroupIds(JsonNode groupIds) {
        if (groupIds == null || !groupIds.isArray() || groupIds.size() < 1 || groupIds.size() > 100) {
            throw validation("GroupIds must contain between 1 and 100 identifiers.");
        }
        return java.util.stream.StreamSupport.stream(groupIds.spliterator(), false)
                .map(node -> {
                    if (!node.isTextual()) {
                        throw validation("GroupIds must contain strings.");
                    }
                    return requireResourceId(node.textValue(), "GroupId");
                })
                .toList();
    }

    @Override
    public void clear() {
        groups.clear();
        users.clear();
        memberships.clear();
    }

    private List<Group> listGroupsAll(String storeId, String displayName) {
        return scanGroups(storeId).stream()
                .filter(group -> displayName == null || displayName.equals(group.displayName()))
                .sorted(Comparator.comparing(Group::displayName, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(Group::groupId))
                .toList();
    }

    private List<User> listUsersAll(String storeId, String userName) {
        return scanUsers(storeId).stream()
                .filter(user -> userName == null || userName.equals(user.userName()))
                .sorted(Comparator.comparing(User::userName, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(User::userId))
                .toList();
    }

    private List<Group> scanGroups(String storeId) {
        return groups.scanForAccount(GLOBAL_PARTITION, key -> key.startsWith(storeId + "::"));
    }

    private List<User> scanUsers(String storeId) {
        return users.scanForAccount(GLOBAL_PARTITION, key -> key.startsWith(storeId + "::"));
    }

    private List<Membership> listMembershipsAll(String storeId) {
        return memberships.scanForAccount(GLOBAL_PARTITION, key -> key.startsWith(storeId + "::"));
    }

    private Group requireGroup(String storeId, String groupId) {
        return groups.getForAccount(GLOBAL_PARTITION, groupKey(storeId, groupId))
                .orElseThrow(() -> notFound("Group not found: " + groupId));
    }

    private User requireUser(String storeId, String userId) {
        return users.getForAccount(GLOBAL_PARTITION, userKey(storeId, userId))
                .orElseThrow(() -> notFound("User not found: " + userId));
    }

    private Membership requireMembership(String storeId, String membershipId) {
        return memberships.getForAccount(GLOBAL_PARTITION, membershipKey(storeId, membershipId))
                .orElseThrow(() -> notFound("Group membership not found: " + membershipId));
    }

    private Group findGroupByAlternate(String storeId, JsonNode alternate) {
        validateAlternateUnion(alternate);
        JsonNode unique = alternate.get("UniqueAttribute");
        if (unique != null) {
            if (!"displayName".equals(text(unique, "AttributePath"))) {
                throw validation("The only supported unique group attribute path is displayName.");
            }
            JsonNode value = unique.get("AttributeValue");
            if (value == null || !value.isTextual()) {
                throw validation("AlternateIdentifier.UniqueAttribute.AttributeValue must be a string.");
            }
            return listGroupsAll(storeId, value.textValue()).stream().findFirst()
                    .orElseThrow(() -> notFound("Group not found."));
        }
        JsonNode external = alternate.get("ExternalId");
        return scanGroups(storeId).stream()
                .filter(group -> hasExternalId(group.attributes(), external))
                .findFirst()
                .orElseThrow(() -> notFound("Group not found."));
    }

    private User findUserByAlternate(String storeId, JsonNode alternate) {
        validateAlternateUnion(alternate);
        JsonNode unique = alternate.get("UniqueAttribute");
        if (unique != null) {
            String path = text(unique, "AttributePath");
            JsonNode value = unique.get("AttributeValue");
            if (value == null || !value.isTextual()) {
                throw validation("AlternateIdentifier.UniqueAttribute.AttributeValue must be a string.");
            }
            if ("userName".equals(path)) {
                return listUsersAll(storeId, value.textValue()).stream().findFirst()
                        .orElseThrow(() -> notFound("User not found."));
            }
            if ("emails.value".equals(path)) {
                return scanUsers(storeId).stream()
                        .filter(user -> arrayHasValue(user.attributes().get("Emails"), "Value", value.textValue()))
                        .findFirst()
                        .orElseThrow(() -> notFound("User not found."));
            }
            throw validation("The unique user attribute path must be userName or emails.value.");
        }
        JsonNode external = alternate.get("ExternalId");
        return scanUsers(storeId).stream()
                .filter(user -> hasExternalId(user.attributes(), external))
                .findFirst()
                .orElseThrow(() -> notFound("User not found."));
    }

    private static boolean hasExternalId(ObjectNode attributes, JsonNode requested) {
        if (requested == null || !requested.isObject()) {
            throw validation("ExternalId must be an object.");
        }
        String issuer = required(requested, "Issuer");
        String id = required(requested, "Id");
        JsonNode externalIds = attributes.get("ExternalIds");
        if (externalIds == null || !externalIds.isArray()) {
            return false;
        }
        for (JsonNode externalId : externalIds) {
            if (issuer.equals(text(externalId, "Issuer")) && id.equals(text(externalId, "Id"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean arrayHasValue(JsonNode array, String field, String expected) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (expected.equals(text(item, field))) {
                return true;
            }
        }
        return false;
    }

    private static void validateAlternateUnion(JsonNode alternate) {
        boolean external = alternate.has("ExternalId") && !alternate.get("ExternalId").isNull();
        boolean unique = alternate.has("UniqueAttribute") && !alternate.get("UniqueAttribute").isNull();
        if (external == unique) {
            throw validation("AlternateIdentifier must contain exactly one union member.");
        }
    }

    private void applyOperations(ObjectNode attributes, JsonNode operations, boolean user) {
        if (operations == null || !operations.isArray() || operations.size() < 1 || operations.size() > 100) {
            throw validation("Operations must contain between 1 and 100 attribute operations.");
        }
        for (JsonNode operation : operations) {
            if (!operation.isObject()) {
                throw validation("Each operation must be an object.");
            }
            String path = required(operation, "AttributePath");
            JsonNode value = operation.get("AttributeValue");
            applyAttribute(attributes, path, value, user);
        }
    }

    private void applyAttribute(ObjectNode attributes, String path, JsonNode value, boolean user) {
        if (path.length() > 255 || !ATTRIBUTE_PATH.matcher(path).matches()) {
            throw validation("AttributePath is invalid.");
        }
        if (path.startsWith("aws:identitystore:")) {
            if (!user || !(path.equals(ENTERPRISE_EXTENSION) || path.startsWith(ENTERPRISE_EXTENSION + "."))) {
                throw validation("The attribute path is not supported for this resource.");
            }
            ObjectNode extensions = attributes.withObject("Extensions");
            int dot = path.indexOf('.');
            if (dot < 0) {
                setOrRemove(extensions, path, value);
                return;
            }
            String extensionName = path.substring(0, dot);
            String nestedPath = path.substring(dot + 1);
            ObjectNode extension = extensions.withObject(extensionName);
            setNested(extension, nestedPath, value);
            return;
        }
        String[] segments = path.split("\\.");
        Set<String> writableAttributes = user ? USER_WRITABLE_ATTRIBUTES : GROUP_WRITABLE_ATTRIBUTES;
        if (!writableAttributes.contains(segments[0])) {
            throw validation("The " + (user ? "user" : "group") + " attribute path is not supported.");
        }
        segments[0] = upperCamel(segments[0]);
        setNested(attributes, String.join(".", segments), value);
    }

    private void setNested(ObjectNode root, String path, JsonNode value) {
        String[] segments = path.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = i == 0 ? segments[i] : upperCamel(segments[i]);
            JsonNode child = current.get(segment);
            if (child == null || !child.isObject()) {
                child = mapper.createObjectNode();
                current.set(segment, child);
            }
            current = (ObjectNode) child;
        }
        String leaf = segments.length == 1 ? segments[0] : upperCamel(segments[segments.length - 1]);
        setOrRemove(current, leaf, value);
    }

    private static void setOrRemove(ObjectNode node, String field, JsonNode value) {
        if (value == null || value.isNull()) {
            node.remove(field);
        } else {
            node.set(field, value.deepCopy());
        }
    }

    private ObjectNode copyAttributes(JsonNode request, Set<String> excluded) {
        if (request == null || !request.isObject()) {
            throw validation("The request body must be a JSON object.");
        }
        ObjectNode copy = mapper.createObjectNode();
        request.fields().forEachRemaining(entry -> {
            if (!excluded.contains(entry.getKey())) {
                copy.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return copy;
    }

    private static PaginatedResult<Membership> paginateMemberships(List<Membership> memberships, JsonNode request) {
        return Pagination.paginate(memberships, Membership::membershipId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    private static String filterValue(JsonNode request, String allowedPath) {
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters == null || filters.isNull() || (filters.isArray() && filters.isEmpty())) {
            return null;
        }
        if (!filters.isArray() || filters.size() > 1) {
            throw validation("Filters must contain at most one filter.");
        }
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
        if (value == null || value.isBlank()) {
            throw validation(field + " must be a non-empty string.");
        }
        return value;
    }

    private static JsonNode requireObject(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isObject()) {
            throw validation(field + " must be an object.");
        }
        return value;
    }

    static String text(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String optionalTextLength(JsonNode request, String field, int min, int max) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        String value = text(request, field);
        if (value == null || value.length() < min || value.length() > max) {
            throw validation(field + " length is invalid.");
        }
        return value;
    }

    private static Integer optionalMaxResults(JsonNode request) {
        JsonNode node = request == null ? null : request.get("MaxResults");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw validation("MaxResults must be an integer.");
        }
        int value = node.intValue();
        if (value < 1 || value > 100) {
            throw validation("MaxResults must be between 1 and 100.");
        }
        return value;
    }

    private static String requireStore(String storeId) {
        if (storeId == null || !STORE_ID.matcher(storeId).matches()) {
            throw validation("IdentityStoreId is invalid.");
        }
        return storeId;
    }

    private static String requireResourceId(String value, String field) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) {
            throw validation(field + " is invalid.");
        }
        return value;
    }

    private static void requireNotReserved(String value) {
        if (RESERVED_NAMES.contains(value)) {
            throw validation(value + " is a reserved identity name.");
        }
    }

    private static String resourceId(String storeId) {
        String uuid = UUID.randomUUID().toString();
        return storeId.startsWith("d-") ? storeId.substring(2) + "-" + uuid : uuid;
    }

    private static String groupKey(String store, String id) {
        return store + "::" + id;
    }

    private static String userKey(String store, String id) {
        return store + "::" + id;
    }

    private static String membershipKey(String store, String id) {
        return store + "::" + id;
    }

    private static String upperCamel(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    private static AwsException quota(String message) {
        return new AwsException("ServiceQuotaExceededException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
