package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> new InMemoryStorage<>());
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

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
