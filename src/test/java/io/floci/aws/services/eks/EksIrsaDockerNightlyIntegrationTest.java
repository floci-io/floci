package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterIdentity;
import io.floci.aws.services.eks.model.OidcIdentity;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EksTestNode;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Docker integration test for EKS IRSA: verifies that k3s signs projected service account tokens
 * with the cluster's OIDC keypair and advertises the cluster's Floci OIDC issuer URL.
 *
 * Runs a pod with a projected service account token volume with audience sts.amazonaws.com,
 * calls sts:AssumeRoleWithWebIdentity with the pod's token against an IAM role with a federated
 * trust policy pinning the cluster's OIDC provider and subject, and asserts real temporary
 * credentials and claims are returned. Also asserts rejection when the subject is mismatched
 * or the cluster issuer is untrusted under IAM enforcement.
 *
 * <p>The real cluster, the OIDC signing key and IAM enforcement come from
 * {@link S3IamEnforcementProfile}.
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class EksIrsaDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksIrsaDockerNightlyIntegrationTest.class);
    private static final String FORM = "application/x-www-form-urlencoded";

    /**
     * The root stand-in credential, for the IAM fixtures this test sets up. Those are ordinary
     * management calls that AWS signs, and this test runs with enforcement on, so they carry a
     * credential. The AssumeRoleWithWebIdentity calls under test stay unsigned on purpose: AWS
     * serves that operation without credentials, which is the whole point of web identity.
     */
    private static final String SETUP_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/iam/aws4_request, "
                    + "SignedHeaders=host, Signature=setup";
    private static final String ACCOUNT = "000000000000";
    private static final String POD = "irsa-workload-pod";
    private static final Duration API_SERVER_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksOidcService oidcService;

    private Cluster cluster;
    private String roleName;
    private String wrongSubjectRoleName;
    private String wrongClusterRoleName;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = DockerAvailability.answers(dockerClient);
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksIrsaDockerNightlyIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS IRSA integration test");
    }

    @AfterEach
    void tearDown() {
        if (roleName != null) {
            deleteRole(roleName);
        }
        if (wrongSubjectRoleName != null) {
            deleteRole(wrongSubjectRoleName);
        }
        if (wrongClusterRoleName != null) {
            deleteRole(wrongClusterRoleName);
        }
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Failed to stop test cluster: {0}", e.getMessage());
            }
        }
    }

    @Test
    void projectedServiceAccountTokenAssumesRoleAndEnforcesPolicy() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "irsa-dock-" + suffix;
        roleName = "irsa-dock-role-" + suffix;
        wrongSubjectRoleName = "irsa-dock-wrong-sub-" + suffix;
        wrongClusterRoleName = "irsa-dock-wrong-clus-" + suffix;

        cluster = EksTestNode.cluster(ACCOUNT, clusterName);
        String issuer = oidcService.newIssuerUrl("us-east-1");
        cluster.setIdentity(new ClusterIdentity(
                new OidcIdentity(issuer)));
        oidcService.ensureKeyForAccount(ACCOUNT, EksService.clusterKey("us-east-1", clusterName), issuer);

        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, API_SERVER_TIMEOUT);

        // Create a pod with a projected service account token with audience sts.amazonaws.com
        node.apply("""
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: default
                spec:
                  containers:
                  - name: workload
                    image: alpine:3.21
                    command: ["sleep", "3600"]
                    volumeMounts:
                    - mountPath: /var/run/secrets/tokens
                      name: sa-token
                  serviceAccountName: default
                  volumes:
                  - name: sa-token
                    projected:
                      sources:
                      - serviceAccountToken:
                          path: token
                          expirationSeconds: 86400
                          audience: sts.amazonaws.com
                """.formatted(POD));
        node.awaitPodRunning(POD, Duration.ofSeconds(45));

        // The pod is Running, so it was scheduled onto the node, which the kubelet
        // registered with its provider ID: the node is guaranteed to exist here.
        assertEquals(eksClusterManager.deriveClusterNodeProviderId(cluster),
                node.kubectlOutput("get", "nodes", "-o", "jsonpath={.items[0].spec.providerID}"),
                "Node providerID must match the derived AWS provider ID");
        assertEquals(eksClusterManager.deriveClusterNodePrivateDnsName(cluster),
                node.kubectlOutput("get", "nodes", "-o", "jsonpath={.items[0].metadata.name}"),
                "Node name must match the derived cluster node private DNS name");
        assertEquals(eksClusterManager.deriveClusterNodeAvailabilityZone(cluster),
                node.kubectlOutput("get", "nodes", "-o",
                        "jsonpath={.items[0].metadata.labels.topology\\.kubernetes\\.io/zone}"),
                "Node topology zone label must match the derived availability zone");
        assertEquals(eksClusterManager.clusterRegion(cluster),
                node.kubectlOutput("get", "nodes", "-o",
                        "jsonpath={.items[0].metadata.labels.topology\\.kubernetes\\.io/region}"),
                "Node topology region label must match the cluster region");

        // Extract the projected token from the pod volume
        String token = node.kubectlOutput("exec", POD, "--", "cat", "/var/run/secrets/tokens/token");
        assertFalse(token.isBlank(), "Projected service account token must not be blank");

        // Create IAM role with federated trust policy matching cluster issuer and subject
        String issuerPrefix = issuer.replaceFirst("^https://", "");
        String trustPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{\
                "Federated":"arn:aws:iam::%s:oidc-provider/%s"},\
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{\
                "%s:sub":"system:serviceaccount:default:default","%s:aud":"sts.amazonaws.com"}}}]}"""
                .formatted(ACCOUNT, issuerPrefix, issuerPrefix, issuerPrefix);

        String roleArn = createRole(roleName, trustPolicy);
        assertNotNull(roleArn);

        // 1. Positive case: call AssumeRoleWithWebIdentity with pod token -> succeeds with real credentials
        given()
            .contentType(FORM)
            .formParam("Action", "AssumeRoleWithWebIdentity")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", roleArn)
            .formParam("RoleSessionName", "irsa-docker-session")
            .formParam("WebIdentityToken", token)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<AccessKeyId>ASIA"))
            .body(containsString("<SecretAccessKey>"))
            .body(containsString("<SessionToken>"))
            .body(containsString("<SubjectFromWebIdentityToken>system:serviceaccount:default:default</SubjectFromWebIdentityToken>"))
            .body(containsString("<Provider>" + issuer + "</Provider>"))
            .body(containsString("<Audience>sts.amazonaws.com</Audience>"));

        // 2. Negative case: role trust policy requires different subject -> 403 AccessDenied
        String wrongSubjectTrustPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{\
                "Federated":"arn:aws:iam::%s:oidc-provider/%s"},\
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{\
                "%s:sub":"system:serviceaccount:default:other-service-account","%s:aud":"sts.amazonaws.com"}}}]}"""
                .formatted(ACCOUNT, issuerPrefix, issuerPrefix, issuerPrefix);

        String wrongSubjectRoleArn = createRole(wrongSubjectRoleName, wrongSubjectTrustPolicy);
        given()
            .contentType(FORM)
            .formParam("Action", "AssumeRoleWithWebIdentity")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", wrongSubjectRoleArn)
            .formParam("RoleSessionName", "wrong-sub-session")
            .formParam("WebIdentityToken", token)
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        // 3. Negative case: role trusts a different cluster / provider -> 403 AccessDenied
        String untrustedIssuerPrefix = "oidc.eks.us-east-1.amazonaws.com/id/UNTRUSTEDCLUSTER999999999999";
        String wrongClusterTrustPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{\
                "Federated":"arn:aws:iam::%s:oidc-provider/%s"},\
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{\
                "%s:sub":"system:serviceaccount:default:default","%s:aud":"sts.amazonaws.com"}}}]}"""
                .formatted(ACCOUNT, untrustedIssuerPrefix, untrustedIssuerPrefix, untrustedIssuerPrefix);

        String wrongClusterRoleArn = createRole(wrongClusterRoleName, wrongClusterTrustPolicy);
        given()
            .contentType(FORM)
            .formParam("Action", "AssumeRoleWithWebIdentity")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", wrongClusterRoleArn)
            .formParam("RoleSessionName", "wrong-cluster-session")
            .formParam("WebIdentityToken", token)
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        // 4. Negative case: tampered / invalid signature on token -> 400 InvalidIdentityToken
        given()
            .contentType(FORM)
            .formParam("Action", "AssumeRoleWithWebIdentity")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", roleArn)
            .formParam("RoleSessionName", "tampered-token-session")
            .formParam("WebIdentityToken", token + "invalid-signature")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidIdentityToken"));

        // 5. Container recreation: recreate the cluster container and verify that k3s
        // reuses the same node name on the existing data volume without duplicate or stale nodes
        String containerId = node.containerId();
        dockerClient.stopContainerCmd(containerId).exec();
        dockerClient.removeContainerCmd(containerId).exec();

        EksTestNode recreated = EksTestNode.start(dockerClient, eksClusterManager, cluster, API_SERVER_TIMEOUT);
        assertNotEquals(containerId, recreated.containerId());
        assertEquals(eksClusterManager.deriveClusterNodePrivateDnsName(cluster),
                recreated.kubectlOutput("get", "nodes", "-o", "jsonpath={.items[*].metadata.name}"),
                "Recreated cluster must keep the same node name without leaving duplicate or stale nodes");
    }

    private String createRole(String name, String trustPolicy) {
        return given()
            .contentType(FORM)
            .header("Authorization", SETUP_AUTH)
            .formParam("Action", "CreateRole")
            .formParam("Version", "2010-05-08")
            .formParam("RoleName", name)
            .formParam("AssumeRolePolicyDocument", trustPolicy)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    private void deleteRole(String name) {
        try {
            given()
                .contentType(FORM)
                .header("Authorization", SETUP_AUTH)
                .formParam("Action", "DeleteRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", name)
            .when()
                .post("/");
        } catch (Exception ignored) {
        }
    }
}
