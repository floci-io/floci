package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudFormationServiceExecutorTest {

    @Test
    void operationExecutorRejectsWorkAfterThreadsAndQueueAreFull() throws Exception {
        ThreadPoolExecutor executor = CloudFormationService.newOperationExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < executor.getMaximumPoolSize(); i++) {
                executor.execute(() -> await(release));
            }
            int queueCapacity = executor.getQueue().remainingCapacity();
            for (int i = 0; i < queueCapacity; i++) {
                executor.execute(() -> { });
            }

            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedCloudFormationOperationUsesAwsErrorCode() {
        AwsException exception = CloudFormationService.operationLimitExceeded();

        assertEquals("LimitExceededException", exception.getErrorCode());
    }

    private static void await(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
