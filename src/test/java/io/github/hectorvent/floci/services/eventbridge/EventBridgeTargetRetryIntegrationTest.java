package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeTargetRetryIntegrationTest {

    private static final String EB_CT = "application/x-amz-json-1.1";
    private static final String SQS_CT = "application/x-amz-json-1.0";
    private static final String RULE = "eb-retry-dlq-rule";

    private static String dlqUrl;
    private static String dlqArn;
    private static String ruleArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createDeadLetterQueueAndRule() {
        dlqUrl = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"eb-retry-dlq\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");

        dlqArn = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + dlqUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        ruleArn = given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                        {
                          "Name": "%s",
                          "EventBusName": "default",
                          "EventPattern": "{\\"source\\":[\\"test.retry\\"]}"
                        }
                        """.formatted(RULE))
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("RuleArn");
    }

    @Test
    @Order(2)
    void listTargetsByRuleEchoesRetryPolicyAndDeadLetterConfig() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "%s",
                          "Targets": [
                            {
                              "Id": "WithPolicies",
                              "Arn": "%s",
                              "RetryPolicy": {"MaximumRetryAttempts": 4, "MaximumEventAgeInSeconds": 120},
                              "DeadLetterConfig": {"Arn": "%s"}
                            },
                            {"Id": "Plain", "Arn": "%s"}
                          ]
                        }
                        """.formatted(RULE, dlqArn, dlqArn, dlqArn))
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
                .body("{\"Rule\":\"" + RULE + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Targets", hasSize(2))
                .body("Targets.find { it.Id == 'WithPolicies' }.RetryPolicy.MaximumRetryAttempts", equalTo(4))
                .body("Targets.find { it.Id == 'WithPolicies' }.RetryPolicy.MaximumEventAgeInSeconds", equalTo(120))
                .body("Targets.find { it.Id == 'WithPolicies' }.DeadLetterConfig.Arn", equalTo(dlqArn))
                .body("Targets.find { it.Id == 'Plain' }", not(hasKey("RetryPolicy")))
                .body("Targets.find { it.Id == 'Plain' }", not(hasKey("DeadLetterConfig")));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.RemoveTargets")
                .body("{\"Rule\":\"" + RULE + "\",\"Ids\":[\"WithPolicies\",\"Plain\"]}")
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(3)
    void putTargetsRejectsEventAgeBelowMinimum() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "%s",
                          "Targets": [{
                            "Id": "TooYoung",
                            "Arn": "%s",
                            "RetryPolicy": {"MaximumEventAgeInSeconds": 0}
                          }]
                        }
                        """.formatted(RULE, dlqArn))
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '0' at "
                        + "'targets.1.member.retryPolicy.maximumEventAgeInSeconds' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 60"));
    }

    @Test
    @Order(4)
    void undeliverableEventLandsInTheDeadLetterQueue() {
        String missingQueueArn = dlqArn.replace("eb-retry-dlq", "eb-retry-missing-queue");
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "%s",
                          "Targets": [{
                            "Id": "MissingQueue",
                            "Arn": "%s",
                            "DeadLetterConfig": {"Arn": "%s"}
                          }]
                        }
                        """.formatted(RULE, missingQueueArn, dlqArn))
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {
                          "Entries": [{
                            "Source": "test.retry",
                            "DetailType": "RetryTest",
                            "Detail": "{\\"orderId\\":\\"o-7\\"}"
                          }]
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        JsonPath received = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + dlqUrl + "\",\"MaxNumberOfMessages\":10,"
                        + "\"MessageAttributeNames\":[\"All\"]}")
                .when().post("/")
                .then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].MessageAttributes.RULE_ARN.StringValue", equalTo(ruleArn))
                .body("Messages[0].MessageAttributes.TARGET_ARN.StringValue", equalTo(missingQueueArn))
                .body("Messages[0].MessageAttributes.ERROR_CODE.StringValue", equalTo("NO_RESOURCE"))
                .body("Messages[0].MessageAttributes.ERROR_MESSAGE.StringValue", not(emptyOrNullString()))
                .body("Messages[0].MessageAttributes.RETRY_ATTEMPTS.StringValue", equalTo("0"))
                .body("Messages[0].MessageAttributes.RETRY_ATTEMPTS.DataType", equalTo("String"))
                .body("Messages[0].MessageAttributes", not(hasKey("EXHAUSTED_RETRY_CONDITION")))
                .extract().jsonPath();

        JsonPath event = new JsonPath(received.getString("Messages[0].Body"));
        assertEquals("test.retry", event.getString("source"));
        assertEquals("RetryTest", event.getString("detail-type"));
        assertEquals("o-7", event.getString("detail.orderId"));
    }
}
