package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LambdaConcurrencyLimiterTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:000000000000:function:fn";

    private LambdaFunction fn(Integer reserved) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        fn.setFunctionArn(ARN);
        fn.setReservedConcurrentExecutions(reserved);
        return fn;
    }

    @Test
    void unsetReserved_isNoop() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaConcurrencyLimiter.Permit p = limiter.acquire(fn(null));
        assertEquals(0, limiter.inflightCount(ARN));
        p.close();
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
    void reset_clearsInflight() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaFunction f = fn(1);
        limiter.acquire(f);
        limiter.reset(ARN);
        assertEquals(0, limiter.inflightCount(ARN));
        limiter.acquire(f).close();
    }
}
