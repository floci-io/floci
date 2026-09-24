package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(Ec2VolumeBlockDeviceDockerIntegrationTest.Profile.class)
class Ec2VolumeBlockDeviceDockerIntegrationTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.volume-block-devices", "true"
            );
        }
    }

    @Inject
    Ec2Service ec2Service;

    @Inject
    Ec2VolumeBlockDeviceManager volumeBlockDeviceManager;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    DockerClient dockerClient;

    @Inject
    EmulatorConfig config;

    private static final Logger LOG = Logger.getLogger(Ec2VolumeBlockDeviceDockerIntegrationTest.class);

    private final List<String> containersToClean = new ArrayList<>();
    private final List<String> volumesToClean = new ArrayList<>();

    @BeforeEach
    void checkPrerequisites() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for EC2 volume block device tests");
    }

    @AfterEach
    void cleanup() {
        for (String volId : volumesToClean) {
            try {
                Volume vol = ec2Service.describeVolumes("us-east-1", List.of(volId), null).getFirst();
                if (vol != null && vol.getAttachments() != null) {
                    for (VolumeAttachment att : vol.getAttachments()) {
                        try {
                            ec2Service.detachVolume("us-east-1", att.getVolumeId(), att.getInstanceId(), att.getDevice(), true);
                        } catch (Exception e) {
                            LOG.debugv("Ignoring detach volume error during test cleanup: {0}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debugv("Ignoring describe volume error during test cleanup: {0}", e.getMessage());
            }
            try {
                ec2Service.deleteVolume("us-east-1", volId);
            } catch (Exception e) {
                LOG.debugv("Ignoring delete volume error during test cleanup: {0}", e.getMessage());
            }
        }
        volumesToClean.clear();
        ec2Service.setClusterNodeInstanceProvider(null);

        for (String cid : containersToClean) {
            try {
                dockerClient.stopContainerCmd(cid).withTimeout(2).exec();
            } catch (Exception e) {
                LOG.debugv("Ignoring stop container error during test cleanup: {0}", e.getMessage());
            }
            try {
                dockerClient.removeContainerCmd(cid).withForce(true).exec();
            } catch (Exception e) {
                LOG.debugv("Ignoring remove container error during test cleanup: {0}", e.getMessage());
            }
        }
        containersToClean.clear();
    }

    private boolean isDockerAvailable() {
        if (dockerClient == null) {
            return false;
        }
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Instance createRunningTestInstance(String suffix) {
        String containerName = "floci-test-instance-" + suffix;
        ContainerSpec spec = containerBuilder.newContainer("alpine:3.21")
                .withName(containerName)
                .withPrivileged(true)
                .withEntrypoint(List.of("sleep", "3600"))
                .build();

        String containerId = lifecycleManager.createAndStart(spec).containerId();
        containersToClean.add(containerId);
        execIn(containerId, "apk add --no-cache e2fsprogs");

        String instanceId = "i-" + suffix;
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setDockerContainerId(containerId);
        instance.setRegion("us-east-1");
        Placement placement = new Placement();
        placement.setAvailabilityZone("us-east-1a");
        instance.setPlacement(placement);
        instance.setState(InstanceState.running());

        // Register directly into service storage for hermetic test execution
        ec2Service.putInstanceForTest(instance);
        return instance;
    }

    @Test
    void volumeAttachedProducesUsableBlockDeviceOfCorrectSize() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = createRunningTestInstance(suffix);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        VolumeAttachment attachment = ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf");
        assertNotNull(attachment);
        assertEquals("attached", attachment.getState());
        assertEquals("/dev/xvdf", attachment.getDevice());

        String targetContainerId = instance.getDockerContainerId();

        // 1. Device node exists in container
        ExecResult checkDev = execIn(targetContainerId, "test -b /dev/xvdf || test -L /dev/xvdf");
        assertEquals(0, checkDev.exitCode(), "Device /dev/xvdf should exist as block or symlink: " + checkDev.output());

        // 2. Size matches 1 GiB (1073741824 bytes)
        ExecResult checkSize = execIn(targetContainerId, "blockdev --getsize64 /dev/xvdf");
        assertEquals(0, checkSize.exitCode(), "blockdev query should succeed: " + checkSize.output());
        assertEquals("1073741824", checkSize.output().trim());

        // 3. Format filesystem, mount, write data, unmount
        ExecResult mkfs = execIn(targetContainerId, "mkfs.ext4 -F /dev/xvdf");
        assertEquals(0, mkfs.exitCode(), "mkfs.ext4 should succeed: " + mkfs.output());

        ExecResult mountAndWrite = execIn(targetContainerId,
                "mkdir -p /mnt/vol && mount /dev/xvdf /mnt/vol && echo 'floci-ebs-test-data' > /mnt/vol/test.txt && cat /mnt/vol/test.txt && umount /mnt/vol");
        assertEquals(0, mountAndWrite.exitCode(), "Mount, write, read and umount should succeed: " + mountAndWrite.output());
        assertTrue(mountAndWrite.output().contains("floci-ebs-test-data"));
    }

    @Test
    void detachingRemovesDeviceAndDeletingRemovesBackingFile() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = createRunningTestInstance(suffix);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf");
        String targetContainerId = instance.getDockerContainerId();

        ExecResult existsBefore = execIn(targetContainerId, "test -e /dev/xvdf");
        assertEquals(0, existsBefore.exitCode());

        // Detach volume
        VolumeAttachment detached = ec2Service.detachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf", false);
        assertEquals("detached", detached.getState());

        // Device node should be removed from container
        ExecResult existsAfter = execIn(targetContainerId, "test ! -e /dev/xvdf");
        assertEquals(0, existsAfter.exitCode(), "Device /dev/xvdf should no longer exist in container");

        // Delete volume
        ec2Service.deleteVolume("us-east-1", volume.getVolumeId());
        volumesToClean.remove(volume.getVolumeId());

        // Verify helper container removed backing file
        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        ExecResult checkFile = execIn(helperName, "test ! -f /volumes/" + volume.getVolumeId() + ".raw");
        assertEquals(0, checkFile.exitCode(), "Backing file should be removed on volume deletion");
    }

    @Test
    void secondAttachWhileAttachedFailsAndReAttachPreservesData() {
        String suffix1 = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String suffix2 = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance1 = createRunningTestInstance(suffix1);
        Instance instance2 = createRunningTestInstance(suffix2);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        // Attach to instance1
        ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance1.getInstanceId(), "/dev/xvdf");

        // Attempt second attach to instance2 while in-use: must fail with VolumeInUse
        AwsException ex = assertThrows(AwsException.class, () ->
                ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance2.getInstanceId(), "/dev/xvdg"));
        assertEquals("VolumeInUse", ex.getErrorCode());

        // Format and write data on instance1
        ExecResult formatAndWrite = execIn(instance1.getDockerContainerId(),
                "mkfs.ext4 -F /dev/xvdf && mkdir -p /mnt/vol1 && mount /dev/xvdf /mnt/vol1 && echo 'persisted-payload' > /mnt/vol1/hello.txt && umount /mnt/vol1");
        assertEquals(0, formatAndWrite.exitCode(), formatAndWrite.output());

        // Detach from instance1
        ec2Service.detachVolume("us-east-1", volume.getVolumeId(), instance1.getInstanceId(), "/dev/xvdf", false);

        // Re-attach to instance2 at /dev/xvdg
        ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance2.getInstanceId(), "/dev/xvdg");

        // Verify device exists on instance2 and data is preserved
        ExecResult verifyData = execIn(instance2.getDockerContainerId(),
                "mkdir -p /mnt/vol2 && mount /dev/xvdg /mnt/vol2 && cat /mnt/vol2/hello.txt && umount /mnt/vol2");
        assertEquals(0, verifyData.exitCode(), "Data should be preserved across detach and re-attach: " + verifyData.output());
        assertTrue(verifyData.output().contains("persisted-payload"));
    }

    @Test
    void restoreInstanceVolumeDevicesRecreatesBlockDevice() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = createRunningTestInstance(suffix);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf");
        String targetContainerId = instance.getDockerContainerId();

        // Simulate device node loss (e.g. container reboot or tmpfs recreation)
        execIn(targetContainerId, "rm -f /dev/xvdf");
        ExecResult deleted = execIn(targetContainerId, "test ! -e /dev/xvdf");
        assertEquals(0, deleted.exitCode());

        // Call restoreInstanceVolumeDevices
        volumeBlockDeviceManager.restoreInstanceVolumeDevices(instance, volume.getAttachments(),
                volId -> Optional.of(volume));

        // Verify device node is restored
        ExecResult restored = execIn(targetContainerId, "test -b /dev/xvdf || test -L /dev/xvdf");
        assertEquals(0, restored.exitCode(), "Device /dev/xvdf should be restored: " + restored.output());
    }

    @Test
    void attachVolumeToClusterNodeInstanceCreatesBlockDevice() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String containerName = "floci-test-eks-node-" + suffix;
        ContainerSpec spec = containerBuilder.newContainer("alpine:3.21")
                .withName(containerName)
                .withPrivileged(true)
                .withEntrypoint(List.of("sleep", "3600"))
                .build();

        String containerId = lifecycleManager.createAndStart(spec).containerId();
        containersToClean.add(containerId);

        String instanceId = "i-" + suffix;
        Instance nodeInstance = new Instance();
        nodeInstance.setInstanceId(instanceId);
        nodeInstance.setDockerContainerId(containerId);
        nodeInstance.setRegion("us-east-1");
        Placement placement = new Placement();
        placement.setAvailabilityZone("us-east-1a");
        nodeInstance.setPlacement(placement);
        nodeInstance.setState(InstanceState.running());

        // Register via ClusterNodeInstanceProvider
        ec2Service.setClusterNodeInstanceProvider(new ClusterNodeInstanceProvider() {
            @Override
            public Optional<Instance> findInstance(String accountId, String region, String id) {
                if (instanceId.equals(id)) {
                    return Optional.of(nodeInstance);
                }
                return Optional.empty();
            }

            @Override
            public List<Instance> listInstances(String accountId, String region) {
                return List.of(nodeInstance);
            }
        });

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        VolumeAttachment attachment = ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instanceId, "/dev/xvdf");
        assertNotNull(attachment);

        ExecResult checkDev = execIn(containerId, "test -b /dev/xvdf || test -L /dev/xvdf");
        assertEquals(0, checkDev.exitCode(), "Block device should exist inside cluster node container: " + checkDev.output());

        ec2Service.setClusterNodeInstanceProvider(null);
    }

    @Test
    void attachVolumeGracefullyDegradesWhenContainerNotRunning() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = new Instance();
        instance.setInstanceId("i-" + suffix);
        instance.setDockerContainerId("non-existent-container-id");
        instance.setRegion("us-east-1");
        Placement placement = new Placement();
        placement.setAvailabilityZone("us-east-1a");
        instance.setPlacement(placement);
        instance.setState(InstanceState.running());
        ec2Service.putInstanceForTest(instance);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        // attachVolume succeeds at metadata level even if target container is missing
        VolumeAttachment att = ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf");
        assertNotNull(att);
        assertEquals("attached", att.getState());
    }

    @Test
    void attachVolumeRejectsInvalidDeviceName() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = createRunningTestInstance(suffix);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        AwsException ex1 = assertThrows(AwsException.class, () ->
                ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf$(touch /tmp/bad)"));
        assertEquals("InvalidParameterValue", ex1.getErrorCode());

        AwsException ex2 = assertThrows(AwsException.class, () ->
                ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/../etc/passwd"));
        assertEquals("InvalidParameterValue", ex2.getErrorCode());
    }

    @Test
    void attachVolumeRejectsDuplicateDeviceName() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = createRunningTestInstance(suffix);

        Volume vol1 = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(vol1.getVolumeId());
        Volume vol2 = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(vol2.getVolumeId());

        ec2Service.attachVolume("us-east-1", vol1.getVolumeId(), instance.getInstanceId(), "/dev/xvdf");

        AwsException ex = assertThrows(AwsException.class, () ->
                ec2Service.attachVolume("us-east-1", vol2.getVolumeId(), instance.getInstanceId(), "/dev/xvdf"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("already in use by volume"));
    }

    private ExecResult execIn(String containerIdOrName, String command) {
        try {
            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerIdOrName)
                    .withCmd("sh", "-c", command)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            StringBuilder sb = new StringBuilder();
            boolean done = dockerClient.execStartCmd(exec.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame != null && frame.getPayload() != null) {
                                sb.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);

            if (!done) {
                return new ExecResult(-1, "Timed out: " + sb);
            }
            Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            return new ExecResult(exitCode != null ? exitCode : -1, sb.toString());
        } catch (Exception e) {
            return new ExecResult(-1, e.getMessage());
        }
    }

    private record ExecResult(long exitCode, String output) {}
}
