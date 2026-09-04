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
    private final IdentityStoreService service;
    private final ObjectMapper mapper;

    @Inject
    public IdentityStoreJsonHandler(IdentityStoreService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request) {
        return switch (action) {
            case "ListGroups" -> listGroups(request);
            case "CreateGroup" -> createGroup(request);
            case "ListUsers" -> listUsers(request);
            case "CreateUser" -> createUser(request);
            case "IsMemberInGroups" -> isMemberInGroups(request);
            case "CreateGroupMembership" -> createGroupMembership(request);
            default -> throw new AwsException("UnknownOperationException", "Operation " + action + " is not supported.", 400);
        };
    }

    private Response listGroups(JsonNode request) {
        var page = service.listGroups(request);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray("Groups");
        for (Group group : page.items()) {
            ObjectNode node = array.addObject();
            node.put("GroupId", group.groupId());
            node.put("IdentityStoreId", group.identityStoreId());
            node.put("DisplayName", group.displayName());
            if (group.description() != null) node.put("Description", group.description());
        }
        if (page.nextToken() != null) out.put("NextToken", page.nextToken());
        return Response.ok(out).build();
    }

    private Response createGroup(JsonNode request) {
        Group group = service.createGroup(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("GroupId", group.groupId());
        out.put("IdentityStoreId", group.identityStoreId());
        return Response.ok(out).build();
    }

    private Response listUsers(JsonNode request) {
        var page = service.listUsers(request);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray("Users");
        for (User user : page.items()) {
            ObjectNode node = array.addObject();
            node.put("UserId", user.userId());
            node.put("IdentityStoreId", user.identityStoreId());
            node.put("UserName", user.userName());
            if (user.displayName() != null) node.put("DisplayName", user.displayName());
        }
        if (page.nextToken() != null) out.put("NextToken", page.nextToken());
        return Response.ok(out).build();
    }

    private Response createUser(JsonNode request) {
        User user = service.createUser(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("UserId", user.userId());
        out.put("IdentityStoreId", user.identityStoreId());
        return Response.ok(out).build();
    }

    private Response isMemberInGroups(JsonNode request) {
        String store = IdentityStoreService.required(request, "IdentityStoreId");
        String user = IdentityStoreService.memberUserId(request.get("MemberId"));
        var groupIds = service.validateGroupIds(request.get("GroupIds"));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode results = out.putArray("Results");
        for (String group : groupIds) {
            ObjectNode result = results.addObject();
            result.put("GroupId", group);
            result.putObject("MemberId").put("UserId", user);
            result.put("MembershipExists", service.isMember(store, user, group));
        }
        return Response.ok(out).build();
    }

    private Response createGroupMembership(JsonNode request) {
        Membership membership = service.createMembership(request);
        ObjectNode out = mapper.createObjectNode();
        out.put("MembershipId", membership.membershipId());
        out.put("IdentityStoreId", membership.identityStoreId());
        return Response.ok(out).build();
    }
}
