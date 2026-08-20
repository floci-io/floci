package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        String functionName = "cross-account-function";
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

    @Test
    void invokeArnMigratesOwnedLegacyFunctionVersionAndAlias() {
        String defaultAccount = "000000000000";
        String targetAccount = "100000000012";
        String region = "ap-south-1";
        String functionName = "legacy-account-function";
        String functionArn = "arn:aws:lambda:" + region + ":" + targetAccount
                + ":function:" + functionName;
        String latestKey = "lambda::" + region + "::" + functionName + "::$LATEST";
        String versionKey = "lambda::" + region + "::" + functionName + "::7";
        String aliasKey = "alias::" + region + "::" + functionName + "::live";

        InMemoryStorage<String, LambdaFunction> rawFunctions = new InMemoryStorage<>();
        LambdaFunction latest = function(functionName, functionArn, "$LATEST");
        LambdaFunction version = function(functionName, functionArn + ":7", "7");
        rawFunctions.put(latestKey, latest);
        rawFunctions.put(versionKey, version);
        LambdaFunctionStore functionStore = new LambdaFunctionStore(
                new AccountAwareStorageBackend<>(rawFunctions, null, defaultAccount));

        InMemoryStorage<String, LambdaAlias> rawAliases = new InMemoryStorage<>();
        LambdaAlias alias = new LambdaAlias();
        alias.setName("live");
        alias.setFunctionName(functionName);
        alias.setFunctionVersion("7");
        alias.setAliasArn(functionArn + ":live");
        rawAliases.put(aliasKey, alias);
        LambdaAliasStore aliasStore = new LambdaAliasStore(
                new AccountAwareStorageBackend<>(rawAliases, null, defaultAccount));

        LambdaExecutorService executor = mock(LambdaExecutorService.class);
        when(executor.invoke(eq(latest), aryEq("{}".getBytes()), eq(InvocationType.Event)))
                .thenAnswer(ignored -> new InvokeResult());
        when(executor.invoke(eq(version), aryEq("{}".getBytes()), eq(InvocationType.Event)))
                .thenAnswer(ignored -> new InvokeResult());
        LambdaService service = service(functionStore, aliasStore, executor, region, defaultAccount);

        service.invokeArn(functionArn, "{}".getBytes(), InvocationType.Event);
        service.invokeArn(functionArn + ":7", "{}".getBytes(), InvocationType.Event);
        service.invokeArn(functionArn + ":live", "{}".getBytes(), InvocationType.Event);

        assertTrue(rawFunctions.get(latestKey).isEmpty());
        assertTrue(rawFunctions.get(versionKey).isEmpty());
        assertEquals(latest, rawFunctions.get(targetAccount + "/" + latestKey).orElseThrow());
        assertEquals(version, rawFunctions.get(targetAccount + "/" + versionKey).orElseThrow());
        assertTrue(rawAliases.get(aliasKey).isEmpty());
        assertEquals(alias, rawAliases.get(targetAccount + "/" + aliasKey).orElseThrow());
    }

    @Test
    void invokeArnDoesNotMigrateForeignLegacyFunctionOrAlias() {
        String defaultAccount = "000000000000";
        String targetAccount = "100000000012";
        String foreignAccount = "200000000034";
        String region = "ap-south-1";
        String functionName = "legacy-account-function";
        String targetArn = "arn:aws:lambda:" + region + ":" + targetAccount
                + ":function:" + functionName;
        String functionKey = "lambda::" + region + "::" + functionName + "::$LATEST";

        InMemoryStorage<String, LambdaFunction> rawFunctions = new InMemoryStorage<>();
        LambdaFunction foreignFunction = function(
                functionName,
                "arn:aws:lambda:" + region + ":" + foreignAccount + ":function:" + functionName,
                "$LATEST");
        rawFunctions.put(functionKey, foreignFunction);
        LambdaFunctionStore functionStore = new LambdaFunctionStore(
                new AccountAwareStorageBackend<>(rawFunctions, null, defaultAccount));

        String aliasKey = "alias::" + region + "::" + functionName + "::live";
        InMemoryStorage<String, LambdaAlias> rawAliases = new InMemoryStorage<>();
        LambdaAlias foreignAlias = new LambdaAlias();
        foreignAlias.setName("live");
        foreignAlias.setFunctionName(functionName);
        foreignAlias.setFunctionVersion("7");
        foreignAlias.setAliasArn(
                "arn:aws:lambda:" + region + ":" + foreignAccount + ":function:" + functionName + ":live");
        rawAliases.put(aliasKey, foreignAlias);
        LambdaAliasStore aliasStore = new LambdaAliasStore(
                new AccountAwareStorageBackend<>(rawAliases, null, defaultAccount));
        LambdaService service = service(
                functionStore, aliasStore, mock(LambdaExecutorService.class), region, defaultAccount);

        assertThrows(AwsException.class,
                () -> service.invokeArn(targetArn, "{}".getBytes(), InvocationType.Event));
        assertThrows(AwsException.class,
                () -> service.invokeArn(targetArn + ":live", "{}".getBytes(), InvocationType.Event));
        assertEquals(foreignFunction, rawFunctions.get(functionKey).orElseThrow());
        assertTrue(rawFunctions.get(targetAccount + "/" + functionKey).isEmpty());
        assertEquals(foreignAlias, rawAliases.get(aliasKey).orElseThrow());
        assertTrue(rawAliases.get(targetAccount + "/" + aliasKey).isEmpty());
    }

    private static LambdaFunction function(String functionName, String functionArn, String version) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(functionName);
        function.setFunctionArn(functionArn);
        function.setVersion(version);
        return function;
    }

    private static LambdaService service(
            LambdaFunctionStore functionStore,
            LambdaAliasStore aliasStore,
            LambdaExecutorService executor,
            String region,
            String defaultAccount) {
        return new LambdaService(
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
                null,
                null);
    }
}
