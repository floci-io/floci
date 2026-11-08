package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudtrail.CloudTrailService;
import io.floci.aws.services.cloudtrail.model.Trail;
import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a stack update does with the {@code S3KeyPrefix} of an {@code AWS::CloudTrail::Trail}. */
@QuarkusTest
class CloudFormationCloudTrailTrailIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String REGION = "us-east-1";

    @Inject
    CloudTrailService trails;

    @Test
    void updateStackThatDropsTheS3KeyPrefixClearsItAndAReAddSetsItAgain() {
        String name = "cfn-trail-prefix-drop-" + Long.toString(System.nanoTime(), 36);
        deploy("CreateStack", name, trailTemplate(name, "bucket-a", ", \"S3KeyPrefix\": \"orig/\""),
                "CREATE_COMPLETE");
        assertEquals("orig/", trails.getTrail(REGION, name).s3KeyPrefix());

        deploy("UpdateStack", name, trailTemplate(name, "bucket-a", ""), "UPDATE_COMPLETE");
        assertNull(trails.getTrail(REGION, name).s3KeyPrefix());

        deploy("UpdateStack", name, trailTemplate(name, "bucket-a", ", \"S3KeyPrefix\": \"back/\""),
                "UPDATE_COMPLETE");
        assertEquals("back/", trails.getTrail(REGION, name).s3KeyPrefix());
        CfnStackWaits.deleteStack(name, CFN_AUTH);
    }

    @Test
    void updateStackWhoseS3KeyPrefixBecomesNoValueClearsIt() {
        String name = "cfn-trail-prefix-novalue-" + Long.toString(System.nanoTime(), 36);
        deploy("CreateStack", name, trailTemplate(name, "bucket-a", ", \"S3KeyPrefix\": \"orig/\""),
                "CREATE_COMPLETE");

        deploy("UpdateStack", name, trailTemplate(name, "bucket-a",
                ", \"S3KeyPrefix\": {\"Fn::If\": [\"Off\", {\"Ref\": \"AWS::NoValue\"}, \"orig/\"]}"),
                "UPDATE_COMPLETE");

        assertNull(trails.getTrail(REGION, name).s3KeyPrefix());
        CfnStackWaits.deleteStack(name, CFN_AUTH);
    }

    @Test
    void changedS3KeyPrefixAppliesAndAnEmptyOneIsStoredAsGivenUntilALaterDropClearsIt() {
        String name = "cfn-trail-prefix-empty-" + Long.toString(System.nanoTime(), 36);
        deploy("CreateStack", name, trailTemplate(name, "bucket-a", ", \"S3KeyPrefix\": \"orig/\""),
                "CREATE_COMPLETE");

        deploy("UpdateStack", name, trailTemplate(name, "bucket-a", ", \"S3KeyPrefix\": \"changed/\""),
                "UPDATE_COMPLETE");
        assertEquals("changed/", trails.getTrail(REGION, name).s3KeyPrefix());

        deploy("UpdateStack", name, trailTemplate(name, "bucket-a", ", \"S3KeyPrefix\": \"\""), "UPDATE_COMPLETE");
        assertEquals("", trails.getTrail(REGION, name).s3KeyPrefix());

        deploy("UpdateStack", name, trailTemplate(name, "bucket-a", ""), "UPDATE_COMPLETE");
        assertNull(trails.getTrail(REGION, name).s3KeyPrefix());
        CfnStackWaits.deleteStack(name, CFN_AUTH);
    }

    @Test
    void s3KeyPrefixSetOutsideTheStackSurvivesAnUpdateThatNeverDeclaredIt() {
        String name = "cfn-trail-prefix-outside-" + Long.toString(System.nanoTime(), 36);
        deploy("CreateStack", name, trailTemplate(name, "bucket-a", ""), "CREATE_COMPLETE");
        trails.updateTrail(REGION, name, null, "outside/", null, null, null, null, null);

        deploy("UpdateStack", name, trailTemplate(name, "bucket-b", ""), "UPDATE_COMPLETE");

        Trail stored = trails.getTrail(REGION, name);
        assertEquals("outside/", stored.s3KeyPrefix());
        assertEquals("bucket-b", stored.s3BucketName());
        CfnStackWaits.deleteStack(name, CFN_AUTH);
    }

    @Test
    void droppingTheS3KeyPrefixLeavesTheBucketAndTheTopicAlone() {
        String name = "cfn-trail-prefix-others-" + Long.toString(System.nanoTime(), 36);
        deploy("CreateStack", name, trailTemplate(name, "bucket-a", ", \"S3KeyPrefix\": \"orig/\""),
                "CREATE_COMPLETE");
        String topicBefore = trails.getTrail(REGION, name).snsTopicArn();
        assertNotNull(topicBefore);

        deploy("UpdateStack", name, trailTemplate(name, "bucket-a", ""), "UPDATE_COMPLETE");

        Trail stored = trails.getTrail(REGION, name);
        assertEquals("bucket-a", stored.s3BucketName());
        assertEquals(topicBefore, stored.snsTopicArn());
        CfnStackWaits.deleteStack(name, CFN_AUTH);
    }

    private static void deploy(String action, String stackName, String template, String expectedStatus) {
        CfnRequests.deploy(action, stackName, template, CFN_AUTH, expectedStatus);
    }

    /** A trail named like its stack; {@code extraProperties} is appended to its properties. */
    private static String trailTemplate(String name, String bucket, String extraProperties) {
        return """
                {
                  "Conditions": {"Off": {"Fn::Equals": ["a", "a"]}},
                  "Resources": {
                    "Trail": {
                      "Type": "AWS::CloudTrail::Trail",
                      "Properties": {"TrailName": "%s", "S3BucketName": "%s", "SnsTopicName": "trail-topic"%s}
                    }
                  }
                }
                """.formatted(name, bucket, extraProperties);
    }
}
