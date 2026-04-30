package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns Cognito authentication-flow protocol logic: USER_PASSWORD_AUTH,
 * USER_SRP_AUTH, REFRESH_TOKEN_AUTH, CUSTOM_AUTH, and challenge responses
 * (PASSWORD_VERIFIER, CUSTOM_CHALLENGE, NEW_PASSWORD_REQUIRED).
 *
 * For CUSTOM_AUTH, dispatches Cognito Lambda triggers
 * (DefineAuthChallenge, CreateAuthChallenge, VerifyAuthChallengeResponse).
 * When a trigger is not configured (or invocation fails), falls back to a
 * deterministic stub: single CUSTOM_CHALLENGE round, accept any non-empty
 * answer (or match {@code custom:expectedAuthAnswer} attribute if set).
 *
 * Calls back into {@link CognitoService} for user/pool lookup and token
 * generation.
 */
final class CognitoAuthFlowHandler {

    private static final Logger LOG = Logger.getLogger(CognitoAuthFlowHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitoService service;
    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, SrpSession> srpSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CustomAuthSession> customAuthSessions = new ConcurrentHashMap<>();

    private record SrpSession(String userPoolId, String username, String clientId,
                              String aHex, String bHex, String bPublicHex,
                              String secretBlockBase64, Map<String, String> clientMetadata) {}

    static final class CustomAuthSession {
        final String userPoolId;
        final String username;
        final String clientId;
        final List<Map<String, Object>> history = new ArrayList<>();
        Map<String, String> privateChallengeParameters;
        Map<String, String> clientMetadata = Map.of();
        String currentChallengeName;

        CustomAuthSession(String userPoolId, String username, String clientId) {
            this.userPoolId = userPoolId;
            this.username = username;
            this.clientId = clientId;
        }
    }

    CognitoAuthFlowHandler(CognitoService service, LambdaService lambdaService, RegionResolver regionResolver) {
        this.service = service;
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Public entry points ────────────────────────────

    Map<String, Object> initiateAuth(String clientId, String authFlow, Map<String, String> authParameters,
                                      Map<String, String> clientMetadata) {
        UserPoolClient client = service.findClientById(clientId);
        UserPool pool = service.describeUserPool(client.getUserPoolId());

        return switch (authFlow) {
            case "USER_PASSWORD_AUTH" -> authenticateWithPassword(pool, client, authParameters, clientMetadata);
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> handleRefreshToken(pool, client, authParameters, clientMetadata);
            case "USER_SRP_AUTH" -> handleUserSrpAuth(pool, client, authParameters, clientMetadata);
            case "CUSTOM_AUTH" -> handleCustomAuth(pool, client, authParameters, clientMetadata);
            default -> {
                String username = authParameters.get("USERNAME");
                if (username == null) {
                    throw new AwsException("InvalidParameterException", "USERNAME is required", 400);
                }
                CognitoUser user = service.adminGetUser(pool.getId(), username);
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult",
                        issueTokens(pool, client, user, "TokenGeneration_Authentication", clientMetadata));
                yield result;
            }
        };
    }

    Map<String, Object> adminInitiateAuth(String userPoolId, String clientId, String authFlow,
                                           Map<String, String> authParameters, Map<String, String> clientMetadata) {
        UserPoolClient client = service.describeUserPoolClient(userPoolId, clientId);
        UserPool pool = service.describeUserPool(userPoolId);

        String username = authParameters.get("USERNAME");
        if (username != null) {
            try {
                CognitoUser user = service.adminGetUser(userPoolId, username);
                if ("RESET_REQUIRED".equals(user.getUserStatus())) {
                    throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
                }
            } catch (AwsException ae) {
                if (!"UserNotFoundException".equals(ae.getErrorCode())) throw ae;
                // Allow flow to proceed; UserMigration may handle it inside the flow handler.
            }
        }

        return switch (authFlow) {
            case "ADMIN_USER_PASSWORD_AUTH", "USER_PASSWORD_AUTH" ->
                    authenticateWithPassword(pool, client, authParameters, clientMetadata);
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> handleRefreshToken(pool, client, authParameters, clientMetadata);
            case "ADMIN_USER_SRP_AUTH" -> handleUserSrpAuth(pool, client, authParameters, clientMetadata);
            case "CUSTOM_AUTH" -> handleCustomAuth(pool, client, authParameters, clientMetadata);
            default -> {
                CognitoUser user = service.adminGetUser(userPoolId, username);
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult",
                        issueTokens(pool, client, user, "TokenGeneration_Authentication", clientMetadata));
                yield result;
            }
        };
    }

    Map<String, Object> respondToAuthChallenge(String clientId, String challengeName, String session,
                                                Map<String, String> responses, Map<String, String> clientMetadata) {
        UserPoolClient client = service.findClientById(clientId);
        UserPool pool = service.describeUserPool(client.getUserPoolId());
        return processChallenge(pool, client, challengeName, session, responses, clientMetadata);
    }

