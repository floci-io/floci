package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class CognitoService {

    private static final Logger LOG = Logger.getLogger(CognitoService.class);

    private final StorageBackend<String, UserPool> poolStore;
    private final StorageBackend<String, UserPoolClient> clientStore;
    private final StorageBackend<String, ResourceServer> resourceServerStore;
    private final StorageBackend<String, CognitoUser> userStore;
    private final StorageBackend<String, CognitoGroup> groupStore;
    private final String baseUrl;

    @Inject
    public CognitoService(StorageFactory storageFactory, EmulatorConfig emulatorConfig) {
        this.poolStore = storageFactory.create("cognito", "cognito-pools.json",
                new TypeReference<Map<String, UserPool>>() {});
        this.clientStore = storageFactory.create("cognito", "cognito-clients.json",
                new TypeReference<Map<String, UserPoolClient>>() {});
        this.resourceServerStore = storageFactory.create("cognito", "cognito-resource-servers.json",
                new TypeReference<Map<String, ResourceServer>>() {});
        this.userStore = storageFactory.create("cognito", "cognito-users.json",
                new TypeReference<Map<String, CognitoUser>>() {});
        this.groupStore = storageFactory.create("cognito", "cognito-groups.json",
                new TypeReference<Map<String, CognitoGroup>>() {});
        this.baseUrl = trimTrailingSlash(emulatorConfig.baseUrl());
    }

    CognitoService(StorageBackend<String, UserPool> poolStore,
                   StorageBackend<String, UserPoolClient> clientStore,
                   StorageBackend<String, ResourceServer> resourceServerStore,
                   StorageBackend<String, CognitoUser> userStore,
                   StorageBackend<String, CognitoGroup> groupStore,
                   String baseUrl) {
        this.poolStore = poolStore;
        this.clientStore = clientStore;
        this.resourceServerStore = resourceServerStore;
        this.userStore = userStore;
        this.groupStore = groupStore;
        this.baseUrl = baseUrl;
    }

    // ──────────────────────────── User Pools ────────────────────────────

    public UserPool createUserPool(String name, String region) {
        String id = region + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        UserPool pool = new UserPool();
        pool.setId(id);
        pool.setName(name);
        ensureJwtSigningKeys(pool);
        poolStore.put(id, pool);
        LOG.infov("Created User Pool: {0}", id);
        return pool;
    }

    public UserPool describeUserPool(String id) {
        UserPool pool = poolStore.get(id)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "User pool not found", 404));
        if (ensureJwtSigningKeys(pool)) {
            poolStore.put(id, pool);
        }
        return pool;
    }

    public List<UserPool> listUserPools() {
        return poolStore.scan(k -> true);
    }

    public void deleteUserPool(String id) {
        String prefix = id + "::";
        groupStore.scan(k -> k.startsWith(prefix))
                .forEach(g -> groupStore.delete(groupKey(id, g.getGroupName())));
        poolStore.delete(id);
    }

    // ──────────────────────────── User Pool Clients ────────────────────────────

    public UserPoolClient createUserPoolClient(String userPoolId, String clientName, boolean generateSecret,
                                               boolean allowedOAuthFlowsUserPoolClient,
                                               List<String> allowedOAuthFlows,
                                               List<String> allowedOAuthScopes) {
        describeUserPool(userPoolId);
        String clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        UserPoolClient client = new UserPoolClient();
        client.setClientId(clientId);
        client.setUserPoolId(userPoolId);
        client.setClientName(clientName);
        client.setGenerateSecret(generateSecret);
        client.setAllowedOAuthFlowsUserPoolClient(allowedOAuthFlowsUserPoolClient);
        client.setAllowedOAuthFlows(normalizeStringList(allowedOAuthFlows));
        client.setAllowedOAuthScopes(normalizeStringList(allowedOAuthScopes));
        if (generateSecret) {
            client.setClientSecret(generateSecretValue());
        }
        clientStore.put(clientId, client);
        LOG.infov("Created User Pool Client: {0} for pool {1}", clientId, userPoolId);
        return client;
    }

    public UserPoolClient describeUserPoolClient(String userPoolId, String clientId) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "User pool client not found", 404));
        if (!client.getUserPoolId().equals(userPoolId)) {
            throw new AwsException("ResourceNotFoundException", "User pool client not found", 404);
        }
        return client;
    }

    public List<UserPoolClient> listUserPoolClients(String userPoolId) {
        return clientStore.scan(k -> clientStore.get(k).map(c -> c.getUserPoolId().equals(userPoolId)).orElse(false));
    }

    public void deleteUserPoolClient(String userPoolId, String clientId) {
        describeUserPoolClient(userPoolId, clientId);
        clientStore.delete(clientId);
    }

    // ──────────────────────────── Resource Servers ────────────────────────────

    public ResourceServer createResourceServer(String userPoolId, String identifier, String name,
                                               List<ResourceServerScope> scopes) {
        describeUserPool(userPoolId);
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("InvalidParameterException", "Identifier is required", 400);
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "Name is required", 400);
        }

        String key = resourceServerKey(userPoolId, identifier);
        if (resourceServerStore.get(key).isPresent()) {
            throw new AwsException("ResourceConflictException", "Resource server already exists", 400);
        }

        ResourceServer server = new ResourceServer();
        server.setUserPoolId(userPoolId);
        server.setIdentifier(identifier);
        server.setName(name);
        server.setScopes(normalizeScopes(scopes));
        resourceServerStore.put(key, server);
        return server;
    }

    public ResourceServer describeResourceServer(String userPoolId, String identifier) {
        describeUserPool(userPoolId);
        return resourceServerStore.get(resourceServerKey(userPoolId, identifier))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Resource server not found", 404));
    }

    public List<ResourceServer> listResourceServers(String userPoolId) {
        describeUserPool(userPoolId);
        String prefix = userPoolId + "::";
        return resourceServerStore.scan(k -> k.startsWith(prefix));
    }

    public ResourceServer updateResourceServer(String userPoolId, String identifier, String name,
                                               List<ResourceServerScope> scopes) {
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new AwsException("InvalidParameterException", "UserPoolId is required", 400);
        }
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("InvalidParameterException", "Identifier is required", 400);
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "Name is required", 400);
        }

        ResourceServer server = describeResourceServer(userPoolId, identifier);
        server.setName(name);
        server.setScopes(normalizeScopes(scopes));
        server.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        resourceServerStore.put(resourceServerKey(userPoolId, identifier), server);
        return server;
    }

    public void deleteResourceServer(String userPoolId, String identifier) {
        describeResourceServer(userPoolId, identifier);
        resourceServerStore.delete(resourceServerKey(userPoolId, identifier));
    }

    // ──────────────────────────── Users ────────────────────────────

    public CognitoUser adminCreateUser(String userPoolId, String username, Map<String, String> attributes,
                                       String temporaryPassword) {
        describeUserPool(userPoolId);
        String key = userKey(userPoolId, username);
        if (userStore.get(key).isPresent()) {
            throw new AwsException("UsernameExistsException", "User already exists", 400);
        }

        CognitoUser user = new CognitoUser();
        user.setUsername(username);
        user.setUserPoolId(userPoolId);
        if (attributes != null) {
            user.getAttributes().putAll(attributes);
        }

        if (temporaryPassword != null && !temporaryPassword.isEmpty()) {
            user.setPasswordHash(hashPassword(temporaryPassword));
            user.setTemporaryPassword(true);
            user.setUserStatus("FORCE_CHANGE_PASSWORD");
        }

        userStore.put(key, user);
        LOG.infov("Created user {0} in pool {1}", username, userPoolId);
        return user;
    }

    public CognitoUser adminGetUser(String userPoolId, String username) {
        return userStore.get(userKey(userPoolId, username))
                .orElseThrow(() -> new AwsException("UserNotFoundException", "User not found", 404));
    }

    public void adminDeleteUser(String userPoolId, String username) {
        CognitoUser user = adminGetUser(userPoolId, username);
        for (String groupName : new ArrayList<>(user.getGroupNames())) {
            groupStore.get(groupKey(userPoolId, groupName)).ifPresent(group -> {
                group.removeUserName(username);
                group.setLastModifiedDate(System.currentTimeMillis() / 1000L);
                groupStore.put(groupKey(userPoolId, groupName), group);
            });
        }
        userStore.delete(userKey(userPoolId, username));
    }

    public void adminSetUserPassword(String userPoolId, String username, String password, boolean permanent) {
        CognitoUser user = adminGetUser(userPoolId, username);
        user.setPasswordHash(hashPassword(password));
        user.setTemporaryPassword(!permanent);
        user.setUserStatus(permanent ? "CONFIRMED" : "FORCE_CHANGE_PASSWORD");
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, username), user);
        LOG.infov("Set password for user {0} in pool {1} (permanent={2})", username, userPoolId, permanent);
    }

    public void adminUpdateUserAttributes(String userPoolId, String username, Map<String, String> attributes) {
        CognitoUser user = adminGetUser(userPoolId, username);
        user.getAttributes().putAll(attributes);
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, username), user);
    }

    public List<CognitoUser> listUsers(String userPoolId) {
        String prefix = userPoolId + "::";
        return userStore.scan(k -> k.startsWith(prefix));
    }

    // ──────────────────────────── Groups ────────────────────────────

    public CognitoGroup createGroup(String userPoolId, String groupName, String description,
                                     Integer precedence, String roleArn) {
        describeUserPool(userPoolId);
        validateGroupName(groupName);
        if (groupStore.get(groupKey(userPoolId, groupName)).isPresent()) {
            throw new AwsException("GroupExistsException",
                    "A group with the name " + groupName + " already exists.", 400);
        }
        CognitoGroup group = new CognitoGroup();
        group.setGroupName(groupName);
        group.setUserPoolId(userPoolId);
        group.setDescription(description);
        group.setPrecedence(precedence);
        group.setRoleArn(roleArn);
        groupStore.put(groupKey(userPoolId, groupName), group);
        LOG.infov("Created Cognito group: {0} in pool {1}", groupName, userPoolId);
        return group;
    }

    public CognitoGroup getGroup(String userPoolId, String groupName) {
        describeUserPool(userPoolId);
        validateGroupName(groupName);
        return groupStore.get(groupKey(userPoolId, groupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Group not found: " + groupName, 404));
    }

    public List<CognitoGroup> listGroups(String userPoolId) {
        describeUserPool(userPoolId);
        String prefix = userPoolId + "::";
        List<CognitoGroup> groups = new ArrayList<>(groupStore.scan(k -> k.startsWith(prefix)));
        groups.sort(Comparator.comparing(CognitoGroup::getGroupName));
        return groups;
    }

    public void deleteGroup(String userPoolId, String groupName) {
        CognitoGroup group = getGroup(userPoolId, groupName);
        long now = System.currentTimeMillis() / 1000L;
        for (String username : new ArrayList<>(group.getUserNames())) {
            userStore.get(userKey(userPoolId, username)).ifPresent(user -> {
                if (user.getGroupNames().remove(groupName)) {
                    user.setLastModifiedDate(now);
                    userStore.put(userKey(userPoolId, username), user);
                }
            });
        }
        groupStore.delete(groupKey(userPoolId, groupName));
        LOG.infov("Deleted Cognito group: {0} from pool {1}", groupName, userPoolId);
    }

    public void adminAddUserToGroup(String userPoolId, String groupName, String username) {
        CognitoGroup group = getGroup(userPoolId, groupName);
        CognitoUser user = adminGetUser(userPoolId, username);
        long now = System.currentTimeMillis() / 1000L;
        if (group.addUserName(username)) {
            group.setLastModifiedDate(now);
            groupStore.put(groupKey(userPoolId, groupName), group);
        }
        if (!user.getGroupNames().contains(groupName)) {
            user.getGroupNames().add(groupName);
            user.setLastModifiedDate(now);
            userStore.put(userKey(userPoolId, username), user);
        }
    }

    public void adminRemoveUserFromGroup(String userPoolId, String groupName, String username) {
        CognitoGroup group = getGroup(userPoolId, groupName);
        CognitoUser user = adminGetUser(userPoolId, username);
        long now = System.currentTimeMillis() / 1000L;
        if (group.removeUserName(username)) {
            group.setLastModifiedDate(now);
            groupStore.put(groupKey(userPoolId, groupName), group);
        }
        if (user.getGroupNames().remove(groupName)) {
            user.setLastModifiedDate(now);
            userStore.put(userKey(userPoolId, username), user);
        }
    }

    public List<CognitoGroup> adminListGroupsForUser(String userPoolId, String username) {
        describeUserPool(userPoolId);
        CognitoUser user = adminGetUser(userPoolId, username);
        return user.getGroupNames().stream()
                .flatMap(gn -> groupStore.get(groupKey(userPoolId, gn)).stream())
                .toList();
    }

    // ──────────────────────────── Self-Service Registration ────────────────────────────

    public CognitoUser signUp(String clientId, String username, String password, Map<String, String> attributes) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        String userPoolId = client.getUserPoolId();
        describeUserPool(userPoolId);

        String key = userKey(userPoolId, username);
        if (userStore.get(key).isPresent()) {
            throw new AwsException("UsernameExistsException", "User already exists", 400);
        }

        CognitoUser user = new CognitoUser();
        user.setUsername(username);
        user.setUserPoolId(userPoolId);
        user.setPasswordHash(hashPassword(password));
        user.setUserStatus("UNCONFIRMED");
        if (attributes != null) {
            user.getAttributes().putAll(attributes);
        }

        userStore.put(key, user);
        LOG.infov("Signed up user {0} in pool {1}", username, userPoolId);
        return user;
    }

    public void confirmSignUp(String clientId, String username) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        CognitoUser user = adminGetUser(client.getUserPoolId(), username);
        user.setUserStatus("CONFIRMED");
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(client.getUserPoolId(), username), user);
    }

    // ──────────────────────────── Auth ────────────────────────────

    public Map<String, Object> initiateAuth(String clientId, String authFlow, Map<String, String> authParameters) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        UserPool pool = describeUserPool(client.getUserPoolId());

        return switch (authFlow) {
            case "USER_PASSWORD_AUTH" -> authenticateWithPassword(pool, authParameters, clientId);
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> handleRefreshToken(pool, authParameters);
            default -> {
                // For other flows (USER_SRP_AUTH, etc.), if user exists return tokens
                String username = authParameters.get("USERNAME");
                if (username == null) {
                    throw new AwsException("InvalidParameterException", "USERNAME is required", 400);
                }
                CognitoUser user = adminGetUser(pool.getId(), username);
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult", generateAuthResult(user, pool));
                yield result;
            }
        };
    }

    public Map<String, Object> adminInitiateAuth(String userPoolId, String clientId, String authFlow,
                                                  Map<String, String> authParameters) {
        describeUserPoolClient(userPoolId, clientId);
        UserPool pool = describeUserPool(userPoolId);

        return switch (authFlow) {
            case "ADMIN_USER_PASSWORD_AUTH", "USER_PASSWORD_AUTH" ->
                    authenticateWithPassword(pool, authParameters, clientId);
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> handleRefreshToken(pool, authParameters);
            default -> {
                String username = authParameters.get("USERNAME");
                CognitoUser user = adminGetUser(userPoolId, username);
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult", generateAuthResult(user, pool));
                yield result;
            }
        };
    }

    public Map<String, Object> respondToAuthChallenge(String clientId, String challengeName,
                                                       String session, Map<String, String> responses) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        UserPool pool = describeUserPool(client.getUserPoolId());

        if ("NEW_PASSWORD_REQUIRED".equals(challengeName)) {
            String username = responses.get("USERNAME");
            String newPassword = responses.get("NEW_PASSWORD");
            if (username == null || newPassword == null) {
                throw new AwsException("InvalidParameterException", "USERNAME and NEW_PASSWORD are required", 400);
            }
            adminSetUserPassword(pool.getId(), username, newPassword, true);
            CognitoUser user = adminGetUser(pool.getId(), username);
            Map<String, Object> result = new HashMap<>();
            result.put("AuthenticationResult", generateAuthResult(user, pool));
            return result;
        }

        throw new AwsException("InvalidParameterException", "Unsupported challenge: " + challengeName, 400);
    }

    public void changePassword(String accessToken, String previousPassword, String proposedPassword) {
        String username = extractUsernameFromToken(accessToken);
        String poolId = extractPoolIdFromToken(accessToken);
        if (username == null || poolId == null) {
            throw new AwsException("NotAuthorizedException", "Invalid access token", 400);
        }

        CognitoUser user = adminGetUser(poolId, username);
        if (user.getPasswordHash() != null && !user.getPasswordHash().equals(hashPassword(previousPassword))) {
            throw new AwsException("NotAuthorizedException", "Incorrect username or password", 400);
        }

        user.setPasswordHash(hashPassword(proposedPassword));
        user.setTemporaryPassword(false);
        user.setUserStatus("CONFIRMED");
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(poolId, username), user);
    }

    public void forgotPassword(String clientId, String username) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        // Verify user exists; real AWS would send email/SMS
        adminGetUser(client.getUserPoolId(), username);
        LOG.infov("ForgotPassword stub: user {0} requested password reset", username);
    }

    public void confirmForgotPassword(String clientId, String username, String confirmationCode, String newPassword) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        // Accept any confirmation code in the emulator
        adminSetUserPassword(client.getUserPoolId(), username, newPassword, true);
    }

    public Map<String, Object> getUser(String accessToken) {
        String username = extractUsernameFromToken(accessToken);
        String poolId = extractPoolIdFromToken(accessToken);
        if (username == null || poolId == null) {
            throw new AwsException("NotAuthorizedException", "Invalid access token", 400);
        }
        CognitoUser user = adminGetUser(poolId, username);
        Map<String, Object> result = new HashMap<>();
        result.put("Username", user.getUsername());
        List<Map<String, String>> attrs = new ArrayList<>();
        user.getAttributes().forEach((k, v) -> attrs.add(Map.of("Name", k, "Value", v)));
        result.put("UserAttributes", attrs);
        return result;
    }

    public void updateUserAttributes(String accessToken, Map<String, String> attributes) {
        String username = extractUsernameFromToken(accessToken);
        String poolId = extractPoolIdFromToken(accessToken);
        if (username == null || poolId == null) {
            throw new AwsException("NotAuthorizedException", "Invalid access token", 400);
        }
        adminUpdateUserAttributes(poolId, username, attributes);
    }

    public Map<String, Object> issueClientCredentialsToken(String clientId, String clientSecret, String scope) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        UserPool pool = describeUserPool(client.getUserPoolId());
        validateClientAllowsClientCredentials(client);
        validateClientSecret(client, clientSecret);
        String normalizedScope = resolveAuthorizedScopes(client, pool.getId(), scope);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", generateClientAccessToken(client, pool, normalizedScope));
        response.put("token_type", "Bearer");
        response.put("expires_in", 3600);
        return response;
    }

    public String getIssuer(String poolId) {
        return baseUrl + "/" + poolId;
    }

    public String getJwksUri(String poolId) {
        return getIssuer(poolId) + "/.well-known/jwks.json";
    }

    public String getTokenEndpoint() {
        return baseUrl + "/cognito-idp/oauth2/token";
    }

    // ──────────────────────────── Private helpers ────────────────────────────

    private Map<String, Object> authenticateWithPassword(UserPool pool, Map<String, String> params, String clientId) {
        String username = params.get("USERNAME");
        String password = params.get("PASSWORD");
        if (username == null) {
            throw new AwsException("InvalidParameterException", "USERNAME is required", 400);
        }
        if (password == null) {
            throw new AwsException("InvalidParameterException", "PASSWORD is required", 400);
        }

        CognitoUser user = adminGetUser(pool.getId(), username);

        if (!user.isEnabled()) {
            throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
        }

        if ("UNCONFIRMED".equals(user.getUserStatus())) {
            throw new AwsException("UserNotConfirmedException", "User is not confirmed", 400);
        }

        if (user.getPasswordHash() != null && !user.getPasswordHash().equals(hashPassword(password))) {
            throw new AwsException("NotAuthorizedException", "Incorrect username or password", 400);
        }

        if (user.isTemporaryPassword() || "FORCE_CHANGE_PASSWORD".equals(user.getUserStatus())) {
            // Return a challenge instead of auth tokens
            String session = buildSessionToken(pool.getId(), username, clientId);
            Map<String, Object> result = new HashMap<>();
            result.put("ChallengeName", "NEW_PASSWORD_REQUIRED");
            result.put("Session", session);
            result.put("ChallengeParameters", Map.of(
                    "USER_ID_FOR_SRP", username,
                    "requiredAttributes", "[]",
                    "userAttributes", "{}"
            ));
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult", generateAuthResult(user, pool));
        return result;
    }

    private Map<String, Object> handleRefreshToken(UserPool pool, Map<String, String> params) {
        // In the emulator, accept any refresh token and return new tokens
        // A real implementation would validate the refresh token
        String refreshToken = params.get("REFRESH_TOKEN");
        if (refreshToken == null) {
            throw new AwsException("InvalidParameterException", "REFRESH_TOKEN is required", 400);
        }
        // We can't look up the user from a stub refresh token, so return a minimal result
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", generateTokenString("access", "unknown", pool));
        auth.put("IdToken", generateTokenString("id", "unknown", pool));
        auth.put("ExpiresIn", 3600);
        auth.put("TokenType", "Bearer");
        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult", auth);
        return result;
    }

    private Map<String, Object> generateAuthResult(CognitoUser user, UserPool pool) {
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", generateSignedJwt(user, pool, "access"));
        auth.put("IdToken", generateSignedJwt(user, pool, "id"));
        auth.put("RefreshToken", UUID.randomUUID().toString());
        auth.put("ExpiresIn", 3600);
        auth.put("TokenType", "Bearer");
        return auth;
    }

    private String generateSignedJwt(CognitoUser user, UserPool pool, String type) {
        String headerJson = String.format(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"%s\"}",
                escapeJson(getSigningKeyId(pool)));
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

        long now = System.currentTimeMillis() / 1000L;
        String email = user.getAttributes().getOrDefault("email", user.getUsername());
        String groupsFragment = "";
        if (!user.getGroupNames().isEmpty()) {
            String groupsJson = user.getGroupNames().stream()
                    .map(g -> "\"" + escapeJsonString(g) + "\"")
                    .collect(Collectors.joining(",", "[", "]"));
            groupsFragment = ",\"cognito:groups\":" + groupsJson;
        }
        String payloadJson = String.format(
                "{\"sub\":\"%s\",\"event_id\":\"%s\",\"token_use\":\"%s\",\"auth_time\":%d," +
                "\"iss\":\"%s\",\"exp\":%d,\"iat\":%d," +
                "\"username\":\"%s\",\"email\":\"%s\",\"cognito:username\":\"%s\"%s}",
                UUID.randomUUID(), UUID.randomUUID(), type, now,
                escapeJson(getIssuer(pool.getId())), now + 3600, now,
                user.getUsername(), email, user.getUsername(), groupsFragment
        );
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return signJwt(header, payload, getSigningPrivateKey(pool));
    }

    private String generateTokenString(String type, String username, UserPool pool) {
        long now = System.currentTimeMillis() / 1000L;
        String headerJson = String.format(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"%s\"}",
                escapeJson(getSigningKeyId(pool)));
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadJson = String.format(
                "{\"sub\":\"%s\",\"token_use\":\"%s\",\"iss\":\"%s\"," +
                "\"exp\":%d,\"iat\":%d,\"username\":\"%s\"}",
                UUID.randomUUID(), type, escapeJson(getIssuer(pool.getId())), now + 3600, now, username
        );
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return signJwt(header, payload, getSigningPrivateKey(pool));
    }

    private String generateClientAccessToken(UserPoolClient client, UserPool pool, String scope) {
        String headerJson = String.format(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"%s\"}",
                escapeJson(getSigningKeyId(pool)));
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

        long now = System.currentTimeMillis() / 1000L;
        StringBuilder payloadJson = new StringBuilder();
        payloadJson.append("{")
                .append("\"iss\":\"").append(escapeJson(getIssuer(pool.getId()))).append("\",")
                .append("\"version\":2,")
                .append("\"sub\":\"").append(escapeJson(client.getClientId())).append("\",")
                .append("\"client_id\":\"").append(escapeJson(client.getClientId())).append("\",")
                .append("\"token_use\":\"access\",")
                .append("\"exp\":").append(now + 3600).append(",")
                .append("\"iat\":").append(now).append(",")
                .append("\"jti\":\"").append(UUID.randomUUID()).append("\"");
        if (scope != null && !scope.isBlank()) {
            payloadJson.append(",\"scope\":\"").append(escapeJson(scope)).append("\"");
        }
        payloadJson.append("}");

        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.toString().getBytes(StandardCharsets.UTF_8));
        return signJwt(header, payload, getSigningPrivateKey(pool));
    }

    private void validateClientSecret(UserPoolClient client, String clientSecret) {
        String expectedSecret = client.getClientSecret();
        if (expectedSecret == null || expectedSecret.isBlank() || !client.isGenerateSecret()) {
            throw new AwsException("InvalidClientException", "Client must have a secret for client_credentials", 400);
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new AwsException("InvalidClientException", "Client secret is required", 400);
        }
        if (!expectedSecret.equals(clientSecret)) {
            throw new AwsException("InvalidClientException", "Client secret is invalid", 400);
        }
    }

    private void validateClientAllowsClientCredentials(UserPoolClient client) {
        if (!client.isAllowedOAuthFlowsUserPoolClient()) {
            throw new AwsException("UnauthorizedClientException", "Client is not enabled for OAuth flows", 400);
        }
        if (!client.getAllowedOAuthFlows().contains("client_credentials")) {
            throw new AwsException("UnauthorizedClientException", "Client is not allowed to use client_credentials", 400);
        }
    }

    private String resolveAuthorizedScopes(UserPoolClient client, String userPoolId, String requestedScope) {
        List<String> allowedScopes = normalizeStringList(client.getAllowedOAuthScopes());
        if (allowedScopes.isEmpty()) {
            throw new AwsException("InvalidScopeException", "Client has no allowed OAuth scopes", 400);
        }

        List<String> effectiveScopes;
        if (requestedScope == null || requestedScope.isBlank()) {
            effectiveScopes = allowedScopes;
        } else {
            effectiveScopes = Arrays.asList(normalizeRequestedScope(requestedScope).split(" "));
            for (String scope : effectiveScopes) {
                if (!allowedScopes.contains(scope)) {
                    throw new AwsException("InvalidScopeException", "Scope is not allowed for this client: " + scope, 400);
                }
            }
        }

        Set<String> validCustomScopes = new HashSet<>();
        for (ResourceServer server : listResourceServers(userPoolId)) {
            for (ResourceServerScope serverScope : server.getScopes()) {
                validCustomScopes.add(server.getIdentifier() + "/" + serverScope.getScopeName());
            }
        }

        for (String scope : effectiveScopes) {
            if (isBuiltInScope(scope)) {
                continue;
            }
            if (!validCustomScopes.contains(scope)) {
                throw new AwsException("InvalidScopeException", "Scope is invalid: " + scope, 400);
            }
        }

        return String.join(" ", effectiveScopes);
    }

    private String normalizeRequestedScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }

        List<String> normalized = new ArrayList<>();
        for (String part : scope.trim().split("\\s+")) {
            if (!part.isBlank()) {
                normalized.add(part);
            }
        }
        return normalized.isEmpty() ? null : String.join(" ", normalized);
    }

    private List<ResourceServerScope> normalizeScopes(List<ResourceServerScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }

        List<ResourceServerScope> normalized = new ArrayList<>();
        Set<String> scopeNames = new HashSet<>();
        for (ResourceServerScope scope : scopes) {
            if (scope == null || scope.getScopeName() == null || scope.getScopeName().isBlank()) {
                throw new AwsException("InvalidParameterException", "ScopeName is required", 400);
            }
            if (!scopeNames.add(scope.getScopeName())) {
                throw new AwsException("InvalidParameterException", "Duplicate scope name: " + scope.getScopeName(), 400);
            }
            ResourceServerScope normalizedScope = new ResourceServerScope();
            normalizedScope.setScopeName(scope.getScopeName());
            normalizedScope.setScopeDescription(scope.getScopeDescription());
            normalized.add(normalizedScope);
        }
        return normalized;
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private boolean isBuiltInScope(String scope) {
        return switch (scope) {
            case "phone", "email", "openid", "profile", "aws.cognito.signin.user.admin" -> true;
            default -> false;
        };
    }

    private String signJwt(String header, String payload, PrivateKey signingKey) {
        String signingInput = header + "." + payload;
        String signature = rsaSha256(signingInput, signingKey);
        return signingInput + "." + signature;
    }

    private String rsaSha256(String data, PrivateKey signingKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signingKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] sig = signature.sign();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("JWT signing failed", e);
        }
    }

    String getSigningKeyId(UserPool pool) {
        ensureJwtSigningKeys(pool);
        return pool.getSigningKeyId();
    }

    RSAPublicKey getSigningPublicKey(UserPool pool) {
        ensureJwtSigningKeys(pool);

        try {
            byte[] encoded = Base64.getDecoder().decode(pool.getSigningPublicKey());
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
            return (RSAPublicKey) publicKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Cognito RSA public key", e);
        }
    }

    private PrivateKey getSigningPrivateKey(UserPool pool) {
        ensureJwtSigningKeys(pool);

        try {
            byte[] encoded = Base64.getDecoder().decode(pool.getSigningPrivateKey());
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Cognito RSA private key", e);
        }
    }

    private boolean ensureJwtSigningKeys(UserPool pool) {
        synchronized (pool) {
            boolean changed = false;

            if (pool.getSigningKeyId() == null || pool.getSigningKeyId().isBlank()) {
                pool.setSigningKeyId(pool.getId());
                changed = true;
            }

            if (pool.getSigningPrivateKey() == null || pool.getSigningPrivateKey().isBlank()
                    || pool.getSigningPublicKey() == null || pool.getSigningPublicKey().isBlank()) {
                try {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(2048);
                    KeyPair keyPair = generator.generateKeyPair();

                    pool.setSigningPrivateKey(
                            Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
                    pool.setSigningPublicKey(
                            Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
                    changed = true;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to generate Cognito RSA signing keypair", e);
                }
            }

            if (changed && pool.getId() != null) {
                pool.setLastModifiedDate(System.currentTimeMillis() / 1000L);
            }

            return changed;
        }
    }

    String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    private String buildSessionToken(String poolId, String username, String clientId) {
        String raw = poolId + "|" + username + "|" + clientId + "|" + UUID.randomUUID();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String extractUsernameFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            // Simple extraction without full JSON parsing
            return extractJsonField(payloadJson, "username");
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPoolIdFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String iss = extractJsonField(payloadJson, "iss");
            if (iss == null) return null;
            int lastSlash = iss.lastIndexOf('/');
            return lastSlash >= 0 ? iss.substring(lastSlash + 1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void validateGroupName(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            throw new AwsException("InvalidParameterException", "GroupName is required", 400);
        }
    }

    private String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private String extractJsonField(String json, String field) {
        String search = "\"" + field + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private String userKey(String poolId, String username) {
        return poolId + "::" + username;
    }

    private String groupKey(String poolId, String groupName) {
        return poolId + "::" + groupName;
    }

    private String resourceServerKey(String userPoolId, String identifier) {
        return userPoolId + "::" + identifier;
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String generateSecretValue() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
