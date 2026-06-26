package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.*;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeException;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class CognitoService {
    private static final int DEFAULT_REFRESH_TOKEN_VALIDITY_DAYS = 30;

    private static final Logger LOG = Logger.getLogger(CognitoService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Claim overrides returned by a PreTokenGeneration Lambda trigger.
     *
     * Supports both V1 (single claims map applied to both id and access tokens)
     * and V2 (per-token-type claim overrides + scope changes for the access
     * token). For V1 lambdas the parser populates the id/access slots with the
     * same map.
     */
    public record ClaimsOverride(Map<String, Object> idClaimsToAddOrOverride,
                                  List<String> idClaimsToSuppress,
                                  Map<String, Object> accessClaimsToAddOrOverride,
                                  List<String> accessClaimsToSuppress,
                                  List<String> scopesToAdd,
                                  List<String> scopesToSuppress,
                                  List<String> groupsToOverride,
                                  List<String> iamRolesToOverride,
                                  String preferredRole) {}

    private final StorageBackend<String, UserPool> poolStore;
    private final StorageBackend<String, UserPoolClient> clientStore;
    private final StorageBackend<String, ResourceServer> resourceServerStore;
    private final StorageBackend<String, CognitoUser> userStore;
    private final StorageBackend<String, CognitoGroup> groupStore;
    private final StorageBackend<String, RevokedTokenInfo> revokedTokenStore;
    private final String baseUrl;
    private final RegionResolver regionResolver;
    private final LambdaService lambdaService;
    private final VerificationCodeService verificationCodeService;
    private final CognitoMessageDispatcher messageDispatcher;

    // Keyed by session token; contains SRP ephemeral state (bPrivate, B, A, secretBlock)
    private final CognitoAuthFlowHandler authFlowHandler;

    @Inject
    public CognitoService(StorageFactory storageFactory, EmulatorConfig emulatorConfig,
            RegionResolver regionResolver, LambdaService lambdaService, SesService sesService,
            SnsService snsService, Clock clock) {
        this(
                storageFactory.create("cognito", "cognito-pools.json",
                        new TypeReference<Map<String, UserPool>>() {}),
                storageFactory.create("cognito", "cognito-clients.json",
                        new TypeReference<Map<String, UserPoolClient>>() {}),
                storageFactory.create("cognito", "cognito-resource-servers.json",
                        new TypeReference<Map<String, ResourceServer>>() {}),
                storageFactory.create("cognito", "cognito-users.json",
                        new TypeReference<Map<String, CognitoUser>>() {}),
                storageFactory.create("cognito", "cognito-groups.json",
                        new TypeReference<Map<String, CognitoGroup>>() {}),
                storageFactory.create("cognito", "cognito-revoked-tokens.json",
                        new TypeReference<Map<String, RevokedTokenInfo>>() {}),
                trimTrailingSlash(emulatorConfig.baseUrl()),
                regionResolver,
                lambdaService,
                new VerificationCodeService(storageFactory, clock),
                new CognitoMessageDispatcher(sesService, snsService)
        );
    }

    CognitoService(StorageBackend<String, UserPool> poolStore,
                   StorageBackend<String, UserPoolClient> clientStore,
                   StorageBackend<String, ResourceServer> resourceServerStore,
                   StorageBackend<String, CognitoUser> userStore,
                   StorageBackend<String, CognitoGroup> groupStore,
                   StorageBackend<String, RevokedTokenInfo> revokedTokenStore,
                   String baseUrl,
                   RegionResolver regionResolver,
                   LambdaService lambdaService) {
        this(poolStore, clientStore, resourceServerStore, userStore, groupStore, revokedTokenStore, baseUrl,
                regionResolver, lambdaService, null, null);
    }

    CognitoService(StorageBackend<String, UserPool> poolStore,
            StorageBackend<String, UserPoolClient> clientStore,
            StorageBackend<String, ResourceServer> resourceServerStore,
            StorageBackend<String, CognitoUser> userStore,
            StorageBackend<String, CognitoGroup> groupStore,
            StorageBackend<String, RevokedTokenInfo> revokedTokenStore,
            String baseUrl,
            RegionResolver regionResolver, LambdaService lambdaService,
            VerificationCodeService verificationCodeService,
            CognitoMessageDispatcher messageDispatcher) {
        this.poolStore = poolStore;
        this.clientStore = clientStore;
        this.resourceServerStore = resourceServerStore;
        this.userStore = userStore;
        this.groupStore = groupStore;
        this.revokedTokenStore = revokedTokenStore;
        this.baseUrl = baseUrl;
        this.regionResolver = regionResolver;
        this.lambdaService = lambdaService;
        this.verificationCodeService = verificationCodeService;
        this.messageDispatcher = messageDispatcher;
        this.authFlowHandler = new CognitoAuthFlowHandler(this, lambdaService, regionResolver);
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
        UserPool updatedPool = MAPPER.convertValue(pool, UserPool.class);

        populateUserPool(updatedPool, request);

        updatedPool.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        poolStore.put(id, updatedPool);
        LOG.infov("Updated User Pool: {0}", id);
        return updatedPool;
    }

    public void addCustomAttributes(String userPoolId, List<Map<String, Object>> customAttributes) {
        UserPool pool = describeUserPool(userPoolId);
        List<Map<String, Object>> schema = pool.getSchemaAttributes();
        if (schema == null) {
            schema = new ArrayList<>();
        }
        for (Map<String, Object> attr : customAttributes) {
            attr = new HashMap<>(attr);
            String name = (String) attr.get("Name");
            if (name == null || name.isEmpty()) {
                throw new AwsException("InvalidParameterException", "Attribute name is required.", 400);
            }

            // Strip prefix to validate name length and pattern
            String strippedName = name;
            if (strippedName.startsWith("custom:")) {
                strippedName = strippedName.substring("custom:".length());
            } else if (strippedName.startsWith("dev:")) {
                strippedName = strippedName.substring("dev:".length());
            }

            if (strippedName.isEmpty() || strippedName.length() > 20) {
                throw new AwsException("InvalidParameterException", "Attribute name length must be between 1 and 20 characters.", 400);
            }

            if (!strippedName.matches("[\\p{L}\\p{M}\\p{S}\\p{N}\\p{P}]+")) {
                throw new AwsException("InvalidParameterException", "Attribute name contains invalid characters.", 400);
            }

            boolean developerOnly = Boolean.TRUE.equals(attr.get("DeveloperOnlyAttribute"));
            String prefix = developerOnly ? "dev:" : "custom:";
            if (!name.startsWith("custom:") && !name.startsWith("dev:")) {
                attr.put("Name", prefix + name);
            }

            String finalName = (String) attr.get("Name");
            boolean exists = schema.stream().anyMatch(existing -> finalName.equals(existing.get("Name")));
            if (exists) {
                throw new AwsException("InvalidParameterException", "Attribute already exists in schema: " + finalName, 400);
            }

            schema.add(attr);
        }
        pool.setSchemaAttributes(schema);
        pool.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        poolStore.put(userPoolId, pool);
        LOG.infov("Added custom attributes to User Pool: {0}", userPoolId);
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
        if (request.containsKey("UserPoolTags")) pool.setUserPoolTags(ReservedTags.stripReservedTags((Map<String, String>) request.get("UserPoolTags")));
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
        return createUserPoolClient(userPoolId, clientName, generateSecret,
                allowedOAuthFlowsUserPoolClient, allowedOAuthFlows, allowedOAuthScopes, null,
                List.of(), null, List.of(), null, null, List.of(), null, List.of(), null, null,
                null, List.of(), null, null);
    }

    public UserPoolClient createUserPoolClient(String userPoolId, String clientName,
            boolean generateSecret, boolean allowedOAuthFlowsUserPoolClient,
            List<String> allowedOAuthFlows, List<String> allowedOAuthScopes,
            Map<String, Object> analyticsConfiguration, List<String> callbackURLs,
            String defaultRedirectURI, List<String> explicitAuthFlows, Integer accessTokenValidity,
            Integer idTokenValidity, List<String> logoutURLs, String preventUserExistenceErrors,
            List<String> readAttributes, Integer refreshTokenValidity,
            List<String> supportedIdentityProviders, Map<String, String> tokenValidityUnits,
            List<String> writeAttributes, Map<String, Object> refreshTokenRotation,
            Boolean enableTokenRevocation) {
        describeUserPool(userPoolId);
        String clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        List<String> normalizedAllowedOAuthFlows = normalizeStringList(allowedOAuthFlows);
        List<String> normalizedAllowedOAuthScopes = normalizeStringList(allowedOAuthScopes);
        List<String> normalizedCallbackUrls = normalizeStringList(callbackURLs);
        String normalizedDefaultRedirectUri = normalizeOptionalString(defaultRedirectURI);
        List<String> normalizedExplicitAuthFlows = normalizeStringList(explicitAuthFlows);
        List<String> normalizedLogoutUrls = normalizeStringList(logoutURLs);
        List<String> normalizedReadAttributes = normalizeStringList(readAttributes);
        List<String> normalizedSupportedIdentityProviders = normalizeStringList(supportedIdentityProviders);
        Map<String, String> copiedTokenValidityUnits = copyStringMap(tokenValidityUnits);
        List<String> normalizedWriteAttributes = normalizeStringList(writeAttributes);
        Integer normalizedRefreshTokenValidity = normalizeRefreshTokenValidity(refreshTokenValidity);

        validateUserPoolClientConfiguration(
                allowedOAuthFlowsUserPoolClient,
                normalizedAllowedOAuthFlows,
                normalizedAllowedOAuthScopes,
                normalizedCallbackUrls,
                normalizedDefaultRedirectUri,
                accessTokenValidity,
                idTokenValidity,
                normalizedRefreshTokenValidity,
                normalizedLogoutUrls,
                copiedTokenValidityUnits
        );

        UserPoolClient client = new UserPoolClient();
        client.setClientId(clientId);
        client.setUserPoolId(userPoolId);
        client.setClientName(clientName);
        client.setGenerateSecret(generateSecret);
        client.setAllowedOAuthFlowsUserPoolClient(allowedOAuthFlowsUserPoolClient);
        client.setAllowedOAuthFlows(normalizedAllowedOAuthFlows);
        client.setAllowedOAuthScopes(normalizedAllowedOAuthScopes);
        client.setAnalyticsConfiguration(copyObjectMap(analyticsConfiguration));
        client.setCallbackURLs(normalizedCallbackUrls);
        client.setDefaultRedirectURI(normalizedDefaultRedirectUri);
        client.setExplicitAuthFlows(normalizedExplicitAuthFlows);
        client.setAccessTokenValidity(accessTokenValidity);
        client.setIdTokenValidity(idTokenValidity);
        client.setLogoutURLs(normalizedLogoutUrls);
        client.setPreventUserExistenceErrors(preventUserExistenceErrors);
        client.setReadAttributes(normalizedReadAttributes);
        client.setRefreshTokenValidity(normalizedRefreshTokenValidity);
        client.setSupportedIdentityProviders(normalizedSupportedIdentityProviders.isEmpty()
                ? List.of("COGNITO")
                : normalizedSupportedIdentityProviders);
        client.setTokenValidityUnits(copiedTokenValidityUnits);
        client.setWriteAttributes(normalizedWriteAttributes);
        client.setRefreshTokenRotation(copyObjectMap(refreshTokenRotation));
        client.setEnableTokenRevocation(enableTokenRevocation != null ? enableTokenRevocation : Boolean.TRUE);
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

    public void deleteUserPoolClient(String clientId) {
        clientStore.get(clientId).orElseThrow(() -> new AwsException("ResourceNotFoundException", "User pool client not found", 404));
        clientStore.delete(clientId);
    }

    public void deleteUserPoolClient(String userPoolId, String clientId) {
        describeUserPoolClient(userPoolId, clientId);
        clientStore.delete(clientId);
    }

    public UserPoolClient updateUserPoolClient(String userPoolId, String clientId, String clientName,
                                               Boolean allowedOAuthFlowsUserPoolClient,
                                               List<String> allowedOAuthFlows,
                                               List<String> allowedOAuthScopes) {
        return updateUserPoolClient(
                userPoolId,
                clientId,
                clientName,
                allowedOAuthFlowsUserPoolClient,
                allowedOAuthFlows,
                allowedOAuthScopes,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public UserPoolClient updateUserPoolClient(String userPoolId, String clientId, String clientName,
                                               Boolean allowedOAuthFlowsUserPoolClient,
                                               List<String> allowedOAuthFlows,
                                               List<String> allowedOAuthScopes,
                                               Map<String, Object> analyticsConfiguration,
                                               List<String> callbackURLs,
                                               String defaultRedirectURI,
                                               List<String> explicitAuthFlows,
                                               Integer accessTokenValidity,
                                               Integer idTokenValidity,
                                               List<String> logoutURLs,
                                               String preventUserExistenceErrors,
                                               List<String> readAttributes,
                                               Integer refreshTokenValidity,
                                               List<String> supportedIdentityProviders,
                                               Map<String, String> tokenValidityUnits,
                                               List<String> writeAttributes,
                                               Map<String, Object> refreshTokenRotation,
                                               Boolean enableTokenRevocation) {
        UserPoolClient client = describeUserPoolClient(userPoolId, clientId);
        boolean effectiveAllowedOAuthFlowsUserPoolClient = allowedOAuthFlowsUserPoolClient != null
                ? allowedOAuthFlowsUserPoolClient
                : client.isAllowedOAuthFlowsUserPoolClient();
        List<String> effectiveAllowedOAuthFlows = allowedOAuthFlows != null
                ? normalizeStringList(allowedOAuthFlows)
                : client.getAllowedOAuthFlows();
        List<String> effectiveAllowedOAuthScopes = allowedOAuthScopes != null
                ? normalizeStringList(allowedOAuthScopes)
                : client.getAllowedOAuthScopes();
        List<String> effectiveCallbackUrls = callbackURLs != null
                ? normalizeStringList(callbackURLs)
                : client.getCallbackURLs();
        String effectiveDefaultRedirectUri = defaultRedirectURI != null
                ? normalizeOptionalString(defaultRedirectURI)
                : client.getDefaultRedirectURI();
        Integer effectiveAccessTokenValidity = accessTokenValidity != null
                ? accessTokenValidity
                : client.getAccessTokenValidity();
        Integer effectiveIdTokenValidity = idTokenValidity != null
                ? idTokenValidity
                : client.getIdTokenValidity();
        Integer effectiveRefreshTokenValidity = refreshTokenValidity != null
                ? normalizeRefreshTokenValidity(refreshTokenValidity)
                : client.getRefreshTokenValidity();
        Map<String, String> effectiveTokenValidityUnits = tokenValidityUnits != null
                ? copyStringMap(tokenValidityUnits)
                : client.getTokenValidityUnits();
        List<String> effectiveLogoutUrls = logoutURLs != null
                ? normalizeStringList(logoutURLs)
                : client.getLogoutURLs();

        validateUserPoolClientConfiguration(
                effectiveAllowedOAuthFlowsUserPoolClient,
                effectiveAllowedOAuthFlows,
                effectiveAllowedOAuthScopes,
                effectiveCallbackUrls,
                effectiveDefaultRedirectUri,
                effectiveAccessTokenValidity,
                effectiveIdTokenValidity,
                effectiveRefreshTokenValidity,
                effectiveLogoutUrls,
                effectiveTokenValidityUnits
        );

        if (clientName != null) client.setClientName(clientName);
        if (allowedOAuthFlowsUserPoolClient != null) {
            client.setAllowedOAuthFlowsUserPoolClient(allowedOAuthFlowsUserPoolClient);
        }
        if (allowedOAuthFlows != null) {
            client.setAllowedOAuthFlows(effectiveAllowedOAuthFlows);
        }
        if (allowedOAuthScopes != null) {
            client.setAllowedOAuthScopes(effectiveAllowedOAuthScopes);
        }
        if (analyticsConfiguration != null) {
            client.setAnalyticsConfiguration(copyObjectMap(analyticsConfiguration));
        }
        if (callbackURLs != null) {
            client.setCallbackURLs(effectiveCallbackUrls);
        }
        if (defaultRedirectURI != null) {
            client.setDefaultRedirectURI(effectiveDefaultRedirectUri);
        }
        if (explicitAuthFlows != null) {
            client.setExplicitAuthFlows(normalizeStringList(explicitAuthFlows));
        }
        if (accessTokenValidity != null) {
            client.setAccessTokenValidity(accessTokenValidity);
        }
        if (idTokenValidity != null) {
            client.setIdTokenValidity(idTokenValidity);
        }
        if (logoutURLs != null) {
            client.setLogoutURLs(effectiveLogoutUrls);
        }
        if (preventUserExistenceErrors != null) {
            client.setPreventUserExistenceErrors(preventUserExistenceErrors);
        }
        if (readAttributes != null) {
            client.setReadAttributes(normalizeStringList(readAttributes));
        }
        if (refreshTokenValidity != null) {
            client.setRefreshTokenValidity(effectiveRefreshTokenValidity);
        }
        if (supportedIdentityProviders != null) {
            client.setSupportedIdentityProviders(normalizeStringList(supportedIdentityProviders));
        }
        if (tokenValidityUnits != null) {
            client.setTokenValidityUnits(effectiveTokenValidityUnits);
        }
        if (writeAttributes != null) {
            client.setWriteAttributes(normalizeStringList(writeAttributes));
        }
        if (refreshTokenRotation != null) {
            client.setRefreshTokenRotation(copyObjectMap(refreshTokenRotation));
        }
        if (enableTokenRevocation != null) {
            client.setEnableTokenRevocation(enableTokenRevocation);
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
        return adminCreateUser(userPoolId, username, attributes, temporaryPassword, null);
    }

    /**
     * AdminCreateUser with optional MessageAction.
     *
     * <p>{@code messageAction = "RESEND"} resends the invitation for an existing
     * user in {@code FORCE_CHANGE_PASSWORD} status without recreating it; floci
     * has no email transport, so this only refreshes {@code lastModifiedDate}.
     * {@code "SUPPRESS"} or {@code null} retain the default create behavior.</p>
     */
    public CognitoUser adminCreateUser(String userPoolId,
                                       String username,
                                       Map<String, String> attributes,
                                       String temporaryPassword,
                                       String messageAction) {
        describeUserPool(userPoolId);
        String key = userKey(userPoolId, username);
        boolean resend = "RESEND".equalsIgnoreCase(messageAction);

        if (resend) {
            CognitoUser existing = userStore.get(key)
                    .orElseThrow(() -> new AwsException("UserNotFoundException", "User not found", 404));
            if (!"FORCE_CHANGE_PASSWORD".equals(existing.getUserStatus())) {
                final String userStateExceptionMessage = """
                        User is in %s state and cannot be resent an invitation.
                        """.formatted(existing.getUserStatus());
                throw new AwsException("UnsupportedUserStateException", userStateExceptionMessage, 400);
            }
            existing.setLastModifiedDate(System.currentTimeMillis() / 1000L);
            userStore.put(key, existing);
            LOG.infov("Resent invitation for user {0} in pool {1}", username, userPoolId);
            return existing;
        }

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

    void adminCreateMigratedUser(String userPoolId, String username, String password,
                                  Map<String, String> attributes, String finalUserStatus) {
        describeUserPool(userPoolId);
        String key = userKey(userPoolId, username);

        CognitoUser user = userStore.get(key).orElseGet(CognitoUser::new);
        user.setUsername(username);
        user.setUserPoolId(userPoolId);
        if (attributes != null) {
            user.getAttributes().putAll(attributes);
        }
        if (!user.getAttributes().containsKey("sub")) {
            user.getAttributes().put("sub", UUID.randomUUID().toString());
        }
        if (password != null && !password.isEmpty()) {
            updateUserPassword(user, password);
            user.setTemporaryPassword(false);
        }
        user.setUserStatus(finalUserStatus == null ? "CONFIRMED" : finalUserStatus);
        user.setEnabled(true);
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);

        userStore.put(key, user);
        LOG.infov("Migrated user {0} into pool {1} (status={2})", username, userPoolId, user.getUserStatus());
    }

    public void adminUserGlobalSignOut(String userPoolId, String username) {
        // Validate user exists
        CognitoUser user = adminGetUser(userPoolId, username);
        
        // Revoke all tokens for this user
        revokeAllUserTokens(userPoolId, username);
        
        LOG.infov("AdminUserGlobalSignOut: revoked all tokens for user {0} in pool {1}", username, userPoolId);
    }

    public CognitoUser adminGetUser(String userPoolId, String username) {
        UserPool pool = poolStore.get(userPoolId).orElseThrow(
                () -> new AwsException("ResourceNotFoundException", "User pool not found", 400));
        LinkedHashSet<CognitoUser> matches = new LinkedHashSet<>();
        userStore.get(userKey(userPoolId, username)).ifPresent(matches::add);
        String prefix = userPoolId + "::";
        matches.addAll(userStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(u -> matchesAliasOrUsernameAttribute(pool, u, username)).toList());
        if (matches.isEmpty()) {
            throw new AwsException("UserNotFoundException", "User not found", 400);
        }
        if (matches.size() > 1) {
            throw new AwsException("InvalidParameterException",
                    "Multiple users found for the supplied username", 400);
        }
        return matches.getFirst();
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

    public void adminDeleteUserAttributes(String userPoolId, String username, List<String> attributeNames) {
        CognitoUser user = adminGetUser(userPoolId, username);
        for (String attrName : attributeNames) {
            user.getAttributes().remove(attrName);
        }
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, user.getUsername()), user);
        LOG.infov("Deleted attributes {0} for user {1} in pool {2}", attributeNames, username, userPoolId);
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

    public CognitoGroup updateGroup(String userPoolId, String groupName, String description,
                                     Integer precedence, String roleArn) {
        CognitoGroup group = getGroup(userPoolId, groupName);
        if (description != null) group.setDescription(description);
        if (precedence != null) group.setPrecedence(precedence);
        if (roleArn != null) group.setRoleArn(roleArn);
        group.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        groupStore.put(groupKey(userPoolId, groupName), group);
        LOG.infov("Updated Cognito group: {0} in pool {1}", groupName, userPoolId);
        return group;
    }

    public List<CognitoUser> listUsersInGroup(String userPoolId, String groupName) {
        CognitoGroup group = getGroup(userPoolId, groupName);
        return group.getUserNames().stream()
                .flatMap(username -> userStore.get(userKey(userPoolId, username)).stream())
                .toList();
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
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found",
                        400));
        String userPoolId = client.getUserPoolId();
        UserPool pool = describeUserPool(userPoolId);

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

        // Ensure sub attribute is present (required by PreSignUp event)
        if (!user.getAttributes().containsKey("sub")) {
            user.getAttributes().put("sub", UUID.randomUUID().toString());
        }

        // Fire PreSignUp BEFORE persisting — allows the trigger to block signup
        // (via lambda error) or auto-confirm/auto-verify the user (via response).
        CognitoAuthFlowHandler.PreSignUpResponse preSignUp = authFlowHandler.firePreSignUp(
                pool, client, user, Map.of(), Map.of(), "PreSignUp_SignUp");
        if (preSignUp.autoConfirmUser()) {
            user.setUserStatus("CONFIRMED");
        }
        if (preSignUp.autoVerifyEmail()) {
            user.getAttributes().put("email_verified", "true");
        }
        if (preSignUp.autoVerifyPhone()) {
            user.getAttributes().put("phone_number_verified", "true");
        }

        DeliveryTarget deliveryTarget = null;
        boolean requiresConfirmationCode =
                !preSignUp.autoConfirmUser() && verificationCodeService != null
                        && messageDispatcher != null && isSignUpConfirmationEnabled(pool);
        if (requiresConfirmationCode) {
            deliveryTarget = resolveSignUpDeliveryTarget(pool, user);
            if (deliveryTarget == null) {
                throw new AwsException("InvalidParameterException",
                        "Cannot confirm user because email or phone_number is missing", 400);
            }
        }

        userStore.put(key, user);
        LOG.infov("Signed up user {0} in pool {1} (status={2})",
                username, userPoolId, user.getUserStatus());

        if (requiresConfirmationCode) {
            try {
                String code = verificationCodeService.issue(pool.getId(), user.getUsername(),
                        VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
                messageDispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
                        code, List.of(deliveryTarget.deliveryMedium()));
            } catch (VerificationCodeException e) {
                rollbackSignUpConfirmationArtifacts(pool.getId(), user.getUsername(), key);
                throw mapVerificationCodeException(e);
            } catch (RuntimeException e) {
                rollbackSignUpConfirmationArtifacts(pool.getId(), user.getUsername(), key);
                throw new AwsException("CodeDeliveryFailureException",
                        "Failed to deliver the message.", 400);
            }
        }

        // When PreSignUp auto-confirms, AWS Cognito also fires PostConfirmation.
        // See: docs.aws.amazon.com/cognito/latest/developerguide/user-pool-lambda-pre-sign-up.html
        if (preSignUp.autoConfirmUser()) {
            authFlowHandler.firePostConfirmation(pool, client, user, Map.of(), "PostConfirmation_ConfirmSignUp");
        }
        return user;
    }

    public void confirmSignUp(String clientId, String username) {
        confirmSignUp(clientId, username, null);
    }

    public void confirmSignUp(String clientId, String username, String confirmationCode) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found",
                        400));
        UserPool pool = poolStore.get(client.getUserPoolId())
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "User pool not found", 400));
        CognitoUser user = adminGetUser(client.getUserPoolId(), username);
        if (verificationCodeService != null && isSignUpConfirmationEnabled(pool)) {
            try {
                verificationCodeService.consume(client.getUserPoolId(), user.getUsername(),
                        VerificationCode.Purpose.SIGNUP_CONFIRMATION,
                        confirmationCode == null ? "" : confirmationCode);
            } catch (VerificationCodeException e) {
                throw mapVerificationCodeException(e);
            }
        }
        user.setUserStatus("CONFIRMED");
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(client.getUserPoolId(), user.getUsername()), user);
        authFlowHandler.firePostConfirmation(pool, client, user, Map.of(), "PostConfirmation_ConfirmSignUp");
    }

    Map<String, String> signUpCodeDeliveryDetails(CognitoUser user) {
        UserPool pool = describeUserPool(user.getUserPoolId());
        if (!isSignUpConfirmationEnabled(pool)) {
            return Map.of();
        }
        DeliveryTarget deliveryTarget = resolveSignUpDeliveryTarget(pool, user);
        if (deliveryTarget == null) {
            return Map.of();
        }
        return Map.of("AttributeName", deliveryTarget.attributeName(), "DeliveryMedium",
                deliveryTarget.deliveryMedium(), "Destination", deliveryTarget.destination());
    }

    public void adminConfirmSignUp(String userPoolId, String username) {
        CognitoUser user = adminGetUser(userPoolId, username);
        user.setUserStatus("CONFIRMED");
        user.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        userStore.put(userKey(userPoolId, user.getUsername()), user);
        LOG.infov("Admin confirmed sign up for user {0} in pool {1}", username, userPoolId);
    }

    // ──────────────────────────── Auth ────────────────────────────

    public Map<String, Object> initiateAuth(String clientId, String authFlow, Map<String, String> authParameters) {
        return authFlowHandler.initiateAuth(clientId, authFlow, authParameters, Map.of());
    }

    public Map<String, Object> initiateAuth(String clientId, String authFlow, Map<String, String> authParameters,
                                             Map<String, String> clientMetadata) {
        return authFlowHandler.initiateAuth(clientId, authFlow, authParameters, clientMetadata);
    }

    public Map<String, Object> adminInitiateAuth(String userPoolId, String clientId, String authFlow,
                                                  Map<String, String> authParameters) {
        return authFlowHandler.adminInitiateAuth(userPoolId, clientId, authFlow, authParameters, Map.of());
    }

    public Map<String, Object> adminInitiateAuth(String userPoolId, String clientId, String authFlow,
                                                  Map<String, String> authParameters,
                                                  Map<String, String> clientMetadata) {
        return authFlowHandler.adminInitiateAuth(userPoolId, clientId, authFlow, authParameters, clientMetadata);
    }

    public Map<String, Object> respondToAuthChallenge(String clientId, String challengeName,
                                                       String session, Map<String, String> responses) {
        return authFlowHandler.respondToAuthChallenge(clientId, challengeName, session, responses, Map.of());
    }

    public Map<String, Object> respondToAuthChallenge(String clientId, String challengeName,
                                                       String session, Map<String, String> responses,
                                                       Map<String, String> clientMetadata) {
        return authFlowHandler.respondToAuthChallenge(clientId, challengeName, session, responses, clientMetadata);
    }

    public Map<String, Object> adminRespondToAuthChallenge(String userPoolId, String clientId,
                                                             String challengeName, String session,
                                                             Map<String, String> responses) {
        return authFlowHandler.adminRespondToAuthChallenge(userPoolId, clientId, challengeName, session, responses, Map.of());
    }

    public Map<String, Object> adminRespondToAuthChallenge(String userPoolId, String clientId,
                                                             String challengeName, String session,
                                                             Map<String, String> responses,
                                                             Map<String, String> clientMetadata) {
        return authFlowHandler.adminRespondToAuthChallenge(userPoolId, clientId, challengeName, session, responses, clientMetadata);
    }

    public void changePassword(String accessToken, String previousPassword, String proposedPassword) {
        String username = extractUsernameFromToken(accessToken);
        String poolId = extractPoolIdFromToken(accessToken);
        String jti = extractJtiFromToken(accessToken);
        
        if (username == null || poolId == null) {
            throw new AwsException("NotAuthorizedException", "Invalid access token", 400);
        }
        
        // Check if access token has been revoked
        validateTokenNotRevoked(jti, poolId, "access");
        Long iat = extractIatFromToken(accessToken);
        validateUserNotGloballySignedOut(username, poolId, "access", iat != null ? iat : 0L);

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

    public Map<String, Object> forgotPassword(String clientId, String username) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        CognitoUser user = adminGetUser(client.getUserPoolId(), username);
        UserPool pool = describeUserPool(client.getUserPoolId());
        ensureVerificationWiring();
        DeliveryTarget deliveryTarget = resolveForgotPasswordDeliveryTarget(pool, user);

        try {
            String code = verificationCodeService.issue(pool.getId(), user.getUsername(),
                    VerificationCode.Purpose.PASSWORD_RESET, Duration.ofHours(1));
            messageDispatcher.dispatch(pool, user, VerificationCode.Purpose.PASSWORD_RESET, code,
                    List.of(deliveryTarget.deliveryMedium()));
        } catch (VerificationCodeException e) {
            throw mapVerificationCodeException(e);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("AttributeName", deliveryTarget.attributeName());
        response.put("DeliveryMedium", deliveryTarget.deliveryMedium());
        response.put("Destination", deliveryTarget.destination());
        return response;
    }

    public void confirmForgotPassword(String clientId, String username, String confirmationCode, String newPassword) {
        UserPoolClient client = clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
        CognitoUser user = adminGetUser(client.getUserPoolId(), username);
        ensureVerificationWiring();
        try {
            verificationCodeService.consume(client.getUserPoolId(), user.getUsername(),
                    VerificationCode.Purpose.PASSWORD_RESET, confirmationCode);
        } catch (VerificationCodeException e) {
            throw mapVerificationCodeException(e);
        }
        adminSetUserPassword(client.getUserPoolId(), user.getUsername(), newPassword, true);
    }

    public Map<String, Object> getUser(String accessToken) {
        String username = extractUsernameFromToken(accessToken);
        String poolId = extractPoolIdFromToken(accessToken);
        String jti = extractJtiFromToken(accessToken);
        
        if (username == null || poolId == null || jti == null) {
            throw new AwsException("NotAuthorizedException", "Invalid access token", 400);
        }
        
        // Check if access token has been revoked
        validateTokenNotRevoked(jti, poolId, "access");
        Long iat = extractIatFromToken(accessToken);
        validateUserNotGloballySignedOut(username, poolId, "access", iat != null ? iat : 0L);
        
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
        String jti = extractJtiFromToken(accessToken);
        
        if (username == null || poolId == null) {
            throw new AwsException("NotAuthorizedException", "Invalid access token", 400);
        }
        
        // Check if access token has been revoked
        validateTokenNotRevoked(jti, poolId, "access");
        Long iat = extractIatFromToken(accessToken);
        validateUserNotGloballySignedOut(username, poolId, "access", iat != null ? iat : 0L);
        
        adminUpdateUserAttributes(poolId, username, attributes);
    }

    public void deleteUserAttributes(String accessToken, List<String> attributeNames) {
        String username = extractUsernameFromToken(accessToken);
        String poolId = extractPoolIdFromToken(accessToken);
        String jti = extractJtiFromToken(accessToken);
        
        if (username == null || poolId == null) {
            throw new AwsException("NotAuthorizedException", "Invalid access token", 400);
        }
        
        // Check if access token has been revoked
        validateTokenNotRevoked(jti, poolId, "access");
        Long iat = extractIatFromToken(accessToken);
        validateUserNotGloballySignedOut(username, poolId, "access", iat != null ? iat : 0L);
        
        adminDeleteUserAttributes(poolId, username, attributeNames);
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
        response.put("expires_in", resolveAccessTokenLifetimeSeconds(client));
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

    public String getUserInfoEndpoint() {
        return baseUrl + "/cognito-idp/oauth2/userInfo";
    }

    // ──────────────────────────── Private helpers ────────────────────────────

    UserPoolClient findClientById(String clientId) {
        return clientStore.get(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Client not found", 404));
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
        String refreshTokenUuid = parts[4]; // UUID from refresh token
        
        if (!client.getUserPoolId().equals(poolId)) {
            throw new AwsException("NotAuthorizedException", "Invalid refresh token", 400);
        }
        if (isRefreshTokenExpired(client, parts)) {
            throw new AwsException("NotAuthorizedException", "Refresh Token has expired", 400);
        }
        
        // Check if refresh token has been revoked
        validateTokenNotRevoked(refreshTokenUuid, poolId, "refresh");
        long issuedAt = 0L;
        try {
            issuedAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException ignored) {}
        validateUserNotGloballySignedOut(username, poolId, "refresh", issuedAt);
        
        UserPool pool = describeUserPool(poolId);
        CognitoUser user = adminGetUser(poolId, username);
        ClaimsOverride override = authFlowHandler.preTokenGenerationForRefresh(pool, client, user);
        
        // Use refresh token UUID as origin_jti for derived tokens
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", generateSignedJwt(user, pool, "access", client, override, refreshTokenUuid));
        auth.put("IdToken", generateSignedJwt(user, pool, "id", client, override, refreshTokenUuid));
        auth.put("ExpiresIn", resolveAccessTokenLifetimeSeconds(client));
        auth.put("TokenType", "Bearer");
        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult", auth);
        return result;
    }

    Map<String, Object> generateAuthResult(CognitoUser user, UserPool pool, UserPoolClient client, ClaimsOverride override) {
        return generateAuthResult(user, pool, client, override, null);
    }
    
    Map<String, Object> generateAuthResult(CognitoUser user, UserPool pool, UserPoolClient client, ClaimsOverride override, String originJti) {
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", generateSignedJwt(user, pool, "access", client, override, originJti));
        auth.put("IdToken", generateSignedJwt(user, pool, "id", client, override, originJti));
        auth.put("RefreshToken", buildRefreshToken(pool.getId(), user.getUsername(), client.getClientId()));
        auth.put("ExpiresIn", resolveAccessTokenLifetimeSeconds(client));
        auth.put("TokenType", "Bearer");
        return auth;
    }

    String generateSignedJwt(CognitoUser user, UserPool pool, String type, UserPoolClient client, ClaimsOverride override) {
        return generateSignedJwt(user, pool, type, client, override, null);
    }
    
    String generateSignedJwt(CognitoUser user, UserPool pool, String type, UserPoolClient client, ClaimsOverride override, String originJti) {
        String header = encodeJwtHeader(pool);
        long now = System.currentTimeMillis() / 1000L;
        long lifetimeSeconds = resolveTokenLifetimeSeconds(client, type);

        Map<String, Object> claims = new LinkedHashMap<>();
        String sub = user.getAttributes().getOrDefault("sub", user.getUsername());
        String email = user.getAttributes().getOrDefault("email", user.getUsername());
        claims.put("sub", sub);
        claims.put("event_id", UUID.randomUUID().toString());
        claims.put("token_use", type);
        claims.put("auth_time", now);
        claims.put("iss", getIssuer(pool.getId()));
        claims.put("exp", now + lifetimeSeconds);
        claims.put("iat", now);
        claims.put("username", user.getUsername());
        claims.put("email", email);
        claims.put("cognito:username", user.getUsername());
        
        // Add JWT ID (jti) claim for token revocation support
        String jti = UUID.randomUUID().toString();
        claims.put("jti", jti);
        
        // Add origin_jti for access and ID tokens derived from refresh tokens
        if (("access".equals(type) || "id".equals(type)) && originJti != null) {
            claims.put("origin_jti", originJti);
        }
        
        String clientId = client != null ? client.getClientId() : null;
        if (clientId != null && !clientId.isBlank()) {
            if ("access".equals(type)) claims.put("client_id", clientId);
            if ("id".equals(type)) claims.put("aud", clientId);
        }
        if (!user.getGroupNames().isEmpty()) {
            claims.put("cognito:groups", new ArrayList<>(user.getGroupNames()));
        }
        if ("id".equals(type)) {
            addUserAttributeClaims(claims, user);
        }

        applyClaimsOverride(claims, override, type);

        return signJwt(header, encodeJsonBase64Url(claims), getSigningPrivateKey(pool));
    }

    private static void addUserAttributeClaims(Map<String, Object> claims, CognitoUser user) {
        for (Map.Entry<String, String> e : user.getAttributes().entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (name == null || name.isEmpty() || value == null) continue;
            if (claims.containsKey(name)) continue;
            switch (name) {
                case "email_verified", "phone_number_verified" -> claims.put(name, Boolean.parseBoolean(value));
                case "updated_at" -> {
                    try {
                        claims.put(name, Long.parseLong(value));
                    } catch (NumberFormatException _) {
                        // OIDC requires updated_at to be a JSON number; omit invalid values.
                    }
                }
                default -> claims.put(name, value);
            }
        }
    }

    private static void applyClaimsOverride(Map<String, Object> claims, ClaimsOverride override, String tokenType) {
        if (override == null) return;
        boolean isAccess = "access".equals(tokenType);
        List<String> suppress = isAccess ? override.accessClaimsToSuppress() : override.idClaimsToSuppress();
        Map<String, Object> addOrOverride = isAccess ? override.accessClaimsToAddOrOverride() : override.idClaimsToAddOrOverride();
        if (suppress != null) suppress.forEach(claims::remove);
        if (addOrOverride != null) claims.putAll(addOrOverride);
        if (override.groupsToOverride() != null) {
            claims.put("cognito:groups", override.groupsToOverride());
        }
        if (override.iamRolesToOverride() != null) {
            claims.put("cognito:roles", override.iamRolesToOverride());
        }
        if (override.preferredRole() != null) {
            claims.put("cognito:preferred_role", override.preferredRole());
        }
        // V2 access-token scope mutations.
        if (isAccess && (override.scopesToAdd() != null || override.scopesToSuppress() != null)) {
            Object existing = claims.get("scope");
            List<String> current = new ArrayList<>();
            if (existing instanceof String s && !s.isBlank()) {
                for (String t : s.split(" ")) if (!t.isBlank()) current.add(t);
            }
            if (override.scopesToSuppress() != null) current.removeAll(override.scopesToSuppress());
            if (override.scopesToAdd() != null) {
                for (String s : override.scopesToAdd()) if (!current.contains(s)) current.add(s);
            }
            if (!current.isEmpty()) claims.put("scope", String.join(" ", current));
        }
    }

    private String encodeJwtHeader(UserPool pool) {
        String headerJson = String.format(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"%s\"}",
                escapeJson(getSigningKeyId(pool)));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeJsonBase64Url(Map<String, Object> claims) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(claims));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JWT claims", e);
        }
    }

    String generateTokenString(String type, String username, UserPool pool, UserPoolClient client) {
        long now = System.currentTimeMillis() / 1000L;
        long lifetimeSeconds = resolveTokenLifetimeSeconds(client, type);
        String headerJson = String.format(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"%s\"}",
                escapeJson(getSigningKeyId(pool)));
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String clientId = client != null ? client.getClientId() : null;
        String audFragment = (clientId != null && !clientId.isBlank() && "id".equals(type))
                ? ",\"aud\":\"" + escapeJson(clientId) + "\""
                : "";
        String payloadJson = String.format(
                "{\"sub\":\"%s\",\"token_use\":\"%s\",\"iss\":\"%s\"," +
                "\"exp\":%d,\"iat\":%d,\"username\":\"%s\"%s}",
                UUID.randomUUID(), type, escapeJson(getIssuer(pool.getId())), now + lifetimeSeconds, now, username, audFragment
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
        long lifetimeSeconds = resolveAccessTokenLifetimeSeconds(client);
        StringBuilder payloadJson = new StringBuilder();
        payloadJson.append("{")
                .append("\"iss\":\"").append(escapeJson(getIssuer(pool.getId()))).append("\",")
                .append("\"version\":2,")
                .append("\"sub\":\"").append(escapeJson(client.getClientId())).append("\",")
                .append("\"client_id\":\"").append(escapeJson(client.getClientId())).append("\",")
                .append("\"token_use\":\"access\",")
                .append("\"exp\":").append(now + lifetimeSeconds).append(",")
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

    private Map<String, Object> copyObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(source);
    }

    private Map<String, String> copyStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(source);
    }

    private void validateUserPoolClientConfiguration(boolean allowedOAuthFlowsUserPoolClient,
                                                     List<String> allowedOAuthFlows,
                                                     List<String> allowedOAuthScopes,
                                                     List<String> callbackURLs,
                                                     String defaultRedirectURI,
                                                     Integer accessTokenValidity,
                                                     Integer idTokenValidity,
                                                     Integer refreshTokenValidity,
                                                     List<String> logoutURLs,
                                                     Map<String, String> tokenValidityUnits) {
        validateTokenValidityUnits(tokenValidityUnits);
        validateTokenValidityValue("AccessTokenValidity", accessTokenValidity);
        validateTokenValidityValue("IdTokenValidity", idTokenValidity);
        validateRefreshTokenValidityValue(refreshTokenValidity);

        List<String> effectiveFlows = allowedOAuthFlows != null ? allowedOAuthFlows : List.of();
        List<String> effectiveScopes = allowedOAuthScopes != null ? allowedOAuthScopes : List.of();
        List<String> effectiveCallbackUrls = callbackURLs != null ? callbackURLs : List.of();
        List<String> effectiveLogoutUrls = logoutURLs != null ? logoutURLs : List.of();

        if (!allowedOAuthFlowsUserPoolClient) {
            if (!effectiveFlows.isEmpty() || !effectiveScopes.isEmpty()
                    || !effectiveCallbackUrls.isEmpty() || !effectiveLogoutUrls.isEmpty()
                    || defaultRedirectURI != null) {
                throw new AwsException("InvalidParameterException",
                        "To use authorization server features, set AllowedOAuthFlowsUserPoolClient to true.",
                        400);
            }
            return;
        }

        if (defaultRedirectURI != null && !effectiveCallbackUrls.contains(defaultRedirectURI)) {
            throw new AwsException("InvalidParameterException",
                    "DefaultRedirectURI must be in the CallbackURLs list.", 400);
        }

        if ((effectiveFlows.contains("code") || effectiveFlows.contains("implicit"))
                && effectiveCallbackUrls.isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "CallbackURLs must contain at least one URI when code or implicit OAuth flows are enabled.",
                    400);
        }
    }

    private void validateTokenValidityUnits(Map<String, String> tokenValidityUnits) {
        if (tokenValidityUnits == null || tokenValidityUnits.isEmpty()) {
            return;
        }

        Set<String> supportedKeys = Set.of("AccessToken", "IdToken", "RefreshToken");
        Set<String> supportedUnits = Set.of("seconds", "minutes", "hours", "days");
        for (Map.Entry<String, String> entry : tokenValidityUnits.entrySet()) {
            if (!supportedKeys.contains(entry.getKey())) {
                throw new AwsException("InvalidParameterException",
                        "TokenValidityUnits contains an unsupported key: " + entry.getKey() + ".", 400);
            }
            String normalizedUnit = normalizeOptionalString(entry.getValue());
            if (normalizedUnit == null || !supportedUnits.contains(normalizedUnit)) {
                throw new AwsException("InvalidParameterException",
                        "TokenValidityUnits contains an unsupported unit value: " + entry.getValue() + ".", 400);
            }
        }
    }

    private void validateTokenValidityValue(String fieldName, Integer value) {
        if (value != null && value <= 0) {
            throw new AwsException("InvalidParameterException", fieldName + " must be greater than 0.", 400);
        }
    }

    private void validateRefreshTokenValidityValue(Integer value) {
        if (value != null && value < 0) {
            throw new AwsException("InvalidParameterException", "RefreshTokenValidity must be greater than or equal to 0.", 400);
        }
    }

    private Integer normalizeRefreshTokenValidity(Integer value) {
        if (value != null && value == 0) {
            return DEFAULT_REFRESH_TOKEN_VALIDITY_DAYS;
        }
        return value;
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

    private String normalizeOptionalString(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    int getAccessTokenExpiresInSeconds(UserPoolClient client) {
        return resolveAccessTokenLifetimeSeconds(client);
    }

    private int resolveAccessTokenLifetimeSeconds(UserPoolClient client) {
        return resolveTokenLifetimeSeconds(client, "access");
    }

    private int resolveTokenLifetimeSeconds(UserPoolClient client, String tokenType) {
        String normalizedType = tokenType == null ? "" : tokenType.toLowerCase(Locale.ROOT);
        int defaultValue;
        String defaultUnit;
        Integer configuredValue;
        if ("refresh".equals(normalizedType)) {
            defaultValue = 30;
            defaultUnit = "days";
            configuredValue = client != null ? client.getRefreshTokenValidity() : null;
        } else if ("id".equals(normalizedType)) {
            defaultValue = 1;
            defaultUnit = "hours";
            configuredValue = client != null ? client.getIdTokenValidity() : null;
        } else {
            defaultValue = 1;
            defaultUnit = "hours";
            configuredValue = client != null ? client.getAccessTokenValidity() : null;
        }

        int value = configuredValue == null ? defaultValue : configuredValue;
        if ("refresh".equals(normalizedType) && value == 0) {
            value = defaultValue;
        } else if (value <= 0) {
            value = defaultValue;
        }
        String unit = resolveTokenValidityUnit(client, normalizedType, defaultUnit);
        long seconds = switch (unit) {
            case "seconds" -> value;
            case "minutes" -> value * 60L;
            case "hours" -> value * 3600L;
            case "days" -> value * 86400L;
            default -> throw new AwsException("InvalidParameterException", "Unsupported token validity unit: " + unit, 400);
        };
        if (seconds > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) seconds;
    }

    private String resolveTokenValidityUnit(UserPoolClient client, String tokenType, String defaultUnit) {
        Map<String, String> units = client != null ? client.getTokenValidityUnits() : null;
        if (units == null || units.isEmpty()) {
            return defaultUnit;
        }
        String key = switch (tokenType) {
            case "refresh" -> "RefreshToken";
            case "id" -> "IdToken";
            default -> "AccessToken";
        };
        String configured = units.get(key);
        return configured == null || configured.isBlank() ? defaultUnit : configured.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isRefreshTokenExpired(UserPoolClient client, String[] parts) {
        if (parts.length < 5) {
            return false;
        }
        try {
            long issuedAt = Long.parseLong(parts[3]);
            long expiresAt = issuedAt + resolveTokenLifetimeSeconds(client, "refresh");
            return System.currentTimeMillis() / 1000L >= expiresAt;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    String buildRefreshToken(String poolId, String username, String clientId) {
        long issuedAt = System.currentTimeMillis();
        String raw = poolId + "|" + username + "|" + clientId + "|" + issuedAt + "|" + UUID.randomUUID();
        return Base64.getEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    String[] parseRefreshToken(String refreshToken) {
        try {
            byte[] decoded = Base64.getDecoder().decode(refreshToken);
            String raw = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 5);
            if (parts.length == 5) {
                return parts; // [poolId, username, clientId, issuedAt, nonce]
            }
            if (parts.length == 4) {
                return new String[] { parts[0], parts[1], parts[2], "", parts[3] };
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

    /**
     * Extract JWT ID (jti) claim from a JWT token.
     */
    private String extractJtiFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return extractJsonField(payloadJson, "jti");
        } catch (Exception e) {
            return null;
        }
    }

    private Long extractIatFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String iatStr = extractJsonField(payloadJson, "iat");
            if (iatStr != null) {
                return Long.parseLong(iatStr);
            }
            // In case the simple extractor doesn't work for numbers (it extracts strings between quotes usually)
            // let's use MAPPER for this specific field
            Map<String, Object> payload = MAPPER.readValue(payloadJson, new TypeReference<>() {});
            Object iat = payload.get("iat");
            if (iat instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
    
    /**
     * Validate that a refresh token has not been revoked, including global user sign-out.
     * Called from CognitoAuthFlowHandler for the REFRESH_TOKEN_AUTH flow.
     */
    void validateRefreshTokenNotRevoked(String jti, String poolId, String username, long iat) {
        validateTokenNotRevoked(jti, poolId, "refresh");
        validateUserNotGloballySignedOut(username, poolId, "refresh", iat);
    }
    
    /**
     * Validate that a token has not been revoked.
     * @param jti The JWT ID to check
     * @param poolId The user pool ID
     * @param tokenType The type of token (access, id, refresh)
     * @throws AwsException if the token has been revoked
     */
    private void validateTokenNotRevoked(String jti, String poolId, String tokenType) {
        if (jti == null) {
            return; // Skip validation for tokens without jti (legacy tokens)
        }
        
        // Check for specific token revocation
        String revokedKey = revokedTokenKey(poolId, jti);
        Optional<RevokedTokenInfo> revoked = revokedTokenStore.get(revokedKey);
        
        if (revoked.isPresent()) {
            RevokedTokenInfo revokedInfo = revoked.get();
            
            // Clean up expired revocation records
            if (revokedInfo.isExpired()) {
                revokedTokenStore.delete(revokedKey);
                return;
            }
            
            // Token has been revoked
            String errorMessage = switch (tokenType) {
                case "access" -> "Access Token has been revoked";
                case "id" -> "ID Token has been revoked";
                case "refresh" -> "Refresh Token has been revoked";
                default -> "Token has been revoked";
            };
            throw new AwsException("NotAuthorizedException", errorMessage, 400);
        }
    }
    
    /**
     * Check if a user has been globally signed out (affects all their tokens).
     * This method should be called in addition to validateTokenNotRevoked.
     */
    private void validateUserNotGloballySignedOut(String username, String poolId, String tokenType, long iat) {
        String globalRevokeKey = revokedTokenKey(poolId, "global:" + username);
        Optional<RevokedTokenInfo> globalRevoked = revokedTokenStore.get(globalRevokeKey);
        
        if (globalRevoked.isPresent()) {
            RevokedTokenInfo globalInfo = globalRevoked.get();
            if (!globalInfo.isExpired()) {
                long revokedAtMs = globalInfo.getRevokedAt();
                boolean revoked = false;
                
                if (iat > 1000000000000L) {
                    // iat is in milliseconds (refresh token)
                    revoked = iat <= revokedAtMs;
                } else if (iat > 0) {
                    // iat is in seconds (access or id token)
                    // If iat_seconds * 1000 <= revokedAt_ms, then the token was issued at or before the revocation second
                    revoked = (iat * 1000L) <= revokedAtMs;
                }

                if (revoked) {
                    String errorMessage = switch (tokenType) {
                        case "access" -> "Access Token has been revoked";
                        case "id" -> "ID Token has been revoked"; 
                        case "refresh" -> "Refresh Token has been revoked";
                        default -> "Token has been revoked";
                    };
                    throw new AwsException("NotAuthorizedException", errorMessage, 400);
                }
            } else {
                revokedTokenStore.delete(globalRevokeKey);
            }
        }
    }
    
    /**
     * Revoke all tokens (refresh, access, ID) for a specific user.
     * This implements the core logic for AdminUserGlobalSignOut.
     */
    private void revokeAllUserTokens(String userPoolId, String username) {
        long nowMs = System.currentTimeMillis();
        
        // Note: In a real implementation, we would need to track all active tokens for a user.
        // Since Floci doesn't currently maintain a token registry, we implement a simpler
        // approach that marks the user as globally signed out with a future expiration.
        // This covers the most common use case where tokens are checked at validation time.
        
        // Create a revocation record for the user with a future expiration
        // This will catch any existing tokens when they're next validated
        String globalRevokeKey = revokedTokenKey(userPoolId, "global:" + username);
        long globalExpiration = nowMs + (365L * 24L * 60L * 60L * 1000L); // 1 year from now in ms
        
        RevokedTokenInfo globalRevocation = new RevokedTokenInfo(
            "global:" + username,
            "global",
            username,
            userPoolId,
            nowMs,
            globalExpiration
        );
        
        revokedTokenStore.put(globalRevokeKey, globalRevocation);
        
        LOG.debugv("Created global revocation record for user {0} in pool {1}", username, userPoolId);
    }
    
    /**
     * Generate a storage key for revoked token information.
     */
    private String revokedTokenKey(String poolId, String jti) {
        return "revoked:" + poolId + ":" + jti;
    }

    private void ensureVerificationWiring() {
        if (verificationCodeService == null || messageDispatcher == null) {
            throw new IllegalStateException("Verification services are not configured");
        }
    }

    private DeliveryTarget resolveForgotPasswordDeliveryTarget(UserPool pool, CognitoUser user) {
        Map<String, String> attributes = user.getAttributes();
        boolean verifiedEmail =
                Boolean.parseBoolean(attributes.getOrDefault("email_verified", "false"));
        boolean verifiedPhone =
                Boolean.parseBoolean(attributes.getOrDefault("phone_number_verified", "false"));
        String email = blankToNull(attributes.get("email"));
        String phoneNumber = blankToNull(attributes.get("phone_number"));

        for (String mechanism : accountRecoveryMechanisms(pool)) {
            if ("verified_email".equals(mechanism) && verifiedEmail && email != null) {
                return new DeliveryTarget("email", "EMAIL", maskEmail(email));
            }
            if ("verified_phone_number".equals(mechanism) && verifiedPhone && phoneNumber != null) {
                return new DeliveryTarget("phone_number", "SMS", maskPhoneNumber(phoneNumber));
            }
        }

        if (verifiedEmail && email != null) {
            return new DeliveryTarget("email", "EMAIL", maskEmail(email));
        }
        if (verifiedPhone && phoneNumber != null) {
            return new DeliveryTarget("phone_number", "SMS", maskPhoneNumber(phoneNumber));
        }

        throw new AwsException("InvalidParameterException",
                "Cannot reset password for the user as there is no registered/verified email or phone_number",
                400);
    }

    private boolean isSignUpConfirmationEnabled(UserPool pool) {
        return pool.getAutoVerifiedAttributes() != null
                && !pool.getAutoVerifiedAttributes().isEmpty();
    }

    private DeliveryTarget resolveSignUpDeliveryTarget(UserPool pool, CognitoUser user) {
        Map<String, String> attributes = user.getAttributes();
        List<String> autoVerifiedAttributes =
                pool.getAutoVerifiedAttributes() != null ? pool.getAutoVerifiedAttributes()
                        : List.of();
        for (String attribute : autoVerifiedAttributes) {
            if ("email".equals(attribute)) {
                String email = blankToNull(attributes.get("email"));
                if (email != null) {
                    return new DeliveryTarget("email", "EMAIL", maskEmail(email));
                }
            }
            if ("phone_number".equals(attribute)) {
                String phoneNumber = blankToNull(attributes.get("phone_number"));
                if (phoneNumber != null) {
                    return new DeliveryTarget("phone_number", "SMS", maskPhoneNumber(phoneNumber));
                }
            }
        }

        return null;
    }

    private void rollbackSignUpConfirmationArtifacts(String userPoolId, String username,
            String userKey) {
        userStore.delete(userKey);
        if (verificationCodeService != null) {
            verificationCodeService.invalidatePrevious(userPoolId, username,
                    VerificationCode.Purpose.SIGNUP_CONFIRMATION);
        }
    }

    private AwsException mapVerificationCodeException(VerificationCodeException e) {
        return switch (e.getKind()) {
            case MISMATCH, NOT_FOUND -> new AwsException("CodeMismatchException",
                    "Invalid verification code provided, please try again.", 400);
            case EXPIRED -> new AwsException("ExpiredCodeException",
                    "Invalid code provided, please request a code again.", 400);
            case RATE_LIMIT -> new AwsException("LimitExceededException",
                    "Attempt limit exceeded, please try again later", 400);
        };
    }

    private boolean matchesAliasOrUsernameAttribute(UserPool pool, CognitoUser user,
            String username) {
        if (username.equals(user.getAttributes().get("sub"))) {
            return true;
        }

        for (String attribute : pool.getAliasAttributes()) {
            if (username.equals(user.getAttributes().get(attribute))
                    && isActiveAliasAttribute(user, attribute)) {
                return true;
            }
        }

        for (String attribute : pool.getUsernameAttributes()) {
            if (username.equals(user.getAttributes().get(attribute))) {
                return true;
            }
        }

        return false;
    }

    private boolean isActiveAliasAttribute(CognitoUser user, String attribute) {
        return switch (attribute) {
            case "email" -> Boolean
                    .parseBoolean(user.getAttributes().getOrDefault("email_verified", "false"));
            case "phone_number" -> Boolean.parseBoolean(
                    user.getAttributes().getOrDefault("phone_number_verified", "false"));
            default -> true;
        };
    }

    private List<String> accountRecoveryMechanisms(UserPool pool) {
        Map<String, Object> setting = pool.getAccountRecoverySetting();
        if (setting == null) {
            return List.of();
        }
        Object mechanisms = setting.get("RecoveryMechanisms");
        if (!(mechanisms instanceof List<?> recoveryMechanisms)) {
            return List.of();
        }
        return recoveryMechanisms.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .sorted(Comparator.comparingInt(this::recoveryPriority))
                .map(m -> String.valueOf(m.get("Name"))).filter(name -> !"admin_only".equals(name))
                .toList();
    }

    private int recoveryPriority(Map<?, ?> mechanism) {
        Object priority = mechanism.get("Priority");
        if (priority instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(priority));
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return "****";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        return maskSegment(local) + "@" + maskDomain(domain);
    }

    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber.length() <= 4) {
            return "*".repeat(phoneNumber.length());
        }
        return "*".repeat(phoneNumber.length() - 4)
                + phoneNumber.substring(phoneNumber.length() - 4);
    }

    private String maskDomain(String domain) {
        int dot = domain.lastIndexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) {
            return maskSegment(domain);
        }
        return maskSegment(domain.substring(0, dot)) + domain.substring(dot);
    }

    private String maskSegment(String value) {
        if (value.length() <= 1) {
            return "*";
        }
        return value.charAt(0) + "*".repeat(Math.max(1, value.length() - 1));
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record DeliveryTarget(String attributeName, String deliveryMedium, String destination) {
    }
}
