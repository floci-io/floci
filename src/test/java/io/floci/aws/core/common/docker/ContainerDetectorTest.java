package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContainerDetectorTest {

    private static ContainerDetector detector(boolean dockerEnvExists,
                                              boolean podmanEnvExists,
                                              String cgroupContent,
                                              String mountInfoContent,
                                              String containerEnv,
                                              String dotnetEnv,
                                              String genericContainerEnv) {
        return new ContainerDetector() {
            @Override
            boolean fileExists(String path) {
                if ("/.dockerenv".equals(path)) return dockerEnvExists;
                if ("/run/.containerenv".equals(path)) return podmanEnvExists;
                // For cgroup / mountinfo we control via readFileContent
                if ("/proc/1/cgroup".equals(path)) return cgroupContent != null;
                if ("/proc/self/mountinfo".equals(path)) return mountInfoContent != null;
                return false;
            }

            @Override
            String readFileContent(Path path) throws IOException {
                if (path.equals(Path.of("/proc/1/cgroup")) && cgroupContent != null) {
                    return cgroupContent;
                }
                if (path.equals(Path.of("/proc/self/mountinfo")) && mountInfoContent != null) {
                    return mountInfoContent;
                }
                throw new IOException("File not available in test stub: " + path);
            }

            @Override
            String getEnv(String name) {
                return switch (name) {
                    case "container" -> containerEnv;
                    case "DOTNET_RUNNING_IN_CONTAINER" -> dotnetEnv;
                    case "CONTAINER" -> genericContainerEnv;
                    default -> null;
                };
            }
        };
    }

    @Test
    void notInContainer() {
        ContainerDetector d = detector(false, false, null, null, null, null, null);
        assertFalse(d.isRunningInContainer());
    }

    @Test
    void detectedViaDockerenvMarker() {
        ContainerDetector d = detector(true, false, null, null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaPodmanMarker() {
        ContainerDetector d = detector(false, true, null, null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaPodmanContainerEnv() {
        ContainerDetector d = detector(false, false, null, null, "podman", null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaDotnetEnvVariable() {
        ContainerDetector d = detector(false, false, null, null, null, "true", null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void dotnetEnvFalseDoesNotDetect() {
        ContainerDetector d = detector(false, false, null, null, null, "false", null);
        assertFalse(d.isRunningInContainer());
    }

    @Test
    void detectedViaGenericContainerEnv() {
        ContainerDetector d = detector(false, false, null, null, null, null, "docker");
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaCgroupDocker() {
        ContainerDetector d = detector(false, false,
                "12:memory:/docker/abc123\n", null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaCgroupMoby() {
        ContainerDetector d = detector(false, false,
                "12:memory:/moby/abc123\n", null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaCgroupKubepods() {
        ContainerDetector d = detector(false, false,
                "10:cpuset:/kubepods/pod-xyz\n", null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaCgroupLibpod() {
        ContainerDetector d = detector(false, false,
                "3:pids:/libpod-abc123\n", null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaCgroupContainerd() {
        ContainerDetector d = detector(false, false,
                "5:memory:/containerd/xyz\n", null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaCgroupCriContainerd() {
        ContainerDetector d = detector(false, false,
                "5:memory:/cri-containerd-abc\n", null, null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void cgroupWithoutMarkers() {
        ContainerDetector d = detector(false, false,
                "12:memory:/user.slice\n", null, null, null, null);
        assertFalse(d.isRunningInContainer());
    }

    @Test
    void detectedViaMountInfoDocker() {
        ContainerDetector d = detector(false, false, null,
                "100 95 0:54 / / rw,relatime - overlay overlay rw,lowerdir=/docker/overlay2/l/ABC\n",
                null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void hostDockerMountsDoNotDetectAsContainer() {
        ContainerDetector d = detector(false, false, null,
                """
                1505 1423 252:1 / / rw,relatime - ext4 /dev/mapper/root rw
                1514 1511 0:4 net:[4026533347] /run/docker/netns/abc rw - nsfs nsfs rw
                1538 1505 0:67 / /var/lib/docker/rootfs/abc rw - overlay overlay rw,lowerdir=/var/lib/containerd/snapshots/1/fs
                """,
                null, null, null);
        assertFalse(d.isRunningInContainer());
    }

    @Test
    void detectedViaMountInfoMoby() {
        ContainerDetector d = detector(false, false, null,
                "100 95 0:54 / / rw - overlay overlay lowerdir=/moby/something\n",
                null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void detectedViaMountInfoLibpod() {
        ContainerDetector d = detector(false, false, null,
                "100 95 0:54 / / rw - overlay overlay lowerdir=/libpod-abc123/overlay\n",
                null, null, null);
        assertTrue(d.isRunningInContainer());
    }

    @Test
    void mountInfoWithoutMarkers() {
        ContainerDetector d = detector(false, false, null,
                "100 95 0:54 / / rw - ext4 /dev/sda1 rw\n",
                null, null, null);
        assertFalse(d.isRunningInContainer());
    }

    @Test
    void resultIsCached() {
        ContainerDetector d = detector(true, false, null, null, null, null, null);
        assertTrue(d.isRunningInContainer());
        // Second call should return the cached result (still true)
        assertTrue(d.isRunningInContainer());
    }
}

