package io.github.hectorvent.floci.services.identitystore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.identitystore.model.Group;
import io.github.hectorvent.floci.services.identitystore.model.Membership;
import io.github.hectorvent.floci.services.identitystore.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class IdentityStoreJsonHandler {
    private static final String ENTERPRISE_EXTENSION = "aws:identitystore:enterprise";

    private final IdentityStoreService service;
    private final ObjectMapper mapper;

    @Inject
    public IdentityStoreJsonHandler(IdentityStoreService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request) {
        return switch (action) {
            case "CreateGroup" -> createGroup(request);
            case "DeleteGroup" -> emptyAfter(() -> service.deleteGroup(request));
            case "DescribeGroup" -> Response.ok(groupDetails(service.describeGroup(request))).build();
            case "GetGroupId" -> getGroupId(request);
            case "ListGroups" -> listGroups(request);
            case "UpdateGroup" -> emptyAfter(() -> service.updateGroup(request));
            case "CreateUser" -> createUser(request);
            case "DeleteUser" -> emptyAfter(() -> service.deleteUser(request));
            case "DescribeUser" -> describeUser(request);
            case "GetUserId" -> getUserId(request);
            case "ListUsers" -> listUsers(request);
            case "UpdateUser" -> emptyAfter(() -> service.updateUser(request));
            case "CreateGroupMembership" -> createGroupMembership(request);
            case "DeleteGroupMembership" -> emptyAfter(() -> service.deleteMembership(request));
            case "DescribeGroupMembership" -> Response.ok(membershipDetails(service.describeMembership(request))).build();
            case "GetGroupMembershipId" -> getGroupMembershipId(request);
            case "IsMemberInGroups" -> isMemberInGroups(request);
            case "ListGroupMemberships" -> listGroupMemberships(request);
            case "ListGroupMembershipsForMember" -> listGroupMembershipsForMember(request);
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response createGroup(JsonNode request) {
        Group group = service.createGroup(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("GroupId", group.groupId());
        out.put("IdentityStoreId", group.identityStoreId());
        return Response.ok(out).build();
    }

    private Response listGroups(JsonNode request) {
        var page = service.listGroups(request);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray("Groups");
        for (Group group : page.items()) {
            array.add(groupDetails(group));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    private Response getGroupId(JsonNode request) {
        Group group = service.getGroupId(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("GroupId", group.groupId());
        out.put("IdentityStoreId", group.identityStoreId());
        return Response.ok(out).build();
    }

    private Response createUser(JsonNode request) {
        User user = service.createUser(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("UserId", user.userId());
        out.put("IdentityStoreId", user.identityStoreId());
        return Response.ok(out).build();
    }

    private Response describeUser(JsonNode request) {
        boolean includeExtensions = includeExtensions(request);
        return Response.ok(userDetails(service.describeUser(request), includeExtensions)).build();
    }

    private Response listUsers(JsonNode request) {
        boolean includeExtensions = includeExtensions(request);
        var page = service.listUsers(request);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray("Users");
        for (User user : page.items()) {
            array.add(userDetails(user, includeExtensions));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    private Response getUserId(JsonNode request) {
        User user = service.getUserId(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("UserId", user.userId());
        out.put("IdentityStoreId", user.identityStoreId());
        return Response.ok(out).build();
    }

    private Response createGroupMembership(JsonNode request) {
        Membership membership = service.createMembership(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("MembershipId", membership.membershipId());
        out.put("IdentityStoreId", membership.identityStoreId());
        return Response.ok(out).build();
    }

    private Response getGroupMembershipId(JsonNode request) {
        Membership membership = service.getMembershipId(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("MembershipId", membership.membershipId());
        out.put("IdentityStoreId", membership.identityStoreId());
        return Response.ok(out).build();
    }

    private Response listGroupMemberships(JsonNode request) {
        var page = service.listGroupMemberships(request);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode result = out.putArray("GroupMemberships");
        for (Membership membership : page.items()) {
            result.add(membershipDetails(membership));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    private Response listGroupMembershipsForMember(JsonNode request) {
        var page = service.listGroupMembershipsForMember(request);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode result = out.putArray("GroupMemberships");
        for (Membership membership : page.items()) {
            result.add(membershipDetails(membership));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    private Response isMemberInGroups(JsonNode request) {
        String storeId = IdentityStoreService.required(request, "IdentityStoreId");
        String userId = IdentityStoreService.memberUserId(request.get("MemberId"));
        var groupIds = service.validateGroupIds(request.get("GroupIds"));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode results = out.putArray("Results");
        for (String groupId : groupIds) {
            ObjectNode result = results.addObject();
            result.put("GroupId", groupId);
            result.putObject("MemberId").put("UserId", userId);
            result.put("MembershipExists", service.isMember(storeId, userId, groupId));
        }
        return Response.ok(out).build();
    }

    private ObjectNode groupSummary(Group group) {
        ObjectNode out = mapper.createObjectNode();
        out.put("GroupId", group.groupId());
        out.put("IdentityStoreId", group.identityStoreId());
        copyIfPresent(group.attributes(), out, "DisplayName");
        copyIfPresent(group.attributes(), out, "Description");
        copyIfPresent(group.attributes(), out, "ExternalIds");
        return out;
    }

    private ObjectNode groupDetails(Group group) {
        ObjectNode out = groupSummary(group);
        putTimestamp(out, "CreatedAt", group.createdAt());
        putTimestamp(out, "UpdatedAt", group.updatedAt());
        return out;
    }

    private ObjectNode userDetails(User user, boolean includeExtensions) {
        ObjectNode out = mapper.createObjectNode();
        out.put("UserId", user.userId());
        out.put("IdentityStoreId", user.identityStoreId());
        user.attributes().fields().forEachRemaining(entry -> {
            if (!"Extensions".equals(entry.getKey()) || includeExtensions) {
                out.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        putTimestamp(out, "CreatedAt", user.createdAt());
        putTimestamp(out, "UpdatedAt", user.updatedAt());
        return out;
    }

    private ObjectNode membershipDetails(Membership membership) {
        ObjectNode out = mapper.createObjectNode();
        out.put("IdentityStoreId", membership.identityStoreId());
        out.put("MembershipId", membership.membershipId());
        out.put("GroupId", membership.groupId());
        out.putObject("MemberId").put("UserId", membership.userId());
        putTimestamp(out, "CreatedAt", membership.createdAt());
        putTimestamp(out, "UpdatedAt", membership.updatedAt());
        return out;
    }

    private static boolean includeExtensions(JsonNode request) {
        JsonNode extensions = request == null ? null : request.get("Extensions");
        if (extensions == null || extensions.isNull()) {
            return false;
        }
        if (!extensions.isArray() || extensions.size() < 1 || extensions.size() > 10) {
            throw validation("Extensions must contain between 1 and 10 extension names.");
        }
        for (JsonNode extension : extensions) {
            if (!extension.isTextual() || !ENTERPRISE_EXTENSION.equals(extension.textValue())) {
                throw validation("Only aws:identitystore:enterprise is supported in Extensions.");
            }
        }
        return true;
    }

    private static void copyIfPresent(ObjectNode source, ObjectNode target, String field) {
        if (source != null && source.has(field) && !source.get(field).isNull()) {
            target.set(field, source.get(field).deepCopy());
        }
    }

    private static void putTimestamp(ObjectNode target, String field, String timestamp) {
        if (timestamp != null) {
            var instant = java.time.Instant.parse(timestamp);
            double unixTimestamp = instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
            target.put(field, unixTimestamp);
        }
    }

    private Response emptyAfter(Runnable action) {
        action.run();
        return Response.ok(mapper.createObjectNode()).build();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
