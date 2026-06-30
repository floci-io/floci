package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class IotTopicRuleIntegrationTest {

    @Inject
    IotPublishEventRecorder eventRecorder;

    @BeforeEach
    void clearEvents() {
        eventRecorder.clear();
    }

    @Test
    void topicRuleMetadataRoundTrips() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/phase8/crud'",
                    "description": "phase 8 metadata",
                    "ruleDisabled": false,
                    "actions": [
                      {
                        "republish": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "topic": "devices/phase8/target"
                        }
                      }
                    ]
                  }
                }
                """)
        .when()
            .put("/rules/phase8CrudRule")
        .then()
            .statusCode(200)
            .body("ruleArn", equalTo("arn:aws:iot:us-east-1:000000000000:rule/phase8CrudRule"))
            .body("ruleName", equalTo("phase8CrudRule"));

        given()
        .when()
            .get("/rules/phase8CrudRule")
        .then()
            .statusCode(200)
            .body("ruleArn", equalTo("arn:aws:iot:us-east-1:000000000000:rule/phase8CrudRule"))
            .body("rule.ruleName", equalTo("phase8CrudRule"))
            .body("rule.sql", equalTo("SELECT * FROM 'devices/phase8/crud'"))
            .body("rule.description", equalTo("phase 8 metadata"))
            .body("rule.ruleDisabled", equalTo(false))
            .body("rule.actions[0].republish.topic", equalTo("devices/phase8/target"))
            .body("rule.createdAt", notNullValue());

        given()
        .when()
            .post("/rules/phase8CrudRule/disable")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/rules")
        .then()
            .statusCode(200)
            .body("rules.ruleName", hasItem("phase8CrudRule"))
            .body("rules.find { it.ruleName == 'phase8CrudRule' }.ruleDisabled", equalTo(true));

        given()
        .when()
            .post("/rules/phase8CrudRule/enable")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/rules/phase8CrudRule")
        .then()
            .statusCode(200)
            .body("rule.ruleDisabled", equalTo(false));

        given()
        .when()
            .delete("/rules/phase8CrudRule")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/rules")
        .then()
            .statusCode(200)
            .body("rules.ruleName", not(hasItem("phase8CrudRule")));
    }

    @Test
    void matchingTopicRuleRepublishesOnlyMatchingPublishes() throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/phase8/match/+'",
                    "actions": [
                      {
                        "republish": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "topic": "devices/phase8/republished"
                        }
                      }
                    ]
                  }
                }
                """)
        .when()
            .put("/rules/phase8RepublishRule")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .body("matched-payload")
        .when()
            .post("/topics/devices/phase8/match/one")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .body("ignored-payload")
        .when()
            .post("/topics/devices/phase8/no-match")
        .then()
            .statusCode(200);

        awaitPublishedEvent("devices/phase8/republished", "matched-payload".getBytes(StandardCharsets.UTF_8));
        assertFalse(eventRecorder.recentEvents().stream()
                .anyMatch(event -> "devices/phase8/republished".equals(event.topic())
                        && Arrays.equals("ignored-payload".getBytes(StandardCharsets.UTF_8), event.payload())));
    }

    @Test
    void matchingTopicRuleSendsPayloadToSqs() {
        String queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "phase8-iot-rule-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/phase8/sqs'",
                    "actions": [
                      {
                        "sqs": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "queueUrl": "%s"
                        }
                      }
                    ]
                  }
                }
                """.formatted(queueUrl))
        .when()
            .put("/rules/phase8SqsRule")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .body("sqs-rule-payload")
        .when()
            .post("/topics/devices/phase8/sqs")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sqs-rule-payload"));
    }

    @Test
    void matchingTopicRulePublishesPayloadToSns() {
        String queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "mvp1-iot-sns-rule-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "mvp1-iot-sns-rule-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "sqs")
            .formParam("Endpoint", queueUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/mvp1/sns'",
                    "actions": [
                      {
                        "sns": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "targetArn": "%s"
                        }
                      }
                    ]
                  }
                }
                """.formatted(topicArn))
        .when()
            .put("/rules/mvp1SnsRule")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .body("sns-rule-payload")
        .when()
            .post("/topics/devices/mvp1/sns")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sns-rule-payload"));
    }

    @Test
    void topicRuleConflictReplaceDeleteAndTagsMatchMvpLifecycle() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/mvp1/original'",
                    "description": "original",
                    "actions": []
                  }
                }
                """)
        .when()
            .put("/rules/mvp1Rule")
        .then()
            .statusCode(200)
            .body("ruleArn", equalTo("arn:aws:iot:us-east-1:000000000000:rule/mvp1Rule"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/mvp1/original'",
                    "actions": []
                  }
                }
                """)
        .when()
            .put("/rules/mvp1Rule")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceAlreadyExistsException"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:iot:us-east-1:000000000000:rule/mvp1Rule",
                  "tags": [{"Key": "env", "Value": "rule"}]
                }
                """)
        .when()
            .post("/tags")
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceArn", "arn:aws:iot:us-east-1:000000000000:rule/mvp1Rule")
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("env"))
            .body("tags.Value", hasItem("rule"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/mvp1/replaced'",
                    "description": "replaced",
                    "actions": []
                  }
                }
                """)
        .when()
            .patch("/rules/mvp1Rule")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/rules/mvp1Rule")
        .then()
            .statusCode(200)
            .body("rule.sql", equalTo("SELECT * FROM 'devices/mvp1/replaced'"))
            .body("rule.description", equalTo("replaced"));

        given()
        .when()
            .delete("/rules/mvp1Rule")
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/rules/mvp1Rule")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private void awaitPublishedEvent(String topic, byte[] payload) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (Instant.now().isBefore(deadline)) {
            boolean found = eventRecorder.recentEvents().stream()
                    .anyMatch(event -> topic.equals(event.topic()) && Arrays.equals(payload, event.payload()));
            if (found) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("IoT publish event was not recorded for topic " + topic);
    }
}
