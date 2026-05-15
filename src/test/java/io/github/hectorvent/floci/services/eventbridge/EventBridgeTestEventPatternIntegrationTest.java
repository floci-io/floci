package io.github.hectorvent.floci.services.eventbridge;

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
