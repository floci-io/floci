package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceAttributeName;
import software.amazon.awssdk.services.ec2.model.ShutdownBehavior;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class Ec2InstanceInitiatedShutdownBehaviorIntegrationTest {

    @Test
    void anInstanceLaunchedWithoutAShutdownBehaviorStops() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            String instanceId = ec2.runInstances(r -> r.imageId("ami-alpine").instanceType("t3.micro")
                    .minCount(1).maxCount(1)).instances().getFirst().instanceId();

            assertEquals("stop", shutdownBehavior(ec2, instanceId));
        }
    }

    @Test
    void runInstancesKeepsTheShutdownBehaviorItIsGiven() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            String instanceId = ec2.runInstances(r -> r.imageId("ami-alpine").instanceType("t3.micro")
                    .minCount(1).maxCount(1).instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE))
                    .instances().getFirst().instanceId();

            assertEquals("terminate", shutdownBehavior(ec2, instanceId));
        }
    }

    @Test
    void anInstanceLaunchedFromATemplateKeepsTheTemplatesShutdownBehavior() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            String templateName = "halt-" + UUID.randomUUID();
            ec2.createLaunchTemplate(r -> r.launchTemplateName(templateName).launchTemplateData(d -> d
                    .imageId("ami-alpine").instanceType("t3.micro")
                    .instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)));

            String instanceId = ec2.runInstances(r -> r.launchTemplate(t -> t.launchTemplateName(templateName))
                    .minCount(1).maxCount(1)).instances().getFirst().instanceId();

            assertEquals("terminate", shutdownBehavior(ec2, instanceId));
        }
    }

    private static String shutdownBehavior(Ec2Client ec2, String instanceId) {
        return ec2.describeInstanceAttribute(r -> r.instanceId(instanceId)
                .attribute(InstanceAttributeName.INSTANCE_INITIATED_SHUTDOWN_BEHAVIOR))
                .instanceInitiatedShutdownBehavior().value();
    }
}
