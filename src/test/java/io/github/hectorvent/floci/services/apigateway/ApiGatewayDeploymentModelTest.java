package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import io.github.hectorvent.floci.services.apigateway.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ApiGatewayDeploymentModelTest {

    private static final String REGION = "us-east-1";
    private static final String ORIGINAL = "{\"type\":\"string\",\"enum\":[\"READY\"]}";
    private static final String UPDATED = "{\"type\":\"string\",\"enum\":[\"DONE\"]}";
    private static final String SNAPSHOTS = "apigateway-deployment-models.json";

    @Test
    void deploymentValidationUsesTheSchemaCapturedForExport(@TempDir Path directory) throws Exception {
        ApiGatewayService service = spy(service(directory));
        String apiId = service.createRestApi(REGION, Map.of("name", "concurrent-model-update")).getId();
        Model original = new Model();
        original.setName("Status");
        original.setSchema(ORIGINAL);
        Model updated = new Model();
        updated.setName("Status");
        updated.setSchema(UPDATED);
        doReturn(List.of(original), List.of(updated)).when(service).getModels(REGION, apiId);

        Deployment deployment = service.createDeployment(REGION, apiId, Map.of());

        assertEquals(ORIGINAL, new ObjectMapper().readTree(deployment.exportSnapshot())
                .path("models").get(0).path("schema").asText());
        assertEquals(Map.of("Status", ORIGINAL),
                service.getDeploymentModelSchemas(REGION, apiId, deployment.id()));
    }

    @Test
    void deploymentModelsSurviveUpdatesDeletionAndRestart(@TempDir Path directory) {
        ApiGatewayService service = service(directory);
        String apiId = service.createRestApi(REGION, Map.of("name", "snapshot")).getId();
        service.createModel(REGION, apiId, Map.of("name", "Status", "schema", ORIGINAL));
        String original = service.createDeployment(REGION, apiId, Map.of()).id();
        service.updateModel(REGION, apiId, "Status",
                List.of(Map.of("op", "replace", "path", "/schema", "value", UPDATED)));

        ApiGatewayService restarted = service(directory);
        assertEquals(UPDATED, restarted.getModel(REGION, apiId, "Status").getSchema());
        assertEquals(Map.of("Status", ORIGINAL), restarted.getDeploymentModelSchemas(REGION, apiId, original));
        String updated = restarted.createDeployment(REGION, apiId, Map.of()).id();
        restarted.deleteModel(REGION, apiId, "Status");
        assertEquals(Map.of("Status", ORIGINAL), restarted.getDeploymentModelSchemas(REGION, apiId, original));
        assertEquals(Map.of("Status", UPDATED), restarted.getDeploymentModelSchemas(REGION, apiId, updated));
    }

    @Test
    void emptySnapshotsDifferFromLegacyDeploymentsWithoutSnapshots(@TempDir Path directory) throws Exception {
        ApiGatewayService service = service(directory);
        String apiId = service.createRestApi(REGION, Map.of("name", "empty")).getId();
        String deploymentId = service.createDeployment(REGION, apiId, Map.of()).id();
        service.createModel(REGION, apiId, Map.of("name", "Status", "schema", ORIGINAL));
        assertTrue(service.getDeploymentModelSchemas(REGION, apiId, deploymentId).isEmpty());

        Files.delete(directory.resolve(SNAPSHOTS));
        ApiGatewayService legacy = service(directory);
        assertEquals(deploymentId, legacy.getDeployment(REGION, apiId, deploymentId).id());
        assertEquals(Map.of("Status", ORIGINAL), legacy.getDeploymentModelSchemas(REGION, apiId, deploymentId));
    }

    @Test
    void deletingDeploymentsAndApisRemovesTheirSnapshots(@TempDir Path directory) throws Exception {
        ApiGatewayService service = service(directory);
        String firstApi = service.createRestApi(REGION, Map.of("name", "first")).getId();
        String secondApi = service.createRestApi(REGION, Map.of("name", "second")).getId();
        String firstDeployment = service.createDeployment(REGION, firstApi, Map.of()).id();
        service.createDeployment(REGION, secondApi, Map.of());

        service.deleteDeployment(REGION, firstApi, firstDeployment);
        assertEquals(1, new ObjectMapper().readTree(directory.resolve(SNAPSHOTS).toFile()).size());
        service.deleteRestApi(REGION, secondApi);
        assertTrue(new ObjectMapper().readTree(directory.resolve(SNAPSHOTS).toFile()).isEmpty());
    }

    private ApiGatewayService service(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn("000000000000");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        return new ApiGatewayService(new StorageFactory(config, access), config,
                mock(TlsCertificateManager.class), new RegionResolver(REGION, "000000000000"));
    }
}
