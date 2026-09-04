package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClientSecret;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import io.github.hectorvent.floci.services.cognito.model.ManagedLoginBranding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            case "AddCustomAttributes" -> handleAddCustomAttributes(request);
            case "TagResource" -> handleTagResource(request);
            case "UntagResource" -> handleUntagResource(request);
            case "ListTagsForResource" -> handleListTagsForResource(request);
            case "GetUserPoolMfaConfig" -> handleGetUserPoolMfaConfig(request);
            case "SetUserPoolMfaConfig" -> handleSetUserPoolMfaConfig(request);
            case "DeleteUserPool" -> handleDeleteUserPool(request);
            case "CreateUserPoolClient" -> handleCreateUserPoolClient(request);
            case "DescribeUserPoolClient" -> handleDescribeUserPoolClient(request);
            case "ListUserPoolClients" -> handleListUserPoolClients(request);
            case "DeleteUserPoolClient" -> handleDeleteUserPoolClient(request);
            case "UpdateUserPoolClient" -> handleUpdateUserPoolClient(request);
            case "CreateResourceServer" -> handleCreateResourceServer(request);
            case "DescribeResourceServer" -> handleDescribeResourceServer(request);
            case "ListResourceServers" -> handleListResourceServers(request);
            case "UpdateResourceServer" -> handleUpdateResourceServer(request);
            case "DeleteResourceServer" -> handleDeleteResourceServer(request);
            case "CreateIdentityProvider" -> handleCreateIdentityProvider(request);
            case "DescribeIdentityProvider" -> handleDescribeIdentityProvider(request);
            case "ListIdentityProviders" -> handleListIdentityProviders(request);
            case "UpdateIdentityProvider" -> handleUpdateIdentityProvider(request);
            case "DeleteIdentityProvider" -> handleDeleteIdentityProvider(request);
            case "CreateUserPoolDomain" -> handleCreateUserPoolDomain(request);
            case "DescribeUserPoolDomain" -> handleDescribeUserPoolDomain(request);
            case "UpdateUserPoolDomain" -> handleUpdateUserPoolDomain(request);
            case "DeleteUserPoolDomain" -> handleDeleteUserPoolDomain(request);
            case "SetLogDeliveryConfiguration" -> handleSetLogDeliveryConfiguration(request);
            case "GetLogDeliveryConfiguration" -> handleGetLogDeliveryConfiguration(request);
            case "AdminResetUserPassword" -> handleAdminResetUserPassword(request);
            case "AdminCreateUser" -> handleAdminCreateUser(request);
            case "AdminGetUser" -> handleAdminGetUser(request);
            case "AdminDeleteUser" -> handleAdminDeleteUser(request);
            case "AdminSetUserPassword" -> handleAdminSetUserPassword(request);
            case "AdminUpdateUserAttributes" -> handleAdminUpdateUserAttributes(request);
            case "AdminDeleteUserAttributes" -> handleAdminDeleteUserAttributes(request);
            case "AdminUserGlobalSignOut" -> handleAdminUserGlobalSignOut(request);
            case "AdminEnableUser" -> handleAdminEnableUser(request);
            case "AdminDisableUser" -> handleAdminDisableUser(request);
            case "AdminLinkProviderForUser" -> handleAdminLinkProviderForUser(request);
            case "CreateManagedLoginBranding" -> handleCreateManagedLoginBranding(request);
            case "DescribeManagedLoginBranding" -> handleDescribeManagedLoginBranding(request);
            case "DescribeManagedLoginBrandingByClient" -> handleDescribeManagedLoginBrandingByClient(request);
            case "UpdateManagedLoginBranding" -> handleUpdateManagedLoginBranding(request);
            case "DeleteManagedLoginBranding" -> handleDeleteManagedLoginBranding(request);
            case "ListUsers" -> handleListUsers(request);
            case "InitiateAuth" -> handleInitiateAuth(request);
            case "AdminInitiateAuth" -> handleAdminInitiateAuth(request);
            case "RespondToAuthChallenge" -> handleRespondToAuthChallenge(request);
            case "AdminRespondToAuthChallenge" -> handleAdminRespondToAuthChallenge(request);
            case "SignUp" -> handleSignUp(request);
            case "ConfirmSignUp" -> handleConfirmSignUp(request);
            case "ResendConfirmationCode" -> handleResendConfirmationCode(request);
            case "AdminConfirmSignUp" -> handleAdminConfirmSignUp(request);
            case "ChangePassword" -> handleChangePassword(request);
            case "ForgotPassword" -> handleForgotPassword(request);
            case "ConfirmForgotPassword" -> handleConfirmForgotPassword(request);
            case "GetUser" -> handleGetUser(request);
            case "GetUserAttributeVerificationCode" -> handleGetUserAttributeVerificationCode(request);
            case "UpdateUserAttributes" -> handleUpdateUserAttributes(request);
            case "DeleteUserAttributes" -> handleDeleteUserAttributes(request);
            case "GlobalSignOut" -> handleGlobalSignOut(request);
            case "CreateGroup" -> handleCreateGroup(request);
            case "GetGroup" -> handleGetGroup(request);
            case "ListGroups" -> handleListGroups(request);
            case "DeleteGroup" -> handleDeleteGroup(request);
            case "UpdateGroup" -> handleUpdateGroup(request);
            case "ListUsersInGroup" -> handleListUsersInGroup(request);
            case "AdminAddUserToGroup" -> handleAdminAddUserToGroup(request);
            case "AdminRemoveUserFromGroup" -> handleAdminRemoveUserFromGroup(request);
            case "AdminListGroupsForUser" -> handleAdminListGroupsForUser(request);
            case "GetTokensFromRefreshToken" -> handleGetTokensFromRefreshToken(request);
            case "RevokeToken" -> handleRevokeToken(request);
            case "ListUserPoolClientSecrets" -> handleListUserPoolClientSecrets(request);
            case "AddUserPoolClientSecret" -> handleAddUserPoolClientSecret(request);
            case "DeleteUserPoolClientSecret" -> handleDeleteUserPoolClientSecret(request);
            case "AdminSetUserMFAPreference" -> handleAdminSetUserMFAPreference(request);
            case "SetUserMFAPreference" -> handleSetUserMFAPreference(request);
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

    private Response handleAddCustomAttributes(JsonNode request) {
        String userPoolId = request.path("UserPoolId").asText();
        List<Map<String, Object>> customAttributes = new java.util.ArrayList<>();
        JsonNode attrsNode = request.path("CustomAttributes");
        if (attrsNode.isArray()) {
            for (JsonNode attrNode : attrsNode) {
                Map<String, Object> attr = objectMapper.convertValue(attrNode, new TypeReference<Map<String, Object>>() {});
                customAttributes.add(attr);
            }
        }
        if (customAttributes.isEmpty() || customAttributes.size() > 25) {
            throw new AwsException("InvalidParameterException", "CustomAttributes list size must be between 1 and 25.", 400);
        }
        service.addCustomAttributes(userPoolId, customAttributes);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleTagResource(JsonNode request) {
        @SuppressWarnings("unchecked")
        Map<String, String> tags = objectMapper.convertValue(request.path("Tags"), Map.class);
        service.tagResource(request.path("ResourceArn").asText(), tags);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        service.untagResource(request.path("ResourceArn").asText(), readStringList(request.path("TagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", objectMapper.valueToTree(service.listTagsForResource(request.path("ResourceArn").asText())));
        return Response.ok(response).build();
    }

    private Response handleGetUserPoolMfaConfig(JsonNode request) {
        UserPool pool = service.describeUserPool(request.path("UserPoolId").asText());
        return Response.ok(buildMfaConfigResponse(pool)).build();
    }

    private Response handleSetUserPoolMfaConfig(JsonNode request) {
        JsonNode softwareToken = request.path("SoftwareTokenMfaConfiguration");
        boolean otherFactorConfigured = request.hasNonNull("EmailMfaConfiguration")
                || request.hasNonNull("SmsMfaConfiguration");
        UserPool pool = service.setUserPoolMfaConfig(
                request.path("UserPoolId").asText(),
                request.hasNonNull("MfaConfiguration") ? request.path("MfaConfiguration").asText() : null,
                softwareToken.hasNonNull("Enabled") ? softwareToken.path("Enabled").asBoolean() : null,
                otherFactorConfigured);
        return Response.ok(buildMfaConfigResponse(pool)).build();
    }

    /**
     * Shared by Get and Set, which answer with the same members. SoftwareTokenMfaConfiguration
     * is omitted while unset, matching the live service, which returns only the factors that
     * have been configured.
     */
    private ObjectNode buildMfaConfigResponse(UserPool pool) {
        ObjectNode response = objectMapper.createObjectNode();
        if (pool.getSoftwareTokenMfaEnabled() != null) {
            response.putObject("SoftwareTokenMfaConfiguration")
                    .put("Enabled", pool.getSoftwareTokenMfaEnabled());
        }
        response.put("MfaConfiguration", pool.getMfaConfiguration());
        return response;
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
                readStringList(request.path("AllowedOAuthScopes")),
                request.path("AnalyticsConfiguration").isObject()
                        ? objectMapper.convertValue(request.path("AnalyticsConfiguration"),
                                new TypeReference<Map<String, Object>>() {})
                        : null,
                readStringList(request.path("CallbackURLs")),
                request.path("DefaultRedirectURI").asText(null),
                readStringList(request.path("ExplicitAuthFlows")),
                request.has("AccessTokenValidity") ? request.path("AccessTokenValidity").asInt()
                        : null,
                request.has("IdTokenValidity") ? request.path("IdTokenValidity").asInt() : null,
                readStringList(request.path("LogoutURLs")),
                request.path("PreventUserExistenceErrors").asText(null),
                readStringList(request.path("ReadAttributes")),
                request.has("RefreshTokenValidity") ? request.path("RefreshTokenValidity").asInt()
                        : null,
                readStringList(request.path("SupportedIdentityProviders")),
                request.path("TokenValidityUnits").isObject()
                        ? objectMapper.convertValue(request.path("TokenValidityUnits"),
                                new TypeReference<Map<String, String>>() {})
                        : null,
                readStringList(request.path("WriteAttributes")),
                request.path("RefreshTokenRotation").isObject()
                        ? objectMapper.convertValue(request.path("RefreshTokenRotation"),
                                new TypeReference<Map<String, Object>>() {})
                        : null,
                request.has("EnableTokenRevocation")
                        ? request.path("EnableTokenRevocation").asBoolean()
                        : null
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
        clients.forEach(c -> items.add(clientToDescriptionNode(c)));
        return Response.ok(response).build();
    }

    private Response handleDeleteUserPoolClient(JsonNode request) {
        service.deleteUserPoolClient(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUpdateUserPoolClient(JsonNode request) {
        UserPoolClient client = service.updateUserPoolClient(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText(),
                request.has("ClientName") ? request.path("ClientName").asText() : null,
                request.has("AllowedOAuthFlowsUserPoolClient") ? request.path("AllowedOAuthFlowsUserPoolClient").asBoolean() : null,
                request.has("AllowedOAuthFlows") ? readStringList(request.path("AllowedOAuthFlows")) : null,
                request.has("AllowedOAuthScopes") ? readStringList(request.path("AllowedOAuthScopes")) : null,
                request.path("AnalyticsConfiguration").isObject()
                        ? objectMapper.convertValue(request.path("AnalyticsConfiguration"),
                        new TypeReference<Map<String, Object>>() {})
                        : null,
                request.has("CallbackURLs") ? readStringList(request.path("CallbackURLs")) : null,
                request.has("DefaultRedirectURI") ? request.path("DefaultRedirectURI").asText(null) : null,
                request.has("ExplicitAuthFlows") ? readStringList(request.path("ExplicitAuthFlows")) : null,
                request.has("AccessTokenValidity") ? request.path("AccessTokenValidity").asInt() : null,
                request.has("IdTokenValidity") ? request.path("IdTokenValidity").asInt() : null,
                request.has("LogoutURLs") ? readStringList(request.path("LogoutURLs")) : null,
                request.has("PreventUserExistenceErrors") ? request.path("PreventUserExistenceErrors").asText(null) : null,
                request.has("ReadAttributes") ? readStringList(request.path("ReadAttributes")) : null,
                request.has("RefreshTokenValidity") ? request.path("RefreshTokenValidity").asInt() : null,
                request.has("SupportedIdentityProviders") ? readStringList(request.path("SupportedIdentityProviders")) : null,
                request.path("TokenValidityUnits").isObject()
                        ? objectMapper.convertValue(request.path("TokenValidityUnits"),
                        new TypeReference<Map<String, String>>() {})
                        : null,
                request.has("WriteAttributes") ? readStringList(request.path("WriteAttributes")) : null,
                request.path("RefreshTokenRotation").isObject()
                        ? objectMapper.convertValue(request.path("RefreshTokenRotation"),
                        new TypeReference<Map<String, Object>>() {})
                        : null,
                request.has("EnableTokenRevocation") ? request.path("EnableTokenRevocation").asBoolean() : null
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("UserPoolClient", clientToNode(client));
        return Response.ok(response).build();
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

    private Response handleCreateIdentityProvider(JsonNode request) {
        IdentityProvider provider = service.createIdentityProvider(
                request.path("UserPoolId").asText(),
                request.path("ProviderName").asText(),
                request.path("ProviderType").asText(null),
                readStringMap(request, "ProviderDetails"),
                readStringMap(request, "AttributeMapping"),
                readStringList(request, "IdpIdentifiers")
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("IdentityProvider", identityProviderToNode(provider, request.has("IdpIdentifiers")));
        return Response.ok(response).build();
    }

    private Response handleDescribeIdentityProvider(JsonNode request) {
        IdentityProvider provider = service.describeIdentityProvider(
                request.path("UserPoolId").asText(),
                request.path("ProviderName").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("IdentityProvider", identityProviderToNode(provider, true));
        return Response.ok(response).build();
    }

    private Response handleListIdentityProviders(JsonNode request) {
        List<IdentityProvider> providers = service.listIdentityProviders(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Providers");
        providers.forEach(provider -> items.add(providerDescriptionToNode(provider)));
        return Response.ok(response).build();
    }

    private Response handleUpdateIdentityProvider(JsonNode request) {
        IdentityProvider provider = service.updateIdentityProvider(
                request.path("UserPoolId").asText(),
                request.path("ProviderName").asText(),
                readStringMap(request, "ProviderDetails"),
                readStringMap(request, "AttributeMapping"),
                readStringList(request, "IdpIdentifiers")
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("IdentityProvider", identityProviderToNode(provider, request.has("IdpIdentifiers")));
        return Response.ok(response).build();
    }

    private Response handleDeleteIdentityProvider(JsonNode request) {
        service.deleteIdentityProvider(
                request.path("UserPoolId").asText(),
                request.path("ProviderName").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateUserPoolDomain(JsonNode request) {
        UserPoolDomain domain = service.createUserPoolDomain(
                request.path("Domain").asText(),
                request.path("UserPoolId").asText(),
                customDomainConfig(request),
                managedLoginVersion(request)
        );
        return Response.ok(userPoolDomainResultToNode(domain)).build();
    }

    private Response handleUpdateUserPoolDomain(JsonNode request) {
        UserPoolDomain domain = service.updateUserPoolDomain(
                request.path("Domain").asText(),
                request.path("UserPoolId").asText(),
                customDomainConfig(request),
                managedLoginVersion(request)
        );
        return Response.ok(userPoolDomainResultToNode(domain)).build();
    }

    private Map<String, Object> customDomainConfig(JsonNode request) {
        JsonNode node = request.path("CustomDomainConfig");
        return node.isObject()
                ? objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {})
                : null;
    }

    /** Absent or null yields null; a value of another JSON type is a SerializationException, as on AWS. */
    private static Integer managedLoginVersion(JsonNode request) {
        JsonNode node = request.get("ManagedLoginVersion");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new AwsException("SerializationException", "Expected integer or null", 400);
        }
        return node.intValue();
    }

    /** CreateUserPoolDomain and UpdateUserPoolDomain share this result shape. */
    private ObjectNode userPoolDomainResultToNode(UserPoolDomain domain) {
        ObjectNode response = objectMapper.createObjectNode();
        if (domain.isCustomDomain()) {
            response.put("CloudFrontDomain", domain.getCloudFrontDistribution());
        }
        if (domain.getManagedLoginVersion() != null) {
            response.put("ManagedLoginVersion", domain.getManagedLoginVersion());
        }
        return response;
    }

    private Response handleDescribeUserPoolDomain(JsonNode request) {
        UserPoolDomain domain = service.describeUserPoolDomain(request.path("Domain").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("DomainDescription", userPoolDomainToNode(domain));
        return Response.ok(response).build();
    }

    private Response handleSetLogDeliveryConfiguration(JsonNode request) {
        UserPool pool = service.setLogDeliveryConfiguration(
                request.path("UserPoolId").asText(),
                readObjectList(request, "LogConfigurations")
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.set("LogDeliveryConfiguration", logDeliveryConfigurationToNode(pool));
        return Response.ok(response).build();
    }

    private Response handleGetLogDeliveryConfiguration(JsonNode request) {
        UserPool pool = service.getLogDeliveryConfiguration(request.path("UserPoolId").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("LogDeliveryConfiguration", logDeliveryConfigurationToNode(pool));
        return Response.ok(response).build();
    }

    private ObjectNode logDeliveryConfigurationToNode(UserPool pool) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("UserPoolId", pool.getId());
        ArrayNode configurations = node.putArray("LogConfigurations");
        pool.getLogConfigurations().forEach(config -> configurations.add(objectMapper.valueToTree(config)));
        return node;
    }

    /**
     * Absent or null yields null so the service can reject it. A value of the wrong JSON type is
     * a deserialization failure, which AWS reports as SerializationException, and a null element
     * inside the array is dropped, which is what AWS does with it.
     */
    private List<Map<String, Object>> readObjectList(JsonNode request, String member) {
        JsonNode node = request.get(member);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new AwsException("SerializationException", "Expected list or null", 400);
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isNull()) {
                continue;
            }
            if (!element.isObject()) {
                throw new AwsException("SerializationException", "Expected null", 400);
            }
            values.add(objectMapper.convertValue(element, new TypeReference<Map<String, Object>>() {}));
        }
        return values;
    }

    private Response handleDeleteUserPoolDomain(JsonNode request) {
        service.deleteUserPoolDomain(
                request.path("Domain").asText(),
                request.path("UserPoolId").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode userPoolDomainToNode(UserPoolDomain d) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AWSAccountId", d.getAwsAccountId());
        if (d.getCloudFrontDistribution() != null) {
            node.put("CloudFrontDistribution", d.getCloudFrontDistribution());
        }
        if (d.isCustomDomain()) {
            ObjectNode customDomainConfig = node.putObject("CustomDomainConfig");
            customDomainConfig.put("CertificateArn", d.getCertificateArn());
            customDomainConfig.put("SecurityPolicy", d.getSecurityPolicy());
        }
        node.put("Domain", d.getDomain());
        if (d.getManagedLoginVersion() != null) {
            node.put("ManagedLoginVersion", d.getManagedLoginVersion());
        }
        node.put("S3Bucket", d.getS3Bucket());
        node.put("Status", d.getStatus());
        node.put("UserPoolId", d.getUserPoolId());
        node.put("Version", d.getVersion());
        return node;
    }

    private Response handleAdminCreateUser(JsonNode request) {
        Map<String, String> attrs = new HashMap<>();
        request.path("UserAttributes").forEach(a -> attrs.put(a.path("Name").asText(), a.path("Value").asText()));
        String tempPassword = request.path("TemporaryPassword").isMissingNode() ? null
                : request.path("TemporaryPassword").asText(null);
        String messageAction = request.path("MessageAction").isMissingNode() ? null
                : request.path("MessageAction").asText(null);

        CognitoUser user = service.adminCreateUser(
                request.path("UserPoolId").asText(),
                request.path("Username").asText(),
                attrs,
                tempPassword,
                messageAction,
                request.path("ForceAliasCreation").asBoolean(false)
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

    private Response handleAdminResetUserPassword(JsonNode request) {
        service.adminResetUserPassword(request.path("UserPoolId").asText(), request.path("Username").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
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

    private Response handleAdminDeleteUserAttributes(JsonNode request) {
        String userPoolId = request.path("UserPoolId").asText();
        String username = request.path("Username").asText();
        List<String> attributeNames = readStringList(request.path("UserAttributeNames"));
        service.adminDeleteUserAttributes(userPoolId, username, attributeNames);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminUserGlobalSignOut(JsonNode request) {
        service.adminUserGlobalSignOut(
                request.path("UserPoolId").asText(),
                request.path("Username").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminEnableUser(JsonNode request) {
        service.adminEnableUser(request.path("UserPoolId").asText(), request.path("Username").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleAdminDisableUser(JsonNode request) {
        service.adminDisableUser(request.path("UserPoolId").asText(), request.path("Username").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /**
     * AWS coerces a JSON string in this member instead of rejecting it, and rejects every
     * other non-boolean type. Measured against Cognito in ap-southeast-1:
     *
     * <pre>
     * absent          accepted, stored false
     * true / false    accepted as given
     * "true", "yes"   accepted, stored true
     * "false"         accepted, stored false
     * 1, null, {}, [] SerializationException
     * </pre>
     *
     * <p>Jackson would map all of those last four to false through {@code asBoolean()},
     * which is how a malformed flag used to be accepted alongside valid settings and
     * persisted as unintended branding state.
     */
    private static Boolean readUseCognitoProvidedValues(JsonNode request) {
        if (!request.has("UseCognitoProvidedValues")) {
            return null;
        }
        JsonNode node = request.path("UseCognitoProvidedValues");
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return !"false".equalsIgnoreCase(node.asText());
        }
        throw new AwsException("SerializationException", null, 400);
    }

    private Response handleCreateManagedLoginBranding(JsonNode request) {
        ManagedLoginBranding branding = service.createManagedLoginBranding(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText(),
                readUseCognitoProvidedValues(request),
                readObjectMap(request, "Settings"),
                readMapList(request, "Assets")
        );
        return brandingResponse(branding);
    }

    private Response handleDescribeManagedLoginBranding(JsonNode request) {
        return brandingResponse(service.describeManagedLoginBranding(
                request.path("UserPoolId").asText(),
                request.path("ManagedLoginBrandingId").asText(null)));
    }

    private Response handleDescribeManagedLoginBrandingByClient(JsonNode request) {
        return brandingResponse(service.describeManagedLoginBrandingByClient(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText()));
    }

    private Response handleUpdateManagedLoginBranding(JsonNode request) {
        return brandingResponse(service.updateManagedLoginBranding(
                request.path("UserPoolId").asText(),
                request.path("ManagedLoginBrandingId").asText(null),
                readUseCognitoProvidedValues(request),
                readObjectMap(request, "Settings"),
                readMapList(request, "Assets")));
    }

    private Response handleDeleteManagedLoginBranding(JsonNode request) {
        service.deleteManagedLoginBranding(
                request.path("UserPoolId").asText(),
                request.path("ManagedLoginBrandingId").asText(null));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response brandingResponse(ManagedLoginBranding branding) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ManagedLoginBranding", managedLoginBrandingToNode(branding));
        return Response.ok(response).build();
    }

    /** AWS omits Settings entirely when none was supplied, but always returns Assets. */
    private ObjectNode managedLoginBrandingToNode(ManagedLoginBranding branding) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ManagedLoginBrandingId", branding.getManagedLoginBrandingId());
        node.put("UserPoolId", branding.getUserPoolId());
        node.put("UseCognitoProvidedValues", branding.isUseCognitoProvidedValues());
        if (branding.getSettings() != null) {
            node.set("Settings", objectMapper.valueToTree(branding.getSettings()));
        }
        ArrayNode assets = node.putArray("Assets");
        branding.getAssets().forEach(asset -> assets.add(objectMapper.valueToTree(asset)));
        node.put("CreationDate", branding.getCreationDate());
        node.put("LastModifiedDate", branding.getLastModifiedDate());
        return node;
    }

    /** Absent or null yields null; a value of the wrong JSON type is a deserialization failure. */
    private Map<String, Object> readObjectMap(JsonNode request, String member) {
        JsonNode node = request.get(member);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new AwsException("SerializationException", "Unexpected field type", 400);
        }
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /**
     * Elements are checked before conversion so a scalar cannot escape as an IllegalArgumentException.
     * AWS separates the two failures: a null element is a validation error, a non-object element a
     * deserialization one. Note this differs from LogConfigurations, where a null element is dropped.
     */
    private List<Map<String, Object>> readMapList(JsonNode request, String member) {
        JsonNode node = request.get(member);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new AwsException("SerializationException", "Unexpected field type", 400);
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isNull()) {
                throw new AwsException("InvalidParameterException",
                        "1 validation error detected: Value '" + node + "' at '"
                                + Character.toLowerCase(member.charAt(0)) + member.substring(1)
                                + "' failed to satisfy constraint: Member must satisfy constraint: "
                                + "[Member must not be null]", 400);
            }
            if (!element.isObject()) {
                throw new AwsException("SerializationException", "Unexpected value type in payload", 400);
            }
            values.add(objectMapper.convertValue(element, new TypeReference<Map<String, Object>>() {}));
        }
        return values;
    }

    private Response handleAdminLinkProviderForUser(JsonNode request) {
        JsonNode sourceUser = request.path("SourceUser");
        service.adminLinkProviderForUser(
                request.path("UserPoolId").asText(),
                request.path("DestinationUser").path("ProviderAttributeValue").asText(),
                sourceUser.path("ProviderName").asText(),
                sourceUser.path("ProviderAttributeValue").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListUsers(JsonNode request) {
        String filter = request.path("Filter").isMissingNode() ? null : request.path("Filter").asText(null);
        List<CognitoUser> users = service.listUsers(request.path("UserPoolId").asText(), filter);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Users");
        users.forEach(u -> items.add(userToNode(u)));
        return Response.ok(response).build();
    }

    private Response handleGetTokensFromRefreshToken(JsonNode request) {
        Map<String, Object> result = service.getTokensFromRefreshToken(
                request.path("ClientId").asText(),
                request.path("RefreshToken").asText()
        );
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleRevokeToken(JsonNode request) {
        service.revokeToken(
                request.path("ClientId").asText(null),
                request.path("Token").asText(null),
                request.path("ClientSecret").asText(null)
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleInitiateAuth(JsonNode request) {
        Map<String, String> params = new HashMap<>();
        request.path("AuthParameters").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
        Map<String, String> clientMetadata = new HashMap<>();
        request.path("ClientMetadata").fields().forEachRemaining(e -> clientMetadata.put(e.getKey(), e.getValue().asText()));

        Map<String, Object> result = service.initiateAuth(
                request.path("ClientId").asText(),
                request.path("AuthFlow").asText(),
                params,
                clientMetadata
        );
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleAdminInitiateAuth(JsonNode request) {
        Map<String, String> params = new HashMap<>();
        request.path("AuthParameters").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
        Map<String, String> clientMetadata = new HashMap<>();
        request.path("ClientMetadata").fields().forEachRemaining(e -> clientMetadata.put(e.getKey(), e.getValue().asText()));

        Map<String, Object> result = service.adminInitiateAuth(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText(),
                request.path("AuthFlow").asText(),
                params,
                clientMetadata
        );
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleRespondToAuthChallenge(JsonNode request) {
        Map<String, String> responses = new HashMap<>();
        request.path("ChallengeResponses").fields().forEachRemaining(e -> responses.put(e.getKey(), e.getValue().asText()));
        Map<String, String> clientMetadata = new HashMap<>();
        request.path("ClientMetadata").fields().forEachRemaining(e -> clientMetadata.put(e.getKey(), e.getValue().asText()));

        Map<String, Object> result = service.respondToAuthChallenge(
                request.path("ClientId").asText(),
                request.path("ChallengeName").asText(),
                request.path("Session").asText(null),
                responses,
                clientMetadata
        );
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleAdminRespondToAuthChallenge(JsonNode request) {
        Map<String, String> responses = new HashMap<>();
        request.path("ChallengeResponses").fields().forEachRemaining(e -> responses.put(e.getKey(), e.getValue().asText()));
        Map<String, String> clientMetadata = new HashMap<>();
        request.path("ClientMetadata").fields().forEachRemaining(e -> clientMetadata.put(e.getKey(), e.getValue().asText()));

        Map<String, Object> result = service.adminRespondToAuthChallenge(
                request.path("UserPoolId").asText(),
                request.path("ClientId").asText(),
                request.path("ChallengeName").asText(),
                request.path("Session").asText(null),
                responses,
                clientMetadata
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
        response.put("UserSub", user.getAttributes().get("sub"));
        if (!"CONFIRMED".equals(user.getUserStatus())) {
            Map<String, String> deliveryDetails = service.signUpCodeDeliveryDetails(user);
            if (!deliveryDetails.isEmpty()) {
                ObjectNode delivery = response.putObject("CodeDeliveryDetails");
                delivery.put("AttributeName", deliveryDetails.get("AttributeName"));
                delivery.put("DeliveryMedium", deliveryDetails.get("DeliveryMedium"));
                delivery.put("Destination", deliveryDetails.get("Destination"));
            }
        }
        return Response.ok(response).build();
    }

    private Response handleConfirmSignUp(JsonNode request) {
        service.confirmSignUp(
                request.path("ClientId").asText(),
                request.path("Username").asText(),
                request.path("ConfirmationCode").asText()
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleResendConfirmationCode(JsonNode request) {
        Map<String, String> deliveryDetails = service.resendConfirmationCode(
                request.path("ClientId").asText(),
                request.path("Username").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode delivery = response.putObject("CodeDeliveryDetails");
        delivery.put("AttributeName", deliveryDetails.get("AttributeName"));
        delivery.put("DeliveryMedium", deliveryDetails.get("DeliveryMedium"));
        delivery.put("Destination", deliveryDetails.get("Destination"));
        return Response.ok(response).build();
    }

    private Response handleAdminConfirmSignUp(JsonNode request) {
        service.adminConfirmSignUp(
                request.path("UserPoolId").asText(),
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
        Map<String, Object> deliveryDetails = service.forgotPassword(
                request.path("ClientId").asText(),
                request.path("Username").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode delivery = response.putObject("CodeDeliveryDetails");
        delivery.put("AttributeName", String.valueOf(deliveryDetails.get("AttributeName")));
        delivery.put("DeliveryMedium", String.valueOf(deliveryDetails.get("DeliveryMedium")));
        delivery.put("Destination", String.valueOf(deliveryDetails.get("Destination")));
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

    private Response handleGetUserAttributeVerificationCode(JsonNode request) {
        Map<String, Object> deliveryDetails = service.getUserAttributeVerificationCode(
                request.path("AccessToken").asText(),
                request.path("AttributeName").asText()
        );
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode delivery = response.putObject("CodeDeliveryDetails");
        delivery.put("AttributeName", (String) deliveryDetails.get("AttributeName"));
        delivery.put("DeliveryMedium", (String) deliveryDetails.get("DeliveryMedium"));
        delivery.put("Destination", (String) deliveryDetails.get("Destination"));
        return Response.ok(response).build();
    }

    private Response handleUpdateUserAttributes(JsonNode request) {
        Map<String, String> attrs = new HashMap<>();
        request.path("UserAttributes").forEach(a -> attrs.put(a.path("Name").asText(), a.path("Value").asText()));
        service.updateUserAttributes(request.path("AccessToken").asText(), attrs);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("CodeDeliveryDetailsList");
        return Response.ok(response).build();
    }

    private Response handleDeleteUserAttributes(JsonNode request) {
        String accessToken = request.path("AccessToken").asText();
        List<String> attributeNames = readStringList(request.path("UserAttributeNames"));
        service.deleteUserAttributes(accessToken, attributeNames);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGlobalSignOut(JsonNode request) {
        service.globalSignOut(request.path("AccessToken").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
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
        node.set("SchemaAttributes", objectMapper.valueToTree(CognitoStandardAttributes.merge(p.getSchemaAttributes())));
        node.set("AutoVerifiedAttributes", objectMapper.valueToTree(p.getAutoVerifiedAttributes() != null ? p.getAutoVerifiedAttributes() : new java.util.ArrayList<>()));
        node.set("AliasAttributes", objectMapper.valueToTree(p.getAliasAttributes() != null ? p.getAliasAttributes() : new java.util.ArrayList<>()));
        node.set("UsernameAttributes", objectMapper.valueToTree(p.getUsernameAttributes() != null ? p.getUsernameAttributes() : new java.util.ArrayList<>()));

        if (p.getSmsVerificationMessage() != null) node.put("SmsVerificationMessage", p.getSmsVerificationMessage());
        if (p.getEmailVerificationMessage() != null) node.put("EmailVerificationMessage", p.getEmailVerificationMessage());
        if (p.getEmailVerificationSubject() != null) node.put("EmailVerificationSubject", p.getEmailVerificationSubject());

        node.set("VerificationMessageTemplate", objectMapper.valueToTree(p.getVerificationMessageTemplate() != null ? p.getVerificationMessageTemplate() : new HashMap<>()));

        if (p.getSmsAuthenticationMessage() != null) node.put("SmsAuthenticationMessage", p.getSmsAuthenticationMessage());

        node.put("MfaConfiguration", p.getMfaConfiguration() != null ? p.getMfaConfiguration() : "OFF");
        // AWS's JSON protocol serializes only members with a value provided - an unconfigured
        // pool omits this key entirely, it doesn't emit a JSON null (confirmed against moto's
        // DescribeUserPool, which never writes the key when unset). An empty object here (the
        // prior bug) made a re-planning Terraform see one block with false/false the first time
        // and no block at all the next, reporting perpetual drift.
        if (p.getDeviceConfiguration() != null && !p.getDeviceConfiguration().isEmpty()) {
            node.set("DeviceConfiguration", objectMapper.valueToTree(p.getDeviceConfiguration()));
        }
        node.put("EstimatedNumberOfUsers", p.getEstimatedNumberOfUsers());
        Map<String, Object> emailConfig = p.getEmailConfiguration() != null
                ? new HashMap<>(p.getEmailConfiguration()) : new HashMap<>();
        emailConfig.putIfAbsent("EmailSendingAccount", "COGNITO_DEFAULT");
        node.set("EmailConfiguration", objectMapper.valueToTree(emailConfig));
        node.set("SmsConfiguration", objectMapper.valueToTree(p.getSmsConfiguration() != null ? p.getSmsConfiguration() : new HashMap<>()));
        node.set("UserPoolTags", objectMapper.valueToTree(p.getUserPoolTags() != null ? p.getUserPoolTags() : new HashMap<>()));
        node.set("AdminCreateUserConfig", objectMapper.valueToTree(p.getAdminCreateUserConfig() != null ? p.getAdminCreateUserConfig() : new HashMap<>()));
        // Same reasoning as DeviceConfiguration above: an unconfigured pool omits the
        // UserPoolAddOns key entirely, it isn't present with AdvancedSecurityMode filled in or
        // as a JSON null - confirmed by re-planning Terraform, which otherwise saw the block
        // appear at apply (captured into state) and disappear on the next refresh.
        if (p.getUserPoolAddOns() != null && !p.getUserPoolAddOns().isEmpty()) {
            node.set("UserPoolAddOns", objectMapper.valueToTree(p.getUserPoolAddOns()));
        }
        node.set("UsernameConfiguration", objectMapper.valueToTree(p.getUsernameConfiguration() != null ? p.getUsernameConfiguration() : new HashMap<>()));
        node.set("AccountRecoverySetting", objectMapper.valueToTree(p.getAccountRecoverySetting() != null ? p.getAccountRecoverySetting() : new HashMap<>()));
        node.put("UserPoolTier", p.getUserPoolTier() != null ? p.getUserPoolTier() : "ESSENTIALS");

        return node;
    }


    @SuppressWarnings("unchecked")



    private ObjectNode clientToDescriptionNode(UserPoolClient c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClientId", c.getClientId());
        node.put("ClientName", c.getClientName());
        node.put("UserPoolId", c.getUserPoolId());
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
        Optional.ofNullable(c.getAnalyticsConfiguration())
                .ifPresent(it -> node.set("AnalyticsConfiguration", objectMapper.valueToTree(it)));
        ArrayNode callbackUrls = node.putArray("CallbackURLs");
        c.getCallbackURLs().forEach(callbackUrls::add);
        if (c.getDefaultRedirectURI() != null) {
            node.put("DefaultRedirectURI", c.getDefaultRedirectURI());
        }
        ArrayNode explicitAuthFlows = node.putArray("ExplicitAuthFlows");
        c.getExplicitAuthFlows().forEach(explicitAuthFlows::add);
        if (c.getAccessTokenValidity() != null) {
            node.put("AccessTokenValidity", c.getAccessTokenValidity());
        }
        if (c.getIdTokenValidity() != null) {
            node.put("IdTokenValidity", c.getIdTokenValidity());
        }
        ArrayNode logoutUrls = node.putArray("LogoutURLs");
        c.getLogoutURLs().forEach(logoutUrls::add);
        if (c.getPreventUserExistenceErrors() != null) {
            node.put("PreventUserExistenceErrors", c.getPreventUserExistenceErrors());
        }
        ArrayNode readAttributes = node.putArray("ReadAttributes");
        c.getReadAttributes().forEach(readAttributes::add);
        if (c.getRefreshTokenValidity() != null) {
            node.put("RefreshTokenValidity", c.getRefreshTokenValidity());
        }
        ArrayNode supportedIdentityProviders = node.putArray("SupportedIdentityProviders");
        c.getSupportedIdentityProviders().forEach(supportedIdentityProviders::add);
        Optional.ofNullable(c.getTokenValidityUnits())
                .ifPresent(it -> node.set("TokenValidityUnits", objectMapper.valueToTree(it)));
        ArrayNode writeAttributes = node.putArray("WriteAttributes");
        c.getWriteAttributes().forEach(writeAttributes::add);
        Optional.ofNullable(c.getRefreshTokenRotation())
                .ifPresent(it -> node.set("RefreshTokenRotation", objectMapper.valueToTree(it)));
        if (c.getEnableTokenRevocation() != null) {
            node.put("EnableTokenRevocation", c.getEnableTokenRevocation());
        }
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

    /**
     * {@code IdpIdentifiers} is echoed only when the caller supplied the member: AWS omits it
     * from CreateIdentityProvider and UpdateIdentityProvider responses otherwise, whatever the
     * stored value, while DescribeIdentityProvider always returns it.
     */
    private ObjectNode identityProviderToNode(IdentityProvider provider, boolean includeIdpIdentifiers) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("UserPoolId", provider.getUserPoolId());
        node.put("ProviderName", provider.getProviderName());
        node.put("ProviderType", provider.getProviderType());
        ObjectNode details = node.putObject("ProviderDetails");
        provider.getProviderDetails().forEach(details::put);
        ObjectNode mapping = node.putObject("AttributeMapping");
        provider.getAttributeMapping().forEach(mapping::put);
        if (includeIdpIdentifiers) {
            ArrayNode identifiers = node.putArray("IdpIdentifiers");
            provider.getIdpIdentifiers().forEach(identifiers::add);
        }
        node.put("CreationDate", provider.getCreationDate());
        node.put("LastModifiedDate", provider.getLastModifiedDate());
        return node;
    }

    private ObjectNode providerDescriptionToNode(IdentityProvider provider) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ProviderName", provider.getProviderName());
        node.put("ProviderType", provider.getProviderType());
        node.put("LastModifiedDate", provider.getLastModifiedDate());
        node.put("CreationDate", provider.getCreationDate());
        return node;
    }

    private Map<String, String> readStringMap(JsonNode request, String member) {
        JsonNode node = request.get(member);
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private List<String> readStringList(JsonNode request, String member) {
        JsonNode node = request.get(member);
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
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

    private Response handleUpdateGroup(JsonNode request) {
        String userPoolId = request.path("UserPoolId").asText();
        String groupName = request.path("GroupName").asText();
        String description = request.has("Description") ? request.path("Description").asText() : null;
        JsonNode precNode = request.path("Precedence");
        Integer precedence = precNode.isMissingNode() || precNode.isNull() ? null : precNode.asInt();
        String roleArn = request.has("RoleArn") ? request.path("RoleArn").asText() : null;
        CognitoGroup group = service.updateGroup(userPoolId, groupName, description, precedence, roleArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Group", groupToNode(group));
        return Response.ok(response).build();
    }

    private Response handleListUsersInGroup(JsonNode request) {
        String userPoolId = request.path("UserPoolId").asText();
        String groupName = request.path("GroupName").asText();
        List<CognitoUser> users = service.listUsersInGroup(userPoolId, groupName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Users");
        users.forEach(u -> items.add(userToNode(u)));
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

    private Response handleListUserPoolClientSecrets(JsonNode request) {
        List<UserPoolClientSecret> clientSecrets =
                service.listUserPoolClientSecrets(
                        request.path("UserPoolId").asText(),
                        request.path("ClientId").asText()
                );
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("ClientSecrets");
        clientSecrets.forEach(cs -> items.add(clientSecretToNode(cs, false)));
        return Response.ok(response).build();
    }

    private Response handleAddUserPoolClientSecret(JsonNode request) {
        String clientId = request.path("ClientId").asText();
        String clientSecret = request.path("ClientSecret").asText(null);
        String userPoolId = request.path("UserPoolId").asText();

        UserPoolClientSecret cs = service.addUserPoolClientSecret(
                clientId, clientSecret, userPoolId
        );

        boolean includeClientSecretValue = clientSecret == null;
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("ClientSecretDescriptor", clientSecretToNode(cs, includeClientSecretValue));
        return Response.ok(wrapper).build();
    }

    private Response handleDeleteUserPoolClientSecret(JsonNode request) {
        String clientId = request.path("ClientId").asText();
        String clientSecretId = request.path("ClientSecretId").asText();
        String userPoolId = request.path("UserPoolId").asText();

        service.deleteUserPoolClientSecret(clientId, clientSecretId, userPoolId);

        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode clientSecretToNode(UserPoolClientSecret cs,
                                          boolean includeClientSecretValue) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ClientSecretId", cs.getClientSecretId());
        if (includeClientSecretValue) {
            node.put("ClientSecretValue", cs.getClientSecretValue());
        }
        node.put("ClientSecretCreateDate", cs.getClientSecretCreateDate());
        return node;
    }
    private Response handleAdminSetUserMFAPreference(JsonNode request) {
        String userPoolId = request.path("UserPoolId").asText();
        String username = request.path("Username").asText();

        JsonNode emailSettings = request.path("EmailMfaSettings");

        Boolean enabled = emailSettings.has("Enabled")
                ? emailSettings.path("Enabled").asBoolean()
                : null;

        Boolean preferredMfa = emailSettings.has("PreferredMfa")
                ? emailSettings.path("PreferredMfa").asBoolean()
                : null;

        service.adminSetUserMFAPreference(
                userPoolId,
                username,
                enabled,
                preferredMfa
        );

        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetUserMFAPreference(JsonNode request) {
        JsonNode emailSettings = request.path("EmailMfaSettings");

        Boolean enabled = emailSettings.has("Enabled")
                ? emailSettings.path("Enabled").asBoolean()
                : null;

        Boolean preferredMfa = emailSettings.has("PreferredMfa")
                ? emailSettings.path("PreferredMfa").asBoolean()
                : null;

        service.setUserMFAPreference(
                request.path("AccessToken").asText(),
                enabled,
                preferredMfa
        );

        return Response.ok(objectMapper.createObjectNode()).build();
    }

}
