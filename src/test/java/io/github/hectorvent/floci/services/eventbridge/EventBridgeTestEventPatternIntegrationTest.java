package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class EventBridgeTestEventPatternIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "AWSEvents.TestEventPattern";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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

    /** A complete event envelope, as AWS requires TestEventPattern events to be. */
    private static String fullEnvelopeRequest(String eventPattern, String detail) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("version", "0");
        event.put("id", "7bf73129-1428-4cd3-a780-95db273d1602");
        event.put("detail-type", "Object Created");
        event.put("source", "aws.s3");
        event.put("account", "123456789012");
        event.put("time", "2015-11-11T21:29:54Z");
        event.put("region", "us-east-1");
        event.putArray("resources");
        try {
            event.set("detail", MAPPER.readTree(detail));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(detail, e);
        }
        ObjectNode request = MAPPER.createObjectNode();
        request.put("EventPattern", eventPattern);
        request.put("Event", event.toString());
        return request.toString();
    }

    private static void assertTestEventPatternResult(String eventPattern, String detail, boolean expected) {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body(fullEnvelopeRequest(eventPattern, detail))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Result", equalTo(expected));
    }

    @Test
    void numericComparisonOnAFullEnvelope() {
        String pattern = "{\"detail\":{\"price\":[{\"numeric\":[\">\",10]}]}}";
        assertTestEventPatternResult(pattern, "{\"price\":42}", true);
        assertTestEventPatternResult(pattern, "{\"price\":5}", false);
    }

    @Test
    void prefixEqualsIgnoreCaseOnAFullEnvelope() {
        String pattern = "{\"detail\":{\"service\":[{\"prefix\":{\"equals-ignore-case\":\"eventb\"}}]}}";
        assertTestEventPatternResult(pattern, "{\"service\":\"EventBridge\"}", true);
        assertTestEventPatternResult(pattern, "{\"service\":\"s3\"}", false);
    }

    @Test
    void wildcardOnAFullEnvelope() {
        String pattern = "{\"detail\":{\"key\":[{\"wildcard\":\"dir/*.png\"}]}}";
        assertTestEventPatternResult(pattern, "{\"key\":\"dir/cat.png\"}", true);
        assertTestEventPatternResult(pattern, "{\"key\":\"dir/cat.jpg\"}", false);
    }

    @Test
    void cidrOnAFullEnvelope() {
        String pattern = "{\"detail\":{\"sourceIPAddress\":[{\"cidr\":\"10.0.0.0/24\"}]}}";
        assertTestEventPatternResult(pattern, "{\"sourceIPAddress\":\"10.0.0.7\"}", true);
        assertTestEventPatternResult(pattern, "{\"sourceIPAddress\":\"10.0.1.7\"}", false);
    }

    @Test
    void anythingButSuffixOnAFullEnvelope() {
        String pattern = "{\"detail\":{\"key\":[{\"anything-but\":{\"suffix\":\".txt\"}}]}}";
        assertTestEventPatternResult(pattern, "{\"key\":\"a.png\"}", true);
        assertTestEventPatternResult(pattern, "{\"key\":\"a.txt\"}", false);
    }

    @Test
    void timeIsMatchedOnAFullEnvelope() {
        assertTestEventPatternResult("{\"time\":[{\"prefix\":\"2015-11-11\"}]}", "{}", true);
        assertTestEventPatternResult("{\"time\":[{\"prefix\":\"2017-10-02\"}]}", "{}", false);
    }

    @Test
    void arrayValueMatchesAnyElementOnAFullEnvelope() {
        assertTestEventPatternResult("{\"detail\":{\"tags\":[\"prod\"]}}", "{\"tags\":[\"prod\",\"eu\"]}", true);
        assertTestEventPatternResult("{\"detail\":{\"tags\":[\"prod\"]}}", "{\"tags\":[\"dev\"]}", false);
        assertTestEventPatternResult("{\"detail\":{\"items\":{\"sku\":[\"A\"]}}}",
                "{\"items\":[{\"sku\":\"B\"},{\"sku\":\"A\"}]}", true);
    }

    @Test
    void scalarPatternValueRejected() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", TARGET)
            .body(fullEnvelopeRequest("{\"source\":\"aws.s3\"}", "{}"))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidEventPatternException"));
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
