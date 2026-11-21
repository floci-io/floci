package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayModelTemplateSdkIntegrationTest {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string"
                },
                "count": {
                  "type": "integer"
                },
                "ratio": {
                  "type": "number"
                },
                "enabled": {
                  "type": "boolean"
                },
                "nothing": {
                  "type": "null"
                },
                "nested": {
                  "type": "object",
                  "properties": {
                    "with space": {
                      "type": "string"
                    },
                    "a.b": {
                      "type": "boolean"
                    }
                  }
                },
                "words": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                },
                "rows": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "label": {
                        "type": "string"
                      },
                      "values": {
                        "type": "array",
                        "items": {
                          "type": "integer"
                        }
                      }
                    }
                  }
                }
              }
            }
            """;
    private static final String REFERENCE_SCHEMA = """
            {
              "definitions": {
                "a/b": {
                  "type": "object",
                  "properties": {
                    "hyphen-key": {
                      "type": "string"
                    }
                  }
                }
              },
              "type": "object",
              "properties": {
                "first": {
                  "$ref": "#/definitions/a~1b"
                },
                "second": {
                  "$ref": "#/definitions/a~1b"
                }
              }
            }
            """;

    @Inject
    VtlTemplateEngine engine;
    @Inject
    ObjectMapper json;
    @TestHTTPResource("/")
    URI endpoint;

    @BeforeAll
    static void configureJsonCompatibilityContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generatedTemplatesRenderTypedNestedAndEscapedJson(boolean websocket) throws Exception {
        try (Fixture fixture = new Fixture(websocket)) {
            String model = fixture.createModel("TypedModel", SCHEMA);
            String template = fixture.template(model);
            JsonNode input = json.readTree("""
                    {"name":"quote\\\" slash\\\\ line\\n tab\\t apostrophe' Ω", "count":0,"ratio":2.5,
                    "enabled":false,"nothing":null,"nested":{"with space":"","a.b":false},
                    "words":["one","two's"],"rows":[{"label":"alpha","values":[1,2]},
                    {"label":"beta","values":[]}]}
                    """);
            JsonNode output = render(template, input.toString());
            if (websocket) {
                assertEquals(input, output);
                JsonNode nullable = json.readTree("""
                        {"name":null,"count":null,"ratio":null,"enabled":null,"nothing":null,
                        "nested":null,"words":null,"rows":[]}
                        """);
                assertEquals(nullable, render(template, nullable.toString()));
            } else {
                assertTrue(output.get("name").isTextual());
                assertTrue(output.get("count").isIntegralNumber());
                assertTrue(output.get("ratio").isNumber());
                assertTrue(output.get("enabled").isBoolean());
                assertTrue(output.get("nothing").isNull());
                assertTrue(output.at("/nested/with space").isTextual());
                assertTrue(output.at("/nested/a.b").isBoolean());
                assertTrue(output.at("/words/0").isTextual());
                assertTrue(output.at("/rows/0/values/0").isIntegralNumber());
            }
            String reference = fixture.createModel("ReferenceModel", REFERENCE_SCHEMA);
            JsonNode referenceInput = json.readTree("""
                    {"first":{"hyphen-key":"left"},"second":{"hyphen-key":"right"}}
                    """);
            JsonNode referenceOutput = render(fixture.template(reference), referenceInput.toString());
            assertTrue(referenceOutput.at("/first/hyphen-key").isTextual());
            assertTrue(referenceOutput.at("/second/hyphen-key").isTextual());
            if (websocket) {
                assertEquals(referenceInput, referenceOutput);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelUpdatesDeletionAndOwnedApiBoundariesAreObserved(boolean websocket) throws Exception {
        try (Fixture fixture = new Fixture(websocket)) {
            String model = fixture.createModel("LifecycleModel", "{\"type\":\"string\"}");
            assertTrue(render(fixture.template(model), "\"input\"").isTextual());
            fixture.update(model, "{\"type\":\"integer\"}");
            assertTrue(render(fixture.template(model), "42").isIntegralNumber());
            assertStatus(404, () -> fixture.templateAs(model, "test", Region.US_WEST_2));
            assertStatus(404, () -> fixture.templateAs(model, "987654321098", Region.US_EAST_1));
            assertStatus(404, () -> fixture.template("MissingModel"));
            String realApi = fixture.apiId;
            try {
                fixture.apiId = "missing-api";
                assertStatus(404, () -> fixture.template(model));
            } finally {
                fixture.apiId = realApi;
            }
            fixture.deleteModel(model);
            assertStatus(404, () -> fixture.template(model));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsupportedSchemasAreExplicitAndDoNotChangeStoredModels(boolean websocket) {
        try (Fixture fixture = new Fixture(websocket)) {
            String schema = "{\"allOf\":[{\"type\":\"object\"}]}";
            String model = fixture.createModel("CompositionModel", schema);
            assertStatus(501, () -> fixture.template(model));
            assertEquals(schema, fixture.schema(model));
            fixture.update(model, "{\"type\":\"boolean\"}");
            assertFalse(fixture.template(model).isBlank());
        }
    }

    @Test
    void websocketJsonPathMatchesSdkTemplatesAndErrors() {
        try (Fixture fixture = new Fixture(true)) {
            String model = fixture.createModel("JsonPathModel", SCHEMA);
            assertEquals(fixture.template(model), jsonTemplate(fixture.apiId, model).statusCode(200)
                    .extract().path("Value"));
            fixture.update(model, "{\"type\":\"boolean\"}");
            assertEquals(fixture.template(model), jsonTemplate(fixture.apiId, model).statusCode(200)
                    .extract().path("Value"));
            fixture.update(model, "{\"allOf\":[{\"type\":\"object\"}]}");
            jsonTemplate(fixture.apiId, model).statusCode(501).body(containsString("UnsupportedOperation"));
            fixture.deleteModel(model);
            jsonTemplate(fixture.apiId, model).statusCode(404).body(containsString("NotFoundException"));
            jsonTemplate("missing-api", model).statusCode(404).body(containsString("NotFoundException"));
        }
    }

    @Test
    void httpApiTemplatesRemainOutsideTheWebSocketScope() {
        try (ApiGatewayV2Client client = v2("test", Region.US_EAST_1)) {
            String api = client.createApi(request -> request.name("http-template-boundary").protocolType("HTTP")).apiId();
            try {
                String model = client.createModel(request -> request.apiId(api).name("HttpModel")
                        .contentType("application/json").schema("{\"type\":\"string\"}")).modelId();
                assertStatus(400, () -> client.getModelTemplate(request -> request.apiId(api).modelId(model)));
                jsonTemplate(api, model).statusCode(400).body(containsString("BadRequestException"));
            } finally {
                client.deleteApi(request -> request.apiId(api));
            }
        }
    }

    private JsonNode render(String template, String body) throws Exception {
        VtlTemplateEngine.VtlContext context = new VtlTemplateEngine.VtlContext(body, Map.of(), Map.of(),
                Map.of(), "stage", "POST", "/", "request", "000000000000", Map.of(), Map.of());
        return json.readTree(engine.evaluate(template, context).body());
    }

    private void assertStatus(int status, Runnable request) {
        assertEquals(status, assertThrows(AwsServiceException.class, request::run).statusCode());
    }

    private ApiGatewayClient v1(String key, Region region) {
        return AwsTestClients.create(ApiGatewayClient.builder(), key, "test", region,
                Apache5HttpClient.builder(), endpoint);
    }

    private ApiGatewayV2Client v2(String key, Region region) {
        return AwsTestClients.create(ApiGatewayV2Client.builder(), key, "test", region,
                Apache5HttpClient.builder(), endpoint);
    }

    private ValidatableResponse jsonTemplate(String api, String model) {
        return given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonApiGatewayV2.GetModelTemplate")
                .header("Authorization", auth("test", "apigatewayv2"))
                .body(Map.of("ApiId", api, "ModelId", model)).post("/").then();
    }

    private final class Fixture implements AutoCloseable {
        private final boolean websocket;
        private final ApiGatewayClient rest = v1("test", Region.US_EAST_1);
        private final ApiGatewayV2Client socket = v2("test", Region.US_EAST_1);
        private String apiId;

        private Fixture(boolean websocket) {
            this.websocket = websocket;
            String name = "model-template-" + UUID.randomUUID();
            apiId = websocket ? socket.createApi(request -> request.name(name).protocolType("WEBSOCKET")
                    .routeSelectionExpression("$request.body.action")).apiId()
                    : rest.createRestApi(request -> request.name(name)).id();
        }

        private String createModel(String name, String schema) {
            return websocket ? socket.createModel(request -> request.apiId(apiId).name(name)
                    .contentType("application/json").schema(schema)).modelId()
                    : rest.createModel(request -> request.restApiId(apiId).name(name)
                    .contentType("application/json").schema(schema)).name();
        }

        private String template(String model) {
            return websocket ? socket.getModelTemplate(request -> request.apiId(apiId).modelId(model)).value()
                    : rest.getModelTemplate(request -> request.restApiId(apiId).modelName(model)).value();
        }

        private String templateAs(String model, String key, Region region) {
            try (ApiGatewayClient otherRest = v1(key, region); ApiGatewayV2Client otherSocket = v2(key, region)) {
                return websocket ? otherSocket.getModelTemplate(request -> request.apiId(apiId).modelId(model)).value()
                        : otherRest.getModelTemplate(request -> request.restApiId(apiId).modelName(model)).value();
            }
        }

        private void update(String model, String schema) {
            if (websocket) {
                socket.updateModel(request -> request.apiId(apiId).modelId(model).schema(schema));
            } else {
                rest.updateModel(request -> request.restApiId(apiId).modelName(model).patchOperations(
                        PatchOperation.builder().op("replace").path("/schema").value(schema).build()));
            }
        }

        private String schema(String model) {
            return websocket ? socket.getModel(request -> request.apiId(apiId).modelId(model)).schema()
                    : rest.getModel(request -> request.restApiId(apiId).modelName(model)).schema();
        }

        private void deleteModel(String model) {
            if (websocket) {
                socket.deleteModel(request -> request.apiId(apiId).modelId(model));
            } else {
                rest.deleteModel(request -> request.restApiId(apiId).modelName(model));
            }
        }

        @Override
        public void close() {
            try {
                if (websocket) {
                    socket.deleteApi(request -> request.apiId(apiId));
                } else {
                    rest.deleteRestApi(request -> request.restApiId(apiId));
                }
            } finally {
                rest.close();
                socket.close();
            }
        }
    }
}
