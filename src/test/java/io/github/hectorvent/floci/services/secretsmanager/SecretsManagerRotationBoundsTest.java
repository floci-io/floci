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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rotation churns through a unique secret (and, per rotation, a unique lock key) every time a
 * secret is created, rotated, and deleted. Nothing ever pruned the per-ARN lock, and rotations ran
 * on an unbounded thread pool, so heavy churn grew both without bound. These tests prove both are
 * now capped, independent of how many secrets have been rotated.
 */
class SecretsManagerRotationBoundsTest {

    private static final String REGION = "us-east-1";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotate";

    @Test
    void rotationLockStorageDoesNotGrowWithTheNumberOfSecretsChurnedThrough() {
        int lockCountAfterFewSecrets = rotationLockCountAfterChurn(5);
        int lockCountAfterManySecrets = rotationLockCountAfterChurn(500);

        assertEquals(lockCountAfterFewSecrets, lockCountAfterManySecrets,
                "rotation lock storage grew with the number of secrets churned through "
                        + "create/rotate/delete: " + lockCountAfterFewSecrets
                        + " locks for 5 secrets vs " + lockCountAfterManySecrets + " for 500");
    }

    private int rotationLockCountAfterChurn(int secretCount) {
        SecretsManagerService svc = new SecretsManagerService(new InMemoryStorage<>(), 30);
        for (int i = 0; i < secretCount; i++) {
            String name = "lock-churn-" + secretCount + "-" + i;
            svc.createSecret(name, "v1", null, null, null, null, REGION);
            svc.rotateSecret(name, null, LAMBDA_ARN, null, true, REGION);
            svc.deleteSecret(name, null, true, REGION);
        }
        return svc.rotationLockCount();
    }

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
        assertTrue(peak < secretCount,
                "rotation ran " + peak + " secrets concurrently out of " + secretCount
                        + " submitted; the rotation executor must be bounded, not one thread per secret");
    }
}
