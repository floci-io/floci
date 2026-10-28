package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.ArchitectureValues;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceBlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RunInstances creates one EBS volume per device of the AMI's block device mapping with the
 * launch's own entries over it, and TerminateInstances keeps or deletes each as its
 * DeleteOnTermination says.
 */
class Ec2LaunchBlockDeviceMappingsTest {

    /** An account of its own, so the volumes listed are only this test's. */
    private static final String ACCOUNT = "724800000001";
    private static final String SNAPSHOT = "snap-0a1b2c3d4e5f60719";

    @Test
    @DisplayName("RunInstances creates the AMI's and the request's volumes and termination keeps those it should")
    void launchCreatesTheMappedVolumes() {
        try (Ec2Client ec2 = TestFixtures.ec2Client(ACCOUNT)) {
            String imageId = registerImage(ec2);
            String instanceId = null;
            try {
                Instance launched = ec2.runInstances(r -> r.imageId(imageId).instanceType(InstanceType.T3_MICRO)
                        .minCount(1).maxCount(1)
                        .blockDeviceMappings(
                                BlockDeviceMapping.builder().deviceName("/dev/sda1")
                                        .ebs(e -> e.volumeSize(20).deleteOnTermination(false)).build(),
                                BlockDeviceMapping.builder().deviceName("/dev/sdg")
                                        .ebs(e -> e.volumeSize(5).volumeType(VolumeType.GP3)).build()))
                        .instances().get(0);
                instanceId = launched.instanceId();

                Instance described = ec2.describeInstances(r -> r.instanceIds(launched.instanceId()))
                        .reservations().get(0).instances().get(0);
                assertThat(described.rootDeviceName()).isEqualTo("/dev/sda1");
                assertThat(described.blockDeviceMappings()).extracting(InstanceBlockDeviceMapping::deviceName)
                        .containsExactly("/dev/sda1", "/dev/sdf", "/dev/sdg");
                assertThat(described.blockDeviceMappings()).extracting(m -> m.ebs().deleteOnTermination())
                        .containsExactly(false, false, true);

                Map<String, Volume> volumes = volumesByDevice(ec2, launched.instanceId());
                assertThat(volumes.get("/dev/sda1").size()).isEqualTo(20);
                assertThat(volumes.get("/dev/sda1").snapshotId()).isEqualTo(SNAPSHOT);
                assertThat(volumes.get("/dev/sda1").volumeType()).isEqualTo(VolumeType.GP2);
                assertThat(volumes.get("/dev/sdf").size()).isEqualTo(125);
                assertThat(volumes.get("/dev/sdf").volumeType()).isEqualTo(VolumeType.ST1);
                assertThat(volumes.get("/dev/sdg").size()).isEqualTo(5);
                assertThat(volumes.get("/dev/sdg").volumeType()).isEqualTo(VolumeType.GP3);

                ec2.terminateInstances(r -> r.instanceIds(launched.instanceId()));
                instanceId = null;
                List<Volume> left = ec2.describeVolumes(r -> r.volumeIds(volumes.get("/dev/sda1").volumeId(),
                        volumes.get("/dev/sdf").volumeId())).volumes();
                assertThat(left).allSatisfy(volume -> {
                    assertThat(volume.stateAsString()).isEqualTo("available");
                    assertThat(volume.attachments()).isEmpty();
                });
                assertThat(ec2.describeVolumes().volumes()).extracting(Volume::volumeId)
                        .doesNotContain(volumes.get("/dev/sdg").volumeId());
                for (Volume volume : left) {
                    ec2.deleteVolume(r -> r.volumeId(volume.volumeId()));
                }
            } finally {
                if (instanceId != null) {
                    String cleanup = instanceId;
                    ec2.terminateInstances(r -> r.instanceIds(cleanup));
                }
                ec2.deregisterImage(r -> r.imageId(imageId));
            }
        }
    }

    @Test
    @DisplayName("RunInstances refuses a volume smaller than its snapshot and launches nothing")
    void refusesAVolumeSmallerThanItsSnapshot() {
        try (Ec2Client ec2 = TestFixtures.ec2Client(ACCOUNT)) {
            String imageId = registerImage(ec2);
            try {
                Ec2Exception refused = assertThrows(Ec2Exception.class, () -> ec2.runInstances(r -> r
                        .imageId(imageId).instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1)
                        .blockDeviceMappings(BlockDeviceMapping.builder().deviceName("/dev/sda1")
                                .ebs(e -> e.volumeSize(8)).build())));
                assertThat(refused.statusCode()).isEqualTo(400);
                assertThat(refused.awsErrorDetails().errorCode()).isEqualTo("InvalidBlockDeviceMapping");
                assertThat(refused.awsErrorDetails().errorMessage())
                        .isEqualTo("Volume of size 8GB is smaller than snapshot '" + SNAPSHOT + "', expect size>= 16GB");
                assertThat(ec2.describeInstances(r -> r.filters(Filter.builder().name("image-id").values(imageId)
                        .build())).reservations()).isEmpty();
            } finally {
                ec2.deregisterImage(r -> r.imageId(imageId));
            }
        }
    }

    /** A /dev/sda1-rooted gp2 AMI on a 16 GiB snapshot, with a 125 GiB st1 /dev/sdf that is kept on termination. */
    private static String registerImage(Ec2Client ec2) {
        return ec2.registerImage(r -> r.name("launch-volumes-" + UUID.randomUUID())
                .rootDeviceName("/dev/sda1").architecture(ArchitectureValues.X86_64)
                .blockDeviceMappings(
                        BlockDeviceMapping.builder().deviceName("/dev/sda1")
                                .ebs(e -> e.snapshotId(SNAPSHOT).volumeSize(16).volumeType(VolumeType.GP2)).build(),
                        BlockDeviceMapping.builder().deviceName("/dev/sdf")
                                .ebs(e -> e.volumeSize(125).volumeType(VolumeType.ST1).deleteOnTermination(false))
                                .build()))
                .imageId();
    }

    private static Map<String, Volume> volumesByDevice(Ec2Client ec2, String instanceId) {
        return ec2.describeVolumes(r -> r.filters(Filter.builder().name("attachment.instance-id")
                        .values(instanceId).build())).volumes().stream()
                .collect(Collectors.toMap(volume -> volume.attachments().get(0).device(), volume -> volume));
    }
}
