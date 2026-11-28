package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.SelfSignedTlsProfile;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterIdentity;
import io.floci.aws.services.eks.model.ClusterStatus;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * IAM roles for service accounts on a real k3s node, through Floci's admission webhook alone: a pod whose service
 * account carries {@code eks.amazonaws.com/role-arn} is created with nothing of its own, the webhook injects the
 * projected token and the web identity variables, and the token the pod reads exchanges for the role's credentials
 * at Floci's STS. A pod of a service account without the annotation is left as it is.
 *
 * <p>A nightly check: it starts a k3s cluster. Its own profile serves the self-signed certificate
 * {@link SelfSignedTlsProfile} writes before boot, which names {@code host.docker.internal}, where the API server
 * calls the webhook: the certificate Floci generates itself only follows TLS settings in system properties or the
 * environment, never a test profile's. On top go the EKS settings {@link EksPodIdentityProfile} holds, its ports
 * included, which put Floci's TLS proxy on the port it routes to Quarkus from.
 */
@QuarkusTest
@TestProfile(EksIrsaWebhookNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the k3s image's pull and the API server, then the webhook and two pods; the Nightly checks job's
// timeout counts the 10.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class EksIrsaWebhookNightlyIntegrationTest {

    /** The self-signed certificate of {@link SelfSignedTlsProfile} with the EKS settings of the admission webhook. */
    public static final class Profile extends SelfSignedTlsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.putAll(new EksPodIdentityProfile().getConfigOverrides());
            return overrides;
        }
    }

    private static final Logger LOG = Logger.getLogger(EksIrsaWebhookNightlyIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String SERVICE_ACCOUNT = "irsa-workload";
    private static final Duration API_SERVER_TIMEOUT = Duration.ofSeconds(120);
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
    IamService iamService;

    private Cluster cluster;
    private String roleName;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the EKS IRSA webhook check");
        Assumptions.assumeTrue(dockerHostResolver.isLinuxHost(),
                "The node reaches Floci through host.docker.internal, which this check expects on a Linux host");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
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
    void aPodOfAnAnnotatedServiceAccountGetsTheRoleThroughTheWebhook() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "irsa-webhook-" + suffix;
        cluster = EksTestNode.cluster(ACCOUNT, clusterName);
        cluster.setVersion(EksService.DEFAULT_K8S_VERSION);
        String issuer = oidcService.newIssuerUrl(REGION);
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        oidcService.ensureKeyForAccount(ACCOUNT, EksService.clusterKey(REGION, clusterName), issuer);
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, API_SERVER_TIMEOUT);
        cluster.setStatus(ClusterStatus.ACTIVE);
        eksService.putClusterForAccount(ACCOUNT, cluster);
        await("the pod identity webhook").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .until(() -> node.kubectl("get", "mutatingwebhookconfiguration", "floci-eks-pod-identity")
                        .exitCode() == 0);

        String issuerHost = issuer.replaceFirst("^https://", "");
        roleName = "irsa-webhook-role-" + suffix;
        String roleArn = iamService.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{\
                "Federated":"arn:aws:iam::%1$s:oidc-provider/%2$s"},"Action":"sts:AssumeRoleWithWebIdentity",\
                "Condition":{"StringEquals":{"%2$s:sub":"system:serviceaccount:default:%3$s",\
                "%2$s:aud":"sts.amazonaws.com"}}}]}""".formatted(ACCOUNT, issuerHost, SERVICE_ACCOUNT),
                null, 3600, null).getArn();
        node.apply("""
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: %s
                  namespace: default
                  annotations:
                    eks.amazonaws.com/role-arn: %s
                """.formatted(SERVICE_ACCOUNT, roleArn));
        runPod(node, "irsa-pod", SERVICE_ACCOUNT);
        runPod(node, "plain-pod", "default");

        assertEquals(roleArn, node.kubectlOutput("exec", "irsa-pod", "--", "printenv", "AWS_ROLE_ARN"));
        assertEquals(REGION, node.kubectlOutput("exec", "irsa-pod", "--", "printenv", "AWS_REGION"));
        String tokenFile = node.kubectlOutput("exec", "irsa-pod", "--", "printenv", "AWS_WEB_IDENTITY_TOKEN_FILE");
        assertEquals("/var/run/secrets/eks.amazonaws.com/serviceaccount/token", tokenFile);
        String token = node.kubectlOutput("exec", "irsa-pod", "--", "cat", tokenFile);
        assertFalse(token.isBlank());
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRoleWithWebIdentity")
                .formParam("Version", "2011-06-15")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "irsa-webhook")
                .formParam("WebIdentityToken", token)
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<AccessKeyId>ASIA"))
                .body(containsString("<SubjectFromWebIdentityToken>system:serviceaccount:default:" + SERVICE_ACCOUNT
                        + "</SubjectFromWebIdentityToken>"));

        assertEquals("", node.kubectlOutput("get", "pod", "plain-pod", "-o",
                "jsonpath={.spec.volumes[?(@.name==\"aws-iam-token\")].name}"));
    }

    private static void runPod(EksTestNode node, String name, String serviceAccount) {
        node.apply("""
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: default
                spec:
                  serviceAccountName: %s
                  containers:
                  - name: workload
                    image: alpine:3.21
                    command: ["sleep", "3600"]
                """.formatted(name, serviceAccount));
        node.awaitPodRunning(name, POD_TIMEOUT);
    }
}
