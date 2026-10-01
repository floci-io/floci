package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.ec2.model.VolumeModification;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
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
    private final List<String> syntheticRootVolumesToClean = new ArrayList<>();
    private final List<String> instancesToClean = new ArrayList<>();

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

        for (String volId : syntheticRootVolumesToClean) {
            try {
                ec2Service.deleteVolumeForTest("us-east-1", volId);
            } catch (Exception e) {
                LOG.debugv("Ignoring delete synthetic root volume error during test cleanup: {0}", e.getMessage());
            }
        }
        syntheticRootVolumesToClean.clear();

        for (String iid : instancesToClean) {
            try {
                ec2Service.deleteInstanceForTest("us-east-1", iid);
            } catch (Exception e) {
                LOG.debugv("Ignoring delete test instance error during test cleanup: {0}", e.getMessage());
            }
        }
        instancesToClean.clear();

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
        String rootVolId = "vol-root-" + suffix;
        instance.setDockerContainerId(containerId);
        instance.setRegion("us-east-1");
        instance.setRootVolumeId(rootVolId);
        instance.setRootDeviceName("/dev/xvda");
        Placement placement = new Placement();
        placement.setAvailabilityZone("us-east-1a");
        instance.setPlacement(placement);
        instance.setState(InstanceState.running());

        Volume rootVol = new Volume();
        rootVol.setVolumeId(rootVolId);
        rootVol.setVolumeType("gp3");
        rootVol.setSize(8);
        rootVol.setState("in-use");
        rootVol.setRegion("us-east-1");
        rootVol.setAvailabilityZone("us-east-1a");
        VolumeAttachment rootAtt = new VolumeAttachment();
        rootAtt.setVolumeId(rootVolId);
        rootAtt.setInstanceId(instanceId);
        rootAtt.setDevice("/dev/xvda");
        rootAtt.setState("attached");
        rootVol.getAttachments().add(rootAtt);
        ec2Service.putVolumeForTest(rootVol);
        syntheticRootVolumesToClean.add(rootVolId);
        instancesToClean.add(instanceId);

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
        ContainerExec.Result checkDev = execIn(targetContainerId, "test -b /dev/xvdf || test -L /dev/xvdf");
        assertEquals(0, checkDev.exitCode(), "Device /dev/xvdf should exist as block or symlink: " + checkDev.summary());

        // 2. Size matches 1 GiB (1073741824 bytes)
        ContainerExec.Result checkSize = execIn(targetContainerId, "blockdev --getsize64 /dev/xvdf");
        assertEquals(0, checkSize.exitCode(), "blockdev query should succeed: " + checkSize.summary());
        assertEquals("1073741824", checkSize.stdout().trim());

        // 3. Format filesystem, mount, write data, unmount
        ContainerExec.Result mkfs = execIn(targetContainerId, "mkfs.ext4 -F /dev/xvdf");
        assertEquals(0, mkfs.exitCode(), "mkfs.ext4 should succeed: " + mkfs.summary());

        ContainerExec.Result mountAndWrite = execIn(targetContainerId,
                "mkdir -p /mnt/vol && mount /dev/xvdf /mnt/vol && echo 'floci-ebs-test-data' > /mnt/vol/test.txt && cat /mnt/vol/test.txt && umount /mnt/vol");
        assertEquals(0, mountAndWrite.exitCode(), "Mount, write, read and umount should succeed: " + mountAndWrite.summary());
        assertTrue(mountAndWrite.stdout().contains("floci-ebs-test-data"));
    }

    @Test
    void detachingRemovesDeviceAndDeletingRemovesBackingFile() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = createRunningTestInstance(suffix);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf");
        String targetContainerId = instance.getDockerContainerId();

        ContainerExec.Result existsBefore = execIn(targetContainerId, "test -e /dev/xvdf");
        assertEquals(0, existsBefore.exitCode());

        // Detach volume
        VolumeAttachment detached = ec2Service.detachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf", false);
        assertEquals("detached", detached.getState());

        // Device node should be removed from container
        ContainerExec.Result existsAfter = execIn(targetContainerId, "test ! -e /dev/xvdf");
        assertEquals(0, existsAfter.exitCode(), "Device /dev/xvdf should no longer exist in container");

        // Delete volume
        ec2Service.deleteVolume("us-east-1", volume.getVolumeId());
        volumesToClean.remove(volume.getVolumeId());

        // Verify helper container removed backing file
        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        ContainerExec.Result checkFile = execIn(helperName, "test ! -f /volumes/" + volume.getVolumeId() + ".raw");
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
        ContainerExec.Result formatAndWrite = execIn(instance1.getDockerContainerId(),
                "mkfs.ext4 -F /dev/xvdf && mkdir -p /mnt/vol1 && mount /dev/xvdf /mnt/vol1 && echo 'persisted-payload' > /mnt/vol1/hello.txt && umount /mnt/vol1");
        assertEquals(0, formatAndWrite.exitCode(), formatAndWrite.summary());

        // Detach from instance1
        ec2Service.detachVolume("us-east-1", volume.getVolumeId(), instance1.getInstanceId(), "/dev/xvdf", false);

        // Re-attach to instance2 at /dev/xvdg
        ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance2.getInstanceId(), "/dev/xvdg");

        // Verify device exists on instance2 and data is preserved
        ContainerExec.Result verifyData = execIn(instance2.getDockerContainerId(),
                "mkdir -p /mnt/vol2 && mount /dev/xvdg /mnt/vol2 && cat /mnt/vol2/hello.txt && umount /mnt/vol2");
        assertEquals(0, verifyData.exitCode(), "Data should be preserved across detach and re-attach: " + verifyData.summary());
        assertTrue(verifyData.stdout().contains("persisted-payload"));
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
        ContainerExec.Result deleted = execIn(targetContainerId, "test ! -e /dev/xvdf");
        assertEquals(0, deleted.exitCode());

        // Call restoreInstanceVolumeDevices
        volumeBlockDeviceManager.restoreInstanceVolumeDevices(instance, volume.getAttachments(),
                volId -> Optional.of(volume));

        // Verify device node is restored
        ContainerExec.Result restored = execIn(targetContainerId, "test -b /dev/xvdf || test -L /dev/xvdf");
        assertEquals(0, restored.exitCode(), "Device /dev/xvdf should be restored: " + restored.summary());
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

        ContainerExec.Result checkDev = execIn(containerId, "test -b /dev/xvdf || test -L /dev/xvdf");
        assertEquals(0, checkDev.exitCode(), "Block device should exist inside cluster node container: " + checkDev.summary());

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

    @Test
    void modifyVolumeAttachedExpandsBackingFileLoopDeviceAndAllowsWritingBeyondOriginalCapacity() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Instance instance = createRunningTestInstance(suffix);

        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        VolumeAttachment attachment = ec2Service.attachVolume("us-east-1", volume.getVolumeId(), instance.getInstanceId(), "/dev/xvdf");
        assertNotNull(attachment);

        String targetContainerId = instance.getDockerContainerId();

        // 1. Initial size is 1 GiB (1073741824 bytes)
        ContainerExec.Result checkInitialSize = execIn(targetContainerId, "blockdev --getsize64 /dev/xvdf");
        assertEquals(0, checkInitialSize.exitCode());
        assertEquals("1073741824", checkInitialSize.stdout().trim());

        // 2. Modify volume to 2 GiB
        VolumeModification mod = ec2Service.modifyVolume("us-east-1", volume.getVolumeId(), 2, null, null, null, null, false);
        assertNotNull(mod);
        assertEquals("completed", mod.getModificationState());
        assertEquals(2, mod.getTargetSize());

        // 3. Backing file size on helper is 2 GiB (2147483648 bytes)
        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        ContainerExec.Result checkFileSize = execIn(helperName, "stat -c %s /volumes/" + volume.getVolumeId() + ".raw");
        assertEquals(0, checkFileSize.exitCode());
        assertEquals("2147483648", checkFileSize.stdout().trim());

        // 4. Loop device capacity inside instance container refreshed to 2 GiB
        ContainerExec.Result checkResizedSize = execIn(targetContainerId, "blockdev --getsize64 /dev/xvdf");
        assertEquals(0, checkResizedSize.exitCode());
        assertEquals("2147483648", checkResizedSize.stdout().trim());

        // 5. Writing beyond the original 1 GiB capacity (at 1.5 GiB offset) succeeds
        ContainerExec.Result writeBeyond = execIn(targetContainerId,
                "dd if=/dev/zero of=/dev/xvdf bs=1M count=10 seek=1500 conv=notrunc");
        assertEquals(0, writeBeyond.exitCode(), "Writing beyond original capacity should succeed: " + writeBeyond.summary());
    }

    @Test
    void modifyVolumeUnattachedGrowsBackingFile() {
        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 1, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        ContainerExec.Result checkInitialSize = execIn(helperName, "stat -c %s /volumes/" + volume.getVolumeId() + ".raw");
        assertEquals(0, checkInitialSize.exitCode());
        assertEquals("1073741824", checkInitialSize.stdout().trim());

        VolumeModification mod = ec2Service.modifyVolume("us-east-1", volume.getVolumeId(), 3, null, null, null, null, false);
        assertNotNull(mod);
        assertEquals("completed", mod.getModificationState());

        ContainerExec.Result checkResizedSize = execIn(helperName, "stat -c %s /volumes/" + volume.getVolumeId() + ".raw");
        assertEquals(0, checkResizedSize.exitCode());
        assertEquals("3221225472", checkResizedSize.stdout().trim());
    }

    @Test
    void modifyVolumeRejectsSizeDecreaseLeavingBackingFileUntouched() {
        Volume volume = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 5, false, 3000, 125, null, null);
        volumesToClean.add(volume.getVolumeId());

        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        ContainerExec.Result checkInitialSize = execIn(helperName, "stat -c %s /volumes/" + volume.getVolumeId() + ".raw");
        assertEquals(0, checkInitialSize.exitCode());
        assertEquals("5368709120", checkInitialSize.stdout().trim());

        AwsException ex = assertThrows(AwsException.class, () ->
                ec2Service.modifyVolume("us-east-1", volume.getVolumeId(), 3, null, null, null, null, false));
        assertEquals("InvalidParameterValue", ex.getErrorCode());

        ContainerExec.Result checkAfterSize = execIn(helperName, "stat -c %s /volumes/" + volume.getVolumeId() + ".raw");
        assertEquals(0, checkAfterSize.exitCode());
        assertEquals("5368709120", checkAfterSize.stdout().trim());
    }

    @Test
    void modifyVolumeRootVolumeUpdatesMetadataWithoutCreatingBackingFile() {
        Instance instance = createRunningTestInstance("root-resize");
        String rootVolId = instance.getRootVolumeId();
        assertNotNull(rootVolId);

        VolumeModification mod = ec2Service.modifyVolume("us-east-1", rootVolId, 16, null, null, null, null, false);
        assertNotNull(mod);
        assertEquals("completed", mod.getModificationState());
        assertEquals(16, mod.getTargetSize());

        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        ContainerExec.Result checkFile = execIn(helperName, "test -f /volumes/" + rootVolId + ".raw");
        assertTrue(checkFile.exitCode() != 0);
    }

    @Test
    void modifyVolumeDataVolumeAttachedAtRootDeviceResizesBackingFile() {
        Instance instance = createRunningTestInstance("data-at-root");
        String rootVolId = instance.getRootVolumeId();
        assertNotNull(rootVolId);

        ec2Service.stopInstances("us-east-1", List.of(instance.getInstanceId()));
        await().atMost(Duration.ofSeconds(35)).until(() ->
                "stopped".equals(ec2Service.describeInstances("us-east-1", List.of(instance.getInstanceId()), null)
                        .getFirst().getInstances().getFirst().getState().getName()));
        ec2Service.detachVolume("us-east-1", rootVolId, instance.getInstanceId(), instance.getRootDeviceName(), true);

        // Detached root volume is no longer attached as an instance root volume; resizing it creates
        // and expands its backing raw file so it can be reattached as a data volume.
        VolumeModification rootMod = ec2Service.modifyVolume("us-east-1", rootVolId, 12, null, null, null, null, false);
        assertNotNull(rootMod);
        assertEquals("completed", rootMod.getModificationState());
        assertEquals(12, rootMod.getTargetSize());

        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        ContainerExec.Result checkRootFile = execIn(helperName, "stat -c %s /volumes/" + rootVolId + ".raw");
        assertEquals(0, checkRootFile.exitCode());
        assertEquals("12884901888", checkRootFile.stdout().trim());
        volumesToClean.add(rootVolId);
        syntheticRootVolumesToClean.remove(rootVolId);

        Volume dataVol = ec2Service.createVolume("us-east-1", "us-east-1a", "gp3", 5, false, 3000, 125, null, null);
        volumesToClean.add(dataVol.getVolumeId());

        ec2Service.attachVolume("us-east-1", dataVol.getVolumeId(), instance.getInstanceId(), instance.getRootDeviceName());

        VolumeModification mod = ec2Service.modifyVolume("us-east-1", dataVol.getVolumeId(), 10, null, null, null, null, false);
        assertNotNull(mod);
        assertEquals("completed", mod.getModificationState());
        assertEquals(10, mod.getTargetSize());

        ContainerExec.Result checkFile = execIn(helperName, "stat -c %s /volumes/" + dataVol.getVolumeId() + ".raw");
        assertEquals(0, checkFile.exitCode());
        assertEquals("10737418240", checkFile.stdout().trim());
    }

    private ContainerExec.Result execIn(String containerIdOrName, String command) {
        try {
            return ContainerExec.runMerged(dockerClient, containerIdOrName, new String[]{"sh", "-c", command}, 30);
        } catch (RuntimeException e) {
            return new ContainerExec.Result(-1, "", Objects.requireNonNullElse(e.getMessage(), e.toString()), false);
        }
    }
}
