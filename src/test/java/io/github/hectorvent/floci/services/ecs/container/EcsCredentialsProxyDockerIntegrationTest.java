package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the proxy's half of the mechanism this feature depends on. A task container needs its
 * own address in 169.254.0.0/16 to reach the proxy's 169.254.170.2 at all: without one, its
 * routing table has no connected route for that range, so the kernel sends the packet toward the
 * default gateway instead of ARPing the peer directly, and it never reaches the target's socket
 * (confirmed by hand: a plain container got "Connection refused" against a proxy that was
 * genuinely listening and working, the same symptom a completely absent listener produces, which
 * is what made this easy to get wrong). Real task-container addressing is EcsContainerManager's
 * job (a separate piece); this test gives its stand-in task container one directly, the same way
 * that wiring will.
 *
 * <p>Skipped without a Docker daemon, like the other {@code *DockerIntegrationTest} classes.
 */
@QuarkusTest
class EcsCredentialsProxyDockerIntegrationTest {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    DockerHostResolver dockerHostResolver;

    @Inject
    ContainerDetector containerDetector;

    @Inject
    IamService iam;

    private String networkId;
    private String proxyContainerId;
    private Vertx vertx;
    private EcsTaskRoleCredentialsServer server;
    private String taskContainerId;

    @BeforeEach
    void requireDockerOnTheHost() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available");
        Assumptions.assumeFalse(containerDetector.isRunningInContainer(),
                "the plain container dials host.docker.internal, which only names a host-side Floci");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (taskContainerId != null) {
            dockerClient.removeContainerCmd(taskContainerId).withForce(true).exec();
        }
        if (server != null) {
            server.stop();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        // EcsCredentialsProxy never removes its own container, by design: it is meant to persist
        // for the process's life. The test that created it owns tearing it down.
        if (proxyContainerId != null) {
            dockerClient.removeContainerCmd(proxyContainerId).withForce(true).exec();
        }
        if (networkId != null) {
            dockerClient.removeNetworkCmd(networkId).exec();
        }
    }

