package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.github.hectorvent.floci.services.opensearch.OpenSearchVersions;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@EnabledOnOs(OS.LINUX)
class ContainerBindDirectoryDockerIntegrationTest {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ImageCacheService imageCacheService;

    @Test
    void nonRootImageCanWriteFreshBindWithoutChangingExistingFiles(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available");
        Path directory = Files.createDirectory(root.resolve("domain"));
        Path existing = Files.writeString(directory.resolve("existing"), "operator data");
        int originalUid = (Integer) Files.getAttribute(directory, "unix:uid");
        int originalGid = (Integer) Files.getAttribute(directory, "unix:gid");
        String hostPath = directory.toString();
        imageCacheService.ensureImageExists(TestImages.BUSYBOX);
        try {
            assertEquals(0, runBoundCommand(TestImages.BUSYBOX, hostPath, "0",
                    "chown 0:0 /test-data && chmod 0755 /test-data && "
                            + "chown 2222:2222 /test-data/existing && chmod 0600 /test-data/existing"));
            String image = OpenSearchVersions.imageFor(OpenSearchVersions.DEFAULT_VERSION);

            lifecycleManager.ensureBindDirectory(hostPath, image, "/usr/share/opensearch/data");

            assertEquals(1000, Files.getAttribute(directory, "unix:uid"));
            assertEquals(2222, Files.getAttribute(existing, "unix:uid"));
            assertEquals(0600, (Integer) Files.getAttribute(existing, "unix:mode") & 0777);
            assertEquals(0, runBoundCommand(image, hostPath, "1000:1000", "touch /test-data/created"));
            assertTrue(Files.exists(directory.resolve("created")));
        } finally {
            assertEquals(0, runBoundCommand(TestImages.BUSYBOX, hostPath, "0",
                    "rm -f /test-data/created && chown " + originalUid + ":" + originalGid + " /test-data"));
        }
    }

    private Integer runBoundCommand(String image, String hostPath, String user, String script) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(hostPath, new Volume("/test-data")));
        CreateContainerResponse created = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withUser(user)
                .withEntrypoint("sh")
                .withCmd("-c", script)
                .exec();
        String id = created.getId();
        try {
            lifecycleManager.startContainer(id);
            return dockerClient.waitContainerCmd(id).exec(new WaitContainerResultCallback())
                    .awaitStatusCode(60, TimeUnit.SECONDS);
        } finally {
            dockerClient.removeContainerCmd(id).withForce(true).exec();
        }
    }
}
