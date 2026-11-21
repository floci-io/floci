package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayModelTemplatePersistenceTest {
    private static final String REGION = "us-east-1";

    @Test
    void restTemplateUsesTheLatestPersistedSchemaAfterRestart(@TempDir Path directory) {
        Services first = services(directory);
        String api = first.rest().createRestApi(REGION, Map.of("name", "rest-template")).getId();
        first.rest().createModel(REGION, api, Map.of("name", "Model", "schema", "{\"type\":\"string\"}"));
        String original = first.rest().getModelTemplate(REGION, api, "Model");
        assertEquals(original, services(directory).rest().getModelTemplate(REGION, api, "Model"));
        first.rest().updateModel(REGION, api, "Model",
                List.of(Map.of("op", "replace", "path", "/schema", "value", "{\"type\":\"boolean\"}")));
        Services restarted = services(directory);
        assertNotEquals(original, restarted.rest().getModelTemplate(REGION, api, "Model"));
        restarted.rest().deleteModel(REGION, api, "Model");
        assertEquals(404, assertThrows(AwsException.class,
                () -> services(directory).rest().getModelTemplate(REGION, api, "Model")).getHttpStatus());
    }

    @Test
    void webSocketTemplateUsesTheLatestPersistedSchemaAfterRestart(@TempDir Path directory) {
        Services first = services(directory);
        String api = first.socket().createApi(REGION, Map.of("name", "socket-template", "protocolType", "WEBSOCKET",
                "routeSelectionExpression", "$request.body.action")).getApiId();
        String model = first.socket().createModel(REGION, api,
                Map.of("name", "Model", "schema", "{\"type\":\"string\"}")).getModelId();
        String original = first.socket().getModelTemplate(REGION, api, model);
        assertEquals(original, services(directory).socket().getModelTemplate(REGION, api, model));
        first.socket().updateModel(REGION, api, model, Map.of("schema", "{\"type\":\"boolean\"}"));
        Services restarted = services(directory);
        assertNotEquals(original, restarted.socket().getModelTemplate(REGION, api, model));
        restarted.socket().deleteModel(REGION, api, model);
        assertEquals(404, assertThrows(AwsException.class,
                () -> services(directory).socket().getModelTemplate(REGION, api, model)).getHttpStatus());
    }

    private Services services(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        when(config.defaultAccountId()).thenReturn("000000000000");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        StorageFactory storage = new StorageFactory(config, access);
        RegionResolver regions = new RegionResolver(REGION, "000000000000");
        ApiGatewayV2Service socket = new ApiGatewayV2Service(storage, config, regions);
        ApiGatewayService rest = new ApiGatewayService(storage, config, mock(TlsCertificateManager.class),
                regions, socket, Clock.systemUTC());
        return new Services(rest, socket);
    }

    private record Services(ApiGatewayService rest, ApiGatewayV2Service socket) {}
}
