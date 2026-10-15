package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class ApiGatewayImportedIntegrationTypeIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LAMBDA_URI = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/"
            + "functions/arn:aws:lambda:us-east-1:000000000000:function:fixture:live/invocations";

    @ParameterizedTest
    @CsvSource({
        "aws, AWS, get, GET",
        "aws_proxy, AWS_PROXY, get, GET",
        "http, HTTP, get, GET",
        "http_proxy, HTTP_PROXY, get, GET",
        "mock, MOCK, get, GET",
        "AWS, AWS, get, GET",
        "AWS_PROXY, AWS_PROXY, get, GET",
        "HTTP, HTTP, get, GET",
        "HTTP_PROXY, HTTP_PROXY, get, GET",
        "MOCK, MOCK, get, GET",
        "Aws, AWS, get, GET",
        "Aws_Proxy, AWS_PROXY, get, GET",
        "Http, HTTP, get, GET",
        "Http_Proxy, HTTP_PROXY, get, GET",
        "Mock, MOCK, get, GET",
        "mock, MOCK, x-amazon-apigateway-any-method, ANY"
    })
    void importMergeAndOverwriteExposeCanonicalMetadataWithoutChangingOtherFields(
            String importedType, String expectedType, String operation, String verb) throws Exception {
        Map<String, Object> integration = integration(importedType);
        String apiId = importApi(document(operation, integration));
        try {
            assertMetadata(apiId, verb, integration, expectedType);

            for (String mode : List.of("merge", "overwrite")) {
                Map<String, Object> replacement = new LinkedHashMap<>(integration);
                replacement.put("timeoutInMillis", "merge".equals(mode) ? 14000 : 15000);
                if (replacement.containsKey("uri")) {
                    replacement.put("uri", replacement.get("uri").toString().replace("live", "next")
                            .replace("/original", "/replacement"));
                }
                given().contentType(ContentType.JSON).body(document(operation, replacement))
                        .put("/restapis/" + apiId + "?mode=" + mode)
                        .then().statusCode(200);
                assertMetadata(apiId, verb, replacement, expectedType);
            }
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "custom_type, get, GET",
        "CuStOm_TyPe, get, GET",
        "'', get, GET",
        "' mock', get, GET",
        "'mock ', get, GET",
        "http-proxy, get, GET",
        "hıttp, get, GET",
        "mοck, get, GET",
        "moc\u212A, get, GET",
        "CuStOm_TyPe, x-amazon-apigateway-any-method, ANY"
    })
    void unknownTypesWarnAndOmitIntegrationOrRefuseBeforeChangingApi(
            String type, String operation, String verb) throws Exception {
        Map<String, Object> invalid = document(operation, Map.of("type", type));
        String warning = "Unknown integration type '" + type + "' for '" + verb + " /status'. Ignoring.";
        String error = "Warnings found during import:\n\t" + warning;
        given().contentType(ContentType.JSON).body(invalid)
                .post("/restapis?mode=import&failonwarnings=true")
                .then().statusCode(400).body("message", equalTo(error));
        String apiId = given().contentType(ContentType.JSON).body(invalid)
                .post("/restapis?mode=import")
                .then().statusCode(201).body("warnings", equalTo(List.of(warning))).extract().path("id");
        try {
            assertMissingIntegration(apiId, verb);
            Map<String, Object> valid = integration("mock");
            for (String mode : List.of("merge", "overwrite")) {
                given().contentType(ContentType.JSON).body(document(operation, valid))
                        .put("/restapis/" + apiId + "?mode=overwrite").then().statusCode(200);
                given().contentType(ContentType.JSON).body(invalid)
                        .put("/restapis/" + apiId + "?mode=" + mode + "&failonwarnings=true")
                        .then().statusCode(400).body("message", equalTo(error));
                assertMetadata(apiId, verb, valid, "MOCK");
                given().contentType(ContentType.JSON).body(invalid)
                        .put("/restapis/" + apiId + "?mode=" + mode)
                        .then().statusCode(200).body("warnings", equalTo(List.of(warning)));
                assertMissingIntegration(apiId, verb);
            }
        } finally {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private static void assertMissingIntegration(String apiId, String verb) throws Exception {
        String path = methodPath(apiId, verb);
        given().get(path + "/integration").then().statusCode(404);
        assertFalse(get(path).has("methodIntegration"));
    }

    private static Map<String, Object> integration(String type) {
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("type", type);
        String normalizedType = type.toLowerCase(Locale.ROOT);
        integration.put("timeoutInMillis", 12000);
        if (!"mock".equals(normalizedType)) {
            integration.put("httpMethod", "POST");
        }
        if ("aws".equals(normalizedType) || "aws_proxy".equals(normalizedType)) {
            integration.put("uri", LAMBDA_URI);
        } else if (!"mock".equals(normalizedType)) {
            integration.put("uri", "https://example.com/original");
        }
        if (List.of("aws", "http", "mock").contains(normalizedType)) {
            integration.put("responses", Map.of("default", Map.of("statusCode", "200")));
        }
        return integration;
    }

    private static Map<String, Object> document(String verb, Map<String, Object> integration) {
        return Map.of(
                "openapi", "3.0.2",
                "info", Map.of("title", "integration-type", "version", "1"),
                "paths", Map.of("/status", Map.of(verb, Map.of(
                        "responses", Map.of("200", Map.of("description", "ok")),
                        "x-amazon-apigateway-integration", integration))));
    }

    private static String importApi(Map<String, Object> document) {
        return given().contentType(ContentType.JSON).body(document)
                .post("/restapis?mode=import").then().statusCode(201).extract().path("id");
    }

    private static String methodPath(String apiId, String verb) throws Exception {
        JsonNode resources = get("/restapis/" + apiId + "/resources").path("item");
        for (JsonNode resource : resources) {
            if ("/status".equals(resource.path("path").asText())) {
                return "/restapis/" + apiId + "/resources/" + resource.path("id").asText() + "/methods/" + verb;
            }
        }
        throw new AssertionError("Imported status resource is missing");
    }

    private static void assertMetadata(String apiId, String verb, Map<String, Object> expected,
                                       String expectedType) throws Exception {
        String path = methodPath(apiId, verb);
        JsonNode method = get(path);
        JsonNode integration = get(path + "/integration");
        assertEquals(expectedType, integration.path("type").asText());
        assertEquals(integration, method.path("methodIntegration"));
        for (Map.Entry<String, Object> field : expected.entrySet()) {
            if (!List.of("type", "responses").contains(field.getKey())) {
                assertEquals(MAPPER.valueToTree(field.getValue()), integration.get(field.getKey()), field.getKey());
            }
        }
    }

    private static JsonNode get(String path) throws Exception {
        return MAPPER.readTree(given().get(path).then().statusCode(200).extract().asString());
    }
}
