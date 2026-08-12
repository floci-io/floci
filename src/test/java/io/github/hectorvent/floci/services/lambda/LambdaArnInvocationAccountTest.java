package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaArnInvocationAccountTest {

    @Test
    void invokeArnResolvesFunctionFromArnAccountOutsideRequestContext() {
        String defaultAccount = "000000000000";
        String targetAccount = "100000000012";
        String region = "ap-south-1";
        String functionName = "samva-api-dev-saatvik";
        String functionArn = "arn:aws:lambda:" + region + ":" + targetAccount
                + ":function:" + functionName;

        AccountAwareStorageBackend<LambdaFunction> backend = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, defaultAccount);
        LambdaFunctionStore store = new LambdaFunctionStore(backend);
        LambdaFunction function = new LambdaFunction();
        function.setAccountId(targetAccount);
        function.setFunctionName(functionName);
        function.setFunctionArn(functionArn);
        function.setVersion("$LATEST");
        backend.putForAccount(targetAccount,
                "lambda::" + region + "::" + functionName + "::$LATEST", function);

        LambdaExecutorService executor = mock(LambdaExecutorService.class);
        InvokeResult executorResult = new InvokeResult();
        when(executor.invoke(eq(function), aryEq("{}".getBytes()), eq(InvocationType.Event)))
                .thenReturn(executorResult);
        LambdaService service = new LambdaService(
                store,
                executor,
                new LambdaConcurrencyLimiter(),
                new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(),
                null,
                new RegionResolver(region, defaultAccount),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        InvokeResult result = service.invokeArn(functionArn, "{}".getBytes(), InvocationType.Event);

        assertEquals("$LATEST", result.getExecutedVersion());
        verify(executor).invoke(eq(function), aryEq("{}".getBytes()), eq(InvocationType.Event));
    }

    @Test
    void invokeArnResolvesVersionAndAliasFromArnAccount() {
        String defaultAccount = "000000000000";
        String targetAccount = "100000000012";
        String region = "ap-south-1";
        String functionName = "versioned-account-function";
        String functionArn = "arn:aws:lambda:" + region + ":" + targetAccount
                + ":function:" + functionName;

        AccountAwareStorageBackend<LambdaFunction> functionBackend = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, defaultAccount);
        LambdaFunctionStore functionStore = new LambdaFunctionStore(functionBackend);
        LambdaFunction version = new LambdaFunction();
        version.setAccountId(targetAccount);
        version.setFunctionName(functionName);
        version.setFunctionArn(functionArn + ":7");
        version.setVersion("7");
        functionBackend.putForAccount(targetAccount,
                "lambda::" + region + "::" + functionName + "::7", version);

        AccountAwareStorageBackend<LambdaAlias> aliasBackend = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, defaultAccount);
        LambdaAliasStore aliasStore = new LambdaAliasStore(aliasBackend);
        LambdaAlias alias = new LambdaAlias();
        alias.setName("live");
        alias.setFunctionName(functionName);
        alias.setFunctionVersion("7");
        alias.setAliasArn(functionArn + ":live");
        aliasBackend.putForAccount(targetAccount,
                "alias::" + region + "::" + functionName + "::live", alias);

        LambdaExecutorService executor = mock(LambdaExecutorService.class);
        when(executor.invoke(eq(version), aryEq("{}".getBytes()), eq(InvocationType.Event)))
                .thenAnswer(ignored -> new InvokeResult());
        LambdaService service = new LambdaService(
                functionStore,
                executor,
                new LambdaConcurrencyLimiter(),
                new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(),
                null,
                new RegionResolver(region, defaultAccount),
                null,
                aliasStore,
                null,
                null,
                null,
                null,
                null,
                null);

        InvokeResult versionResult = service.invokeArn(
                functionArn + ":7", "{}".getBytes(), InvocationType.Event);
        InvokeResult aliasResult = service.invokeArn(
                functionArn + ":live", "{}".getBytes(), InvocationType.Event);

        assertEquals("7", versionResult.getExecutedVersion());
        assertEquals("7", aliasResult.getExecutedVersion());
        verify(executor, org.mockito.Mockito.times(2))
                .invoke(eq(version), aryEq("{}".getBytes()), eq(InvocationType.Event));
    }
}
