package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-gated integration test proving a caller-configured registry host is usable end to end: a
 * pod scheduled in a real k3s cluster pulls its image through a host that only exists because it
 * was set via {@code _floci/eks/clusters/{name}/registry-hosts}, with a header that
 * {@code registries.yaml} could not express. Pushes reuse the raw OCI Distribution flow (not
 * {@code docker push}) because the Docker daemon only trusts {@code 127.0.0.0/8} as insecure by
 * default, not a {@code *.localhost} hostname; see {@code EcrTlsDataPlaneDockerIntegrationTest} for
 * the same constraint. No second registry is stood up: the mirror endpoint is Floci's own ECR data
 * plane, addressed under an account id the generated ECR mirror does not cover.
 */
@QuarkusTest
@TestProfile(EksRegistryHostsDockerIntegrationTest.Profile.class)
class EksRegistryHostsDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksRegistryHostsDockerIntegrationTest.class);
    private static final String JSON = "application/json";

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.eks.mock", "false");
        }
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    int httpPort;

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksService eksService;

    @Inject
    DockerHostResolver dockerHostResolver;

    private String clusterName;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksRegistryHostsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for the registry-hosts integration test");
    }

    @AfterEach
    void tearDown() {
        if (clusterName != null) {
            try {
                eksService.deleteCluster(clusterName);
            } catch (Exception e) {
                LOG.warnv("Failed to delete test cluster {0}: {1}", clusterName, e.getMessage());
            }
        }
    }

    @Test
    void podPullsThroughACallerConfiguredRegistryHostCarryingAHeader() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        clusterName = "reg-hosts-dock-" + suffix;
        String repo = "reg-hosts-it-" + suffix;
        // Not "000000000000": the generated ECR mirror only covers the default account, so this
        // hostname is only reachable at all because of the registry-hosts configuration below.
        String host = "111122223333.dkr.ecr.us-west-2.localhost:" + httpPort;
        String imageRef = host + "/" + repo + ":v1";

        pushMinimalImage(host, repo);

        given().contentType(JSON)
                .body("{\"name\":\"" + clusterName + "\",\"roleArn\":"
                        + "\"arn:aws:iam::000000000000:role/eks-service-role\"}")
                .when().post("/clusters")
                .then().statusCode(200);

        Cluster cluster = eksService.describeCluster(clusterName);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null");
        waitUntilReady(cluster);
        eksClusterManager.finalizeCluster(cluster);
        String containerId = cluster.getContainerId();

        String endpoint = "http://" + dockerHostResolver.resolve() + ":" + httpPort;
        String payload = """
                {"hosts":[{"host":"%s","endpoints":[{"url":"%s","headers":{"X-Floci-Test":"registry-hosts"}}]}]}
                """.formatted(host, endpoint);
        given().contentType(JSON).body(payload)
                .when().put("/_floci/eks/clusters/" + clusterName + "/registry-hosts")
                .then().statusCode(200)
                .body("hosts[0].host", equalTo(host))
                .body("hosts[0].endpoints[0].headers.X-Floci-Test", equalTo("registry-hosts"));

        String hostsToml = execInContainer(containerId, new String[]{"cat",
                EksClusterManager.K3S_DATA_DIR + "/" + EksClusterManager.CONTAINERD_CERTS_DIR + "/" + host + "/hosts.toml"});
        assertTrue(hostsToml.contains("\"X-Floci-Test\" = \"registry-hosts\""),
                "the configured header must be present in the container's hosts.toml");

        long saDeadline = System.currentTimeMillis() + 30000;
        boolean saReady = false;
        while (System.currentTimeMillis() < saDeadline) {
            ExecResult saResult = execInContainerWithExitCode(containerId,
                    new String[]{"kubectl", "get", "serviceaccount", "default"});
            if (saResult.exitCode() == 0) {
                saReady = true;
                break;
            }
            Thread.sleep(1000);
        }
        assertTrue(saReady, "default serviceaccount must be created within 30 seconds");

        String podYaml = """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: registry-hosts-pod
                spec:
                  containers:
                  - name: workload
                    image: %s
                    command: ["sleep", "3600"]
                """.formatted(imageRef);
        execInContainer(containerId, new String[]{"sh", "-c",
                "cat << 'EOF' | kubectl apply -f -\n" + podYaml + "\nEOF"});

        long deadline = System.currentTimeMillis() + 45000;
        boolean running = false;
        while (System.currentTimeMillis() < deadline) {
            ExecResult status = execInContainerWithExitCode(containerId,
                    new String[]{"kubectl", "get", "pod", "registry-hosts-pod", "-o", "jsonpath={.status.phase}"});
            if ("Running".equalsIgnoreCase(status.stdout().trim())) {
                running = true;
                break;
            }
            Thread.sleep(2000);
        }
        assertTrue(running, "registry-hosts-pod must reach Running, pulling its image through the configured host");
    }

    /**
     * Pushes a minimal, genuinely pullable image (one empty gzipped-tar layer, a valid image config
     * referencing it) via raw OCI Distribution calls against Floci's ECR data plane, so kubelet's
     * pull through the mirror can actually unpack it rather than just fetching bytes.
     */
    private void pushMinimalImage(String host, String repo) throws Exception {
        byte[] tarBytes;
        try (ByteArrayOutputStream tarOut = new ByteArrayOutputStream();
             TarArchiveOutputStream tar = new TarArchiveOutputStream(tarOut)) {
            tar.finish();
            tarBytes = tarOut.toByteArray();
        }
        ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzOut)) {
            gzip.write(tarBytes);
        }
        byte[] layerBytes = gzOut.toByteArray();
        String diffId = "sha256:" + sha256Hex(tarBytes);
        String layerDigest = "sha256:" + sha256Hex(layerBytes);

        byte[] configBytes = ("{\"architecture\":\"amd64\",\"os\":\"linux\",\"config\":{},"
                + "\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[\"" + diffId + "\"]}}")
                .getBytes(StandardCharsets.UTF_8);
        String configDigest = "sha256:" + sha256Hex(configBytes);

        uploadBlob(host, repo, layerDigest, layerBytes);
        uploadBlob(host, repo, configDigest, configBytes);

        String manifestJson = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": %d,
                    "digest": "%s"
                  },
                  "layers": [
                    {
                      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                      "size": %d,
                      "digest": "%s"
                    }
                  ]
                }
                """.formatted(configBytes.length, configDigest, layerBytes.length, layerDigest);

        given().header("Host", host)
                .contentType("application/vnd.docker.distribution.manifest.v2+json")
                .body(manifestJson)
                .when().put("/v2/" + repo + "/manifests/v1")
                .then().statusCode(201);
    }

    private void uploadBlob(String host, String repo, String digest, byte[] content) {
        String location = given().header("Host", host)
                .when().post("/v2/" + repo + "/blobs/uploads/")
                .then().statusCode(202)
                .extract().header("Location");
        assertNotNull(location);
        String separator = location.contains("?") ? "&" : "?";
        given().header("Host", host)
                // The Location header already carries a percent-encoded _state token; RestAssured's
                // default encoding would double-encode it and corrupt the token.
                .urlEncodingEnabled(false)
                .contentType("application/octet-stream")
                .body(content)
                .when().put(location + separator + "digest=" + digest)
                .then().statusCode(201);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void waitUntilReady(Cluster cluster) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            if (eksClusterManager.isReady(cluster)) {
                return;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("k3s API server did not become ready within 60 seconds");
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    record ExecResult(long exitCode, String stdout, String stderr) {}

    private ExecResult execInContainerWithExitCode(String containerId, String[] cmd) throws Exception {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame != null && frame.getPayload() != null) {
                            String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            if (frame.getStreamType() == StreamType.STDERR) {
                                stderr.append(text);
                            } else {
                                stdout.append(text);
                            }
                        }
                    }
                })
                .awaitCompletion(30, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return new ExecResult(exitCode != null ? exitCode : -1L, stdout.toString(), stderr.toString());
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        ExecResult result = execInContainerWithExitCode(containerId, cmd);
        if (result.exitCode() != 0) {
            throw new RuntimeException("exec failed with code " + result.exitCode() + ": " + result.stderr());
        }
        return result.stdout();
    }
}
