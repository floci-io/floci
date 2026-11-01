package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Code-signing mutations stay in the account selected by the request context. */
class LambdaCodeSigningAccountScopeTest {

    private static final String REGION = "us-east-1";
    private static final String CALLER_ACCOUNT = "111111111111";
    private static final String ARN_ACCOUNT = "222222222222";
    private static final String FUNCTION_NAME = "same-name";
    private static final String CONFIG_ARN =
            "arn:aws:lambda:us-east-1:111111111111:code-signing-config:csc-0123456789abcdef0";

    @Test
    void foreignFullArnCannotMutateSameNamedCallerFunction(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        store.saveForAccount(CALLER_ACCOUNT, REGION, function(CALLER_ACCOUNT));
        store.saveForAccount(ARN_ACCOUNT, REGION, function(ARN_ACCOUNT));
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));

        String foreignArn = "arn:aws:lambda:" + REGION + ":" + ARN_ACCOUNT
                + ":function:" + FUNCTION_NAME;
        AwsException putError = assertThrows(AwsException.class,
                () -> service.putFunctionCodeSigningConfig(REGION, foreignArn, CONFIG_ARN));
        assertEquals("ResourceNotFoundException", putError.getErrorCode());
        assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
        assertNull(store.getForAccount(ARN_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());

        AwsException deleteError = assertThrows(AwsException.class,
                () -> service.deleteFunctionCodeSigningConfig(REGION, foreignArn));
        assertEquals("ResourceNotFoundException", deleteError.getErrorCode());
        assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
        assertNull(store.getForAccount(ARN_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void foreignPartialArnCannotMutateSameNamedCallerFunction(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        store.saveForAccount(CALLER_ACCOUNT, REGION, function(CALLER_ACCOUNT));
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));

        String foreignArn = ARN_ACCOUNT + ":function:" + FUNCTION_NAME;
        AwsException error = assertThrows(AwsException.class,
                () -> service.putFunctionCodeSigningConfig(REGION, foreignArn, CONFIG_ARN));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void namespacedFunctionNameAcceptsAllPartialFormsForAttachmentLifecycle(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        store.saveForAccount(CALLER_ACCOUNT, REGION, function(CALLER_ACCOUNT));
        RegionResolver regions = new RegionResolver(REGION, CALLER_ACCOUNT);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), regions);
        LambdaCodeSigningController controller = new LambdaCodeSigningController(
                service, mock(LambdaCodeSigningConfigService.class), regions, new ObjectMapper());

        List<String> references = List.of(
                REGION + ":" + CALLER_ACCOUNT + ":function:" + FUNCTION_NAME,
                REGION + ":" + CALLER_ACCOUNT + ":" + FUNCTION_NAME,
                REGION + ":function:" + FUNCTION_NAME,
                REGION + ":" + FUNCTION_NAME,
                CALLER_ACCOUNT + ":function:" + FUNCTION_NAME,
                CALLER_ACCOUNT + ":" + FUNCTION_NAME,
                "function:" + FUNCTION_NAME,
                FUNCTION_NAME,
                CALLER_ACCOUNT + ":function:" + FUNCTION_NAME + ":$LATEST");
        for (String reference : references) {
            service.putFunctionCodeSigningConfig(REGION, reference, CONFIG_ARN);
            Response response = controller.getFunctionCodeSigningConfig(null, reference);
            assertEquals(200, response.getStatus(), reference);
            assertEquals(CONFIG_ARN, store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                    .orElseThrow().getCodeSigningConfigArn(), reference);

            service.deleteFunctionCodeSigningConfig(REGION, reference);
            assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                    .orElseThrow().getCodeSigningConfigArn(), reference);
        }
    }

    @Test
    void namespacedFunctionNameAllowsFunctionAsTheFunctionName(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        LambdaFunction function = function(CALLER_ACCOUNT);
        function.setFunctionName("function");
        function.setFunctionArn("arn:aws:lambda:" + REGION + ":" + CALLER_ACCOUNT
                + ":function:function");
        store.saveForAccount(CALLER_ACCOUNT, REGION, function);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));

        service.putFunctionCodeSigningConfig(REGION,
                REGION + ":" + CALLER_ACCOUNT + ":function", CONFIG_ARN);
        assertEquals(CONFIG_ARN, store.getForAccount(CALLER_ACCOUNT, REGION, "function")
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void namespacedFunctionNameKeepsPrefixShapedBareNames(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));
        for (String name : List.of(REGION, CALLER_ACCOUNT, "function")) {
            LambdaFunction function = function(CALLER_ACCOUNT);
            function.setFunctionName(name);
            function.setFunctionArn("arn:aws:lambda:" + REGION + ":" + CALLER_ACCOUNT + ":function:" + name);
            store.saveForAccount(CALLER_ACCOUNT, REGION, function);
            service.putFunctionCodeSigningConfig(REGION, name, CONFIG_ARN);
            assertEquals(CONFIG_ARN, service.getFunctionForCodeSigningConfig(REGION, name + ":$LATEST")
                    .getCodeSigningConfigArn(), name);
            service.deleteFunctionCodeSigningConfig(REGION, name + ":$LATEST");
            assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, name)
                    .orElseThrow().getCodeSigningConfigArn(), name);
        }
    }

    @Test
    void foreignPartitionArnCannotSelectCallerFunction(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        store.saveForAccount(CALLER_ACCOUNT, REGION, function(CALLER_ACCOUNT));
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));
        String foreignArn = "arn:aws-us-gov:lambda:" + REGION + ":" + CALLER_ACCOUNT
                + ":function:" + FUNCTION_NAME;

        AwsException error = assertThrows(AwsException.class,
                () -> service.putFunctionCodeSigningConfig(REGION, foreignArn, CONFIG_ARN));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void unpublishedRegionFunctionArnUsesDeploymentPartition(@TempDir Path codePath) {
        String region = "xx-nowhere-9";
        String functionArn = "arn:aws-cn:lambda:" + region + ":" + CALLER_ACCOUNT
                + ":function:" + FUNCTION_NAME;
        String configArn = "arn:aws-cn:lambda:" + region + ":" + CALLER_ACCOUNT
                + ":code-signing-config:csc-0123456789abcdef0";
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        LambdaFunction function = function(CALLER_ACCOUNT);
        function.setFunctionArn(functionArn);
        store.saveForAccount(CALLER_ACCOUNT, region, function);
        RegionResolver regions = new RegionResolver("cn-north-1", CALLER_ACCOUNT);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), regions);

        service.putFunctionCodeSigningConfig(region, functionArn, configArn);
        assertEquals(configArn, service.getFunctionForCodeSigningConfig(region, functionArn)
                .getCodeSigningConfigArn());

        String foreignArn = functionArn.replace("arn:aws-cn:", "arn:aws:");
        AwsException error = assertThrows(AwsException.class,
                () -> service.putFunctionCodeSigningConfig(region, foreignArn, configArn));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(configArn, store.getForAccount(CALLER_ACCOUNT, region, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());

        service.deleteFunctionCodeSigningConfig(region, functionArn);
        assertNull(store.getForAccount(CALLER_ACCOUNT, region, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void foreignPartialRegionAndAccountReferencesCannotMutateCallerFunction(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        store.saveForAccount(CALLER_ACCOUNT, REGION, function(CALLER_ACCOUNT));
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));

        List<String> references = List.of(
                "us-west-2:" + CALLER_ACCOUNT + ":function:" + FUNCTION_NAME,
                "us-west-2:" + CALLER_ACCOUNT + ":" + FUNCTION_NAME,
                "us-west-2:function:" + FUNCTION_NAME,
                "us-west-2:" + FUNCTION_NAME,
                ARN_ACCOUNT + ":function:" + FUNCTION_NAME,
                ARN_ACCOUNT + ":" + FUNCTION_NAME,
                REGION + ":" + ARN_ACCOUNT + ":function:" + FUNCTION_NAME,
                REGION + ":" + ARN_ACCOUNT + ":" + FUNCTION_NAME);
        for (String reference : references) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.putFunctionCodeSigningConfig(REGION, reference, CONFIG_ARN), reference);
            assertEquals("ResourceNotFoundException", error.getErrorCode(), reference);
            assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                    .orElseThrow().getCodeSigningConfigArn(), reference);
        }
    }

    @Test
    void foreignFullArnCannotReadCodeSigningConfigThroughController(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        LambdaFunction callerFunction = function(CALLER_ACCOUNT);
        callerFunction.setCodeSigningConfigArn(CONFIG_ARN);
        store.saveForAccount(CALLER_ACCOUNT, REGION, callerFunction);
        RegionResolver regions = new RegionResolver(REGION, CALLER_ACCOUNT);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), regions);
        ObjectMapper mapper = new ObjectMapper();
        LambdaCodeSigningController controller = new LambdaCodeSigningController(
                service, mock(LambdaCodeSigningConfigService.class), regions, mapper);
        String foreignArn = "arn:aws:lambda:" + REGION + ":" + ARN_ACCOUNT
                + ":function:" + FUNCTION_NAME;

        AwsException error = assertThrows(AwsException.class,
                () -> controller.getFunctionCodeSigningConfig(null, foreignArn));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void foreignRegionArnCannotMutateCallerFunction(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        store.saveForAccount(CALLER_ACCOUNT, REGION, function(CALLER_ACCOUNT));
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));

        String foreignArn = "arn:aws:lambda:us-west-2:" + CALLER_ACCOUNT
                + ":function:" + FUNCTION_NAME;
        AwsException error = assertThrows(AwsException.class,
                () -> service.putFunctionCodeSigningConfig(REGION, foreignArn, CONFIG_ARN));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertNull(store.getForAccount(CALLER_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void namespacedFunctionNameAcceptsQualifiedAndDottedReferences(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        LambdaFunction function = function(CALLER_ACCOUNT);
        function.setFunctionName("same.name");
        function.setFunctionArn("arn:aws:lambda:" + REGION + ":" + CALLER_ACCOUNT
                + ":function:same.name");
        store.saveForAccount(CALLER_ACCOUNT, REGION, function);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));

        service.putFunctionCodeSigningConfig(REGION, "same.name:$LATEST", CONFIG_ARN);
        assertEquals(CONFIG_ARN, store.getForAccount(CALLER_ACCOUNT, REGION, "same.name")
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void foreignArnCannotSelectAFunctionAbsentFromTheCallerAccount(@TempDir Path codePath) {
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.inMemory(CALLER_ACCOUNT));
        store.saveForAccount(ARN_ACCOUNT, REGION, function(ARN_ACCOUNT));
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));
        String foreignArn = "arn:aws:lambda:" + REGION + ":" + ARN_ACCOUNT
                + ":function:" + FUNCTION_NAME;

        AwsException error = assertThrows(AwsException.class,
                () -> service.putFunctionCodeSigningConfig(REGION, foreignArn, CONFIG_ARN));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertNull(store.getForAccount(ARN_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
    }

    @Test
    void legacyArnOwnedFunctionIsWrittenBackToTheVerifiedAccount(@TempDir Path codePath) {
        InMemoryStorage<String, LambdaFunction> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<LambdaFunction> backend =
                new AccountAwareStorageBackend<>(raw, null, ARN_ACCOUNT);
        LambdaFunctionStore store = new LambdaFunctionStore(backend);
        LambdaFunction legacy = function(ARN_ACCOUNT);
        legacy.setAccountId(null);
        raw.put("lambda::" + REGION + "::" + FUNCTION_NAME + "::$LATEST", legacy);
        LambdaService service = new LambdaService(store, new WarmPool(), new CodeStore(codePath),
                new ZipExtractor(), new RegionResolver(REGION, ARN_ACCOUNT));

        service.putFunctionCodeSigningConfig(REGION, FUNCTION_NAME, CONFIG_ARN);

        assertEquals(CONFIG_ARN, store.getForAccount(ARN_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getCodeSigningConfigArn());
        assertEquals(ARN_ACCOUNT, store.getForAccount(ARN_ACCOUNT, REGION, FUNCTION_NAME)
                .orElseThrow().getAccountId());
        assertTrue(store.getForAccount("000000000000", REGION, FUNCTION_NAME).isEmpty());
    }

    private static LambdaFunction function(String account) {
        LambdaFunction function = new LambdaFunction();
        function.setAccountId(account);
        function.setFunctionName(FUNCTION_NAME);
        function.setVersion("$LATEST");
        function.setFunctionArn("arn:aws:lambda:" + REGION + ":" + account
                + ":function:" + FUNCTION_NAME);
        return function;
    }
}
