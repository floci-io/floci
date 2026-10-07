package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.ses.model.EmailContent;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    private static final List<String> AUTH_FLOWS = List.of("ALLOW_USER_PASSWORD_AUTH",
            "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_CUSTOM_AUTH", "ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH");


    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LambdaService lambdaService;
    private RegionResolver regionResolver;
    private CognitoService service;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), // revokedTokenStore
                "http://localhost:4566", regionResolver, lambdaService, mock(AcmService.class));
    }

    /**
     * Builds a CognitoService with a real {@link CognitoMessageDispatcher} (backed by the given
     * mocked {@link SesService}/{@link SnsService}) so tests can assert on the message actually
     * delivered, not just that dispatch() was called.
     */
    private CognitoService createServiceWithMessaging(SesService ses, SnsService sns,
                                                       VerificationCodeService verificationCodeService) {
        return new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", regionResolver, lambdaService, mock(AcmService.class),
                verificationCodeService, new CognitoMessageDispatcher(ses, sns, "us-east-1"),
                mock(TlsCertificateManager.class));
    }

    private UserPool createPoolWithLambdaConfig(Map<String, Object> lambdaConfig) {
        Map<String, Object> req = new HashMap<>();
        req.put("PoolName", "trigger-pool");
        req.put("LambdaConfig", lambdaConfig);
        return service.createUserPool(req, "us-east-1");
    }

    private UserPoolClient createClient(UserPool pool) {
        return service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of(),
                null, List.of(), null, AUTH_FLOWS, null, null, List.of(), null, List.of(), null,
                null, null, List.of(), null, null);
    }

    /** An OAuth client for the authorization-code flow, allowed exactly {@code allowedScopes}. */
    private UserPoolClient createOAuthClient(UserPool pool, List<String> allowedScopes) {
        return service.createUserPoolClient(pool.getId(), "oauth", false, true, List.of("code"), allowedScopes,
                null, List.of("https://application.example.test/callback"), null, AUTH_FLOWS, null, null,
                List.of(), null, List.of(), null, null, null, List.of(), null, null);
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

    private static InvokeResult lambdaError(String error, String errorMessage) {
        try {
            byte[] payload = MAPPER.writeValueAsBytes(Map.of(
                    "errorType", "Error",
                    "errorMessage", errorMessage));
            return new InvokeResult(200, error, payload, null, "req-id");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertVerificationEmail(SesService ses, String to, String subject, String body) {
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        SendEmailRequest sent = captor.getValue();
        assertEquals(SendEmailRequest.builder()
                .source(sent.source())
                .toAddresses(List.of(to))
                .region("us-east-1")
                .content(new EmailContent.Simple(subject, body, null, List.of()))
                .build(), sent);
    }

    private static InvokeResult rawPayload(String payload) {
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "req-id");
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preAuthenticationIncludesCognitoUserStatusAttribute(boolean adminAuth) throws Exception {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class),
                eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        if (adminAuth) {
            service.adminInitiateAuth(pool.getId(), client.getClientId(), "ADMIN_USER_PASSWORD_AUTH",
                    Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        } else {
            service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                    Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        }

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::pre"), payloadCaptor.capture(),
                eq(InvocationType.RequestResponse));

        Map<String, Object> event = MAPPER.readValue(payloadCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) event.get("request");
        @SuppressWarnings("unchecked")
        Map<String, Object> userAttributes = (Map<String, Object>) request.get("userAttributes");
        assertEquals("CONFIRMED", userAttributes.get("cognito:user_status"));
        assertEquals("alice@example.com", userAttributes.get("email"));
        assertFalse(service.adminGetUser(pool.getId(), "alice").getAttributes()
                .containsKey("cognito:user_status"));
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
        assertEquals("UserLambdaValidationException", ex.getErrorCode());
        assertEquals("PreAuthentication failed with error Unhandled.", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preAuthenticationLambdaErrorPreservesThrownMessage(boolean adminAuth) {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled", "Email not verified"));

        AwsException ex = assertThrows(AwsException.class, () -> {
            if (adminAuth) {
                service.adminInitiateAuth(pool.getId(), client.getClientId(), "ADMIN_USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
            } else {
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
            }
        });

        assertEquals("UserLambdaValidationException", ex.getErrorCode());
        assertEquals("PreAuthentication failed with error Email not verified.", ex.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-json", "{}", "{\"errorMessage\":null}",
            "{\"errorMessage\":\"\"}", "{\"errorMessage\":\" \"}",
            "{\"errorMessage\":123}", "{\"errorMessage\":true}",
            "{\"errorMessage\":[]}", "{\"errorMessage\":{}}"})
    void preAuthenticationLambdaErrorFallsBackWithoutUsableMessage(String errorPayload) {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);
        byte[] payload = errorPayload == null ? null : errorPayload.getBytes(StandardCharsets.UTF_8);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenReturn(new InvokeResult(200, "Unhandled", payload, null, "req-id"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));

        assertEquals("UserLambdaValidationException", ex.getErrorCode());
        assertEquals("PreAuthentication failed with error Unhandled.", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preAuthenticationUninvokableFunctionIsAnUnexpectedLambdaException(boolean adminAuth) {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenThrow(new AwsException("ResourceNotFoundException",
                        "Function not found: arn:aws:lambda:::pre", 404));

        AwsException ex = assertThrows(AwsException.class, () -> {
            if (adminAuth) {
                service.adminInitiateAuth(pool.getId(), client.getClientId(), "ADMIN_USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
            } else {
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
            }
        });

        assertEquals("UnexpectedLambdaException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("PreAuthentication trigger failed: Function not found: arn:aws:lambda:::pre", ex.getMessage());
    }

    @Test
    void preAuthenticationRuntimeFailureIsAnUnexpectedLambdaException() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenThrow(new IllegalStateException("runtime container crashed"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));

        assertEquals("UnexpectedLambdaException", ex.getErrorCode());
        assertEquals("PreAuthentication trigger failed: runtime container crashed", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{\"response\":\"not-an-object\"}", ""})
    void preAuthenticationMalformedResponseIsAnInvalidLambdaResponseException(String payload) {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenReturn(rawPayload(payload));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));

        assertEquals("InvalidLambdaResponseException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("PreAuthentication trigger failed: " + malformedResponseReason("PreAuthentication", payload),
                ex.getMessage());
    }

    /** The reason {@code invokeTrigger} attaches to each shape of malformed function output. */
    private static String malformedResponseReason(String trigger, String payload) {
        if (payload.isEmpty()) {
            return trigger + " trigger returned an empty response";
        }
        if ("not-json".equals(payload)) {
            return trigger + " trigger returned malformed JSON";
        }
        return trigger + " trigger returned a non-object response";
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
    void postAuthenticationLambdaErrorBlocksAuth() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PostAuthentication", "arn:aws:lambda:::post"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::post"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("UserLambdaValidationException", error.getErrorCode());
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
    void preSignUpFiresOnSignUp() throws Exception {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("email", "alice@example.com"));

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                payloadCaptor.capture(), eq(InvocationType.RequestResponse));
        assertFalse(MAPPER.readTree(payloadCaptor.getValue()).path("request")
                .path("userAttributes").has("cognito:user_status"));
    }

    @Test
    void preSignUpLambdaErrorBlocksSignUp() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled", "Invitations are closed"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.signUp(client.getClientId(), "alice", "Perm1234!",
                        Map.of("email", "alice@example.com")));
        assertEquals("UserLambdaValidationException", ex.getErrorCode());
        assertEquals("PreSignUp failed with error Invitations are closed.", ex.getMessage());
    }

    @Test
    void preSignUpUninvokableFunctionIsAnUnexpectedLambdaException() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenThrow(new AwsException("ResourceNotFoundException",
                        "Function not found: arn:aws:lambda:::pre-signup", 404));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.signUp(client.getClientId(), "alice", "Perm1234!",
                        Map.of("email", "alice@example.com")));

        assertEquals("UnexpectedLambdaException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("PreSignUp trigger failed: Function not found: arn:aws:lambda:::pre-signup", ex.getMessage());
        AwsException lookup = assertThrows(AwsException.class, () ->
                service.adminGetUser(pool.getId(), "alice"));
        assertEquals("UserNotFoundException", lookup.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{\"response\":\"not-an-object\"}", ""})
    void preSignUpMalformedResponseIsAnInvalidLambdaResponseException(String payload) {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(rawPayload(payload));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.signUp(client.getClientId(), "alice", "Perm1234!",
                        Map.of("email", "alice@example.com")));

        assertEquals("InvalidLambdaResponseException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("PreSignUp trigger failed: " + malformedResponseReason("PreSignUp", payload), ex.getMessage());
        AwsException lookup = assertThrows(AwsException.class, () ->
                service.adminGetUser(pool.getId(), "alice"));
        assertEquals("UserNotFoundException", lookup.getErrorCode());
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

    @Test
    void preSignUpFiresOnAdminCreateUserWithValidationDataAndClientMetadata() throws Exception {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        ObjectNode request = MAPPER.createObjectNode();
        request.put("UserPoolId", pool.getId());
        request.put("Username", "alice");
        request.put("MessageAction", "SUPPRESS");
        request.putArray("UserAttributes").addObject().put("Name", "email").put("Value", "alice@example.com");
        request.putArray("ValidationData").addObject().put("Name", "inviteCode").put("Value", "abc123");
        request.putObject("ClientMetadata").put("source", "admin-console");
        new CognitoJsonHandler(service, MAPPER).handle("AdminCreateUser", request, "us-east-1");

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                payloadCaptor.capture(), eq(InvocationType.RequestResponse));
        JsonNode event = MAPPER.readTree(payloadCaptor.getValue());
        assertEquals("PreSignUp_AdminCreateUser", event.path("triggerSource").asText());
        assertEquals("CLIENT_ID_NOT_APPLICABLE", event.path("callerContext").path("clientId").asText());
        assertEquals("alice", event.path("userName").asText());
        assertEquals("alice@example.com", event.path("request").path("userAttributes").path("email").asText());
        assertEquals("abc123", event.path("request").path("validationData").path("inviteCode").asText());
        assertEquals("admin-console", event.path("request").path("clientMetadata").path("source").asText());
        assertFalse(service.adminGetUser(pool.getId(), "alice").getAttributes().containsKey("inviteCode"),
                "ValidationData must not be stored as a user attribute");
    }

    @Test
    void preSignUpLambdaErrorBlocksAdminCreateUser() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled", "Invitations are closed"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminCreateUser(pool.getId(), "alice",
                        Map.of("email", "alice@example.com"), null, "SUPPRESS"));
        assertEquals("UserLambdaValidationException", ex.getErrorCode());
        assertEquals("PreSignUp failed with error Invitations are closed.", ex.getMessage());

        AwsException lookup = assertThrows(AwsException.class, () ->
                service.adminGetUser(pool.getId(), "alice"));
        assertEquals("UserNotFoundException", lookup.getErrorCode());
    }

    @Test
    void preSignUpAutoConfirmAndVerifyResponseIsIgnoredOnAdminCreateUser() {
        // AWS ignores autoConfirmUser, autoVerifyEmail and autoVerifyPhone for PreSignUp_AdminCreateUser.
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of(
                        "autoConfirmUser", true,
                        "autoVerifyEmail", true,
                        "autoVerifyPhone", true)));

        CognitoUser user = service.adminCreateUser(pool.getId(), "alice",
                Map.of("email", "alice@example.com", "phone_number", "+15551234567"),
                "Temp1234!", "SUPPRESS");

        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse));
        assertEquals("FORCE_CHANGE_PASSWORD", user.getUserStatus());
        assertNull(user.getAttributes().get("email_verified"));
        assertNull(user.getAttributes().get("phone_number_verified"));
    }

    @Test
    void preSignUpLambdaErrorOnForcedAliasCreationKeepsTheExistingAlias() {
        Map<String, Object> poolRequest = new HashMap<>();
        poolRequest.put("PoolName", "alias-trigger-pool");
        poolRequest.put("UsernameAttributes", List.of("email"));
        poolRequest.put("LambdaConfig", Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        UserPool pool = service.createUserPool(poolRequest, "us-east-1");
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of()))
                .thenReturn(lambdaError("Unhandled", "Refused"));
        CognitoUser holder = service.adminCreateUser(pool.getId(), "alice@example.com",
                Map.of("email_verified", "true"), null, "SUPPRESS");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminCreateUser(pool.getId(), "alice@example.com",
                        Map.of("email_verified", "true"), null, "SUPPRESS", true));
        assertEquals("UserLambdaValidationException", ex.getErrorCode());

        CognitoUser after = service.adminGetUser(pool.getId(), holder.getUsername());
        assertEquals("alice@example.com", after.getAttributes().get("email"));
        assertEquals("true", after.getAttributes().get("email_verified"));
    }

    @Test
    void preSignUpFunctionCallingBackForTheSameUsernameIsNotBlocked() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    try {
                        callFromFunction(() -> service.adminCreateUser(pool.getId(), "alice",
                                Map.of("name", "created by the function"), null, "SUPPRESS"));
                    } catch (ExecutionException e) {
                        callbackFailure.set(e.getCause());
                    }
                    return ok(Map.of());
                })
                .thenReturn(ok(Map.of()));

        // The function's call is for the username the request is creating. It would wait out the
        // invocation if the request held that username's lock stripe while the function ran, and
        // it is refused rather than invoking the trigger a second time for the same username.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.adminCreateUser(pool.getId(), "alice",
                Map.of("name", "created by the request"), null, "SUPPRESS"));

        assertEquals("UsernameExistsException",
                assertInstanceOf(AwsException.class, callbackFailure.get()).getErrorCode());
        assertEquals("created by the request",
                service.adminGetUser(pool.getId(), "alice").getAttributes().get("name"));
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void overlappingAdminCreateUserForTheSameUsernameInvokesPreSignUpOnce() throws Exception {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        List<String> invokedFor = new CopyOnWriteArrayList<>();
        CountDownLatch firstInTrigger = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    String request = nameAttributeOf(invocation);
                    invokedFor.add(request);
                    if ("first".equals(request)) {
                        firstInTrigger.countDown();
                        assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
                    }
                    return ok(Map.of());
                });

        ExecutorService firstRequest = Executors.newSingleThreadExecutor();
        try {
            Future<CognitoUser> first = firstRequest.submit(() -> service.adminCreateUser(pool.getId(), "alice",
                    Map.of("name", "first"), null, "SUPPRESS"));
            assertTrue(firstInTrigger.await(10, TimeUnit.SECONDS));

            // The first request is still in its trigger, so no user named alice is stored yet.
            AwsException second = assertThrows(AwsException.class, () -> service.adminCreateUser(pool.getId(),
                    "alice", Map.of("name", "second"), null, "SUPPRESS"));
            assertEquals("UsernameExistsException", second.getErrorCode());
            assertEquals("User already exists", second.getMessage());

            releaseFirst.countDown();
            assertEquals("alice", first.get(10, TimeUnit.SECONDS).getUsername());
        } finally {
            releaseFirst.countDown();
            firstRequest.shutdownNow();
        }

        assertEquals(List.of("first"), invokedFor);
        assertEquals("first", service.adminGetUser(pool.getId(), "alice").getAttributes().get("name"));
    }

    @Test
    void adminCreateUserOverlappingAFailingPreSignUpIsRefusedAndTheUsernameIsFreedAfterwards() throws Exception {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        List<String> invokedFor = new CopyOnWriteArrayList<>();
        CountDownLatch firstInTrigger = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        // The function refuses every user but the retried one, so a second request that reached it
        // would fail with the function's error instead of UsernameExistsException.
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    String request = nameAttributeOf(invocation);
                    invokedFor.add(request);
                    if ("first".equals(request)) {
                        firstInTrigger.countDown();
                        assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
                    }
                    return "retry".equals(request) ? ok(Map.of()) : lambdaError("Unhandled", "Refused");
                });

        ExecutorService firstRequest = Executors.newSingleThreadExecutor();
        try {
            Future<CognitoUser> first = firstRequest.submit(() -> service.adminCreateUser(pool.getId(), "alice",
                    Map.of("name", "first"), null, "SUPPRESS"));
            assertTrue(firstInTrigger.await(10, TimeUnit.SECONDS));

            AwsException second = assertThrows(AwsException.class, () -> service.adminCreateUser(pool.getId(),
                    "alice", Map.of("name", "second"), null, "SUPPRESS"));
            assertEquals("UsernameExistsException", second.getErrorCode());

            releaseFirst.countDown();
            ExecutionException firstFailure = assertThrows(ExecutionException.class,
                    () -> first.get(10, TimeUnit.SECONDS));
            assertEquals("UserLambdaValidationException",
                    assertInstanceOf(AwsException.class, firstFailure.getCause()).getErrorCode());
        } finally {
            releaseFirst.countDown();
            firstRequest.shutdownNow();
        }

        service.adminCreateUser(pool.getId(), "alice", Map.of("name", "retry"), null, "SUPPRESS");

        assertEquals(List.of("first", "retry"), invokedFor);
        assertEquals("retry", service.adminGetUser(pool.getId(), "alice").getAttributes().get("name"));
    }

    @Test
    void forcedAliasCreationDoesNotRestoreAHolderThePreSignUpFunctionDeleted() {
        UserPool pool = createEmailUsernamePoolWithPreSignUp();
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of()))
                .thenAnswer(invocation -> {
                    callFromFunction(() -> {
                        service.adminDeleteUser(pool.getId(), "alice@example.com");
                        return null;
                    });
                    return ok(Map.of());
                });
        CognitoUser holder = service.adminCreateUser(pool.getId(), "alice@example.com",
                Map.of("email_verified", "true"), null, "SUPPRESS");

        CognitoUser created = service.adminCreateUser(pool.getId(), "alice@example.com",
                Map.of("email_verified", "true"), null, "SUPPRESS", true);

        AwsException lookup = assertThrows(AwsException.class, () ->
                service.adminGetUser(pool.getId(), holder.getUsername()));
        assertEquals("UserNotFoundException", lookup.getErrorCode());
        assertEquals(created.getUsername(),
                service.adminGetUser(pool.getId(), "alice@example.com").getUsername());
    }

    @Test
    void forcedAliasCreationKeepsTheEmailThePreSignUpFunctionGaveTheHolder() {
        UserPool pool = createEmailUsernamePoolWithPreSignUp();
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-signup"),
                any(byte[].class), any()))
                .thenReturn(ok(Map.of()))
                .thenAnswer(invocation -> {
                    callFromFunction(() -> {
                        service.adminUpdateUserAttributes(pool.getId(), "alice@example.com",
                                Map.of("email", "alice.new@example.com"));
                        return null;
                    });
                    return ok(Map.of());
                });
        CognitoUser holder = service.adminCreateUser(pool.getId(), "alice@example.com",
                Map.of("email_verified", "true"), null, "SUPPRESS");

        CognitoUser created = service.adminCreateUser(pool.getId(), "alice@example.com",
                Map.of("email_verified", "true"), null, "SUPPRESS", true);

        assertEquals("alice.new@example.com",
                service.adminGetUser(pool.getId(), holder.getUsername()).getAttributes().get("email"));
        assertEquals(created.getUsername(),
                service.adminGetUser(pool.getId(), "alice@example.com").getUsername());
    }

    private UserPool createEmailUsernamePoolWithPreSignUp() {
        Map<String, Object> poolRequest = new HashMap<>();
        poolRequest.put("PoolName", "alias-trigger-pool");
        poolRequest.put("UsernameAttributes", List.of("email"));
        poolRequest.put("LambdaConfig", Map.of("PreSignUp", "arn:aws:lambda:::pre-signup"));
        return service.createUserPool(poolRequest, "us-east-1");
    }

    /**
     * Makes {@code call} the way a trigger function's SDK call reaches Floci: from another thread,
     * while the request that invoked the function waits for it to return.
     */
    private static <T> T callFromFunction(Callable<T> call) throws Exception {
        ExecutorService functionThread = Executors.newSingleThreadExecutor();
        try {
            return functionThread.submit(call).get(30, TimeUnit.SECONDS);
        } finally {
            functionThread.shutdownNow();
        }
    }

    /**
     * The {@code name} attribute of the user a trigger invocation is for, which tells the tests'
     * requests apart.
     */
    private static String nameAttributeOf(InvocationOnMock invocation) throws IOException {
        byte[] event = invocation.getArgument(2);
        return MAPPER.readTree(event).path("request").path("userAttributes").path("name").asText();
    }

    // =========================================================================
    // PreTokenGeneration
    // =========================================================================

    @Test
    void preTokenGenerationLambdaErrorBlocksTokenIssuance() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("UserLambdaValidationException", error.getErrorCode());
    }

    @Test
    void preTokenGenerationMalformedResponseBlocksTokenIssuance() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(rawPayload("not-json"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("InvalidLambdaResponseException", error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preTokenGenerationEmptyResponseBlocksTokenIssuance(boolean nullPayload) {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        byte[] payload = nullPayload ? null : new byte[0];
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(new InvokeResult(200, null, payload, null, "req-id"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("InvalidLambdaResponseException", error.getErrorCode());
    }

    @Test
    void newPasswordMfaSetupPreservesPreTokenGenerationSource() throws Exception {
        MutableClock clock = new MutableClock();
        service = serviceWithClock(clock);
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), "Temp1234!");
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);
        UserPoolClient client = createClient(pool);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(Map.of()));

        Map<String, Object> login = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Temp1234!"));
        assertEquals("NEW_PASSWORD_REQUIRED", login.get("ChallengeName"));
        Map<String, Object> setup = service.respondToAuthChallenge(client.getClientId(),
                "NEW_PASSWORD_REQUIRED", (String) login.get("Session"),
                Map.of("USERNAME", "alice", "NEW_PASSWORD", "Perm1234!"));
        assertEquals("MFA_SETUP", setup.get("ChallengeName"));
        Map<String, Object> associated = service.associateSoftwareToken(null, (String) setup.get("Session"));
        String code = CognitoTotp.code((String) associated.get("SecretCode"), clock.instant());
        Map<String, Object> verified = service.verifySoftwareToken(null, (String) associated.get("Session"), code);
        Map<String, Object> tokens = service.respondToAuthChallenge(client.getClientId(),
                "MFA_SETUP", (String) verified.get("Session"), Map.of("USERNAME", "alice"));
        assertNotNull(tokens.get("AuthenticationResult"));

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::pre-token"), payload.capture(), any());
        Map<String, Object> event = MAPPER.readValue(payload.getValue(), new TypeReference<>() {});
        assertEquals("TokenGeneration_NewPasswordChallenge", event.get("triggerSource"));
    }

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
    void preTokenGenerationV2SuppressingEveryScopeRemovesTheScopeClaim() throws Exception {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("claimsAndScopeOverrideDetails", Map.of(
                        "accessTokenGeneration", Map.of(
                                "scopesToSuppress", List.of("aws.cognito.signin.user.admin"))))));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String accessToken = (String) ((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken");

        Map<String, Object> claims = MAPPER.readValue(decodeJwtPayload(accessToken), new TypeReference<>() {});
        assertFalse(claims.containsKey("scope"), "suppressing the only scope leaves no scope claim: " + claims);
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

    // =========================================================================
    // Managed login shares USER_PASSWORD_AUTH's password check and its triggers
    // =========================================================================

    @Test
    void managedLoginFiresPreAndPostAuthenticationButIssuesNoTokens() {
        UserPool pool = createPoolWithLambdaConfig(Map.of(
                "PreAuthentication", "arn:aws:lambda:::pre",
                "PostAuthentication", "arn:aws:lambda:::post",
                "PreTokenGeneration", "arn:aws:lambda:::pretoken"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any())).thenReturn(ok(Map.of()));

        CognitoUser user = service.authenticateManagedLogin(client, "alice", "Perm1234!");

        assertEquals("alice", user.getUsername());
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any());
        verify(lambdaService).invoke(anyString(), eq("arn:aws:lambda:::post"), any(byte[].class), any());
        // Sign-in issues no tokens in the authorization-code flow: it hands back a code, and the
        // token endpoint mints the tokens later. PreTokenGeneration therefore belongs to redemption,
        // not here; see the hostedAuth tests below.
        verify(lambdaService, never()).invoke(anyString(), eq("arn:aws:lambda:::pretoken"), any(byte[].class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preTokenGenerationFiresWhenTheTokenEndpointRedeemsAnAuthorizationCode() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid", "email"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");

        ArgumentCaptor<byte[]> payloadCap = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), payloadCap.capture(), any()))
                .thenReturn(ok(Map.of("claimsOverrideDetails", Map.of(
                        "claimsToAddOrOverride", Map.of("tenant", "acme"),
                        "groupOverrideDetails", Map.of("groupsToOverride", List.of("admins"))))));

        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client, null,
                List.of("openid", "email"));

        Map<String, Object> event;
        Map<String, Object> idClaims;
        Map<String, Object> accessClaims;
        try {
            event = MAPPER.readValue(payloadCap.getValue(), new TypeReference<>() {});
            idClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("IdToken")), new TypeReference<>() {});
            accessClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("AccessToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("TokenGeneration_HostedAuth", event.get("triggerSource"),
                "AWS uses TokenGeneration_HostedAuth for sign-in through the hosted UI");
        assertEquals(List.of("openid", "email"), ((Map<String, Object>) event.get("request")).get("scopes"),
                "a V2 lambda may branch on the scopes the authorization request asked for");
        assertEquals("acme", idClaims.get("tenant"), "the trigger's claims should reach the ID token");
        assertEquals("acme", accessClaims.get("tenant"), "the trigger's claims should reach the access token");
        assertEquals(List.of("admins"), accessClaims.get("cognito:groups"),
                "groupOverrideDetails should apply on redemption as it does on InitiateAuth");
    }

    /**
     * The authorize endpoint refuses a scope the client may not use, but a client's AllowedOAuthScopes
     * can change before its code is redeemed. A V2 trigger may grant claims or scopes from what it is
     * told was requested, so it must never be told about one the client no longer allows.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aScopeTheClientIsNotAllowedNeverReachesTheTrigger() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid", "email"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        ArgumentCaptor<byte[]> payloadCap = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), payloadCap.capture(), any()))
                .thenReturn(ok(Map.of()));

        service.generateAuthResultForHostedAuth(user, pool, client, null,
                List.of("openid", "admin/superuser", "email"));

        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payloadCap.getValue(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(List.of("openid", "email"), ((Map<String, Object>) event.get("request")).get("scopes"),
                "an unallowed scope must be dropped, so a trigger cannot grant entitlements from it");
    }

    @Test
    void anOAuthClientWithNoAllowedScopesSendsTheTriggerNone() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of());
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        ArgumentCaptor<byte[]> payloadCap = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), payloadCap.capture(), any()))
                .thenReturn(ok(Map.of()));

        service.generateAuthResultForHostedAuth(user, pool, client, null, List.of("openid"));

        Map<String, Object> event;
        try {
            event = MAPPER.readValue(payloadCap.getValue(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        @SuppressWarnings("unchecked")
        Object scopes = ((Map<String, Object>) event.get("request")).get("scopes");
        assertEquals(List.of(), scopes, "a client allowed no scopes grants the trigger none");
    }

    /**
     * The OIDC flow owns {@code nonce}, and AWS lists it among the claims this trigger cannot
     * override or suppress. A trigger that tries must not displace the request's value.
     */
    @Test
    @SuppressWarnings("unchecked")
    void redemptionKeepsTheRequestNonceOverATriggerThatTriesToChangeIt() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("claimsOverrideDetails", Map.of(
                        "claimsToAddOrOverride", Map.of("nonce", "trigger-nonce", "tier", "gold"),
                        "claimsToSuppress", List.of("nonce")))));

        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client,
                new CognitoService.ClaimsOverride(Map.of("nonce", "request-nonce"), null, null, null,
                        null, null, null, null, null), List.of("openid"));

        Map<String, Object> idClaims;
        try {
            idClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("IdToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("request-nonce", idClaims.get("nonce"),
                "the authorization request's nonce must survive a trigger that overrides or suppresses it");
        assertEquals("gold", idClaims.get("tier"), "the trigger's other claims should still apply");
    }

    @Test
    void redemptionMintsTokensWhenNoPreTokenGenerationTriggerIsConfigured() {
        UserPool pool = createPoolWithLambdaConfig(Map.of());
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");

        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client,
                new CognitoService.ClaimsOverride(Map.of("nonce", "request-nonce"), null, null, null,
                        null, null, null, null, null), List.of("openid"));

        assertNotNull(auth.get("IdToken"));
        assertNotNull(auth.get("AccessToken"));
        assertNotNull(auth.get("RefreshToken"));
        Map<String, Object> idClaims;
        try {
            idClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("IdToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("request-nonce", idClaims.get("nonce"));
        verify(lambdaService, never()).invoke(anyString(), anyString(), any(byte[].class), any());
    }

    /**
     * AWS grants every scope the client allows to a request that names none, and that grant is what
     * the trigger is told and what the access token carries.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRequestWithoutScopesIsGrantedEveryAllowedScope() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid", "email", "aws.cognito.signin.user.admin"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        ArgumentCaptor<byte[]> payloadCap = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), payloadCap.capture(), any()))
                .thenReturn(ok(Map.of()));

        List<String> granted = CognitoService.grantedScopes(client, List.of());
        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client, null, granted);

        assertEquals(List.of("openid", "email", "aws.cognito.signin.user.admin"), granted);
        Map<String, Object> event;
        Map<String, Object> accessClaims;
        try {
            event = MAPPER.readValue(payloadCap.getValue(), new TypeReference<>() {});
            accessClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("AccessToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(granted, ((Map<String, Object>) event.get("request")).get("scopes"));
        assertEquals(Set.copyOf(granted), Set.of(((String) accessClaims.get("scope")).split(" ")));
        assertNotNull(auth.get("IdToken"));
    }

    /** Redemption issues the code's grant and never widens it, even when that grant is empty. */
    @Test
    void redemptionNeverWidensTheCodesGrant() {
        UserPool pool = createPoolWithLambdaConfig(Map.of());
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid", "email"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");

        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client, null, List.of());

        Map<String, Object> accessClaims;
        try {
            accessClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("AccessToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertFalse(accessClaims.containsKey("scope"), "an empty grant stays empty: " + accessClaims);
        assertFalse(auth.containsKey("IdToken"));
    }

    /** A V2 trigger that suppresses every scope of the grant leaves no scope claim. */
    @Test
    void preTokenGenerationV2SuppressingTheWholeCodeGrantRemovesTheScopeClaim() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid", "email"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("claimsAndScopeOverrideDetails", Map.of(
                        "accessTokenGeneration", Map.of("scopesToSuppress", List.of("email"))))));

        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client, null, List.of("email"));

        Map<String, Object> accessClaims;
        try {
            accessClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("AccessToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertFalse(accessClaims.containsKey("scope"), "suppressing the only granted scope leaves none: " + accessClaims);
    }

    /** Only a grant with {@code openid} gets an ID token, and the access token carries just the grant. */
    @Test
    void redemptionWithoutOpenidMintsNoIdToken() {
        UserPool pool = createPoolWithLambdaConfig(Map.of());
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid", "email"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");

        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client, null, List.of("email"));

        Map<String, Object> accessClaims;
        try {
            accessClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("AccessToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("email", accessClaims.get("scope"));
        assertFalse(auth.containsKey("IdToken"), "no openid, no ID token: " + auth.keySet());
        assertNotNull(auth.get("RefreshToken"));
    }

    /** V2 scope changes apply on top of the grant, not on top of a fixed default scope. */
    @Test
    void preTokenGenerationV2ScopeChangesApplyOnTopOfTheCodeGrant() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PreTokenGeneration", "arn:aws:lambda:::pre-token"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createOAuthClient(pool, List.of("openid", "email", "profile"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre-token"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("claimsAndScopeOverrideDetails", Map.of(
                        "accessTokenGeneration", Map.of(
                                "scopesToSuppress", List.of("email"),
                                "scopesToAdd", List.of("aws.cognito.signin.user.admin"))))));

        Map<String, Object> auth = service.generateAuthResultForHostedAuth(user, pool, client, null,
                List.of("openid", "email"));

        Map<String, Object> accessClaims;
        try {
            accessClaims = MAPPER.readValue(decodeJwtPayload((String) auth.get("AccessToken")), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(Set.of("openid", "aws.cognito.signin.user.admin"),
                Set.of(((String) accessClaims.get("scope")).split(" ")));
    }

    @Test
    void managedLoginPreAuthenticationErrorBlocksSignIn() {
        UserPool pool = createPoolWithLambdaConfig(Map.of(
                "PreAuthentication", "arn:aws:lambda:::pre",
                "PostAuthentication", "arn:aws:lambda:::post"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled", "Email not verified"));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.authenticateManagedLogin(client, "alice", "Perm1234!"));

        assertEquals("PreAuthentication failed with error Email not verified.", ex.getMessage());
        verify(lambdaService, never()).invoke(anyString(), eq("arn:aws:lambda:::post"), any(byte[].class), any());
    }

    @Test
    void managedLoginMigratesAMissingUser() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("UserMigration", "arn:aws:lambda:::migrate"));
        UserPoolClient client = createClient(pool);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::migrate"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("userAttributes", Map.of("email", "newcomer@example.com"),
                        "finalUserStatus", "CONFIRMED")));

        CognitoUser user = service.authenticateManagedLogin(client, "newcomer", "MyPassword1!");

        assertEquals("newcomer", user.getUsername());
        assertEquals("newcomer@example.com", service.adminGetUser(pool.getId(), "newcomer").getAttributes().get("email"));
    }

    /** Managed login needs neither ALLOW_USER_PASSWORD_AUTH nor a SECRET_HASH for a client with a secret. */
    @Test
    void managedLoginIgnoresExplicitAuthFlowsAndSecretHash() {
        UserPool pool = createPoolWithLambdaConfig(Map.of());
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", true, false, List.of(), List.of(),
                null, List.of(), null, List.of("ALLOW_REFRESH_TOKEN_AUTH"), null, null, List.of(), null, List.of(),
                null, null, null, List.of(), null, null);

        assertThrows(AwsException.class, () -> service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("alice", service.authenticateManagedLogin(client, "alice", "Perm1234!").getUsername());
    }

    @Test
    void managedLoginRefusesAUserWhoMustSetANewPassword() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("PostAuthentication", "arn:aws:lambda:::post"));
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), "Temp1234!");
        UserPoolClient client = createClient(pool);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.authenticateManagedLogin(client, "alice", "Temp1234!"));

        assertEquals("NotAuthorizedException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("must set a new password"), ex.getMessage());
        verify(lambdaService, never()).invoke(anyString(), eq("arn:aws:lambda:::post"), any(byte[].class), any());
    }

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
    void customPasswordChallengeRequiresTotpBeforeIssuingTokens() {
        UserPool pool = createPoolWithLambdaConfig(Map.of(
                "DefineAuthChallenge", "arn:aws:lambda:::define",
                "CreateAuthChallenge", "arn:aws:lambda:::create"));
        seedUser(pool, "alice", "Perm1234!");
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);
        UserPoolClient client = createClient(pool);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "PASSWORD_VERIFIER")))
                .thenReturn(ok(Map.of("issueTokens", true)));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any()))
                .thenReturn(ok(Map.of()));

        Map<String, Object> started = service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "alice"));
        assertEquals("PASSWORD_VERIFIER", started.get("ChallengeName"));
        Map<String, Object> completed = service.respondToAuthChallenge(client.getClientId(),
                "PASSWORD_VERIFIER", (String) started.get("Session"),
                Map.of("USERNAME", "alice", "ANSWER", "Perm1234!"));
        assertEquals("MFA_SETUP", completed.get("ChallengeName"));
        assertFalse(completed.containsKey("AuthenticationResult"));
    }

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
                        "CreateAuthChallenge", "arn:aws:lambda:::create",
                                "VerifyAuthChallengeResponse", "arn:aws:lambda:::verify"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        // First Define call (initiation): present a challenge
        // Second Define call (after verify): issue tokens
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                .thenReturn(ok(Map.of("issueTokens", true)));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class),
                        any())).thenReturn(
                                        ok(Map.of("publicChallengeParameters",
                                                        Map.of("question", "favourite-colour"),
                                                        "privateChallengeParameters",
                                                        Map.of("answer", "blue"))));
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

    private CognitoService serviceWithClock(MutableClock clock) {
        return new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", "cloudfront.net", regionResolver, lambdaService, mock(AcmService.class),
                null, null, mock(TlsCertificateManager.class), clock);
    }

    private UserPoolClient customAuthClient(CognitoService clockedService) {
        Map<String, Object> req = new HashMap<>();
        req.put("PoolName", "trigger-pool");
        req.put("LambdaConfig", Map.of(
                "DefineAuthChallenge", "arn:aws:lambda:::define",
                "CreateAuthChallenge", "arn:aws:lambda:::create",
                "VerifyAuthChallengeResponse", "arn:aws:lambda:::verify"));
        UserPool pool = clockedService.createUserPool(req, "us-east-1");
        clockedService.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), null);
        clockedService.adminSetUserPassword(pool.getId(), "alice", "Perm1234!", true);
        return clockedService.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of(),
                null, List.of(), null, AUTH_FLOWS, null, null, List.of(), null, List.of(), null,
                null, null, List.of(), null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void customAuthSessionValidityStartsWhenTheSessionIsIssued() {
        MutableClock clock = new MutableClock();
        CognitoService clockedService = serviceWithClock(clock);
        UserPoolClient client = customAuthClient(clockedService);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                .thenReturn(ok(Map.of("issueTokens", true)));
        // A slow CreateAuthChallenge trigger: two minutes pass before the session is handed out.
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any()))
                .thenAnswer(invocation -> {
                    clock.advance(Duration.ofMinutes(2));
                    return ok(Map.of("privateChallengeParameters", Map.of("answer", "blue")));
                });
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("answerCorrect", true)));

        String session = (String) clockedService.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "alice")).get("Session");
        clock.advance(Duration.ofMinutes(2));

        Map<String, Object> tokens = clockedService.respondToAuthChallenge(client.getClientId(),
                "CUSTOM_CHALLENGE", session, Map.of("USERNAME", "alice", "ANSWER", "blue"));

        assertNotNull(((Map<String, Object>) tokens.get("AuthenticationResult")).get("AccessToken"));
    }

    @Test
    void customAuthSessionExpiresThreeMinutesAfterItIsIssued() {
        MutableClock clock = new MutableClock();
        CognitoService clockedService = serviceWithClock(clock);
        UserPoolClient client = customAuthClient(clockedService);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("privateChallengeParameters", Map.of("answer", "blue"))));

        String session = (String) clockedService.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "alice")).get("Session");
        clock.advance(Duration.ofMinutes(client.getAuthSessionValidity()).plusSeconds(1));

        AwsException ex = assertThrows(AwsException.class, () ->
                clockedService.respondToAuthChallenge(client.getClientId(), "CUSTOM_CHALLENGE", session,
                        Map.of("USERNAME", "alice", "ANSWER", "blue")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
        assertEquals("Invalid session for the user, session is expired.", ex.getMessage());
        verify(lambdaService, never()).invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class), any());
    }

    @Test
    void customAuthSessionUsesConfiguredClientValidityAcrossOtherClientInitiation() {
        MutableClock clock = new MutableClock();
        CognitoService clockedService = serviceWithClock(clock);
        UserPoolClient longClient = customAuthClient(clockedService);
        longClient.setAuthSessionValidity(10);
        UserPoolClient shortClient = clockedService.createUserPoolClient(
                longClient.getUserPoolId(), "short", false, false, List.of(), List.of(),
                null, List.of(), null, AUTH_FLOWS, null, null, List.of(), null, List.of(), null,
                null, null, List.of(), null, null);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                .thenReturn(ok(Map.of("issueTokens", true)))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("privateChallengeParameters", Map.of("answer", "blue"))));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("answerCorrect", true)));

        String longSession = (String) clockedService.initiateAuth(longClient.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "alice")).get("Session");
        clock.advance(Duration.ofMinutes(4));
        clockedService.initiateAuth(shortClient.getClientId(), "CUSTOM_AUTH", Map.of("USERNAME", "alice"));

        Map<String, Object> result = clockedService.respondToAuthChallenge(longClient.getClientId(),
                "CUSTOM_CHALLENGE", longSession, Map.of("USERNAME", "alice", "ANSWER", "blue"));
        assertNotNull(result.get("AuthenticationResult"));

        String expiringSession = (String) clockedService.initiateAuth(longClient.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "alice")).get("Session");
        clock.advance(Duration.ofMinutes(longClient.getAuthSessionValidity()).plusSeconds(1));
        AwsException expired = assertThrows(AwsException.class, () -> clockedService.respondToAuthChallenge(
                longClient.getClientId(), "CUSTOM_CHALLENGE", expiringSession,
                Map.of("USERNAME", "alice", "ANSWER", "blue")));
        assertEquals("Invalid session for the user, session is expired.", expired.getMessage());
    }

    @Test
    void customAuthDoesNotIssueTokensWhenVerifyAuthChallengeTriggerErrors() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("DefineAuthChallenge",
                        "arn:aws:lambda:::define", "CreateAuthChallenge",
                        "arn:aws:lambda:::create", "VerifyAuthChallengeResponse",
                        "arn:aws:lambda:::verify"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        // First Define call presents a challenge. If the flow incorrectly falls back after
        // verify trigger failure, the second Define call will issue tokens and this test will
        // fail.
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class),
                        any())).thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                                        .thenReturn(ok(Map.of("issueTokens", true)));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class),
                        any())).thenReturn(
                                        ok(Map.of("publicChallengeParameters",
                                                        Map.of("question", "favourite-colour"),
                                                        "privateChallengeParameters",
                                                        Map.of("answer", "blue"))));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class),
                        any())).thenReturn(lambdaError("Unhandled"));

        Map<String, Object> initResult = service.initiateAuth(client.getClientId(),
                        "CUSTOM_AUTH", Map.of("USERNAME", "alice"));
        String session = (String) initResult.get("Session");

        AwsException ex = assertThrows(AwsException.class,
                        () -> service.respondToAuthChallenge(client.getClientId(),
                                        "CUSTOM_CHALLENGE", session,
                                        Map.of("USERNAME", "alice", "ANSWER", "anything")));

        assertEquals("UserLambdaValidationException", ex.getErrorCode());
        assertEquals("VerifyAuthChallengeResponse failed with error Unhandled", ex.getMessage());
    }

    @Test
    void customAuthFailsWhenTriggerReturnsNonObjectResponse() {
        UserPool pool = createPoolWithLambdaConfig(Map.of("DefineAuthChallenge", "arn:aws:lambda:::define"));
        seedUser(pool, "alice", "Perm1234!");
        UserPoolClient client = createClient(pool);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class),
                        any())).thenReturn(rawPayload("{\"response\":\"not-an-object\"}"));

        AwsException ex = assertThrows(AwsException.class,
                        () -> service.initiateAuth(client.getClientId(),
                                        "CUSTOM_AUTH", Map.of("USERNAME", "alice")));

        assertEquals("InvalidLambdaResponseException", ex.getErrorCode());
        assertEquals("DefineAuthChallenge trigger failed: DefineAuthChallenge trigger returned a non-object response",
                        ex.getMessage());
    }

    // =========================================================================
    // UsernameAttributes=email: triggers receive the canonical UUID as event.userName
    // =========================================================================

    private UserPool createEmailAliasPoolWithLambdaConfig(Map<String, Object> lambdaConfig) {
        Map<String, Object> req = new HashMap<>();
        req.put("PoolName", "alias-trigger-pool");
        req.put("UsernameAttributes", List.of("email"));
        req.put("LambdaConfig", lambdaConfig);
        return service.createUserPool(req, "us-east-1");
    }

    @Test
    void aliasPoolTriggerReceivesUuidUserNameAndEmailAttribute() throws Exception {
        UserPool pool = createEmailAliasPoolWithLambdaConfig(
                Map.of("PreAuthentication", "arn:aws:lambda:::pre"));
        UserPoolClient client = createClient(pool);

        // Alias pool: the supplied value is the email; canonical username is a minted UUID == sub.
        CognitoUser user = service.adminCreateUser(pool.getId(), "person@example.com",
                Map.of("email", "person@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "person@example.com", "Perm1234!", true);
        String uuid = user.getUsername();

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::pre"), any(byte[].class), any()))
                .thenReturn(ok(Map.of()));

        // Sign in with the email alias.
        service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "person@example.com", "PASSWORD", "Perm1234!"));

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService, atLeastOnce())
                .invoke(anyString(), eq("arn:aws:lambda:::pre"), payloadCaptor.capture(), any());

        Map<String, Object> event = MAPPER.readValue(payloadCaptor.getValue(), new TypeReference<>() {});
        assertEquals(uuid, event.get("userName"), "trigger event.userName must be the canonical UUID");
        assertNotEquals("person@example.com", event.get("userName"));

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) event.get("request");
        @SuppressWarnings("unchecked")
        Map<String, Object> userAttributes = (Map<String, Object>) request.get("userAttributes");
        assertEquals("person@example.com", userAttributes.get("email"),
                "trigger event.request.userAttributes.email must be the sign-in email");
        assertEquals(uuid, userAttributes.get("sub"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aliasPoolCustomAuthEchoesUuidUsernameAndValidatesSecretHashAgainstSentValue() {
        Map<String, Object> req = new HashMap<>();
        req.put("PoolName", "alias-custom-pool");
        req.put("UsernameAttributes", List.of("email"));
        req.put("LambdaConfig", Map.of(
                "DefineAuthChallenge", "arn:aws:lambda:::define",
                "CreateAuthChallenge", "arn:aws:lambda:::create",
                "VerifyAuthChallengeResponse", "arn:aws:lambda:::verify"));
        UserPool pool = service.createUserPool(req, "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());
        String secret = client.getClientSecret();
        assertNotNull(secret);

        CognitoUser user = service.adminCreateUser(pool.getId(), "custom@example.com",
                Map.of("email", "custom@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "custom@example.com", "Perm1234!", true);
        String uuid = user.getUsername();

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                .thenReturn(ok(Map.of("issueTokens", true)));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("publicChallengeParameters", Map.of("question", "q"),
                        "privateChallengeParameters", Map.of("answer", "blue"))));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("answerCorrect", true)));

        // Round 1: sign in with the email alias.
        Map<String, Object> init = service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "custom@example.com"));
        Map<String, String> challengeParams = (Map<String, String>) init.get("ChallengeParameters");
        // ChallengeParameters.USERNAME must echo the canonical UUID (as real AWS does), not the email.
        assertEquals(uuid, challengeParams.get("USERNAME"));
        assertNotEquals("custom@example.com", challengeParams.get("USERNAME"));
        String session = (String) init.get("Session");

        // Round 2 with USERNAME=UUID but SECRET_HASH computed over the email -> rejected
        // (SECRET_HASH is validated against the sent USERNAME).
        AwsException ex = assertThrows(AwsException.class, () ->
                service.respondToAuthChallenge(client.getClientId(), "CUSTOM_CHALLENGE", session,
                        Map.of("USERNAME", uuid, "ANSWER", "blue",
                                "SECRET_HASH", secretHash(secret, "custom@example.com", client.getClientId()))));
        assertEquals("NotAuthorizedException", ex.getErrorCode());

        // Round 2 with USERNAME=UUID and a matching SECRET_HASH over the UUID -> tokens issued.
        Map<String, Object> tokens = service.respondToAuthChallenge(client.getClientId(),
                "CUSTOM_CHALLENGE", session,
                Map.of("USERNAME", uuid, "ANSWER", "blue",
                        "SECRET_HASH", secretHash(secret, uuid, client.getClientId())));
        assertNotNull(((Map<String, Object>) tokens.get("AuthenticationResult")).get("AccessToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customAuthRejectsChallengeResponseUsernameThatIsNotTheSessionUser() {
        Map<String, Object> req = new HashMap<>();
        req.put("PoolName", "alias-custom-binding-pool");
        req.put("UsernameAttributes", List.of("email"));
        req.put("LambdaConfig", Map.of(
                "DefineAuthChallenge", "arn:aws:lambda:::define",
                "CreateAuthChallenge", "arn:aws:lambda:::create",
                "VerifyAuthChallengeResponse", "arn:aws:lambda:::verify"));
        UserPool pool = service.createUserPool(req, "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());
        String secret = client.getClientSecret();
        assertNotNull(secret);

        service.adminCreateUser(pool.getId(), "victim@example.com",
                Map.of("email", "victim@example.com"), null);
        CognitoUser other = service.adminCreateUser(pool.getId(), "other@example.com",
                Map.of("email", "other@example.com"), null);

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::define"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("challengeName", "CUSTOM_CHALLENGE")))
                .thenReturn(ok(Map.of("issueTokens", true)));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::create"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("publicChallengeParameters", Map.of("question", "q"),
                        "privateChallengeParameters", Map.of("answer", "blue"))));
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::verify"), any(byte[].class), any()))
                .thenReturn(ok(Map.of("answerCorrect", true)));

        Map<String, Object> init = service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", "victim@example.com"));
        String session = (String) init.get("Session");

        // A SECRET_HASH that is valid for a *different* user must not satisfy the challenge:
        // the sent USERNAME has to resolve to the session's user, as it does in real Cognito.
        AwsException ex = assertThrows(AwsException.class, () ->
                service.respondToAuthChallenge(client.getClientId(), "CUSTOM_CHALLENGE", session,
                        Map.of("USERNAME", other.getUsername(), "ANSWER", "blue",
                                "SECRET_HASH", secretHash(secret, other.getUsername(), client.getClientId()))));
        assertEquals("NotAuthorizedException", ex.getErrorCode());

        // The session user's own email alias still resolves to the session user, so it is accepted
        // even though the session was keyed off the canonical UUID.
        Map<String, Object> tokens = service.respondToAuthChallenge(client.getClientId(),
                "CUSTOM_CHALLENGE", session,
                Map.of("USERNAME", "victim@example.com", "ANSWER", "blue",
                        "SECRET_HASH", secretHash(secret, "victim@example.com", client.getClientId())));
        assertNotNull(((Map<String, Object>) tokens.get("AuthenticationResult")).get("AccessToken"));
    }

    private static String secretHash(String secret, String username, String clientId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((username + clientId).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // CustomMessage
    // =========================================================================

    @Test
    void customMessageOverridesSignUpVerificationEmail() {
        SesService ses = mock(SesService.class);
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                .thenReturn("246962");
        CognitoService svc = createServiceWithMessaging(ses, mock(SnsService.class), verificationCodeService);

        UserPool pool = svc.createUserPool(Map.of(
                "PoolName", "trigger-pool",
                "AutoVerifiedAttributes", List.of("email"),
                "LambdaConfig", Map.of("CustomMessage", "arn:aws:lambda:::custom-message")), "us-east-1");
        UserPoolClient client = svc.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::custom-message"),
                any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of(
                        "emailSubject", "TRIGGER-FIRED",
                        "emailMessage", "TRIGGER-FIRED code={####}")));

        svc.signUp(client.getClientId(), "spike", "Spike@Test1", Map.of("email", "spike@example.com"));

        assertVerificationEmail(ses, "spike@example.com", "TRIGGER-FIRED", "TRIGGER-FIRED code=246962");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customMessageSignUpTriggerReceivesCodeParameterAndUsername() throws Exception {
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                .thenReturn("246962");
        CognitoService svc = createServiceWithMessaging(mock(SesService.class), mock(SnsService.class),
                verificationCodeService);

        UserPool pool = svc.createUserPool(Map.of(
                "PoolName", "trigger-pool",
                "AutoVerifiedAttributes", List.of("email"),
                "LambdaConfig", Map.of("CustomMessage", "arn:aws:lambda:::custom-message")), "us-east-1");
        UserPoolClient client = svc.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::custom-message"),
                payloadCaptor.capture(), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        svc.signUp(client.getClientId(), "spike", "Spike@Test1", Map.of("email", "spike@example.com"));

        Map<String, Object> event = MAPPER.readValue(payloadCaptor.getValue(), new TypeReference<>() {});
        assertEquals("CustomMessage_SignUp", event.get("triggerSource"));
        Map<String, Object> request = (Map<String, Object>) event.get("request");
        assertEquals("{####}", request.get("codeParameter"));
        assertEquals("spike", request.get("usernameParameter"));
    }

    @Test
    void customMessageLambdaErrorFallsBackToDefaultSignUpMessage() {
        SesService ses = mock(SesService.class);
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                .thenReturn("246962");
        CognitoService svc = createServiceWithMessaging(ses, mock(SnsService.class), verificationCodeService);

        UserPool pool = svc.createUserPool(Map.of(
                "PoolName", "trigger-pool",
                "AutoVerifiedAttributes", List.of("email"),
                "LambdaConfig", Map.of("CustomMessage", "arn:aws:lambda:::custom-message")), "us-east-1");
        UserPoolClient client = svc.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::custom-message"), any(byte[].class), any()))
                .thenReturn(lambdaError("Unhandled"));

        // SignUp must still succeed and deliver the default message when CustomMessage errors.
        svc.signUp(client.getClientId(), "spike", "Spike@Test1", Map.of("email", "spike@example.com"));

        assertVerificationEmail(ses, "spike@example.com", "Your verification code",
                "Your verification code is 246962.");
    }

    @Test
    void customMessageDoesNotFireWhenNotConfigured() {
        SesService ses = mock(SesService.class);
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                .thenReturn("246962");
        CognitoService svc = createServiceWithMessaging(ses, mock(SnsService.class), verificationCodeService);

        UserPool pool = svc.createUserPool(Map.of(
                "PoolName", "trigger-pool",
                "AutoVerifiedAttributes", List.of("email")), "us-east-1");
        UserPoolClient client = svc.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        svc.signUp(client.getClientId(), "spike", "Spike@Test1", Map.of("email", "spike@example.com"));

        verify(lambdaService, never()).invoke(anyString(), anyString(), any(byte[].class), any());
        assertVerificationEmail(ses, "spike@example.com", "Your verification code",
                "Your verification code is 246962.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customMessageFiresWithResendCodeTriggerSourceOnResendConfirmationCode() throws Exception {
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                .thenReturn("111222");
        CognitoService svc = createServiceWithMessaging(mock(SesService.class), mock(SnsService.class),
                verificationCodeService);

        UserPool pool = svc.createUserPool(Map.of(
                "PoolName", "trigger-pool",
                "AutoVerifiedAttributes", List.of("email"),
                "LambdaConfig", Map.of("CustomMessage", "arn:aws:lambda:::custom-message")), "us-east-1");
        UserPoolClient client = svc.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());
        svc.signUp(client.getClientId(), "spike", "Spike@Test1", Map.of("email", "spike@example.com"));

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::custom-message"),
                payloadCaptor.capture(), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        svc.resendConfirmationCode(client.getClientId(), "spike");

        Map<String, Object> event = MAPPER.readValue(payloadCaptor.getValue(), new TypeReference<>() {});
        assertEquals("CustomMessage_ResendCode", event.get("triggerSource"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customMessageFiresWithForgotPasswordTriggerSourceOnForgotPassword() throws Exception {
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.PASSWORD_RESET), any()))
                .thenReturn("333444");
        CognitoService svc = createServiceWithMessaging(mock(SesService.class), mock(SnsService.class),
                verificationCodeService);

        UserPool pool = svc.createUserPool(Map.of(
                "PoolName", "trigger-pool",
                "LambdaConfig", Map.of("CustomMessage", "arn:aws:lambda:::custom-message")), "us-east-1");
        UserPoolClient client = svc.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());
        svc.adminCreateUser(pool.getId(), "spike",
                Map.of("email", "spike@example.com", "email_verified", "true"), null);
        svc.adminSetUserPassword(pool.getId(), "spike", "Spike@Test1", true);

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq("arn:aws:lambda:::custom-message"),
                payloadCaptor.capture(), eq(InvocationType.RequestResponse)))
                .thenReturn(ok(Map.of()));

        svc.forgotPassword(client.getClientId(), "spike");

        Map<String, Object> event = MAPPER.readValue(payloadCaptor.getValue(), new TypeReference<>() {});
        assertEquals("CustomMessage_ForgotPassword", event.get("triggerSource"));
    }
}
