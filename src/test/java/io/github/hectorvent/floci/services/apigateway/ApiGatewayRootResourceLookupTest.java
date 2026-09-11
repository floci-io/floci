package io.github.hectorvent.floci.services.apigateway;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2OpenApiImporter;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGatewayRootResourceLookupTest {

    private static final String REGION = "us-east-1";

    private AccountAwareStorageBackend<RestApi> apiStore;
    private AccountAwareStorageBackend<ApiGatewayResource> resourceStore;
    private ApiGatewayService service;

    @BeforeEach
    void setUp() {
        apiStore = spy(AccountAwareStorageBackend.inMemory("000000000000"));
        resourceStore = spy(AccountAwareStorageBackend.inMemory("000000000000"));
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            return switch (fileName) {
                case "apigateway-apis.json" -> apiStore;
                case "apigateway-resources.json" -> resourceStore;
                default -> AccountAwareStorageBackend.inMemory("000000000000");
            };
        });
        service = new ApiGatewayService(storageFactory, mock(EmulatorConfig.class),
                mock(TlsCertificateManager.class));
    }

    @Test
    void newApisResolveTheirRootsWithoutScanningResources() {
        for (int i = 0; i < 3; i++) {
            RestApi api = service.createRestApi(REGION, Map.of("name", "api-" + i));
            assertNotNull(api.getRootResourceId());
        }
        clearInvocations(resourceStore);

        for (RestApi api : service.getRestApis(REGION)) {
            assertEquals(Optional.of(api.getRootResourceId()),
                    service.findRootResourceId(REGION, api.getId()));
        }

        verify(resourceStore, never()).scan(any());
    }

    @Test
    void persistedApisWithoutRootIdsFallBackToTheResourceStore() {
        RestApi api = service.createRestApi(REGION, Map.of("name", "legacy-api"));
        String rootResourceId = api.getRootResourceId();
        api.setRootResourceId(null);
        clearInvocations(resourceStore);

        assertEquals(Optional.of(rootResourceId), service.findRootResourceId(REGION, api.getId()));

        verify(resourceStore).scan(any());
    }

    @Test
    void listRenderingUsesTheRootIdAlreadyCarriedByTheApi() throws Exception {
        RestApi api = new RestApi();
        api.setId("api-id");
        api.setName("direct-root-read");
        api.setRootResourceId("root-id");

        ApiGatewayService mockedService = mock(ApiGatewayService.class);
        when(mockedService.getRestApis(REGION)).thenReturn(List.of(api));
        RegionResolver regionResolver = mock(RegionResolver.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(regionResolver.resolveRegion(headers)).thenReturn(REGION);
        ObjectMapper objectMapper = new ObjectMapper();
        ApiGatewayController controller = new ApiGatewayController(mockedService,
                mock(ApiGatewayV2Service.class), mock(ApiGatewayV2OpenApiImporter.class),
                regionResolver, objectMapper);

        Response response = controller.getRestApis(headers);
        JsonNode body = objectMapper.readTree((String) response.getEntity());

        assertEquals("root-id", body.path("item").get(0).path("rootResourceId").asText());
        verify(mockedService, never()).findRootResourceId(anyString(), anyString());
    }
}
