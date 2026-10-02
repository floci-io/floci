package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-gated integration test proving containerd registry host configuration via node group launch
 * template user data works end to end. Real EKS node groups configure containerd hosts (e.g.
 * pull-through cache mirrors with auth headers) by writing /etc/containerd/certs.d/&lt;host&gt;/hosts.toml
 * in launch template user data. Floci symlinks /etc/containerd/certs.d to k3s's containerd certs
 * directory (/var/lib/rancher/k3s/agent/etc/containerd/certs.d) before container start, so standard
 * user data works unchanged. A pod scheduled in the cluster pulls its image through the configured
 * host, with the custom header that registries.yaml could not express.
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
    Ec2Service ec2Service;

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
    void podPullsThroughRegistryHostConfiguredViaLaunchTemplateUserData() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        clusterName = "reg-hosts-dock-" + suffix;
        String repo = "reg-hosts-it-" + suffix;
        // Not "000000000000": the generated ECR mirror only covers the default account, so this
        // hostname is only reachable at all because of the registry-hosts configuration below.
        String host = "111122223333.dkr.ecr.us-east-1.localhost:" + httpPort;
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

        // Verify symlink was created before container started
        ExecResult linkResult = execInContainerWithExitCode(containerId,
                new String[]{"readlink", "/etc/containerd/certs.d"});
        assertEquals(0, linkResult.exitCode(), "readlink /etc/containerd/certs.d must succeed");
        assertEquals(EksClusterManager.CONTAINERD_CERTS_TARGET, linkResult.stdout().trim());

        // Configure registry host via node group launch template user data
        String endpoint = "http://" + dockerHostResolver.resolve() + ":" + httpPort;
        String userDataScript = """
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="==MYBOUNDARY=="

                --==MYBOUNDARY==
                Content-Type: text/x-shellscript; charset="us-ascii"

                #!/bin/sh
                mkdir -p /etc/containerd/certs.d/%s
                cat << 'EOF' > /etc/containerd/certs.d/%s/hosts.toml
                server = "https://%s"

                [host."%s"]
                  capabilities = ["pull", "resolve"]
                  [host."%s".header]
                    "X-Floci-Test" = "registry-hosts"
                EOF

                --==MYBOUNDARY==--
                """.formatted(host, host, host, endpoint, endpoint);

        LaunchTemplateData ltData = new LaunchTemplateData();
        ltData.setUserData(Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8)));
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "eks-hosts-lt-" + suffix, ltData, List.of());

        given().contentType(JSON)
                .body("""
                        {
                          "nodegroupName": "ng-%s",
                          "subnets": ["subnet-12345678"],
                          "nodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
                          "launchTemplate": {
                            "id": "%s",
                            "version": "1"
                          }
                        }
                        """.formatted(suffix, lt.getLaunchTemplateId()))
                .when().post("/clusters/" + clusterName + "/node-groups")
                .then().statusCode(200);

        // Re-read cluster and container ID: applying the first node group replaces the k3s container
        // to apply node capacity limits, invalidating the previous container ID.
        cluster = eksService.describeCluster(clusterName);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null after node group creation");
        waitUntilReady(cluster);
        containerId = cluster.getContainerId();

        // Verify user data script wrote hosts.toml into k3s certs.d through the symlink
        String hostsToml = execInContainer(containerId, new String[]{"cat",
                EksClusterManager.CONTAINERD_CERTS_TARGET + "/" + host + "/hosts.toml"});
        assertTrue(hostsToml.contains("\"X-Floci-Test\" = \"registry-hosts\""),
                "the configured header must be present in the container's hosts.toml via the certs.d symlink");

        // Verify hosts.toml survives container restart
        dockerClient.restartContainerCmd(containerId).exec();
        waitUntilReady(cluster);
        String hostsTomlAfterRestart = null;
        long restartDeadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < restartDeadline) {
            try {
                hostsTomlAfterRestart = execInContainer(containerId, new String[]{"cat",
                        EksClusterManager.CONTAINERD_CERTS_TARGET + "/" + host + "/hosts.toml"});
                break;
            } catch (Exception ignored) {
                Thread.sleep(1000);
            }
        }
        assertNotNull(hostsTomlAfterRestart, "hosts.toml must survive container restart");
        assertTrue(hostsTomlAfterRestart.contains("\"X-Floci-Test\" = \"registry-hosts\""),
                "the configured header must survive container restart");

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
                    command: ["/bin/sleep", "3600"]
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
        execInContainerWithExitCode(containerId, new String[]{"kubectl", "delete", "pod", "registry-hosts-pod", "--now"});
    }

    /**
     * Pushes a minimal, genuinely pullable image via raw OCI Distribution calls against Floci's
     * ECR data plane. The image contains a tiny static executable at {@code /bin/sleep} so kubelet
     * can both unpack and run it to reach {@code Running}.
     */
    private void pushMinimalImage(String host, String repo) throws Exception {
        String arch = "amd64";
        boolean isArm64 = false;
        try {
            String dockerArch = dockerClient.infoCmd().exec().getArchitecture();
            if (dockerArch != null && (dockerArch.contains("aarch64") || dockerArch.contains("arm64"))) {
                arch = "arm64";
                isArm64 = true;
            }
        } catch (Exception ignored) {
            // default to amd64
        }

        byte[] elfBytes = buildPauseElf(isArm64);
        byte[] tarBytes;
        try (ByteArrayOutputStream tarOut = new ByteArrayOutputStream();
             TarArchiveOutputStream tar = new TarArchiveOutputStream(tarOut)) {
            TarArchiveEntry entry = new TarArchiveEntry("bin/sleep");
            entry.setMode(0755);
            entry.setSize(elfBytes.length);
            tar.putArchiveEntry(entry);
            tar.write(elfBytes);
            tar.closeArchiveEntry();
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

        byte[] configBytes = ("{\"architecture\":\"" + arch + "\",\"os\":\"linux\",\"config\":{},"
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

    private static byte[] buildPauseElf(boolean isArm64) {
        byte[] code;
        short machine;
        if (isArm64) {
            // aarch64: sys_ppoll(NULL, 0, NULL, NULL, 0) (syscall 73) sleeps indefinitely at 0% CPU
            code = new byte[]{
                    0x00, 0x00, (byte) 0x80, (byte) 0xd2, // mov x0, #0
                    0x01, 0x00, (byte) 0x80, (byte) 0xd2, // mov x1, #0
                    0x02, 0x00, (byte) 0x80, (byte) 0xd2, // mov x2, #0
                    0x03, 0x00, (byte) 0x80, (byte) 0xd2, // mov x3, #0
                    0x04, 0x00, (byte) 0x80, (byte) 0xd2, // mov x4, #0
                    0x28, 0x09, (byte) 0x80, (byte) 0xd2, // mov x8, #73
                    0x01, 0x00, 0x00, (byte) 0xd4,       // svc #0
                    (byte) 0xf9, (byte) 0xff, (byte) 0xff, 0x17  // b -7
            };
            machine = (short) 0xB7;
        } else {
            // x86_64: sys_pause() (syscall 34) sleeps indefinitely at 0% CPU
            code = new byte[]{
                    (byte) 0xb8, 0x22, 0x00, 0x00, 0x00, // mov eax, 34
                    0x0f, 0x05,                         // syscall
                    (byte) 0xeb, (byte) 0xf7            // jmp -9
            };
            machine = (short) 0x3E;
        }
        long baseAddr = 0x400000L;
        long entryPoint = baseAddr + 0x78L;
        int fileSize = 0x78 + code.length;

        ByteBuffer buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        // ELF header (64 bytes)
        buf.put(new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0});
        buf.put(new byte[8]); // padding
        buf.putShort((short) 2); // ET_EXEC
        buf.putShort(machine);
        buf.putInt(1); // EV_CURRENT
        buf.putLong(entryPoint);
        buf.putLong(64L); // e_phoff
        buf.putLong(0L); // e_shoff
        buf.putInt(0); // e_flags
        buf.putShort((short) 64); // e_ehsize
        buf.putShort((short) 56); // e_phentsize
        buf.putShort((short) 1); // e_phnum
        buf.putShort((short) 0); // e_shentsize
        buf.putShort((short) 0); // e_shnum
        buf.putShort((short) 0); // e_shstrndx

        // Program header (56 bytes)
        buf.putInt(1); // PT_LOAD
        buf.putInt(7); // PF_R | PF_W | PF_X
        buf.putLong(0L); // p_offset
        buf.putLong(baseAddr); // p_vaddr
        buf.putLong(baseAddr); // p_paddr
        buf.putLong(fileSize); // p_filesz
        buf.putLong(fileSize); // p_memsz
        buf.putLong(0x1000L); // p_align

        // Code
        buf.put(code);
        return buf.array();
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

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitUntilReady(Cluster cluster) throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            Cluster c = eksService.describeCluster(cluster.getName());
            if (c.getStatus() == ClusterStatus.ACTIVE) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Cluster did not become ACTIVE within timeout");
    }

    private String execInContainer(String containerId, String[] cmd) {
        ExecResult result = execInContainerWithExitCode(containerId, cmd);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Command failed with exit code " + result.exitCode() + ": " + result.stdout());
        }
        return result.stdout();
    }

    private record ExecResult(int exitCode, String stdout) {}

    private ExecResult execInContainerWithExitCode(String containerId, String[] cmd) {
        try {
            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            StringBuilder stdout = new StringBuilder();
            dockerClient.execStartCmd(exec.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame.getStreamType() == StreamType.STDOUT
                                    || frame.getStreamType() == StreamType.STDERR) {
                                stdout.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);

            Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            return new ExecResult(exitCode != null ? exitCode.intValue() : -1, stdout.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Exec command interrupted", e);
        } catch (Exception e) {
            return new ExecResult(-1, e.getMessage());
        }
    }
}
