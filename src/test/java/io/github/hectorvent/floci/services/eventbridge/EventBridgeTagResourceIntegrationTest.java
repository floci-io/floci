package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeTagResourceIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void tagResourceOnEventBus() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.CreateEventBus")
            .body("{\"Name\":\"tag-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String busArn = given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"tag-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Arn");

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.TagResource")
            .body("""
                {
                    "ResourceARN": "%s",
                    "Tags": [
                        {"Key": "env", "Value": "prod"},
                        {"Key": "team", "Value": "infra"}
                    ]
                }
                """.formatted(busArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + busArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("prod"))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("infra"));
    }

    @Test
    @Order(2)
    void tagResourceOnRule() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("{\"Name\":\"tag-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String ruleArn = given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"tag-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Arn");

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.TagResource")
            .body("""
                {
                    "ResourceARN": "%s",
                    "Tags": [
                        {"Key": "service", "Value": "payments"},
                        {"Key": "priority", "Value": "high"}
                    ]
                }
                """.formatted(ruleArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + ruleArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'service' }.Value", equalTo("payments"))
            .body("Tags.find { it.Key == 'priority' }.Value", equalTo("high"));
    }

    @Test
    @Order(3)
    void untagResourceOnEventBus() {
        String busArn = given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"tag-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Arn");

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.UntagResource")
            .body("""
                {
                    "ResourceARN": "%s",
                    "TagKeys": ["env"]
                }
                """.formatted(busArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + busArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("infra"));
    }

    @Test
    @Order(4)
    void untagResourceOnRule() {
        String ruleArn = given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"tag-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Arn");

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.UntagResource")
            .body("""
                {
                    "ResourceARN": "%s",
                    "TagKeys": ["service", "priority"]
                }
                """.formatted(ruleArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + ruleArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    @Order(5)
    void tagResourceNotFound() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.TagResource")
            .body("""
                {
                    "ResourceARN": "arn:aws:events:us-east-1:000000000000:event-bus/nonexistent",
                    "Tags": [{"Key": "k", "Value": "v"}]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(6)
    void untagResourceNotFound() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.UntagResource")
            .body("""
                {
                    "ResourceARN": "arn:aws:events:us-east-1:000000000000:rule/nonexistent-rule",
                    "TagKeys": ["k"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(7)
    void cleanup() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DeleteRule")
            .body("{\"Name\":\"tag-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DeleteEventBus")
            .body("{\"Name\":\"tag-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /**
     * Regression test for #963 — a rule on a custom bus whose name contains the
     * substring "event-bus" must be tagged/listed/untagged as a rule, not a bus.
     */
    @Test
    @Order(8)
    void tagRuleOnBusNamedWithEventBusSubstring() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.CreateEventBus")
            .body("{\"Name\":\"my-event-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("{\"Name\":\"regression-rule\",\"EventBusName\":\"my-event-bus\",\"EventPattern\":\"{\\\"source\\\":[\\\"test\\\"]}\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String ruleArn = given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"regression-rule\",\"EventBusName\":\"my-event-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Arn");

        // Tag the rule — before the fix this matched the bus branch and failed.
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.TagResource")
            .body("""
                {
                    "ResourceARN": "%s",
                    "Tags": [{"Key": "env", "Value": "dev"}]
                }
                """.formatted(ruleArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + ruleArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("dev"));

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.UntagResource")
            .body("""
                {
                    "ResourceARN": "%s",
                    "TagKeys": ["env"]
                }
                """.formatted(ruleArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + ruleArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));

        // Cleanup
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DeleteRule")
            .body("{\"Name\":\"regression-rule\",\"EventBusName\":\"my-event-bus\"}")
        .when().post("/").then().statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DeleteEventBus")
            .body("{\"Name\":\"my-event-bus\"}")
        .when().post("/").then().statusCode(200);
    }
}
