package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterIdentity;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.CreatePodIdentityAssociationRequest;
import io.floci.aws.services.eks.model.OidcIdentity;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker integration test of the Pod Identity address on a real k3s node: it answers a pod's
 * credentials request while the {@code eks-pod-identity-agent} add-on is installed, stops answering
 * when DeleteAddon removes the add-on, keeps answering when it removes it with {@code preserve}, and
 * after a Floci restart answers again only while the add-on is stored.
 *
 * <p>The cluster is started through {@link EksClusterManager} directly, on the webhook and TLS
 * settings the Pod Identity forward needs, so this class keeps a profile of its own rather than
 * {@code RealEksProfile}. The add-on is created and deleted through the EKS API, as a client would.
 */
@QuarkusTest
@TestProfile(EksPodIdentityAgentDockerIntegrationTest.Profile.class)
class EksPodIdentityAgentDockerIntegrationTest {

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.mock", "false",
                    "floci.services.eks.pod-identity-webhook", "true",
                    "floci.tls.enabled", "true",
                    "floci.tls.aws-https-port", "0",
                    "quarkus.http.test-port", "4510",
                    "quarkus.http.test-ssl-port", "4511",
                    "floci.port", "4595",
                    "floci.base-url", "http://localhost:4595");
        }
    }

    private static final Logger LOG = Logger.getLogger(EksPodIdentityAgentDockerIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String POD = "pod-identity-client";
    private static final String CREDENTIALS_URL = "http://169.254.170.23/v1/credentials";

    @Inject
    DockerClient dockerClient;

    @Inject
    DockerHostResolver dockerHostResolver;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksService eksService;

    @Inject
    EksOidcService oidcService;

    @Inject
    EksPodIdentityAssociationService associationService;

    @Inject
    EksAddonService addonService;

    @Inject
    IamService iamService;

    private Cluster cluster;
    private String roleName;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the EKS Pod Identity agent test");
        Assumptions.assumeTrue(dockerHostResolver.isLinuxHost(),
                "The node reaches Floci through host.docker.internal, which this test expects on a Linux host");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            addonService.deleteClusterAddons(cluster);
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to stop test cluster {0}", cluster.getName());
            }
            try {
                eksService.deleteClusterForAccount(ACCOUNT, cluster.getName());
            } catch (Exception e) {
                LOG.warnv(e, "Failed to delete test cluster {0}", cluster.getName());
            }
        }
        if (roleName != null) {
            try {
                iamService.deleteRole(roleName);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to delete test role {0}", roleName);
            }
        }
    }

    @Test
    void thePodIdentityAddressAnswersWhileTheAgentAddonIsInstalled() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "pi-agent-" + suffix;
        roleName = "pi-agent-role-" + suffix;
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/" + roleName;
        iamService.createRole(roleName, "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null, 3600, null);

        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-service-role");
        // The default version, so the node runs the configured stock image.
        cluster.setVersion(EksService.DEFAULT_K8S_VERSION);
        String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));

        eksClusterManager.startCluster(cluster);
        String node = cluster.getContainerId();
        assertNotNull(node, "Container ID must not be null");
        awaitClusterReady(node);
        cluster.setStatus(ClusterStatus.ACTIVE);
        eksService.putClusterForAccount(ACCOUNT, cluster);

        String clusterKey = EksService.clusterKey("us-east-1", clusterName);
        assertNotNull(oidcService.ensureKeyForAccount(ACCOUNT, clusterKey, issuer));
        associationService.create(cluster, new CreatePodIdentityAssociationRequest(
                clusterName, "default", "workload-sa", roleArn, null, null, null, null, null));
        String token = oidcService.mintServiceAccountToken(clusterKey, issuer, "default", "workload-sa",
                "pods.eks.amazonaws.com", 3600);
        startClientPod(node);

        // Without the agent nothing answers, as on an EKS node.
        assertEquals(List.of(), podIdentityRules(node));
        assertNotAnswered(node, token);

        createAgent(clusterName);
        assertForwarded(node, token);

        deleteAgent(clusterName, false);
        assertEquals(List.of(), podIdentityRules(node));
        assertNotAnswered(node, token);

        // With preserve, EKS leaves the agent running, so the address keeps answering.
        createAgent(clusterName);
        deleteAgent(clusterName, true);
        assertForwarded(node, token);

        // A restart forwards only while the add-on is stored, which it no longer is.
        restartFloci(node);
        assertEquals(List.of(), podIdentityRules(node));

        createAgent(clusterName);
        restartFloci(node);
        assertForwarded(node, token);
    }

    private void awaitClusterReady(String node) {
        await("k3s API server ready").atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2))
                .until(() -> eksClusterManager.isReady(cluster));
        eksClusterManager.finalizeCluster(cluster);
        await("default serviceaccount created").atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(2))
                .until(() -> kubectl(node, "get", "serviceaccount", "default").exitCode() == 0);
    }

    /** A pod on the pod network, whose requests take the node's PREROUTING rule. */
    private void startClientPod(String node) {
        String podYaml = """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: default
                spec:
                  containers:
                  - name: client
                    image: alpine:3.21
                    command: ["sleep", "3600"]
                """.formatted(POD);
        ContainerExec.Result applied = exec(node, "sh", "-c", "cat << 'EOF' | kubectl apply -f -\n" + podYaml + "\nEOF");
        assertEquals(0, applied.exitCode(), "kubectl apply failed: " + applied.stderr());
        awaitPodRunning(node);
    }

    private void awaitPodRunning(String node) {
        await("client pod running").atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertEquals("Running",
                        kubectl(node, "get", "pod", POD, "-o", "jsonpath={.status.phase}").stdout().trim()));
    }

    private void createAgent(String clusterName) {
        given().header("Authorization", auth()).contentType("application/json")
                .body(Map.of("addonName", EksPodIdentityWebhook.POD_IDENTITY_ADDON, "addonVersion", "v1.4.0-eksbuild.3"))
                .post("/clusters/" + clusterName + "/addons")
                .then().statusCode(200);
    }

    private void deleteAgent(String clusterName, boolean preserve) {
        given().header("Authorization", auth()).queryParam("preserve", preserve)
                .delete("/clusters/" + clusterName + "/addons/" + EksPodIdentityWebhook.POD_IDENTITY_ADDON)
                .then().statusCode(200);
    }

    /**
     * One rule in each chain, both to the same Floci address on its port, and a pod on the pod
     * network and the node itself, where a {@code hostNetwork} pod's requests come from, both get
     * the association's credentials.
     */
    private void assertForwarded(String node, String token) {
        List<String> rules = podIdentityRules(node);
        assertEquals(2, rules.size(), "expected one PREROUTING and one OUTPUT rule, got: " + rules);
        String target = rules.get(0).substring(rules.get(0).indexOf("--to-destination ") + "--to-destination ".length());
        assertTrue(target.endsWith(":4595"), target);
        assertEquals(List.of(
                "-A PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination " + target,
                "-A OUTPUT -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination " + target), rules);

        await("credentials from the pod network").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertCredentials(kubectl(node, "exec", POD, "--", "wget", "-q", "-T", "5",
                        "-O", "-", "--header", "Authorization: " + token, CREDENTIALS_URL)));
        assertCredentials(exec(node, "wget", "-q", "-T", "5", "-O", "-", "--header", "Authorization: " + token,
                CREDENTIALS_URL));
    }

    private static void assertCredentials(ContainerExec.Result result) {
        assertEquals(0, result.exitCode(), result.summary());
        assertTrue(result.stdout().contains("\"AccessKeyId\"") && result.stdout().contains("ASIA"), result.stdout());
    }

    private void assertNotAnswered(String node, String token) {
        ContainerExec.Result result = kubectl(node, "exec", POD, "--", "wget", "-q", "-T", "3", "-O", "-",
                "--header", "Authorization: " + token, CREDENTIALS_URL);
        assertNotEquals(0, result.exitCode(), result.summary());
        assertFalse(result.stdout().contains("AccessKeyId"), result.stdout());
    }

    /** The node's PREROUTING and OUTPUT rules for the Pod Identity address, as iptables lists them. */
    private List<String> podIdentityRules(String node) {
        ContainerExec.Result listed = exec(node, "sh", "-c",
                "IPTABLES=$(command -v iptables || command -v /bin/aux/iptables);"
                        + " $IPTABLES -t nat -S PREROUTING && $IPTABLES -t nat -S OUTPUT");
        assertEquals(0, listed.exitCode(), listed.summary());
        return Arrays.stream(listed.stdout().split("\n"))
                .map(String::trim)
                .filter(rule -> rule.contains("169.254.170.23"))
                .toList();
    }

    /**
     * Floci stops with the cluster left running, Docker stops the node, and Floci starts again and
     * adopts it, which starts it, as {@code restoreCluster} does for a cluster that survived.
     */
    private void restartFloci(String node) {
        eksClusterManager.detachCluster(cluster);
        dockerClient.stopContainerCmd(node).withTimeout(30).exec();
        eksClusterManager.restoreCluster(cluster);
        assertEquals(node, cluster.getContainerId(), "the surviving node is adopted, not replaced");
        await("k3s API server ready again").atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2))
                .until(() -> eksClusterManager.isReady(cluster));
        awaitPodRunning(node);
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260920/us-east-1/eks/aws4_request, SignedHeaders=host, Signature=fake";
    }

    private ContainerExec.Result kubectl(String node, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "kubectl";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return exec(node, cmd);
    }

    private ContainerExec.Result exec(String node, String... cmd) {
        return ContainerExec.run(dockerClient, node, cmd, 30).throwIfTimedOut(node);
    }
}
