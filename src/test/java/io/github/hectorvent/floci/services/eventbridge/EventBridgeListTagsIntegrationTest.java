package io.github.hectorvent.floci.services.eventbridge;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeListTagsIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(EVENT_BRIDGE_CONTENT_TYPE, ContentType.TEXT)
        );
    }

    @Test
    @Order(1)
    void createRuleWithTags() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("""
                {
                    "Name": "tagged-rule",
                    "Tags": [
                        {"Key": "env", "Value": "test"},
                        {"Key": "team", "Value": "platform"}
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleArn", notNullValue());
    }

    @Test
    @Order(2)
    void listTagsForRuleResource() {
        // First get the rule ARN
        String ruleArn = given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"tagged-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Arn");

        // Now list tags for the rule
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + ruleArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("test"))
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("platform"));
    }

    @Test
    @Order(3)
    void listTagsForResourceWithNoTags() {
        // Create a rule with no tags
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("{\"Name\":\"untagged-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String ruleArn = given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"untagged-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Arn");

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
    @Order(4)
    void cleanup() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DeleteRule")
            .body("{\"Name\":\"tagged-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DeleteRule")
            .body("{\"Name\":\"untagged-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
