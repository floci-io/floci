package io.github.hectorvent.floci.services.ecs.container;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EcsContainerManager#efsVolumeName}, the naming logic that scopes an
 * EFS-backed task volume's shared Docker named volume to both {@code fileSystemId} and its
 * effective root (#2563). The name must be stable for a given (fileSystemId, rootDirectory,
 * accessPointId) combination (so identical mounts keep sharing one volume), distinct across
 * different roots on the same file system (so mounts do not collide on the same tree), and
 * always a legal Docker volume name.
 */
class EcsContainerManagerEfsVolumeNamingTest {

    private static final Pattern DOCKER_VOLUME_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$");

    @Test
    void defaultsToPlainFileSystemNameWhenNoRootOrAccessPointIsSet() {
        assertEquals("floci-efs-fs-abc", EcsContainerManager.efsVolumeName("fs-abc", null, null));
        assertEquals("floci-efs-fs-abc", EcsContainerManager.efsVolumeName("fs-abc", null, ""));
        assertEquals("floci-efs-fs-abc", EcsContainerManager.efsVolumeName("fs-abc", null, "/"));
    }

    @Test
    void differentRootDirectoriesOnSameFileSystemYieldDifferentVolumes() {
        String rootA = EcsContainerManager.efsVolumeName("fs-shared", null, "/team-a");
        String rootB = EcsContainerManager.efsVolumeName("fs-shared", null, "/team-b");
        String rootNone = EcsContainerManager.efsVolumeName("fs-shared", null, null);

        assertNotEquals(rootA, rootB, "different rootDirectory values must isolate the volume");
        assertNotEquals(rootA, rootNone, "a non-root rootDirectory must not collapse to the plain name");
        assertTrue(DOCKER_VOLUME_NAME.matcher(rootA).matches(), "must be docker-safe, was: " + rootA);
        assertTrue(DOCKER_VOLUME_NAME.matcher(rootB).matches(), "must be docker-safe, was: " + rootB);
    }

    @Test
    void sameRootDirectoryOnSameFileSystemStillShares() {
        String first = EcsContainerManager.efsVolumeName("fs-shared", null, "/team-a");
        String second = EcsContainerManager.efsVolumeName("fs-shared", null, "/team-a");

        assertEquals(first, second, "identical (fileSystemId, rootDirectory) must keep sharing one volume");
    }

    @Test
    void accessPointIdTakesPrecedenceOverRootDirectory() {
        String viaAccessPoint = EcsContainerManager.efsVolumeName("fs-shared", "fsap-111", "/ignored");
        String viaRootOnly = EcsContainerManager.efsVolumeName("fs-shared", null, "/ignored");

        assertNotEquals(viaAccessPoint, viaRootOnly,
                "an access point's own root must take precedence over rootDirectory, not merge with it");
    }

    @Test
    void differentAccessPointsOnSameFileSystemYieldDifferentVolumes() {
        String apOne = EcsContainerManager.efsVolumeName("fs-shared", "fsap-111", null);
        String apTwo = EcsContainerManager.efsVolumeName("fs-shared", "fsap-222", null);

        assertNotEquals(apOne, apTwo, "different accessPointId values must isolate the volume");
    }

    @Test
    void sameAccessPointStillShares() {
        String first = EcsContainerManager.efsVolumeName("fs-shared", "fsap-111", null);
        String second = EcsContainerManager.efsVolumeName("fs-shared", "fsap-111", "/whatever-is-ignored");

        assertEquals(first, second,
                "the same accessPointId must keep sharing one volume regardless of rootDirectory");
    }

    @Test
    void equivalentRootDirectorySpellingsShareTheSameVolume() {
        String canonical = EcsContainerManager.efsVolumeName("fs-shared", null, "/team-a");

        assertEquals(canonical, EcsContainerManager.efsVolumeName("fs-shared", null, "/team-a/"),
                "a trailing slash must not create a distinct volume for the same directory");
        assertEquals(canonical, EcsContainerManager.efsVolumeName("fs-shared", null, "//team-a"),
                "a repeated leading slash must not create a distinct volume for the same directory");
        assertEquals(canonical, EcsContainerManager.efsVolumeName("fs-shared", null, "team-a"),
                "a missing leading slash must not create a distinct volume for the same directory");
    }
}
