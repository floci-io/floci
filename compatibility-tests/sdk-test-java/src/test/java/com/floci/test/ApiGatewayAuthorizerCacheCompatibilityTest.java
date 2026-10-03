package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiGatewayAuthorizerCacheCompatibilityTest {
    @Test
    void flushStageAuthorizersCacheUsesSdkWireProtocol() {
        try (ApiGatewayClient gateway = TestFixtures.apiGatewayClient()) {
            String apiId = gateway.createRestApi(request -> request.name("authorizer-cache-sdk")).id();
            try {
                String rootId = gateway.getResources(request -> request.restApiId(apiId)).items().stream()
                        .filter(resource -> "/".equals(resource.path())).findFirst().orElseThrow().id();
                gateway.putMethod(request -> request.restApiId(apiId).resourceId(rootId)
                        .httpMethod("GET").authorizationType("NONE"));
                gateway.putIntegration(request -> request.restApiId(apiId).resourceId(rootId)
                        .httpMethod("GET").type(IntegrationType.MOCK)
                        .requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
                gateway.createDeployment(request -> request.restApiId(apiId).stageName("test"));
                assertThat(gateway.flushStageAuthorizersCache(request -> request.restApiId(apiId).stageName("test"))
                        .sdkHttpResponse().statusCode()).isEqualTo(202);
                assertThatThrownBy(() -> gateway.flushStageAuthorizersCache(request -> request.restApiId(apiId).stageName("missing")))
                        .isInstanceOf(NotFoundException.class);
            } finally {
                gateway.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
