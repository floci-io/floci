package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.services.ec2.model.Volume;
import io.floci.aws.services.ec2.model.VolumeAttachment;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A guest that ends its own container, which a Docker stop issued outside Floci stands in for here,
 * gets its instance's shutdown behavior applied through the real EC2 service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class Ec2GuestShutdownDockerNightlyIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    Ec2Service ec2Service;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        assumeTrue(DockerAvailability.answers(dockerClient), "Docker must be available for EC2 guest shutdown tests");
    }

    @Test
    void anInstanceWhoseGuestShutsDownStops() {
        Instance instance = launch(List.of());
        try {
            ec2Service.awaitContainerLaunch(instance);

            dockerClient.stopContainerCmd(instance.getDockerContainerId()).withTimeout(5).exec();

            await().atMost(Duration.ofSeconds(60)).until(() -> "stopped".equals(state(instance)));
        } finally {
            ec2Service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void anInstanceLaunchedToTerminateIsTerminatedWithItsDeleteOnTerminationVolumes() {
        LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
        ebs.setVolumeSize(1);
        ebs.setDeleteOnTermination(true);
        LaunchTemplateData.BlockDeviceMapping data = new LaunchTemplateData.BlockDeviceMapping();
        data.setDeviceName("/dev/sdf");
        data.setEbs(ebs);
        Instance instance = launch(List.of(data));
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            ec2.modifyInstanceAttribute(r -> r.instanceId(instance.getInstanceId())
                    .instanceInitiatedShutdownBehavior(v -> v.value("terminate")));
        }
        ec2Service.awaitContainerLaunch(instance);
        List<String> volumeIds = ec2Service.instanceVolumeAttachments(List.of(instance))
                .getOrDefault(instance.getInstanceId(), List.of()).stream()
                .map(VolumeAttachment::getVolumeId).toList();
        assertFalse(volumeIds.isEmpty(), "the launch must attach its volumes");

        dockerClient.stopContainerCmd(instance.getDockerContainerId()).withTimeout(5).exec();

        await().atMost(Duration.ofSeconds(60)).until(() -> "terminated".equals(state(instance)));
        List<String> remaining = ec2Service.describeVolumes(REGION, List.of(), Map.of()).stream()
                .map(Volume::getVolumeId).filter(volumeIds::contains).toList();
        assertEquals(List.of(), remaining);
    }

    private Instance launch(List<LaunchTemplateData.BlockDeviceMapping> blockDevices) {
        return ec2Service.runInstances(REGION, "ami-alpine", "t3.micro", 1, 1, null, List.of(),
                null, null, List.of(), null, null, null, null, 0, null, null, null, null, false, null, null,
                blockDevices, false, null, null).getInstances().getFirst();
    }

    private String state(Instance instance) {
        return ec2Service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of()).getFirst()
                .getInstances().getFirst().getState().getName();
    }
}
