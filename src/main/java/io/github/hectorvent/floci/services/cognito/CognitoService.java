package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
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

@ApplicationScoped
public class CognitoService {

    private static final Logger LOG = Logger.getLogger(CognitoService.class);

    private final StorageBackend<String, UserPool> poolStore;
    private final StorageBackend<String, UserPoolClient> clientStore;
    private final StorageBackend<String, CognitoUser> userStore;

    @Inject
    public CognitoService(StorageFactory storageFactory) {
        this.poolStore = storageFactory.create("cognito", "cognito-pools.json",
                new TypeReference<Map<String, UserPool>>() {});
        this.clientStore = storageFactory.create("cognito", "cognito-clients.json",
                new TypeReference<Map<String, UserPoolClient>>() {});
        this.userStore = storageFactory.create("cognito", "cognito-users.json",
                new TypeReference<Map<String, CognitoUser>>() {});
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
        ensureJwtSigningKeys(pool);
        return pool;
    }

    public List<UserPool> listUserPools() {
        return poolStore.scan(k -> true);
    }

    public void deleteUserPool(String id) {
        poolStore.delete(id);
    }

    // ──────────────────────────── User Pool Clients ────────────────────────────

    public UserPoolClient createUserPoolClient(String userPoolId, String clientName) {
        describeUserPool(userPoolId);
        String clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        UserPoolClient client = new UserPoolClient();
        client.setClientId(clientId);
        client.setUserPoolId(userPoolId);
        client.setClientName(clientName);
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
        String payloadJson = String.format(
                "{\"sub\":\"%s\",\"event_id\":\"%s\",\"token_use\":\"%s\",\"auth_time\":%d," +
                "\"iss\":\"https://cognito-idp.local/%s\",\"exp\":%d,\"iat\":%d," +
                "\"username\":\"%s\",\"email\":\"%s\",\"cognito:username\":\"%s\"}",
                UUID.randomUUID(), UUID.randomUUID(), type, now,
                pool.getId(), now + 3600, now,
                user.getUsername(), email, user.getUsername()
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
                "{\"sub\":\"%s\",\"token_use\":\"%s\",\"iss\":\"https://cognito-idp.local/%s\"," +
                "\"exp\":%d,\"iat\":%d,\"username\":\"%s\"}",
                UUID.randomUUID(), type, pool.getId(), now + 3600, now, username
        );
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return signJwt(header, payload, getSigningPrivateKey(pool));
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

    private void ensureJwtSigningKeys(UserPool pool) {
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
                poolStore.put(pool.getId(), pool);
            }
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
            // iss = "https://cognito-idp.local/POOL_ID"
            int lastSlash = iss.lastIndexOf('/');
            return lastSlash >= 0 ? iss.substring(lastSlash + 1) : null;
        } catch (Exception e) {
            return null;
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

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
