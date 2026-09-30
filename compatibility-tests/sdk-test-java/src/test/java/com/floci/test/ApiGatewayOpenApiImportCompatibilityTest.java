package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.GetIntegrationResponse;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.GatewayResponseType;
import software.amazon.awssdk.services.apigateway.model.ImportRestApiResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.PutMode;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.apigateway.model.RestApi;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API Gateway OpenAPI import through the AWS SDK management API.
 */
@DisplayName("API Gateway OpenAPI import")
class ApiGatewayOpenApiImportCompatibilityTest {

    @Test
    @DisplayName("ImportRestApi and PutRestApi resolve AWS variables through the SDK")
    void sdkImportAndPutResolveAwsVariables() {
        String title = TestFixtures.uniqueName("apigw-variable-import");
        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            ImportRestApiResponse imported = apiGateway.importRestApi(request -> request
                    .body(SdkBytes.fromUtf8String(variableSpec(title, "${AWS::Region}"))));
            try {
                String initialId = resourceId(apiGateway, imported.id(), "/variable");
                GetIntegrationResponse integration = apiGateway.getIntegration(request -> request
                        .restApiId(imported.id()).resourceId(initialId).httpMethod("POST"));
                assertThat(integration.uri())
                        .isEqualTo("arn:aws:apigateway:us-east-1:states:action/StartExecution");
                assertThat(integration.requestTemplates())
                        .containsEntry("application/json", "{\"value\":\"us-east-1\"}");
                assertThat(integration.requestParameters())
                        .containsEntry("integration.request.header.X-Region", "'us-east-1'");
                assertThat(apiGateway.getGatewayResponse(request -> request
                        .restApiId(imported.id()).responseType(GatewayResponseType.UNAUTHORIZED))
                        .responseTemplates())
                        .containsEntry("application/json", "{\"account\":\"000000000000\"}");

                apiGateway.putRestApi(request -> request.restApiId(imported.id())
                        .mode(PutMode.OVERWRITE)
                        .body(SdkBytes.fromUtf8String(variableSpec(title, "${AWS::AccountId}"))));
                String updatedId = resourceId(apiGateway, imported.id(), "/variable");
                integration = apiGateway.getIntegration(request -> request
                        .restApiId(imported.id()).resourceId(updatedId).httpMethod("POST"));
                assertThat(integration.requestTemplates())
                        .containsEntry("application/json", "{\"value\":\"000000000000\"}");
            } finally {
                apiGateway.deleteRestApi(request -> request.restApiId(imported.id()));
            }
        }
    }

    private static String variableSpec(String title, String templateValue) {
        return """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "%s", "version": "1"},
                  "paths": {
                    "/variable": {
                      "post": {
                        "responses": {"200": {"description": "ok"}},
                        "x-amazon-apigateway-integration": {
                          "type": "aws", "httpMethod": "POST",
                          "uri": "arn:${AWS::Partition}:apigateway:${AWS::Region}:states:action/StartExecution",
                          "requestParameters": {
                            "integration.request.header.X-Region": "'${AWS::Region}'"
                          },
                          "requestTemplates": {"application/json": "{\\"value\\":\\"%s\\"}"},
                          "responses": {"default": {"statusCode": "200"}}
                        }
                      }
                    }
                  },
                  "x-amazon-apigateway-gateway-responses": {
                    "UNAUTHORIZED": {
                      "responseTemplates": {"application/json": "{\\"account\\":\\"${AWS::AccountId}\\"}"}
                    }
                  }
                }
                """.formatted(title, templateValue);
    }

    private static final String BROKEN_ANY_METHOD_PATHS = """
            "paths": {
              "/p/{proxy+}": {
                "x-amazon-apigateway-any-method": "not-an-operation-object"
              }
            }
            """;

    @Test
    @DisplayName("ImportRestApi creates an ANY method from x-amazon-apigateway-any-method")
    void importCreatesAnyMethod() {
        String title = TestFixtures.uniqueName("apigw-any-import");
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "%s", "version": "1"},
                  "paths": {
                    "/p/{proxy+}": {
                      "x-amazon-apigateway-any-method": {
                        "parameters": [
                          {"name": "proxy", "in": "path", "required": true, "schema": {"type": "string"}}
                        ],
                        "security": [],
                        "x-amazon-apigateway-integration": {
                          "type": "http_proxy",
                          "httpMethod": "ANY",
                          "uri": "http://example.com/{proxy}",
                          "requestParameters": {
                            "integration.request.path.proxy": "method.request.path.proxy"
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(title);

        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            ImportRestApiResponse imported = apiGateway.importRestApi(request -> request
                    .body(SdkBytes.fromUtf8String(spec)));
            try {
                String resourceId = resourceId(apiGateway, imported.id(), "/p/{proxy+}");

                GetMethodResponse method = apiGateway.getMethod(request -> request
                        .restApiId(imported.id())
                        .resourceId(resourceId)
                        .httpMethod("ANY"));
                assertThat(method.httpMethod()).isEqualTo("ANY");
                assertThat(method.requestParameters()).containsEntry("method.request.path.proxy", true);

                GetIntegrationResponse integration = apiGateway.getIntegration(request -> request
                        .restApiId(imported.id())
                        .resourceId(resourceId)
                        .httpMethod("ANY"));
                assertThat(integration.uri()).isEqualTo("http://example.com/{proxy}");
                assertThat(integration.requestParameters())
                        .containsEntry("integration.request.path.proxy", "method.request.path.proxy");
            } finally {
                apiGateway.deleteRestApi(request -> request.restApiId(imported.id()));
            }
        }
    }

    @Test
    @DisplayName("A failed ImportRestApi leaves no REST API behind")
    void failedImportLeavesNothing() {
        String title = TestFixtures.uniqueName("apigw-failed-import");
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "%s", "version": "1"},
                  %s
                }
                """.formatted(title, BROKEN_ANY_METHOD_PATHS);

        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            assertThatThrownBy(() -> apiGateway.importRestApi(request -> request
                    .body(SdkBytes.fromUtf8String(spec))))
                    .isInstanceOf(BadRequestException.class);

            List<String> names = apiGateway.getRestApis(request -> request.limit(500)).items().stream()
                    .map(RestApi::name)
                    .toList();
            assertThat(names).doesNotContain(title);
        }
    }

    @Test
    @DisplayName("A failed PutRestApi leaves the existing REST API unchanged")
    void failedPutLeavesApiUnchanged() {
        String title = TestFixtures.uniqueName("apigw-failed-put");
        String original = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "%s", "version": "1"},
                  "paths": {
                    "/kept": {
                      "get": {
                        "x-amazon-apigateway-integration": {
                          "type": "http_proxy", "httpMethod": "GET", "uri": "http://example.com/kept"
                        }
                      }
                    }
                  }
                }
                """.formatted(title);
        String broken = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "%s-renamed", "version": "1"},
                  %s
                }
                """.formatted(title, BROKEN_ANY_METHOD_PATHS);

        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            ImportRestApiResponse imported = apiGateway.importRestApi(request -> request
                    .body(SdkBytes.fromUtf8String(original)));
            try {
                String keptResourceId = resourceId(apiGateway, imported.id(), "/kept");

                assertThatThrownBy(() -> apiGateway.putRestApi(request -> request
                        .restApiId(imported.id())
                        .mode(PutMode.OVERWRITE)
                        .body(SdkBytes.fromUtf8String(broken))))
                        .isInstanceOf(BadRequestException.class);

                assertThat(apiGateway.getRestApi(request -> request.restApiId(imported.id())).name())
                        .isEqualTo(title);
                List<String> paths = apiGateway.getResources(request -> request.restApiId(imported.id()))
                        .items().stream()
                        .map(Resource::path)
                        .toList();
                assertThat(paths).containsExactlyInAnyOrder("/", "/kept");
                assertThat(resourceId(apiGateway, imported.id(), "/kept")).isEqualTo(keptResourceId);
                GetIntegrationResponse integration = apiGateway.getIntegration(request -> request
                        .restApiId(imported.id())
                        .resourceId(keptResourceId)
                        .httpMethod("GET"));
                assertThat(integration.uri()).isEqualTo("http://example.com/kept");
            } finally {
                apiGateway.deleteRestApi(request -> request.restApiId(imported.id()));
            }
        }
    }

    @Test
    @DisplayName("PutRestApi with mode=merge retains unrelated methods and replaces a conflicting method")
    void explicitMergeKeepsUnrelatedMethods() {
        assertMergeKeepsUnrelatedMethods(PutMode.MERGE);
    }

    @Test
    @DisplayName("PutRestApi defaults to merge when mode is omitted")
    void omittedModeMerges() {
        assertMergeKeepsUnrelatedMethods(null);
    }

    private static void assertMergeKeepsUnrelatedMethods(PutMode mode) {
        String title = TestFixtures.uniqueName("apigw-merge");
        String original = """
                {
                  "openapi": "3.0.1", "info": {"title": "%s", "version": "1"},
                  "paths": {
                    "/kept": {"get": {"x-amazon-apigateway-integration": {"type": "MOCK"}}},
                    "/shared": {
                      "get": {"x-amazon-apigateway-integration": {
                        "type": "http_proxy", "httpMethod": "GET", "uri": "https://example.com/old",
                        "responses": {"default": {"statusCode": "200"}, "legacy": {"statusCode": "206"}}
                      }},
                      "post": {"x-amazon-apigateway-integration": {"type": "MOCK"}}
                    }
                  }
                }
                """.formatted(title);
        String incoming = """
                {
                  "openapi": "3.0.1", "info": {"title": "%s", "version": "2"},
                  "paths": {
                    "/shared": {"get": {"x-amazon-apigateway-integration": {
                      "type": "http_proxy", "httpMethod": "GET", "uri": "https://example.com/new",
                      "responses": {"default": {"statusCode": "200"}, "new": {"statusCode": "400"}}
                    }}},
                    "/new": {"get": {"x-amazon-apigateway-integration": {"type": "MOCK"}}}
                  }
                }
                """.formatted(title);

        try (ApiGatewayClient apiGateway = TestFixtures.apiGatewayClient()) {
            ImportRestApiResponse imported = apiGateway.importRestApi(request -> request
                    .body(SdkBytes.fromUtf8String(original)));
            try {
                apiGateway.putRestApi(request -> {
                    request.restApiId(imported.id()).body(SdkBytes.fromUtf8String(incoming));
                    if (mode != null) {
                        request.mode(mode);
                    }
                });

                List<String> paths = apiGateway.getResources(request -> request.restApiId(imported.id()))
                        .items().stream().map(Resource::path).toList();
                assertThat(paths).containsExactlyInAnyOrder("/", "/kept", "/shared", "/new");

                apiGateway.getMethod(request -> request.restApiId(imported.id())
                        .resourceId(resourceId(apiGateway, imported.id(), "/kept")).httpMethod("GET"));
                String sharedId = resourceId(apiGateway, imported.id(), "/shared");
                apiGateway.getMethod(request -> request.restApiId(imported.id())
                        .resourceId(sharedId).httpMethod("POST"));
                apiGateway.getMethodResponse(request -> request.restApiId(imported.id())
                        .resourceId(sharedId).httpMethod("GET").statusCode("200"));
                apiGateway.getMethodResponse(request -> request.restApiId(imported.id())
                        .resourceId(sharedId).httpMethod("GET").statusCode("400"));
                assertThatThrownBy(() -> apiGateway.getMethodResponse(request -> request
                        .restApiId(imported.id()).resourceId(sharedId).httpMethod("GET")
                        .statusCode("206"))).isInstanceOf(NotFoundException.class);
                GetIntegrationResponse integration = apiGateway.getIntegration(request -> request
                        .restApiId(imported.id()).resourceId(sharedId).httpMethod("GET"));
                assertThat(integration.uri()).isEqualTo("https://example.com/new");
                assertThat(integration.integrationResponses())
                        .containsKeys("200", "400").doesNotContainKey("206");
            } finally {
                apiGateway.deleteRestApi(request -> request.restApiId(imported.id()));
            }
        }
    }

    private static String resourceId(ApiGatewayClient apiGateway, String restApiId, String path) {
        return apiGateway.getResources(request -> request.restApiId(restApiId)).items().stream()
                .filter(resource -> path.equals(resource.path()))
                .map(Resource::id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No resource with path " + path));
    }
}
