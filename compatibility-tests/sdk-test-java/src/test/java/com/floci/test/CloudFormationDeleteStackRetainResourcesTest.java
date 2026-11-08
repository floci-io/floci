package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation DeleteStack RetainResources")
class CloudFormationDeleteStackRetainResourcesTest {

    private static CloudFormationClient cfn;
    private static S3Client s3;
    private String stackName;
    private String dataBucket;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        s3 = TestFixtures.s3Client();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-retain");
        dataBucket = stackName + "-data";
    }

    @AfterEach
    void cleanup() {
        try {
            TestFixtures.deleteBucketContents(s3, dataBucket);
        } catch (NoSuchBucketException ignored) {
            // The stack never created the bucket, because an assertion failed first.
        }
        try {
            cfn.deleteStack(r -> r.stackName(stackName));
        } catch (CloudFormationException ignored) {
            // The test deletes the stack itself; this only covers an assertion failing first.
        }
        try {
            s3.deleteBucket(r -> r.bucket(dataBucket));
        } catch (NoSuchBucketException ignored) {
            // Deleted by the stack, which the test retains it from only on the path it asserts.
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        s3.close();
    }

    @Test
    @DisplayName("keeps the retained resource of a DELETE_FAILED stack and deletes the rest")
    void retainsResourceOfDeleteFailedStack() throws InterruptedException {
        String stackId = cfn.createStack(r -> r.stackName(stackName).templateBody("""
                {
                  "Resources": {
                    "DataBucket": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%s"}},
                    "OtherBucket": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%s-other"}}
                  }
                }
                """.formatted(dataBucket, stackName))).stackId();
        awaitStatus(stackId, "CREATE_COMPLETE");
        assertThatThrownBy(() -> cfn.deleteStack(r -> r.stackName(stackName).retainResources("DataBucket")))
                .isInstanceOf(CloudFormationException.class)
                .hasMessageContaining("specify which resources to retain only when the stack is in the"
                        + " DELETE_FAILED state");

        s3.putObject(r -> r.bucket(dataBucket).key("kept.txt"), RequestBody.fromString("kept"));
        cfn.deleteStack(r -> r.stackName(stackName));
        awaitStatus(stackId, "DELETE_FAILED");
        assertThatThrownBy(() -> cfn.deleteStack(r -> r.stackName(stackName).retainResources("OtherBucket")))
                .as("OtherBucket was deleted by the failed delete")
                .isInstanceOf(CloudFormationException.class)
                .hasMessageContaining("Do not specify resources that are in the DELETE_COMPLETE state");

        cfn.deleteStack(r -> r.stackName(stackName).retainResources("DataBucket"));
        awaitStatus(stackId, "DELETE_COMPLETE");

        assertThat(s3.getObjectAsBytes(r -> r.bucket(dataBucket).key("kept.txt")).asUtf8String())
                .isEqualTo("kept");
        assertThatThrownBy(() -> s3.headBucket(r -> r.bucket(stackName + "-other")))
                .isInstanceOf(NoSuchBucketException.class);
        assertThat(cfn.describeStackEventsPaginator(r -> r.stackName(stackId)).stackEvents().stream()
                .filter(event -> "DataBucket".equals(event.logicalResourceId()))
                .findFirst())
                .as("DescribeStackEvents lists newest first")
                .get()
                .extracting(StackEvent::resourceStatusAsString)
                .isEqualTo("DELETE_SKIPPED");
    }

    private void awaitStatus(String stackId, String expected) throws InterruptedException {
        TestFixtures.await(() -> {
            String status = cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0).stackStatusAsString();
            if (!status.endsWith("_IN_PROGRESS") && !status.equals(expected)) {
                throw new AssertionError(stackName + " settled at " + status + ", expected " + expected);
            }
            return status.equals(expected);
        }, stackName, expected, 120_000_000_000L, TestFixtures.isRealAws() ? 5000 : 100);
    }
}
