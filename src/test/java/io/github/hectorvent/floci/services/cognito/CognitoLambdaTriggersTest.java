package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that Cognito user-pool Lambda triggers fire on the right events with the right
 * triggerSource, and that their responses are correctly applied to the auth flow.
 */
class CognitoLambdaTriggersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LambdaService lambdaService;
    private CognitoService service;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", regionResolver, lambdaService);
    }

    private UserPool createPoolWithLambdaConfig(Map<String, Object> lambdaConfig) {
        Map<String, Object> req = new HashMap<>();
        req.put("PoolName", "trigger-pool");
        req.put("LambdaConfig", lambdaConfig);
        return service.createUserPool(req, "us-east-1");
    }

    private UserPoolClient createClient(UserPool pool) {
        return service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());
    }

    private void seedUser(UserPool pool, String username, String password) {
        service.adminCreateUser(pool.getId(), username,
                Map.of("email", username + "@example.com"), null);
        service.adminSetUserPassword(pool.getId(), username, password, true);
    }

    /** Builds a Lambda-style response payload with the given {@code response} block. */
    private static byte[] lambdaPayload(Map<String, Object> response) {
        try {
            return MAPPER.writeValueAsBytes(Map.of("response", response));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static InvokeResult ok(Map<String, Object> response) {
        return new InvokeResult(200, null, lambdaPayload(response), null, "req-id");
    }

    private static InvokeResult lambdaError(String error) {
        return new InvokeResult(200, error, new byte[0], null, "req-id");
    }

    private static String decodeJwtPayload(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    // =========================================================================
    // PreAuthentication
    // =========================================================================

    @Test
    void preAuthenticationFiresOnUserPasswordAuth() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        verify(lambdaService, atLeastOnce())
                .invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void preAuthenticationLambdaErrorBlocksAuthentication() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    // =========================================================================
    // PostAuthentication
    // =========================================================================

    @Test
    void postAuthenticationFiresAfterSuccessfulAuth() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PostAuthentication", "arn:aws:lambda:::post"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::post"), any(byte[].class), any()))
                .thenReturn(ok(Map.of()));

        service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        verify(lambdaService, atLeastOnce())
                .invoke(anyString(), eq("arn:aws:lambda:::post"), any(byte[].class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void postAuthenticationLambdaErrorDoesNotBlockAuth() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PostAuthentication", "arn:aws:lambda:::post"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::post"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> auth = (Map<String, Object>) result.get("AuthenticationResult");
        assertNotNull(auth, "Auth should still succeed when PostAuthentication errors");
        assertNotNull(auth.get("AccessToken"));
    }

    // =========================================================================
    // PostConfirmation
    // =========================================================================

    @Test
    void postConfirmationFiresOnConfirmSignUp() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PostConfirmation", "arn:aws:lambda:::post-confirm"));
        UserPoolClient client = createClient(pool);

        // SignUp creates an UNCONFIRMED user
        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::post-confirm"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        service.confirmSignUp(client.getClientId(), "alice");

        verify(lambdaService, atLeastOnce())
                .invoke(anyString(), eq("arn:aws:lambda:::post-confirm"),
                        any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void postConfirmationLambdaErrorDoesNotBlockConfirm() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PostConfirmation", "arn:aws:lambda:::post-confirm"));
        UserPoolClient client = createClient(pool);

        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::post-confirm"),
                any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        // confirmSignUp must succeed even if the trigger errors (Cognito semantics)
        service.confirmSignUp(client.getClientId(), "alice");
        // Test passes if no exception was thrown.
    }

    @Test
    void postConfirmationDoesNotFireWhenNotConfigured() {
        UserPool pool = createPoolWithLambdaConfig(Map.of());
        UserPoolClient client = createClient(pool);

        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));
        service.confirmSignUp(client.getClientId(), "alice");

        verify(lambdaService, never())
                .invoke(anyString(), anyString(), any(byte[].class), any());
    }

    // =========================================================================
    // PreSignUp
    // =========================================================================

    @Test
    void preSignUpFiresOnSignUp() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));

        verify(lambdaService, atLeastOnce())
                .invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                        any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void preSignUpLambdaErrorBlocksSignUp() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.signUp(client.getClientId(), "alice", "Perm1234!",
                        Map.of("email", "alice@example.com")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    @Test
    void preSignUpAutoConfirmUserResponseSkipsConfirmStep() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of(
                        "autoConfirmUser", true,
                        "autoVerifyEmail", true)));

        var user = service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));

        assertEquals("CONFIRMED", user.getUserStatus(),
                "autoConfirmUser=true should set status to CONFIRMED at signup");
        assertEquals("true", user.getAttributes().get("email_verified"),
                "autoVerifyEmail=true should set email_verified attribute");
    }

    @Test
    void preSignUpAutoVerifyPhoneSetsPhoneVerifiedAttribute() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of("autoVerifyPhone", true)));

        var user = service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("phone_number", "+15551234567"));

        assertEquals("true", user.getAttributes().get("phone_number_verified"),
                "autoVerifyPhone=true should set phone_number_verified attribute");
    }

    @Test
    void preSignUpAutoConfirmAlsoFiresPostConfirmation() {
        // AWS docs: when PreSignUp returns autoConfirmUser=true, PostConfirmation is also invoked.
        UserPool pool = createPoolWithLambdaConfig(Map.of(
                "PreSignUp", "arn:aws:lambda:::pre-signup",
                "PostConfirmation", "arn:aws:lambda:::post-confirm"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of("autoConfirmUser", true)));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::post-confirm"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of()));

        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));

        verify(lambdaService, atLeastOnce())
                .invoke(anyString(), eq("arn:aws:lambda:::post-confirm"),
                        any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void preSignUpDoesNotFireWhenNotConfigured() {
        UserPool pool = createPoolWithLambdaConfig(Map.of());
        UserPoolClient client = createClient(pool);

        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));

        verify(lambdaService, never())
                .invoke(anyString(), anyString(), any(byte[].class), any());
    }

    // =========================================================================
    // PreTokenGeneration
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void preTokenGenerationAddsAndOverridesClaims() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        Map<String, Object> claimsOverride = Map.of(
                "claimsToAddOrOverride", Map.of(
                        "tier", "gold",
                        "email", "override@example.com"),
                "claimsToSuppress", List.of("auth_time"),
                "groupOverrideDetails", Map.of(
                        "groupsToOverride", List.of("admins", "managers")));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("claimsOverrideDetails", claimsOverride)));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String accessToken = (String) ((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken");

        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(decodeJwtPayload(accessToken), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("gold", claims.get("tier"), "claimsToAddOrOverride should add 'tier'");
        assertEquals("override@example.com", claims.get("email"), "claimsToAddOrOverride should override 'email'");
        assertNull(claims.get("auth_time"), "claimsToSuppress should drop 'auth_time'");
        assertEquals(List.of("admins", "managers"), claims.get("cognito:groups"),
                "groupOverrideDetails.groupsToOverride should replace cognito:groups");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preTokenGenerationFiresOnRefreshTokenFlow() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("claimsOverrideDetails",
                        Map.of("claimsToAddOrOverride", Map.of("source", "refresh")))));

        Map<String, Object> initial = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String refreshToken = (String) ((Map<String, Object>) initial.get("AuthenticationResult")).get("RefreshToken");

        Map<String, Object> refreshResult = service.initiateAuth(client.getClientId(), "REFRESH_TOKEN_AUTH",
                Map.of("REFRESH_TOKEN", refreshToken));
        String accessToken = (String) ((Map<String, Object>) refreshResult.get("AuthenticationResult")).get("AccessToken");

        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(decodeJwtPayload(accessToken), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("refresh", claims.get("source"));
    }

    // =========================================================================
    // PreTokenGeneration V2
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void preTokenGenerationV2AppliesPerTokenClaimsSeparately() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        Map<String, Object> v2 = Map.of(
                "claimsAndScopeOverrideDetails", Map.of(
                        "idTokenGeneration", Map.of(
                                "claimsToAddOrOverride", Map.of("id_only", "yes", "tier", "id-gold"),
                                "claimsToSuppress", List.of("auth_time")),
                        "accessTokenGeneration", Map.of(
                                "claimsToAddOrOverride", Map.of("access_only", "yes", "tier", "access-gold"))));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(v2));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        Map<String, Object> auth = (Map<String, Object>) result.get("AuthenticationResult");

        Map<String, Object> idClaims;
        Map<String, Object> accessClaims;
        try {
            idClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("IdToken")), new TypeReference<>() {});
            accessClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("AccessToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertEquals("yes", idClaims.get("id_only"));
        assertEquals("id-gold", idClaims.get("tier"));
        assertNull(idClaims.get("access_only"), "access-only override must not leak into id token");
        assertNull(idClaims.get("auth_time"), "id-side claimsToSuppress should drop auth_time");

        assertEquals("yes", accessClaims.get("access_only"));
        assertEquals("access-gold", accessClaims.get("tier"));
        assertNull(accessClaims.get("id_only"), "id-only override must not leak into access token");
        assertNotNull(accessClaims.get("auth_time"), "access-side did not suppress auth_time");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preTokenGenerationV2AppliesScopeAddAndSuppressToAccessToken() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        Map<String, Object> v2 = Map.of(
                "claimsAndScopeOverrideDetails", Map.of(
                        "accessTokenGeneration", Map.of(
                                "claimsToAddOrOverride", Map.of("scope", "openid email phone"),
                                "scopesToSuppress", List.of("phone"),
                                "scopesToAdd", List.of("profile", "openid"))));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(v2));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String accessToken = (String) ((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken");

        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(decodeJwtPayload(accessToken), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String scope = (String) claims.get("scope");
        assertNotNull(scope);
        List<String> tokens = List.of(scope.split(" "));
        assertTrue(tokens.contains("openid"), "openid retained (already present; scopesToAdd dedups)");
        assertTrue(tokens.contains("email"), "email retained (not suppressed)");
        assertTrue(tokens.contains("profile"), "profile added by scopesToAdd");
        assertTrue(!tokens.contains("phone"), "phone dropped by scopesToSuppress");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preTokenGenerationV2ResolvesArnFromPreTokenGenerationConfigKey() {
        UserPool pool = createPoolWithLambdaConfig(Map.of(
                "PreTokenGenerationConfig", Map.of(
                        "LambdaArn", "arn:aws:lambda:::pre-token-v2",
                        "LambdaVersion", "V2_0")));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token-v2"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("claimsAndScopeOverrideDetails", Map.of(
                        "accessTokenGeneration", Map.of(
                                "claimsToAddOrOverride", Map.of("from_v2_config", "ok"))))));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String accessToken = (String) ((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken");

        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(decodeJwtPayload(accessToken), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("ok", claims.get("from_v2_config"),
                "V2 config object form (PreTokenGenerationConfig.LambdaArn) should resolve trigger ARN");
        verify(lambdaService, atLeastOnce())
                .invoke(anyString(), eq("arn:aws:lambda:::pre-token-v2"), any(byte[].class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preTokenGenerationV2RequestIncludesScopesField() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        org.mockito.ArgumentCaptor<byte[]> payloadCap = org.mockito.ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), payloadCap.capture(), any()))
                .thenReturn(ok(Map.of()));

        service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payloadCap.getValue(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, Object> req = (Map<String, Object>) event.get("request");
        assertNotNull(req.get("scopes"),
                "V2 lambdas (CognitoEventUserPoolsPreTokenGenV2) require `scopes` to deserialize");
        assertTrue(req.get("scopes") instanceof List<?>);
        assertNotNull(req.get("groupConfiguration"));
        assertNotNull(req.get("userAttributes"));
    }

    // =========================================================================
    // UserMigration
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void userMigrationCreatesAndAuthenticatesMissingUser() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("UserMigration", "arn:aws:lambda:::migrate"));
        UserPoolClient client = createClient(pool);

        Map<String, Object> migrationResp = Map.of(
                "userAttributes", Map.of(
                        "email", "newcomer@example.com",
                        "email_verified", "true"),
                "finalUserStatus", "CONFIRMED",
                "messageAction", "SUPPRESS");
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::migrate"), any(byte[].class), any()))
                .thenReturn(ok(migrationResp));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "newcomer", "PASSWORD", "MyPassword1!"));

        Map<String, Object> auth = (Map<String, Object>) result.get("AuthenticationResult");
        assertNotNull(auth, "Migrated user should authenticate successfully");
        assertNotNull(auth.get("AccessToken"));

        CognitoUser user = service.adminGetUser(pool.getId(), "newcomer");
        assertEquals("newcomer@example.com", user.getAttributes().get("email"));
        assertEquals("CONFIRMED", user.getUserStatus());
        assertTrue(user.isEnabled());
    }

    @Test
    void userMigrationLambdaErrorPropagatesUserNotFound() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("UserMigration", "arn:aws:lambda:::migrate"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::migrate"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "ghost", "PASSWORD", "x")));
        assertEquals("UserNotFoundException", ex.getErrorCode());
    }

    @Test
    void userMigrationNotInvokedWhenUserAlreadyExists() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("UserMigration", "arn:aws:lambda:::migrate"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        verify(lambdaService, never())
                .invoke(anyString(), eq("arn:aws:lambda:::migrate"), any(byte[].class), any());
    }

    // =========================================================================
    // CUSTOM_AUTH triggers (already covered indirectly; check triggerSource wiring)
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void customAuthInvokesDefineAndCreateChallengeTriggers() {
        UserPool pool = createPoolWithLambdaConfig(Map.of(
                "DefineAuthChallenge", "arn:aws:lambda:::define",
                "CreateAuthChallenge", "arn:aws:lambda:::create"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any()))
                .thenReturn(ok(Map.of(
                        "publicChallengeParameters", Map.of("question", "favourite-colour"),
                        "privateChallengeParameters", Map.of("answer", "blue"),
                        "challengeMetadata", "QA-V1")));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "alice"));

        assertEquals("CUSTOM_CHALLENGE", result.get("ChallengeName"));
        Map<String, String> params = (Map<String, String>) result.get("ChallengeParameters");
        assertEquals("favourite-colour", params.get("question"),
                "publicChallengeParameters from CreateAuthChallenge should appear in ChallengeParameters");
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any());
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyAuthChallengeLambdaDecidesCorrectness() {
        UserPool pool = createPoolWithLambdaConfig(Map.of(
                "DefineAuthChallenge", "arn:aws:lambda:::define",
                "VerifyAuthChallengeResponse", "arn:aws:lambda:::verify"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        // First Define call (initiation): present a challenge
        // Second Define call (after verify): issue tokens
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                .thenReturn(ok(Map.of("issueTokens", true)));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("answerCorrect", true)));

        Map<String, Object> initResult = service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "alice"));
        String session = (String) initResult.get("Session");

        Map<String, Object> tokens = service.respondToAuthChallenge(client.getClientId(),
                "CUSTOM_CHALLENGE", session,
                Map.of("USERNAME", "alice", "ANSWER", "anything"));

        assertNotNull(((Map<String, Object>) tokens.get("AuthenticationResult")).get("AccessToken"));
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class), any());
    }
}
