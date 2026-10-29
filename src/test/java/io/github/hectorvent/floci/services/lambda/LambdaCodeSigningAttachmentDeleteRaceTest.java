package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaCodeSigningAttachmentDeleteRaceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private static final String NAME = "attachment-delete-race";
    private static final String ARN = "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + NAME;
    private static final String CONFIG_ARN = "arn:aws:lambda:" + REGION + ":" + ACCOUNT
            + ":code-signing-config:csc-0123456789abcdef0";

    @Test
    void putCannotRestoreFunctionDeletedWhileMutationWaitsForLock(@TempDir Path codePath) throws Exception {
        assertDeletedFunctionIsNotRestored(codePath, null,
                service -> service.putFunctionCodeSigningConfig(REGION, NAME, CONFIG_ARN));
    }

    @Test
    void detachCannotRestoreFunctionDeletedWhileMutationWaitsForLock(@TempDir Path codePath) throws Exception {
        assertDeletedFunctionIsNotRestored(codePath, CONFIG_ARN,
                service -> service.deleteFunctionCodeSigningConfig(REGION, NAME));
    }

    private void assertDeletedFunctionIsNotRestored(Path codePath, String initialConfigArn,
                                                    Consumer<LambdaService> mutation) throws Exception {
        LambdaFunctionStore store = new LambdaFunctionStore(AccountAwareStorageBackend.inMemory(ACCOUNT));
        LambdaFunction function = new LambdaFunction();
        function.setAccountId(ACCOUNT);
        function.setFunctionName(NAME);
        function.setFunctionArn(ARN);
        function.setVersion("$LATEST");
        function.setCodeSigningConfigArn(initialConfigArn);
        store.saveForAccount(ACCOUNT, REGION, function);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, ACCOUNT));

        ExecutorService workerPool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            Future<?> future;
            synchronized (service.lockForConcurrencyOp(ARN)) {
                future = workerPool.submit(() -> {
                    worker.set(Thread.currentThread());
                    started.countDown();
                    mutation.accept(service);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                awaitBlockedOrDone(worker.get());
                service.deleteFunction(REGION, NAME);
                assertFalse(store.getForAccount(ACCOUNT, REGION, NAME).isPresent());
            }

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            AwsException error = assertInstanceOf(AwsException.class, failure.getCause());
            assertEquals("ResourceNotFoundException", error.getErrorCode());
            assertFalse(store.getForAccount(ACCOUNT, REGION, NAME).isPresent());
        } finally {
            workerPool.shutdownNow();
        }
    }

}
