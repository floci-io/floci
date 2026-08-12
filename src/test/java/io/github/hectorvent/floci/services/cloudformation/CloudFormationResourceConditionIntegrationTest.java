package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFormationResourceConditionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_skipsResourceWhenResourceLevelConditionIsFalse() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-resource-" + suffix;
        String skippedQueueName = "should-not-exist-" + suffix;
        String pickedQueueName = "false-branch-" + suffix;
        String trueBranchQueueName = "true-branch-" + suffix;

        String template = """
                {
                  "Conditions": {
                    "IsFalse": { "Fn::Equals": ["a", "b"] }
                  },
                  "Resources": {
                    "SkippedQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Condition": "IsFalse",
                      "Properties": { "QueueName": "%s" }
                    },
                    "PickedQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {
                        "QueueName": { "Fn::If": ["IsFalse", "%s", "%s"] }
                      }
                    }
                  }
                }
                """.formatted(skippedQueueName, trueBranchQueueName, pickedQueueName);

        createStack(stackName, template);
        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", pickedQueueName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(pickedQueueName));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", skippedQueueName)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LogicalResourceId>PickedQueue</LogicalResourceId>"))
                .body(not(containsString("<LogicalResourceId>SkippedQueue</LogicalResourceId>")));
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void updateStack_deletesResourceWhenConditionTurnsFalse() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-update-" + suffix;
        String queueName = "condition-update-" + suffix;

        String enabledTemplate = """
                {
                  "Conditions": {
                    "Enabled": { "Fn::Equals": ["enabled", "enabled"] }
                  },
                  "Resources": {
                    "OptionalQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Condition": "Enabled",
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(queueName);
        String disabledTemplate = """
                {
                  "Conditions": {
                    "Enabled": { "Fn::Equals": ["enabled", "disabled"] }
                  },
                  "Resources": {
                    "OptionalQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Condition": "Enabled",
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(queueName);

        createStack(stackName, enabledTemplate);
        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(queueName));

            updateStack(stackName, disabledTemplate);

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueName)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<LogicalResourceId>OptionalQueue</LogicalResourceId>")));
        } finally {
            deleteStack(stackName);
        }
    }

    private static void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));
    }

    private static void updateStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
