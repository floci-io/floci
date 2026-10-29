package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.CodeSigningConfig;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class LambdaCodeSigningConfigUpdateDeleteRaceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";

    @Test
    void deleteWaitsForUpdateAndCannotBeUndoneByStaleSave(@TempDir Path codePath) throws Exception {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
        LambdaCodeSigningConfigService configs = spy(new LambdaCodeSigningConfigService(factory, regions));
        CodeSigningConfig.AllowedPublishers publishers = new CodeSigningConfig.AllowedPublishers();
        publishers.setSigningProfileVersionArns(List.of(
                "arn:aws:signer:us-east-1:111111111111:/signing-profiles/prof/AbCdEfGhIj"));
        String arn = configs.create(REGION, ACCOUNT, null, publishers, null).getCodeSigningConfigArn();

        LambdaFunctionStore functions = new LambdaFunctionStore(AccountAwareStorageBackend.inMemory(ACCOUNT));
        LambdaService lambda = new LambdaService(functions, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), regions);
        ObjectMapper mapper = new ObjectMapper();
        LambdaCodeSigningConfigFunctionsController controller =
                new LambdaCodeSigningConfigFunctionsController(configs, lambda, regions, mapper);

        CountDownLatch readForUpdate = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        AtomicReference<Thread> updateThread = new AtomicReference<>();
        AtomicInteger updateReads = new AtomicInteger();
        doAnswer(invocation -> {
            CodeSigningConfig result = (CodeSigningConfig) invocation.callRealMethod();
            if (Thread.currentThread() == updateThread.get() && updateReads.incrementAndGet() == 2) {
                readForUpdate.countDown();
                assertTrue(releaseUpdate.await(15, TimeUnit.SECONDS));
            }
            return result;
        }).when(configs).get(REGION, arn);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            ObjectNode body = mapper.createObjectNode().put("Description", "updated");
            Future<Response> update = workers.submit(() -> {
                updateThread.set(Thread.currentThread());
                return controller.updateCodeSigningConfig(null, arn, body);
            });
            assertTrue(readForUpdate.await(5, TimeUnit.SECONDS));

            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<Response> delete = workers.submit(() -> {
                deleteThread.set(Thread.currentThread());
                return controller.deleteCodeSigningConfig(null, arn);
            });
            awaitBlockedOrDone(deleteThread, delete);
            assertFalse(delete.isDone(), "delete must wait until the update finishes");

            releaseUpdate.countDown();
            assertEquals(200, update.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(204, delete.get(5, TimeUnit.SECONDS).getStatus());
            AwsException notFound = assertThrows(AwsException.class,
                    () -> configs.get(REGION, ACCOUNT, arn));
            assertEquals("ResourceNotFoundException", notFound.getErrorCode());
        } finally {
            releaseUpdate.countDown();
            workers.shutdownNow();
        }
    }
}
