package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lambda function URLs are not available in AWS GovCloud (US) or in the China Regions, so one
 * cannot be created there, while the commercial partition and the partitions no source covers
 * are unchanged.
 */
class LambdaFunctionUrlPartitionTest {

    private static final String ACCOUNT = "000000000000";
    private static final String FUNCTION_NAME = "url-gate-fn";

    private LambdaService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new LambdaService(new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()),
                new WarmPool(), new CodeStore(Path.of("target/test-data/lambda-code")), new ZipExtractor(),
                config, new RegionResolver("us-east-1", ACCOUNT), storageFactory);
    }

    private void createFunction(String region) {
        Map<String, Object> request = new HashMap<>();
        request.put("FunctionName", FUNCTION_NAME);
        request.put("Runtime", "nodejs20.x");
        request.put("Role", "arn:" + AwsRegions.partitionFor(region) + ":iam::" + ACCOUNT + ":role/test-role");
        request.put("Handler", "index.handler");
        service.createFunction(region, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-gov-west-1", "us-gov-east-1", "cn-north-1", "cn-northwest-1"})
    void aFunctionUrlIsRefusedInGovCloudAndChina(String region) {
        createFunction(region);

        AwsException error = assertThrows(AwsException.class,
                () -> service.createFunctionUrlConfig(region, FUNCTION_NAME, null, Map.of("AuthType", "NONE")));

        assertEquals("InvalidParameterValueException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertThat(error.getMessage(),
                containsString("not available in partition " + AwsRegions.partitionFor(region)));
        assertNull(service.getFunction(region, FUNCTION_NAME).getUrlConfig());
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "eu-west-1", "us-iso-east-1", "eusc-de-east-1"})
    void aFunctionUrlIsAcceptedWhereNoSourceRulesItOut(String region) {
        createFunction(region);

        LambdaUrlConfig created =
                service.createFunctionUrlConfig(region, FUNCTION_NAME, null, Map.of("AuthType", "NONE"));

        assertEquals("NONE", created.getAuthType());
        assertThat(created.getFunctionUrl(), containsString(".lambda-url." + region + "."));
        assertEquals(created.getFunctionUrl(),
                service.getFunctionUrlConfig(region, FUNCTION_NAME, null).getFunctionUrl());
    }
}
