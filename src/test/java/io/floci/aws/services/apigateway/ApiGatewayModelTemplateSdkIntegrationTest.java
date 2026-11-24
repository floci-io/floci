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
    void generatedSamplesRenderNestedValuesAndArrayPlaceholders(boolean websocket) throws Exception {
        try (Fixture fixture = new Fixture(websocket)) {
            String model = fixture.createModel("TypedModel", SCHEMA);
            String template = fixture.template(model);
            assertTrue(template.contains("\n  \"name\" : \"foo\","));
            assertTrue(template.contains("##TODO: Update this foreach loop to reference array from input json"));
            assertTrue(template.contains("#foreach($elem in $inputRoot.TODO)"));
            JsonNode output = render(template, "{\"TODO\":[{},{}]}");
            assertEquals("foo", output.get("name").textValue());
            assertEquals(1, output.get("count").intValue());
            assertEquals(3.1415, output.get("ratio").doubleValue());
            assertTrue(output.get("enabled").booleanValue());
            assertTrue(output.get("nothing").isNull());
            assertEquals("foo", output.at("/nested/with space").textValue());
            assertTrue(output.at("/nested/a.b").booleanValue());
            assertEquals(2, output.get("words").size());
            assertEquals("foo", output.at("/words/0").textValue());
            assertEquals(1, output.at("/rows/0/values/0").intValue());
            String reference = fixture.createModel("ReferenceModel", REFERENCE_SCHEMA);
            JsonNode referenceInput = json.readTree("""
                    {"first":{"hyphen-key":"left"},"second":{"hyphen-key":"right"}}
                    """);
            JsonNode referenceOutput = render(fixture.template(reference), referenceInput.toString());
            assertEquals("foo", referenceOutput.at("/first/hyphen-key").textValue());
            assertEquals("foo", referenceOutput.at("/second/hyphen-key").textValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothSdkRoutesReturnTheDocumentedAwsSample(boolean websocket) {
        String schema = """
                {"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"},
                "op":{"type":"string"},"c":{"type":"number"}}}
                """;
        String expected = """
                #set($inputRoot = $input.path('$'))
                {
                  "a" : 3.1415,
                  "b" : 3.1415,
                  "op" : "foo",
                  "c" : 3.1415
                }""";
        try (Fixture fixture = new Fixture(websocket)) {
            assertEquals(expected, fixture.template(fixture.createModel("DocumentedSample", schema)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothSdkRoutesReturnTheAwsStringArraySkeleton(boolean websocket) throws Exception {
        String schema = """
                {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                }
                """;
        String expected = """
                #set($inputRoot = $input.path('$'))
                [
                ##TODO: Update this foreach loop to reference array from input json
                #foreach($elem in $inputRoot.TODO)
                 "foo"\s
                #if($foreach.hasNext),#end
                #end
                ]""";
        try (Fixture fixture = new Fixture(websocket)) {
            String template = fixture.template(fixture.createModel("StringArray", schema));
            assertEquals(expected, template);
            assertEquals(json.readTree("[]"), render(template, "{\"TODO\":[]}"));
            assertEquals(json.readTree("[\"foo\"]"), render(template, "{\"TODO\":[{}]}"));
            assertEquals(json.readTree("[\"foo\",\"foo\"]"), render(template, "{\"TODO\":[{},{}]}"));
        }
    }

    @Test
    void websocketMissingModelUsesTemplateSpecificError() {
        try (Fixture fixture = new Fixture(true)) {
            String model = "missing-model";
            String message = "Invalid model identifier specified: " + model;
            AwsServiceException error = assertThrows(AwsServiceException.class, () -> fixture.template(model));
            assertEquals(404, error.statusCode());
            assertEquals("NotFoundException", error.awsErrorDetails().errorCode());
            assertEquals(message, error.awsErrorDetails().errorMessage());
            jsonTemplate(fixture.apiId, model).statusCode(404).body(containsString(message));

            AwsServiceException modelError = assertThrows(AwsServiceException.class,
                    () -> fixture.socket.getModel(request -> request.apiId(fixture.apiId).modelId(model)));
            assertEquals("Model not found", modelError.awsErrorDetails().errorMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void topLevelStringModelsCannotGenerateTemplates(boolean websocket) {
        try (Fixture fixture = new Fixture(websocket)) {
            String model = fixture.createModel("Scalar", "{\"type\":\"string\"}");
            AwsServiceException error = assertThrows(AwsServiceException.class, () -> fixture.template(model));
            assertEquals(400, error.statusCode());
            assertEquals("Unsupported data type for mapping template: string", error.awsErrorDetails().errorMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelUpdatesDeletionAndOwnedApiBoundariesAreObserved(boolean websocket) throws Exception {
        try (Fixture fixture = new Fixture(websocket)) {
            String model = fixture.createModel("LifecycleModel", valueSchema("string"));
            assertTrue(render(fixture.template(model), "{}").get("value").isTextual());
            fixture.update(model, valueSchema("integer"));
            assertTrue(render(fixture.template(model), "{}").get("value").isIntegralNumber());
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
            fixture.update(model, valueSchema("boolean"));
            assertFalse(fixture.template(model).isBlank());
        }
    }

    @Test
    void websocketJsonPathMatchesSdkTemplatesAndErrors() {
        try (Fixture fixture = new Fixture(true)) {
            String model = fixture.createModel("JsonPathModel", SCHEMA);
            assertEquals(fixture.template(model), jsonTemplate(fixture.apiId, model).statusCode(200)
                    .extract().path("Value"));
            fixture.update(model, valueSchema("boolean"));
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
    void httpApisRejectModelCreationBeforePersistingAnything() {
        try (ApiGatewayV2Client client = v2("test", Region.US_EAST_1)) {
            String api = client.createApi(request -> request.name("http-template-boundary").protocolType("HTTP")).apiId();
            try {
                String message = "Currently, Models are not permitted for APIs with a protocol type of HTTP";
                AwsServiceException error = assertThrows(AwsServiceException.class,
                        () -> client.createModel(request -> request.apiId(api).name("HttpModel")
                                .contentType("application/json").schema(valueSchema("string"))));
                assertEquals(400, error.statusCode());
                assertEquals(message, error.awsErrorDetails().errorMessage());
                given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                        .header("X-Amz-Target", "AmazonApiGatewayV2.CreateModel")
                        .header("Authorization", auth("test", "apigatewayv2"))
                        .body(Map.of("ApiId", api, "Name", "HttpModel", "ContentType", "application/json",
                                "Schema", valueSchema("string")))
                        .post("/").then().statusCode(400).body(containsString(message));
                assertTrue(client.getModels(request -> request.apiId(api)).items().isEmpty());
                assertStatus(404, () -> client.getModelTemplate(request -> request.apiId(api).modelId("missing")));
                jsonTemplate(api, "missing").statusCode(404).body(containsString("NotFoundException"));
            } finally {
                client.deleteApi(request -> request.apiId(api));
            }
        }
    }

    private static String valueSchema(String type) {
        return "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"" + type + "\"}}}";
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