    @Test
    void taskContainerWithItsOwnLinkLocalAddressFetchesRealCredentialsThroughTheProxy() throws Exception {
        String network = "floci-ecs-credentials-proxy-test-" + UUID.randomUUID();
        CreateNetworkResponse created = dockerClient.createNetworkCmd().withName(network).exec();
        networkId = created.getId();

        String account = "987654321098";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = "ecs-task-role-" + suffix;
        String taskArn = "arn:aws:ecs:us-east-1:" + account + ":task/cluster/" + suffix;
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260916/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":"
                        + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},"
                        + "\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        String roleArn = "arn:aws:iam::" + account + ":role/" + role;

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().port()).thenReturn(port);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);
        when(config.services().ecs().taskRoleCredentials().proxyImage()).thenReturn("floci/network-helper:local");

        vertx = Vertx.vertx();
        EcsTaskRoleCredentials credentials = new EcsTaskRoleCredentials(iam, config);
        server = new EcsTaskRoleCredentialsServer(vertx, config, credentials);
        server.start().get(10, TimeUnit.SECONDS);
        String path = credentials.issue(taskArn, roleArn, account, Instant.now()).orElseThrow();

        EcsCredentialsProxy proxy = new EcsCredentialsProxy(
                dockerClient, containerBuilder, lifecycleManager, dockerHostResolver, config);
        proxy.ensureProxyOn(network);
        proxyContainerId = dockerClient.listContainersCmd()
                .withLabelFilter(Map.of("floci.ecs-task-role-credentials-proxy", "true"))
                .withNetworkFilter(List.of(networkId))
                .exec().get(0).getId();

        // A stand-in task container needs its own 169.254.0.0/16 address to reach the proxy at
        // all: without one, its routing table has no connected route for that range and the
        // kernel routes toward the default gateway instead of ARPing the peer directly, which
        // never reaches the proxy's socket. The real allocation (unique per task, avoiding the
        // proxy's own .2) is EcsContainerManager's job; this just proves the proxy's own side.
        ContainerSpec taskSpec = containerBuilder.newContainer("public.ecr.aws/docker/library/python:3.12-alpine")
                .withName("floci-ecs-credentials-proxy-test-task-" + suffix)
                .withDockerNetwork(Optional.of(network))
                .withLinkLocalIp("169.254.170.5")
                .withEnv(List.of("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI=" + path))
                .withEntrypoint(List.of("python3", "-c", """
                        import json, os, urllib.request
                        uri = os.environ["AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"]
                        body = urllib.request.urlopen("http://169.254.170.2" + uri, timeout=10).read()
                        creds = json.loads(body)
                        assert creds["AccessKeyId"].startswith("ASIA"), creds
                        assert creds["RoleArn"], creds
                        print(json.dumps(creds))
                        """))
                .build();
        ContainerLifecycleManager.ContainerInfo taskInfo = lifecycleManager.createAndStart(taskSpec);
        taskContainerId = taskInfo.containerId();

        Integer status = dockerClient.waitContainerCmd(taskContainerId)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode(30, TimeUnit.SECONDS);
        assertEquals(0, status, () -> logs(taskContainerId));

        given().header("Authorization", auth).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", role)
                .post("/").then().statusCode(200);
    }

    /**
     * Reproduces the exact failure a stale, stopped proxy container used to cause: Docker refuses
     * to create a new container under a name a stopped one still holds. A crashed process, or a
     * launch that got this far and then failed later, would leave one of these behind under
     * {@code EcsCredentialsProxy}'s deterministic per-network name.
     */
    @Test
    void ensureProxyOnRecoversFromAStaleStoppedContainerHoldingItsName() throws Exception {
        String network = "floci-ecs-credentials-proxy-test-" + UUID.randomUUID();
        CreateNetworkResponse created = dockerClient.createNetworkCmd().withName(network).exec();
        networkId = created.getId();

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().taskRoleCredentials().proxyImage()).thenReturn("floci/network-helper:local");
        when(config.services().ecs().taskRoleCredentials().port()).thenReturn(port);
        EcsCredentialsProxy proxy = new EcsCredentialsProxy(
                dockerClient, containerBuilder, lifecycleManager, dockerHostResolver, config);
        String name = ContainerStorageHelper.resourceName(
                config, "ecs-credentials-proxy", null, network + "-" + proxy.ownerNameSegment());

        // A container that already occupies the name ensureProxyOn will ask for, stopped rather
        // than removed, exactly what a crashed or killed proxy leaves behind.
        // Carries the same label ensureProxyOn itself always sets: a real stale proxy is always
        // one this code (this instance or an earlier run of it) created, never an arbitrary
        // container that happens to collide on the name.
        ContainerSpec staleSpec = containerBuilder.newContainer("floci/network-helper:local")
                .withName(name)
                .withDockerNetwork(Optional.of(network))
                .withLabels(Map.of("floci.ecs-task-role-credentials-proxy", "true"))
                .build();
        String staleId = lifecycleManager.createAndStart(staleSpec).containerId();
        dockerClient.stopContainerCmd(staleId).withTimeout(1).exec();

        // ensureProxyOn's endpoint check needs something real to answer through; this recovery
        // test is not about the credentials response itself, so any server on the reported port
        // will do.
        vertx = Vertx.vertx();
        server = new EcsTaskRoleCredentialsServer(vertx, config, new EcsTaskRoleCredentials(iam, config));
        server.start().get(10, TimeUnit.SECONDS);

        try {
            proxy.ensureProxyOn(network);
            proxyContainerId = dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of("floci.ecs-task-role-credentials-proxy", "true"))
                    .withNetworkFilter(List.of(networkId))
                    .exec().get(0).getId();
            assertEquals("running", dockerClient.inspectContainerCmd(proxyContainerId)
                    .exec().getState().getStatus());
        } finally {
            // ensureProxyOn already replaced the stale container under its own name (a new
            // container ID), so removing "staleId" itself is expected to find nothing.
            lifecycleManager.removeIfExists(staleId);
        }
    }

    private String logs(String containerId) {
        StringBuilder out = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            out.append("(could not read logs: ").append(e.getMessage()).append(')');
        }
        return out.toString();
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
