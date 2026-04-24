package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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
    private final RegionResolver regionResolver;

    // Keyed by session token; contains SRP ephemeral state (bPrivate, B, A, secretBlock)
    private final ConcurrentHashMap<String, SrpSession> srpSessions = new ConcurrentHashMap<>();

    private record SrpSession(String userPoolId, String username, String clientId,
                               String aHex, String bHex, String bPublicHex,
                               String secretBlockBase64) {}

    @Inject
    public CognitoService(StorageFactory storageFactory, EmulatorConfig emulatorConfig, RegionResolver regionResolver) {
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
        this.regionResolver = regionResolver;
    }

    CognitoService(StorageBackend<String, UserPool> poolStore,
                   StorageBackend<String, UserPoolClient> clientStore,
                   StorageBackend<String, ResourceServer> resourceServerStore,
                   StorageBackend<String, CognitoUser> userStore,
                   StorageBackend<String, CognitoGroup> groupStore,
                   String baseUrl,
                   RegionResolver regionResolver) {
        this.poolStore = poolStore;
        this.clientStore = clientStore;
        this.resourceServerStore = resourceServerStore;
        this.userStore = userStore;
        this.groupStore = groupStore;
        this.baseUrl = baseUrl;
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── User Pools ────────────────────────────

    @SuppressWarnings("unchecked")
    public UserPool createUserPool(Map<String, Object> request, String region) {
        String name = (String) request.get("PoolName");
        Map<String, String> userPoolTags = (Map<String, String>) request.get("UserPoolTags");
        String id = resolveUserPoolId(region, userPoolTags);
        if (poolStore.get(id).isPresent()) {
            throw new AwsException("ResourceConflictException", "User pool already exists", 400);
        }
        UserPool pool = new UserPool();
        pool.setId(id);
        pool.setName(name);
        pool.setArn(regionResolver.buildArn("cognito-idp", region, "userpool/" + id));

        populateUserPool(pool, request);

        ensureJwtSigningKeys(pool);
        poolStore.put(id, pool);
        LOG.infov("Created User Pool: {0}", id);
        return pool;
    }

    public UserPool updateUserPool(Map<String, Object> request, String region) {
        String id = (String) request.get("UserPoolId");
        UserPool pool = describeUserPool(id);

        populateUserPool(pool, request);

        pool.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        poolStore.put(id, pool);
        LOG.infov("Updated User Pool: {0}", id);
        return pool;
    }

    @SuppressWarnings("unchecked")
    private void populateUserPool(UserPool pool, Map<String, Object> request) {
        if (request.containsKey("Policies")) pool.setPolicies((Map<String, Object>) request.get("Policies"));
        if (request.containsKey("DeletionProtection")) pool.setDeletionProtection((String) request.get("DeletionProtection"));
        if (request.containsKey("LambdaConfig")) pool.setLambdaConfig((Map<String, Object>) request.get("LambdaConfig"));
        if (request.containsKey("Schema")) pool.setSchemaAttributes((List<Map<String, Object>>) request.get("Schema"));
        if (request.containsKey("AutoVerifiedAttributes")) pool.setAutoVerifiedAttributes((List<String>) request.get("AutoVerifiedAttributes"));
        if (request.containsKey("AliasAttributes")) pool.setAliasAttributes((List<String>) request.get("AliasAttributes"));
        if (request.containsKey("UsernameAttributes")) pool.setUsernameAttributes((List<String>) request.get("UsernameAttributes"));
        if (request.containsKey("SmsVerificationMessage")) pool.setSmsVerificationMessage((String) request.get("SmsVerificationMessage"));
        if (request.containsKey("EmailVerificationMessage")) pool.setEmailVerificationMessage((String) request.get("EmailVerificationMessage"));
        if (request.containsKey("EmailVerificationSubject")) pool.setEmailVerificationSubject((String) request.get("EmailVerificationSubject"));
        if (request.containsKey("VerificationMessageTemplate")) pool.setVerificationMessageTemplate((Map<String, Object>) request.get("VerificationMessageTemplate"));
        if (request.containsKey("SmsAuthenticationMessage")) pool.setSmsAuthenticationMessage((String) request.get("SmsAuthenticationMessage"));
        if (request.containsKey("MfaConfiguration")) pool.setMfaConfiguration((String) request.get("MfaConfiguration"));
        if (request.containsKey("DeviceConfiguration")) pool.setDeviceConfiguration((Map<String, Object>) request.get("DeviceConfiguration"));
        if (request.containsKey("EmailConfiguration")) pool.setEmailConfiguration((Map<String, Object>) request.get("EmailConfiguration"));
        if (request.containsKey("SmsConfiguration")) pool.setSmsConfiguration((Map<String, Object>) request.get("SmsConfiguration"));
        if (request.containsKey("UserPoolTags")) {
            pool.setUserPoolTags(ReservedTags.stripReservedTags((Map<String, String>) request.get("UserPoolTags")));
        }
        if (request.containsKey("AdminCreateUserConfig")) pool.setAdminCreateUserConfig((Map<String, Object>) request.get("AdminCreateUserConfig"));
        if (request.containsKey("UserPoolAddOns")) pool.setUserPoolAddOns((Map<String, Object>) request.get("UserPoolAddOns"));
        if (request.containsKey("UsernameConfiguration")) pool.setUsernameConfiguration((Map<String, Object>) request.get("UsernameConfiguration"));
        if (request.containsKey("AccountRecoverySetting")) pool.setAccountRecoverySetting((Map<String, Object>) request.get("AccountRecoverySetting"));
        if (request.containsKey("UserPoolTier")) pool.setUserPoolTier((String) request.get("UserPoolTier"));
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

    private UserPool describeUserPoolByArn(String resourceArn) {
        String poolId = extractUserPoolIdFromArn(resourceArn);
        return describeUserPool(poolId);
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Tags are required", 400);
        }
        ReservedTags.rejectReservedTagsOnUpdate(tags);
        UserPool pool = describeUserPoolByArn(resourceArn);
        synchronized (pool) {
            pool.setUserPoolTags(mergeUserPoolTags(pool.getUserPoolTags(), tags));
            pool.setLastModifiedDate(System.currentTimeMillis() / 1000L);
            poolStore.put(pool.getId(), pool);
        }
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("InvalidParameterException", "TagKeys are required", 400);
        }
        UserPool pool = describeUserPoolByArn(resourceArn);
        synchronized (pool) {
            pool.setUserPoolTags(removeUserPoolTags(pool.getUserPoolTags(), tagKeys));
            pool.setLastModifiedDate(System.currentTimeMillis() / 1000L);
            poolStore.put(pool.getId(), pool);
        }
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        UserPool pool = describeUserPoolByArn(resourceArn);
        return new HashMap<>(pool.getUserPoolTags() != null ? pool.getUserPoolTags() : Map.of());
    }

    private static String extractUserPoolIdFromArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidParameterException", "ResourceArn is required", 400);
        }
        // arn:aws:cognito-idp:<region>:<account>:userpool/<pool-id>
        String[] parts = resourceArn.split(":", 6);
        if (parts.length < 6 || !"cognito-idp".equals(parts[2])) {
            throw new AwsException("InvalidParameterException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String resource = parts[5];
        if (!resource.startsWith("userpool/")) {
            throw new AwsException("InvalidParameterException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String poolId = resource.substring("userpool/".length());
        if (poolId.isBlank()) {
            throw new AwsException("InvalidParameterException", "Invalid resource ARN: " + resourceArn, 400);
        }
        return poolId;
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
            String clientSecret = generateSecretValue();
            client.setClientSecret(clientSecret);

            long epochMillis = System.currentTimeMillis();
            UserPoolClientSecret userPoolClientSecret = new UserPoolClientSecret(
                    clientId + "--" + epochMillis,
                    epochMillis / 1000,
                    clientSecret
            );

            client.getUserPoolClientSecrets().add(userPoolClientSecret);
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

    public UserPoolClient updateUserPoolClient(String userPoolId, String clientId, String clientName,
                                               Boolean allowedOAuthFlowsUserPoolClient,
                                               List<String> allowedOAuthFlows,
                                               List<String> allowedOAuthScopes) {
        UserPoolClient client = describeUserPoolClient(userPoolId, clientId);
        if (clientName != null) client.setClientName(clientName);
        if (allowedOAuthFlowsUserPoolClient != null) {
            client.setAllowedOAuthFlowsUserPoolClient(allowedOAuthFlowsUserPoolClient);
        }
        if (allowedOAuthFlows != null) {
            client.setAllowedOAuthFlows(normalizeStringList(allowedOAuthFlows));
        }
        if (allowedOAuthScopes != null) {
            client.setAllowedOAuthScopes(normalizeStringList(allowedOAuthScopes));
        }

        client.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        clientStore.put(clientId, client);
        LOG.infov("Updated User Pool Client: {0} for pool {1}", clientId, userPoolId);
        return client;
    }

    public List<UserPoolClientSecret> listUserPoolClientSecrets(String userPoolId, String clientId) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "User pool client not found", 404));
        if (!client.getUserPoolId().equals(userPoolId)) {
            throw new AwsException("ResourceNotFoundException", "User pool client not found", 404);
        }
        return client.getUserPoolClientSecrets();
    }

    public UserPoolClientSecret addUserPoolClientSecret(String clientId, String clientSecret, String userPoolId) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "User pool client not found", 404));
        if (!client.getUserPoolId().equals(userPoolId)) {
            throw new AwsException("ResourceNotFoundException", "User pool client not found", 404);
        }

        if (client.getUserPoolClientSecrets().size() >= 2) {
            throw new AwsException("LimitExceededException", "Client secrets cannot exceed limit of 2 secrets.", 400);
        }

        if (clientSecret == null) {
            clientSecret = generateSecretValue();
        } else if (!clientSecret.matches("\\w{24,64}")) {
            throw new AwsException("InvalidParameterException",
                    "Client secret format is invalid.", 400);
        }
        long epochMillis = System.currentTimeMillis();
        UserPoolClientSecret userPoolClientSecret = new UserPoolClientSecret(
                clientId + "--" + epochMillis,
                epochMillis / 1000,
                clientSecret
        );

        client.getUserPoolClientSecrets().add(userPoolClientSecret);

        return userPoolClientSecret;
    }

    public void deleteUserPoolClientSecret(String clientId, String clientSecretId, String userPoolId) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "User pool client not found", 404));
        if (!client.getUserPoolId().equals(userPoolId)) {
            throw new AwsException("ResourceNotFoundException", "User pool client not found", 404);
        }

        UserPoolClientSecret userPoolClientSecret = client.getUserPoolClientSecrets().stream()
                .filter(s -> s.getClientSecretId().equals(clientSecretId))
                .findFirst()
                .orElseThrow(() -> new AwsException(
                        "ResourceNotFoundException", "Client secret does not exist", 404));

        if (client.getUserPoolClientSecrets().size() <= 1) {
            throw new AwsException(
                    "InvalidParameterException", "Cannot delete the only " +
                    "client secret.", 400
            );
        }

        if (userPoolClientSecret.getClientSecretValue().equals(client.getClientSecret())) {
            client.setClientSecret(null);
        }

        client.getUserPoolClientSecrets().remove(userPoolClientSecret);
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

        // Ensure sub attribute is present
        if (!user.getAttributes().containsKey("sub")) {
            user.getAttributes().put("sub", UUID.randomUUID().toString());
        }

        if (temporaryPassword != null && !temporaryPassword.isEmpty()) {
            updateUserPassword(user, temporaryPassword);
            user.setTemporaryPassword(true);
            user.setUserStatus("FORCE_CHANGE_PASSWORD");
        }

        userStore.put(key, user);
        LOG.infov("Created user {0} in pool {1}", username, userPoolId);
        return user;
    }

    public void adminUserGlobalSignOut(String userPoolId, String username) {
        adminGetUser(userPoolId, username);
        LOG.infov("AdminUserGlobalSignOut stub: user {0} in pool {1} signed out globally", username, userPoolId);
    }

    public CognitoUser adminGetUser(String userPoolId, String username) {
        Optional<CognitoUser> byKey = userStore.get(userKey(userPoolId, username));
        if (byKey.isPresent()) {
            return byKey.get();
        }
        // Fallback: resolve by sub UUID or email alias
        String prefix = userPoolId + "::";
        return userStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(u -> username.equals(u.getAttributes().get("sub"))
                          || username.equals(u.getAttributes().get("email")))
                .findFirst()
                .orElseThrow(() -> new AwsException("UserNotFoundException", "User not found", 404));
    }

    public void adminDeleteUser(String userPoolId, String username) {
        CognitoUser user = adminGetUser(userPoolId, username);
        for (String groupName : new ArrayList<>(user.getGroupNames())) {
            groupStore.get(groupKey(userPoolId, groupName)).ifPresent(group -> {
                group.removeUserName(user.getUsername());
                group.setLastModifiedDate(System.currentTimeMillis() / 1000L);
                groupStore.put(groupKey(userPoolId, groupName), group);
            });
        }
        userStore.delete(userKey(userPoolId, user.getUsername()));
    }

    public void adminSetUserPassword(String userPoolId, String username, String password, boolean permanent) {
        CognitoUser user = adminGetUser(userPoolId, username);
        updateUserPassword(user, password);
        user.setTemporaryPassword(!permanent);
        user.setUserStatus(permanent ? "CONFIRMED" : "FORCE_CHANGE_PASSWORD");
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, user.getUsername()), user);
        LOG.infov("Set password for user {0} in pool {1} (permanent={2})", user.getUsername(), userPoolId, permanent);
    }

    public void adminUpdateUserAttributes(String userPoolId, String username, Map<String, String> attributes) {
        CognitoUser user = adminGetUser(userPoolId, username);
        user.getAttributes().putAll(attributes);
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, user.getUsername()), user);
    }

    public void adminEnableUser(String userPoolId, String username) {
        CognitoUser user = adminGetUser(userPoolId, username);
        user.setEnabled(true);
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, user.getUsername()), user);
        LOG.infov("Enabled user {0} in pool {1}", user.getUsername(), userPoolId);
    }

    public void adminDisableUser(String userPoolId, String username) {
        CognitoUser user = adminGetUser(userPoolId, username);
        user.setEnabled(false);
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, user.getUsername()), user);
        LOG.infov("Disabled user {0} in pool {1}", user.getUsername(), userPoolId);
    }

    public void adminResetUserPassword(String userPoolId, String username) {
        CognitoUser user = adminGetUser(userPoolId, username);
        user.setUserStatus("RESET_REQUIRED");
        user.setPasswordHash(null);
        user.setSrpVerifier(null);
        user.setSrpSalt(null);
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, user.getUsername()), user);
        LOG.infov("Reset password for user {0} in pool {1}", user.getUsername(), userPoolId);
    }

    public List<CognitoUser> listUsers(String userPoolId, String filter) {
        String prefix = userPoolId + "::";
        List<CognitoUser> all = userStore.scan(k -> k.startsWith(prefix));
        if (filter == null || filter.isBlank()) {
            return all;
        }
        return all.stream().filter(u -> matchesUserFilter(u, filter)).toList();
    }

    private boolean matchesUserFilter(CognitoUser user, String filter) {
        String originalFilter = filter;
        filter = filter.trim();
        boolean startsWithOp = filter.contains("^=");
        int opIdx = startsWithOp ? filter.indexOf("^=") : filter.indexOf('=');
        if (opIdx < 0) {
            throw new AwsException("InvalidParameterException", "Invalid filter expression: " + filter, 400);
        }
        String attrName = filter.substring(0, opIdx).trim();
        String rawValue = filter.substring(opIdx + (startsWithOp ? 2 : 1)).trim();
        if (rawValue.length() >= 2 && rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
            rawValue = rawValue.substring(1, rawValue.length() - 1);
        }
        String attrValue = getUserAttribute(user, attrName);
        boolean matches = false;
        if (attrValue != null) {
            matches = startsWithOp ? attrValue.startsWith(rawValue) : attrValue.equals(rawValue);
        }
        LOG.infov("Matching user {0} against filter [{1}]: attrName=[{2}], rawValue=[{3}], attrValue=[{4}], matches={5}",
                user.getUsername(), originalFilter, attrName, rawValue, attrValue, matches);
        return matches;
    }

    private String getUserAttribute(CognitoUser user, String attrName) {
        return switch (attrName) {
            case "username" -> user.getUsername();
            case "cognito:user_status", "status" -> user.getUserStatus();
            default -> user.getAttributes().get(attrName);
        };
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
                    userStore.put(userKey(userPoolId, user.getUsername()), user);
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
        if (group.addUserName(user.getUsername())) {
            group.setLastModifiedDate(now);
            groupStore.put(groupKey(userPoolId, groupName), group);
        }
        if (!user.getGroupNames().contains(groupName)) {
            user.getGroupNames().add(groupName);
            user.setLastModifiedDate(now);
            userStore.put(userKey(userPoolId, user.getUsername()), user);
        }
    }

    public void adminRemoveUserFromGroup(String userPoolId, String groupName, String username) {
        CognitoGroup group = getGroup(userPoolId, groupName);
        CognitoUser user = adminGetUser(userPoolId, username);
        long now = System.currentTimeMillis() / 1000L;
        if (group.removeUserName(user.getUsername())) {
            group.setLastModifiedDate(now);
            groupStore.put(groupKey(userPoolId, groupName), group);
        }
        if (user.getGroupNames().remove(groupName)) {
            user.setLastModifiedDate(now);
            userStore.put(userKey(userPoolId, user.getUsername()), user);
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
        updateUserPassword(user, password);
        user.setUserStatus("UNCONFIRMED");
        if (attributes != null) {
            user.getAttributes().putAll(attributes);
        }

        // Ensure sub attribute is present
        if (!user.getAttributes().containsKey("sub")) {
            user.getAttributes().put("sub", UUID.randomUUID().toString());
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
        userStore.put(userKey(client.getUserPoolId(), user.getUsername()), user);
    }

    // ──────────────────────────── Auth ────────────────────────────

    public Map<String, Object> initiateAuth(String clientId, String authFlow, Map<String, String> authParameters) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        UserPool pool = describeUserPool(client.getUserPoolId());

        return switch (authFlow) {
            case "USER_PASSWORD_AUTH" -> authenticateWithPassword(pool, authParameters, clientId);
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> handleRefreshToken(pool, authParameters, clientId);
            case "USER_SRP_AUTH" -> handleUserSrpAuth(pool, client, authParameters);
            default -> {
                // For other flows, if user exists return tokens
                String username = authParameters.get("USERNAME");
                if (username == null) {
                    throw new AwsException("InvalidParameterException", "USERNAME is required", 400);
                }
                CognitoUser user = adminGetUser(pool.getId(), username);
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult", generateAuthResult(user, pool, clientId));
                yield result;
            }
        };
    }

    public Map<String, Object> adminInitiateAuth(String userPoolId, String clientId, String authFlow,
                                                  Map<String, String> authParameters) {
        UserPoolClient client = describeUserPoolClient(userPoolId, clientId);
        UserPool pool = describeUserPool(userPoolId);

        String username = authParameters.get("USERNAME");
        if (username != null) {
            CognitoUser user = adminGetUser(userPoolId, username);
            if ("RESET_REQUIRED".equals(user.getUserStatus())) {
                throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
            }
        }

        return switch (authFlow) {
            case "ADMIN_USER_PASSWORD_AUTH", "USER_PASSWORD_AUTH" ->
                    authenticateWithPassword(pool, authParameters, clientId);
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> handleRefreshToken(pool, authParameters, clientId);
            case "ADMIN_USER_SRP_AUTH" -> handleUserSrpAuth(pool, client, authParameters);
            default -> {
                CognitoUser user = adminGetUser(userPoolId, username);
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult", generateAuthResult(user, pool, clientId));
                yield result;
            }
        };
    }

    private Map<String, Object> handleUserSrpAuth(UserPool pool, UserPoolClient client, Map<String, String> authParameters) {
        String username = authParameters.get("USERNAME");
        String aHex = authParameters.get("SRP_A");

        if (username == null || aHex == null) {
            throw new AwsException("InvalidParameterException", "USERNAME and SRP_A are required", 400);
        }

        CognitoUser user = adminGetUser(pool.getId(), username);
        if (!user.isEnabled()) {
            throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
        }
        if ("RESET_REQUIRED".equals(user.getUserStatus())) {
            throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
        }

        if (user.getSrpVerifier() == null) {
            throw new AwsException("NotAuthorizedException", "User does not support SRP auth", 400);
        }

        String[] serverB = CognitoSrpHelper.generateServerB(user.getSrpVerifier());
        String bHex = serverB[0];
        String bPublicHex = serverB[1];

        String sessionToken = buildSessionToken(pool.getId(), user.getUsername(), client.getClientId());

        byte[] secretBlock = new byte[16];
        new java.security.SecureRandom().nextBytes(secretBlock);
        String secretBlockBase64 = Base64.getEncoder().encodeToString(secretBlock);

        srpSessions.put(sessionToken, new SrpSession(
                pool.getId(),
                user.getUsername(),
                client.getClientId(),
                aHex,
                bHex,
                bPublicHex,
                secretBlockBase64
        ));

        Map<String, Object> result = new HashMap<>();
        result.put("ChallengeName", "PASSWORD_VERIFIER");
        result.put("Session", sessionToken);
        result.put("ChallengeParameters", Map.of(
                "SALT", user.getSrpSalt(),
                "SRP_B", bPublicHex,
                "SECRET_BLOCK", secretBlockBase64,
                "USER_ID_FOR_SRP", user.getUsername()
        ));
        return result;
    }

    private Map<String, Object> handlePasswordVerifierChallenge(UserPool pool, UserPoolClient client,
                                                                 String session, Map<String, String> responses) {
        SrpSession srp = srpSessions.get(session);
        if (srp == null) {
            throw new AwsException("NotAuthorizedException", "Session not found", 400);
        }

        String username = responses.get("USERNAME");
        String claimSignature = responses.get("PASSWORD_CLAIM_SIGNATURE");
        String timestamp = responses.get("TIMESTAMP");

        if (username == null || claimSignature == null || timestamp == null) {
            throw new AwsException("InvalidParameterException", "USERNAME, PASSWORD_CLAIM_SIGNATURE and TIMESTAMP are required", 400);
        }

        CognitoUser user = adminGetUser(pool.getId(), username);
        if (!user.isEnabled()) {
            throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
        }
        if ("RESET_REQUIRED".equals(user.getUserStatus())) {
            throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
        }

        if (user.getSrpVerifier() == null) {
            throw new AwsException("NotAuthorizedException", "User does not support SRP auth", 400);
        }

        byte[] sessionKey = CognitoSrpHelper.computeSessionKey(srp.aHex(), srp.bHex(), srp.bPublicHex(), user.getSrpVerifier());
        byte[] secretBlock = Base64.getDecoder().decode(srp.secretBlockBase64());

        boolean valid = CognitoSrpHelper.verifySignature(sessionKey, pool.getId(), user.getUsername(), secretBlock, timestamp, claimSignature);

        if (!valid) {
            throw new AwsException("NotAuthorizedException", "Incorrect username or password", 400);
        }

        // Session consumed
        srpSessions.remove(session);

        if (user.isTemporaryPassword() || "FORCE_CHANGE_PASSWORD".equals(user.getUserStatus())) {
            String newSession = buildSessionToken(pool.getId(), username, client.getClientId());
            Map<String, Object> result = new HashMap<>();
            result.put("ChallengeName", "NEW_PASSWORD_REQUIRED");
            result.put("Session", newSession);
            result.put("ChallengeParameters", Map.of(
                    "USER_ID_FOR_SRP", username,
                    "requiredAttributes", "[]",
                    "userAttributes", "{}"
            ));
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult", generateAuthResult(user, pool, client.getClientId()));
        return result;
    }

    public Map<String, Object> respondToAuthChallenge(String clientId, String challengeName,
                                                       String session, Map<String, String> responses) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        UserPool pool = describeUserPool(client.getUserPoolId());

        if ("PASSWORD_VERIFIER".equals(challengeName)) {
            return handlePasswordVerifierChallenge(pool, client, session, responses);
        }

        if ("NEW_PASSWORD_REQUIRED".equals(challengeName)) {
            String username = responses.get("USERNAME");
            String newPassword = responses.get("NEW_PASSWORD");
            if (username == null || newPassword == null) {
                throw new AwsException("InvalidParameterException", "USERNAME and NEW_PASSWORD are required", 400);
            }
            adminSetUserPassword(pool.getId(), username, newPassword, true);
            CognitoUser user = adminGetUser(pool.getId(), username);
            Map<String, Object> result = new HashMap<>();
            result.put("AuthenticationResult", generateAuthResult(user, pool, clientId));
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

        updateUserPassword(user, proposedPassword);
        user.setTemporaryPassword(false);
        user.setUserStatus("CONFIRMED");
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(poolId, user.getUsername()), user);
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

    private String resolveUserPoolId(String region, Map<String, String> tags) {
        String overrideId = ReservedTags.extractOverrideId(tags);
        if (overrideId == null) {
            return region + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        }
        validateOverridePoolId(overrideId);
        return overrideId.trim();
    }

    private void validateOverridePoolId(String overrideId) {
        if (overrideId == null || overrideId.trim().isEmpty()) {
            throw new AwsException("ValidationException", "Override resource ID must not be blank.", 400);
        }

        String normalized = overrideId.trim();
        if (normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new AwsException("ValidationException", "Override resource ID must not contain whitespace.", 400);
        }
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('?') >= 0 || normalized.indexOf('#') >= 0) {
            throw new AwsException("ValidationException", "Override resource ID contains unsupported characters.", 400);
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new AwsException("ValidationException", "Override resource ID must not contain control characters.", 400);
        }
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

        if ("RESET_REQUIRED".equals(user.getUserStatus())) {
            throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
        }

        if ("UNCONFIRMED".equals(user.getUserStatus())) {
            throw new AwsException("UserNotConfirmedException", "User is not confirmed", 400);
        }

        if (user.getPasswordHash() == null || !user.getPasswordHash().equals(hashPassword(password))) {
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
        result.put("AuthenticationResult", generateAuthResult(user, pool, clientId));
        return result;
    }

    private Map<String, Object> handleRefreshToken(UserPool pool, Map<String, String> params, String clientId) {
        String refreshToken = params.get("REFRESH_TOKEN");
        if (refreshToken == null) {
            throw new AwsException("InvalidParameterException", "REFRESH_TOKEN is required", 400);
        }
        String[] parts = parseRefreshToken(refreshToken);
        if (parts != null) {
            String username = parts[1];
            String tokenClientId = parts[2];
            try {
                CognitoUser user = adminGetUser(pool.getId(), username);
                Map<String, Object> auth = new HashMap<>();
                auth.put("AccessToken", generateSignedJwt(user, pool, "access", tokenClientId));
                auth.put("IdToken", generateSignedJwt(user, pool, "id", tokenClientId));
                auth.put("ExpiresIn", 3600);
                auth.put("TokenType", "Bearer");
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult", auth);
                return result;
            } catch (AwsException ignored) { }
        }
        // Fallback for legacy tokens: emit minimal tokens using clientId from request
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", generateTokenString("access", "unknown", pool, clientId));
        auth.put("IdToken", generateTokenString("id", "unknown", pool, clientId));
        auth.put("ExpiresIn", 3600);
        auth.put("TokenType", "Bearer");
        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult", auth);
        return result;
    }

    public Map<String, Object> getTokensFromRefreshToken(String clientId, String refreshToken) {
        if (refreshToken == null) {
            throw new AwsException("InvalidParameterException", "RefreshToken is required", 400);
        }
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        String[] parts = parseRefreshToken(refreshToken);
        if (parts == null) {
            throw new AwsException("NotAuthorizedException", "Invalid refresh token", 400);
        }
        String poolId = parts[0];
        String username = parts[1];
        if (!client.getUserPoolId().equals(poolId)) {
            throw new AwsException("NotAuthorizedException", "Invalid refresh token", 400);
        }
        UserPool pool = describeUserPool(poolId);
        CognitoUser user = adminGetUser(poolId, username);
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", generateSignedJwt(user, pool, "access", clientId));
        auth.put("IdToken", generateSignedJwt(user, pool, "id", clientId));
        auth.put("ExpiresIn", 3600);
        auth.put("TokenType", "Bearer");
        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult", auth);
        return result;
    }

    private Map<String, Object> generateAuthResult(CognitoUser user, UserPool pool, String clientId) {
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", generateSignedJwt(user, pool, "access", clientId));
        auth.put("IdToken", generateSignedJwt(user, pool, "id", clientId));
        auth.put("RefreshToken", buildRefreshToken(pool.getId(), user.getUsername(), clientId));
        auth.put("ExpiresIn", 3600);
        auth.put("TokenType", "Bearer");
        return auth;
    }

    private String generateSignedJwt(CognitoUser user, UserPool pool, String type, String clientId) {
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
        String sub = user.getAttributes().getOrDefault("sub", user.getUsername());
        String clientIdFragment = (clientId != null && !clientId.isBlank() && "access".equals(type))
                ? ",\"client_id\":\"" + escapeJson(clientId) + "\""
                : "";
        String audFragment = (clientId != null && !clientId.isBlank() && "id".equals(type))
                ? ",\"aud\":\"" + escapeJson(clientId) + "\""
                : "";
        String payloadJson = String.format(
                "{\"sub\":\"%s\",\"event_id\":\"%s\",\"token_use\":\"%s\",\"auth_time\":%d," +
                "\"iss\":\"%s\",\"exp\":%d,\"iat\":%d," +
                "\"username\":\"%s\",\"email\":\"%s\",\"cognito:username\":\"%s\"%s%s%s}",
                escapeJson(sub), UUID.randomUUID(), type, now,
                escapeJson(getIssuer(pool.getId())), now + 3600, now,
                user.getUsername(), email, user.getUsername(), clientIdFragment, audFragment, groupsFragment
        );
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return signJwt(header, payload, getSigningPrivateKey(pool));
    }

    private String generateTokenString(String type, String username, UserPool pool, String clientId) {
        long now = System.currentTimeMillis() / 1000L;
        String headerJson = String.format(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"%s\"}",
                escapeJson(getSigningKeyId(pool)));
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String audFragment = (clientId != null && !clientId.isBlank() && "id".equals(type))
                ? ",\"aud\":\"" + escapeJson(clientId) + "\""
                : "";
        String payloadJson = String.format(
                "{\"sub\":\"%s\",\"token_use\":\"%s\",\"iss\":\"%s\"," +
                "\"exp\":%d,\"iat\":%d,\"username\":\"%s\"%s}",
                UUID.randomUUID(), type, escapeJson(getIssuer(pool.getId())), now + 3600, now, username, audFragment
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
        if (client.getUserPoolClientSecrets().isEmpty()
                && (expectedSecret == null || expectedSecret.isBlank() || !client.isGenerateSecret())) {
            throw new AwsException("InvalidClientException", "Client must have a secret for client_credentials", 400);
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new AwsException("InvalidClientException", "Client secret is required", 400);
        }
        for (UserPoolClientSecret userPoolClientSecret : client.getUserPoolClientSecrets()) {
            if (clientSecret.equals(userPoolClientSecret.getClientSecretValue())) {
                return;
            }
        }
        // for "legacy" clients
        if (expectedSecret != null && expectedSecret.equals(clientSecret)) {
            return;
        }
        throw new AwsException("InvalidClientException", "Client secret is invalid", 400);
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

    private void updateUserPassword(CognitoUser user, String password) {
        String saltHex = CognitoSrpHelper.generateSalt();
        String verifierHex = CognitoSrpHelper.computeVerifier(
                CognitoSrpHelper.extractPoolName(user.getUserPoolId()),
                user.getUsername(),
                password,
                saltHex
        );
        user.setPasswordHash(hashPassword(password));
        user.setSrpSalt(saltHex);
        user.setSrpVerifier(verifierHex);
    }

    private String buildSessionToken(String poolId, String username, String clientId) {
        String raw = poolId + "|" + username + "|" + clientId + "|" + UUID.randomUUID();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String buildRefreshToken(String poolId, String username, String clientId) {
        String raw = poolId + "|" + username + "|" + clientId + "|" + UUID.randomUUID();
        return Base64.getEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String[] parseRefreshToken(String refreshToken) {
        try {
            byte[] decoded = Base64.getDecoder().decode(refreshToken);
            String raw = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 4);
            if (parts.length == 4) {
                return parts; // [poolId, username, clientId, nonce]
            }
        } catch (Exception ignored) { }
        return null;
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

    private Map<String, String> mergeUserPoolTags(Map<String, String> existingTags, Map<String, String> tagsToAdd) {
        Map<String, String> merged = new HashMap<>(existingTags != null ? existingTags : Map.of());
        merged.putAll(tagsToAdd);
        return merged;
    }

    private Map<String, String> removeUserPoolTags(Map<String, String> existingTags, List<String> tagKeys) {
        Map<String, String> updated = new HashMap<>(existingTags != null ? existingTags : Map.of());
        tagKeys.forEach(updated::remove);
        return updated;
    }

    private String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
