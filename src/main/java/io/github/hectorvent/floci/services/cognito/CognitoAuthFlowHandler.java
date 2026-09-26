package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Owns Cognito authentication-flow protocol logic: USER_PASSWORD_AUTH,
 * USER_SRP_AUTH, REFRESH_TOKEN_AUTH, CUSTOM_AUTH, and challenge responses
 * (PASSWORD_VERIFIER, CUSTOM_CHALLENGE, NEW_PASSWORD_REQUIRED).
 *
 * For CUSTOM_AUTH, dispatches Cognito Lambda triggers
 * (DefineAuthChallenge, CreateAuthChallenge, VerifyAuthChallengeResponse).
 * CUSTOM_AUTH is trigger-driven: if any required trigger is not configured,
 * invocation fails, or the response is incomplete, authentication fails.
 *
 * Calls back into {@link CognitoService} for user/pool lookup and token
 * generation.
 */
final class CognitoAuthFlowHandler {

    private static final Logger LOG = Logger.getLogger(CognitoAuthFlowHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CUSTOM_MESSAGE_CODE_PARAMETER = "{####}";
    /** The message of a wrong password, which managed login also shows for an unknown user. */
    static final String INCORRECT_CREDENTIALS = "Incorrect username or password";
    /**
     * AWS default {@code AuthSessionValidity} (CreateUserPoolClient, valid range 3-15 minutes):
     * the lifetime of a challenge session token before InitiateAuth/RespondToAuthChallenge reject
     * it as expired. {@link UserPoolClient} does not yet store a per-client override, so every
     * client uses the AWS default until that field exists.
     */
    private static final Duration AUTH_SESSION_VALIDITY = Duration.ofMinutes(3);

    private final CognitoService service;
    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final ConcurrentHashMap<String, SrpSession> srpSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CustomAuthToken> customAuthSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserAuthSession> userAuthSessions = new ConcurrentHashMap<>();

    private record SrpSession(String userPoolId, String username, String clientId,
                              String aHex, String bHex, String bPublicHex,
                              String secretBlockBase64, Map<String, String> clientMetadata,
                              Instant issuedAt) {}

    /**
     * Correlates a USER_AUTH challenge response back to the InitiateAuth/RespondToAuthChallenge
     * call that issued it, the same way {@link SrpSession} and {@link CustomAuthSession} do for
     * their own challenges. Without this, RespondToAuthChallenge would accept any USERNAME and
     * factor answer with no session at all, bypassing handleUserAuth's tier gate and letting a
     * caller trigger an OTP send without ever starting a flow.
     */
    private record UserAuthSession(String userPoolId, String username, String clientId, String challengeName,
                                   Instant issuedAt) {}

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

    /**
     * One issued CUSTOM_AUTH session token. The flow state is shared across rounds, but each token
     * keeps the time it was minted, so issuing the next round's token never extends the old one.
     */
    private record CustomAuthToken(CustomAuthSession state, Instant issuedAt) {}

    CognitoAuthFlowHandler(CognitoService service, LambdaService lambdaService, RegionResolver regionResolver,
                           Clock clock) {
        this.service = service;
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    /**
     * True once {@code issuedAt} is older than {@link #AUTH_SESSION_VALIDITY}, matching the
     * NotAuthorizedException AWS returns for an expired InitiateAuth/RespondToAuthChallenge session.
     */
    private boolean sessionExpired(Instant issuedAt) {
        return clock.instant().isAfter(issuedAt.plus(AUTH_SESSION_VALIDITY));
    }

    private static AwsException sessionExpiredException() {
        return new AwsException("NotAuthorizedException",
                "Invalid session for the user, session is expired.", 400);
    }

    /** Stores a newly minted CUSTOM_AUTH token, stamped now, after dropping expired ones. */
    private void issueCustomAuthToken(String sessionToken, CustomAuthSession state) {
        purgeExpired(customAuthSessions, CustomAuthToken::issuedAt);
        customAuthSessions.put(sessionToken, new CustomAuthToken(state, clock.instant()));
    }

    /** Drops already-expired entries so an abandoned challenge session doesn't linger forever. */
    private <V> void purgeExpired(ConcurrentHashMap<String, V> sessions, Function<V, Instant> issuedAtOf) {
        sessions.entrySet().removeIf(entry -> sessionExpired(issuedAtOf.apply(entry.getValue())));
    }

    // ──────────────────────────── Public entry points ────────────────────────────

    Map<String, Object> initiateAuth(String clientId, String authFlow, Map<String, String> authParameters,
                                      Map<String, String> clientMetadata) {
        UserPoolClient client = service.findClientById(clientId);
        UserPool pool = service.describeUserPool(client.getUserPoolId());

        return switch (authFlow) {
            case "USER_PASSWORD_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_USER_PASSWORD_AUTH", "USER_PASSWORD_AUTH");
                yield authenticateWithPassword(pool, client, authParameters, clientMetadata);
            }
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> {
                requireRefreshEnabled(client, authFlow);
                yield handleRefreshToken(pool, client, authParameters, clientMetadata);
            }
            case "USER_SRP_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_USER_SRP_AUTH");
                yield handleUserSrpAuth(pool, client, authParameters, clientMetadata);
            }
            case "CUSTOM_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_CUSTOM_AUTH", "CUSTOM_AUTH_FLOW_ONLY");
                yield handleCustomAuth(pool, client, authParameters, clientMetadata);
            }
            case "USER_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_USER_AUTH");
                yield handleUserAuth(pool, client, authParameters, clientMetadata);
            }
            default -> throw unsupportedAuthFlow(authFlow);
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
            case "ADMIN_USER_PASSWORD_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ADMIN_NO_SRP_AUTH");
                yield authenticateWithPassword(pool, client, authParameters, clientMetadata);
            }
            case "ADMIN_NO_SRP_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ADMIN_NO_SRP_AUTH");
                yield authenticateWithPassword(pool, client, authParameters, clientMetadata);
            }
            case "USER_PASSWORD_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_USER_PASSWORD_AUTH", "USER_PASSWORD_AUTH",
                        "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ADMIN_NO_SRP_AUTH");
                yield authenticateWithPassword(pool, client, authParameters, clientMetadata);
            }
            case "REFRESH_TOKEN_AUTH", "REFRESH_TOKEN" -> {
                requireRefreshEnabled(client, authFlow);
                yield handleRefreshToken(pool, client, authParameters, clientMetadata);
            }
            case "ADMIN_USER_SRP_AUTH", "USER_SRP_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_USER_SRP_AUTH");
                yield handleUserSrpAuth(pool, client, authParameters, clientMetadata);
            }
            case "CUSTOM_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_CUSTOM_AUTH", "CUSTOM_AUTH_FLOW_ONLY");
                yield handleCustomAuth(pool, client, authParameters, clientMetadata);
            }
            case "USER_AUTH" -> {
                requireFlowEnabled(client, authFlow, "ALLOW_USER_AUTH");
                yield handleUserAuth(pool, client, authParameters, clientMetadata);
            }
            default -> throw unsupportedAuthFlow(authFlow);
        };
    }

    /**
     * Enforces the app client's {@code ExplicitAuthFlows}: the flow must be covered by one of
     * {@code acceptedValues}.
     */
    private static void requireFlowEnabled(UserPoolClient client, String authFlow, String... acceptedValues) {
        List<String> enabled = enabledAuthFlows(client);
        for (String accepted : acceptedValues) {
            if (enabled.contains(accepted)) {
                return;
            }
        }
        throw new AwsException("InvalidParameterException", authFlow + " flow not enabled for this client", 400);
    }

    /**
     * A client with no stored {@code ExplicitAuthFlows} gets the AWS default rather than every
     * flow. Floci never materialises the default into storage, so this covers every such client,
     * not just one from before enforcement existed.
     */
    private static List<String> enabledAuthFlows(UserPoolClient client) {
        List<String> enabled = client.getExplicitAuthFlows();
        return enabled == null || enabled.isEmpty() ? CognitoService.DEFAULT_EXPLICIT_AUTH_FLOWS : enabled;
    }

    /**
     * Refresh has to be listed explicitly via {@code ALLOW_REFRESH_TOKEN_AUTH}, except on clients that
     * only use legacy (non {@code ALLOW_}) values, where refresh was never gated.
     */
    private static void requireRefreshEnabled(UserPoolClient client, String authFlow) {
        if (enabledAuthFlows(client).stream().noneMatch(flow -> flow.startsWith("ALLOW_"))) {
            return;
        }
        requireFlowEnabled(client, authFlow, "ALLOW_REFRESH_TOKEN_AUTH");
    }

    private static AwsException unsupportedAuthFlow(String authFlow) {
        return new AwsException("InvalidParameterException", "Unsupported AuthFlow: " + authFlow, 400);
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
        if ("SELECT_CHALLENGE".equals(challengeName)) {
            return handleSelectChallenge(pool, client, session, responses, clientMetadata);
        }
        if ("PASSWORD".equals(challengeName)) {
            consumeUserAuthSession(pool, client, session, "PASSWORD");
            return authenticateWithPassword(pool, client, responses, clientMetadata);
        }
        if ("PASSWORD_SRP".equals(challengeName)) {
            consumeUserAuthSession(pool, client, session, "PASSWORD_SRP");
            return handleUserSrpAuth(pool, client, responses, clientMetadata);
        }
        if ("EMAIL_OTP".equals(challengeName) || "SMS_OTP".equals(challengeName)) {
            consumeUserAuthSession(pool, client, session, challengeName);
            return handleOtpChallengeResponse(pool, client, challengeName, responses, clientMetadata);
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

        CognitoUser user = verifyPassword(pool, client, username, password, clientMetadata);

        if (user.isTemporaryPassword() || "FORCE_CHANGE_PASSWORD".equals(user.getUserStatus())) {
            return buildNewPasswordRequiredChallenge(pool, client, user);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult",
                issueTokens(pool, client, user, "TokenGeneration_Authentication", clientMetadata));
        return result;
    }

    /**
     * Managed login's username and password sign-in: the checks of USER_PASSWORD_AUTH, without the
     * client's {@code ExplicitAuthFlows} or a {@code SECRET_HASH}, neither of which managed login
     * uses. It issues no tokens, since the token endpoint redeems the authorization code for them,
     * and it refuses a user who would get a challenge, because the sign-in page answers none.
     */
    CognitoUser authenticateManagedLogin(UserPool pool, UserPoolClient client, String username, String password) {
        CognitoUser user = verifyPassword(pool, client, username, password, Map.of());
        if (user.isTemporaryPassword() || "FORCE_CHANGE_PASSWORD".equals(user.getUserStatus())) {
            throw new AwsException("NotAuthorizedException", "This user must set a new password, which this "
                    + "sign-in page does not support. Set a permanent password with AdminSetUserPassword.", 400);
        }
        firePostAuthentication(pool, client, user, Map.of(), false);
        return user;
    }

    /**
     * The credential check USER_PASSWORD_AUTH and managed login share: finds the user by username or
     * alias, or through the UserMigration trigger, fires PreAuthentication, refuses a user who cannot
     * sign in, and compares the password.
     */
    private CognitoUser verifyPassword(UserPool pool, UserPoolClient client, String username, String password,
                                       Map<String, String> clientMetadata) {
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
            throw new AwsException("NotAuthorizedException", INCORRECT_CREDENTIALS, 400);
        }
        return user;
    }

    private Map<String, Object> handleRefreshToken(UserPool pool, UserPoolClient client,
                                                    Map<String, String> params, Map<String, String> clientMetadata) {
        String refreshToken = params.get("REFRESH_TOKEN");
        if (refreshToken == null) throw new AwsException("InvalidParameterException", "REFRESH_TOKEN is required", 400);
        String[] parts = service.parseRefreshToken(refreshToken);
        if (parts == null) {
            throw new AwsException("NotAuthorizedException", "Invalid Refresh Token", 400);
        }
        // Scope the token to the pool that minted it. HMAC verification only proves the token
        // was signed by parts[0]'s pool, not that parts[0] is the pool serving this request, so
        // a token legitimately issued for pool A must not be replayed against pool B's client.
        // Mirrors getTokensFromRefreshToken's pool-id check.
        if (!pool.getId().equals(parts[0])) {
            throw new AwsException("NotAuthorizedException", "Invalid Refresh Token", 400);
        }
        String username = parts[1];
        long iat;
        try {
            iat = parts.length > 3 && !parts[3].isEmpty() ? Long.parseLong(parts[3]) : 0L;
        } catch (NumberFormatException e) {
            throw new AwsException("NotAuthorizedException", "Invalid Refresh Token", 400);
        }
        String refreshTokenUuid = parts.length > 4 ? parts[4] : null;

        if (service.isRefreshTokenExpired(client, parts)) {
            throw new AwsException("NotAuthorizedException", "Refresh Token has expired", 400);
        }

        // Check revocation before issuing new tokens
        service.validateRefreshTokenNotRevoked(refreshTokenUuid, pool.getId(), username, iat);

        CognitoUser user;
        try {
            user = service.adminGetUser(pool.getId(), username);
        } catch (AwsException ae) {
            // Real Cognito never reveals whether the encoded username exists; any
            // refresh token that doesn't resolve to a real user is simply invalid.
            if ("UserNotFoundException".equals(ae.getErrorCode())) {
                throw new AwsException("NotAuthorizedException", "Invalid Refresh Token", 400);
            }
            throw ae;
        }
        CognitoService.ClaimsOverride override = firePreTokenGeneration(pool, client, user,
                clientMetadata, "TokenGeneration_RefreshTokens");
        Map<String, Object> auth = new HashMap<>();
        auth.put("AccessToken", service.generateSignedJwt(user, pool, "access", client, override, refreshTokenUuid));
        auth.put("IdToken", service.generateSignedJwt(user, pool, "id", client, override, refreshTokenUuid));
        auth.put("ExpiresIn", service.getAccessTokenExpiresInSeconds(client));
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
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new AwsException("NotAuthorizedException", "SECRET_HASH does not match", 400);
        }
    }

    private String resolveToCanonicalUsername(UserPool pool, String username) {
        try {
            return service.adminGetUser(pool.getId(), username).getUsername();
        } catch (AwsException e) {
            LOG.debugv("Could not resolve USERNAME {0} in pool {1}: {2}",
                    username, pool.getId(), e.getMessage());
            return null;
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
        if ("UNCONFIRMED".equals(user.getUserStatus())) {
            throw new AwsException("UserNotConfirmedException", "User is not confirmed", 400);
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

        purgeExpired(srpSessions, SrpSession::issuedAt);
        srpSessions.put(sessionToken, new SrpSession(
                pool.getId(), user.getUsername(), client.getClientId(),
                aHex, bHex, bPublicHex, secretBlockBase64,
                clientMetadata == null ? Map.of() : clientMetadata, clock.instant()));

        Map<String, Object> result = new HashMap<>();
        result.put("ChallengeName", "PASSWORD_VERIFIER");
        result.put("Session", sessionToken);
        result.put("ChallengeParameters", Map.of(
                "SALT", user.getSrpSalt(),
                "SRP_B", bPublicHex,
                "SECRET_BLOCK", secretBlockBase64,
                "USERNAME", user.getUsername(),
                "USER_ID_FOR_SRP", user.getUsername()
        ));
        return result;
    }

    private Map<String, Object> handlePasswordVerifierChallenge(UserPool pool, UserPoolClient client,
                                                                 String session, Map<String, String> responses,
                                                                 Map<String, String> clientMetadata) {
        CustomAuthToken customToken =
                session == null ? null : customAuthSessions.get(session);
        if (customToken != null) {
            if (sessionExpired(customToken.issuedAt())) {
                customAuthSessions.remove(session);
                throw sessionExpiredException();
            }
            return verifyPasswordWithinCustomAuth(pool, client, session, customToken.state(), responses,
                    clientMetadata);
        }

        SrpSession srp = srpSessions.get(session);
        if (srp == null) throw new AwsException("NotAuthorizedException", "Session not found", 400);
        if (sessionExpired(srp.issuedAt())) {
            srpSessions.remove(session);
            throw sessionExpiredException();
        }

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
        if ("UNCONFIRMED".equals(user.getUserStatus())) {
            throw new AwsException("UserNotConfirmedException", "User is not confirmed", 400);
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

    // ──────────────────────────── USER_AUTH (choice-based) ────────────────────────────

    /**
     * Entry point for the {@code USER_AUTH} choice-based flow. {@code PASSWORD} and
     * {@code PASSWORD_SRP} reuse {@link #authenticateWithPassword} and {@link #handleUserSrpAuth}
     * unchanged rather than reverifying the same credential a second way.
     *
     * <p>Not implemented: {@code WEB_AUTHN} and the {@code ConfirmSignUp} session as a
     * first-factor shortcut (tracked as follow-ups on the issue this was added for).
     */
    private Map<String, Object> handleUserAuth(UserPool pool, UserPoolClient client,
                                                Map<String, String> params, Map<String, String> clientMetadata) {
        // AWS requires the Essentials tier or higher for USER_AUTH.
        if ("LITE".equals(pool.getUserPoolTier())) {
            throw new AwsException("InvalidParameterException",
                    "USER_AUTH requires the user pool to be in the Essentials tier or higher", 400);
        }
        String username = params.get("USERNAME");
        if (username == null) {
            throw new AwsException("InvalidParameterException", "USERNAME is required", 400);
        }
        validateSecretHash(client, params, username);
        CognitoUser user = service.adminGetUser(pool.getId(), username);
        requireSignInEligible(user);

        List<String> available = availableUserAuthChallenges(user);
        if (available.isEmpty()) {
            throw new AwsException("NotAuthorizedException",
                    "No USER_AUTH challenge is available for this user", 400);
        }

        String preferred = params.get("PREFERRED_CHALLENGE");
        if (preferred == null) {
            return userAuthChallengeResponse(pool, client, user, "SELECT_CHALLENGE", available,
                    Map.of("USERNAME", user.getUsername()));
        }
        return startUserAuthChallenge(pool, client, user, preferred, available, true, params, clientMetadata);
    }

    /**
     * Responds to {@code SELECT_CHALLENGE}: {@code ANSWER} names the chosen factor. If the same
     * request already carries that factor's answer ({@code PASSWORD} or {@code SRP_A}), this
     * completes in one round trip instead of two.
     */
    private Map<String, Object> handleSelectChallenge(UserPool pool, UserPoolClient client, String session,
                                                        Map<String, String> responses,
                                                        Map<String, String> clientMetadata) {
        consumeUserAuthSession(pool, client, session, "SELECT_CHALLENGE");
        String username = responses.get("USERNAME");
        String answer = responses.get("ANSWER");
        if (username == null || answer == null) {
            throw new AwsException("InvalidParameterException", "USERNAME and ANSWER are required", 400);
        }
        validateSecretHash(client, responses, username);
        CognitoUser user = service.adminGetUser(pool.getId(), username);
        requireSignInEligible(user);
        List<String> available = availableUserAuthChallenges(user);
        return startUserAuthChallenge(pool, client, user, answer, available, false, responses, clientMetadata);
    }

    /**
     * Starts {@code challenge} for {@code user}. {@code advertiseAvailable} says whether the
     * challenge response may carry {@code AvailableChallenges}: only {@code InitiateAuthResponse}
     * and {@code AdminInitiateAuthResponse} declare that member, so the {@code SELECT_CHALLENGE}
     * path (a RespondToAuthChallenge response) passes {@code false}.
     */
    private Map<String, Object> startUserAuthChallenge(UserPool pool, UserPoolClient client, CognitoUser user,
                                                          String challenge, List<String> available,
                                                          boolean advertiseAvailable,
                                                          Map<String, String> params, Map<String, String> clientMetadata) {
        if (!available.contains(challenge)) {
            throw new AwsException("InvalidParameterException",
                    challenge + " is not an available challenge for this user", 400);
        }
        List<String> advertised = advertiseAvailable ? available : null;
        return switch (challenge) {
            case "PASSWORD" -> params.containsKey("PASSWORD")
                    ? authenticateWithPassword(pool, client, params, clientMetadata)
                    : userAuthChallengeResponse(pool, client, user, "PASSWORD", advertised,
                            Map.of("USERNAME", user.getUsername()));
            case "PASSWORD_SRP" -> params.containsKey("SRP_A")
                    ? handleUserSrpAuth(pool, client, params, clientMetadata)
                    : userAuthChallengeResponse(pool, client, user, "PASSWORD_SRP", advertised,
                            Map.of("USERNAME", user.getUsername()));
            case "EMAIL_OTP" -> issueOtpChallenge(pool, client, user, "EMAIL_OTP", "email", "EMAIL",
                    advertised, clientMetadata);
            case "SMS_OTP" -> issueOtpChallenge(pool, client, user, "SMS_OTP", "phone_number", "SMS",
                    advertised, clientMetadata);
            default -> throw new AwsException("InvalidParameterException",
                    challenge + " is not a supported challenge", 400);
        };
    }

    private Map<String, Object> issueOtpChallenge(UserPool pool, UserPoolClient client, CognitoUser user,
                                                    String challengeName, String attributeName, String deliveryMedium,
                                                    List<String> available, Map<String, String> clientMetadata) {
        firePreAuthentication(pool, client, user, null, clientMetadata, false);
        VerificationCode.Purpose purpose = "EMAIL_OTP".equals(challengeName)
                ? VerificationCode.Purpose.EMAIL_OTP : VerificationCode.Purpose.SMS_OTP;
        Map<String, Object> customMessage = fireCustomMessage(pool, client, user, "CustomMessage_Authentication");
        Map<String, String> challengeParams = new HashMap<>(
                service.issueSignInOtp(pool, user, purpose, attributeName, deliveryMedium, customMessage));
        challengeParams.put("USERNAME", user.getUsername());
        return userAuthChallengeResponse(pool, client, user, challengeName, available, challengeParams);
    }

    private Map<String, Object> handleOtpChallengeResponse(UserPool pool, UserPoolClient client, String challengeName,
                                                             Map<String, String> responses,
                                                             Map<String, String> clientMetadata) {
        String username = responses.get("USERNAME");
        boolean isEmail = "EMAIL_OTP".equals(challengeName);
        String codeParam = isEmail ? "EMAIL_OTP_CODE" : "SMS_OTP_CODE";
        String code = responses.get(codeParam);
        if (username == null || code == null) {
            throw new AwsException("InvalidParameterException", "USERNAME and " + codeParam + " are required", 400);
        }
        validateSecretHash(client, responses, username);
        CognitoUser user = service.adminGetUser(pool.getId(), username);
        requireSignInEligible(user);
        service.consumeSignInOtp(pool.getId(), user.getUsername(),
                isEmail ? VerificationCode.Purpose.EMAIL_OTP : VerificationCode.Purpose.SMS_OTP, code);

        if (user.isTemporaryPassword() || "FORCE_CHANGE_PASSWORD".equals(user.getUserStatus())) {
            return buildNewPasswordRequiredChallenge(pool, client, user);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("AuthenticationResult",
                issueTokens(pool, client, user, "TokenGeneration_Authentication", clientMetadata));
        return result;
    }

    /**
     * Builds a USER_AUTH challenge response and tracks its session. {@code available} is the
     * {@code AvailableChallenges} list to emit, or {@code null} to leave the member out, which
     * RespondToAuthChallenge responses must do because their shape does not declare it.
     */
    private Map<String, Object> userAuthChallengeResponse(UserPool pool, UserPoolClient client, CognitoUser user,
                                                            String challengeName, List<String> available,
                                                            Map<String, String> challengeParameters) {
        String session = buildSessionToken(pool.getId(), user.getUsername(), client.getClientId());
        purgeExpired(userAuthSessions, UserAuthSession::issuedAt);
        userAuthSessions.put(session,
                new UserAuthSession(pool.getId(), user.getUsername(), client.getClientId(), challengeName,
                        clock.instant()));
        Map<String, Object> result = new HashMap<>();
        result.put("ChallengeName", challengeName);
        result.put("Session", session);
        result.put("ChallengeParameters", challengeParameters);
        if (available != null) {
            result.put("AvailableChallenges", available);
        }
        return result;
    }

    /**
     * Validates that {@code session} was issued by {@link #userAuthChallengeResponse} for
     * {@code expectedChallenge} against this same pool and client, and consumes it so it cannot
     * be replayed against a second RespondToAuthChallenge call.
     */
    private void consumeUserAuthSession(UserPool pool, UserPoolClient client, String session,
                                         String expectedChallenge) {
        UserAuthSession state = session == null ? null : userAuthSessions.remove(session);
        if (state == null || !expectedChallenge.equals(state.challengeName())) {
            throw new AwsException("NotAuthorizedException", "Session not found", 400);
        }
        if (sessionExpired(state.issuedAt())) {
            throw sessionExpiredException();
        }
        if (!state.userPoolId().equals(pool.getId()) || !state.clientId().equals(client.getClientId())) {
            throw new AwsException("NotAuthorizedException", "Session does not match client", 400);
        }
    }

    /** The set of USER_AUTH challenges this user currently qualifies for. */
    private List<String> availableUserAuthChallenges(CognitoUser user) {
        List<String> available = new ArrayList<>();
        if (user.getPasswordHash() != null) {
            available.add("PASSWORD");
        }
        if (user.getSrpVerifier() != null) {
            available.add("PASSWORD_SRP");
        }
        Map<String, String> attrs = user.getAttributes();
        if (attrs != null && service.verificationServicesConfigured()) {
            if (Boolean.parseBoolean(attrs.getOrDefault("email_verified", "false")) && attrs.get("email") != null) {
                available.add("EMAIL_OTP");
            }
            if (Boolean.parseBoolean(attrs.getOrDefault("phone_number_verified", "false"))
                    && attrs.get("phone_number") != null) {
                available.add("SMS_OTP");
            }
        }
        return available;
    }

    private void requireSignInEligible(CognitoUser user) {
        if (!user.isEnabled()) {
            throw new AwsException("UserNotConfirmedException", "User is disabled", 400);
        }
        if ("RESET_REQUIRED".equals(user.getUserStatus())) {
            throw new AwsException("PasswordResetRequiredException", "Password reset required", 400);
        }
        if ("UNCONFIRMED".equals(user.getUserStatus())) {
            throw new AwsException("UserNotConfirmedException", "User is not confirmed", 400);
        }
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
                new CustomAuthSession(pool.getId(), user.getUsername(), client.getClientId());
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
        publicParams.put("USERNAME", user.getUsername());
        for (Map.Entry<String, String> e : authParameters.entrySet()) {
            if (!"USERNAME".equals(e.getKey()) && !"SRP_A".equals(e.getKey())) {
                publicParams.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        applyCreateResponse(state, challengeName,
                createAuthChallenge(pool, client, user, state, challengeName), publicParams);

        String sessionToken = buildSessionToken(pool.getId(), user.getUsername(), client.getClientId());
        issueCustomAuthToken(sessionToken, state);

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
        CustomAuthToken token = customAuthSessions.get(session);
        if (token == null) {
            throw new AwsException("NotAuthorizedException", "Session not found", 400);
        }
        if (sessionExpired(token.issuedAt())) {
            customAuthSessions.remove(session);
            throw sessionExpiredException();
        }
        CustomAuthSession state = token.state();
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

        String suppliedUsername = responses.getOrDefault("USERNAME", state.username);
        validateSecretHash(client, responses, suppliedUsername);

        CognitoUser user = service.adminGetUser(pool.getId(), state.username);
        if (!user.getUsername().equals(resolveToCanonicalUsername(pool, suppliedUsername))) {
            throw new AwsException("NotAuthorizedException", "Invalid session for the user.", 400);
        }

        boolean answerCorrect = verifyAuthChallenge(pool, client, user, state, answer);
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
        issueCustomAuthToken(newSession, state);

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
        issueCustomAuthToken(newSession, state);

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
        Map<String, Object> resp = requireCustomAuthTriggerResponse(
                invokeTrigger(pool, client, user, "DefineAuthChallenge",
                        "DefineAuthChallenge_Authentication", req),
                "DefineAuthChallenge");
        if (!resp.containsKey("challengeName")
                && !Boolean.TRUE.equals(resp.get("issueTokens"))
                && !Boolean.TRUE.equals(resp.get("failAuthentication"))) {
            throw customAuthTriggerFailure("InvalidLambdaResponseException",
                    "DefineAuthChallenge trigger returned no challenge decision");
        }
        return resp;
    }

    private Map<String, Object> createAuthChallenge(UserPool pool, UserPoolClient client, CognitoUser user,
                                                     CustomAuthSession state, String challengeName) {
        Map<String, Object> req = new HashMap<>();
        req.put("challengeName", challengeName);
        req.put("session", new ArrayList<>(state.history));
        req.put("clientMetadata", state.clientMetadata == null ? Map.of() : state.clientMetadata);
        return requireCustomAuthTriggerResponse(
                invokeTrigger(pool, client, user, "CreateAuthChallenge",
                        "CreateAuthChallenge_Authentication", req),
                "CreateAuthChallenge");
    }

    private boolean verifyAuthChallenge(UserPool pool, UserPoolClient client, CognitoUser user,
                                         CustomAuthSession state, String answer) {
        Map<String, Object> req = new HashMap<>();
        req.put("challengeAnswer", answer);
        req.put("privateChallengeParameters",
                state.privateChallengeParameters == null ? Map.of() : state.privateChallengeParameters);
        req.put("clientMetadata", state.clientMetadata == null ? Map.of() : state.clientMetadata);
        Map<String, Object> resp = requireCustomAuthTriggerResponse(
                invokeTrigger(pool, client, user, "VerifyAuthChallengeResponse",
                        "VerifyAuthChallengeResponse_Authentication", req),
                "VerifyAuthChallengeResponse");
        Object v = resp.get("answerCorrect");
        if (v instanceof Boolean b) {
            return b;
        }
        throw customAuthTriggerFailure("InvalidLambdaResponseException",
                "VerifyAuthChallengeResponse trigger returned no answerCorrect flag");
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

    private enum TriggerErrorKind {
        NOT_CONFIGURED,
        USER_VALIDATION,
        INVOCATION_FAILED,
        INVALID_RESPONSE
    }

    private record TriggerResult(Map<String, Object> response, String errorMessage, boolean configured,
                                 TriggerErrorKind errorKind) {
        static TriggerResult notConfigured() {
            return new TriggerResult(null, null, false, TriggerErrorKind.NOT_CONFIGURED);
        }
        static TriggerResult success(Map<String, Object> response) {
            return new TriggerResult(response, null, true, null);
        }
        static TriggerResult error(TriggerErrorKind kind, String msg) {
            return new TriggerResult(null, msg, true, kind);
        }
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
            Map<String, String> userAttributes = new LinkedHashMap<>();
            if (user.getAttributes() != null) {
                userAttributes.putAll(user.getAttributes());
            }
            if ("PreAuthentication".equals(triggerKey)) {
                userAttributes.put("cognito:user_status", user.getUserStatus());
            }
            req.put("userAttributes", userAttributes);
        }
        event.put("request", req);
        event.put("response", new HashMap<>());

        try {
            byte[] payload = MAPPER.writeValueAsBytes(event);
            InvokeResult result = lambdaService.invoke(region, functionRef, payload, InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                String errorMessage = lambdaFunctionErrorMessage(result);
                String msg = String.format("trigger %s (%s) returned error: %s",
                        triggerKey, functionRef, errorMessage);
                LOG.warnv("Cognito {0}", msg);
                return TriggerResult.error(TriggerErrorKind.USER_VALIDATION, errorMessage);
            }
            if (result.getPayload() == null || result.getPayload().length == 0) {
                return TriggerResult.success(Map.of());
            }
            Map<String, Object> parsed = MAPPER.readValue(result.getPayload(), new TypeReference<>() {});
            Object response = parsed.get("response");
            if (response != null && !(response instanceof Map<?, ?>)) {
                return TriggerResult.error(TriggerErrorKind.INVALID_RESPONSE,
                        triggerKey + " trigger returned a non-object response");
            }
            Map<String, Object> respMap = response instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            return TriggerResult.success(respMap);
        } catch (AwsException ae) {
            LOG.warnv("Cognito trigger {0} not invokable: {1}", triggerKey, ae.getMessage());
            return TriggerResult.error(TriggerErrorKind.INVOCATION_FAILED, ae.getMessage());
        } catch (Exception e) {
            LOG.warnv(e, "Cognito trigger {0} invocation failed", triggerKey);
            return TriggerResult.error(TriggerErrorKind.INVOCATION_FAILED, e.getMessage());
        }
    }

    private static String lambdaFunctionErrorMessage(InvokeResult result) {
        byte[] payload = result.getPayload();
        if (payload == null || payload.length == 0) {
            return result.getFunctionError();
        }
        try {
            JsonNode errorMessage = MAPPER.readTree(payload).path("errorMessage");
            if (errorMessage.isTextual() && !errorMessage.asText().isBlank()) {
                return errorMessage.asText();
            }
        } catch (Exception e) {
            LOG.debugv(e, "Unable to parse Cognito Lambda function error payload");
        }
        return result.getFunctionError();
    }

    private Map<String, Object> requireCustomAuthTriggerResponse(TriggerResult result, String triggerName) {
        if (!result.configured()) {
            throw customAuthTriggerFailure("InvalidUserPoolConfigurationException",
                    triggerName + " trigger is not configured");
        }
        if (result.errored()) {
            if (result.errorKind() == TriggerErrorKind.USER_VALIDATION) {
                throw customAuthTriggerFailure("UserLambdaValidationException",
                        triggerName + " failed with error " + result.errorMessage());
            }
            String errorCode = result.errorKind() == TriggerErrorKind.INVALID_RESPONSE
                    ? "InvalidLambdaResponseException"
                    : "UnexpectedLambdaException";
            throw customAuthTriggerFailure(errorCode, triggerName + " trigger failed: " + result.errorMessage());
        }
        if (result.response() == null) {
            throw customAuthTriggerFailure("InvalidLambdaResponseException",
                    triggerName + " trigger returned no response");
        }
        return result.response();
    }

    private AwsException customAuthTriggerFailure(String errorCode, String message) {
        return new AwsException(errorCode, message, 400);
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
                    "PreAuthentication failed with error " + result.errorMessage() + ".", 400);
        }
    }

    private void firePostAuthentication(UserPool pool, UserPoolClient client, CognitoUser user,
                                         Map<String, String> clientMetadata, boolean newDeviceUsed) {
        Map<String, Object> req = new HashMap<>();
        req.put("newDeviceUsed", newDeviceUsed);
        req.put("clientMetadata", clientMetadata == null ? Map.of() : clientMetadata);
        invokeTrigger(pool, client, user, "PostAuthentication", "PostAuthentication_Authentication", req);
    }

    void firePostConfirmation(UserPool pool, UserPoolClient client, CognitoUser user,
                               Map<String, String> clientMetadata, String triggerSource) {
        Map<String, Object> req = new HashMap<>();
        req.put("clientMetadata", clientMetadata == null ? Map.of() : clientMetadata);
        // PostConfirmation is fire-and-forget per AWS Cognito semantics:
        // trigger errors are logged but never block the confirm operation.
        invokeTrigger(pool, client, user, "PostConfirmation", triggerSource, req);
    }

    record PreSignUpResponse(boolean autoConfirmUser, boolean autoVerifyEmail, boolean autoVerifyPhone) {
        static PreSignUpResponse empty() { return new PreSignUpResponse(false, false, false); }
    }

    PreSignUpResponse firePreSignUp(UserPool pool, UserPoolClient client, CognitoUser user,
                                     Map<String, String> validationData,
                                     Map<String, String> clientMetadata,
                                     String triggerSource) {
        Map<String, Object> req = new HashMap<>();
        req.put("validationData", validationData == null ? Map.of() : validationData);
        req.put("clientMetadata", clientMetadata == null ? Map.of() : clientMetadata);
        TriggerResult result = invokeTrigger(pool, client, user, "PreSignUp", triggerSource, req);
        if (result.errored()) {
            throw new AwsException("NotAuthorizedException",
                    "PreSignUp trigger denied signup: " + result.errorMessage(), 400);
        }
        if (!result.configured() || result.response() == null) return PreSignUpResponse.empty();
        Map<String, Object> resp = result.response();
        return new PreSignUpResponse(
                Boolean.TRUE.equals(resp.get("autoConfirmUser")),
                Boolean.TRUE.equals(resp.get("autoVerifyEmail")),
                Boolean.TRUE.equals(resp.get("autoVerifyPhone")));
    }

    /**
     * Fires the CustomMessage trigger, if configured, so the function can override the
     * subject/body Cognito would otherwise deliver. Non-blocking: an unconfigured or
     * failing trigger falls back to the pool's default templated message, mirroring how
     * {@link #firePostAuthentication} and {@link #firePostConfirmation} tolerate errors.
     */
    Map<String, Object> fireCustomMessage(UserPool pool, UserPoolClient client, CognitoUser user,
                                           String triggerSource) {
        Map<String, Object> req = new HashMap<>();
        req.put("codeParameter", CUSTOM_MESSAGE_CODE_PARAMETER);
        req.put("usernameParameter", user.getUsername());
        req.put("clientMetadata", Map.of());
        TriggerResult result = invokeTrigger(pool, client, user, "CustomMessage", triggerSource, req);
        if (!result.configured()) return null;
        if (result.errored()) {
            LOG.warnv("CustomMessage trigger failed for pool {0} (source {1}): {2}",
                    pool.getId(), triggerSource, result.errorMessage());
            return null;
        }
        return result.response();
    }

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
        return service.generateAuthResult(user, pool, client, override);
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
        return AwsArnUtils.regionOrDefault(pool.getArn(), regionResolver.getDefaultRegion());
    }
}
