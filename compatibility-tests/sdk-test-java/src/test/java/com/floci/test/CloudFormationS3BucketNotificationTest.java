package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.FilterRule;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires a bucket to a queue through an {@code AWS::S3::Bucket} {@code NotificationConfiguration}
 * with the CloudFormation SDK and reads the result back with the S3 SDK, which is how a template's
 * notifications are checked against AWS. Removing the property in an update clears the bucket's
 * configuration, as it does on AWS.
 */
@DisplayName("CloudFormation AWS::S3::Bucket NotificationConfiguration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFormationS3BucketNotificationTest {

    private static CloudFormationClient cloudFormation;
    private static S3Client s3;
    private static String stackName;
    private static String bucketName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        s3 = TestFixtures.s3Client();
        stackName = TestFixtures.uniqueName("compat-cfn-s3-notify");
        bucketName = TestFixtures.uniqueName("compat-cfn-s3-notify").toLowerCase();
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(r -> r.stackName(stackName));
                awaitStatus("DELETE_COMPLETE");
            } catch (Exception e) {
                System.err.println("CloudFormation S3 notification cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
        if (s3 != null) {
            s3.close();
        }
    }

    private static String template(String notificationConfiguration) {
        return """
            {
              "Resources": {
                "Queue": { "Type": "AWS::SQS::Queue" },
                "QueuePolicy": {
                  "Type": "AWS::SQS::QueuePolicy",
                  "Properties": {
                    "Queues": [ { "Ref": "Queue" } ],
                    "PolicyDocument": { "Version": "2012-10-17", "Statement": [ {
                      "Effect": "Allow", "Principal": { "Service": "s3.amazonaws.com" },
                      "Action": "sqs:SendMessage", "Resource": { "Fn::GetAtt": ["Queue", "Arn"] } } ] }
                  }
                },
                "Bucket": {
                  "Type": "AWS::S3::Bucket",
                  "DependsOn": "QueuePolicy",
                  "Properties": { "BucketName": "%s" %s }
                }
              }
            }
            """.formatted(bucketName, notificationConfiguration);
    }

    @Test
    @Order(1)
    void theBucketCarriesTheTemplatesQueueConfiguration() throws InterruptedException {
        cloudFormation.createStack(r -> r.stackName(stackName).templateBody(template("""
            , "NotificationConfiguration": { "QueueConfigurations": [ {
                "Event": "s3:ObjectCreated:*",
                "Queue": { "Fn::GetAtt": ["Queue", "Arn"] },
                "Filter": { "S3Key": { "Rules": [ { "Name": "prefix", "Value": "uploads/" } ] } } } ] }
            """)));
        awaitStatus("CREATE_COMPLETE");

        GetBucketNotificationConfigurationResponse config =
                s3.getBucketNotificationConfiguration(r -> r.bucket(bucketName));

        assertThat(config.queueConfigurations()).hasSize(1);
        QueueConfiguration queue = config.queueConfigurations().get(0);
        assertThat(queue.queueArn()).startsWith("arn:").contains(":sqs:");
        assertThat(queue.eventsAsStrings()).containsExactly("s3:ObjectCreated:*");
        List<FilterRule> rules = queue.filter().key().filterRules();
        assertThat(rules).hasSize(1);
        // AWS returns the rule name as "Prefix"; compare it the way S3 matches it.
        assertThat(rules.get(0).nameAsString()).isEqualToIgnoringCase("prefix");
        assertThat(rules.get(0).value()).isEqualTo("uploads/");
        assertThat(config.lambdaFunctionConfigurations()).isEmpty();
        assertThat(config.topicConfigurations()).isEmpty();
    }

    @Test
    @Order(2)
    void removingThePropertyInAnUpdateClearsTheBucketsConfiguration() throws InterruptedException {
        assertThat(s3.getBucketNotificationConfiguration(r -> r.bucket(bucketName)).queueConfigurations())
                .as("the configuration the update removes").hasSize(1);

        cloudFormation.updateStack(r -> r.stackName(stackName).templateBody(template("")));
        awaitStatus("UPDATE_COMPLETE");

        GetBucketNotificationConfigurationResponse config =
                s3.getBucketNotificationConfiguration(r -> r.bucket(bucketName));

        assertThat(config.queueConfigurations()).isEmpty();
        assertThat(config.topicConfigurations()).isEmpty();
        assertThat(config.lambdaFunctionConfigurations()).isEmpty();
    }

    private static void awaitStatus(String status) throws InterruptedException {
        String current = "";
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Stack stack = cloudFormation.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
                current = stack.stackStatusAsString();
            } catch (CloudFormationException e) {
                if ("DELETE_COMPLETE".equals(status) && e.getMessage() != null
                        && e.getMessage().contains("does not exist")) {
                    return;
                }
                throw e;
            }
            if (status.equals(current)) {
                return;
            }
            if (current.endsWith("_FAILED") || current.endsWith("ROLLBACK_COMPLETE")) {
                break;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Stack " + stackName + " did not reach " + status + "; last status " + current);
    }
}
