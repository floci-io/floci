package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the resource-level {@code DeletionPolicy} attribute (issue #1555): {@code Retain} keeps a
 * resource on every stack operation, {@code RetainExceptOnCreate} keeps it everywhere except the
 * rollback of the create that made it, and any other value falls through to the default delete.
 *
 * <p>Exercised through S3 buckets — the case the attribute is most used for, and one whose survival
 * is directly observable without Docker.
 */
@QuarkusTest
class CloudFormationDeletionPolicyIntegrationTest {

    private static final String CUSTOM_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/eu-west-1/cloudformation/aws4_request";

    @Test
    void retainKeepsANonEmptyBucketAndTheStackStillCompletesTheDelete() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-retain-bucket-" + suffix;
        String stackName = "cfn-retain-stack-" + suffix;

        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "Retain",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucket);

        String stackId = createStack(stackName, template);

        given()
            .contentType("text/plain")
            .body("kept")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        deleteStack(stackName);

        // A deleted bucket would have failed on its objects instead (issue #1539), so reaching
        // DELETE_COMPLETE is itself evidence that the bucket was never touched.
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        given()
        .when()
            .get("/" + bucket + "/object.txt")
        .then()
            .statusCode(200)
            .body(containsString("kept"));

        assertThat(describeStackEvents(stackId),
                containsString("<ResourceStatus>DELETE_SKIPPED</ResourceStatus>"));
    }

    @Test
    void retainExceptOnCreateKeepsTheBucketWhenTheStackIsDeleted() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-reoc-delete-bucket-" + suffix;
        String stackName = "cfn-reoc-delete-stack-" + suffix;

        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "RetainExceptOnCreate",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucket);

        String stackId = createStack(stackName, template);
        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        assertBucketExists(bucket);
    }

    @Test
    void rollingBackAFailedCreateDeletesRetainExceptOnCreateButKeepsRetain() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String keptBucket = "cfn-rollback-retain-bucket-" + suffix;
        String rolledBackBucket = "cfn-rollback-reoc-bucket-" + suffix;
        String stackName = "cfn-rollback-policy-stack-" + suffix;

        // DependsOn forces both buckets to provision before BadSecret fails (setting SecretString
        // and GenerateSecretString together is invalid), which triggers the create rollback.
        String template = """
            {
              "Resources": {
                "KeptBucket": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "Retain",
                  "Properties": { "BucketName": "%s" }
                },
                "RolledBackBucket": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "RetainExceptOnCreate",
                  "DependsOn": "KeptBucket",
                  "Properties": { "BucketName": "%s" }
                },
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "RolledBackBucket",
                  "Properties": {
                    "Name": "cfn-rollback-policy-secret-%s",
                    "SecretString": "explicit",
                    "GenerateSecretString": { "PasswordLength": 32 }
                  }
                }
              }
            }
            """.formatted(keptBucket, rolledBackBucket, suffix);

        String stackId = createStack(stackName, template);

        assertThat(describeStacks(stackId), containsString("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"));
        assertBucketExists(keptBucket);
        assertBucketDeleted(rolledBackBucket);
    }

    @Test
    void bucketsWithoutARetainingPolicyAreStillDeleted() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String defaulted = "cfn-policy-default-bucket-" + suffix;
        String explicitDelete = "cfn-policy-delete-bucket-" + suffix;
        String unrecognized = "cfn-policy-unknown-bucket-" + suffix;
        String stackName = "cfn-policy-delete-stack-" + suffix;

        String template = """
            {
              "Resources": {
                "Defaulted": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                },
                "ExplicitDelete": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "Delete",
                  "Properties": { "BucketName": "%s" }
                },
                "Unrecognized": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "Keep",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(defaulted, explicitDelete, unrecognized);

        String stackId = createStack(stackName, template);
        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        assertBucketDeleted(defaulted);
        assertBucketDeleted(explicitDelete);
        assertBucketDeleted(unrecognized);
    }

    @Test
    void retainKeepsResourceWhenRemovedFromTemplateOnUpdate() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket1 = "cfn-update-remove-bucket1-" + suffix;
        String bucket2 = "cfn-update-remove-bucket2-" + suffix;
        String stackName = "cfn-update-remove-stack-" + suffix;

        String template1 = """
            {
              "Resources": {
                "KeptBucket": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "Retain",
                  "Properties": { "BucketName": "%s" }
                },
                "DeletedBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucket1, bucket2);

        String stackId = createStack(stackName, template1);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String template2 = """
            {
              "Resources": {
              }
            }
            """;

        updateStack(stackName, template2);
        awaitStackStatus(stackId, "UPDATE_COMPLETE");

        assertBucketExists(bucket1);
        assertBucketDeleted(bucket2);

        String resXml = cfnQuery("DescribeStackResources", stackName, null).then().statusCode(200).extract().asString();
        assertThat(resXml, not(containsString("<LogicalResourceId>KeptBucket</LogicalResourceId>")));
        assertThat(resXml, not(containsString("<LogicalResourceId>DeletedBucket</LogicalResourceId>")));

        String events = describeStackEvents(stackId);
        assertThat(events, containsString("<ResourceStatus>DELETE_SKIPPED</ResourceStatus>"));
    }

    @Test
    void retainExceptOnCreateKeepsResourceWhenRemovedFromTemplateOnUpdate() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket1 = "cfn-reoc-update-bucket1-" + suffix;
        String bucket2 = "cfn-reoc-update-bucket2-" + suffix;
        String stackName = "cfn-reoc-update-stack-" + suffix;

        String template1 = """
            {
              "Resources": {
                "KeptBucket": {
                  "Type": "AWS::S3::Bucket",
                  "DeletionPolicy": "RetainExceptOnCreate",
                  "Properties": { "BucketName": "%s" }
                },
                "DeletedBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucket1, bucket2);

        String stackId = createStack(stackName, template1);
        try {
            awaitStackStatus(stackId, "CREATE_COMPLETE");

            String template2 = """
                {
                  "Resources": {
                  }
                }
                """;

            updateStack(stackName, template2);
            awaitStackStatus(stackId, "UPDATE_COMPLETE");

            assertBucketExists(bucket1);
            assertBucketDeleted(bucket2);

            String resXml = cfnQuery("DescribeStackResources", stackName, null).then().statusCode(200).extract().asString();
            assertThat(resXml, not(containsString("<LogicalResourceId>KeptBucket</LogicalResourceId>")));
            assertThat(resXml, not(containsString("<LogicalResourceId>DeletedBucket</LogicalResourceId>")));

            String events = describeStackEvents(stackId);
            assertThat(events, containsString("<ResourceStatus>DELETE_SKIPPED</ResourceStatus>"));
        } finally {
            deleteStack(stackName);
            given().header("Host", bucket1 + ".localhost").when().delete("/");
        }
    }

    @Test
    void nestedStackIsPhysicallyDeletedWhenRemovedFromTemplateOnUpdate() throws InterruptedException {
        given().header("Authorization", CUSTOM_AUTH).when().put("/nested-stack-templates").then().statusCode(200);
        String childTemplate = "{\"Resources\": {\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}}";
        given()
            .header("Authorization", CUSTOM_AUTH)
            .contentType("application/json")
            .body(childTemplate)
        .when()
            .put("/nested-stack-templates/child-update.json")
        .then()
            .statusCode(200);

        String stackName = "parent-stack-" + System.nanoTime();
        String template1 = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child-update.json" }
                }
              }
            }
            """;

        String parentStackId = createStack(stackName, template1, CUSTOM_AUTH);
        try {
            awaitStackStatus(parentStackId, "CREATE_COMPLETE", CUSTOM_AUTH);

            String nestedStackId = getNestedStackId(parentStackId, "ChildStack", CUSTOM_AUTH);
            assertThat(nestedStackId, containsString("111122223333"));
            assertThat(nestedStackId, containsString("eu-west-1"));

            String template2 = "{\"Resources\": {}}";
            updateStack(stackName, template2, CUSTOM_AUTH);
            awaitStackStatus(parentStackId, "UPDATE_COMPLETE", CUSTOM_AUTH);

            awaitStackStatus(nestedStackId, "DELETE_COMPLETE", CUSTOM_AUTH);
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void nestedStackIsRetainedWhenRemovedFromTemplateOnUpdateWithRetainPolicy() throws InterruptedException {
        given().when().put("/nested-stack-templates").then().statusCode(200);
        String childTemplate = "{\"Resources\": {\"Queue\": {\"Type\": \"AWS::SQS::Queue\"}}}";
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child-retain.json");

        String stackName = "parent-retain-" + System.nanoTime();
        String template1 = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "DeletionPolicy": "Retain",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child-retain.json" }
                }
              }
            }
            """;

        String parentStackId = createStack(stackName, template1, null);
        String nestedStackId = null;
        try {
            awaitStackStatus(parentStackId, "CREATE_COMPLETE", null);
            nestedStackId = getNestedStackId(parentStackId, "ChildStack", null);

            String template2 = "{\"Resources\": {}}";
            updateStack(stackName, template2, null);
            awaitStackStatus(parentStackId, "UPDATE_COMPLETE", null);

            awaitStackStatus(nestedStackId, "CREATE_COMPLETE", null);

            String resXml = cfnQuery("DescribeStackResources", stackName, null).then().statusCode(200).extract().asString();
            assertThat(resXml, not(containsString("<LogicalResourceId>ChildStack</LogicalResourceId>")));
        } finally {
            deleteStack(stackName);
            if (nestedStackId != null) {
                deleteStack(nestedStackId);
            }
        }
    }

    @Test
    void nestedStackWithNonEmptyBucketFailsDeletionOnUpdate_leavesChildAsDeleteFailedAndTracksInParent() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketName = "cfn-nested-orphan-" + suffix;
        given().when().put("/nested-stack-templates").then().statusCode(200);
        String childTemplate = """
            {
              "Resources": {
                "ChildBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucketName);
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child-orphan-" + suffix + ".json");

        String stackName = "parent-orphan-" + suffix;
        String template1 = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child-orphan-%s.json" }
                }
              }
            }
            """.formatted(suffix);

        String parentStackId = createStack(stackName, template1);
        try {
            awaitStackStatus(parentStackId, "CREATE_COMPLETE");
            String nestedStackId = getNestedStackId(parentStackId, "ChildStack", null);

            // Put an object in the nested bucket so nested stack deletion will fail
            given().contentType("text/plain").body("keep").when().put("/" + bucketName + "/object.txt").then().statusCode(200);

            String template2 = "{\"Resources\": {}}";
            updateStack(stackName, template2);
            awaitStackStatus(parentStackId, "UPDATE_COMPLETE");
            assertThat(describeStacks(parentStackId), containsString("could not be deleted during update cleanup"));

            // Nested stack resource stays in parent as DELETE_FAILED
            String resXml = cfnQuery("DescribeStackResources", stackName, null).then().statusCode(200).extract().asString();
            assertThat(resXml, containsString("<LogicalResourceId>ChildStack</LogicalResourceId>"));
            assertThat(resXml, containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"));

            // Child stack itself is DELETE_FAILED
            awaitStackStatus(nestedStackId, "DELETE_FAILED");

            // Clean up bucket and delete parent stack to verify clean recovery
            given().when().delete("/" + bucketName + "/object.txt").then().statusCode(204);
            deleteStack(stackName);
            awaitStackStatus(parentStackId, "DELETE_COMPLETE");
            awaitStackStatus(nestedStackId, "DELETE_COMPLETE");
            assertBucketDeleted(bucketName);
        } finally {
            given().when().delete("/" + bucketName + "/object.txt");
            deleteStack(stackName);
        }
    }

    @Test
    void deleteStack_withNestedStack_deletesChildStackAndPhysicalResources() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketName = "cfn-nested-del-" + suffix;
        given().when().put("/nested-stack-templates").then().statusCode(200);
        String childTemplate = """
            {
              "Resources": {
                "ChildBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucketName);
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child-del-" + suffix + ".json");

        String stackName = "parent-del-" + suffix;
        String template = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child-del-%s.json" }
                }
              }
            }
            """.formatted(suffix);

        String parentStackId = createStack(stackName, template);
        try {
            awaitStackStatus(parentStackId, "CREATE_COMPLETE");
            String nestedStackId = getNestedStackId(parentStackId, "ChildStack", null);
            assertBucketExists(bucketName);

            deleteStack(stackName);
            awaitStackStatus(parentStackId, "DELETE_COMPLETE");
            awaitStackStatus(nestedStackId, "DELETE_COMPLETE");
            assertBucketDeleted(bucketName);
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void deleteStack_withNestedStackContainingNonEmptyBucket_failsAndLeavesBothStacksInDeleteFailed() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketName = "cfn-nested-delfail-" + suffix;
        given().when().put("/nested-stack-templates").then().statusCode(200);
        String childTemplate = """
            {
              "Resources": {
                "ChildBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucketName);
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child-delfail-" + suffix + ".json");

        String stackName = "parent-delfail-" + suffix;
        String template = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child-delfail-%s.json" }
                }
              }
            }
            """.formatted(suffix);

        String parentStackId = createStack(stackName, template);
        try {
            awaitStackStatus(parentStackId, "CREATE_COMPLETE");
            String nestedStackId = getNestedStackId(parentStackId, "ChildStack", null);

            // Put object in child bucket
            given().contentType("text/plain").body("blocker").when().put("/" + bucketName + "/object.txt").then().statusCode(200);

            deleteStack(stackName);
            awaitStackStatus(parentStackId, "DELETE_FAILED");
            awaitStackStatus(nestedStackId, "DELETE_FAILED");
            assertBucketExists(bucketName);

            // Clean up object and retry DeleteStack
            given().when().delete("/" + bucketName + "/object.txt").then().statusCode(204);
            deleteStack(stackName);
            awaitStackStatus(parentStackId, "DELETE_COMPLETE");
            awaitStackStatus(nestedStackId, "DELETE_COMPLETE");
            assertBucketDeleted(bucketName);
        } finally {
            given().when().delete("/" + bucketName + "/object.txt");
            deleteStack(stackName);
        }
    }

    @Test
    void createStack_rollbackWithNestedStack_deletesChildStackAndResources() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketName = "cfn-nested-createrb-" + suffix;
        given().when().put("/nested-stack-templates").then().statusCode(200);
        String childTemplate = """
            {
              "Resources": {
                "ChildBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucketName);
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child-createrb-" + suffix + ".json");

        String stackName = "parent-createrb-" + suffix;
        String template = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child-createrb-%s.json" }
                },
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "ChildStack",
                  "Properties": {
                    "Name": "bad-secret-%s",
                    "SecretString": "explicit",
                    "GenerateSecretString": { "PasswordLength": 32 }
                  }
                }
              }
            }
            """.formatted(suffix, suffix);

        String parentStackId = createStack(stackName, template);
        try {
            assertThat(describeStacks(parentStackId), containsString("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"));
            assertBucketDeleted(bucketName);
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void updateStack_rollbackWithNestedStack_deletesNewlyAddedChildStack() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketName = "cfn-nested-updaterb-" + suffix;
        given().when().put("/nested-stack-templates").then().statusCode(200);
        String childTemplate = """
            {
              "Resources": {
                "ChildBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucketName);
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child-updaterb-" + suffix + ".json");

        String stackName = "parent-updaterb-" + suffix;
        String sfnName = "initial-sfn-" + suffix;
        String initialTemplate = """
            {
              "Resources": {
                "InitialStateMachine": {
                  "Type": "AWS::StepFunctions::StateMachine",
                  "Properties": {
                    "StateMachineName": "%s",
                    "RoleArn": "arn:aws:iam::000000000000:role/cfn-sfn-rollback-role",
                    "DefinitionString": "{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"Result\\":\\"marker-v1\\",\\"End\\":true}}}"
                  }
                }
              }
            }
            """.formatted(sfnName);

        String parentStackId = createStack(stackName, initialTemplate);
        try {
            awaitStackStatus(parentStackId, "CREATE_COMPLETE");

            String failingUpdateTemplate = """
                {
                  "Resources": {
                    "InitialStateMachine": {
                      "Type": "AWS::StepFunctions::StateMachine",
                      "Properties": {
                        "StateMachineName": "%s",
                        "RoleArn": "arn:aws:iam::000000000000:role/cfn-sfn-rollback-role",
                        "DefinitionString": "{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"Result\\":\\"marker-v2\\",\\"End\\":true}}}"
                      }
                    },
                    "ChildStack": {
                      "Type": "AWS::CloudFormation::Stack",
                      "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child-updaterb-%s.json" }
                    },
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "DependsOn": "ChildStack",
                      "Properties": {
                        "Name": "bad-secret-update-%s",
                        "SecretString": "explicit",
                        "GenerateSecretString": { "PasswordLength": 32 }
                      }
                    }
                  }
                }
                """.formatted(sfnName, suffix, suffix);

            updateStack(stackName, failingUpdateTemplate);
            awaitStackStatus(parentStackId, "UPDATE_ROLLBACK_COMPLETE");
            assertBucketDeleted(bucketName);

            String resXml = cfnQuery("DescribeStackResources", stackName, null).then().statusCode(200).extract().asString();
            assertThat(resXml, containsString("<LogicalResourceId>InitialStateMachine</LogicalResourceId>"));
            assertThat(resXml, not(containsString("<LogicalResourceId>ChildStack</LogicalResourceId>")));
            assertThat(resXml, not(containsString("<LogicalResourceId>BadSecret</LogicalResourceId>")));

            deleteStack(stackName);
            awaitStackStatus(parentStackId, "DELETE_COMPLETE");
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void nestedStackWithMultiLevelHierarchy_deletesRecursivelyOnUpdate() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketName = "cfn-nested3-del-" + suffix;
        given().when().put("/nested-stack-templates").then().statusCode(200);

        String grandchildTemplate = """
            {
              "Resources": {
                "GrandchildBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucketName);
        given().contentType("application/json").body(grandchildTemplate).when().put("/nested-stack-templates/grandchild-" + suffix + ".json");

        String childTemplate = """
            {
              "Resources": {
                "GrandchildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/grandchild-%s.json" }
                }
              }
            }
            """.formatted(suffix);
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child3-" + suffix + ".json");

        String rootStackName = "root3-del-" + suffix;
        String rootTemplate = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child3-%s.json" }
                }
              }
            }
            """.formatted(suffix);

        String rootStackId = createStack(rootStackName, rootTemplate);
        try {
            awaitStackStatus(rootStackId, "CREATE_COMPLETE");
            String childStackId = getNestedStackId(rootStackId, "ChildStack", null);
            String grandchildStackId = getNestedStackId(childStackId, "GrandchildStack", null);
            assertBucketExists(bucketName);

            String updatedRootTemplate = "{\"Resources\": {}}";
            updateStack(rootStackName, updatedRootTemplate);
            awaitStackStatus(rootStackId, "UPDATE_COMPLETE");

            awaitStackStatus(childStackId, "DELETE_COMPLETE");
            awaitStackStatus(grandchildStackId, "DELETE_COMPLETE");
            assertBucketDeleted(bucketName);
        } finally {
            deleteStack(rootStackName);
        }
    }

    @Test
    void nestedStackWithMultiLevelHierarchy_propagatesDeleteFailedOnUpdate() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketName = "cfn-nested3-fail-" + suffix;
        given().when().put("/nested-stack-templates").then().statusCode(200);

        String grandchildTemplate = """
            {
              "Resources": {
                "GrandchildBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                }
              }
            }
            """.formatted(bucketName);
        given().contentType("application/json").body(grandchildTemplate).when().put("/nested-stack-templates/grandchild-fail-" + suffix + ".json");

        String childTemplate = """
            {
              "Resources": {
                "GrandchildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/grandchild-fail-%s.json" }
                }
              }
            }
            """.formatted(suffix);
        given().contentType("application/json").body(childTemplate).when().put("/nested-stack-templates/child3-fail-" + suffix + ".json");

        String rootStackName = "root3-fail-" + suffix;
        String rootTemplate = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": { "TemplateURL": "http://localhost/nested-stack-templates/child3-fail-%s.json" }
                }
              }
            }
            """.formatted(suffix);

        String rootStackId = createStack(rootStackName, rootTemplate);
        try {
            awaitStackStatus(rootStackId, "CREATE_COMPLETE");
            String childStackId = getNestedStackId(rootStackId, "ChildStack", null);
            String grandchildStackId = getNestedStackId(childStackId, "GrandchildStack", null);

            // Put blocker object into grandchild's bucket
            given().contentType("text/plain").body("keep").when().put("/" + bucketName + "/blocker.txt").then().statusCode(200);

            String updatedRootTemplate = "{\"Resources\": {}}";
            updateStack(rootStackName, updatedRootTemplate);
            awaitStackStatus(rootStackId, "UPDATE_COMPLETE");
            assertThat(describeStacks(rootStackId), containsString("could not be deleted during update cleanup"));

            awaitStackStatus(grandchildStackId, "DELETE_FAILED");
            awaitStackStatus(childStackId, "DELETE_FAILED");

            // Clean up blocker and delete root stack
            given().when().delete("/" + bucketName + "/blocker.txt").then().statusCode(204);
            deleteStack(rootStackName);
            awaitStackStatus(rootStackId, "DELETE_COMPLETE");
            awaitStackStatus(childStackId, "DELETE_COMPLETE");
            awaitStackStatus(grandchildStackId, "DELETE_COMPLETE");
            assertBucketDeleted(bucketName);
        } finally {
            given().when().delete("/" + bucketName + "/blocker.txt");
            deleteStack(rootStackName);
        }
    }

    @Test
    void updateStack_rollbackPreservesRemovedResourcesWhenNewResourceFails() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucketA = "cfn-rb-keep-a-" + suffix;
        String sfnName = "cfn-rb-sfn-" + suffix;
        String stackName = "cfn-rb-preserve-" + suffix;

        String initialTemplate = """
            {
              "Resources": {
                "BucketA": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "%s" }
                },
                "InitialStateMachine": {
                  "Type": "AWS::StepFunctions::StateMachine",
                  "Properties": {
                    "StateMachineName": "%s",
                    "RoleArn": "arn:aws:iam::000000000000:role/cfn-sfn-rollback-role",
                    "DefinitionString": "{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"Result\\":\\"marker-v1\\",\\"End\\":true}}}"
                  }
                }
              }
            }
            """.formatted(bucketA, sfnName);

        String stackId = createStack(stackName, initialTemplate);
        try {
            awaitStackStatus(stackId, "CREATE_COMPLETE");
            assertBucketExists(bucketA);

            // Update template: omits BucketA, updates InitialStateMachine, and adds a resource that fails creation
            String failingUpdateTemplate = """
                {
                  "Resources": {
                    "InitialStateMachine": {
                      "Type": "AWS::StepFunctions::StateMachine",
                      "Properties": {
                        "StateMachineName": "%s",
                        "RoleArn": "arn:aws:iam::000000000000:role/cfn-sfn-rollback-role",
                        "DefinitionString": "{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"Result\\":\\"marker-v2\\",\\"End\\":true}}}"
                      }
                    },
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {
                        "Name": "bad-secret-%s",
                        "SecretString": "explicit",
                        "GenerateSecretString": { "PasswordLength": 32 }
                      }
                    }
                  }
                }
                """.formatted(sfnName, suffix);

            updateStack(stackName, failingUpdateTemplate);
            awaitStackStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");

            // BucketA was removed in the failing update template, but rollback must preserve it!
            assertBucketExists(bucketA);

            String resXml = cfnQuery("DescribeStackResources", stackName, null).then().statusCode(200).extract().asString();
            assertThat(resXml, containsString("<LogicalResourceId>BucketA</LogicalResourceId>"));
            assertThat(resXml, containsString("<LogicalResourceId>InitialStateMachine</LogicalResourceId>"));
            assertThat(resXml, not(containsString("<LogicalResourceId>BadSecret</LogicalResourceId>")));
        } finally {
            deleteStack(stackName);
            awaitStackStatus(stackId, "DELETE_COMPLETE");
            assertBucketDeleted(bucketA);
        }
    }

    private static String getNestedStackId(String stackName, String logicalId, String auth) {
        String xml = cfnQuery("DescribeStackResources", stackName, auth).then().statusCode(200).extract().asString();
        int logIdx = xml.indexOf("<LogicalResourceId>" + logicalId + "</LogicalResourceId>");
        if (logIdx == -1) {
            fail("Logical resource " + logicalId + " not found");
        }
        int physStart = xml.indexOf("<PhysicalResourceId>", logIdx) + "<PhysicalResourceId>".length();
        int physEnd = xml.indexOf("</PhysicalResourceId>", physStart);
        return xml.substring(physStart, physEnd);
    }

    private static String createStack(String stackName, String template) {
        return createStack(stackName, template, null);
    }

    private static String createStack(String stackName, String template, String auth) {
        var req = given().contentType("application/x-www-form-urlencoded");
        if (auth != null) {
            req.header("Authorization", auth);
        }
        String xml = req
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        int start = xml.indexOf("<StackId>") + "<StackId>".length();
        return xml.substring(start, xml.indexOf("</StackId>", start));
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

    private static void updateStack(String stackName, String template) {
        updateStack(stackName, template, null);
    }

    private static void updateStack(String stackName, String template, String auth) {
        var req = given().contentType("application/x-www-form-urlencoded");
        if (auth != null) {
            req.header("Authorization", auth);
        }
        req.formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /** DeleteStack runs asynchronously, and a deleted stack stays describable by its stack ID. */
    private static void awaitStackStatus(String stackId, String status) throws InterruptedException {
        awaitStackStatus(stackId, status, null);
    }

    private static void awaitStackStatus(String stackId, String status, String auth) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStacks(stackId, auth);
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String describeStacks(String stackId) {
        return describeStacks(stackId, null);
    }

    private static String describeStacks(String stackId, String auth) {
        return cfnQuery("DescribeStacks", stackId, auth).then().statusCode(200).extract().asString();
    }

    private static String describeStackEvents(String stackId) {
        return cfnQuery("DescribeStackEvents", stackId, null).then().statusCode(200).extract().asString();
    }

    private static Response cfnQuery(String action, String stackId) {
        return cfnQuery(action, stackId, null);
    }

    private static Response cfnQuery(String action, String stackId, String auth) {
        var req = given().contentType("application/x-www-form-urlencoded");
        if (auth != null) {
            req.header("Authorization", auth);
        }
        return req.formParam("Action", action).formParam("StackName", stackId).when().post("/");
    }

    private static void assertBucketExists(String bucket) {
        bucketRequest(bucket).then().statusCode(200);
    }

    private static void assertBucketDeleted(String bucket) {
        bucketRequest(bucket).then().statusCode(404);
    }

    private static Response bucketRequest(String bucket) {
        return given().header("Host", bucket + ".localhost").when().get("/");
    }
}
