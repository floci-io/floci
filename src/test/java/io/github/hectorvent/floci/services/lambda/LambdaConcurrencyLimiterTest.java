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
        limiter.acquire(fn(ARN, 2));
        limiter.acquire(fn(ARN, 2));
        // Unreserved function can still use the full regionLimit - totalReserved = 1
        limiter.acquire(fn(ARN2, null));
        AwsException ex = assertThrows(AwsException.class, () -> limiter.acquire(fn(ARN2, null)));
        assertEquals(429, ex.getHttpStatus());
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
    void reset_clearsInflightAndReserved() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaFunction f = fn(1);
        limiter.setReserved(ARN, 1);
        limiter.acquire(f);
        limiter.reset(ARN);
        assertEquals(0, limiter.inflightCount(ARN));
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
        small.acquire(fn(ARN, null));
        // Same exhaustion in us-east-1, but ap-northeast-1 still has a slot
        assertThrows(AwsException.class, () -> small.acquire(fn(ARN2, null)));
        assertDoesNotThrow(() -> small.acquire(fn(ARN_OTHER_REGION, null)));
    }
}
