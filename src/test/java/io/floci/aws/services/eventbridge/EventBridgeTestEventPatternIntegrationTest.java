package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.InvalidEventPatternException;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class EventBridgeTestEventPatternIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "AWSEvents.TestEventPattern";

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            7     | ">=",7         | true
            7     | ">=",6         | true
            6     | ">=",7         | false
            7     | ">",7          | false
            8     | ">",7          | true
            7     | "<=",7         | true
            8     | "<",7          | false
            7.0   | "=",7          | true
            301.8 | "=",3.018e2    | true
            7     | ">",6,"<=",7   | true
            8     | ">",6,"<=",7   | false
            "7"   | ">=",7         | false
            null  | ">=",0         | false
            [3,7] | ">=",7         | true
            [3,6] | ">=",7         | false
            [3,9] | ">=",7,"<",9   | false
            [3,8] | ">=",7,"<",9   | true
            ["7"] | ">=",7         | false
            []    | ">=",7         | false
            6     | ">",6,"<",8     | false
            7     | ">",6,"<",8     | true
            8     | ">",6,"<",8     | false
            6     | ">",6,"<=",8    | false
            7     | ">",6,"<=",8    | true
            8     | ">",6,"<=",8    | true
            6     | ">=",6,"<",8    | true
            7     | ">=",6,"<",8    | true
            8     | ">=",6,"<",8    | false
            6     | ">=",6,"<=",8   | true
            7     | ">=",6,"<=",8   | true
            8     | ">=",6,"<=",8   | true
            """)
    void numericComparisonThroughSdk(String value, String comparisons, boolean expected) {
        try (EventBridgeClient client = sdkClient()) {
            String event = """
                    {"version":"0","id":"numeric-test","detail-type":"Example","source":"example.test",
                     "account":"000000000000","time":"2026-11-06T00:00:00Z","region":"eu-west-2",
                     "resources":[],"detail":{"severity":%s}}
                    """.formatted(value);
            String pattern = "{\"detail\":{\"severity\":[{\"numeric\":[" + comparisons + "]}]}}";

            assertEquals(expected, client.testEventPattern(request -> request
                    .eventPattern(pattern).event(event)).result());
            String name = "valid-numeric-pattern";
            client.putRule(request -> request.name(name).eventPattern(pattern));
            try {
                assertEquals(pattern, client.describeRule(request -> request.name(name)).eventPattern());
            } finally {
                client.deleteRule(request -> request.name(name));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("invalidNumericPatterns")
    void invalidNumericPatternsRaiseSdkErrors(String pattern) {
        try (EventBridgeClient client = sdkClient()) {
            for (String detail : List.of("{\"severity\":7}", "{\"severity\":\"7\"}", "{}")) {
                String event = """
                        {"version":"0","id":"numeric-test","detail-type":"Example","source":"example.test",
                         "account":"000000000000","time":"2026-11-06T00:00:00Z","region":"eu-west-2",
                         "resources":[],"detail":%s}
                        """.formatted(detail);
                InvalidEventPatternException error = assertThrows(InvalidEventPatternException.class,
                        () -> client.testEventPattern(request -> request.eventPattern(pattern).event(event)));
                assertEquals(400, error.statusCode());
                assertEquals("InvalidEventPatternException", error.awsErrorDetails().errorCode());
            }
            InvalidEventPatternException error = assertThrows(InvalidEventPatternException.class,
                    () -> client.putRule(request -> request.name("invalid-numeric-pattern").eventPattern(pattern)));
            assertEquals(400, error.statusCode());
            assertEquals("InvalidEventPatternException", error.awsErrorDetails().errorCode());
        }
    }

    private static Stream<String> invalidNumericPatterns() {
        Stream<String> invalidFilters = Stream.of("null", "7", "{}", "[]", "[\">=\"]", "[\">=\",7,\"<\"]",
                "[\"!=\",7]", "[7,7]", "[\">=\",\"7\"]", "[\">=\",null]", "[\">=\",true]",
                "[\">\",0,\">\",1]", "[\">=\",0,\">\",1]",
                "[\">\",0,\">=\",1]", "[\">=\",0,\">=\",1]",
                "[\"<\",9,\"<\",7]", "[\"<=\",9,\"<=\",7]",
                "[\"=\",7,\">\",6]", "[\">\",6,\"=\",7]",
                "[\"=\",7,\"=\",7]", "[\"<\",9,\">\",7]",
                "[\"<=\",9,\">=\",7]", "[\">\",9,\"<\",7]",
                "[\">=\",9,\"<=\",7]", "[\">\",7,\"<\",7]",
                "[\">\",7,\"<=\",7]", "[\">=\",7,\"<\",7]",
                "[\">=\",7,\"<=\",7]", "[\">\",0,\"<\",9,\"<\",8]")
                .map(filter -> "{\"detail\":{\"severity\":[{\"numeric\":" + filter + "}]}}");
        return Stream.concat(invalidFilters, Stream.of(
                "{\"detail\":{\"nested\":{\"severity\":[{\"numeric\":[]}]}}}",
                "{\"$or\":[{\"source\":[\"example.test\"]},{\"detail\":{\"severity\":[{\"numeric\":[]}]}}]}",
                "{\"detail\":{\"$or\":[{\"severity\":[7]},{\"severity\":[{\"numeric\":[]}] }]}}",
                "{\"detail\":{\"severity\":[7,{\"numeric\":[]}]}}"));
    }

    @ParameterizedTest
    @MethodSource("invalidNumericPatterns")
    void invalidNumericUpdatePreservesExistingRuleThroughSdk(String pattern) {
        try (EventBridgeClient client = sdkClient()) {
            String name = "numeric-update-preservation";
            String original = "{\"detail\":{\"severity\":[7]}}";
            client.putRule(request -> request.name(name).eventPattern(original).description("original"));
            try {
                InvalidEventPatternException error = assertThrows(InvalidEventPatternException.class,
                        () -> client.putRule(request -> request.name(name).eventPattern(pattern).description("changed")));
                assertEquals(400, error.statusCode());
                assertEquals("InvalidEventPatternException", error.awsErrorDetails().errorCode());
                assertEquals(original, client.describeRule(request -> request.name(name)).eventPattern());
                assertEquals("original", client.describeRule(request -> request.name(name)).description());
            } finally {
                client.deleteRule(request -> request.name(name));
            }
        }
    }

    private EventBridgeClient sdkClient() {
        return EventBridgeClient.builder()
                .endpointOverride(endpoint)
                .region(Region.EU_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Test
    void sourceMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[\\"com.myapp\\"]}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void sourceNoMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[\\"com.myapp\\"]}",
                    "Event": "{\\"source\\":\\"com.otherapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailTypeMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[\\"com.myapp\\"],\\"detail-type\\":[\\"OrderPlaced\\"]}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailNestedMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"status\\":[\\"PAID\\"]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"status\\":\\"PAID\\"}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailBooleanLiteralMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"enabled\\":[true]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"enabled\\":true}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailBooleanLiteralNoMatchOnDifferentValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"enabled\\":[true]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"enabled\\":false}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailStringPatternDoesNotMatchBooleanValue() {
        // Pattern ["true"] must not match the JSON boolean true (type-strict, as in AWS)
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"enabled\\":[\\"true\\"]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"enabled\\":true}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailBooleanPatternDoesNotMatchStringValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"enabled\\":[true]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"enabled\\":\\"true\\"}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailNumberLiteralMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[5]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":5}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailStringPatternDoesNotMatchNumberValue() {
        // Pattern ["5"] must not match the JSON number 5 (type-strict, as in AWS)
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[\\"5\\"]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":5}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailNumberPatternDoesNotMatchStringValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[5]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":\\"5\\"}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailNumberMatchIsByNumericValue() {
        // AWS normalizes numbers before comparing: 300 and 300.0 are equal.
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[300]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":300.0}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailNumberScientificNotationMatchesDecimal() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[3.0e2]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":300.0}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailExistsMatchesBooleanValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"enabled\\":[{\\"exists\\":true}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"enabled\\":false}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailNullLiteralMatchesNullValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"field\\":[null]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"field\\":null}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailNullLiteralDoesNotMatchMissingField() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"field\\":[null]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"other\\":1}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailExistsTrueMatchesNullValue() {
        // A key carrying the JSON literal null still exists in AWS.
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"field\\":[{\\"exists\\":true}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"field\\":null}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailExistsFalseDoesNotMatchNullValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"field\\":[{\\"exists\\":false}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"field\\":null}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailExistsFalseMatchesMissingField() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"field\\":[{\\"exists\\":false}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"other\\":1}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailExistsTrueDoesNotMatchMissingField() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"field\\":[{\\"exists\\":true}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"other\\":1}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailAnythingButNumberValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[{\\"anything-but\\":[3]}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":5}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailAnythingButNumberValueExcluded() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[{\\"anything-but\\":[5]}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":5}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailAnythingButScalarStringValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"state\\":[{\\"anything-but\\":\\"initializing\\"}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"state\\":\\"running\\"}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailAnythingButScalarStringValueExcluded() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"state\\":[{\\"anything-but\\":\\"initializing\\"}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"state\\":\\"initializing\\"}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailAnythingButScalarNumberValue() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[{\\"anything-but\\":5}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":3}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void detailAnythingButScalarNumberValueExcluded() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"count\\":[{\\"anything-but\\":5}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"count\\":5}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void detailAnythingButScalarMissingFieldDoesNotMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"detail\\":{\\"state\\":[{\\"anything-but\\":\\"initializing\\"}]}}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{\\"other\\":1}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void prefixFilterMatch() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[{\\"prefix\\":\\"com.\\"}]}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void emptyPatternRejected() {
        // AWS spec: EventPattern is required; an empty string must be rejected
        // rather than treated as a match-all wildcard.
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void accountMatchFromEventEnvelope() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"account\\":[\\"999999999999\\"]}",
                    "Event": "{\\"source\\":\\"x\\",\\"detail-type\\":\\"y\\",\\"account\\":\\"999999999999\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void accountNoMatchFromEventEnvelope() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"account\\":[\\"999999999999\\"]}",
                    "Event": "{\\"source\\":\\"x\\",\\"detail-type\\":\\"y\\",\\"account\\":\\"111111111111\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void regionMatchFromEventEnvelope() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"region\\":[\\"eu-west-1\\"]}",
                    "Event": "{\\"source\\":\\"x\\",\\"detail-type\\":\\"y\\",\\"region\\":\\"eu-west-1\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(true));
    }

    @Test
    void regionNoMatchFromEventEnvelope() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"region\\":[\\"eu-west-1\\"]}",
                    "Event": "{\\"source\\":\\"x\\",\\"detail-type\\":\\"y\\",\\"region\\":\\"us-east-1\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(false));
    }

    @Test
    void arrayEventRejected() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[\\"com.myapp\\"]}",
                    "Event": "[]"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void scalarEventRejected() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[\\"com.myapp\\"]}",
                    "Event": "true"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void nullLiteralEventRejected() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[\\"com.myapp\\"]}",
                    "Event": "null"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void missingPatternRejected() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void malformedPatternRejected() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{not-json}",
                    "Event": "{\\"source\\":\\"com.myapp\\",\\"detail-type\\":\\"OrderPlaced\\",\\"detail\\":{}}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void missingEventRejected() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body("""
                {
                    "EventPattern": "{\\"source\\":[\\"com.myapp\\"]}"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }
}
