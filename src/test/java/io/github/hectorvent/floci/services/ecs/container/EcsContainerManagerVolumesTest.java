package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.services.efs.EfsService;
import io.github.hectorvent.floci.services.efs.model.AccessPointDescription;
import io.github.hectorvent.floci.services.efs.model.CreationInfo;
import io.github.hectorvent.floci.services.efs.model.PosixUser;
import io.github.hectorvent.floci.services.efs.model.RootDirectory;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for task-definition {@code volumes} + container {@code mountPoints} handling in
 * {@link EcsContainerManager#startTask}: a container mountPoint resolves its source volume to
 * the task-level volume's host source path and bind-mounts it at the container path —
 * read-only via {@code withReadOnlyBind}, read-write via {@code withBind}. A mountPoint whose
 * source volume is not declared in the task definition is skipped (no bind).
 *
 * <p>The container builder and lifecycle manager are mocked, so the test asserts the bind
 * arguments that <em>would</em> be handed to Docker without launching one — runnable under
 * {@code mvn test} (CI) with no Docker daemon.
 */
class EcsContainerManagerVolumesTest {

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private LaunchedContainerAwsEnv awsEnv;
    private EcrRegistryManager ecrRegistryManager;
    private EcsContainerManager manager;
    private EfsService efsService;

    private static Stream<Volume> ephemeralVolumes() {
        return Stream.of(new Volume("shared", null),
                new Volume("shared", null, null, Map.of("host", Map.of())),
                new Volume("shared", "", null, Map.of("host", Map.of("sourcePath", ""))));
    }

    @ParameterizedTest
    @MethodSource("ephemeralVolumes")
    void ephemeralVolumeIsCreatedOnceAndSharedByContainersWithTheirAccessModes(Volume volume) {
        EcsTaskHandle handle = manager.startTask(task("000000000000", "us-east-1"),
                ephemeralDefinition(volume), List.of(), "us-east-1");

        ArgumentCaptor<String> volumeName = ArgumentCaptor.forClass(String.class);
        verify(builder).withNamedVolume(volumeName.capture(), eq("/data"), eq(false));
        verify(builder).withNamedVolume(volumeName.getValue(), "/data", true);
        verify(lifecycleManager).ensureVolume(eq(volumeName.getValue()), any());
        verify(lifecycleManager, times(1)).ensureVolume(anyString(), any());
        assertEquals(List.of(volumeName.getValue()), handle.getTaskVolumeNames().stream().toList());
        verify(builder, never()).withBind(any(), any());
    }

    @Test
    void taskVolumeIdentityIncludesAccountAndRegion() {
        TaskDefinition definition = ephemeralDefinition(new Volume("shared", null));
        manager.startTask(task("111111111111", "us-east-1"), definition, List.of(), "us-east-1");
        manager.startTask(task("222222222222", "us-east-1"), definition, List.of(), "us-east-1");
        manager.startTask(task("111111111111", "eu-west-1"), definition, List.of(), "eu-west-1");

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(lifecycleManager, times(3)).ensureVolume(names.capture(), any());
        assertEquals(3, names.getAllValues().stream().distinct().count());
    }

    @Test
    void unmountedAndUnsupportedVolumesAreNotCreatedAsEphemeralStorage() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(new MountPoint("external", "/external", false),
                new MountPoint("ebs", "/ebs", false)));
        TaskDefinition definition = new TaskDefinition();
        definition.setFamily("ephemeral-test");
        definition.setContainerDefinitions(List.of(app));
        definition.setVolumes(List.of(new Volume("unused", null),
                new Volume("external", null, null, Map.of("dockerVolumeConfiguration", Map.of("scope", "shared"))),
                new Volume("ebs", null, null, Map.of("configuredAtLaunch", true))));

        manager.startTask(task("000000000000", "us-east-1"), definition, List.of(), "us-east-1");

        verify(lifecycleManager, never()).ensureVolume(anyString(), any());
        verify(builder, never()).withNamedVolume(any(), any(), anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedLaunchRemovesItsEphemeralVolume(boolean firstContainerStarted) {
        if (firstContainerStarted) {
            when(lifecycleManager.createAndStartEphemeral(any(), any())).thenReturn(new ContainerInfo("docker-id", Map.of()))
                    .thenThrow(new IllegalStateException("launch failed"));
        } else {
            when(lifecycleManager.createAndStartEphemeral(any(), any())).thenThrow(new IllegalStateException("launch failed"));
        }
        assertThrows(IllegalStateException.class, () -> manager.startTask(task("000000000000", "us-east-1"),
                ephemeralDefinition(new Volume("shared", null)), List.of(), "us-east-1"));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(lifecycleManager).ensureVolume(name.capture(), any());
        verify(lifecycleManager).removeVolume(name.getValue());
    }

    @Test
    void volumeRemovalIsRetriedAfterContainersAreGone() {
        when(lifecycleManager.getDockerClient()).thenReturn(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        EcsContainerManager stopping = spy(manager);
        doReturn(0).when(stopping).getExitCodeIfStopped(anyString());
        doReturn(null).when(stopping).getFinishedAtIfStopped(anyString());
        EcsTaskHandle handle = stopping.startTask(task("000000000000", "us-east-1"),
                ephemeralDefinition(new Volume("shared", null)), List.of(), "us-east-1");
        String volumeName = handle.getTaskVolumeNames().iterator().next();
        when(lifecycleManager.removeVolume(volumeName)).thenReturn(false, true);

        stopping.stopTask(handle);
        assertTrue(handle.allContainersRemoved());
        assertTrue(handle.hasTaskVolumes());
        stopping.stopTask(handle);
        assertFalse(handle.hasTaskVolumes());
        verify(lifecycleManager, times(2)).removeVolume(volumeName);
    }

    @Test
    void volumeIsKeptUntilEveryContainerHasBeenRemoved() {
        EcsTaskHandle handle = manager.startTask(task("000000000000", "us-east-1"),
                ephemeralDefinition(new Volume("shared", null)), List.of(), "us-east-1");
        String name = handle.getTaskVolumeNames().iterator().next();
        doThrow(new IllegalStateException("remove failed")).when(lifecycleManager)
                .stopAndRemoveStrict("docker-id", null, 5, true);

        manager.cleanupStoppedTask(handle);
        assertTrue(handle.hasTaskVolumes());
        verify(lifecycleManager, never()).removeVolume(name);

        doNothing().when(lifecycleManager).stopAndRemoveStrict("docker-id", null, 5, true);
        when(lifecycleManager.removeVolume(name)).thenReturn(true);
        manager.cleanupStoppedTask(handle);
        assertFalse(handle.hasTaskVolumes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedLaunchPreservesResourcesWhenCleanupFails(boolean containerRemovalFails) {
        when(lifecycleManager.createAndStartEphemeral(any(), any())).thenReturn(new ContainerInfo("docker-id", Map.of()))
                .thenThrow(new IllegalStateException("launch failed"));
        if (containerRemovalFails) {
            doThrow(new IllegalStateException("remove failed")).when(lifecycleManager)
                    .stopAndRemoveStrict("docker-id", null, 5, true);
        } else {
            when(lifecycleManager.removeVolume(anyString())).thenReturn(false);
        }
        EcsTaskLaunchException failure = assertThrows(EcsTaskLaunchException.class,
                () -> manager.startTask(task("000000000000", "us-east-1"),
                        ephemeralDefinition(new Volume("shared", null)), List.of(), "us-east-1"));
        EcsTaskHandle handle = failure.getHandle();
        assertTrue(handle.hasTaskVolumes());
        assertEquals(!containerRemovalFails, handle.allContainersRemoved());
        assertEquals("launch failed", failure.getMessage());

        doNothing().when(lifecycleManager).stopAndRemoveStrict("docker-id", null, 5, true);
        when(lifecycleManager.removeVolume(anyString())).thenReturn(true);
        manager.cleanupStoppedTask(handle);
        assertTrue(handle.allContainersRemoved());
        assertFalse(handle.hasTaskVolumes());
    }

    @Test
    void recoveredVolumeNamesMatchLaunchAndExcludeUnusedAndExternalVolumes() {
        EcsTask task = task("000000000000", "us-east-1");
        TaskDefinition definition = ephemeralDefinition(new Volume("shared", null));
        EcsTaskHandle handle = manager.startTask(task, definition, List.of(), "us-east-1");
        assertEquals(handle.getTaskVolumeNames(), manager.taskVolumeNames(task, definition));
        definition.setVolumes(List.of(new Volume("shared", "/host/path")));
        assertTrue(manager.taskVolumeNames(task, definition).isEmpty());
        definition.setVolumes(List.of(new Volume("shared", null,
                new EfsVolumeConfiguration("fs-test", null, null, null, null, null))));
        assertTrue(manager.taskVolumeNames(task, definition).isEmpty());
    }

    private static TaskDefinition ephemeralDefinition(Volume volume) {
        ContainerDefinition writer = new ContainerDefinition();
        writer.setName("writer");
        writer.setImage("app:latest");
        writer.setMountPoints(List.of(new MountPoint("shared", "/data", false)));
        ContainerDefinition reader = new ContainerDefinition();
        reader.setName("reader");
        reader.setImage("app:latest");
        reader.setMountPoints(List.of(new MountPoint("shared", "/data", true)));
        TaskDefinition definition = new TaskDefinition();
        definition.setFamily("ephemeral-test");
        definition.setContainerDefinitions(List.of(writer, reader));
        definition.setVolumes(List.of(volume, new Volume("unused", null)));
        return definition;
    }

    private static EcsTask task(String account, String region) {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:" + region + ":" + account + ":task/test-cluster/same-task-id");
        return task;
    }

    @BeforeEach
    void setUp() {
        efsService = mock(EfsService.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.removeVolume(anyString())).thenReturn(true);
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), any()))
                .thenReturn(new ContainerInfo("docker-id", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        SsmService ssmService = mock(SsmService.class);
        SecretsManagerService secretsManagerService = mock(SecretsManagerService.class);
        ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        // These tests bind-mount hand-built "/host/abs/..." paths that don't sit under any
        // real approved root, so opt out of the fail-closed default explicitly; the Docker
        // socket ancestor block still applies regardless (see the rejection test below).
        when(config.services().ecs().allowUnsafeHostVolumes()).thenReturn(true);

        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, ssmService, secretsManagerService,
                mock(S3Service.class), ecrRegistryManager, new HostVolumePolicy(config));
    }

    @Test
    void mountPointsResolveTaskVolumesToHostBindsByAccessMode() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(
                new MountPoint("config-vol", "/app/config.yml", true),    // read-only
                new MountPoint("aws-vol", "/root/.aws", false),           // read-write
                new MountPoint("missing-vol", "/app/nope", true)));       // unresolved -> skipped

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setContainerDefinitions(List.of(app));
        // "/host/abs" has no drive letter on Windows, so build the absolute paths per platform.
        String configHostPath = Path.of("/host/abs/app/config.yml").toAbsolutePath().toString();
        String awsHostPath = Path.of("/host/abs/home/.aws").toAbsolutePath().toString();
        taskDef.setVolumes(List.of(
                new Volume("config-vol", configHostPath),
                new Volume("aws-vol", awsHostPath)));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDef, List.of(), "us-east-1");

        // Read-only mountPoint -> withReadOnlyBind(hostSourcePath, containerPath).
        ArgumentCaptor<String> roHost = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> roPath = ArgumentCaptor.forClass(String.class);
        verify(builder, times(1)).withReadOnlyBind(roHost.capture(), roPath.capture());
        assertEquals(configHostPath, roHost.getValue());
        assertEquals("/app/config.yml", roPath.getValue());

        // Read-write mountPoint -> withBind(hostSourcePath, containerPath).
        ArgumentCaptor<String> rwHost = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rwPath = ArgumentCaptor.forClass(String.class);
        verify(builder, times(1)).withBind(rwHost.capture(), rwPath.capture());
        assertEquals(awsHostPath, rwHost.getValue());
        assertEquals("/root/.aws", rwPath.getValue());

        // The unresolved "missing-vol" mountPoint contributes no bind beyond the two above.
        verify(builder, never()).withBind("/app/nope", "/app/nope");
    }

    /**
     * Each EFS volume resolves to a shared local Docker named volume keyed by file system id
     * AND effective root (here: rootDirectory, since neither volume sets an accessPointId),
     * read-write or read-only per the mountPoint.
     */
    @Test
    void efsVolumeMountPointsResolveToSharedNamedVolumesByAccessMode() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(
                new MountPoint("customer-data", "/mnt/efs", false),    // read-write
                new MountPoint("shared-ro", "/mnt/shared", true)));    // read-only

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("efs-family");
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setVolumes(List.of(
                new Volume("customer-data", null,
                        new EfsVolumeConfiguration("fs-0123456789abcdef0", "/dps", "ENABLED", null, null, null)),
                new Volume("shared-ro", null,
                        new EfsVolumeConfiguration("fs-00000000000000001", null, null, null, null, null))));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/efs1");

        manager.startTask(task, taskDef, List.of(), "us-east-1");

        // Each EFS volume -> a shared local Docker named volume under the current prefix (no
        // legacy-named volume exists here), read-write or read-only per the mountPoint.
        verify(builder, times(1)).withNamedVolume(
                "floci-aws-" + EcsContainerManager.efsVolumeToken("fs-0123456789abcdef0", null, "/dps"), "/mnt/efs", false);
        verify(builder, times(1)).withNamedVolume(
                "floci-aws-" + EcsContainerManager.efsVolumeToken("fs-00000000000000001", null, null), "/mnt/shared", true);

        // EFS volumes are never bind-mounted as host paths.
        verify(builder, never()).withBind(any(), any());
        verify(builder, never()).withReadOnlyBind(any(), any());
    }

    @Test
    void efsVolumesInitialiseSharedVolumeOwnershipFromConfig() {
        // Rebuild the manager with an EFS config that emulates an access point's CreationInfo +
        // PosixUser (owner 1001:1001, "2775" — 0775 plus the setgid special bit via the 4-digit
        // octal). The EFS mount must initialise the shared volume root with exactly those values
        // before mounting it.
        EmulatorConfig cfg = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(cfg.storage().efs().ownerUid()).thenReturn(OptionalInt.of(1001));
        when(cfg.storage().efs().ownerGid()).thenReturn(OptionalInt.of(1001));
        when(cfg.storage().efs().rootPermissions()).thenReturn(Optional.of("2775"));
        when(cfg.storage().efs().initImage()).thenReturn("busybox:stable");
        EcsContainerManager configured = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), cfg,
                mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), ecrRegistryManager, new HostVolumePolicy(cfg));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(new MountPoint("customer-data", "/mnt/efs", false)));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("efs-family");
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setVolumes(List.of(new Volume("customer-data", null,
                new EfsVolumeConfiguration("fs-abc", "/dps", "ENABLED", null, null, null))));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/efsown");

        configured.startTask(task, taskDef, List.of(), "us-east-1");

        verify(lifecycleManager, times(1)).ensureSharedVolume(
                "floci-aws-" + EcsContainerManager.efsVolumeToken("fs-abc", null, "/dps"),
                OptionalInt.of(1001), OptionalInt.of(1001), Optional.of("2775"), "busybox:stable");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accessPointSettingsOverrideEachFallbackIndependently(boolean creationInfo) {
        EmulatorConfig cfg = efsFallbackConfig();
        AccessPointDescription ap = new AccessPointDescription();
        ap.setFileSystemId("fs-test");
        if (creationInfo) {
            CreationInfo creation = new CreationInfo();
            creation.setOwnerUid(3000000000L);
            creation.setOwnerGid(3000000001L);
            creation.setPermissions("0750");
            RootDirectory root = new RootDirectory();
            root.setCreationInfo(creation);
            ap.setRootDirectory(root);
        } else {
            PosixUser user = new PosixUser();
            user.setUid(10001L);
            user.setGid(10002L);
            user.setSecondaryGids(List.of(20001L, 20002L));
            ap.setPosixUser(user);
        }
        when(efsService.describeAccessPoints("eu-west-1", null, "fsap-test")).thenReturn(List.of(ap));
        managerWithEfs(cfg).startTask(task("000000000000", "eu-west-1"),
                accessPointDefinition("fsap-test"), List.of(), "eu-west-1");
        if (creationInfo) {
            verify(lifecycleManager).ensureSharedVolume(anyString(), eq(3000000000L), eq(3000000001L),
                    eq("0750"), eq("busybox:stable"));
            verify(builder).withUser("3001:3002");
            verify(builder).withGroupAdd("4001");
        } else {
            verifyFallbackOwnership();
            verify(builder).withUser("42:42");
            verify(builder).withGroupAdd("10002");
            verify(builder).withGroupAdd("20001");
            verify(builder).withGroupAdd("20002");
            verify(builder, never()).withUser("10001:10002");
            verify(builder, never()).withUser("3001:3002");
            verify(builder, never()).withGroupAdd("4001");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accessPointWithoutSettingsOrUnavailableMetadataUsesFallback(boolean exists) {
        AccessPointDescription ap = new AccessPointDescription();
        ap.setFileSystemId("fs-test");
        when(efsService.describeAccessPoints("us-east-1", null, "fsap-test"))
                .thenReturn(exists ? List.of(ap) : List.of());
        managerWithEfs(efsFallbackConfig()).startTask(task("000000000000", "us-east-1"),
                accessPointDefinition("fsap-test"), List.of(), "us-east-1");
        verifyFallbackOwnership();
        verify(builder).withUser("3001:3002");
        verify(builder).withGroupAdd("4001");
    }

    @Test
    void accessPointOnAnotherFileSystemRejectsLaunch() {
        AccessPointDescription ap = new AccessPointDescription();
        ap.setFileSystemId("fs-other");
        when(efsService.describeAccessPoints("us-east-1", null, "fsap-test")).thenReturn(List.of(ap));
        AwsException failure = assertThrows(AwsException.class,
                () -> managerWithEfs(efsFallbackConfig()).startTask(task("000000000000", "us-east-1"),
                        accessPointDefinition("fsap-test"), List.of(), "us-east-1"));
        assertEquals("ClientException", failure.getErrorCode());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
    }

    @Test
    void accessPointPosixUserWithNullSecondaryGidSkipsNull() {
        EmulatorConfig cfg = efsFallbackConfig();
        AccessPointDescription ap = new AccessPointDescription();
        ap.setFileSystemId("fs-test");
        PosixUser user = new PosixUser();
        user.setUid(10001L);
        user.setGid(10002L);
        user.setSecondaryGids(Arrays.asList(20001L, null, 20002L));
        ap.setPosixUser(user);
        when(efsService.describeAccessPoints("eu-west-1", null, "fsap-test")).thenReturn(List.of(ap));
        managerWithEfs(cfg).startTask(task("000000000000", "eu-west-1"),
                accessPointDefinition("fsap-test"), List.of(), "eu-west-1");
        verify(builder).withGroupAdd("10002");
        verify(builder).withGroupAdd("20001");
        verify(builder).withGroupAdd("20002");
        verify(builder, never()).withGroupAdd("null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "888", "07777", ""})
    void accessPointWithInvalidCreationInfoPermissionsRejectsLaunch(String permissions) {
        AccessPointDescription ap = new AccessPointDescription();
        ap.setFileSystemId("fs-test");
        CreationInfo creation = new CreationInfo();
        creation.setOwnerUid(1000L);
        creation.setOwnerGid(1000L);
        creation.setPermissions(permissions);
        RootDirectory root = new RootDirectory();
        root.setCreationInfo(creation);
        ap.setRootDirectory(root);
        when(efsService.describeAccessPoints("us-east-1", null, "fsap-test")).thenReturn(List.of(ap));
        AwsException failure = assertThrows(AwsException.class,
                () -> managerWithEfs(efsFallbackConfig()).startTask(task("000000000000", "us-east-1"),
                        accessPointDefinition("fsap-test"), List.of(), "us-east-1"));
        assertEquals("ClientException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("permissions"));
    }

    private EmulatorConfig efsFallbackConfig() {
        EmulatorConfig cfg = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(cfg.storage().efs().ownerUid()).thenReturn(OptionalInt.of(1001));
        when(cfg.storage().efs().ownerGid()).thenReturn(OptionalInt.of(1002));
        when(cfg.storage().efs().rootPermissions()).thenReturn(Optional.of("0775"));
        when(cfg.storage().efs().initImage()).thenReturn("busybox:stable");
        when(cfg.storage().efs().mountUser()).thenReturn(Optional.of("3001:3002"));
        when(cfg.storage().efs().mountGroupAdd()).thenReturn(OptionalInt.of(4001));
        return cfg;
    }

    private EcsContainerManager managerWithEfs(EmulatorConfig cfg) {
        return new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), cfg,
                mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class), ecrRegistryManager, new HostVolumePolicy(cfg),
                null, null, null, null, null, efsService);
    }

    private static TaskDefinition accessPointDefinition(String accessPointId) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setUser("42:42");
        app.setMountPoints(List.of(new MountPoint("data", "/data", false)));
        TaskDefinition definition = new TaskDefinition();
        definition.setFamily("efs-access-point");
        definition.setContainerDefinitions(List.of(app));
        definition.setVolumes(List.of(new Volume("data", null,
                new EfsVolumeConfiguration("fs-test", null, "ENABLED", null, accessPointId, null))));
        return definition;
    }

    private void verifyFallbackOwnership() {
        verify(lifecycleManager).ensureSharedVolume(anyString(), eq(OptionalInt.of(1001)), eq(OptionalInt.of(1002)),
                eq(Optional.of("0775")), eq("busybox:stable"));
    }

    @Test
    void efsMountAppliesConfiguredPosixUserAndGroupToContainer() {
        // Rebuild with an EFS config carrying a PosixUser (mount-user 1001:1001) and a supplementary
        // group (mount-group-add 2000). A container mounting the EFS volume must run under that user
        // and add that group, emulating the access point's PosixUser squashing — otherwise the
        // container keeps its image USER and cannot write the volume owned by ownerUid/ownerGid.
        EmulatorConfig cfg = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(cfg.storage().efs().mountUser()).thenReturn(Optional.of("1001:1001"));
        when(cfg.storage().efs().mountGroupAdd()).thenReturn(OptionalInt.of(2000));
        EcsContainerManager configured = new EcsContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), cfg,
                mock(RegionResolver.class), awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class),
                ecrRegistryManager, new HostVolumePolicy(cfg));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(new MountPoint("customer-data", "/mnt/efs", false)));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("efs-family");
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setVolumes(List.of(new Volume("customer-data", null,
                new EfsVolumeConfiguration("fs-abc", "/dps", "ENABLED", null, null, null))));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/efsposix");

        configured.startTask(task, taskDef, List.of(), "us-east-1");

        verify(builder, times(1)).withUser("1001:1001");
        verify(builder, times(1)).withGroupAdd("2000");
    }

    /**
     * The host-volume policy is enforced again at mount time, immediately before the bind
     * call, not only once at RegisterTaskDefinition time. A mount whose source is an ancestor
     * of the Docker socket (here {@code /var/run}, which contains {@code docker.sock}) must be
     * rejected even though this test class's setUp already allows unsafe host volumes: the
     * socket-ancestor block applies unconditionally. The rejection must propagate out of
     * startTask (not be swallowed) and no bind must have been issued for it.
     */
    @Test
    void startTaskRejectsMountThatExposesDockerSocketEvenWhenUnsafeVolumesAllowed() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setMountPoints(List.of(new MountPoint("run-vol", "/host-run", false)));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("socket-family");
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setVolumes(List.of(new Volume("run-vol", "/var/run")));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/socket1");

        assertThrows(AwsException.class, () -> manager.startTask(task, taskDef, List.of(), "us-east-1"));

        verify(builder, never()).withBind(any(), any());
        verify(builder, never()).withReadOnlyBind(any(), any());
    }
}
