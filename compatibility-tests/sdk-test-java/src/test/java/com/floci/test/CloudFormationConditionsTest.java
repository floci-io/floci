package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation conditions with intrinsic operands")
class CloudFormationConditionsTest {

    private static CloudFormationClient cfn;
    private static S3Client s3;
    private static SqsClient sqs;
    private String stackName;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        s3 = TestFixtures.s3Client();
        sqs = TestFixtures.sqsClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-cond");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        // Also when the stack was refused: DeleteStack of a name with no stack does nothing.
        cfn.deleteStack(r -> r.stackName(stackName));
        TestFixtures.await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                return stacks.isEmpty() || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString());
            } catch (CloudFormationException e) {
                return e.getMessage().contains("does not exist");
            }
        }, stackName, "deleted", TestFixtures.isRealAws() ? 600_000_000_000L : 60_000_000_000L,
                TestFixtures.isRealAws() ? 5_000 : 100);
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        s3.close();
        sqs.close();
    }

    @Test
    @DisplayName("Fn::FindInMap inside Fn::Equals compares the mapped value")
    void mappedConditionSelectsTheBucketName() throws InterruptedException {
        String region = TestFixtures.region().id();
        String prefix = stackName + "-";
        createStack("""
                {
                  "Mappings": {"Locations": {"%1$s": {"Code": "false"}}},
                  "Conditions": {"UseCode": {"Fn::Not": [{"Fn::Equals": [
                    {"Fn::FindInMap": ["Locations", {"Ref": "AWS::Region"}, "Code"]}, "false"]}]}},
                  "Resources": {"Bucket": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName":
                    {"Fn::Join": ["", ["%2$s", {"Fn::If": ["UseCode",
                      {"Fn::FindInMap": ["Locations", {"Ref": "AWS::Region"}, "Code"]},
                      {"Ref": "AWS::Region"}]}]]}}}}
                }
                """.formatted(region, prefix));

        s3.headBucket(r -> r.bucket(prefix + region));
        assertThatThrownBy(() -> s3.headBucket(r -> r.bucket(prefix + "false")))
                .isInstanceOf(NoSuchBucketException.class);
    }

    @Test
    @DisplayName("Ref AWS::StackName inside Fn::Equals is the stack's own name")
    void stackNameConditionCreatesTheResource() throws InterruptedException {
        createStack("""
                {
                  "Conditions": {"IsThisStack": {"Fn::Equals": [{"Ref": "AWS::StackName"}, "%1$s"]}},
                  "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsThisStack",
                    "Properties": {"QueueName": "%1$s"}}}
                }
                """.formatted(stackName));

        assertThat(sqs.getQueueUrl(r -> r.queueName(stackName)).queueUrl()).endsWith("/" + stackName);
    }

    @Test
    @DisplayName("Fn::ImportValue inside a condition is a template error")
    void importValueInAConditionIsRefused() {
        assertThatThrownBy(() -> cfn.createStack(r -> r.stackName(stackName).templateBody("""
                {
                  "Conditions": {"IsOn": {"Fn::Equals": [{"Fn::ImportValue": "%s-export"}, "on"]}},
                  "Resources": {"Queue": {"Type": "AWS::SQS::Queue", "Condition": "IsOn"}}
                }
                """.formatted(stackName))))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(400);
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).contains("Fn::ImportValue");
                });
        assertThatThrownBy(() -> cfn.describeStacks(r -> r.stackName(stackName)))
                .isInstanceOf(CloudFormationException.class)
                .hasMessageContaining("does not exist");
    }

    private void createStack(String template) throws InterruptedException {
        cfn.createStack(r -> r.stackName(stackName).templateBody(template));
        TestFixtures.await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return "CREATE_COMPLETE".equals(status);
        }, stackName, "CREATE_COMPLETE", TestFixtures.isRealAws() ? 600_000_000_000L : 60_000_000_000L,
                TestFixtures.isRealAws() ? 5_000 : 100);
    }
}
