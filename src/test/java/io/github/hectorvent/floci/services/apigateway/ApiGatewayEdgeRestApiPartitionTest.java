package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.EndpointType;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An edge-optimized REST API exists only in the commercial partition, so it cannot be created or
 * switched to elsewhere, while a regional one works in every partition.
 */
class ApiGatewayEdgeRestApiPartitionTest {

    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
    private ApiGatewayService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory("000000000000")));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudfront().domainSuffix()).thenReturn("cloudfront.net");
        service = new ApiGatewayService(storageFactory, config, mock(TlsCertificateManager.class),
                new RegionResolver("us-east-1", "000000000000"));
    }

    private RestApi create(String region, String type) {
        return service.createRestApi(region, Map.of("name", "edge-test",
                "endpointConfiguration", Map.of("types", List.of(type))));
    }

    private static List<Map<String, String>> switchTo(String from, String to) {
        return List.of(Map.of("op", "replace", "path", "/endpointConfiguration/types/" + from, "value", to));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cn-north-1", "us-gov-west-1", "us-iso-east-1", "eusc-de-east-1"})
    void anEdgeRestApiIsRefusedOutsideTheCommercialPartition(String region) {
        AwsException error = assertThrows(AwsException.class, () -> create(region, "EDGE"));

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertThat(error.getMessage(), containsString("EDGE"));
        assertEquals(List.of(), service.getRestApis(region));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cn-north-1", "us-gov-west-1"})
    void switchingARestApiToEdgeIsRefusedOutsideTheCommercialPartition(String region) {
        RestApi api = create(region, "REGIONAL");

        for (String pathRef : List.of("REGIONAL", "0")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.updateRestApi(region, api.getId(), switchTo(pathRef, "EDGE")));
            assertEquals("BadRequestException", error.getErrorCode());
        }
        assertEquals(List.of(EndpointType.REGIONAL),
                service.getRestApi(region, api.getId()).getEndpointConfiguration().getTypes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1", "us-iso-east-1", "eusc-de-east-1"})
    void aRegionalRestApiIsAcceptedInEveryPartition(String region) {
        RestApi api = create(region, "REGIONAL");

        assertEquals(List.of(EndpointType.REGIONAL), api.getEndpointConfiguration().getTypes());
    }

    @Test
    void anEdgeRestApiIsAcceptedInTheCommercialPartition() {
        RestApi created = create("us-east-1", "EDGE");
        assertEquals(List.of(EndpointType.EDGE), created.getEndpointConfiguration().getTypes());

        RestApi regional = create("us-east-1", "REGIONAL");
        RestApi switched = service.updateRestApi("us-east-1", regional.getId(), switchTo("REGIONAL", "EDGE"));
        assertEquals(List.of(EndpointType.EDGE), switched.getEndpointConfiguration().getTypes());
    }

    /** A move away from EDGE needs nothing the partition lacks, so a stored EDGE API can still leave it. */
    @Test
    void aStoredEdgeRestApiCanMoveToRegionalOutsideTheCommercialPartition() {
        RestApi api = create("us-east-1", "EDGE");
        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<RestApi> apiStore =
                (AccountAwareStorageBackend<RestApi>) stores.get("apigateway-apis.json");
        apiStore.put("cn-north-1::" + api.getId(), api);

        RestApi renamed = service.updateRestApi("cn-north-1", api.getId(),
                List.of(Map.of("op", "replace", "path", "/name", "value", "renamed")));
        assertEquals("renamed", renamed.getName());

        RestApi switched = service.updateRestApi("cn-north-1", api.getId(), switchTo("EDGE", "REGIONAL"));
        assertEquals(List.of(EndpointType.REGIONAL), switched.getEndpointConfiguration().getTypes());
    }
}
