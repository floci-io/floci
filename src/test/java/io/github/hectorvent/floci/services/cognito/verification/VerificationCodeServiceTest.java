package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationCodeServiceTest {

    private VerificationCodeService service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-05-15T12:00:00Z"));
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new VerificationCodeService(storageFactory, clock);
    }

    @Test
    void issue_generatesSixDigitCode() {
        String code = service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        assertEquals(6, code.length(), "code length");
        assertTrue(code.matches("\\d{6}"), "code is digits");
    }

    @Test
    void consume_validCode_succeeds() {
        String code = service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        assertDoesNotThrow(() -> service.consume("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, code));
    }

    @Test
    void consume_consumedAttributeVerificationCode_throwsExpired() {
        String code = service.issue("pool", "alice",
            VerificationCode.Purpose.EMAIL_ATTRIBUTE_VERIFICATION, Duration.ofHours(24));
        service.consume("pool", "alice",
            VerificationCode.Purpose.EMAIL_ATTRIBUTE_VERIFICATION, code);

        VerificationCodeException ex = assertThrows(VerificationCodeException.class,
            () -> service.consume("pool", "alice",
                VerificationCode.Purpose.EMAIL_ATTRIBUTE_VERIFICATION, code));

        assertEquals(VerificationCodeException.Kind.EXPIRED, ex.getKind());
    }

    @Test
    void consume_consumedSignupOrResetCode_throwsNotFound() {
        // Unlike the attribute-verification purposes, SIGNUP_CONFIRMATION and
        // PASSWORD_RESET delete on consume rather than retaining a tombstone: that
        // reuse behavior was never validated against real Cognito for ConfirmSignUp
        // or ConfirmForgotPassword, so it stays exactly as it was before this change.
        String signupCode = service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        service.consume("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, signupCode);
        VerificationCodeException signupEx = assertThrows(VerificationCodeException.class,
            () -> service.consume("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, signupCode));
        assertEquals(VerificationCodeException.Kind.NOT_FOUND, signupEx.getKind());

        String resetCode = service.issue("pool", "bob",
            VerificationCode.Purpose.PASSWORD_RESET, Duration.ofHours(1));
        service.consume("pool", "bob",
            VerificationCode.Purpose.PASSWORD_RESET, resetCode);
        VerificationCodeException resetEx = assertThrows(VerificationCodeException.class,
            () -> service.consume("pool", "bob",
                VerificationCode.Purpose.PASSWORD_RESET, resetCode));
        assertEquals(VerificationCodeException.Kind.NOT_FOUND, resetEx.getKind());
    }

    @Test
    void issue_afterConsumedCode_succeedsImmediately() {
        String code = service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        service.consume("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, code);

        assertDoesNotThrow(() ->
            service.issue("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24)));
    }

    @Test
    void consume_wrongCode_throwsMismatch() {
        service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        VerificationCodeException ex = assertThrows(VerificationCodeException.class,
            () -> service.consume("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, "000000"));
        assertEquals(VerificationCodeException.Kind.MISMATCH, ex.getKind());
    }

    @Test
    void consume_afterTtl_throwsExpired() {
        String code = service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofMinutes(15));
        clock.advance(Duration.ofMinutes(16));
        VerificationCodeException ex = assertThrows(VerificationCodeException.class,
            () -> service.consume("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, code));
        assertEquals(VerificationCodeException.Kind.EXPIRED, ex.getKind());
    }

    @Test
    void consume_attemptsExhausted_invalidatesCode() {
        String code = service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        for (int i = 0; i < 5; i++) {
            assertThrows(VerificationCodeException.class,
                () -> service.consume("pool", "alice",
                    VerificationCode.Purpose.SIGNUP_CONFIRMATION, "000000"));
        }
        // Even the real code should now fail — store entry was invalidated.
        VerificationCodeException ex = assertThrows(VerificationCodeException.class,
            () -> service.consume("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, code));
        assertEquals(VerificationCodeException.Kind.NOT_FOUND, ex.getKind());
    }

    @Test
    void invalidatePrevious_removesActiveCode() {
        service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        service.invalidatePrevious("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION);
        // Issuing a new code right after invalidate should not hit rate-limit
        assertDoesNotThrow(() ->
            service.issue("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24)));
    }

    @Test
    void issue_twiceWithin30s_throwsRateLimit() {
        service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        VerificationCodeException ex = assertThrows(VerificationCodeException.class,
            () -> service.issue("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24)));
        assertEquals(VerificationCodeException.Kind.RATE_LIMIT, ex.getKind());
    }

    @Test
    void issue_after30s_succeeds() {
        service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        clock.advance(Duration.ofSeconds(31));
        assertDoesNotThrow(() ->
            service.issue("pool", "alice",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24)));
    }

    @Test
    void codesForDifferentPurposes_independent() {
        String signupCode = service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        String resetCode  = service.issue("pool", "alice",
            VerificationCode.Purpose.PASSWORD_RESET, Duration.ofHours(1));
        assertNotEquals(signupCode, resetCode);
        assertDoesNotThrow(() -> service.consume("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, signupCode));
        assertDoesNotThrow(() -> service.consume("pool", "alice",
            VerificationCode.Purpose.PASSWORD_RESET, resetCode));
    }

    @Test
    void invalidateForPool_removesOnlyThatPoolsCodes() {
        service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        String otherCode = service.issue("other", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));

        service.invalidateForPool("pool");

        assertThrows(VerificationCodeException.class, () -> service.consume("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "000000"));
        assertDoesNotThrow(() -> service.consume("other", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, otherCode));
    }

    @Test
    void invalidateForPool_sparesPoolWhoseIdExtendsThisOne() {
        // floci:override-id lets a caller pin an id containing a colon, so "pool:child" is a
        // distinct live pool whose storage keys share the "pool:" prefix of the pool being deleted.
        service.issue("pool", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));
        String childCode = service.issue("pool:child", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, Duration.ofHours(24));

        service.invalidateForPool("pool");

        assertDoesNotThrow(() -> service.consume("pool:child", "alice",
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, childCode),
            "deleting pool must not invalidate a code issued by pool:child");
    }

    /** A code redeems once: of consumes of it at the same moment, one succeeds and the others find it gone. */
    @Test
    void consume_sameCodeConcurrently_redeemsOnce() throws Exception {
        VerificationCodeService slow = new VerificationCodeService(SlowVerificationCodeStore.factory(), clock);
        String code = slow.issue("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, Duration.ofMinutes(5));

        List<VerificationCodeException.Kind> outcomes = consumeAtOnce(slow, Collections.nCopies(8, code));

        assertEquals(1, Collections.frequency(outcomes, null), outcomes.toString());
        assertEquals(7, Collections.frequency(outcomes, VerificationCodeException.Kind.NOT_FOUND),
            outcomes.toString());
    }

    /** Wrong codes at the same moment each use up an attempt, so five of them invalidate the code as five in turn do. */
    @Test
    void consume_wrongCodesConcurrently_countEveryAttempt() throws Exception {
        VerificationCodeService slow = new VerificationCodeService(SlowVerificationCodeStore.factory(), clock);
        String code = slow.issue("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, Duration.ofMinutes(5));

        consumeAtOnce(slow, Collections.nCopies(5, "000000".equals(code) ? "000001" : "000000"));

        VerificationCodeException ex = assertThrows(VerificationCodeException.class,
            () -> slow.consume("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, code));
        assertEquals(VerificationCodeException.Kind.NOT_FOUND, ex.getKind());
    }

    /** A code sent while the one before it is redeemed stays usable: redeeming the old code cannot delete it. */
    @Test
    void issue_whilePreviousCodeIsConsumed_leavesTheNewCodeUsable() throws Exception {
        VerificationCodeService slow = new VerificationCodeService(SlowVerificationCodeStore.factory(), clock);
        String previous = slow.issue("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, Duration.ofMinutes(5));
        clock.advance(Duration.ofSeconds(31));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<VerificationCodeException.Kind> redeemed = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    slow.consume("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, previous);
                    return null;
                } catch (VerificationCodeException e) {
                    return e.getKind();
                }
            });
            Future<String> resent = executor.submit(() -> {
                ready.countDown();
                start.await();
                return slow.issue("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, Duration.ofMinutes(5));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            VerificationCodeException.Kind previousOutcome = redeemed.get(10, TimeUnit.SECONDS);
            String code = resent.get(10, TimeUnit.SECONDS);

            assertTrue(previousOutcome == null || previousOutcome == VerificationCodeException.Kind.MISMATCH,
                "the old code is redeemed, or is wrong once the new one replaced it: " + previousOutcome);
            assertDoesNotThrow(() -> slow.consume("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, code));
        } finally {
            executor.shutdownNow();
        }
    }

    /** Consumes each of {@code codes} as alice's EMAIL_OTP at the same moment; null where one succeeded. */
    private static List<VerificationCodeException.Kind> consumeAtOnce(VerificationCodeService service,
                                                                      List<String> codes) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(codes.size());
        try {
            CountDownLatch ready = new CountDownLatch(codes.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<VerificationCodeException.Kind>> futures = new ArrayList<>();
            for (String code : codes) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.consume("pool", "alice", VerificationCode.Purpose.EMAIL_OTP, code);
                        return null;
                    } catch (VerificationCodeException e) {
                        return e.getKind();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<VerificationCodeException.Kind> outcomes = new ArrayList<>();
            for (Future<VerificationCodeException.Kind> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
