package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeException;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CognitoAttributeVerificationConcurrencyTest {

    private static final String OLD_EMAIL = "old@example.com";
    private static final String FIRST_EMAIL = "alpha@example.com";
    private static final String SECOND_EMAIL = "beta@example.com";
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String FIRST_CODE = "111111";
    private static final String SECOND_CODE = "222222";

    @Test
    void concurrentUpdatesAreSerializedPerUser() throws Exception {
        VerificationCodeService codes = mock(VerificationCodeService.class);
        CognitoMessageDispatcher dispatcher = mock(CognitoMessageDispatcher.class);
        Harness harness = harness(codes, dispatcher);
        AtomicInteger issues = new AtomicInteger();
        AtomicReference<String> latestCode = new AtomicReference<>();
        Map<String, String> destinationByCode = new java.util.concurrent.ConcurrentHashMap<>();
        CountDownLatch firstIssueEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstIssue = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        when(codes.issue(anyString(), anyString(), any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    int issue = issues.incrementAndGet();
                    if (issue == 1) {
                        firstIssueEntered.countDown();
                        await(releaseFirstIssue);
                    }
                    String code = issue == 1 ? FIRST_CODE : SECOND_CODE;
                    latestCode.set(code);
                    return code;
                });
        doAnswer(invocation -> {
            CognitoUser deliveryUser = invocation.getArgument(1);
            String code = invocation.getArgument(3);
            destinationByCode.put(code, deliveryUser.getAttributes().get("email"));
            return null;
        }).when(dispatcher).dispatch(any(), any(), any(), anyString(), any());
        acceptOnlyLatestCode(codes, latestCode);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> harness.service().updateUserAttributes(
                    harness.accessToken(), Map.of("email", FIRST_EMAIL)));
            assertTrue(firstIssueEntered.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                harness.service().updateUserAttributes(
                        harness.accessToken(), Map.of("email", SECOND_EMAIL));
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            releaseFirstIssue.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            String validCode = latestCode.get();
            harness.service().verifyUserAttribute(harness.accessToken(), "email", validCode);

            assertEquals(SECOND_CODE, validCode);
            assertEquals(SECOND_EMAIL, harness.user().getAttributes().get("email"));
            assertEquals(destinationByCode.get(validCode), harness.user().getAttributes().get("email"),
                    "the valid code must only promote the destination it was sent to");
        } finally {
            releaseFirstIssue.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void resendAndUpdateAreSerializedPerUser() throws Exception {
        VerificationCodeService codes = mock(VerificationCodeService.class);
        CognitoMessageDispatcher dispatcher = mock(CognitoMessageDispatcher.class);
        Harness harness = harness(codes, dispatcher);
        AtomicInteger issues = new AtomicInteger();
        AtomicReference<String> latestCode = new AtomicReference<>();
        CountDownLatch resendIssueEntered = new CountDownLatch(1);
        CountDownLatch releaseResendIssue = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);

        when(codes.issue(anyString(), anyString(), any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    int issue = issues.incrementAndGet();
                    if (issue == 2) {
                        resendIssueEntered.countDown();
                        await(releaseResendIssue);
                    }
                    String code = issue == 1 ? FIRST_CODE : issue == 2 ? SECOND_CODE : "333333";
                    latestCode.set(code);
                    return code;
                });
        acceptOnlyLatestCode(codes, latestCode);

        harness.service().updateUserAttributes(
                harness.accessToken(), Map.of("email", FIRST_EMAIL));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> resend = executor.submit(() -> harness.service()
                    .getUserAttributeVerificationCode(harness.accessToken(), "email"));
            assertTrue(resendIssueEntered.await(5, TimeUnit.SECONDS));

            Future<?> update = executor.submit(() -> {
                updateStarted.countDown();
                harness.service().updateUserAttributes(
                        harness.accessToken(), Map.of("email", SECOND_EMAIL));
            });
            assertTrue(updateStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> update.get(100, TimeUnit.MILLISECONDS));
            releaseResendIssue.countDown();
            Map<String, Object> resendResult = resend.get(5, TimeUnit.SECONDS);
            update.get(5, TimeUnit.SECONDS);

            harness.service().verifyUserAttribute(
                    harness.accessToken(), "email", latestCode.get());

            assertEquals("a***@e***", resendResult.get("Destination"),
                    "the resend must complete against its serialized pending email");
            assertEquals(SECOND_EMAIL, harness.user().getAttributes().get("email"));
        } finally {
            releaseResendIssue.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verifyAndUpdateAreSerializedPerUser() throws Exception {
        VerificationCodeService codes = mock(VerificationCodeService.class);
        CognitoMessageDispatcher dispatcher = mock(CognitoMessageDispatcher.class);
        Harness harness = harness(codes, dispatcher);
        AtomicInteger issues = new AtomicInteger();
        CountDownLatch consumeEntered = new CountDownLatch(1);
        CountDownLatch releaseConsume = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);

        when(codes.issue(anyString(), anyString(), any(), any(Duration.class)))
                .thenAnswer(invocation -> issues.incrementAndGet() == 1 ? FIRST_CODE : SECOND_CODE);
        doAnswer(invocation -> {
            assertEquals(FIRST_CODE, invocation.getArgument(3));
            consumeEntered.countDown();
            await(releaseConsume);
            return null;
        }).when(codes).consume(anyString(), anyString(), any(), anyString());

        harness.service().updateUserAttributes(
                harness.accessToken(), Map.of("email", FIRST_EMAIL));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> verify = executor.submit(() -> harness.service().verifyUserAttribute(
                    harness.accessToken(), "email", FIRST_CODE));
            assertTrue(consumeEntered.await(5, TimeUnit.SECONDS));

            Future<?> update = executor.submit(() -> {
                updateStarted.countDown();
                harness.service().updateUserAttributes(
                        harness.accessToken(), Map.of("email", SECOND_EMAIL));
            });
            assertTrue(updateStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> update.get(100, TimeUnit.MILLISECONDS));
            releaseConsume.countDown();
            verify.get(5, TimeUnit.SECONDS);
            update.get(5, TimeUnit.SECONDS);

            assertEquals(FIRST_EMAIL, harness.user().getAttributes().get("email"));
            assertEquals(SECOND_EMAIL, harness.user().getPendingAttributes().get("email"),
                    "a successful concurrent update must not be lost when verification completes");
        } finally {
            releaseConsume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verificationFinishingAfterAdminUpdateDoesNotOverwriteAdminEmail() throws Exception {
        VerificationCodeService codes = mock(VerificationCodeService.class);
        CognitoMessageDispatcher dispatcher = mock(CognitoMessageDispatcher.class);
        Harness harness = harness(codes, dispatcher);
        CountDownLatch consumeEntered = new CountDownLatch(1);
        CountDownLatch releaseConsume = new CountDownLatch(1);
        CountDownLatch adminStarted = new CountDownLatch(1);

        when(codes.issue(anyString(), anyString(), any(), any(Duration.class)))
                .thenReturn(FIRST_CODE);
        doAnswer(invocation -> {
            assertEquals(FIRST_CODE, invocation.getArgument(3));
            consumeEntered.countDown();
            await(releaseConsume);
            return null;
        }).when(codes).consume(anyString(), anyString(), any(), anyString());
        harness.service().updateUserAttributes(
                harness.accessToken(), Map.of("email", FIRST_EMAIL));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> verify = executor.submit(() -> harness.service().verifyUserAttribute(
                    harness.accessToken(), "email", FIRST_CODE));
            assertTrue(consumeEntered.await(5, TimeUnit.SECONDS));

            Future<?> adminUpdate = executor.submit(() -> {
                adminStarted.countDown();
                harness.service().adminUpdateUserAttributes(
                        harness.poolId(), "alice", Map.of("email", ADMIN_EMAIL));
            });
            assertTrue(adminStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> adminUpdate.get(100, TimeUnit.MILLISECONDS));

            releaseConsume.countDown();
            verify.get(5, TimeUnit.SECONDS);
            adminUpdate.get(5, TimeUnit.SECONDS);

            assertEquals(ADMIN_EMAIL, harness.user().getAttributes().get("email"),
                    "verification finishing later must not overwrite a completed admin update");
            assertNull(harness.user().getPendingAttributes().get("email"));
        } finally {
            releaseConsume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verificationFinishingAfterAdminDeleteDoesNotRestoreDeletedEmail() throws Exception {
        VerificationCodeService codes = mock(VerificationCodeService.class);
        CognitoMessageDispatcher dispatcher = mock(CognitoMessageDispatcher.class);
        Harness harness = harness(codes, dispatcher);
        CountDownLatch consumeEntered = new CountDownLatch(1);
        CountDownLatch releaseConsume = new CountDownLatch(1);
        CountDownLatch adminStarted = new CountDownLatch(1);

        when(codes.issue(anyString(), anyString(), any(), any(Duration.class)))
                .thenReturn(FIRST_CODE);
        doAnswer(invocation -> {
            assertEquals(FIRST_CODE, invocation.getArgument(3));
            consumeEntered.countDown();
            await(releaseConsume);
            return null;
        }).when(codes).consume(anyString(), anyString(), any(), anyString());
        harness.service().updateUserAttributes(
                harness.accessToken(), Map.of("email", FIRST_EMAIL));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> verify = executor.submit(() -> harness.service().verifyUserAttribute(
                    harness.accessToken(), "email", FIRST_CODE));
            assertTrue(consumeEntered.await(5, TimeUnit.SECONDS));

            Future<?> adminDelete = executor.submit(() -> {
                adminStarted.countDown();
                harness.service().adminDeleteUserAttributes(
                        harness.poolId(), "alice", List.of("email"));
            });
            assertTrue(adminStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> adminDelete.get(100, TimeUnit.MILLISECONDS));

            releaseConsume.countDown();
            verify.get(5, TimeUnit.SECONDS);
            adminDelete.get(5, TimeUnit.SECONDS);

            assertNull(harness.user().getAttributes().get("email"),
                    "verification finishing later must not restore a completed admin deletion");
            assertNull(harness.user().getPendingAttributes().get("email"));
        } finally {
            releaseConsume.countDown();
            executor.shutdownNow();
        }
    }

    private static Harness harness(VerificationCodeService codes,
                                   CognitoMessageDispatcher dispatcher) {
        CognitoService service = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "http://localhost:4566",
                new RegionResolver("us-east-1", "000000000000"),
                null,
                codes,
                dispatcher);
        UserPool pool = service.createUserPool(Map.of(
                "PoolName", "ConcurrencyPool",
                "AutoVerifiedAttributes", List.of("email"),
                "UserAttributeUpdateSettings", Map.of(
                        "AttributesRequireVerificationBeforeUpdate", List.of("email"))),
                "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "concurrency-client", false, false, List.of(), List.of());
        service.adminCreateUser(pool.getId(), "alice", Map.of(
                "email", OLD_EMAIL,
                "email_verified", "true"), "TempPass1!");
        service.adminSetUserPassword(pool.getId(), "alice", "Permanent1!", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> authentication = (Map<String, Object>) service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Permanent1!"))
                .get("AuthenticationResult");
        return new Harness(service, pool.getId(), (String) authentication.get("AccessToken"));
    }

    private static void acceptOnlyLatestCode(VerificationCodeService codes,
                                             AtomicReference<String> latestCode) {
        doAnswer(invocation -> {
            String supplied = invocation.getArgument(3);
            if (!supplied.equals(latestCode.get())) {
                throw new VerificationCodeException(
                        VerificationCodeException.Kind.MISMATCH,
                        "Invalid verification code provided, please try again");
            }
            return null;
        }).when(codes).consume(anyString(), anyString(), any(), anyString());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating concurrency test", e);
        }
    }

    private record Harness(CognitoService service, String poolId, String accessToken) {
        CognitoUser user() {
            return service.adminGetUser(poolId, "alice");
        }
    }
}
