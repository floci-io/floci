package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CognitoJsonHandler {

    private final CognitoService service;
    private final ObjectMapper objectMapper;

    @Inject
    public CognitoJsonHandler(CognitoService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateUserPool" -> handleCreateUserPool(request, region);
            case "DescribeUserPool" -> handleDescribeUserPool(request);
            case "ListUserPools" -> handleListUserPools(request);
            case "UpdateUserPool" -> handleUpdateUserPool(request, region);
            case "GetUserPoolMfaConfig" -> handleGetUserPoolMfaConfig(request);
            case "DeleteUserPool" -> handleDeleteUserPool(request);
            case "CreateUserPoolClient" -> handleCreateUserPoolClient(request);
            case "DescribeUserPoolClient" -> handleDescribeUserPoolClient(request);
            case "ListUserPoolClients" -> handleListUserPoolClients(request);
            case "DeleteUserPoolClient" -> handleDeleteUserPoolClient(request);
            case "CreateResourceServer" -> handleCreateResourceServer(request);
            case "DescribeResourceServer" -> handleDescribeResourceServer(request);
            case "ListResourceServers" -> handleListResourceServers(request);
            case "UpdateResourceServer" -> handleUpdateResourceServer(request);
            case "DeleteResourceServer" -> handleDeleteResourceServer(request);
            case "AdminCreateUser" -> handleAdminCreateUser(request);
            case "AdminGetUser" -> handleAdminGetUser(request);
            case "AdminDeleteUser" -> handleAdminDeleteUser(request);
            case "AdminSetUserPassword" -> handleAdminSetUserPassword(request);
            case "AdminUpdateUserAttributes" -> handleAdminUpdateUserAttributes(request);
            case "ListUsers" -> handleListUsers(request);
            case "InitiateAuth" -> handleInitiateAuth(request);
            case "AdminInitiateAuth" -> handleAdminInitiateAuth(request);
            case "RespondToAuthChallenge" -> handleRespondToAuthChallenge(request);
            case "SignUp" -> handleSignUp(request);
            case "ConfirmSignUp" -> handleConfirmSignUp(request);
            case "ChangePassword" -> handleChangePassword(request);
            case "ForgotPassword" -> handleForgotPassword(request);
            case "ConfirmForgotPassword" -> handleConfirmForgotPassword(request);
            case "GetUser" -> handleGetUser(request);
            case "UpdateUserAttributes" -> handleUpdateUserAttributes(request);
            case "CreateGroup" -> handleCreateGroup(request);
            case "GetGroup" -> handleGetGroup(request);
            case "ListGroups" -> handleListGroups(request);
            case "DeleteGroup" -> handleDeleteGroup(request);
            case "AdminAddUserToGroup" -> handleAdminAddUserToGroup(request);
            case "AdminRemoveUserFromGroup" -> handleAdminRemoveUserFromGroup(request);
            case "AdminListGroupsForUser" -> handleAdminListGroupsForUser(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateUserPool(JsonNode request, String region) {
        @SuppressWarnings("unchecked")
        Map<String, Object> reqMap = objectMapper.convertValue(request, Map.class);
        UserPool pool = service.createUserPool(reqMap, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("UserPool", userPoolToFullNode(pool));
        return Response.ok(response).build();
    }

    private Response handleDescribeUserPool(JsonNode request) {
        UserPool pool = service.describeUserPool(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("UserPool", userPoolToFullNode(pool));
        return Response.ok(response).build();
    }

    private Response handleListUserPools(JsonNode request) {
        List<UserPool> pools = service.listUserPools();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("UserPools");
        pools.forEach(p -> items.add(userPoolToDescriptionNode(p)));
        return Response.ok(response).build();
    }

    private Response handleUpdateUserPool(JsonNode request, String region) {
        @SuppressWarnings("unchecked")
        Map<String, Object> reqMap = objectMapper.convertValue(request, Map.class);
        UserPool pool = service.updateUserPool(reqMap, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("UserPool", userPoolToFullNode(pool));
        return Response.ok(response).build();
    }

    private Response handleGetUserPoolMfaConfig(JsonNode request) {
        UserPool pool = service.describeUserPool(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MfaConfiguration", pool.getMfaConfiguration());
        return Response.ok(response).build();
    }

    private Response handleDeleteUserPool(JsonNode request) {
        service.deleteUserPool(request.path("UserPoolId").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateUserPoolClient(JsonNode request) {
        UserPoolClient client = service.createUserPoolClient(
                request.path("UserPoolId").asText(),
                request.path("ClientName").asText(),
                request.path("GenerateSecret").asBoolean(false),
                request.path("AllowedOAuthFlowsUserPoolClient").asBoolean(false),
                readStringList(request.path("AllowedOAuthFlows")),
                readStringList(request.path("AllowedOAuthScopes"))
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("UserPoolClient", clientToNode(client));
        return Response.ok(response).build();
    }

    private Response handleDescribeUserPoolClient(JsonNode request) {
        UserPoolClient client = service.describeUserPoolClient(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("UserPoolClient", clientToNode(client));
        return Response.ok(response).build();
    }

    private Response handleListUserPoolClients(JsonNode request) {
        List<UserPoolClient> clients = service.listUserPoolClients(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("UserPoolClients");
        clients.forEach(c -> items.add(clientToNode(c)));
        return Response.ok(response).build();
    }

    private Response handleDeleteUserPoolClient(JsonNode request) {
        service.deleteUserPoolClient(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateResourceServer(JsonNode request) {
        ResourceServer server = service.createResourceServer(
                request.path("UserPoolId").asText(),
                request.path("Identifier").asText(),
                request.path("Name").asText(),
                parseScopes(request.path("Scopes"))
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResourceServer", resourceServerToNode(server));
        return Response.ok(response).build();
    }

    private Response handleDescribeResourceServer(JsonNode request) {
        ResourceServer server = service.describeResourceServer(
                request.path("UserPoolId").asText(),
                request.path("Identifier").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResourceServer", resourceServerToNode(server));
        return Response.ok(response).build();
    }

    private Response handleListResourceServers(JsonNode request) {
        List<ResourceServer> servers = service.listResourceServers(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("ResourceServers");
        servers.forEach(server -> items.add(resourceServerToNode(server)));
        return Response.ok(response).build();
    }

    private Response handleUpdateResourceServer(JsonNode request) {
        ResourceServer server = service.updateResourceServer(
                request.path("UserPoolId").asText(),
                request.path("Identifier").asText(),
                request.path("Name").asText(),
                parseScopes(request.path("Scopes"))
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ResourceServer", resourceServerToNode(server));
        return Response.ok(response).build();
    }

    private Response handleDeleteResourceServer(JsonNode request) {
        service.deleteResourceServer(
                request.path("UserPoolId").asText(),
                request.path("Identifier").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminCreateUser(JsonNode request) {
        Map<String, String> attrs = new HashMap<>();
        request.path("UserAttributes").forEach(a -> attrs.put(a.path("Name").asText(), a.path("Value").asText()));
        String tempPassword = request.path("TemporaryPassword").isMissingNode() ? null
                : request.path("TemporaryPassword").asText(null);

        CognitoUser user = service.adminCreateUser(
                request.path("UserPoolId").asText(),
                request.path("Username").asText(),
                attrs,
                tempPassword
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("User", userToNode(user));
        return Response.ok(response).build();
    }

    private Response handleAdminGetUser(JsonNode request) {
        CognitoUser user = service.adminGetUser(
                request.path("UserPoolId").asText(),
                request.path("Username").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Username", user.getUsername());
        response.put("UserStatus", user.getUserStatus());
        response.put("Enabled", user.isEnabled());
        response.put("UserCreateDate", user.getCreationDate());
        response.put("UserLastModifiedDate", user.getLastModifiedDate());
        ArrayNode attrs = response.putArray("UserAttributes");
        user.getAttributes().forEach((k, v) -> {
            ObjectNode attr = attrs.addObject();
            attr.put("Name", k);
            attr.put("Value", v);
        });
        return Response.ok(response).build();
    }

    private Response handleAdminDeleteUser(JsonNode request) {
        service.adminDeleteUser(request.path("UserPoolId").asText(), request.path("Username").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminSetUserPassword(JsonNode request) {
        service.adminSetUserPassword(
                request.path("UserPoolId").asText(),
                request.path("Username").asText(),
                request.path("Password").asText(),
                request.path("Permanent").asBoolean(true)
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminUpdateUserAttributes(JsonNode request) {
        Map<String, String> attrs = new HashMap<>();
        request.path("UserAttributes").forEach(a -> attrs.put(a.path("Name").asText(), a.path("Value").asText()));
        service.adminUpdateUserAttributes(
                request.path("UserPoolId").asText(),
                request.path("Username").asText(),
                attrs
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListUsers(JsonNode request) {
        List<CognitoUser> users = service.listUsers(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Users");
        users.forEach(u -> items.add(userToNode(u)));
        return Response.ok(response).build();
    }

    private Response handleInitiateAuth(JsonNode request) {
        Map<String, String> params = new HashMap<>();
        request.path("AuthParameters").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));

        Map<String, Object> result = service.initiateAuth(
                request.path("ClientId").asText(),
                request.path("AuthFlow").asText(),
                params
        );
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleAdminInitiateAuth(JsonNode request) {
        Map<String, String> params = new HashMap<>();
        request.path("AuthParameters").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));

        Map<String, Object> result = service.adminInitiateAuth(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText(),
                request.path("AuthFlow").asText(),
                params
        );
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleRespondToAuthChallenge(JsonNode request) {
        Map<String, String> responses = new HashMap<>();
        request.path("ChallengeResponses").fields().forEachRemaining(e -> responses.put(e.getKey(), e.getValue().asText()));

        Map<String, Object> result = service.respondToAuthChallenge(
                request.path("ClientId").asText(),
                request.path("ChallengeName").asText(),
                request.path("Session").asText(null),
                responses
        );
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleSignUp(JsonNode request) {
        Map<String, String> attrs = new HashMap<>();
        request.path("UserAttributes").forEach(a -> attrs.put(a.path("Name").asText(), a.path("Value").asText()));

        CognitoUser user = service.signUp(
                request.path("ClientId").asText(),
                request.path("Username").asText(),
                request.path("Password").asText(),
                attrs
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.put("UserConfirmed", "CONFIRMED".equals(user.getUserStatus()));
        response.put("UserSub", user.getUsername());
        ObjectNode delivery = response.putObject("CodeDeliveryDetails");
        delivery.put("AttributeName", "email");
        delivery.put("DeliveryMedium", "EMAIL");
        delivery.put("Destination", user.getAttributes().getOrDefault("email", "****"));
        return Response.ok(response).build();
    }

    private Response handleConfirmSignUp(JsonNode request) {
        service.confirmSignUp(
                request.path("ClientId").asText(),
                request.path("Username").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleChangePassword(JsonNode request) {
        service.changePassword(
                request.path("AccessToken").asText(),
                request.path("PreviousPassword").asText(),
                request.path("ProposedPassword").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleForgotPassword(JsonNode request) {
        service.forgotPassword(
                request.path("ClientId").asText(),
                request.path("Username").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode delivery = response.putObject("CodeDeliveryDetails");
        delivery.put("AttributeName", "email");
        delivery.put("DeliveryMedium", "EMAIL");
        delivery.put("Destination", "****");
        return Response.ok(response).build();
    }

    private Response handleConfirmForgotPassword(JsonNode request) {
        service.confirmForgotPassword(
                request.path("ClientId").asText(),
                request.path("Username").asText(),
                request.path("ConfirmationCode").asText(),
                request.path("Password").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetUser(JsonNode request) {
        Map<String, Object> result = service.getUser(request.path("AccessToken").asText());
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleUpdateUserAttributes(JsonNode request) {
        Map<String, String> attrs = new HashMap<>();
        request.path("UserAttributes").forEach(a -> attrs.put(a.path("Name").asText(), a.path("Value").asText()));
        service.updateUserAttributes(request.path("AccessToken").asText(), attrs);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("CodeDeliveryDetailsList");
        return Response.ok(response).build();
    }

    private ObjectNode userPoolToDescriptionNode(UserPool p) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", p.getId());
        node.put("Name", p.getName());
        node.set("LambdaConfig", objectMapper.valueToTree(p.getLambdaConfig() != null ? p.getLambdaConfig() : new HashMap<>()));
        node.put("Status", p.getStatus());
        node.put("LastModifiedDate", (double) p.getLastModifiedDate());
        node.put("CreationDate", (double) p.getCreationDate());
        return node;
    }

    private ObjectNode userPoolToFullNode(UserPool p) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", p.getId());
        node.put("Name", p.getName());
        node.put("Arn", p.getArn());
        node.put("Status", p.getStatus());
        node.put("CreationDate", (double) p.getCreationDate());
        node.put("LastModifiedDate", (double) p.getLastModifiedDate());

        node.set("Policies", objectMapper.valueToTree(p.getPolicies() != null ? p.getPolicies() : new HashMap<>()));
        node.put("DeletionProtection", p.getDeletionProtection() != null ? p.getDeletionProtection() : "INACTIVE");
        node.set("LambdaConfig", objectMapper.valueToTree(p.getLambdaConfig() != null ? p.getLambdaConfig() : new HashMap<>()));
        node.set("SchemaAttributes", objectMapper.valueToTree(p.getSchemaAttributes() != null ? p.getSchemaAttributes() : new java.util.ArrayList<>()));
        node.set("AutoVerifiedAttributes", objectMapper.valueToTree(p.getAutoVerifiedAttributes() != null ? p.getAutoVerifiedAttributes() : new java.util.ArrayList<>()));
        node.set("AliasAttributes", objectMapper.valueToTree(p.getAliasAttributes() != null ? p.getAliasAttributes() : new java.util.ArrayList<>()));
        node.set("UsernameAttributes", objectMapper.valueToTree(p.getUsernameAttributes() != null ? p.getUsernameAttributes() : new java.util.ArrayList<>()));
        
        if (p.getSmsVerificationMessage() != null) node.put("SmsVerificationMessage", p.getSmsVerificationMessage());
        if (p.getEmailVerificationMessage() != null) node.put("EmailVerificationMessage", p.getEmailVerificationMessage());
        if (p.getEmailVerificationSubject() != null) node.put("EmailVerificationSubject", p.getEmailVerificationSubject());
        
        node.set("VerificationMessageTemplate", objectMapper.valueToTree(p.getVerificationMessageTemplate() != null ? p.getVerificationMessageTemplate() : new HashMap<>()));
        
        if (p.getSmsAuthenticationMessage() != null) node.put("SmsAuthenticationMessage", p.getSmsAuthenticationMessage());
        
        node.put("MfaConfiguration", p.getMfaConfiguration() != null ? p.getMfaConfiguration() : "OFF");
        node.set("DeviceConfiguration", objectMapper.valueToTree(p.getDeviceConfiguration() != null ? p.getDeviceConfiguration() : new HashMap<>()));
        node.put("EstimatedNumberOfUsers", p.getEstimatedNumberOfUsers());
        node.set("EmailConfiguration", objectMapper.valueToTree(p.getEmailConfiguration() != null ? p.getEmailConfiguration() : new HashMap<>()));
        node.set("SmsConfiguration", objectMapper.valueToTree(p.getSmsConfiguration() != null ? p.getSmsConfiguration() : new HashMap<>()));
        node.set("UserPoolTags", objectMapper.valueToTree(p.getUserPoolTags() != null ? p.getUserPoolTags() : new HashMap<>()));
        node.set("AdminCreateUserConfig", objectMapper.valueToTree(p.getAdminCreateUserConfig() != null ? p.getAdminCreateUserConfig() : new HashMap<>()));
        node.set("UserPoolAddOns", objectMapper.valueToTree(p.getUserPoolAddOns() != null ? p.getUserPoolAddOns() : new HashMap<>()));
        node.set("UsernameConfiguration", objectMapper.valueToTree(p.getUsernameConfiguration() != null ? p.getUsernameConfiguration() : new HashMap<>()));
        node.set("AccountRecoverySetting", objectMapper.valueToTree(p.getAccountRecoverySetting() != null ? p.getAccountRecoverySetting() : new HashMap<>()));
        node.put("UserPoolTier", p.getUserPoolTier() != null ? p.getUserPoolTier() : "ESSENTIALS");

        return node;
    }

    private ObjectNode clientToNode(UserPoolClient c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClientId", c.getClientId());
        node.put("UserPoolId", c.getUserPoolId());
        node.put("ClientName", c.getClientName());
        if (c.getClientSecret() != null) {
            node.put("ClientSecret", c.getClientSecret());
        }
        node.put("GenerateSecret", c.isGenerateSecret());
        node.put("AllowedOAuthFlowsUserPoolClient", c.isAllowedOAuthFlowsUserPoolClient());
        ArrayNode flows = node.putArray("AllowedOAuthFlows");
        c.getAllowedOAuthFlows().forEach(flows::add);
        ArrayNode scopes = node.putArray("AllowedOAuthScopes");
        c.getAllowedOAuthScopes().forEach(scopes::add);
        node.put("CreationDate", c.getCreationDate());
        node.put("LastModifiedDate", c.getLastModifiedDate());
        return node;
    }

    private ObjectNode resourceServerToNode(ResourceServer server) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("UserPoolId", server.getUserPoolId());
        node.put("Identifier", server.getIdentifier());
        node.put("Name", server.getName());
        node.put("CreationDate", server.getCreationDate());
        node.put("LastModifiedDate", server.getLastModifiedDate());
        ArrayNode scopes = node.putArray("Scopes");
        for (ResourceServerScope scope : server.getScopes()) {
            ObjectNode item = scopes.addObject();
            item.put("ScopeName", scope.getScopeName());
            if (scope.getScopeDescription() != null) {
                item.put("ScopeDescription", scope.getScopeDescription());
            }
        }
        return node;
    }

    private List<ResourceServerScope> parseScopes(JsonNode scopesNode) {
        if (scopesNode == null || !scopesNode.isArray()) {
            return List.of();
        }

        List<ResourceServerScope> scopes = new java.util.ArrayList<>();
        scopesNode.forEach(item -> {
            ResourceServerScope scope = new ResourceServerScope();
            scope.setScopeName(item.path("ScopeName").asText());
            scope.setScopeDescription(item.path("ScopeDescription").asText(null));
            scopes.add(scope);
        });
        return scopes;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private ObjectNode userToNode(CognitoUser u) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Username", u.getUsername());
        node.put("UserStatus", u.getUserStatus());
        node.put("Enabled", u.isEnabled());
        node.put("UserCreateDate", u.getCreationDate());
        node.put("UserLastModifiedDate", u.getLastModifiedDate());
        ArrayNode attrs = node.putArray("Attributes");
        u.getAttributes().forEach((k, v) -> {
            ObjectNode attr = attrs.addObject();
            attr.put("Name", k);
            attr.put("Value", v);
        });
        return node;
    }

    private Response handleCreateGroup(JsonNode request) {
        String userPoolId = request.path("UserPoolId").asText();
        String groupName = request.path("GroupName").asText();
        String description = request.path("Description").asText(null);
        JsonNode precNode = request.path("Precedence");
        Integer precedence = precNode.isMissingNode() || precNode.isNull() ? null : precNode.asInt();
        String roleArn = request.path("RoleArn").asText(null);
        CognitoGroup group = service.createGroup(userPoolId, groupName, description, precedence, roleArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Group", groupToNode(group));
        return Response.ok(response).build();
    }

    private Response handleGetGroup(JsonNode request) {
        CognitoGroup group = service.getGroup(
                request.path("UserPoolId").asText(),
                request.path("GroupName").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Group", groupToNode(group));
        return Response.ok(response).build();
    }

    private Response handleListGroups(JsonNode request) {
        List<CognitoGroup> groups = service.listGroups(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Groups");
        groups.forEach(g -> items.add(groupToNode(g)));
        return Response.ok(response).build();
    }

    private Response handleDeleteGroup(JsonNode request) {
        service.deleteGroup(
                request.path("UserPoolId").asText(),
                request.path("GroupName").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminAddUserToGroup(JsonNode request) {
        service.adminAddUserToGroup(
                request.path("UserPoolId").asText(),
                request.path("GroupName").asText(),
                request.path("Username").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminRemoveUserFromGroup(JsonNode request) {
        service.adminRemoveUserFromGroup(
                request.path("UserPoolId").asText(),
                request.path("GroupName").asText(),
                request.path("Username").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminListGroupsForUser(JsonNode request) {
        List<CognitoGroup> groups = service.adminListGroupsForUser(
                request.path("UserPoolId").asText(),
                request.path("Username").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Groups");
        groups.forEach(g -> items.add(groupToNode(g)));
        return Response.ok(response).build();
    }

    private ObjectNode groupToNode(CognitoGroup g) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("GroupName", g.getGroupName());
        node.put("UserPoolId", g.getUserPoolId());
        if (g.getDescription() != null) node.put("Description", g.getDescription());
        if (g.getPrecedence() != null) node.put("Precedence", g.getPrecedence());
        if (g.getRoleArn() != null) node.put("RoleArn", g.getRoleArn());
        node.put("CreationDate", g.getCreationDate());
        node.put("LastModifiedDate", g.getLastModifiedDate());
        return node;
    }

}
