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
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API Gateway REST VTL")
class ApiGatewayVtlCompatibilityTest {

    private static final String STATUS_200_TEMPLATE = "{\"statusCode\": 200}";

    @Test
    @DisplayName("Parsed map and collection methods work in native REST response templates")
    void responseTemplateMapMethodsWorkInNativeImage() throws Exception {
        String responseTemplate = "#set($parsed = $util.parseJson($input.body))"
                + "#if($parsed.headers)$parsed.headers.isEmpty()|$parsed.headers.size()|"
                + "#foreach($entry in $parsed.headers.entrySet())$entry.getKey()=$entry.getValue()#end|"
                + "$parsed.items.isEmpty()|$parsed.items.size()|#foreach($item in $parsed.items)$item#end#end";

        HttpResponse<String> response = invokeMockIntegration("response-map", STATUS_200_TEMPLATE, "200",
                responseTemplate, request -> request
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"headers\":{\"x-test\":\"yes\"},\"items\":[\"a\",\"b\"]}")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().trim()).isEqualTo("false|1|x-test=yes|false|2|ab");
    }

    @Test
    @DisplayName("The $foreach loop scope works in native REST response templates")
    void foreachScopeWorksInNativeImage() throws Exception {
        String responseTemplate = "#foreach($i in [\"a\",\"b\"])"
                + "$i:$foreach.index:$foreach.count:$foreach.hasNext#if($foreach.hasNext),#end#end";

        HttpResponse<String> response = invokeMockIntegration("foreach", STATUS_200_TEMPLATE, "200",
                responseTemplate, HttpRequest.Builder::GET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().trim()).isEqualTo("a:0:1:true,b:1:2:false");
    }

    @Test
    @DisplayName("Header map get and keySet work in the native image")
    void headerMapMethodsWorkInNativeImage() throws Exception {
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

        HttpResponse<String> response = invokeMockIntegration("headers", requestTemplate, "201",
                null, request -> request
                        .header("Content-Type", "application/json")
                        .header("x-repro-header", "repro-header-value")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    private static HttpResponse<String> invokeMockIntegration(String pathPart, String requestTemplate,
            String statusCode, String responseTemplate, UnaryOperator<HttpRequest.Builder> requestCustomizer)
            throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Floci native-image regression test");

        ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient();
        String apiId = apiGateway.createRestApi(request -> request.name(TestFixtures.uniqueName("apigw-vtl-" + pathPart))).id();

        try {
            URI uri = URI.create(TestFixtures.endpoint() + "/execute-api/" + apiId + "/test/" + pathPart);
            HttpRequest httpRequest = requestCustomizer.apply(HttpRequest.newBuilder(uri)).build();
            String httpMethod = httpRequest.method();

            String rootId = apiGateway.getResources(request -> request.restApiId(apiId)).items().stream()
                    .filter(resource -> "/".equals(resource.path()))
                    .findFirst()
                    .map(Resource::id)
                    .orElseThrow();
            String resourceId = apiGateway.createResource(request -> request
                    .restApiId(apiId)
                    .parentId(rootId)
                    .pathPart(pathPart)).id();

            apiGateway.putMethod(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod(httpMethod)
                    .authorizationType("NONE"));
            apiGateway.putIntegration(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod(httpMethod)
                    .type(IntegrationType.MOCK)
                    .requestTemplates(Map.of("application/json", requestTemplate)));
            apiGateway.putMethodResponse(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod(httpMethod)
                    .statusCode(statusCode));
            apiGateway.putIntegrationResponse(request -> request
                    .restApiId(apiId)
                    .resourceId(resourceId)
                    .httpMethod(httpMethod)
                    .statusCode(statusCode)
                    .responseTemplates(responseTemplate == null
                            ? null
                            : Map.of("application/json", responseTemplate)));

            String deploymentId = apiGateway.createDeployment(request -> request.restApiId(apiId)).id();
            apiGateway.createStage(request -> request
                    .restApiId(apiId)
                    .stageName("test")
                    .deploymentId(deploymentId));

            HttpClient httpClient = TestFixtures.emulatorHttpClient();
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } finally {
            apiGateway.deleteRestApi(request -> request.restApiId(apiId));
            apiGateway.close();
        }
    }
}
