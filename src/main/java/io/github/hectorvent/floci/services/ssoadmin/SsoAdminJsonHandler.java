package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ssoadmin.model.Assignment;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class SsoAdminJsonHandler {
    private final SsoAdminService service;
    private final ObjectMapper mapper;

    @Inject
    public SsoAdminJsonHandler(SsoAdminService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String callerAccountId) {
        return switch (action) {
            case "ListInstances" -> listInstances(callerAccountId);
            case "ListPermissionSets" -> listPermissionSets(request);
            case "CreatePermissionSet" -> createPermissionSet(request);
            case "DescribePermissionSet" -> describePermissionSet(request);
            case "UpdatePermissionSet" -> updatePermissionSet(request);
            case "ListManagedPoliciesInPermissionSet" -> listManagedPolicies(request);
            case "AttachManagedPolicyToPermissionSet" -> attachManagedPolicy(request);
            case "DetachManagedPolicyFromPermissionSet" -> detachManagedPolicy(request);
            case "DeleteInlinePolicyFromPermissionSet" -> deleteInlinePolicy(request);
            case "PutInlinePolicyToPermissionSet" -> putInlinePolicy(request);
            case "ListAccountAssignments" -> listAccountAssignments(request);
            case "CreateAccountAssignment" -> createAccountAssignment(request);
            case "DescribeAccountAssignmentCreationStatus" -> describeAssignment(request);
            default -> throw new AwsException("UnknownOperationException", "Operation " + action + " is not supported.", 400);
        };
    }

    private Response listInstances(String callerAccountId) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode instance = response.putArray("Instances").addObject();
        instance.put("InstanceArn", service.getInstanceArn());
        instance.put("IdentityStoreId", service.getIdentityStoreId());
        instance.put("Name", "floci-identity-center");
        instance.put("OwnerAccountId", callerAccountId);
        instance.put("Status", "ACTIVE");
        return Response.ok(response).build();
    }

    private Response listPermissionSets(JsonNode request) {
        var page = service.listPermissionSets(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode arns = response.putArray("PermissionSets");
        page.items().forEach(p -> arns.add(p.arn()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response createPermissionSet(JsonNode request) {
        PermissionSet p = service.createPermissionSet(request);
        ObjectNode response = mapper.createObjectNode();
        response.set("PermissionSet", permissionSetNode(p));
        return Response.ok(response).build();
    }

    private Response describePermissionSet(JsonNode request) {
        PermissionSet p = service.getPermissionSet(
                SsoAdminService.required(request, "InstanceArn"), SsoAdminService.required(request, "PermissionSetArn"));
        ObjectNode response = mapper.createObjectNode();
        response.set("PermissionSet", permissionSetNode(p));
        return Response.ok(response).build();
    }

    private Response updatePermissionSet(JsonNode request) {
        service.updatePermissionSet(request);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response listManagedPolicies(JsonNode request) {
        var page = service.listManagedPolicies(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode policies = response.putArray("AttachedManagedPolicies");
        page.items().forEach(policy -> policies.addObject()
                .put("Arn", policy.getKey()).put("Name", policy.getValue()));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response attachManagedPolicy(JsonNode request) {
        service.attachPolicy(SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"), SsoAdminService.required(request, "ManagedPolicyArn"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response detachManagedPolicy(JsonNode request) {
        service.detachPolicy(SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "PermissionSetArn"), SsoAdminService.required(request, "ManagedPolicyArn"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteInlinePolicy(JsonNode request) {
        service.deleteInlinePolicy(SsoAdminService.required(request, "InstanceArn"), SsoAdminService.required(request, "PermissionSetArn"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response putInlinePolicy(JsonNode request) {
        service.putInlinePolicy(SsoAdminService.required(request, "InstanceArn"), SsoAdminService.required(request, "PermissionSetArn"),
                SsoAdminService.required(request, "InlinePolicy"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response listAccountAssignments(JsonNode request) {
        var page = service.listAssignments(request);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode array = response.putArray("AccountAssignments");
        for (Assignment a : page.items()) {
            array.addObject().put("AccountId", a.accountId()).put("PermissionSetArn", a.permissionSetArn())
                    .put("PrincipalId", a.principalId()).put("PrincipalType", a.principalType());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response createAccountAssignment(JsonNode request) {
        AssignmentOperation op = service.createAssignment(request);
        ObjectNode response = mapper.createObjectNode();
        response.set("AccountAssignmentCreationStatus", assignmentOperationNode(op));
        return Response.ok(response).build();
    }

    private Response describeAssignment(JsonNode request) {
        AssignmentOperation op = service.getAssignmentOperation(
                SsoAdminService.required(request, "InstanceArn"),
                SsoAdminService.required(request, "AccountAssignmentCreationRequestId"));
        ObjectNode response = mapper.createObjectNode();
        response.set("AccountAssignmentCreationStatus", assignmentOperationNode(op));
        return Response.ok(response).build();
    }

    private ObjectNode permissionSetNode(PermissionSet p) {
        ObjectNode node = mapper.createObjectNode();
        node.put("PermissionSetArn", p.arn());
        node.put("Name", p.name());
        if (p.description() != null) {
            node.put("Description", p.description());
        }
        node.put("SessionDuration", p.sessionDuration());
        return node;
    }

    private ObjectNode assignmentOperationNode(AssignmentOperation op) {
        ObjectNode node = mapper.createObjectNode();
        node.put("RequestId", op.requestId()); node.put("Status", op.status());
        node.put("TargetId", op.accountId()); node.put("TargetType", "AWS_ACCOUNT");
        node.put("PermissionSetArn", op.permissionSetArn()); node.put("PrincipalId", op.principalId());
        node.put("PrincipalType", op.principalType());
        if (op.failureReason() != null) {
            node.put("FailureReason", op.failureReason());
        }
        return node;
    }
}
