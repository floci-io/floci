package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.services.efs.EfsService;
import io.github.hectorvent.floci.services.efs.model.AccessPointDescription;
import io.github.hectorvent.floci.services.efs.model.CreateAccessPointRequest;
import io.github.hectorvent.floci.services.efs.model.CreateFileSystemRequest;
import io.github.hectorvent.floci.services.efs.model.CreationInfo;
import io.github.hectorvent.floci.services.efs.model.PosixUser;
import io.github.hectorvent.floci.services.efs.model.RootDirectory;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Docker-backed proof that an EFS-configured task volume's local mount is scoped to
 * {@code (fileSystemId, rootDirectory, accessPointId)}, not just {@code fileSystemId} (#2563).
 * Each scenario runs a real busybox container against the same {@code fileSystemId}, writing
 * and checking marker files under {@code /mnt/efs} with a shell one-liner whose exit code
 * proves whether the previous task's data was visible.
 */
@QuarkusTest
class EcsContainerManagerEfsIsolationDockerIntegrationTest {

    private static final String IMAGE = TestImages.BUSYBOX;

    @Inject
    EcsContainerManager containerManager;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    DockerClient dockerClient;

    @Inject
    EfsService efsService;

    private final List<String> volumesToCleanUp = new ArrayList<>();

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for ECS EFS isolation integration tests");
    }

    @AfterEach
    void cleanUpVolumes() {
        volumesToCleanUp.forEach(lifecycleManager::removeVolume);
        volumesToCleanUp.clear();
    }

    /**
     * Task A writes a marker under {@code rootDirectory} {@code /team-a}; task B, on the same
     * file system but a different root, must not see it (if the bug were still present, one
     * shared volume keyed only by {@code fileSystemId} would leak team-a's marker into team-b's
     * task before it ever reaches its own write); a third task back on {@code /team-a} must
     * likewise not see what team-b wrote.
     */
    @Test
    void differentRootDirectoriesOnSameFileSystemAreIsolated() {
        String fileSystemId = "fs-" + UUID.randomUUID().toString().substring(0, 8);

        assertEquals(0, runEfsCommand(fileSystemId, "/team-a", null,
                "echo team-a > /mnt/efs/only-a.txt"),
                "task A should write its marker cleanly");

        assertEquals(0, runEfsCommand(fileSystemId, "/team-b", null,
                "test ! -e /mnt/efs/only-a.txt && echo team-b > /mnt/efs/only-b.txt"),
                "task B must not see task A's rootDirectory-scoped data");

        assertEquals(0, runEfsCommand(fileSystemId, "/team-a", null,
                "test ! -e /mnt/efs/only-b.txt"),
                "task on /team-a must not see /team-b's data");
    }

    /**
     * A second task using the identical {@code rootDirectory} must see the first task's data:
     * sharing an identical configuration is existing, desired behaviour.
     */
    @Test
    void sameRootDirectoryOnSameFileSystemStillShares() {
        String fileSystemId = "fs-" + UUID.randomUUID().toString().substring(0, 8);

        assertEquals(0, runEfsCommand(fileSystemId, "/shared", null,
                "echo hello > /mnt/efs/shared.txt"),
                "first task should write its marker cleanly");

        assertEquals(0, runEfsCommand(fileSystemId, "/shared", null,
                "grep -qx hello /mnt/efs/shared.txt"),
                "a second task with the same rootDirectory must share the first task's data");
    }

    /**
     * A plain {@code rootDirectory} mount (no {@code accessPointId}) on the same file system
     * must not see access-point-scoped data and vice versa, while a second task through the
     * same access point must still share its data.
     */
    @Test
    void accessPointIdIsolatesFromRootDirectoryOnSameFileSystem() {
        String fileSystemId = "fs-" + UUID.randomUUID().toString().substring(0, 8);
        String accessPointId = "fsap-" + UUID.randomUUID().toString().substring(0, 8);

        assertEquals(0, runEfsCommand(fileSystemId, null, accessPointId,
                "echo via-ap > /mnt/efs/via-ap.txt"),
                "task mounted through the access point should write its marker cleanly");

        assertEquals(0, runEfsCommand(fileSystemId, "/", null,
                "test ! -e /mnt/efs/via-ap.txt"),
                "a plain rootDirectory mount must not see access-point-scoped data");

        assertEquals(0, runEfsCommand(fileSystemId, null, accessPointId,
                "grep -qx via-ap /mnt/efs/via-ap.txt"),
                "a second task through the same access point must share its data");
    }

    @Test
    void accessPointsApplyTheirOwnOwnershipPermissionsAndIdentity() {
        CreateFileSystemRequest fileSystem = new CreateFileSystemRequest();
        fileSystem.setCreationToken(UUID.randomUUID().toString());
        String fileSystemId = efsService.createFileSystem(fileSystem, "us-east-1").getFileSystemId();
        List<String> accessPoints = new ArrayList<>();
        try {
            for (long uid : List.of(10001L, 10002L, 3000000000L)) {
                long processUid = uid == 3000000000L ? 10003L : uid;
                CreationInfo creation = new CreationInfo();
                creation.setOwnerUid(uid);
                creation.setOwnerGid(processUid);
                String mode = uid == 10001 ? "750" : uid == 10002 ? "700" : "770";
                creation.setPermissions("0" + mode);
                RootDirectory root = new RootDirectory();
                root.setPath("/user-" + uid);
                root.setCreationInfo(creation);
                PosixUser user = new PosixUser();
                user.setUid(processUid);
                user.setGid(processUid);
                user.setSecondaryGids(List.of(20001L, 20002L));
                CreateAccessPointRequest request = new CreateAccessPointRequest();
                request.setFileSystemId(fileSystemId);
                request.setRootDirectory(root);
                request.setPosixUser(user);
                AccessPointDescription ap = efsService.createAccessPoint("us-east-1", request);
                accessPoints.add(ap.getAccessPointId());
                String check = "test \"$(stat -c '%u:%g %a' /mnt/efs)\" = '" + uid + ":" + processUid + " " + mode
                        + "' && id -G | grep -qw " + processUid
                        + " && id -G | grep -qw 20001 && id -G | grep -qw 20002";
                assertEquals(0, runEfsCommand(fileSystemId, null, ap.getAccessPointId(),
                        check + " && mkdir /mnt/efs/sub && echo persisted > /mnt/efs/sub/marker"));
                assertEquals(0, runEfsCommand(fileSystemId, null, ap.getAccessPointId(),
                        check + " && grep -qx persisted /mnt/efs/sub/marker"));
            }
        } finally {
            accessPoints.forEach(id -> efsService.deleteAccessPoint("us-east-1", id));
            efsService.deleteFileSystem("us-east-1", fileSystemId);
        }
    }

    /**
     * Runs a single-container ECS task that mounts {@code fileSystemId} (scoped by
     * {@code rootDirectory}/{@code accessPointId}) at {@code /mnt/efs} and executes
     * {@code shellCommand}, then blocks until the container exits and returns its exit code.
     */
    private int runEfsCommand(String fileSystemId, String rootDirectory, String accessPointId, String shellCommand) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(IMAGE);
        app.setCommand(List.of("sh", "-c", shellCommand));
        app.setMountPoints(List.of(new MountPoint("efs-data", "/mnt/efs", false)));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("efs-isolation-" + suffix);
        taskDef.setContainerDefinitions(List.of(app));
        taskDef.setVolumes(List.of(new Volume("efs-data", null,
                new EfsVolumeConfiguration(fileSystemId, rootDirectory, null, null, accessPointId, null))));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/efs-isolation/" + suffix);

        volumesToCleanUp.add("floci-aws-" + EcsContainerManager.efsVolumeToken(fileSystemId, accessPointId, rootDirectory));

        EcsTaskHandle handle = containerManager.startTask(task, taskDef, List.of(), "us-east-1");
        String dockerId = handle.getContainerIds().get("app");
        try {
            return dockerClient.waitContainerCmd(dockerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(60, TimeUnit.SECONDS);
        } finally {
            containerManager.stopTask(handle);
            containerManager.cleanupStoppedTask(handle);
        }
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
