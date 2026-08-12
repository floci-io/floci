package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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

    private static String createStack(String stackName, String template) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
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

    /** DeleteStack runs asynchronously, and a deleted stack stays describable by its stack ID. */
    private static void awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStacks(stackId);
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String describeStacks(String stackId) {
        return cfnQuery("DescribeStacks", stackId).then().statusCode(200).extract().asString();
    }

    private static String describeStackEvents(String stackId) {
        return cfnQuery("DescribeStackEvents", stackId).then().statusCode(200).extract().asString();
    }

    private static Response cfnQuery(String action, String stackId) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackId)
        .when()
            .post("/");
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
