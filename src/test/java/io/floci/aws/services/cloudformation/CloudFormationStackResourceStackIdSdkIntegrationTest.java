package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.cloudformation.model.StackResourceDetail;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code StackResource} and {@code StackResourceDetail} both carry the owning stack's
 * {@code StackId} and {@code StackName} (issue #9509).
 */
@QuarkusTest
class CloudFormationStackResourceStackIdSdkIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources": {"Q": {"Type": "AWS::SQS::Queue"}, "T": {"Type": "AWS::SNS::Topic"}}}
            """;

    private CloudFormationClient cfn;
    private String stackName;
    private String stackId;

    @BeforeEach
    void createStack() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        stackName = "cfn-srsid-" + UUID.randomUUID().toString().substring(0, 8);
        Stack stack = CfnStackWaits.createStack(cfn, stackName, TEMPLATE, "CREATE_COMPLETE");
        stackId = stack.stackId();
    }

    @AfterEach
    void deleteStack() {
        CfnStackWaits.deleteStack(cfn, stackName);
        cfn.close();
    }

    @Test
    void describeStackResourcesStampsTheStackIdOnEveryMember() {
        List<StackResource> resources = cfn.describeStackResources(request -> request.stackName(stackName))
                .stackResources();

        assertEquals(2, resources.size());
        for (StackResource resource : resources) {
            assertEquals(stackId, resource.stackId());
            assertEquals(stackName, resource.stackName());
        }
    }

    @Test
    void describeStackResourcesByStackIdAnswersWithTheStacksOwnName() {
        List<StackResource> resources = cfn.describeStackResources(request -> request.stackName(stackId))
                .stackResources();

        assertFalse(resources.isEmpty());
        for (StackResource resource : resources) {
            assertEquals(stackId, resource.stackId());
            assertEquals(stackName, resource.stackName());
        }
    }

    @Test
    void describeStackResourceReturnsTheStackIdAndName() {
        StackResourceDetail detail = cfn.describeStackResource(request ->
                request.stackName(stackName).logicalResourceId("Q")).stackResourceDetail();

        assertEquals(stackId, detail.stackId());
        assertEquals(stackName, detail.stackName());
        assertEquals("Q", detail.logicalResourceId());
    }

    @Test
    void describeStackResourceByStackIdAnswersWithTheStacksOwnName() {
        StackResourceDetail detail = cfn.describeStackResource(request ->
                request.stackName(stackId).logicalResourceId("T")).stackResourceDetail();

        assertEquals(stackId, detail.stackId());
        assertEquals(stackName, detail.stackName());
    }
}
