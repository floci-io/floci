package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
class CloudFormationResourceConditionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * A condition naming another with anything but a string is a template error, which
     * CloudFormation (measured in us-east-2) answers synchronously on CreateStack. The malformed
     * operand comes first in an Fn::And, where evaluation alone would stop and read it as false.
     */
    @Test
    void aConditionReferenceThatIsNotAStringIsATemplateError() {
        String template = """
                {
                  "Conditions": {
                    "Deploy": { "Fn::Equals": ["a", "a"] },
                    "Other": { "Fn::Equals": ["a", "b"] },
                    "Both": { "Fn::And": [ { "Condition": { "Condition": "Deploy" } }, { "Condition": "Other" } ] }
                  },
                  "Resources": {
                    "Queue": { "Type": "AWS::SQS::Queue", "Condition": "Both" },
                    "Always": { "Type": "AWS::SQS::Queue" }
                  }
                }
                """;
        String stackName = "cfn-cond-malformed-" + Long.toString(System.nanoTime(), 36);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Template error: every Condition object must have a single String value."));
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    /**
     * Fn::FindInMap inside Fn::Equals resolves through the template's mappings. Read as an empty
     * string, a mapped "false" made UseCode true, and the bucket was named after the mapped value.
     */
    @ParameterizedTest
    @CsvSource({"false,us-east-1", "iad,iad"})
    void mappedConditionSelectsTheBucketName(String mappedValue, String expectedLocation) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-mapped-condition-" + suffix;
        String prefix = "mapped-condition-" + suffix + "-";
        createStack(stackName, """
                {
                  "Mappings": {"Locations": {"us-east-1": {"Code": "%s"}}},
                  "Conditions": {
                    "UseCode": {"Fn::Not": [{"Fn::Equals": [
                      {"Fn::FindInMap": ["Locations", {"Ref": "AWS::Region"}, "Code"]}, "false"
                    ]}]}
                  },
                  "Resources": {
                    "Bucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": {"BucketName": {"Fn::Join": ["", ["%s",
                        {"Fn::If": ["UseCode",
                          {"Fn::FindInMap": ["Locations", {"Ref": "AWS::Region"}, "Code"]},
                          {"Ref": "AWS::Region"}]}
                      ]]}}
                    }
                  }
                }
                """.formatted(mappedValue, prefix));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            given().when().head("/" + prefix + expectedLocation).then().statusCode(200);
            given().when().head("/" + prefix + "false").then().statusCode(404);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        given().when().head("/" + prefix + expectedLocation).then().statusCode(404);
    }

    /**
     * The update's change-set preview and its execution evaluate the mapped condition as the
     * create does, so a mapping edit that flips it reaches the property chosen by its Fn::If.
     */
    @Test
    void mappedConditionIsEvaluatedAgainOnUpdate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-mapped-update-" + suffix;
        String queueName = "mapped-update-" + suffix;
        String template = """
                {
                  "Mappings": {"Locations": {"us-east-1": {"Code": "%s"}}},
                  "Conditions": {
                    "UseCode": {"Fn::Not": [{"Fn::Equals": [
                      {"Fn::FindInMap": ["Locations", {"Ref": "AWS::Region"}, "Code"]}, "false"
                    ]}]}
                  },
                  "Resources": {
                    "Queue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "%s", "VisibilityTimeout": {"Fn::If": ["UseCode", 60, 30]}}
                    }
                  }
                }
                """;
        createStack(stackName, template.formatted("false", queueName));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals("30", visibilityTimeout(queueName));

            updateStack(stackName, template.formatted("iad", queueName));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals("60", visibilityTimeout(queueName));

            updateStack(stackName, template.formatted("false", queueName));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals("30", visibilityTimeout(queueName));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
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
    void updateStack_whenConditionDeletionFails_keepsResourceTrackedAsDeleteFailed() {
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
                // The cleanup delete failed (bucket non-empty), so the resource must remain under
                // stack management as DELETE_FAILED instead of being dropped and orphaned.
                .body(containsString("<LogicalResourceId>OptionalBucket</LogicalResourceId>"))
                .body(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"));

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
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
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

    private static Response getQueueUrl(String queueName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
        .when()
            .post("/");
    }

    /**
     * A create's dependency preflight evaluates {@code AWS::StackId} as the id the stack is given,
     * so it agrees with the execution: a dependency on a resource the stack id excludes is refused
     * up front, and one on a resource it includes deploys.
     */
    @Test
    void aCreatesPreflightSeesTheStackIdItWillGet() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String template = """
                {
                  "Conditions": {
                    "NoId": {"Fn::Equals": [{"Ref": "AWS::StackId"}, ""]},
                    "HasId": {"Fn::Not": [{"Condition": "NoId"}]}
                  },
                  "Resources": {
                    "Dependency": {"Type": "AWS::SQS::Queue", "Condition": "%s",
                      "Properties": {"QueueName": "%s-dependency"}},
                    "Dependent": {"Type": "AWS::SQS::Queue", "DependsOn": "Dependency",
                      "Properties": {"QueueName": "%s-dependent"}}
                  }
                }
                """;
        String excluded = "cfn-stackid-excluded-" + suffix;
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", excluded)
            .formParam("TemplateBody", template.formatted("NoId", excluded, excluded))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", containsString("Unresolved resource dependencies"));

        String included = "cfn-stackid-included-" + suffix;
        createStack(included, template.formatted("HasId", included, included));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(included).status());
            getQueueUrl(included + "-dependency").then().statusCode(200);
            getQueueUrl(included + "-dependent").then().statusCode(200);
        } finally {
            deleteStack(included);
            CfnStackWaits.awaitStackDeleted(included);
        }
    }

    /**
     * A create over a name in use is checked as the new stack it asks for, not as the stack that
     * holds the name, so a template that is valid for a new stack gets AlreadyExistsException.
     */
    @Test
    void aCreateOverATakenNameIsCheckedAsTheNewStack() {
        String stackName = "cfn-stackid-taken-" + Long.toString(System.nanoTime(), 36);
        createStack(stackName, """
                {"Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "%s"}}}}
                """.formatted(stackName));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            String takenId = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().xmlPath().getString("**.find { it.name() == 'StackId' }");
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", """
                        {
                          "Conditions": {"NotTheTakenStack": {"Fn::Not": [{"Fn::Equals": [
                            {"Ref": "AWS::StackId"}, "%s"]}]}},
                          "Resources": {
                            "Dependency": {"Type": "AWS::SQS::Queue", "Condition": "NotTheTakenStack"},
                            "Dependent": {"Type": "AWS::SQS::Queue", "DependsOn": "Dependency"}
                          }
                        }
                        """.formatted(takenId))
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("AlreadyExistsException"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    /**
     * The change-set preview, the preflight and the execution all evaluate {@code AWS::StackName}
     * against the stack. Read as an empty string, the condition was false and the stack held no
     * resources; with only the preview reading it so, the update would skip the queue's edit.
     */
    @Test
    void stackNameConditionHoldsAcrossAnUpdateThatEditsItsResource() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-stackname-condition-" + suffix;
        String queueName = "stackname-condition-" + suffix;
        String template = """
                {
                  "Conditions": {"IsThisStack": {"Fn::Equals": [{"Ref": "AWS::StackName"}, "%s"]}},
                  "Resources": {
                    "Queue": {
                      "Type": "AWS::SQS::Queue", "Condition": "IsThisStack",
                      "Properties": {"QueueName": "%s", "VisibilityTimeout": %d}
                    }
                  }
                }
                """;
        createStack(stackName, template.formatted(stackName, queueName, 30));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals("30", visibilityTimeout(queueName));

            updateStack(stackName, template.formatted(stackName, queueName, 60));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals("60", visibilityTimeout(queueName));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    /**
     * CloudFormation allows only Ref, Fn::FindInMap and the condition functions inside a
     * condition, so a template that uses Fn::ImportValue there is refused, even though the
     * exporting stack has the export.
     */
    @Test
    void importValueInAConditionIsRejectedAsUnsupported() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String exporter = "cfn-cond-exporter-" + suffix;
        String exportName = "cfn-cond-export-" + suffix;
        String importer = "cfn-cond-importer-" + suffix;
        createStack(exporter, """
                {"Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "%s"}}},
                 "Outputs": {"Flag": {"Value": "on", "Export": {"Name": "%s"}}}}
                """.formatted("cond-export-queue-" + suffix, exportName));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(exporter).status());
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", importer)
                .formParam("TemplateBody", """
                        {"Conditions": {"IsOn": {"Fn::Equals": [{"Fn::ImportValue": "%s"}, "on"]}},
                         "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsOn"}}}
                        """.formatted(exportName))
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("Template error: Cannot use Fn::ImportValue in Conditions."));
        } finally {
            deleteStack(importer);
            deleteStack(exporter);
            CfnStackWaits.awaitStackDeleted(exporter);
        }
    }

    /** An import in an Fn::Or operand that evaluation never reaches is still a template error. */
    @Test
    void importValueInAConditionOperandEvaluationSkipsIsStillRejected() {
        String stackName = "cfn-cond-or-import-" + Long.toString(System.nanoTime(), 36);
        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", """
                        {"Conditions": {"IsOn": {"Fn::Or": [{"Fn::Equals": ["a", "a"]},
                           {"Fn::Equals": [{"Fn::ImportValue": "never-resolved"}, "on"]}]}},
                         "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsOn"}}}
                        """)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("Template error: Cannot use Fn::ImportValue in Conditions."));
        } finally {
            deleteStack(stackName);
        }
    }

    /**
     * A SAM template's conditions are checked after the transform, so an import there fails the
     * change set with the template error, as a non-string condition reference does.
     */
    @Test
    void importValueInASamTemplateConditionFailsTheChangeSet() {
        String stackName = "cfn-cond-sam-import-" + Long.toString(System.nanoTime(), 36);
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "deploy")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", """
                    {"Transform": "AWS::Serverless-2016-10-31",
                     "Conditions": {"IsOn": {"Fn::Equals": [{"Fn::ImportValue": "never-resolved"}, "on"]}},
                     "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsOn"}}}
                    """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", "deploy")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeChangeSetResponse.DescribeChangeSetResult.Status", equalTo("FAILED"))
                .body("DescribeChangeSetResponse.DescribeChangeSetResult.StatusReason",
                        equalTo("Template error: Cannot use Fn::ImportValue in Conditions."));
        } finally {
            deleteStack(stackName);
        }
    }

    /**
     * {@code AWS::NotificationARNs} is the empty list, in a condition and in an output: Floci keeps
     * no notification ARNs, and the template engine would otherwise answer the Ref with its name.
     */
    @Test
    void notificationArnsAreAnEmptyList() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-notification-arns-" + suffix;
        String queueName = "notification-arns-" + suffix;
        createStack(stackName, """
                {
                  "Conditions": {
                    "HasNotifications": {"Fn::Not": [{"Fn::Equals": [
                      {"Fn::Join": [",", {"Ref": "AWS::NotificationARNs"}]}, ""]}]}
                  },
                  "Resources": {
                    "Notified": {"Type": "AWS::SQS::Queue", "Condition": "HasNotifications",
                      "Properties": {"QueueName": "%s-notified"}},
                    "Always": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "%s-always"}}
                  },
                  "Outputs": {"Arns": {"Value": {"Fn::Join": [",", ["arns", {"Fn::Join": [",",
                    {"Ref": "AWS::NotificationARNs"}]}]]}}}
                }
                """.formatted(queueName, queueName));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            getQueueUrl(queueName + "-always").then().statusCode(200);
            getQueueUrl(queueName + "-notified").then().statusCode(400);
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("**.find { it.OutputKey == 'Arns' }.OutputValue", equalTo("arns,"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    /**
     * A condition operand the template engine cannot resolve, here an Fn::Sub whose template is not
     * a string, compares as an empty string, as it did before conditions went through the engine:
     * the stack deploys, and the dependency checks still run, rather than the request failing with
     * a 500 or the stack with the engine's exception.
     */
    @Test
    void aConditionOperandTheEngineCannotResolveComparesAsEmpty() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-malformed-sub-" + suffix;
        String queueName = "cond-malformed-sub-" + suffix;
        String condition = """
                "Conditions": {"IsBlank": {"Fn::Equals": [{"Fn::Sub": [{"Ref": "AWS::Region"}, {}]}, ""]}}""";
        createStack(stackName, """
                {%s,
                 "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsBlank",
                   "Properties": {"QueueName": "%s"}}}}
                """.formatted(condition, queueName));
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            getQueueUrl(queueName).then().statusCode(200);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName + "-cycle")
            .formParam("TemplateBody", """
                    {%s,
                     "Resources": {
                       "A": {"Type": "AWS::SQS::Queue", "DependsOn": "B"},
                       "B": {"Type": "AWS::SQS::Queue", "DependsOn": "A"}}}
                    """.formatted(condition))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Message", containsString("Circular dependency between resources"));
    }

    private static String visibilityTimeout(String queueName) {
        String queueUrl = getQueueUrl(queueName).then().statusCode(200).extract().xmlPath()
                .getString("GetQueueUrlResponse.GetQueueUrlResult.QueueUrl");
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("AttributeName.1", "VisibilityTimeout")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getString("**.find { it.Name == 'VisibilityTimeout' }.Value");
    }

    @Test
    void createStack_failsWhenResourceReferencesConditionFalseResourceViaGetAtt() {
        // Verified against real AWS: an unguarded Fn::GetAtt to a condition-false resource is
        // rejected the same way as Ref/Fn::Sub ("Unresolved resource dependencies [SkippedQueue]").
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-getatt-" + suffix;
        String skippedQueueName = "skipped-getatt-" + suffix;
        String dependentQueueName = "dependent-getatt-" + suffix;

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
                        "QueueName": { "Fn::GetAtt": ["SkippedQueue", "QueueName"] }
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

        getQueueUrl(dependentQueueName).then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
    }

    @Test
    void createStack_failsWhenDependsOnArrayContainsConditionFalseResource() {
        // The DependsOn array form must be rejected identically to the string form when any listed
        // target is excluded by a false Condition.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-dep-arr-" + suffix;
        String presentQueueName = "present-arr-" + suffix;
        String skippedQueueName = "skipped-arr-" + suffix;
        String dependentQueueName = "dependent-arr-" + suffix;

        String template = """
                {
                  "Conditions": {
                    "IsFalse": { "Fn::Equals": ["a", "b"] }
                  },
                  "Resources": {
                    "PresentQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": { "QueueName": "%s" }
                    },
                    "SkippedQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Condition": "IsFalse",
                      "Properties": { "QueueName": "%s" }
                    },
                    "DependentQueue": {
                      "Type": "AWS::SQS::Queue",
                      "DependsOn": ["PresentQueue", "SkippedQueue"],
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(presentQueueName, skippedQueueName, dependentQueueName);

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

        // The whole template is rejected before any resource is provisioned.
        getQueueUrl(presentQueueName).then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
        getQueueUrl(dependentQueueName).then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
    }

    @Test
    void updateStack_failsWhenResourceDependsOnConditionFalseResourceAndLeavesStackUnchanged() {
        // The same synchronous validation that guards CreateStack must guard UpdateStack: an invalid
        // condition-dependency graph is rejected before any stack state is mutated, so the existing
        // stack and its resources are left intact.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-upd-dep-" + suffix;
        String keepQueueName = "keep-dep-" + suffix;
        String skippedQueueName = "skipped-upd-dep-" + suffix;
        String dependentQueueName = "dependent-upd-dep-" + suffix;

        String initialTemplate = """
                {
                  "Resources": {
                    "KeepQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(keepQueueName);
        String invalidTemplate = """
                {
                  "Conditions": {
                    "IsFalse": { "Fn::Equals": ["a", "b"] }
                  },
                  "Resources": {
                    "KeepQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": { "QueueName": "%s" }
                    },
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
                """.formatted(keepQueueName, skippedQueueName, dependentQueueName);

        createStack(stackName, initialTemplate);
        try {
            getQueueUrl(keepQueueName).then().statusCode(200).body(containsString(keepQueueName));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UpdateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", invalidTemplate)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("Unresolved resource dependencies"))
                .body("ErrorResponse.Error.Message", containsString("SkippedQueue"));

            // The existing stack is untouched: still UPDATE-able, still holding only KeepQueue.
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LogicalResourceId>KeepQueue</LogicalResourceId>"))
                .body(not(containsString("<LogicalResourceId>DependentQueue</LogicalResourceId>")))
                .body(not(containsString("<LogicalResourceId>SkippedQueue</LogicalResourceId>")));

            // The stored template was not replaced by the rejected one.
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetTemplate")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("SkippedQueue")));

            getQueueUrl(keepQueueName).then().statusCode(200).body(containsString(keepQueueName));
            getQueueUrl(dependentQueueName).then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void updateStack_failsWhenResourceReferencesConditionFalseResourceViaRefAndLeavesStackUnchanged() {
        // Same as above but through an unguarded Ref/Fn::Sub reference rather than DependsOn.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-upd-ref-" + suffix;
        String keepQueueName = "keep-ref-" + suffix;
        String skippedQueueName = "skipped-upd-ref-" + suffix;
        String dependentQueueName = "dependent-upd-ref-" + suffix;

        String initialTemplate = """
                {
                  "Resources": {
                    "KeepQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(keepQueueName);
        String invalidTemplate = """
                {
                  "Conditions": {
                    "IsFalse": { "Fn::Equals": ["a", "b"] }
                  },
                  "Resources": {
                    "KeepQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": { "QueueName": "%s" }
                    },
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
                """.formatted(keepQueueName, skippedQueueName, dependentQueueName);

        createStack(stackName, initialTemplate);
        try {
            getQueueUrl(keepQueueName).then().statusCode(200).body(containsString(keepQueueName));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UpdateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", invalidTemplate)
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
                .statusCode(200)
                .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

            getQueueUrl(keepQueueName).then().statusCode(200).body(containsString(keepQueueName));
            getQueueUrl(dependentQueueName).then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void deleteStack_afterConditionDeletionFailure_retriesAndReclaimsResource() {
        // When a condition-driven cleanup delete fails (bucket non-empty), the resource stays under
        // stack management as DELETE_FAILED. Once the blocker is cleared, a later DeleteStack must
        // retry the deletion and reclaim the backing resource instead of leaving it orphaned.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cond-del-orphan-" + suffix;
        String bucketName = "condition-orphan-" + suffix;

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
            given().header("Host", bucketName + ".localhost").when().get("/").then().statusCode(200);
            given()
                .contentType("text/plain")
                .body("prevent condition-disabled bucket deletion")
            .when()
                .put("/" + bucketName + "/object.txt")
            .then()
                .statusCode(200);

            // Cleanup delete fails (bucket non-empty), but the update still completes; the bucket
            // stays under stack management as DELETE_FAILED.
            updateStack(stackName, disabledTemplate);
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LogicalResourceId>OptionalBucket</LogicalResourceId>"))
                .body(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"));

            // Clear the blocker, then DeleteStack must retry the still-tracked DELETE_FAILED resource
            // and reclaim the backing bucket rather than orphaning it.
            given().header("Host", bucketName + ".localhost").delete("/object.txt").then().statusCode(204);
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
            given().header("Host", bucketName + ".localhost").when().get("/").then().statusCode(404);
        } finally {
            given().header("Host", bucketName + ".localhost").delete("/object.txt");
            given().header("Host", bucketName + ".localhost").delete("/");
            deleteStack(stackName);
        }
    }

    @Test
    void updateStack_deletesResourceWhenRemovedFromTemplate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rem-update-" + suffix;
        String queueName = "removed-update-" + suffix;

        String initialTemplate = """
                {
                  "Resources": {
                    "OptionalQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(queueName);
        String updatedTemplate = """
                {
                  "Resources": {
                    "DummyQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": { "QueueName": "dummy-%s" }
                    }
                  }
                }
                """.formatted(suffix);

        createStack(stackName, initialTemplate);
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

            updateStack(stackName, updatedTemplate);

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
    void updateStack_whenResourceRemovedFromTemplateWithNonEmptyBucket_keepsBucketAsDeleteFailedAndReclaimsOnDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rem-orphan-" + suffix;
        String bucketName = "removed-orphan-" + suffix;

        String initialTemplate = """
                {
                  "Resources": {
                    "OptionalBucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": { "BucketName": "%s" }
                    }
                  }
                }
                """.formatted(bucketName);
        String updatedTemplate = """
                {
                  "Resources": {
                  }
                }
                """;

        createStack(stackName, initialTemplate);
        try {
            given().header("Host", bucketName + ".localhost").when().get("/").then().statusCode(200);
            given()
                .contentType("text/plain")
                .body("prevent removed bucket deletion")
            .when()
                .put("/" + bucketName + "/object.txt")
            .then()
                .statusCode(200);

            // Cleanup delete fails (bucket non-empty), but update completes; bucket stays under stack management as DELETE_FAILED.
            updateStack(stackName, updatedTemplate);
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
                .body(containsString("could not be deleted during update cleanup"));

            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LogicalResourceId>OptionalBucket</LogicalResourceId>"))
                .body(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"));

            // Clear the blocker, then DeleteStack retries the DELETE_FAILED resource
            given().header("Host", bucketName + ".localhost").delete("/object.txt").then().statusCode(204);
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
            given().header("Host", bucketName + ".localhost").when().get("/").then().statusCode(404);
        } finally {
            given().header("Host", bucketName + ".localhost").delete("/object.txt");
            given().header("Host", bucketName + ".localhost").delete("/");
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
