package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.AuthorizerType;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetExportResponse;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.Method;
import software.amazon.awssdk.services.apigateway.model.Resource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                client.createModel(request -> request.restApiId(apiId).name("Widget").contentType("application/json")
                        .schema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"));
                client.putMethod(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET")
                        .authorizationType("AWS_IAM").apiKeyRequired(true)
                        .requestModels(Map.of("application/json", "Widget"))
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
                assertThat(get.requestModels()).containsEntry("application/json", "Widget");
                assertThat(client.getModel(request -> request.restApiId(importedId).modelName("Widget")).schema())
                        .contains("name", "string");
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
    @ParameterizedTest
    @CsvSource({"TOKEN,oas30", "TOKEN,swagger", "COGNITO_USER_POOLS,oas30", "COGNITO_USER_POOLS,swagger"})
    @DisplayName("Unused authorizers do not block default export re-import")
    void unusedAuthorizerDoesNotProtectPublicMethods(String kind, String format) {
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            String apiId = client.createRestApi(request -> request.name(TestFixtures.uniqueName("unused-authorizer"))).id();
            String restoredId = null;
            try {
                client.createAuthorizer(request -> {
                    request.restApiId(apiId).name("Unused").type(AuthorizerType.fromValue(kind))
                            .identitySource("method.request.header.Authorization");
                    if ("TOKEN".equals(kind)) {
                        request.authorizerUri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations");
                    } else {
                        request.providerARNs("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_fixture");
                    }
                });
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                client.putMethod(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET")
                        .authorizationType("NONE"));
                client.createDeployment(request -> request.restApiId(apiId).stageName("dev"));
                SdkBytes document = client.getExport(request -> request.restApiId(apiId).stageName("dev")
                        .exportType(format).accepts("application/json")).body();
                restoredId = client.importRestApi(request -> request.body(document)).id();
                String importedId = restoredId;
                Resource restored = client.getResources(request -> request.restApiId(importedId).embed("methods"))
                        .items().stream().filter(resource -> resource.path().equals("/")).findFirst().orElseThrow();
                assertThat(restored.resourceMethods().get("GET").authorizationType()).isEqualTo("NONE");
                assertThat(restored.resourceMethods().get("GET").apiKeyRequired()).isFalse();
            } finally {
                if (restoredId != null) {
                    String importedId = restoredId;
                    client.deleteRestApi(request -> request.restApiId(importedId));
                }
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"TOKEN,oas30", "TOKEN,swagger", "COGNITO_USER_POOLS,oas30", "COGNITO_USER_POOLS,swagger"})
    @DisplayName("Protected exports need authorizer definitions for a safe re-import")
    void authorizerExportCannotBeMisclassifiedAsApiKey(String kind, String format) {
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            String apiId = client.createRestApi(request -> request.name(TestFixtures.uniqueName("protected-export"))).id();
            String restoredId = null;
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                String authorizerId = client.createAuthorizer(request -> {
                    request.restApiId(apiId).name("Protected").type(AuthorizerType.fromValue(kind))
                            .identitySource("method.request.header.Authorization");
                    if ("TOKEN".equals(kind)) {
                        request.authorizerUri("arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations");
                    } else {
                        request.providerARNs("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_fixture");
                    }
                }).id();
                client.putMethod(request -> {
                    request.restApiId(apiId).resourceId(rootId).httpMethod("GET").authorizerId(authorizerId)
                            .authorizationType("TOKEN".equals(kind) ? "CUSTOM" : kind);
                    if (!"TOKEN".equals(kind)) {
                        request.authorizationScopes("widgets/read");
                    }
                });
                client.createDeployment(request -> request.restApiId(apiId).stageName("dev"));
                SdkBytes incomplete = client.getExport(request -> request.restApiId(apiId).stageName("dev")
                        .exportType(format)).body();
                assertThatThrownBy(() -> client.importRestApi(request -> request.body(incomplete)))
                        .isInstanceOf(BadRequestException.class).hasMessageContaining("authorizer definition");
                SdkBytes complete = client.getExport(request -> request.restApiId(apiId).stageName("dev")
                        .exportType(format).parameters(Map.of("extensions", "authorizers"))).body();
                restoredId = client.importRestApi(request -> request.body(complete)).id();
                String importedId = restoredId;
                Resource root = client.getResources(request -> request.restApiId(importedId).embed("methods")).items().get(0);
                Method method = root.resourceMethods().get("GET");
                assertThat(method.authorizationType()).isEqualTo("TOKEN".equals(kind) ? "CUSTOM" : kind);
                assertThat(method.apiKeyRequired()).isFalse();
                assertThat(client.getAuthorizer(request -> request.restApiId(importedId).authorizerId(method.authorizerId())).type())
                        .isEqualTo(AuthorizerType.fromValue(kind));
                if (!"TOKEN".equals(kind)) {
                    assertThat(method.authorizationScopes()).containsExactly("widgets/read");
                }
            } finally {
                if (restoredId != null) {
                    String importedId = restoredId;
                    client.deleteRestApi(request -> request.restApiId(importedId));
                }
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"oas30", "swagger"})
    @DisplayName("Duplicate default responses produce an explicit export error")
    void duplicateResponsePatternsCannotSilentlyOverwrite(String format) {
        try (ApiGatewayClient client = TestFixtures.apiGatewayClient()) {
            String apiId = client.createRestApi(request -> request.name(TestFixtures.uniqueName("duplicate-export"))).id();
            try {
                String rootId = client.getResources(request -> request.restApiId(apiId)).items().get(0).id();
                client.putMethod(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET").authorizationType("NONE"));
                client.putIntegration(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET").type(IntegrationType.MOCK));
                for (String status : new String[]{"200", "400"}) {
                    client.putIntegrationResponse(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET")
                            .statusCode(status).selectionPattern("").responseTemplates(Map.of("application/json", status)));
                }
                client.createDeployment(request -> request.restApiId(apiId).stageName("dev"));
                assertThatThrownBy(() -> client.getExport(request -> request.restApiId(apiId).stageName("dev")
                        .exportType(format).parameters(Map.of("extensions", "integrations"))))
                        .isInstanceOf(BadRequestException.class).hasMessageContaining("duplicate integration response selection pattern");
                assertThat(client.getIntegration(request -> request.restApiId(apiId).resourceId(rootId).httpMethod("GET"))
                        .integrationResponses()).containsOnlyKeys("200", "400");
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

}
