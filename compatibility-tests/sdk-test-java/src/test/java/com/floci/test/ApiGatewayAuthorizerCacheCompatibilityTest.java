package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.AuthorizerType;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiGatewayAuthorizerCacheCompatibilityTest {
    private static final String AUTHORIZER_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
            + "arn:aws:lambda:us-east-1:000000000000:function:identity-source-authorizer/invocations";

    @Test
    void cachedRequestAuthorizerRequiresIdentitySourceOnCreateAndUpdate() {
        try (ApiGatewayClient gateway = TestFixtures.apiGatewayClient()) {
            String apiId = gateway.createRestApi(request -> request.name("request-authorizer-identity-sdk")).id();
            try {
                assertThatThrownBy(() -> gateway.createAuthorizer(request -> request.restApiId(apiId)
                        .name("default-caching").type(AuthorizerType.REQUEST).authorizerUri(AUTHORIZER_URI)))
                        .isInstanceOf(BadRequestException.class);
                assertThatThrownBy(() -> gateway.createAuthorizer(request -> request.restApiId(apiId)
                        .name("explicit-caching").type(AuthorizerType.REQUEST).authorizerUri(AUTHORIZER_URI)
                        .authorizerResultTtlInSeconds(300).identitySource(" ")))
                        .isInstanceOf(BadRequestException.class);
                String authorizerId = gateway.createAuthorizer(request -> request.restApiId(apiId)
                        .name("uncached").type(AuthorizerType.REQUEST).authorizerUri(AUTHORIZER_URI).authorizerResultTtlInSeconds(0)).id();
                assertThatThrownBy(() -> gateway.updateAuthorizer(request -> request.restApiId(apiId)
                        .authorizerId(authorizerId).patchOperations(PatchOperation.builder()
                                .op("replace").path("/authorizerResultTtlInSeconds").value("300").build())))
                        .isInstanceOf(BadRequestException.class);
                assertThat(gateway.getAuthorizer(request -> request.restApiId(apiId).authorizerId(authorizerId))
                        .authorizerResultTtlInSeconds()).isZero();
                assertThat(gateway.getAuthorizers(request -> request.restApiId(apiId)).items()).hasSize(1);
            } finally {
                gateway.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

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
