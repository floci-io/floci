package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LambdaConcurrencyLimiterTest {

    private LambdaFunction fn(Integer reserved) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:fn");
        fn.setReservedConcurrentExecutions(reserved);
        return fn;
    }

    @Test
    void unsetReserved_isNoop() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaConcurrencyLimiter.Permit p = limiter.acquire(fn(null));
        assertThat(limiter.inflightCount("arn:aws:lambda:us-east-1:000000000000:function:fn")).isZero();
        p.close();
    }

    @Test
    void reservedN_allowsUpToN_thenThrows() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaFunction f = fn(2);
        LambdaConcurrencyLimiter.Permit p1 = limiter.acquire(f);
        LambdaConcurrencyLimiter.Permit p2 = limiter.acquire(f);

        assertThatThrownBy(() -> limiter.acquire(f))
                .isInstanceOfSatisfying(AwsException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("TooManyRequestsException");
                    assertThat(e.getHttpStatus()).isEqualTo(429);
                });

        p1.close();
        LambdaConcurrencyLimiter.Permit p3 = limiter.acquire(f);
        p2.close();
        p3.close();
        assertThat(limiter.inflightCount(f.getFunctionArn())).isZero();
    }

    @Test
    void reservedZero_throwsImmediately() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        assertThatThrownBy(() -> limiter.acquire(fn(0)))
                .isInstanceOf(AwsException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 429);
    }

    @Test
    void reset_clearsInflight() {
        LambdaConcurrencyLimiter limiter = new LambdaConcurrencyLimiter();
        LambdaFunction f = fn(1);
        limiter.acquire(f);
        limiter.reset(f.getFunctionArn());
        assertThat(limiter.inflightCount(f.getFunctionArn())).isZero();
        limiter.acquire(f).close();
    }
}
