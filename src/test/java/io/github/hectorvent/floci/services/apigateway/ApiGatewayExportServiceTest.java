package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiGatewayExportServiceTest {
    private static final String REGION = "us-east-1";
    private AccountAwareStorageBackend<Deployment> deployments;
    private ApiGatewayService service;

    @BeforeEach
    void setup() {
        deployments = AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                "apigateway-deployments.json".equals(invocation.getArgument(1))
                        ? deployments : AccountAwareStorageBackend.inMemory("000000000000"));
        service = new ApiGatewayService(factory, mock(EmulatorConfig.class), mock(TlsCertificateManager.class),
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void legacyDeploymentRequiresRedeploymentRatherThanExportingLiveMethods() {
        String apiId = service.createRestApi(REGION, Map.of("name", "legacy")).getId();
        deployments.put(REGION + "::" + apiId + "::old", new Deployment("old", "legacy", 1));
        service.createStage(REGION, apiId, Map.of("stageName", "dev", "deploymentId", "old"));
        AwsException error = assertThrows(AwsException.class,
                () -> service.exportRestApi(REGION, apiId, "dev", "oas30", null));
        assertTrue(error.getMessage().contains("Redeploy"));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        assertNotNull(service.exportRestApi(REGION, apiId, "dev", "oas30", null));
    }

    @Test
    void generatedSecurityNamesDoNotOverwriteAuthorizers() {
        String apiId = service.createRestApi(REGION, Map.of("name", "scheme-collision")).getId();
        String rootId = service.getResources(REGION, apiId).get(0).getId();
        Authorizer authorizer = service.createAuthorizer(REGION, apiId, Map.of("name", "sigv4", "type", "TOKEN",
                "authorizerUri", "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations"));
        service.putMethod(REGION, apiId, rootId, "GET", Map.of("authorizationType", "CUSTOM", "authorizerId", authorizer.getId()));
        service.putMethod(REGION, apiId, rootId, "POST", Map.of("authorizationType", "AWS_IAM"));
        service.createDeployment(REGION, apiId, Map.of("stageName", "dev"));
        JsonNode exported = service.exportRestApi(REGION, apiId, "dev", "oas30", "apigateway");
        assertTrue(exported.at("/components/securitySchemes/sigv4").has("x-amazon-apigateway-authorizer"));
        assertEquals("awsSigv4", exported.at("/components/securitySchemes/sigv4_1/x-amazon-apigateway-authtype").asText());
        assertTrue(exported.at("/paths/~1/get/security/0").has("sigv4"));
        assertTrue(exported.at("/paths/~1/post/security/0").has("sigv4_1"));
    }
}
