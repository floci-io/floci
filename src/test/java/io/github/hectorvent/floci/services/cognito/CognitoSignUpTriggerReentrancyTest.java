package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeException;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CognitoSignUpTriggerReentrancyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FUNCTION = "signup-trigger";
    private static final String PHONE = "+12025550123";

    private LambdaService lambdaService;
    private RegionResolver regionResolver;
    private CognitoService service;
    private VerificationCodeService codes;
    private SnsService sns;
    private ExecutorService callbackExecutor;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        regionResolver = new RegionResolver("us-east-1", "000000000000");
        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory("000000000000");
        EmulatorConfig config = new SmallRyeConfigBuilder().withMapping(EmulatorConfig.class)
                .build().getConfigMapping(EmulatorConfig.class);
        sns = new SnsService(storageFactory, config, regionResolver, null, null, null, MAPPER, null);
        codes = new VerificationCodeService(storageFactory, Clock.systemUTC());
        service = serviceWithSns(sns);
        callbackExecutor = Executors.newSingleThreadExecutor();
    }

    private CognitoService serviceWithSns(SnsService smsService) {
        return new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), "http://localhost:4566",
                regionResolver, lambdaService, null, codes,
                new CognitoMessageDispatcher(null, smsService, "us-east-1"), null);
    }

    @AfterEach
    void stopCallbackExecutor() throws InterruptedException {
        if (callbackExecutor != null) {
            callbackExecutor.shutdownNow();
            assertTrue(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS),
                    "trigger callback executor must terminate after the request releases its locks");
        }
    }

    @Test
    void confirmSignUpPostConfirmationCanUpdateTheSameUserBeforeReturning() throws Exception {
        UserPool pool = pool(Map.of("PostConfirmation", FUNCTION));
        UserPoolClient client = client(pool);
        CognitoUser user = service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("phone_number", PHONE));
        String code = sns.getSentMessages().getFirst().getMessage().replaceAll("\\D", "");
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    assertEquals("PostConfirmation_ConfirmSignUp", event.path("triggerSource").asText());
                    assertEquals("confirm", event.path("request").path("clientMetadata").path("source").asText());
                    callback(() -> service.adminUpdateUserAttributes(pool.getId(), user.getUsername(),
                            Map.of("name", "updated by confirmation trigger")), callbackFailure);
                    return response(Map.of());
                });

        service.confirmSignUp(client.getClientId(), "alice", code, Map.of("source", "confirm"));

        assertNull(callbackFailure.get(), () -> "PostConfirmation callback failed before returning: "
                + callbackFailure.get());
        CognitoUser confirmed = service.adminGetUser(pool.getId(), "alice");
        assertEquals("CONFIRMED", confirmed.getUserStatus());
        assertEquals("true", confirmed.getAttributes().get("phone_number_verified"));
        assertEquals("updated by confirmation trigger", confirmed.getAttributes().get("name"));
        VerificationCodeException consumed = assertThrows(VerificationCodeException.class,
                () -> codes.consume(pool.getId(), "alice", VerificationCode.Purpose.SIGNUP_CONFIRMATION, code));
        assertEquals(VerificationCodeException.Kind.NOT_FOUND, consumed.getKind());
    }

    @Test
    void confirmationStillDispatchesPostConfirmationWhenThePersistedUsersPoolIsDeleted() throws Exception {
        AtomicReference<String> confirmationCode = new AtomicReference<>();
        AtomicBoolean poolDeleted = new AtomicBoolean();
        InMemoryStorage<String, CognitoUser> userStore = new InMemoryStorage<>() {
            @Override
            public void put(String key, CognitoUser user) {
                super.put(key, user);
                if ("CONFIRMED".equals(user.getUserStatus())) {
                    assertEquals("CONFIRMED", get(key).orElseThrow().getUserStatus());
                    assertEquals("true", user.getAttributes().get("phone_number_verified"));
                    VerificationCodeException consumed = assertThrows(VerificationCodeException.class,
                            () -> codes.consume(user.getUserPoolId(), user.getUsername(),
                                    VerificationCode.Purpose.SIGNUP_CONFIRMATION, confirmationCode.get()));
                    assertEquals(VerificationCodeException.Kind.NOT_FOUND, consumed.getKind());
                    service.deleteUserPool(user.getUserPoolId());
                    assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                            () -> service.describeUserPool(user.getUserPoolId())).getErrorCode());
                    poolDeleted.set(true);
                }
            }
        };
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), userStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), "http://localhost:4566",
                regionResolver, lambdaService, null, codes,
                new CognitoMessageDispatcher(null, sns, "us-east-1"), null);
        UserPool pool = pool(Map.of("PostConfirmation", FUNCTION));
        UserPoolClient client = client(pool);
        service.signUp(client.getClientId(), "alice", "Perm1234!", Map.of("phone_number", PHONE));
        confirmationCode.set(sns.getSentMessages().getFirst().getMessage().replaceAll("\\D", ""));
        AtomicReference<JsonNode> event = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    assertTrue(poolDeleted.get(), "the pool must already be deleted when the trigger is dispatched");
                    event.set(MAPPER.readTree((byte[]) invocation.getArgument(2)));
                    return response(Map.of());
                });

        service.confirmSignUp(client.getClientId(), "alice", confirmationCode.get(), Map.of("source", "confirm"));

        assertTrue(poolDeleted.get());
        assertNotNull(event.get(), "persisted confirmation must still invoke its captured PostConfirmation trigger");
        assertEquals("PostConfirmation_ConfirmSignUp", event.get().path("triggerSource").asText());
        assertEquals(pool.getId(), event.get().path("userPoolId").asText());
        assertEquals("alice", event.get().path("userName").asText());
        assertEquals("true", event.get().path("request").path("userAttributes").path("phone_number_verified").asText());
        assertEquals("confirm", event.get().path("request").path("clientMetadata").path("source").asText());
    }

    @Test
    void autoConfirmedSignUpPostConfirmationCanUpdateTheSameUserBeforeReturning() throws Exception {
        UserPool pool = pool(Map.of("PreSignUp", "auto-confirm", "PostConfirmation", FUNCTION));
        UserPoolClient client = client(pool);
        when(lambdaService.invoke(anyString(), eq("auto-confirm"), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenReturn(response(Map.of("autoConfirmUser", true)));
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    assertEquals("PostConfirmation_ConfirmSignUp", event.path("triggerSource").asText());
                    callback(() -> service.adminUpdateUserAttributes(pool.getId(), event.path("userName").asText(),
                            Map.of("name", "updated by auto-confirm trigger")), callbackFailure);
                    return response(Map.of());
                });

        CognitoUser user = service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("phone_number", PHONE));

        assertNull(callbackFailure.get(), () -> "auto-confirm PostConfirmation callback failed before returning: "
                + callbackFailure.get());
        assertEquals("CONFIRMED", user.getUserStatus());
        assertEquals("updated by auto-confirm trigger",
                service.adminGetUser(pool.getId(), user.getUsername()).getAttributes().get("name"));
        assertTrue(sns.getSentMessages().isEmpty());
    }

    @Test
    void customMessageSignUpCanUpdateTheSameUserBeforeReturning() throws Exception {
        UserPool pool = pool(Map.of("CustomMessage", FUNCTION));
        UserPoolClient client = client(pool);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    assertEquals("CustomMessage_SignUp", event.path("triggerSource").asText());
                    callback(() -> service.adminUpdateUserAttributes(pool.getId(), event.path("userName").asText(),
                            Map.of("name", "updated by message trigger")), callbackFailure);
                    return response(Map.of("smsMessage", "Use code {####}"));
                });

        CognitoUser user = service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("phone_number", PHONE));

        assertNull(callbackFailure.get(), () -> "CustomMessage callback failed before returning: "
                + callbackFailure.get());
        assertEquals("UNCONFIRMED", user.getUserStatus());
        assertEquals("updated by message trigger",
                service.adminGetUser(pool.getId(), user.getUsername()).getAttributes().get("name"));
        assertEquals(1, sns.getSentMessages().size());
        assertTrue(sns.getSentMessages().getFirst().getMessage().matches("Use code \\d{6}"));
    }

    @Test
    void preSignUpRejectsSameUsernameAdminCreationWithoutBlockingTheTrigger() throws Exception {
        UserPool pool = pool(Map.of("PreSignUp", FUNCTION));
        UserPoolClient client = client(pool);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<AwsException> duplicateFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    if ("PreSignUp_SignUp".equals(event.path("triggerSource").asText())) {
                        callback(() -> {
                            AwsException duplicate = assertThrows(AwsException.class,
                                    () -> service.adminCreateUser(pool.getId(), "alice",
                                            Map.of("name", "created by trigger"), null, "SUPPRESS"));
                            assertEquals("UsernameExistsException", duplicate.getErrorCode());
                            duplicateFailure.set(duplicate);
                        }, callbackFailure);
                    }
                    return response(Map.of());
                });

        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("phone_number", PHONE, "name", "created by signup"));

        assertNull(callbackFailure.get(), () -> "PreSignUp callback failed before returning: "
                + callbackFailure.get());
        assertNotNull(duplicateFailure.get(), "the PreSignUp trigger must complete its same-username callback");
        assertEquals("created by signup", service.adminGetUser(pool.getId(), "alice").getAttributes().get("name"));
    }

    @Test
    void preSignUpCanUpdateAnExistingUserOnTheSameLockStripe() throws Exception {
        UserPool pool = pool(Map.of());
        UserPoolClient client = client(pool);
        service.adminCreateUser(pool.getId(), "BB", Map.of(), "Perm1234!", "SUPPRESS");
        pool.setLambdaConfig(Map.of("PreSignUp", FUNCTION));
        assertEquals((pool.getId() + "::Aa").hashCode(), (pool.getId() + "::BB").hashCode());
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    callback(() -> service.adminUpdateUserAttributes(pool.getId(), "BB",
                            Map.of("name", "updated by PreSignUp")), callbackFailure);
                    return response(Map.of());
                });

        service.signUp(client.getClientId(), "Aa", "Perm1234!", Map.of("phone_number", PHONE));

        assertNull(callbackFailure.get());
        assertEquals("updated by PreSignUp", service.adminGetUser(pool.getId(), "BB").getAttributes().get("name"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signUpDeliveryDetailsDescribeTheSentMessageDespiteCallbackMutation(boolean deletePhone) throws Exception {
        UserPool pool = pool(Map.of("CustomMessage", FUNCTION));
        UserPoolClient client = client(pool);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    callback(() -> {
                        if (deletePhone) {
                            service.adminDeleteUserAttributes(pool.getId(), "alice", List.of("phone_number"));
                        } else {
                            service.adminUpdateUserAttributes(pool.getId(), "alice",
                                    Map.of("phone_number", "+12025550999"));
                        }
                    }, callbackFailure);
                    return response(Map.of());
                });

        CognitoUser result = service.signUp(client.getClientId(), "alice", "Perm1234!", Map.of("phone_number", PHONE));

        assertNull(callbackFailure.get());
        assertEquals(1, sns.getSentMessages().size());
        assertEquals(PHONE, sns.getSentMessages().getFirst().getPhoneNumber());
        assertEquals(Map.of("AttributeName", "phone_number", "DeliveryMedium", "SMS", "Destination", "+*******0123"),
                service.signUpCodeDeliveryDetails(result));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryFailureRollsBackOriginalUserEvenWhenCallbackChangesSub(boolean deleteSub) throws Exception {
        service = serviceWithSns(failingSms());
        UserPool pool = pool(Map.of("CustomMessage", FUNCTION));
        UserPoolClient client = client(pool);
        service.createGroup(pool.getId(), "members", null, null, null);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    callback(() -> {
                        service.adminAddUserToGroup(pool.getId(), "members", "alice");
                        if (deleteSub) {
                            service.adminDeleteUserAttributes(pool.getId(), "alice", List.of("sub"));
                        } else {
                            service.adminUpdateUserAttributes(pool.getId(), "alice", Map.of("sub", "changed"));
                        }
                    }, callbackFailure);
                    return response(Map.of());
                });

        AwsException failure = assertThrows(AwsException.class, () -> service.signUp(client.getClientId(),
                "alice", "Perm1234!", Map.of("phone_number", PHONE)));

        assertEquals("CodeDeliveryFailureException", failure.getErrorCode());
        assertNull(callbackFailure.get());
        assertEquals("UserNotFoundException", assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "alice")).getErrorCode());
        assertTrue(service.getGroup(pool.getId(), "members").getUserNames().isEmpty());
        assertEquals(VerificationCodeException.Kind.NOT_FOUND, assertThrows(VerificationCodeException.class,
                () -> codes.consume(pool.getId(), "alice", VerificationCode.Purpose.SIGNUP_CONFIRMATION,
                        "123456")).getKind());
    }

    @Test
    void failedDeliveryDoesNotDeleteACallbackReplacementOrItsConfirmationCode() throws Exception {
        service = serviceWithSns(failingSms());
        UserPool pool = pool(Map.of("CustomMessage", FUNCTION));
        UserPoolClient client = client(pool);
        AtomicReference<String> replacementCode = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    callback(() -> {
                        service.adminDeleteUser(pool.getId(), "alice");
                        service.adminCreateMigratedUser(pool.getId(), "alice", "Replacement123!",
                                Map.of("phone_number", PHONE, "name", "replacement"), "UNCONFIRMED");
                        codes.invalidatePrevious(pool.getId(), "alice", VerificationCode.Purpose.SIGNUP_CONFIRMATION);
                        replacementCode.set(codes.issue(pool.getId(), "alice",
                                VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24)));
                    }, callbackFailure);
                    return response(Map.of());
                });

        AwsException failure = assertThrows(AwsException.class, () -> service.signUp(client.getClientId(),
                "alice", "Perm1234!", Map.of("phone_number", PHONE)));

        assertEquals("CodeDeliveryFailureException", failure.getErrorCode());
        assertNull(callbackFailure.get());
        assertEquals("replacement", service.adminGetUser(pool.getId(), "alice").getAttributes().get("name"));
        service.confirmSignUp(client.getClientId(), "alice", replacementCode.get());
        assertEquals("CONFIRMED", service.adminGetUser(pool.getId(), "alice").getUserStatus());
    }

    @Test
    void confirmationCapturesTriggerAttributesBeforeConcurrentAdminMutation() throws Exception {
        UserPool pool = pool(Map.of("PostConfirmation", FUNCTION));
        UserPoolClient client = client(pool);
        service.signUp(client.getClientId(), "alice", "Perm1234!",
                Map.of("phone_number", PHONE, "name", "at confirmation"));
        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        String code = sns.getSentMessages().getFirst().getMessage().replaceAll("\\D", "");
        AtomicBoolean captureStarted = new AtomicBoolean();
        CountDownLatch mutationStarted = new CountDownLatch(1);
        AtomicReference<Thread> mutationThread = new AtomicReference<>();
        AtomicReference<Future<?>> mutation = new AtomicReference<>();
        user.setAttributes(new HashMap<>(user.getAttributes()) {
            @Override
            public Set<Map.Entry<String, String>> entrySet() {
                if (captureStarted.compareAndSet(false, true)) {
                    mutation.set(callbackExecutor.submit(() -> {
                        mutationThread.set(Thread.currentThread());
                        mutationStarted.countDown();
                        service.adminUpdateUserAttributes(pool.getId(), "alice", Map.of("name", "after confirmation"));
                    }));
                    try {
                        assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted while coordinating snapshot", exception);
                    }
                    await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(1))
                            .until(() -> mutation.get().isDone()
                                    || mutationThread.get().getState() == Thread.State.BLOCKED);
                }
                return super.entrySet();
            }
        });
        AtomicReference<JsonNode> event = new AtomicReference<>();
        when(lambdaService.invoke(anyString(), eq(FUNCTION), any(byte[].class),
                eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                    event.set(MAPPER.readTree((byte[]) invocation.getArgument(2)));
                    return response(Map.of());
                });

        service.confirmSignUp(client.getClientId(), "alice", code);
        mutation.get().get(5, TimeUnit.SECONDS);

        assertEquals("at confirmation", event.get().path("request").path("userAttributes").path("name").asText());
        assertEquals("after confirmation", service.adminGetUser(pool.getId(), "alice").getAttributes().get("name"));
    }

    private SnsService failingSms() {
        SnsService failing = mock(SnsService.class);
        when(failing.publish(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("SMS unavailable"));
        return failing;
    }

    private UserPool pool(Map<String, Object> lambdaConfig) {
        return service.createUserPool(Map.of("PoolName", "signup-reentrancy",
                "AutoVerifiedAttributes", List.of("phone_number"), "LambdaConfig", lambdaConfig), "us-east-1");
    }

    private UserPoolClient client(UserPool pool) {
        return service.createUserPoolClient(pool.getId(), "client", false, false, List.of(), List.of());
    }

    private void callback(Runnable action, AtomicReference<Throwable> failure) {
        Future<?> task = callbackExecutor.submit(action);
        try {
            task.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            failure.set(exception.getCause());
        } catch (TimeoutException exception) {
            failure.set(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure.set(exception);
        } finally {
            task.cancel(true);
        }
    }

    private static InvokeResult response(Map<String, Object> response) throws JsonProcessingException {
        return new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of("response", response)), null, "request");
    }
}
