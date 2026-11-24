package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.LaunchTemplate;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EcrRequests;
import io.floci.aws.testing.EcrTestImages;
import io.floci.aws.testing.EksTestNode;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RdsMockProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Docker-gated integration test proving containerd registry host configuration via node group launch
 * template user data works end to end. Real EKS node groups configure containerd hosts (e.g.
 * pull-through cache mirrors with auth headers) by writing /etc/containerd/certs.d/&lt;host&gt;/hosts.toml
 * in launch template user data. Floci symlinks /etc/containerd/certs.d to k3s's containerd certs
 * directory (/var/lib/rancher/k3s/agent/etc/containerd/certs.d) before container start, so standard
 * user data works unchanged. A pod scheduled in the cluster pulls its image through the configured
 * host, with the custom header that registries.yaml could not express.
 *
 * <p>The real cluster comes from {@link RdsMockProfile}, which this class shares with the other
 * classes that run a k3s cluster, so it removes the cluster, node group and launch template it
 * creates.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
// Bounded cluster/workload waits total 285 s; allow Docker setup and cleanup as well.
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class EksRegistryHostsDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksRegistryHostsDockerNightlyIntegrationTest.class);
    private static final String JSON = "application/json";
    private static final String POD = "registry-hosts-pod";
    // Not "000000000000": the generated ECR mirror only covers the default account, so this account's
    // registry host is only reachable at all because of the registry-hosts configuration below.
    private static final String IMAGE_ACCOUNT = "111122223333";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

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

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = DockerAvailability.answers(dockerClient);
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksRegistryHostsDockerNightlyIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for the registry-hosts integration test");
    }

    @Test
    void podPullsThroughRegistryHostConfiguredViaLaunchTemplateUserData() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "reg-hosts-dock-" + suffix;
        String nodegroupName = "ng-" + suffix;
        String repo = "reg-hosts-it-" + suffix;
        String host = IMAGE_ACCOUNT + ".dkr.ecr.us-east-1.localhost:" + httpPort;
        String imageRef = host + "/" + repo + ":v1";

        // ECR answers a push to a repository nobody created with NAME_UNKNOWN, so the account creates it first.
        RestAssuredJsonUtils.configureAwsContentTypes();
        EcrRequests.ecr(IMAGE_ACCOUNT, "us-east-1", "CreateRepository", "{\"repositoryName\":\"" + repo + "\"}");
        cleanup.register(() -> EcrRequests.ecr(IMAGE_ACCOUNT, "us-east-1", "DeleteRepository",
                "{\"repositoryName\":\"" + repo + "\",\"force\":true}"));
        EcrTestImages.pushSleepImage(dockerClient, host, repo, "v1");

        given().contentType(JSON)
                .body("{\"name\":\"" + clusterName + "\",\"roleArn\":"
                        + "\"arn:aws:iam::000000000000:role/eks-service-role\"}")
                .when().post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> eksService.deleteCluster(clusterName));

        Cluster cluster = eksService.describeCluster(clusterName);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null");
        waitUntilReady(cluster);
        eksClusterManager.finalizeCluster(cluster);

        // Verify symlink was created before container started
        assertEquals(EksClusterManager.CONTAINERD_CERTS_TARGET, EksTestNode.of(dockerClient, eksClusterManager, cluster)
                .execOutput("readlink", "/etc/containerd/certs.d"));

        // Configure registry host via node group launch template user data
        String endpoint = "http://" + dockerHostResolver.resolve() + ":" + httpPort;
        String hostsToml = """
                server = "https://%s"

                [host."%s"]
                  capabilities = ["pull", "resolve"]
                  [host."%s".header]
                    "X-Floci-Test" = "registry-hosts"
                """.formatted(host, endpoint, endpoint).stripTrailing();
        String userDataScript = """
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="==MYBOUNDARY=="

                --==MYBOUNDARY==
                Content-Type: text/x-shellscript; charset="us-ascii"

                #!/bin/sh
                mkdir -p /etc/containerd/certs.d/%s
                cat << 'EOF' > /etc/containerd/certs.d/%s/hosts.toml
                %s
                EOF

                --==MYBOUNDARY==--
                """.formatted(host, host, hostsToml);

        LaunchTemplateData ltData = new LaunchTemplateData();
        ltData.setUserData(Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8)));
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "eks-hosts-lt-" + suffix, ltData, List.of());
        cleanup.register(() -> ec2Service.deleteLaunchTemplate("us-east-1", lt.getLaunchTemplateId(), null));

        given().contentType(JSON)
                .body("""
                        {
                          "nodegroupName": "%s",
                          "subnets": ["subnet-12345678"],
                          "nodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
                          "launchTemplate": {
                            "id": "%s",
                            "version": "1"
                          }
                        }
                        """.formatted(nodegroupName, lt.getLaunchTemplateId()))
                .when().post("/clusters/" + clusterName + "/node-groups")
                .then().statusCode(200);
        cleanup.register(() -> eksService.deleteNodeGroup(clusterName, nodegroupName));

        // Re-read cluster and container ID: applying the first node group replaces the k3s container
        // to apply node capacity limits, invalidating the previous container ID.
        cluster = eksService.describeCluster(clusterName);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null after node group creation");
        waitUntilReady(cluster);
        EksTestNode node = EksTestNode.of(dockerClient, eksClusterManager, cluster);

        // Verify user data script wrote hosts.toml into k3s certs.d through the symlink
        String hostsTomlPath = EksClusterManager.CONTAINERD_CERTS_TARGET + "/" + host + "/hosts.toml";
        assertEquals(hostsToml, node.execOutput("cat", hostsTomlPath),
                "the user data's hosts.toml must be in the container's certs.d via the symlink");

        // Verify hosts.toml survives container restart
        dockerClient.restartContainerCmd(node.containerId()).exec();
        Instant restartCompletedAt = Instant.now();
        waitUntilReady(cluster);
        await("hosts.toml after the restart").atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .untilAsserted(() -> assertEquals(hostsToml, node.execOutput("cat", hostsTomlPath),
                        "hosts.toml must survive container restart"));

        await("serviceaccount and Ready nodes with renewed Leases").atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> node.kubectl("--request-timeout=2s", "get", "serviceaccount", "default").exitCode() == 0
                        && node.kubectl("--request-timeout=2s", "wait", "--for=condition=Ready", "nodes", "--all",
                                "--timeout=1s").exitCode() == 0
                        && leasesRenewedAfter(node, restartCompletedAt));

        node.apply("""
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                spec:
                  containers:
                  - name: workload
                    image: %s
                    command: ["/bin/sleep", "3600"]
                """.formatted(POD, imageRef));
        try {
            node.awaitPodRunning(POD, Duration.ofSeconds(45));
        } catch (RuntimeException e) {
            logPodDiagnostics(node.containerId());
            throw e;
        }
        node.kubectl("delete", "pod", POD, "--now");
    }

    /** Whether every node's Lease was renewed after {@code restart}. */
    private static boolean leasesRenewedAfter(EksTestNode node, Instant restart) {
        ContainerExec.Result leases = node.kubectl("--request-timeout=2s", "get", "leases", "-n", "kube-node-lease",
                "-o", "jsonpath={.items[*].spec.renewTime}");
        String renewals = leases.stdout().trim();
        return leases.exitCode() == 0 && !renewals.isEmpty()
                && Arrays.stream(renewals.split("\\s+")).allMatch(renewal -> Instant.parse(renewal).isAfter(restart));
    }

    /** Cleanup removes the k3s container, and kubelet's output reaches nothing else. */
    private void logPodDiagnostics(String containerId) {
        String[][] commands = {
                {"kubectl", "describe", "pod", POD},
                {"kubectl", "describe", "nodes"},
                {"kubectl", "get", "pods", "-A", "-o", "wide"},
                {"crictl", "images"},
                {"cat", "/run/flannel/subnet.env"},
                // An unassigned pod on a Ready node points at the cloud controller, whose role k3s installs as an addon.
                {"kubectl", "get", "crd", "addons.k3s.cattle.io", "-o", "json"},
                {"kubectl", "get", "clusterrole/k3s-cloud-controller-manager",
                        "clusterrolebinding/k3s-cloud-controller-manager", "-o", "json"}
        };
        for (String[] command : commands) {
            try {
                ContainerExec.Result result = ContainerExec.run(dockerClient, containerId, command, 10);
                LOG.warnv("{0}\n{1}", String.join(" ", command), result.summary());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not run {0}", String.join(" ", command));
            }
        }
        LOG.warnv("k3s log tail\n{0}", k3sLogTail(containerId));
    }

    private String k3sLogTail(String containerId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String problem = "";
        try {
            boolean completed = dockerClient.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .withTail(2000)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            out.writeBytes(frame.getPayload());
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
            if (!completed) {
                problem = "(timed out reading logs)";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problem = "(interrupted while reading logs)";
        } catch (Exception e) {
            problem = "(could not read logs: " + e.getMessage() + ")";
        }
        return out.toString(StandardCharsets.UTF_8) + problem;
    }

    private void waitUntilReady(Cluster cluster) {
        await("cluster ACTIVE").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    Cluster c = eksService.describeCluster(cluster.getName());
                    return c.getStatus() == ClusterStatus.ACTIVE && !c.isFinalizePending();
                });
    }
}
