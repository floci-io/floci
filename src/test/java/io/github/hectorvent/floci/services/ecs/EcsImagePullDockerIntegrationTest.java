package io.github.hectorvent.floci.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PushResponseItem;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenario from a CI rebuild: a service's image tag is moved in its registry while the Docker
 * host still holds the old image under that tag. The next task has to run the image the registry
 * names now, as the ECS agent's default pull behaviour does, and report that image's digest.
 */
@QuarkusTest
@TestProfile(EcsImagePullDockerIntegrationTest.DockerEcsProfile.class)
class EcsImagePullDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String MANIFEST_TYPES = String.join(",",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    /**
     * Real containers, security-group enforcement back to its shipped default of off, and the
     * shipped pull behaviour, which the shared test config swaps for prefer-cached.
     */
    public static final class DockerEcsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.services.ecs.image-pull-behavior", "default");
        }
    }

    @Inject
    EcsService ecsService;

    @Inject
    DockerClient dockerClient;

    private String registryContainerId;
    private final List<String> images = new ArrayList<>();
    private final List<String> taskArns = new ArrayList<>();
    private String clusterName;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for the ECS image pull test");
    }

    @AfterEach
    void cleanUp() {
        for (String taskArn : taskArns) {
            ecsService.stopTask(clusterName, taskArn, "test teardown", REGION);
        }
        for (String image : images) {
            try {
                dockerClient.removeImageCmd(image).withForce(true).exec();
            } catch (NotFoundException expected) {
                // Removing one tag of an image can take the others with it.
            }
        }
        if (registryContainerId != null) {
            dockerClient.removeContainerCmd(registryContainerId).withForce(true).exec();
        }
    }

    @Test
    void aTaskRunsTheImageItsTagWasMovedToInTheRegistry() throws Exception {
        String registry = startRegistry();
        String repository = "floci-ecs-pull-" + UUID.randomUUID().toString().substring(0, 8);
        String image = registry + "/" + repository + ":latest";
        images.add(image);

        pull(TestImages.BUSYBOX);
        dockerClient.tagImageCmd(TestImages.BUSYBOX, registry + "/" + repository, "latest").exec();
        push(registry + "/" + repository);
        String firstDigest = registryDigest(registry, repository);

        clusterName = "pull-" + UUID.randomUUID().toString().substring(0, 8);
        ecsService.createCluster(clusterName, REGION);
        TaskDefinition taskDef = registerTaskDefinition(image);

        EcsTask first = runTask(taskDef);
        assertEquals(firstDigest, first.getContainers().getFirst().getImageDigest());
        String firstImageId = containerImageId(first);

        // A rebuild pushed under the same tag, while the Docker host keeps the old image under it.
        String rebuilt = commitChangedImage(image);
        images.add(rebuilt);
        dockerClient.tagImageCmd(rebuilt, registry + "/" + repository, "latest").exec();
        push(registry + "/" + repository);
        String rebuiltDigest = registryDigest(registry, repository);
        assertNotEquals(firstDigest, rebuiltDigest, "the rebuild must have moved the tag in the registry");
        dockerClient.tagImageCmd(firstImageId, registry + "/" + repository, "latest").exec();

        EcsTask second = runTask(taskDef);

        assertEquals(rebuiltDigest, second.getContainers().getFirst().getImageDigest(),
                "the task must report the digest the registry's tag names now");
        assertNotEquals(firstImageId, containerImageId(second),
                "the task must run the rebuilt image, not the one the Docker host held under the tag");
    }

    private EcsTask runTask(TaskDefinition taskDef) {
        EcsTask task = ecsService.runTask(clusterName, taskDef.getTaskDefinitionArn(), 1,
                null, null, null, null, null, REGION).getFirst();
        taskArns.add(task.getTaskArn());
        EcsTask described = ecsService.describeTasks(clusterName, List.of(task.getTaskArn()), REGION).getFirst();
        assertEquals("RUNNING", described.getLastStatus(), "the container must be up: " + described.getStoppedReason());
        return described;
    }

    private TaskDefinition registerTaskDefinition(String image) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(image);
        app.setCommand(List.of("sleep", "120"));
        return ecsService.registerTaskDefinition("pull-" + UUID.randomUUID().toString().substring(0, 8),
                List.of(app), NetworkMode.bridge, null, null, null, null, null, REGION);
    }

    private String containerImageId(EcsTask task) {
        return dockerClient.inspectContainerCmd(task.getContainers().getFirst().getRuntimeId()).exec().getImageId();
    }

    private String commitChangedImage(String image) {
        CreateContainerResponse container = dockerClient.createContainerCmd(image).withCmd("true").exec();
        try {
            return dockerClient.commitCmd(container.getId())
                    .withLabels(Map.of("floci.test.build", UUID.randomUUID().toString()))
                    .exec();
        } finally {
            dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
        }
    }

    private String startRegistry() throws InterruptedException, IOException {
        pull(TestImages.REGISTRY);
        ExposedPort registryPort = ExposedPort.tcp(5000);
        int hostPort = freeLoopbackPort();
        registryContainerId = dockerClient.createContainerCmd(TestImages.REGISTRY)
                .withExposedPorts(registryPort)
                .withHostConfig(HostConfig.newHostConfig().withPortBindings(new PortBinding(
                        Ports.Binding.bindIpAndPort("127.0.0.1", hostPort), registryPort)))
                .exec()
                .getId();
        dockerClient.startContainerCmd(registryContainerId).exec();
        String registry = "localhost:" + hostPort;
        HttpClient http = HttpClient.newHttpClient();
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                HttpResponse<Void> response = http.send(
                        HttpRequest.newBuilder(URI.create("http://" + registry + "/v2/")).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return registry;
                }
            } catch (IOException expected) {
                // Safe to retry: the registry takes a moment to bind after the container starts.
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("registry at " + registry + " never answered");
    }

    /** The digest the registry serves for the tag, which is what a pull of it resolves to. */
    private static String registryDigest(String registry, String repository) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + registry + "/v2/" + repository + "/manifests/latest"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("Accept", MANIFEST_TYPES)
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Docker-Content-Digest").orElseThrow();
    }

    private void pull(String image) throws InterruptedException {
        dockerClient.pullImageCmd(image).exec(new PullImageResultCallback()).awaitCompletion(5, TimeUnit.MINUTES);
    }

    private void push(String repository) throws InterruptedException {
        AtomicReference<String> pushError = new AtomicReference<>();
        boolean finished = dockerClient.pushImageCmd(repository).withTag("latest")
                .exec(new ResultCallback.Adapter<PushResponseItem>() {
                    @Override
                    public void onNext(PushResponseItem item) {
                        if (item.isErrorIndicated()) {
                            pushError.set(String.valueOf(item.getErrorDetail()));
                        }
                    }
                })
                .awaitCompletion(2, TimeUnit.MINUTES);
        assertTrue(finished, "the push to " + repository + " must finish");
        assertNull(pushError.get(), "the push to " + repository + " must succeed");
    }

    /**
     * A port named in the binding, not one the daemon picks: Docker Desktop allocates an unnamed
     * port on the host side only, where the daemon that pushes and pulls cannot reach it.
     */
    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
