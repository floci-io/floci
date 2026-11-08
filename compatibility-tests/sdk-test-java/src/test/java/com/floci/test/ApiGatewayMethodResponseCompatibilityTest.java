package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigateway.model.Resource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API Gateway method responses")
class ApiGatewayMethodResponseCompatibilityTest {

    @Test
    void responseParametersRoundTripThroughTheSdk() {
        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            String apiId = apiGateway.createRestApi(request -> request
                    .name(TestFixtures.uniqueName("method-response"))).id();
            try {
                String rootId = apiGateway.getResources(request -> request.restApiId(apiId))
                        .items().stream().filter(resource -> "/".equals(resource.path()))
                        .findFirst().map(Resource::id).orElseThrow();
                String resourceId = apiGateway.createResource(request -> request
                        .restApiId(apiId).parentId(rootId).pathPart("items")).id();
                apiGateway.putMethod(request -> request.restApiId(apiId)
                        .resourceId(resourceId).httpMethod("GET").authorizationType("NONE"));

                Map<String, Boolean> initial = Map.of("method.response.header.X-First", false);
                assertThat(apiGateway.putMethodResponse(request -> request.restApiId(apiId)
                        .resourceId(resourceId).httpMethod("GET").statusCode("200")
                        .responseParameters(initial)).responseParameters()).isEqualTo(initial);

                Map<String, Boolean> updated = Map.of(
                        "method.response.header.X-First", false,
                        "method.response.header.X-Second", true);
                assertThat(apiGateway.updateMethodResponse(request -> request.restApiId(apiId)
                        .resourceId(resourceId).httpMethod("GET").statusCode("200")
                        .patchOperations(PatchOperation.builder().op("add")
                                .path("/responseParameters/method.response.header.X-Second")
                                .value("true").build())).responseParameters()).isEqualTo(updated);
                assertThat(apiGateway.getMethodResponse(request -> request.restApiId(apiId)
                        .resourceId(resourceId).httpMethod("GET").statusCode("200"))
                        .responseParameters()).isEqualTo(updated);
            } finally {
                apiGateway.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