    Map<String, Object> adminRespondToAuthChallenge(String userPoolId, String clientId, String challengeName,
                                                      String session, Map<String, String> responses,
                                                      Map<String, String> clientMetadata) {
        UserPoolClient client = service.describeUserPoolClient(userPoolId, clientId);
        UserPool pool = service.describeUserPool(userPoolId);
        return processChallenge(pool, client, challengeName, session, responses, clientMetadata);
    }

    private Map<String, Object> processChallenge(UserPool pool, UserPoolClient client, String challengeName,
                                                   String session, Map<String, String> responses,
                                                   Map<String, String> clientMetadata) {
        if ("PASSWORD_VERIFIER".equals(challengeName)) {
            return handlePasswordVerifierChallenge(pool, client, session, responses, clientMetadata);
        }
        if ("CUSTOM_CHALLENGE".equals(challengeName)) {
            return handleCustomChallenge(pool, client, session, responses, clientMetadata);
        }
        if ("NEW_PASSWORD_REQUIRED".equals(challengeName)) {
            String username = responses.get("USERNAME");
            String newPassword = responses.get("NEW_PASSWORD");
            if (username == null || newPassword == null) {
                throw new AwsException("InvalidParameterException", "USERNAME and NEW_PASSWORD are required", 400);
            }
            service.adminSetUserPassword(pool.getId(), username, newPassword, true);
            // Apply any userAttributes.<name> updates the client provided.
            Map<String, String> attrUpdates = new HashMap<>();
            for (Map.Entry<String, String> e : responses.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith("userAttributes.")) {
                    attrUpdates.put(e.getKey().substring("userAttributes.".length()), e.getValue());
                }
            }
            if (!attrUpdates.isEmpty()) {
                service.adminUpdateUserAttributes(pool.getId(), username, attrUpdates);
            }
            CognitoUser user = service.adminGetUser(pool.getId(), username);
            Map<String, Object> result = new HashMap<>();
            result.put("AuthenticationResult",
                    issueTokens(pool, client, user, "TokenGeneration_NewPasswordChallenge", clientMetadata));
            return result;
        }
        throw new AwsException("InvalidParameterException", "Unsupported challenge: " + challengeName, 400);
    }

    // ──────────────────────────── USER_PASSWORD / REFRESH ────────────────────────────

    private Map<String, Object> authenticateWithPassword(UserPool pool, UserPoolClient client,
                                                          Map<String, String> params, Map<String, String> clientMetadata) {
        String username = params.get("USERNAME");
        String password = params.get("PASSWORD");
        if (username == null) throw new AwsException("InvalidParameterException", "USERNAME is required", 400);
        if (password == null) throw new AwsException("InvalidParameterException", "PASSWORD is required", 400);
        validateSecretHash(client, params, username);

        CognitoUser user;
        try {
            user = service.adminGetUser(pool.getId(), username);
        } catch (AwsException ae) {
            if (!"UserNotFoundException".equals(ae.getErrorCode())) throw ae;
            user = tryUserMigration(pool, client, username, password, null, clientMetadata,
                    "UserMigration_Authentication");
            if (user == null) throw ae;
        }

        firePreAuthentication(pool, client, user, null, clientMetadata, false);

        if (!user.isEnabled()) throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
        if ("RESET_REQUIRED".equals(user.getUserStatus())) {
            throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
        }
        if ("UNCONFIRMED".equals(user.getUserStatus())) {
            throw new AwsException("UserNotConfirmedException", "User is not confirmed", 400);
        }
        if (user.getPasswordHash() == null || !user.getPasswordHash().equals(service.hashPassword(password))) {
            throw new AwsException("NotAuthorizedException", "Incorrect username or password", 400);
        }

        if (user.isTemporaryPassword() || "FORCE_CHANGE_PASSWORD".equals(user.getUserStatus())) {
            return buildNewPasswordRequiredChallenge(pool, client, user);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult",
                issueTokens(pool, client, user, "TokenGeneration_Authentication", clientMetadata));
        return result;
    }

    private Map<String, Object> handleRefreshToken(UserPool pool, UserPoolClient client,
                                                    Map<String, String> params, Map<String, String> clientMetadata) {
        String refreshToken = params.get("REFRESH_TOKEN");
        if (refreshToken == null) throw new AwsException("InvalidParameterException", "REFRESH_TOKEN is required", 400);
        String[] parts = service.parseRefreshToken(refreshToken);
        if (parts != null) {
            String username = parts[1];
            String tokenClientId = parts[2];
            try {
                CognitoUser user = service.adminGetUser(pool.getId(), username);
                CognitoService.ClaimsOverride override = firePreTokenGeneration(pool, client, user,
                        clientMetadata, "TokenGeneration_RefreshTokens");
                Map<String, Object> auth = new HashMap<>();
                auth.put("AccessToken", service.generateSignedJwt(user, pool, "access", tokenClientId, override));
                auth.put("IdToken", service.generateSignedJwt(user, pool, "id", tokenClientId, override));
                auth.put("ExpiresIn", 3600);
                auth.put("TokenType", "Bearer");
                Map<String, Object> result = new HashMap<>();
                result.put("AuthenticationResult", auth);
                return result;
            } catch (AwsException ignored) { }
        }
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", service.generateTokenString("access", "unknown", pool, client.getClientId()));
        auth.put("IdToken", service.generateTokenString("id", "unknown", pool, client.getClientId()));
        auth.put("ExpiresIn", 3600);
        auth.put("TokenType", "Bearer");
        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult", auth);
        return result;
    }

    private Map<String, Object> buildNewPasswordRequiredChallenge(UserPool pool, UserPoolClient client, CognitoUser user) {
        String session = buildSessionToken(pool.getId(), user.getUsername(), client.getClientId());
        Map<String, Object> result = new HashMap<>();
        result.put("ChallengeName", "NEW_PASSWORD_REQUIRED");
        result.put("Session", session);
        Map<String, String> params = new HashMap<>();
        params.put("USER_ID_FOR_SRP", user.getUsername());
        params.put("requiredAttributes", "[]");
        try {
            params.put("userAttributes",
                    new ObjectMapper().writeValueAsString(user.getAttributes() == null ? Map.of() : user.getAttributes()));
        } catch (Exception e) {
            params.put("userAttributes", "{}");
        }
        result.put("ChallengeParameters", params);
        return result;
    }

    private static String buildSessionToken(String poolId, String username, String clientId) {
        String raw = poolId + "|" + username + "|" + clientId + "|" + UUID.randomUUID();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private void validateSecretHash(UserPoolClient client, Map<String, String> params, String username) {
        String secret = client.getClientSecret();
        if (secret == null || secret.isBlank()) return;
        String provided = params.get("SECRET_HASH");
        if (provided == null || provided.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Client " + client.getClientId() + " has a secret; SECRET_HASH is required", 400);
        }
        String expected;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((username + client.getClientId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            expected = Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new AwsException("InternalErrorException", "SECRET_HASH computation failed", 500);
        }
        if (!expected.equals(provided)) {
            throw new AwsException("NotAuthorizedException", "SECRET_HASH does not match", 400);
        }
    }

    // ──────────────────────────── SRP ────────────────────────────

    private Map<String, Object> handleUserSrpAuth(UserPool pool, UserPoolClient client,
                                                    Map<String, String> authParameters,
                                                    Map<String, String> clientMetadata) {
        String username = authParameters.get("USERNAME");
        String aHex = authParameters.get("SRP_A");
        if (username == null || aHex == null) {
            throw new AwsException("InvalidParameterException", "USERNAME and SRP_A are required", 400);
        }
        validateSecretHash(client, authParameters, username);

        CognitoUser user = service.adminGetUser(pool.getId(), username);
        firePreAuthentication(pool, client, user, null, clientMetadata, false);

        if (!user.isEnabled()) throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
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
                pool.getId(), user.getUsername(), client.getClientId(),
                aHex, bHex, bPublicHex, secretBlockBase64,
                clientMetadata == null ? Map.of() : clientMetadata));

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
                                                                 String session, Map<String, String> responses,
                                                                 Map<String, String> clientMetadata) {
        CustomAuthSession customState =
                session == null ? null : customAuthSessions.get(session);
        if (customState != null) {
            return verifyPasswordWithinCustomAuth(pool, client, session, customState, responses, clientMetadata);
        }

        SrpSession srp = srpSessions.get(session);
        if (srp == null) throw new AwsException("NotAuthorizedException", "Session not found", 400);

        String username = responses.get("USERNAME");
        String claimSignature = responses.get("PASSWORD_CLAIM_SIGNATURE");
        String timestamp = responses.get("TIMESTAMP");
        if (username == null || claimSignature == null || timestamp == null) {
            throw new AwsException("InvalidParameterException",
                    "USERNAME, PASSWORD_CLAIM_SIGNATURE and TIMESTAMP are required", 400);
        }
        validateSecretHash(client, responses, username);

        CognitoUser user = service.adminGetUser(pool.getId(), username);
        if (!user.isEnabled()) throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
        if ("RESET_REQUIRED".equals(user.getUserStatus())) {
            throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
        }
        if (user.getSrpVerifier() == null) {
            throw new AwsException("NotAuthorizedException", "User does not support SRP auth", 400);
        }

        byte[] sessionKey = CognitoSrpHelper.computeSessionKey(srp.aHex(), srp.bHex(), srp.bPublicHex(), user.getSrpVerifier());
        byte[] secretBlock = Base64.getDecoder().decode(srp.secretBlockBase64());
        boolean valid = CognitoSrpHelper.verifySignature(sessionKey, pool.getId(), user.getUsername(),
                secretBlock, timestamp, claimSignature);
        if (!valid) throw new AwsException("NotAuthorizedException", "Incorrect username or password", 400);

        Map<String, String> effectiveMetadata = (clientMetadata != null && !clientMetadata.isEmpty())
                ? clientMetadata : srp.clientMetadata();
        srpSessions.remove(session);

        if (user.isTemporaryPassword() || "FORCE_CHANGE_PASSWORD".equals(user.getUserStatus())) {
            return buildNewPasswordRequiredChallenge(pool, client, user);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult",
                issueTokens(pool, client, user, "TokenGeneration_Authentication", effectiveMetadata));
        return result;
    }

    // ──────────────────────────── CUSTOM_AUTH ────────────────────────────

    private Map<String, Object> handleCustomAuth(UserPool pool, UserPoolClient client,
                                                  Map<String, String> authParameters,
                                                  Map<String, String> clientMetadata) {
        String username = authParameters.get("USERNAME");
        if (username == null) throw new AwsException("InvalidParameterException", "USERNAME is required", 400);

        CognitoUser user = service.adminGetUser(pool.getId(), username);
        firePreAuthentication(pool, client, user, null, clientMetadata, false);
        if (!user.isEnabled()) throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
        if ("RESET_REQUIRED".equals(user.getUserStatus())) {
            throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
        }

        CustomAuthSession state =
                new CustomAuthSession(pool.getId(), username, client.getClientId());
        state.clientMetadata = clientMetadata == null ? Map.of() : clientMetadata;

        Map<String, Object> defineResp = defineAuthChallenge(pool, client, user, state);
        if (Boolean.TRUE.equals(defineResp.get("failAuthentication"))) {
            throw new AwsException("NotAuthorizedException", "Custom auth failed", 400);
        }
        if (Boolean.TRUE.equals(defineResp.get("issueTokens"))) {
            Map<String, Object> result = new HashMap<>();
            result.put("AuthenticationResult",
                    issueTokens(pool, client, user, "TokenGeneration_Authentication", state.clientMetadata));
            return result;
        }

        String challengeName = (String) defineResp.getOrDefault("challengeName", "CUSTOM_CHALLENGE");
        state.currentChallengeName = challengeName;

        Map<String, String> publicParams = new HashMap<>();
        publicParams.put("USERNAME", username);
        for (Map.Entry<String, String> e : authParameters.entrySet()) {
            if (!"USERNAME".equals(e.getKey()) && !"SRP_A".equals(e.getKey())) {
                publicParams.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        applyCreateResponse(state, challengeName,
                createAuthChallenge(pool, client, user, state, challengeName), publicParams);

        String sessionToken = buildSessionToken(pool.getId(), username, client.getClientId());
        customAuthSessions.put(sessionToken, state);

        Map<String, Object> result = new HashMap<>();
        result.put("ChallengeName", challengeName);
        result.put("Session", sessionToken);
        result.put("ChallengeParameters", publicParams);
        return result;
    }

    private Map<String, Object> handleCustomChallenge(UserPool pool, UserPoolClient client,
                                                       String session, Map<String, String> responses,
                                                       Map<String, String> clientMetadata) {
        if (session == null) throw new AwsException("InvalidParameterException", "Session is required", 400);
        CustomAuthSession state = customAuthSessions.get(session);
        if (state == null) throw new AwsException("NotAuthorizedException", "Session not found", 400);
        if (!state.userPoolId.equals(pool.getId()) || !state.clientId.equals(client.getClientId())) {
            throw new AwsException("NotAuthorizedException", "Session does not match client", 400);
        }
        if (clientMetadata != null && !clientMetadata.isEmpty()) {
            state.clientMetadata = clientMetadata;
        }

        String answer = responses.get("ANSWER");
        if (answer == null) answer = responses.get("custom:ANSWER");
        if (answer == null || answer.isBlank()) {
            throw new AwsException("InvalidParameterException", "ANSWER is required", 400);
        }
        validateSecretHash(client, responses, state.username);

        CognitoUser user = service.adminGetUser(pool.getId(), state.username);

        Boolean answerCorrect = verifyAuthChallenge(pool, client, user, state, answer);
        if (answerCorrect == null) {
            String expected = user.getAttributes() == null ? null : user.getAttributes().get("custom:expectedAuthAnswer");
            answerCorrect = (expected == null) || expected.equals(answer);
        }
        if (!state.history.isEmpty()) {
            state.history.get(state.history.size() - 1).put("challengeResult", answerCorrect);
        }

        Map<String, Object> defineResp = defineAuthChallenge(pool, client, user, state);
        if (Boolean.TRUE.equals(defineResp.get("failAuthentication"))) {
            customAuthSessions.remove(session);
            throw new AwsException("NotAuthorizedException", "Incorrect challenge answer", 400);
        }
        if (Boolean.TRUE.equals(defineResp.get("issueTokens"))) {
            customAuthSessions.remove(session);
            Map<String, Object> result = new HashMap<>();
            result.put("AuthenticationResult",
                    issueTokens(pool, client, user, "TokenGeneration_Authentication", state.clientMetadata));
            return result;
        }

        String nextChallenge = (String) defineResp.getOrDefault("challengeName", "CUSTOM_CHALLENGE");
        state.currentChallengeName = nextChallenge;
        Map<String, String> publicParams = new HashMap<>();
        publicParams.put("USERNAME", state.username);
        applyCreateResponse(state, nextChallenge,
                createAuthChallenge(pool, client, user, state, nextChallenge), publicParams);

        String newSession = buildSessionToken(pool.getId(), state.username, client.getClientId());
        customAuthSessions.remove(session);
        customAuthSessions.put(newSession, state);

        Map<String, Object> result = new HashMap<>();
        result.put("ChallengeName", nextChallenge);
        result.put("Session", newSession);
        result.put("ChallengeParameters", publicParams);
        return result;
    }

    private Map<String, Object> verifyPasswordWithinCustomAuth(UserPool pool, UserPoolClient client,
                                                                String session,
                                                                CustomAuthSession state,
                                                                Map<String, String> responses,
                                                                Map<String, String> clientMetadata) {
        if (!state.userPoolId.equals(pool.getId()) || !state.clientId.equals(client.getClientId())) {
            throw new AwsException("NotAuthorizedException", "Session does not match client", 400);
        }
        if (clientMetadata != null && !clientMetadata.isEmpty()) {
            state.clientMetadata = clientMetadata;
        }

        String password = responses.get("ANSWER");
        if (password == null) password = responses.get("PASSWORD_CLAIM_SIGNATURE");
        if (password == null || password.isBlank()) {
            throw new AwsException("InvalidParameterException", "ANSWER (password) is required", 400);
        }

        CognitoUser user = service.adminGetUser(pool.getId(), state.username);
        boolean passwordOK = user.getPasswordHash() != null
                && user.getPasswordHash().equals(service.hashPassword(password));
        if (!state.history.isEmpty()) {
            state.history.get(state.history.size() - 1).put("challengeResult", passwordOK);
        }
        if (!passwordOK) {
            customAuthSessions.remove(session);
            throw new AwsException("NotAuthorizedException", "Incorrect username or password", 400);
        }

        Map<String, Object> defineResp = defineAuthChallenge(pool, client, user, state);
        if (Boolean.TRUE.equals(defineResp.get("failAuthentication"))) {
            customAuthSessions.remove(session);
            throw new AwsException("NotAuthorizedException", "Custom auth failed", 400);
        }
        if (Boolean.TRUE.equals(defineResp.get("issueTokens"))) {
            customAuthSessions.remove(session);
            Map<String, Object> result = new HashMap<>();
            result.put("AuthenticationResult",
                    issueTokens(pool, client, user, "TokenGeneration_Authentication", state.clientMetadata));
            return result;
        }

        String nextChallenge = (String) defineResp.getOrDefault("challengeName", "CUSTOM_CHALLENGE");
        state.currentChallengeName = nextChallenge;
        Map<String, String> publicParams = new HashMap<>();
        publicParams.put("USERNAME", state.username);
        applyCreateResponse(state, nextChallenge,
                createAuthChallenge(pool, client, user, state, nextChallenge), publicParams);

        String newSession = buildSessionToken(pool.getId(), state.username, client.getClientId());
        customAuthSessions.remove(session);
        customAuthSessions.put(newSession, state);

        Map<String, Object> result = new HashMap<>();
        result.put("ChallengeName", nextChallenge);
        result.put("Session", newSession);
        result.put("ChallengeParameters", publicParams);
        return result;
    }

    // ──────────────────────────── CUSTOM_AUTH triggers + helpers ────────────────────────────

    private Map<String, Object> defineAuthChallenge(UserPool pool, UserPoolClient client, CognitoUser user,
                                                     CustomAuthSession state) {
        Map<String, Object> req = new HashMap<>();
        req.put("session", new ArrayList<>(state.history));
        req.put("userNotFound", false);
        req.put("clientMetadata", state.clientMetadata == null ? Map.of() : state.clientMetadata);
        Map<String, Object> resp = invokeTrigger(pool, client, user, "DefineAuthChallenge",
                "DefineAuthChallenge_Authentication", req).response();
        if (resp != null) return resp;

        Map<String, Object> fallback = new HashMap<>();
        boolean anyCorrect = state.history.stream().anyMatch(h -> Boolean.TRUE.equals(h.get("challengeResult")));
        boolean anyWrong = state.history.stream().anyMatch(h -> Boolean.FALSE.equals(h.get("challengeResult")));
        if (anyCorrect) {
            fallback.put("issueTokens", true);
        } else if (anyWrong && state.history.size() >= 3) {
            fallback.put("failAuthentication", true);
        } else {
            fallback.put("challengeName", "CUSTOM_CHALLENGE");
        }
        return fallback;
    }

    private Map<String, Object> createAuthChallenge(UserPool pool, UserPoolClient client, CognitoUser user,
                                                     CustomAuthSession state, String challengeName) {
        Map<String, Object> req = new HashMap<>();
        req.put("challengeName", challengeName);
        req.put("session", new ArrayList<>(state.history));
        req.put("clientMetadata", state.clientMetadata == null ? Map.of() : state.clientMetadata);
        return invokeTrigger(pool, client, user, "CreateAuthChallenge",
                "CreateAuthChallenge_Authentication", req).response();
    }

    private Boolean verifyAuthChallenge(UserPool pool, UserPoolClient client, CognitoUser user,
                                         CustomAuthSession state, String answer) {
        Map<String, Object> req = new HashMap<>();
        req.put("challengeAnswer", answer);
        req.put("privateChallengeParameters",
                state.privateChallengeParameters == null ? Map.of() : state.privateChallengeParameters);
        req.put("clientMetadata", state.clientMetadata == null ? Map.of() : state.clientMetadata);
        Map<String, Object> resp = invokeTrigger(pool, client, user, "VerifyAuthChallengeResponse",
                "VerifyAuthChallengeResponse_Authentication", req).response();
        if (resp == null) return null;
        Object v = resp.get("answerCorrect");
        return v instanceof Boolean b ? b : null;
    }

    private Map<String, Object> applyCreateResponse(CustomAuthSession state, String challengeName,
                                                     Map<String, Object> createResp,
                                                     Map<String, String> publicParamsOut) {
        if (createResp != null) {
            Object pub = createResp.get("publicChallengeParameters");
            Object priv = createResp.get("privateChallengeParameters");
            if (pub instanceof Map<?, ?> pubMap) {
                pubMap.forEach((k, v) -> publicParamsOut.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            }
            if (priv instanceof Map<?, ?> privMap) {
                Map<String, String> typed = new HashMap<>();
                privMap.forEach((k, v) -> typed.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
                state.privateChallengeParameters = typed;
            }
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("challengeName", challengeName);
        if (createResp != null) entry.put("challengeMetadata", createResp.get("challengeMetadata"));
        state.history.add(entry);
        return entry;
    }

    private record TriggerResult(Map<String, Object> response, String errorMessage, boolean configured) {
        static TriggerResult notConfigured() { return new TriggerResult(null, null, false); }
        static TriggerResult success(Map<String, Object> response) { return new TriggerResult(response, null, true); }
        static TriggerResult error(String msg) { return new TriggerResult(null, msg, true); }
        boolean errored() { return configured && errorMessage != null; }
    }

    @SuppressWarnings("unchecked")
    private TriggerResult invokeTrigger(UserPool pool, UserPoolClient client, CognitoUser user,
                                         String triggerKey, String triggerSource,
                                         Map<String, Object> request) {
        if (lambdaService == null) return TriggerResult.notConfigured();
        String functionRef = resolveTriggerArn(pool, triggerKey);
        if (functionRef == null) return TriggerResult.notConfigured();

        String region = regionForPool(pool);

        Map<String, Object> event = new HashMap<>();
        event.put("version", "1");
        event.put("region", region);
        event.put("userPoolId", pool.getId());
        event.put("userName", user == null ? null : user.getUsername());
        event.put("callerContext", Map.of(
                "awsSdkVersion", "floci",
                "clientId", client.getClientId()));
        event.put("triggerSource", triggerSource);
        Map<String, Object> req = new HashMap<>(request);
        if (user != null) {
            req.put("userAttributes", user.getAttributes() == null ? Map.of() : user.getAttributes());
        }
        event.put("request", req);
        event.put("response", new HashMap<>());

        try {
            byte[] payload = MAPPER.writeValueAsBytes(event);
            InvokeResult result = lambdaService.invoke(region, functionRef, payload, InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                String msg = String.format("trigger %s (%s) returned error: %s",
                        triggerKey, functionRef, result.getFunctionError());
                LOG.warnv("Cognito {0}", msg);
                return TriggerResult.error(msg);
            }
            if (result.getPayload() == null || result.getPayload().length == 0) {
                return TriggerResult.success(Map.of());
            }
            Map<String, Object> parsed = MAPPER.readValue(result.getPayload(), new TypeReference<>() {});
            Object response = parsed.get("response");
            Map<String, Object> respMap = response instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            return TriggerResult.success(respMap);
        } catch (AwsException ae) {
            LOG.warnv("Cognito trigger {0} not invokable: {1}", triggerKey, ae.getMessage());
            return TriggerResult.error(ae.getMessage());
        } catch (Exception e) {
            LOG.warnv(e, "Cognito trigger {0} invocation failed", triggerKey);
            return TriggerResult.error(e.getMessage());
        }
    }

    // ──────────────────────────── Pre/Post/PreToken/UserMigration ────────────────────────────

    private void firePreAuthentication(UserPool pool, UserPoolClient client, CognitoUser user,
                                        Map<String, String> validationData, Map<String, String> clientMetadata,
                                        boolean userNotFound) {
        Map<String, Object> req = new HashMap<>();
        req.put("validationData", validationData == null ? Map.of() : validationData);
        req.put("userNotFound", userNotFound);
        req.put("clientMetadata", clientMetadata == null ? Map.of() : clientMetadata);
        TriggerResult result = invokeTrigger(pool, client, user,
                "PreAuthentication", "PreAuthentication_Authentication", req);
        if (result.errored()) {
            throw new AwsException("NotAuthorizedException",
                    "PreAuthentication trigger denied authentication: " + result.errorMessage(), 400);
        }
    }

    private void firePostAuthentication(UserPool pool, UserPoolClient client, CognitoUser user,
                                         Map<String, String> clientMetadata, boolean newDeviceUsed) {
        Map<String, Object> req = new HashMap<>();
        req.put("newDeviceUsed", newDeviceUsed);
        req.put("clientMetadata", clientMetadata == null ? Map.of() : clientMetadata);
        invokeTrigger(pool, client, user, "PostAuthentication", "PostAuthentication_Authentication", req);
    }

    @SuppressWarnings("unchecked")
    private CognitoService.ClaimsOverride firePreTokenGeneration(UserPool pool, UserPoolClient client, CognitoUser user,
                                                                  Map<String, String> clientMetadata, String triggerSource) {
        Map<String, Object> req = new HashMap<>();
        req.put("groupConfiguration", buildGroupConfiguration(user));
        req.put("clientMetadata", clientMetadata == null ? Map.of() : clientMetadata);
        // V2 lambdas (CognitoEventUserPoolsPreTokenGenV2) require `scopes` to deserialize.
        // V1 lambdas tolerate the extra field.
        req.put("scopes", List.of());
        TriggerResult result = invokeTrigger(pool, client, user, "PreTokenGeneration", triggerSource, req);
        if (!result.configured() || result.errored()) return null;

        Map<String, Object> response = result.response();
        if (response == null) return null;

        // V2 response: claimsAndScopeOverrideDetails { idTokenGeneration, accessTokenGeneration, groupOverrideDetails }
        if (response.get("claimsAndScopeOverrideDetails") instanceof Map<?, ?> v2) {
            return parseV2Override(v2);
        }
        // V1 response: claimsOverrideDetails { claimsToAddOrOverride, claimsToSuppress, groupOverrideDetails }
        if (response.get("claimsOverrideDetails") instanceof Map<?, ?> v1) {
            return parseV1Override(v1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static CognitoService.ClaimsOverride parseV1Override(Map<?, ?> details) {
        Map<String, Object> claimsToAddOrOverride = asStringObjectMap(details.get("claimsToAddOrOverride"));
        List<String> claimsToSuppress = asStringList(details.get("claimsToSuppress"));

        List<String> groupsToOverride = null;
        List<String> iamRolesToOverride = null;
        String preferredRole = null;
        if (details.get("groupOverrideDetails") instanceof Map<?, ?> g) {
            groupsToOverride = asStringList(g.get("groupsToOverride"));
            iamRolesToOverride = asStringList(g.get("iamRolesToOverride"));
            if (g.get("preferredRole") instanceof String pr) preferredRole = pr;
        }

        if (claimsToAddOrOverride == null && claimsToSuppress == null
                && groupsToOverride == null && iamRolesToOverride == null && preferredRole == null) {
            return null;
        }
        // V1 applies the same claims map to both id and access tokens.
        return new CognitoService.ClaimsOverride(
                claimsToAddOrOverride, claimsToSuppress,
                claimsToAddOrOverride, claimsToSuppress,
                null, null,
                groupsToOverride, iamRolesToOverride, preferredRole);
    }

    @SuppressWarnings("unchecked")
    private static CognitoService.ClaimsOverride parseV2Override(Map<?, ?> details) {
        Map<String, Object> idAdd = null;
        List<String> idSuppress = null;
        Map<String, Object> accessAdd = null;
        List<String> accessSuppress = null;
        List<String> scopesToAdd = null;
        List<String> scopesToSuppress = null;

        if (details.get("idTokenGeneration") instanceof Map<?, ?> id) {
            idAdd = asStringObjectMap(id.get("claimsToAddOrOverride"));
            idSuppress = asStringList(id.get("claimsToSuppress"));
        }
        if (details.get("accessTokenGeneration") instanceof Map<?, ?> at) {
            accessAdd = asStringObjectMap(at.get("claimsToAddOrOverride"));
            accessSuppress = asStringList(at.get("claimsToSuppress"));
            scopesToAdd = asStringList(at.get("scopesToAdd"));
            scopesToSuppress = asStringList(at.get("scopesToSuppress"));
        }

        List<String> groupsToOverride = null;
        List<String> iamRolesToOverride = null;
        String preferredRole = null;
        if (details.get("groupOverrideDetails") instanceof Map<?, ?> g) {
            groupsToOverride = asStringList(g.get("groupsToOverride"));
            iamRolesToOverride = asStringList(g.get("iamRolesToOverride"));
            if (g.get("preferredRole") instanceof String pr) preferredRole = pr;
        }

        if (idAdd == null && idSuppress == null && accessAdd == null && accessSuppress == null
                && scopesToAdd == null && scopesToSuppress == null
                && groupsToOverride == null && iamRolesToOverride == null && preferredRole == null) {
            return null;
        }
        return new CognitoService.ClaimsOverride(
                idAdd, idSuppress, accessAdd, accessSuppress,
                scopesToAdd, scopesToSuppress,
                groupsToOverride, iamRolesToOverride, preferredRole);
    }

    private static Map<String, Object> asStringObjectMap(Object o) {
        if (!(o instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> l) || l.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (Object v : l) out.add(String.valueOf(v));
        return out;
    }

    private static Map<String, Object> buildGroupConfiguration(CognitoUser user) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("groupsToOverride", user.getGroupNames() == null ? List.of() : new ArrayList<>(user.getGroupNames()));
        cfg.put("iamRolesToOverride", List.of());
        cfg.put("preferredRole", null);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private CognitoUser tryUserMigration(UserPool pool, UserPoolClient client, String username, String password,
                                          Map<String, String> validationData, Map<String, String> clientMetadata,
                                          String triggerSource) {
        if (resolveTriggerArn(pool, "UserMigration") == null) return null;

        Map<String, Object> req = new HashMap<>();
        if (password != null) req.put("password", password);
        req.put("validationData", validationData == null ? Map.of() : validationData);
        req.put("clientMetadata", clientMetadata == null ? Map.of() : clientMetadata);
        req.put("userNotFound", true);
        // No user object yet — pass username through the event manually.
        Map<String, Object> event = new HashMap<>();
        event.put("version", "1");
        event.put("region", regionForPool(pool));
        event.put("userPoolId", pool.getId());
        event.put("userName", username);
        event.put("callerContext", Map.of(
                "awsSdkVersion", "floci",
                "clientId", client.getClientId()));
        event.put("triggerSource", triggerSource);
        event.put("request", req);
        event.put("response", new HashMap<>());

        try {
            byte[] payload = MAPPER.writeValueAsBytes(event);
            InvokeResult result = lambdaService.invoke(regionForPool(pool),
                    resolveTriggerArn(pool, "UserMigration"), payload, InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                LOG.warnv("UserMigration trigger errored: {0}", result.getFunctionError());
                return null;
            }
            if (result.getPayload() == null || result.getPayload().length == 0) return null;

            Map<String, Object> parsed = MAPPER.readValue(result.getPayload(), new TypeReference<>() {});
            Object responseObj = parsed.get("response");
            if (!(responseObj instanceof Map<?, ?> response)) return null;
            Object attrsObj = response.get("userAttributes");
            if (!(attrsObj instanceof Map<?, ?> attrs) || attrs.isEmpty()) return null;

            Map<String, String> typedAttrs = new HashMap<>();
            attrs.forEach((k, v) -> {
                if (v != null) typedAttrs.put(String.valueOf(k), String.valueOf(v));
            });
            String finalStatus = response.get("finalUserStatus") instanceof String s ? s : "CONFIRMED";

            service.adminCreateMigratedUser(pool.getId(), username, password, typedAttrs, finalStatus);
            return service.adminGetUser(pool.getId(), username);
        } catch (Exception e) {
            LOG.warnv(e, "UserMigration trigger invocation failed");
            return null;
        }
    }

    private Map<String, Object> issueTokens(UserPool pool, UserPoolClient client, CognitoUser user,
                                             String triggerSource, Map<String, String> clientMetadata) {
        firePostAuthentication(pool, client, user, clientMetadata, false);
        CognitoService.ClaimsOverride override = firePreTokenGeneration(pool, client, user, clientMetadata, triggerSource);
        return service.generateAuthResult(user, pool, client.getClientId(), override);
    }

    CognitoService.ClaimsOverride preTokenGenerationForRefresh(UserPool pool, UserPoolClient client, CognitoUser user) {
        return firePreTokenGeneration(pool, client, user, Map.of(), "TokenGeneration_RefreshTokens");
    }

    private static String resolveTriggerArn(UserPool pool, String triggerKey) {
        Map<String, Object> cfg = pool.getLambdaConfig();
        if (cfg == null) return null;
        Object v = cfg.get(triggerKey);
        if (v instanceof String s && !s.isBlank()) return s;
        // V2 form: PreTokenGeneration is configured under "PreTokenGenerationConfig"
        // as { LambdaArn, LambdaVersion } (UpdateUserPool / UpdateUserPoolClient
        // API). Fall through so callers using the V1 key still work, and pick up
        // the V2 ARN when only the V2 key is set.
        if ("PreTokenGeneration".equals(triggerKey)) {
            Object v2 = cfg.get("PreTokenGenerationConfig");
            if (v2 instanceof Map<?, ?> m) {
                Object arn = m.get("LambdaArn");
                if (arn instanceof String s && !s.isBlank()) return s;
            }
        }
        return null;
    }

    private String regionForPool(UserPool pool) {
        String arn = pool.getArn();
        if (arn != null) {
            String[] parts = arn.split(":", 6);
            if (parts.length >= 4 && !parts[3].isBlank()) return parts[3];
        }
        return regionResolver.getDefaultRegion();
    }
}
