package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class EventBridgeTargetRoleIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/eventbridge-target";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listTargetsReturnsRoleAlongsideRetryAndDeadLetterSettings() {
        String rule = "eb-target-role-round-trip";
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"" + rule + "\",\"EventPattern\":\"{}\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "%s",
                          "Targets": [{
                            "Id": "workflow",
                            "Arn": "arn:aws:states:us-east-1:000000000000:stateMachine:example",
                            "RoleArn": "%s",
                            "RetryPolicy": {"MaximumRetryAttempts": 2, "MaximumEventAgeInSeconds": 60},
                            "DeadLetterConfig": {"Arn": "arn:aws:sqs:us-east-1:000000000000:target-role-dlq"}
                          }]
                        }
                        """.formatted(rule, ROLE))
                .when().post("/").then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
                .body("{\"Rule\":\"" + rule + "\"}")
                .when().post("/").then().statusCode(200)
                .body("Targets[0].RoleArn", equalTo(ROLE))
                .body("Targets[0].RetryPolicy.MaximumRetryAttempts", equalTo(2))
                .body("Targets[0].RetryPolicy.MaximumEventAgeInSeconds", equalTo(60))
                .body("Targets[0].DeadLetterConfig.Arn",
                        equalTo("arn:aws:sqs:us-east-1:000000000000:target-role-dlq"));
    }
}
