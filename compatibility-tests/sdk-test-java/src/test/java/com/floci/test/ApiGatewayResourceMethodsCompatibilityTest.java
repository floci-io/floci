package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.Method;
import software.amazon.awssdk.services.apigateway.model.Resource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API Gateway embedded resource methods")
class ApiGatewayResourceMethodsCompatibilityTest {

    @Test
    @DisplayName("Collection and individual resource reads embed supported method fields")
    void sdkResourceReadsEmbedMethodConfiguration() {
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            String apiId = client.createRestApi(request -> request
                    .name(TestFixtures.uniqueName("resource-methods"))).id();
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                String resourceId = client.createResource(request -> request.restApiId(apiId)
                        .parentId(rootId).pathPart("widgets")).id();
                client.putMethod(request -> request.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                        .authorizationType("NONE")
                        .requestParameters(Map.of("method.request.header.X-Trace", true))
                        .requestModels(Map.of("application/json", "Empty")));
                client.putMethod(request -> request.restApiId(apiId).resourceId(resourceId).httpMethod("POST")
                        .authorizationType("AWS_IAM").apiKeyRequired(true));
                client.putIntegration(request -> request.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                        .type(IntegrationType.MOCK).timeoutInMillis(9000)
                        .requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
                client.putMethodResponse(request -> request.restApiId(apiId).resourceId(resourceId).httpMethod("GET")
                        .statusCode("200").responseParameters(Map.of("method.response.header.X-Trace", true)));

                List<Resource> resources = client.getResources(request -> request.restApiId(apiId)
                        .embed("methods")).items();
                Resource embedded = resources.stream().filter(resource -> resource.id().equals(resourceId))
                        .findFirst().orElseThrow();
                assertThat(embedded.resourceMethods()).containsOnlyKeys("GET", "POST");
                Method get = embedded.resourceMethods().get("GET");
                assertThat(get.httpMethod()).isEqualTo("GET");
                assertThat(get.authorizationType()).isEqualTo("NONE");
                assertThat(get.requestParameters()).containsEntry("method.request.header.X-Trace", true);
                assertThat(get.requestModels()).containsEntry("application/json", "Empty");
                assertThat(get.methodIntegration().type()).isEqualTo(IntegrationType.MOCK);
                assertThat(get.methodIntegration().timeoutInMillis()).isEqualTo(9000);
                assertThat(get.methodIntegration().requestTemplates())
                        .containsEntry("application/json", "{\"statusCode\":200}");
                assertThat(get.methodResponses().get("200").responseParameters())
                        .containsEntry("method.response.header.X-Trace", true);
                assertThat(embedded.resourceMethods().get("POST").authorizationType()).isEqualTo("AWS_IAM");
                assertThat(embedded.resourceMethods().get("POST").apiKeyRequired()).isTrue();

                assertThat(client.getResource(request -> request.restApiId(apiId).resourceId(resourceId)
                        .embed("methods")).resourceMethods()).isEqualTo(embedded.resourceMethods());
                Resource summary = client.getResources(request -> request.restApiId(apiId)).items().stream()
                        .filter(resource -> resource.id().equals(resourceId)).findFirst().orElseThrow();
                assertThat(summary.resourceMethods()).containsOnlyKeys("GET", "POST");
                assertThat(summary.resourceMethods().get("GET").httpMethod()).isNull();
                assertThat(summary.resourceMethods().get("GET").methodIntegration()).isNull();
                assertThat(client.getResource(request -> request.restApiId(apiId).resourceId(resourceId))
                        .resourceMethods()).isEqualTo(summary.resourceMethods());
                assertThat(client.getMethod(request -> request.restApiId(apiId).resourceId(resourceId)
                        .httpMethod("GET")).methodIntegration().type()).isEqualTo(IntegrationType.MOCK);
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    @DisplayName("Root resource ANY method is listed and disappears after deletion")
    void sdkRootMethodsReflectDeletion() {
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            String apiId = client.createRestApi(request -> request
                    .name(TestFixtures.uniqueName("root-methods"))).id();
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                assertThat(client.getResource(request -> request.restApiId(apiId).resourceId(rootId)
                        .embed("methods")).resourceMethods()).isEmpty();
                client.putMethod(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("ANY")
                        .authorizationType("NONE"));
                assertThat(client.getResources(request -> request.restApiId(apiId).embed("methods"))
                        .items().get(0).resourceMethods().get("ANY").httpMethod()).isEqualTo("ANY");
                client.deleteMethod(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("ANY"));
                assertThat(client.getResources(request -> request.restApiId(apiId).embed("methods"))
                        .items().get(0).resourceMethods()).isEmpty();
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
