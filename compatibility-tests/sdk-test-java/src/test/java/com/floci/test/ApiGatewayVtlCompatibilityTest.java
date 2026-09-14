package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.Resource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API Gateway REST VTL")
class ApiGatewayVtlCompatibilityTest {

    @Test
    @DisplayName("Header map get and keySet work in the native image")
    void headerMapMethodsWorkInNativeImage() throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Floci native-image regression test");

        ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient();
        String apiId = apiGateway.createRestApi(request -> request.name(TestFixtures.uniqueName("apigw-vtl"))).id();

        try {
            String rootId = apiGateway.getResources(request -> request.restApiId(apiId)).items().stream()
                    .filter(resource -> "/".equals(resource.path()))
                    .findFirst()
                    .map(Resource::id)
                    .orElseThrow();
            String resourceId = apiGateway.createResource(request -> request
                    .restApiId(apiId)
                    .parentId(rootId)
                    .pathPart("headers")).id();

            apiGateway.putMethod(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod("POST")
                    .authorizationType("NONE"));

            String requestTemplate = """
                    #set($headers = $input.params().header)
                    #set($direct = $headers.get('x-repro-header'))
                    #set($status = 500)
                    #foreach($name in $headers.keySet())
                    #if($direct == 'repro-header-value' && $headers.get($name) == 'repro-header-value')
                    #set($status = 201)
                    #end
                    #end
                    {"statusCode": $status}
                    """;
            apiGateway.putIntegration(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod("POST")
                    .type(IntegrationType.MOCK)
                    .requestTemplates(Map.of("application/json", requestTemplate)));
            apiGateway.putMethodResponse(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod("POST")
                    .statusCode("201"));
            apiGateway.putIntegrationResponse(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod("POST")
                    .statusCode("201"));

            String deploymentId = apiGateway.createDeployment(request -> request.restApiId(apiId)).id();
            apiGateway.createStage(request -> request
                    .restApiId(apiId)
                    .stageName("test")
                    .deploymentId(deploymentId));

            HttpClient httpClient = TestFixtures.emulatorHttpClient();
            URI uri = URI.create(TestFixtures.endpoint() + "/execute-api/" + apiId + "/test/headers");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .header("x-repro-header", "repro-header-value")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(201);
        } finally {
            apiGateway.deleteRestApi(request -> request.restApiId(apiId));
            apiGateway.close();
        }
    }
}
