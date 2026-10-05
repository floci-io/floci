package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetExportResponse;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.Method;
import software.amazon.awssdk.services.apigateway.model.Resource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("REST API Gateway stage export")
class ApiGatewayExportCompatibilityTest {
    @ParameterizedTest
    @CsvSource({"oas30,application/json", "swagger,application/json", "oas30,application/yaml", "swagger,application/yaml"})
    @DisplayName("SDK export returns an OpenAPI blob that reimports with method and integration settings")
    void exportAndReimport(String exportType, String accepts) {
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            String apiId = client.createRestApi(request -> request.name(TestFixtures.uniqueName("export"))).id();
            String restoredId = null;
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                client.putMethod(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET")
                        .authorizationType("AWS_IAM").apiKeyRequired(true)
                        .requestParameters(Map.of("method.request.header.X-Trace", true)));
                client.putIntegration(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET")
                        .type(IntegrationType.MOCK).timeoutInMillis(9000)
                        .requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
                client.putMethodResponse(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET")
                        .statusCode("200").responseParameters(Map.of("method.response.header.X-Trace", false)));
                client.putIntegrationResponse(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET")
                        .statusCode("200").responseTemplates(Map.of("application/json", "{\"exported\":true}")));
                client.createDeployment(request -> request.restApiId(apiId).stageName("dev"));
                // Removing the working method must not remove it from the deployed export.
                client.deleteMethod(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET"));
                GetExportResponse exported = client.getExport(request -> request.restApiId(apiId).stageName("dev")
                        .exportType(exportType).accepts(accepts).parameters(Map.of("extensions", "apigateway")));
                assertThat(exported.contentType()).startsWith(accepts);
                assertThat(exported.contentDisposition()).contains("attachment");
                assertThat(exported.body().asUtf8String()).contains("x-amazon-apigateway-integration");
                SdkBytes body = exported.body();
                restoredId = client.importRestApi(request -> request.body(body)).id();
                String importedId = restoredId;
                Resource restored = client.getResources(request -> request.restApiId(importedId).embed("methods"))
                        .items().stream().filter(resource -> resource.path().equals("/")).findFirst().orElseThrow();
                Method get = restored.resourceMethods().get("GET");
                assertThat(get.authorizationType()).isEqualTo("AWS_IAM");
                assertThat(get.apiKeyRequired()).isTrue();
                assertThat(get.requestParameters()).containsEntry("method.request.header.X-Trace", true);
                assertThat(get.methodResponses().get("200").responseParameters()).containsEntry("method.response.header.X-Trace", false);
                assertThat(get.methodIntegration().type()).isEqualTo(IntegrationType.MOCK);
                assertThat(get.methodIntegration().timeoutInMillis()).isEqualTo(9000);
                assertThat(get.methodIntegration().requestTemplates()).containsEntry("application/json", "{\"statusCode\":200}");
                assertThat(get.methodIntegration().integrationResponses().get("200").responseTemplates())
                        .containsEntry("application/json", "{\"exported\":true}");
            } finally {
                if (restoredId != null) {
                    String importedId = restoredId;
                    client.deleteRestApi(request -> request.restApiId(importedId));
                }
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
