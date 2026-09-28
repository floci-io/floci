package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rotations used to run on an unbounded thread pool, one thread per rotation that could not
 * reuse an idle one. They now share a fixed pool.
 */
class SecretsManagerRotationBoundsTest {

    private static final String REGION = "us-east-1";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotate";

    @Test
    void rotationThreadCountDoesNotScaleWithConcurrentRotations() throws Exception {
        LambdaService mockLambda = Mockito.mock(LambdaService.class);
        AtomicInteger concurrentInvocations = new AtomicInteger();
        AtomicInteger peakConcurrentInvocations = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        Mockito.when(mockLambda.invoke(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(byte[].class), Mockito.any()))
                .thenAnswer(invocation -> {
                    int inFlight = concurrentInvocations.incrementAndGet();
                    peakConcurrentInvocations.updateAndGet(peak -> Math.max(peak, inFlight));
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } finally {
                        concurrentInvocations.decrementAndGet();
                    }
                    InvokeResult ok = new InvokeResult();
                    ok.setStatusCode(200);
                    return ok;
                });

        SecretsManagerService svc = new SecretsManagerService(new InMemoryStorage<String, Secret>(), 30,
                new RegionResolver(REGION, "000000000000"), mockLambda, new ObjectMapper());

        int secretCount = 300;
        for (int i = 0; i < secretCount; i++) {
            String name = "thread-bound-" + i;
            svc.createSecret(name, "v1", null, null, null, null, REGION);
            svc.rotateSecret(name, null, LAMBDA_ARN, null, true, REGION);
        }

        // Let the pool ramp up to its cap and stay there; a run of unchanged readings means every
        // task that is ever going to start running concurrently already has.
        long deadline = System.currentTimeMillis() + 5000;
        int lastSeen = -1;
        int stableReadings = 0;
        while (System.currentTimeMillis() < deadline && stableReadings < 5) {
            int now = peakConcurrentInvocations.get();
            if (now == lastSeen) {
                stableReadings++;
            } else {
                stableReadings = 0;
                lastSeen = now;
            }
            Thread.sleep(20);
        }

        release.countDown();
        svc.shutdown();

        int peak = peakConcurrentInvocations.get();
        assertTrue(peak <= SecretsManagerService.ROTATION_EXECUTOR_POOL_SIZE,
                "rotation ran " + peak + " secrets concurrently out of " + secretCount
                        + " submitted; the rotation executor must be bounded by its pool size of "
                        + SecretsManagerService.ROTATION_EXECUTOR_POOL_SIZE + ", not one thread per secret");
    }
}
