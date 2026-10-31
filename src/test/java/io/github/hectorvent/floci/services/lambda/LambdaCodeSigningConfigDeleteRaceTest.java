package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.CodeSigningConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class LambdaCodeSigningConfigDeleteRaceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private static final String NAME = "config-delete-race";

    @Test
    void deleteWaitsForValidatedPutAndRejectsAttachedConfig(@TempDir Path codePath) throws Exception {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
        LambdaCodeSigningConfigService configs = spy(new LambdaCodeSigningConfigService(factory, regions));
        CodeSigningConfig.AllowedPublishers publishers = new CodeSigningConfig.AllowedPublishers();
        publishers.setSigningProfileVersionArns(List.of(
                "arn:aws:signer:us-east-1:111111111111:/signing-profiles/prof/AbCdEfGhIj"));
        String configArn = configs.create(REGION, ACCOUNT, null, publishers, null).getCodeSigningConfigArn();

        LambdaFunctionStore functions = new LambdaFunctionStore(AccountAwareStorageBackend.inMemory(ACCOUNT));
        LambdaFunction function = new LambdaFunction();
        function.setAccountId(ACCOUNT);
        function.setFunctionName(NAME);
        function.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + NAME);
        function.setVersion("$LATEST");
        functions.saveForAccount(ACCOUNT, REGION, function);
        LambdaService lambda = new LambdaService(functions, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), regions);
        ObjectMapper mapper = new ObjectMapper();
        LambdaCodeSigningController attachments = new LambdaCodeSigningController(lambda, configs, regions, mapper);
        LambdaCodeSigningConfigFunctionsController configController =
                new LambdaCodeSigningConfigFunctionsController(configs, lambda, regions, mapper);

        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch releasePut = new CountDownLatch(1);
        AtomicReference<Thread> putThread = new AtomicReference<>();
        doAnswer(invocation -> {
            CodeSigningConfig result = (CodeSigningConfig) invocation.callRealMethod();
            if (Thread.currentThread() == putThread.get()) {
                validated.countDown();
                assertTrue(releasePut.await(15, TimeUnit.SECONDS));
            }
            return result;
        }).when(configs).get(REGION, ACCOUNT, configArn);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            ObjectNode body = mapper.createObjectNode().put("CodeSigningConfigArn", configArn);
            Future<Response> put = workers.submit(() -> {
                putThread.set(Thread.currentThread());
                return attachments.putFunctionCodeSigningConfig(null, NAME, body);
            });
            assertTrue(validated.await(5, TimeUnit.SECONDS));

            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<Response> delete = workers.submit(() -> {
                deleteThread.set(Thread.currentThread());
                return configController.deleteCodeSigningConfig(null, configArn);
            });
            awaitBlockedOrDone(deleteThread, delete);
            assertFalse(delete.isDone(), "config deletion must wait for the validated attachment");

            releasePut.countDown();
            assertEquals(200, put.get(5, TimeUnit.SECONDS).getStatus());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> delete.get(5, TimeUnit.SECONDS));
            AwsException conflict = assertInstanceOf(AwsException.class, failure.getCause());
            assertEquals("ResourceConflictException", conflict.getErrorCode());
            assertEquals(409, conflict.getHttpStatus());
            assertEquals(configArn, configs.get(REGION, ACCOUNT, configArn).getCodeSigningConfigArn());
            assertEquals(configArn, functions.getForAccount(ACCOUNT, REGION, NAME)
                    .orElseThrow().getCodeSigningConfigArn());
        } finally {
            releasePut.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void deleteWaitsForValidatedCreateAndRejectsAttachedConfig(@TempDir Path codePath) throws Exception {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
        LambdaCodeSigningConfigService configs = spy(new LambdaCodeSigningConfigService(factory, regions));
        CodeSigningConfig.AllowedPublishers publishers = new CodeSigningConfig.AllowedPublishers();
        publishers.setSigningProfileVersionArns(List.of(
                "arn:aws:signer:us-east-1:111111111111:/signing-profiles/prof/AbCdEfGhIj"));
        String configArn = configs.create(REGION, ACCOUNT, null, publishers, null).getCodeSigningConfigArn();

        LambdaFunctionStore functions = new LambdaFunctionStore(AccountAwareStorageBackend.inMemory(ACCOUNT));
        LambdaService lambda = new LambdaService(functions, null, new LambdaConcurrencyLimiter(),
                new WarmPool(), new CodeStore(codePath), new ZipExtractor(), null, regions, null, null,
                new LambdaTargetResolver(functions, null), null, null, null, null, null, factory,
                null, null, null, null, new ObjectMapper(), configs);
        LambdaCodeSigningConfigFunctionsController configController =
                new LambdaCodeSigningConfigFunctionsController(configs, lambda, regions, new ObjectMapper());

        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        AtomicReference<Thread> createThread = new AtomicReference<>();
        doAnswer(invocation -> {
            CodeSigningConfig result = (CodeSigningConfig) invocation.callRealMethod();
            if (Thread.currentThread() == createThread.get()) {
                validated.countDown();
                assertTrue(releaseCreate.await(15, TimeUnit.SECONDS));
            }
            return result;
        }).when(configs).requireForAttachment(REGION, ACCOUNT, configArn);

        String functionName = "create-config-delete-race";
        Map<String, Object> request = Map.of(
                "FunctionName", functionName,
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::111111111111:role/lambda-role",
                "Handler", "index.handler",
                "CodeSigningConfigArn", configArn);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<LambdaFunction> create = workers.submit(() -> {
                createThread.set(Thread.currentThread());
                return lambda.createFunction(REGION, request);
            });
            assertTrue(validated.await(5, TimeUnit.SECONDS));

            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<Response> delete = workers.submit(() -> {
                deleteThread.set(Thread.currentThread());
                return configController.deleteCodeSigningConfig(null, configArn);
            });
            awaitBlockedOrDone(deleteThread, delete);
            assertFalse(delete.isDone(), "config deletion must wait for the validated create");

            releaseCreate.countDown();
            assertEquals(configArn, create.get(5, TimeUnit.SECONDS).getCodeSigningConfigArn());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> delete.get(5, TimeUnit.SECONDS));
            AwsException conflict = assertInstanceOf(AwsException.class, failure.getCause());
            assertEquals("ResourceConflictException", conflict.getErrorCode());
            assertEquals(409, conflict.getHttpStatus());
            assertEquals(configArn, functions.getForAccount(ACCOUNT, REGION, functionName)
                    .orElseThrow().getCodeSigningConfigArn());
        } finally {
            releaseCreate.countDown();
            workers.shutdownNow();
        }
    }
}
