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
import io.floci.aws.testing.EksPodIdentityProfile;
import io.floci.aws.testing.EksTestNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
 * settings the Pod Identity forward needs, which {@link EksPodIdentityProfile} holds, rather than on
 * {@code RdsMockProfile}, which the other real-cluster classes share. The add-on is created and
 * deleted through the EKS API, as a client would.
 */
@QuarkusTest
@TestProfile(EksPodIdentityProfile.class)
class EksPodIdentityAgentDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksPodIdentityAgentDockerNightlyIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String POD = "pod-identity-client";
    private static final String CREDENTIALS_URL = "http://169.254.170.23/v1/credentials";
    private static final Duration API_SERVER_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POD_TIMEOUT = Duration.ofSeconds(120);

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
    private EksTestNode node;
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

        cluster = EksTestNode.cluster(ACCOUNT, clusterName);
        // The default version, so the node runs the configured stock image.
        cluster.setVersion(EksService.DEFAULT_K8S_VERSION);
        String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));

        node = EksTestNode.start(dockerClient, eksClusterManager, cluster, API_SERVER_TIMEOUT);
        cluster.setStatus(ClusterStatus.ACTIVE);
        eksService.putClusterForAccount(ACCOUNT, cluster);

        String clusterKey = EksService.clusterKey("us-east-1", clusterName);
        assertNotNull(oidcService.ensureKeyForAccount(ACCOUNT, clusterKey, issuer));
        associationService.create(cluster, new CreatePodIdentityAssociationRequest(
                clusterName, "default", "workload-sa", roleArn, null, null, null, null, null));
        String token = oidcService.mintServiceAccountToken(clusterKey, issuer, "default", "workload-sa",
                "pods.eks.amazonaws.com", 3600);
        startClientPod();

        // Without the agent nothing answers, as on an EKS node.
        assertEquals(List.of(), podIdentityRules());
        assertNotAnswered(token);

        createAgent(clusterName);
        assertForwarded(token);

        deleteAgent(clusterName, false);
        assertEquals(List.of(), podIdentityRules());
        assertNotAnswered(token);

        // With preserve, EKS leaves the agent running, so the address keeps answering.
        createAgent(clusterName);
        deleteAgent(clusterName, true);
        assertForwarded(token);

        // A restart forwards only while the add-on is stored, which it no longer is.
        restartFloci();
        assertEquals(List.of(), podIdentityRules());

        createAgent(clusterName);
        restartFloci();
        assertForwarded(token);
    }

    /** A pod on the pod network, whose requests take the node's PREROUTING rule. */
    private void startClientPod() {
        node.apply("""
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
                """.formatted(POD));
        node.awaitPodRunning(POD, POD_TIMEOUT);
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
    private void assertForwarded(String token) {
        List<String> rules = podIdentityRules();
        assertEquals(2, rules.size(), "expected one PREROUTING and one OUTPUT rule, got: " + rules);
        String target = rules.get(0).substring(rules.get(0).indexOf("--to-destination ") + "--to-destination ".length());
        assertTrue(target.endsWith(":4595"), target);
        assertEquals(List.of(
                "-A PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination " + target,
                "-A OUTPUT -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination " + target), rules);

        await("credentials from the pod network").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> assertCredentials(node.kubectl("exec", POD, "--", "wget", "-q", "-T", "5",
                        "-O", "-", "--header", "Authorization: " + token, CREDENTIALS_URL)));
        assertCredentials(node.exec("wget", "-q", "-T", "5", "-O", "-", "--header", "Authorization: " + token,
                CREDENTIALS_URL));
    }

    private static void assertCredentials(ContainerExec.Result result) {
        assertEquals(0, result.exitCode(), result.summary());
        assertTrue(result.stdout().contains("\"AccessKeyId\"") && result.stdout().contains("ASIA"), result.stdout());
    }

    private void assertNotAnswered(String token) {
        ContainerExec.Result result = node.kubectl("exec", POD, "--", "wget", "-q", "-T", "3", "-O", "-",
                "--header", "Authorization: " + token, CREDENTIALS_URL);
        assertNotEquals(0, result.exitCode(), result.summary());
        assertFalse(result.stdout().contains("AccessKeyId"), result.stdout());
    }

    /** The node's PREROUTING and OUTPUT rules for the Pod Identity address, as iptables lists them. */
    private List<String> podIdentityRules() {
        ContainerExec.Result listed = node.exec("sh", "-c",
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
    private void restartFloci() {
        String containerId = node.containerId();
        eksClusterManager.detachCluster(cluster);
        dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
        eksClusterManager.restoreCluster(cluster);
        assertEquals(containerId, cluster.getContainerId(), "the surviving node is adopted, not replaced");
        node.awaitApiServer(API_SERVER_TIMEOUT);
        node.awaitPodRunning(POD, POD_TIMEOUT);
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260920/us-east-1/eks/aws4_request, SignedHeaders=host, Signature=fake";
    }
}
