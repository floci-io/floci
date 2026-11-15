package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.Volume;
import io.floci.aws.services.ec2.model.VolumeAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers how the volume manager drives its helper container without a Docker daemon: the command
 * it runs there, and that a Docker failure while running it is reported rather than thrown.
 */
class Ec2VolumeBlockDeviceManagerTest {

    private static final String HELPER_ID = "helper-container-id";
    private static final String CREATED_HELPER_ID = "created-helper-container-id";
    private static final String HELPER_IMAGE = "alpine:3.21";

    private EmulatorConfig config;
    private DockerClient dockerClient;
    private InspectContainerResponse helper;
    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private Ec2VolumeBlockDeviceManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);

        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        helper = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(helper.getState().getRunning()).thenReturn(true);
        when(helper.getId()).thenReturn(HELPER_ID);
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenReturn(helper);

        containerBuilder = mock(ContainerBuilder.class);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        containerDetector = mock(ContainerDetector.class);
        manager = new Ec2VolumeBlockDeviceManager(dockerClient, containerBuilder,
                lifecycleManager, containerDetector, config);
    }

    @Test
    void attachVolumeCreatesTheDeviceBeforeReportingSuccess() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "1 /dev/loop3", "");
        List<List<String>> targetCommands = ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");

        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), runningInstance(), "xvdf"));
        assertEquals(1, helperCommands.size());
        assertEquals(1, targetCommands.size());
        assertTrue(targetCommands.getFirst().get(2).endsWith("[ -b \"$dev\" ]"));
        assertEquals("/dev/xvdf", targetCommands.getFirst().get(4));
    }

    @Test
    void loopAllocationFailureReturnsAnAwsErrorWithoutReleasingUnownedLoops() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                List.of(1L, 0L), "", "permission denied");

        AwsException error = assertThrows(AwsException.class,
                () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));

        assertEquals("InternalError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
        assertFalse(error.getMessage().contains("permission denied"));
        assertEquals(1, commands.size());
        verify(dockerClient, never()).execCreateCmd("guest");
    }

    @Test
    void guestDeviceFailureCleansUpAndAllowsRetry() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "1 /dev/loop3", "");
        List<List<String>> targetCommands = ContainerExecStubs.completeEveryExec(dockerClient, "guest",
                List.of(1L, 0L, 0L), "", "");
        Instance instance = runningInstance();

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), instance, "/dev/xvdf"));
        assertEquals(List.of("rm", "-f", "/dev/xvdf"), targetCommands.getLast());
        assertEquals(List.of("losetup", "-d", "/dev/loop3"), helperCommands.getLast());
        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), instance, "/dev/xvdf"));
    }

    @Test
    void failedRestoreDoesNotReleaseTheReusedLoopAndCanBeRetried() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "0 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", List.of(1L, 0L, 0L), "", "");
        Volume volume = volume();
        VolumeAttachment attachment = new VolumeAttachment();
        attachment.setVolumeId(volume.getVolumeId());
        attachment.setDevice("/dev/xvdf");
        attachment.setState("attached");
        volume.setState("in-use");
        volume.getAttachments().add(attachment);
        Instance instance = runningInstance();

        assertDoesNotThrow(() -> manager.restoreInstanceVolumeDevices(instance, List.of(attachment),
                id -> Optional.of(volume)));

        assertEquals(1, helperCommands.size(), "restoration must not detach a pre-existing loop");
        assertEquals("in-use", volume.getState());
        assertEquals("attached", attachment.getState());
        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume, instance, "/dev/xvdf"));
        assertEquals(2, helperCommands.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void helperReportsWhetherItAllocatedOrReusedTheLoop(boolean reused, @TempDir Path directory) throws Exception {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                0, "1 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");
        manager.attachVolume(volume(), runningInstance(), "/dev/xvdf");
        String script = helperCommands.getFirst().get(2);
        String fakeCommands = "losetup() {\n"
                + "  case \"$1\" in\n"
                + "    -j) " + (reused ? "echo \"/dev/loop3: 0 $2\"" : ":") + ";;\n"
                + "    -f) echo /dev/loop3;;\n"
                + "    /dev/loop3) :;;\n"
                + "    *) return 1;;\n"
                + "  esac\n"
                + "}\n"
                + "mknod() { :; }\n";
        Path rawFile = Files.createFile(directory.resolve("volume.raw"));
        int exitCode = HelperShellFixture.run(
                new String[]{"sh", "-c", fakeCommands + script, "helper", rawFile.toString(), "1"}, directory, Map.of());
        String output = HelperShellFixture.output(directory).trim();
        assertEquals(0, exitCode, output);
        assertEquals((reused ? "0" : "1") + " /dev/loop3", output);
    }

    @Test
    void cleanupFailureStillReportsTheOriginalAttachmentError() {
        List<List<String>> helperCommands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID,
                List.of(0L, 1L), "1 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", 1, "", "permission denied");

        AwsException error = assertThrows(AwsException.class,
                () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));

        assertEquals("InternalError", error.getErrorCode());
        assertEquals(2, helperCommands.size());
        assertEquals(List.of("losetup", "-d", "/dev/loop3"), helperCommands.getLast());
    }

    @Test
    void mockModeRetainsMetadataOnlyTracking() {
        when(config.services().ec2().mock()).thenReturn(true);

        assertEquals(Optional.empty(), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void unexpectedLoopOutputFailsWithoutCreatingAGuestDevice() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "not a loop device", "");

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd("guest");
    }

    @Test
    void dockerExecExceptionFailsAttachment() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
    }

    @Test
    void unavailableHelperFailsAttachment() {
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new IllegalStateException("unavailable"));

        assertThrows(AwsException.class, () -> manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
    }

    @Test
    void disabledBlockDevicesRetainMetadataOnlyTracking() {
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);

        assertEquals(Optional.empty(), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void unavailableDockerRetainsMetadataOnlyTracking() {
        when(dockerClient.pingCmd().exec()).thenThrow(new IllegalStateException("unavailable"));

        assertEquals(Optional.empty(), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void stoppedInstanceDefersDeviceCreationEvenWhenContainerIsStillRunning() {
        Instance instance = runningInstance();
        instance.setState(InstanceState.stopped());

        assertEquals(Optional.empty(), manager.attachVolume(volume(), instance, "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void missingOrStoppedContainerRetainsMetadataOnlyTracking() {
        Instance instance = runningInstance();
        when(lifecycleManager.isContainerRunning("guest")).thenReturn(false);
        assertEquals(Optional.empty(), manager.attachVolume(volume(), instance, "/dev/xvdf"));
        instance.setDockerContainerId(null);
        assertEquals(Optional.empty(), manager.attachVolume(volume(), instance, "/dev/xvdf"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void restorationLogsAttachmentFailureAndKeepsExistingRecords() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 1, "", "permission denied");
        VolumeAttachment attachment = new VolumeAttachment();
        attachment.setVolumeId("vol-1");
        attachment.setDevice("/dev/xvdf");
        attachment.setState("attached");

        assertDoesNotThrow(() -> manager.restoreInstanceVolumeDevices(runningInstance(), List.of(attachment),
                id -> Optional.of(volume())));
        assertEquals("attached", attachment.getState());
    }

    /**
     * Inside a container the host persistent path names a directory on the Docker host, which
     * Floci can neither see nor create. Creating it from here used to fail, so no helper started
     * and every attachment reported the helper unavailable; the daemon creates the bind source.
     */
    @Test
    void helperInsideAContainerLeavesCreatingTheHostBindDirectoryToDocker(@TempDir Path directory) {
        Path hostPersistentPath = directory.resolve("floci-data");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        ContainerBuilder.Builder helperBuilder = helperToBeCreatedOn(hostPersistentPath);

        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        assertFalse(Files.exists(hostPersistentPath), "Floci must not create the Docker host's directory itself");
        verify(helperBuilder).withBind(hostPersistentPath.resolve("ec2").resolve("volumes").toString(), "/volumes");
    }

    @Test
    void helperOnTheHostCreatesTheBindDirectoryBeforeBindingIt(@TempDir Path directory) {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        ContainerBuilder.Builder helperBuilder = helperToBeCreatedOn(directory);

        assertEquals(Optional.of("/dev/xvdf"), manager.attachVolume(volume(), runningInstance(), "/dev/xvdf"));
        assertTrue(Files.isDirectory(directory.resolve("ec2").resolve("volumes")));
        verify(helperBuilder).withBind(directory.resolve("ec2").resolve("volumes").toString(), "/volumes");
    }

    /**
     * Stubs bind-mode storage under {@code hostPersistentPath} with no helper container yet, so the
     * next operation creates the helper through the returned builder and its commands succeed.
     */
    private ContainerBuilder.Builder helperToBeCreatedOn(Path hostPersistentPath) {
        when(config.storage().hostPersistentPath()).thenReturn(hostPersistentPath.toString());
        when(config.services().ec2().volumeHelperImage()).thenReturn(HELPER_IMAGE);
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("no such container"));
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerSpec spec = new ContainerSpec(HELPER_IMAGE);
        when(containerBuilder.newContainer(HELPER_IMAGE)).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.createAndStart(spec))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo(CREATED_HELPER_ID, Map.of()));
        ContainerExecStubs.completeEveryExec(dockerClient, CREATED_HELPER_ID, 0, "1 /dev/loop3", "");
        ContainerExecStubs.completeEveryExec(dockerClient, "guest", 0, "", "");
        return builder;
    }

    private Instance runningInstance() {
        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        instance.setState(InstanceState.running());
        instance.setDockerContainerId("guest");
        when(lifecycleManager.isContainerRunning("guest")).thenReturn(true);
        return instance;
    }

    private Volume volume() {
        Volume volume = new Volume();
        volume.setVolumeId("vol-1");
        volume.setSize(1);
        return volume;
    }

    @Test
    void stopManagedContainersReleasesLoopDevicesAndRemovesTheHelper() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.stopManagedContainers();

        assertEquals(1, commands.size());
        String script = commands.getFirst().get(2);
        assertTrue(script.contains("losetup -d \"$loop\""));
        assertFalse(script.contains("rm -f"), "backing files must survive a shutdown");
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void stopManagedContainersRemovesAStoppedHelperWithoutRunningAnythingInIt() {
        when(helper.getState().getRunning()).thenReturn(false);

        manager.stopManagedContainers();

        verify(dockerClient, never()).execCreateCmd(anyString());
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void stopManagedContainersNeverCreatesAHelperThatDoesNotExist() {
        when(dockerClient.inspectContainerCmd(anyString()).exec()).thenThrow(new NotFoundException("no such container"));

        manager.stopManagedContainers();

        verifyNoInteractions(lifecycleManager);
    }

    @Test
    void stopManagedContainersStillRemovesTheHelperWhenReleasingLoopDevicesFails() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertDoesNotThrow(() -> manager.stopManagedContainers());

        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void stopManagedContainersFailsTheReleaseScriptWhenALoopDeviceWillNotDetach() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 1,
                "could not detach /dev/loop3 from /volumes/vol-1.raw", "");

        assertDoesNotThrow(() -> manager.stopManagedContainers());

        String script = commands.getFirst().get(2);
        assertTrue(script.contains("|| { echo \"could not detach $loop from $f\"; failed=1; }"));
        assertTrue(script.endsWith("exit $failed"));
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void clearDeletesTheBackingFilesAndRemovesTheHelper() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.clear();

        assertEquals(1, commands.size());
        assertTrue(commands.getFirst().get(2).contains("rm -f \"$f\""));
        verify(lifecycleManager).stopAndRemove(HELPER_ID, null, 0);
    }

    @Test
    void stopManagedContainersDoesNothingWhenBlockDevicesAreDisabled() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);
        Ec2VolumeBlockDeviceManager disabledManager = new Ec2VolumeBlockDeviceManager(dockerClient,
                mock(ContainerBuilder.class), lifecycleManager, mock(ContainerDetector.class), config);

        disabledManager.stopManagedContainers();

        verify(lifecycleManager, never()).stopAndRemove(anyString(), any(), anyInt());
    }

    @Test
    void createVolumeSizesTheBackingFileInsideTheHelperContainer() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        manager.createVolume("vol-1", 0);

        assertEquals(List.of(List.of("sh", "-c", "truncate -s 8G /volumes/vol-1.raw")), commands);
    }

    @Test
    void aDockerFailureWhileRunningTheCommandIsNotThrown() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertDoesNotThrow(() -> manager.createVolume("vol-1", 8));
    }

    @Test
    void resizeVolumeSizesBackingFileAndRefreshesLoopDevicesInsideHelperContainer() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 0, "", "");

        boolean ok = manager.resizeVolume("vol-1", 16);

        assertTrue(ok);
        assertEquals(1, commands.size());
        List<String> cmd = commands.getFirst();
        assertEquals("sh", cmd.get(0));
        assertEquals("-c", cmd.get(1));
        assertEquals("resize", cmd.get(3));
        assertEquals("/volumes/vol-1.raw", cmd.get(4));
        assertEquals("16", cmd.get(5));
        assertTrue(cmd.get(2).contains("truncate -s \"${size}G\" \"$raw\""));
        assertTrue(cmd.get(2).contains("losetup -c \"$loop\""));
        assertTrue(cmd.get(2).contains("cur_bytes=$(stat -c %s \"$raw\" 2>/dev/null || echo 0)"));
        assertTrue(cmd.get(2).contains("if [ \"$cur_bytes\" -lt \"$target_bytes\" ]; then"));
        assertTrue(cmd.get(2).contains("truncate -s \"$cur_bytes\" \"$raw\""));
        assertTrue(cmd.get(2).contains("for rloop in $(find_loops \"$raw\"); do"));
    }

    @Test
    void resizeVolumeDockerFailureWhileRunningIsNotThrown() {
        when(dockerClient.execCreateCmd(HELPER_ID)).thenThrow(new IllegalStateException("daemon went away"));

        assertDoesNotThrow(() -> {
            boolean ok = manager.resizeVolume("vol-1", 16);
            assertFalse(ok);
        });
    }

    @Test
    void resizeVolumeTruncateFailureLogsAndReturnsFalseWithoutThrowing() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 1, "", "truncate: out of space");

        assertDoesNotThrow(() -> {
            boolean ok = manager.resizeVolume("vol-1", 16);
            assertFalse(ok);
        });
    }

    @Test
    void resizeVolumeRefreshFailureLogsAndReturnsFalseWithoutThrowing() {
        ContainerExecStubs.completeEveryExec(dockerClient, HELPER_ID, 2, "", "losetup: invalid option -- c");

        assertDoesNotThrow(() -> {
            boolean ok = manager.resizeVolume("vol-1", 16);
            assertFalse(ok);
        });
    }

    @Test
    void resizeVolumeReturnsFalseWhenNotAvailable() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().volumeBlockDevices()).thenReturn(false);
        Ec2VolumeBlockDeviceManager disabledManager = new Ec2VolumeBlockDeviceManager(dockerClient,
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerDetector.class), config);

        assertFalse(disabledManager.resizeVolume("vol-1", 16));
    }
}
