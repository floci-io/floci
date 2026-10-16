package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Replacing updates of {@code AWS::S3::Bucket} through the whole engine. A template that changes
 * {@code BucketName} replaces the bucket: the new one is created and the old is deleted once the
 * update commits (unless {@code UpdateReplacePolicy: Retain}), and a later resource that fails the
 * update rolls the replacement back and points the resource at the original bucket again.
 */
@QuarkusTest
class CloudFormationS3BucketReplacementIntegrationTest {

    /**
     * A rename the engine can commit: the old bucket is deleted after {@code UPDATE_COMPLETE} and
     * its {@code DELETE_COMPLETE} event names it.
     */
    @Test
    void renamingTheBucketDeletesTheOldOneAfterTheUpdateCommits() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "s3-replace-" + suffix;
        String oldBucket = "s3-replace-old-" + suffix;
        String newBucket = "s3-replace-new-" + suffix;

        createStack(stackName, template(oldBucket, "", false));
        assertBucketPresent(oldBucket);

        updateStack(stackName, template(newBucket, "", false));
        await(stackName, "UPDATE_COMPLETE");

        String describe = describeStack(stackName);
        assertEquals(newBucket, output(describe, "BucketRef"));
        assertBucketPresent(newBucket);
        assertBucketMissing(oldBucket);
        assertEvent(describeEvents(stackName), "DELETE_COMPLETE", oldBucket);

        deleteStack(stackName);
    }

    /** {@code UpdateReplacePolicy: Retain} keeps the bucket the rename displaced. */
    @Test
    void updateReplacePolicyRetainKeepsTheOldBucket() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "s3-retain-" + suffix;
        String oldBucket = "s3-retain-old-" + suffix;
        String newBucket = "s3-retain-new-" + suffix;

        createStack(stackName, template(oldBucket, "", false));
        updateStack(stackName, template(newBucket, "", true));
        await(stackName, "UPDATE_COMPLETE");

        assertEquals(newBucket, output(describeStack(stackName), "BucketRef"));
        assertBucketPresent(newBucket);
        assertBucketPresent(oldBucket);

        deleteStack(stackName);
    }

    /**
     * A rename a later resource fails rolls back: the stack ends in {@code UPDATE_ROLLBACK_COMPLETE},
     * the resource names the original bucket again, and the replacement the attempt created is gone.
     */
    @Test
    void aFailedRenameRollsBackToTheOriginalBucket() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "s3-rollback-" + suffix;
        String oldBucket = "s3-rollback-old-" + suffix;
        String newBucket = "s3-rollback-new-" + suffix;

        createStack(stackName, template(oldBucket, "", false));
        assertBucketPresent(oldBucket);

        // SecretString and GenerateSecretString cannot be combined, so the resource after the
        // bucket fails the update and the rename has to be undone.
        String failingSecret = """
            ,"Failure":{"Type":"AWS::SecretsManager::Secret","DependsOn":"Bucket",
              "Properties":{"Name":"s3-rollback-secret-%s","SecretString":"x",
                "GenerateSecretString":{"PasswordLength":32}}}""".formatted(suffix);
        updateStack(stackName, template(newBucket, failingSecret, false));
        await(stackName, "UPDATE_ROLLBACK_COMPLETE");

        String describe = describeStack(stackName);
        assertEquals(oldBucket, output(describe, "BucketRef"));
        assertBucketPresent(oldBucket);
        assertBucketMissing(newBucket);

        deleteStack(stackName);
    }

    /**
     * A failed update that changed the bucket's own settings without renaming it restores them: the
     * same bucket is pointed at a different {@code VersioningConfiguration}, a later resource fails,
     * and the bucket ends with the versioning status the stack started with.
     */
    @Test
    void aFailedInPlaceUpdateRestoresTheBucketConfiguration() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "s3-inplace-" + suffix;
        String bucket = "s3-inplace-" + suffix;

        createStack(stackName, inPlaceTemplate(bucket, "Enabled", ""));
        assertVersioning(bucket, "Enabled");

        String failingSecret = """
            ,"Failure":{"Type":"AWS::SecretsManager::Secret","DependsOn":"Bucket",
              "Properties":{"Name":"s3-inplace-secret-%s","SecretString":"x",
                "GenerateSecretString":{"PasswordLength":32}}}""".formatted(suffix);
        updateStack(stackName, inPlaceTemplate(bucket, "Suspended", failingSecret));
        await(stackName, "UPDATE_ROLLBACK_COMPLETE");

        assertVersioning(bucket, "Enabled");

        deleteStack(stackName);
    }

    private static String inPlaceTemplate(String bucketName, String versioningStatus, String extraResources) {
        return """
            {
              "Resources": {
                "Bucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {"BucketName": "%s", "VersioningConfiguration": {"Status": "%s"}}
                }%s
              }
            }""".formatted(bucketName, versioningStatus, extraResources);
    }

    private static String template(String bucketName, String extraResources, boolean retain) {
        String updateReplacePolicy = retain ? ", \"UpdateReplacePolicy\": \"Retain\"" : "";
        return """
            {
              "Resources": {
                "Bucket": {
                  "Type": "AWS::S3::Bucket"%1$s,
                  "Properties": { "BucketName": "%2$s" }
                }%3$s
              },
              "Outputs": { "BucketRef": { "Value": { "Ref": "Bucket" } } }
            }
            """.formatted(updateReplacePolicy, bucketName, extraResources);
    }

    private static void createStack(String stackName, String body) {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName).formParam("TemplateBody", body)
        .when().post("/").then().statusCode(200);
        await(stackName, "CREATE_COMPLETE");
    }

    private static void updateStack(String stackName, String body) {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName).formParam("TemplateBody", body)
        .when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private static String describeStack(String stackName) {
        return given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String describeEvents(String stackName) {
        return given().contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static void assertVersioning(String bucket, String status) {
        given()
        .when()
            .get("/" + bucket + "?versioning")
        .then()
            .statusCode(200)
            .body(containsString("<Status>" + status + "</Status>"));
    }

    private static void await(String stackName, String status) {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStack(stackName);
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            if (xml.contains("_FAILED</StackStatus>")) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("stack " + stackName + " never reached " + status + ": " + xml);
    }

    private static void assertBucketPresent(String bucket) {
        given().when().head("/" + bucket).then().statusCode(200);
    }

    private static void assertBucketMissing(String bucket) {
        given().when().head("/" + bucket).then().statusCode(404);
    }

    private static void assertEvent(String eventsXml, String status, String physicalId) {
        Pattern p = Pattern.compile("<member>(.*?)</member>", Pattern.DOTALL);
        Matcher m = p.matcher(eventsXml);
        while (m.find()) {
            String member = m.group(1);
            if (member.contains("<ResourceStatus>" + status + "</ResourceStatus>")
                    && member.contains("<PhysicalResourceId>" + physicalId + "</PhysicalResourceId>")) {
                return;
            }
        }
        throw new AssertionError("no " + status + " event for " + physicalId + " in " + eventsXml);
    }

    private static String output(String describeXml, String key) {
        Matcher m = Pattern.compile("<OutputKey>" + key + "</OutputKey>\\s*<OutputValue>(.*?)</OutputValue>", Pattern.DOTALL)
                .matcher(describeXml);
        if (!m.find()) {
            m = Pattern.compile("<OutputValue>(.*?)</OutputValue>\\s*<OutputKey>" + key + "</OutputKey>", Pattern.DOTALL)
                    .matcher(describeXml);
            assertTrue(m.find(), "output " + key + " missing in " + describeXml);
        }
        return m.group(1);
    }
}
