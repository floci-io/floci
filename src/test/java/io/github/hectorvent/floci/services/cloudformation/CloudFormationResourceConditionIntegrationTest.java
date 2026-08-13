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

    @Test
    void updateStack_whenConditionDeletionFails_completesAndLeavesResourceUnmanaged() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-delete-fail-" + suffix;
        String bucketName = "condition-delete-fail-" + suffix;

        String enabledTemplate = """
                {
                  "Conditions": {
                    "Enabled": { "Fn::Equals": ["enabled", "enabled"] }
                  },
                  "Resources": {
                    "OptionalBucket": {
                      "Type": "AWS::S3::Bucket",
                      "Condition": "Enabled",
                      "Properties": { "BucketName": "%s" }
                    }
                  }
                }
                """.formatted(bucketName);
        String disabledTemplate = """
                {
                  "Conditions": {
                    "Enabled": { "Fn::Equals": ["enabled", "disabled"] }
                  },
                  "Resources": {
                    "OptionalBucket": {
                      "Type": "AWS::S3::Bucket",
                      "Condition": "Enabled",
                      "Properties": { "BucketName": "%s" }
                    }
                  }
                }
                """.formatted(bucketName);

        createStack(stackName, enabledTemplate);
        try {
            given()
                .header("Host", bucketName + ".localhost")
            .when()
                .get("/")
            .then()
                .statusCode(200);

            given()
                .contentType("text/plain")
                .body("prevent condition-disabled bucket deletion")
            .when()
                .put("/" + bucketName + "/object.txt")
            .then()
                .statusCode(200);

            updateStack(stackName, disabledTemplate);

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
                .body(containsString("<StackStatusReason>"))
                .body(containsString("OptionalBucket"));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<LogicalResourceId>OptionalBucket</LogicalResourceId>")));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackEvents")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LogicalResourceId>OptionalBucket</LogicalResourceId>"))
                .body(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"))
                .body(containsString("<ResourceStatusReason>"));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetTemplate")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("[&quot;enabled&quot;, &quot;disabled&quot;]"))
                .body(not(containsString("[&quot;enabled&quot;, &quot;enabled&quot;]")));

            given()
                .header("Host", bucketName + ".localhost")
            .when()
                .get("/")
            .then()
                .statusCode(200);
        } finally {
            given().header("Host", bucketName + ".localhost").delete("/object.txt");
            given().header("Host", bucketName + ".localhost").delete("/");
            deleteStack(stackName);
        }
    }

    @Test
    void createStack_failsWhenResourceDependsOnConditionFalseResourceViaDependsOn() {
        // Verified against real AWS: CreateStack is rejected synchronously with
        // "Template format error: Unresolved resource dependencies [SkippedQueue]" — CloudFormation
        // does NOT create a DependsOn dependent (or the stack) when its target is condition-false.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-dep-on-" + suffix;
        String skippedQueueName = "skipped-" + suffix;
        String dependentQueueName = "dependent-" + suffix;

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
                    "DependentQueue": {
                      "Type": "AWS::SQS::Queue",
                      "DependsOn": "SkippedQueue",
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(skippedQueueName, dependentQueueName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", containsString("Unresolved resource dependencies"))
            .body("ErrorResponse.Error.Message", containsString("SkippedQueue"));

        // No stack is left behind, and the dependent queue was never provisioned.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", dependentQueueName)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
    }

    @Test
    void createStack_failsWhenResourceReferencesConditionFalseResourceViaRef() {
        // Verified against real AWS: an unguarded Ref/Fn::Sub to a condition-false resource is also
        // rejected with "Unresolved resource dependencies [SkippedQueue]".
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-ref-" + suffix;
        String skippedQueueName = "skipped-ref-" + suffix;
        String dependentQueueName = "dependent-ref-" + suffix;

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
                    "DependentQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {
                        "QueueName": { "Fn::Sub": "${SkippedQueue}-%s" }
                      }
                    }
                  }
                }
                """.formatted(skippedQueueName, dependentQueueName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
            .body("ErrorResponse.Error.Message", containsString("Unresolved resource dependencies"))
            .body("ErrorResponse.Error.Message", containsString("SkippedQueue"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void createStack_createsResourceWhenFnIfGuardsConditionFalseReference() {
        // Verified against real AWS: when Fn::If selects the branch that does NOT reference the
        // condition-false resource, there is no real dependency, so the dependent resource is
        // created and the stack completes.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-fnif-" + suffix;
        String skippedQueueName = "skipped-fnif-" + suffix;
        String fallbackQueueName = "fallback-fnif-" + suffix;

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
                    "DependentQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {
                        "QueueName": { "Fn::If": ["IsFalse", { "Ref": "SkippedQueue" }, "%s"] }
                      }
                    }
                  }
                }
                """.formatted(skippedQueueName, fallbackQueueName);

        createStack(stackName, template);
        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", fallbackQueueName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(fallbackQueueName));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LogicalResourceId>DependentQueue</LogicalResourceId>"))
                .body(not(containsString("<LogicalResourceId>SkippedQueue</LogicalResourceId>")));
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void updateStack_createsPreviouslySkippedResourceWhenConditionTurnsTrue() {
        // Issue #2168 follow-up, verified against real AWS: create with the condition false, then
        // update with it true. AWS re-provisions the previously-skipped resource, and PickedQueue's
        // Fn::If now resolves to the true branch. (AWS also *replaces* PickedQueue and deletes the
        // old physical queue during cleanup because SQS QueueName is immutable; that generic
        // replacement-cleanup behavior is out of scope here and is asserted only for the new name.)
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-2168-" + suffix;
        String skippedQueueName = "should-not-exist-" + suffix;
        String falseBranchName = "false-branch-" + suffix;
        String trueBranchName = "true-branch-" + suffix;

        String falseTemplate = """
                {
                  "Conditions": { "IsFalse": { "Fn::Equals": ["a", "b"] } },
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
                """.formatted(skippedQueueName, trueBranchName, falseBranchName);
        String trueTemplate = """
                {
                  "Conditions": { "IsFalse": { "Fn::Equals": ["a", "a"] } },
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
                """.formatted(skippedQueueName, trueBranchName, falseBranchName);

        createStack(stackName, falseTemplate);
        try {
            // Baseline: PickedQueue resolves to the false branch; SkippedQueue is not created.
            getQueueUrl(falseBranchName).then().statusCode(200).body(containsString(falseBranchName));
            getQueueUrl(skippedQueueName).then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));

            updateStack(stackName, trueTemplate);

            // The previously-skipped resource is created once its condition turns true.
            getQueueUrl(skippedQueueName).then().statusCode(200).body(containsString(skippedQueueName));
            // PickedQueue's Fn::If now resolves to the true branch.
            getQueueUrl(trueBranchName).then().statusCode(200).body(containsString(trueBranchName));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LogicalResourceId>SkippedQueue</LogicalResourceId>"))
                .body(containsString("<LogicalResourceId>PickedQueue</LogicalResourceId>"));
        } finally {
            deleteStack(stackName);
        }
    }

    private static io.restassured.response.Response getQueueUrl(String queueName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
        .when()
            .post("/");
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
