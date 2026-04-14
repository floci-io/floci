package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LambdaConcurrencyLimiterTest {

    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "ap-northeast-1";
    private static final String ARN = "arn:aws:lambda:us-east-1:000000000000:function:fn";
    private static final String ARN2 = "arn:aws:lambda:us-east-1:000000000000:function:other";
    private static final String ARN_OTHER_REGION = "arn:aws:lambda:ap-northeast-1:000000000000:function:fn-apne1";

    private LambdaFunction fn(String arn, Integer reserved) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        fn.setFunctionArn(arn);
        fn.setReservedConcurrentExecutions(reserved);
        return fn;
    }

    private LambdaFunction fn(Integer reserved) {
        return fn(ARN, reserved);
    }

    @Test
    void unsetReserved_countsAgainstAccountPool() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(2, 0);
        LambdaConcurrencyLimiter.Permit p1 = limiter.acquire(fn(null));
        LambdaConcurrencyLimiter.Permit p2 = limiter.acquire(fn(null));
        AwsException ex = assertThrows(AwsException.class, () -> limiter.acquire(fn(null)));
        assertEquals(429, ex.getHttpStatus());
        p1.close();
        p2.close();
        assertEquals(0, limiter.unreservedInflightCount(REGION));
    }

    @Test
    void reservedN_allowsUpToN_thenThrows() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaFunction f = fn(2);
        LambdaConcurrencyLimiter.Permit p1 = limiter.acquire(f);
        LambdaConcurrencyLimiter.Permit p2 = limiter.acquire(f);

        AwsException ex = assertThrows(AwsException.class, () -> limiter.acquire(f));
        assertEquals("TooManyRequestsException", ex.getErrorCode());
        assertEquals(429, ex.getHttpStatus());

        p1.close();
        LambdaConcurrencyLimiter.Permit p3 = limiter.acquire(f);
        p2.close();
        p3.close();
        assertEquals(0, limiter.inflightCount(ARN));
    }

    @Test
    void reservedZero_throwsImmediately() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        AwsException ex = assertThrows(AwsException.class, () -> limiter.acquire(fn(0)));
        assertEquals(429, ex.getHttpStatus());
    }

    @Test
    void reservedPool_doesNotConsumeUnreserved() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(3, 0);
        limiter.setReserved(ARN, 2);
        // Reserved function consumes its own pool, not the region pool
        try (LambdaConcurrencyLimiter.Permit p1 = limiter.acquire(fn(ARN, 2));
             LambdaConcurrencyLimiter.Permit p2 = limiter.acquire(fn(ARN, 2));
             // Unreserved function can still use the full regionLimit - totalReserved = 1
             LambdaConcurrencyLimiter.Permit p3 = limiter.acquire(fn(ARN2, null))) {
            AwsException ex = assertThrows(AwsException.class, () -> limiter.acquire(fn(ARN2, null)));
            assertEquals(429, ex.getHttpStatus());
        }
    }

    @Test
    void reset_preservesInflightCounterWhenBusy() {
        // If a function is deleted while invocations are still running, and the
        // same ARN is recreated, new invocations must see the remaining inflight
        // so we don't transiently over-subscribe.
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaFunction before = fn(3);
        LambdaConcurrencyLimiter.Permit held = limiter.acquire(before);
        assertEquals(1, limiter.inflightCount(ARN));

        limiter.reset(ARN);
        assertEquals(1, limiter.inflightCount(ARN), "inflight retained while permit is open");

        LambdaFunction recreated = fn(3);
        try (LambdaConcurrencyLimiter.Permit p2 = limiter.acquire(recreated)) {
            assertEquals(2, limiter.inflightCount(ARN));
        }
        held.close();
        assertEquals(0, limiter.inflightCount(ARN));
    }

    @Test
    void reset_keepsIdleInflightCounterToAvoidUndercount() {
        // The counter is intentionally retained across reset to close the
        // window where a concurrent acquire could otherwise allocate a fresh
        // counter and undercount inflight permits already in flight.
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        try (LambdaConcurrencyLimiter.Permit p = limiter.acquire(fn(1))) {
            assertEquals(1, limiter.inflightCount(ARN));
        }
        assertEquals(0, limiter.inflightCount(ARN));
        limiter.reset(ARN);
        // Counter still present at zero; new acquires share it.
        assertEquals(0, limiter.inflightCount(ARN));
        try (LambdaConcurrencyLimiter.Permit p = limiter.acquire(fn(1))) {
            assertEquals(1, limiter.inflightCount(ARN));
        }
    }

    @Test
    void validateAndSetReserved_allowsReductionEvenWhenOverCommitted() {
        // Simulate an over-committed state: total reserved exceeds the
        // unreserved minimum's ceiling. (e.g. region-concurrency-limit was
        // lowered at runtime, or state was migrated from earlier behavior.)
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(1000, 100);
        // Bypass validation by using setReserved directly so we can engineer
        // the broken state that validateAndSetReserved should still let us recover from.
        limiter.setReserved(ARN, 950);
        // Now totalReserved=950, unreserved capacity = 50 < min(100). Any
        // increase should still be blocked.
        assertThrows(AwsException.class, () -> limiter.validateAndSetReserved(ARN, 951));
        // But a reduction must succeed so the operator can recover.
        assertDoesNotThrow(() -> limiter.validateAndSetReserved(ARN, 500));
        assertEquals(500, limiter.totalReserved(REGION));
    }

    @Test
    void validatePut_rejectsWhenUnreservedMinViolated() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(1000, 100);
        // totalReserved=0, max allowed for new function = 1000 - 100 = 900
        assertDoesNotThrow(() -> limiter.validateAndSetReserved(ARN, 900));
        AwsException ex = assertThrows(AwsException.class, () -> limiter.validateAndSetReserved(ARN, 901));
        assertEquals("LimitExceededException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void validatePut_excludesSelfWhenUpdating() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(1000, 100);
        limiter.setReserved(ARN, 500);
        // Updating the same ARN to 900 should succeed (self is excluded from "other")
        assertDoesNotThrow(() -> limiter.validateAndSetReserved(ARN, 900));
    }

    @Test
    void validatePut_considersOtherFunctions() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(1000, 100);
        limiter.setReserved(ARN2, 500);
        // otherReserved=500, max for ARN = 1000 - 100 - 500 = 400
        assertDoesNotThrow(() -> limiter.validateAndSetReserved(ARN, 400));
        assertThrows(AwsException.class, () -> limiter.validateAndSetReserved(ARN, 401));
    }

    @Test
    void reset_clearsReservedEntry() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        limiter.setReserved(ARN, 1);
        limiter.reset(ARN);
        assertEquals(0, limiter.totalReserved(REGION));
    }

    @Test
    void setReserved_returnsPreviousValue() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        assertNull(limiter.setReserved(ARN, 5));
        assertEquals(5, limiter.setReserved(ARN, 10));
    }

    @Test
    void clearReserved_returnsClearedValue() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        limiter.setReserved(ARN, 7);
        assertEquals(7, limiter.clearReserved(ARN));
        assertNull(limiter.clearReserved(ARN));
    }

    @Test
    void rollbackReservedIfExpected_restoresWhenUnchanged() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        limiter.setReserved(ARN, 5);
        limiter.setReserved(ARN, 10); // now at 10
        limiter.rollbackReservedIfExpected(ARN, 10, 5);
        assertEquals(5, limiter.totalReserved(REGION));
    }

    @Test
    void rollbackReservedIfExpected_skipsWhenConcurrentlyChanged() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        // Request A wrote 10 (previous null), then another request superseded to 20
        limiter.setReserved(ARN, 10);
        limiter.setReserved(ARN, 20);
        // A's rollback expects 10 still present — must not clobber 20
        limiter.rollbackReservedIfExpected(ARN, 10, null);
        assertEquals(20, limiter.totalReserved(REGION));
    }

    @Test
    void totalReserved_tracksOverlappingUpdates() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        limiter.setReserved(ARN, 50);
        limiter.setReserved(ARN2, 30);
        assertEquals(80, limiter.totalReserved(REGION));
        limiter.setReserved(ARN, 100); // +50
        assertEquals(130, limiter.totalReserved(REGION));
        limiter.clearReserved(ARN2); // -30
        assertEquals(100, limiter.totalReserved(REGION));
    }

    @Test
    void permit_closeIsIdempotent() {
        // Future callers must not be able to drive the inflight counter
        // negative by double-closing a permit (try-with-resources +
        // explicit close, retry logic, etc.).
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaConcurrencyLimiter.Permit p = limiter.acquire(fn(3));
        assertEquals(1, limiter.inflightCount(ARN));
        p.close();
        assertEquals(0, limiter.inflightCount(ARN));
        p.close(); // second close must be a no-op
        assertEquals(0, limiter.inflightCount(ARN));
        p.close(); // ...and so must the third
        assertEquals(0, limiter.inflightCount(ARN));
    }

    @Test
    void regions_areIndependent() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter(1000, 100);
        // Fill one region's reserved pool near the limit
        limiter.validateAndSetReserved(ARN, 900);
        // Another region starts fresh — Put up to 900 still allowed
        assertDoesNotThrow(() -> limiter.validateAndSetReserved(ARN_OTHER_REGION, 900));
        assertEquals(900, limiter.totalReserved(REGION));
        assertEquals(900, limiter.totalReserved(OTHER_REGION));

        // Unreserved pool is also per-region
        LambdaConcurrencyLimiter small = new LambdaConcurrencyLimiter(1, 0);
        try (LambdaConcurrencyLimiter.Permit usEast = small.acquire(fn(ARN, null));
             LambdaConcurrencyLimiter.Permit apne1 = small.acquire(fn(ARN_OTHER_REGION, null))) {
            // Same exhaustion in us-east-1, but ap-northeast-1 has its own slot
            // (already consumed by apne1 above, so second acquire there also throws)
            assertThrows(AwsException.class, () -> small.acquire(fn(ARN2, null)));
            assertThrows(AwsException.class, () -> small.acquire(fn(ARN_OTHER_REGION, null)));
        }
        // After close, both regions have capacity again
        try (LambdaConcurrencyLimiter.Permit reacquire = small.acquire(fn(ARN, null))) {
            assertEquals(1, small.unreservedInflightCount(REGION));
        }
    }
}
