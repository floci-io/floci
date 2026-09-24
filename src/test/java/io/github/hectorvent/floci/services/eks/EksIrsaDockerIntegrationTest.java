package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker integration test for EKS IRSA: verifies that k3s signs projected service account tokens
 * with the cluster's OIDC keypair and advertises the cluster's Floci OIDC issuer URL.
 *
 * Runs a pod with a projected service account token volume with audience sts.amazonaws.com,
 * calls sts:AssumeRoleWithWebIdentity with the pod's token against an IAM role with a federated
 * trust policy pinning the cluster's OIDC provider and subject, and asserts real temporary
 * credentials and claims are returned. Also asserts rejection when the subject is mismatched
 * or the cluster issuer is untrusted under IAM enforcement.
 */
@QuarkusTest
@TestProfile(EksIrsaDockerIntegrationTest.Profile.class)
class EksIrsaDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksIrsaDockerIntegrationTest.class);
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String ACCOUNT = "000000000000";

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.mock", "false",
                    "floci.services.eks.irsa-signing-key", "true",
                    "floci.services.iam.enforcement-enabled", "true"
            );
        }
    }

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
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksIrsaDockerIntegrationTest");
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
    void projectedServiceAccountTokenAssumesRoleAndEnforcesPolicy() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "irsa-dock-" + suffix;
        roleName = "irsa-dock-role-" + suffix;
        wrongSubjectRoleName = "irsa-dock-wrong-sub-" + suffix;
        wrongClusterRoleName = "irsa-dock-wrong-clus-" + suffix;

        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-service-role");

        String issuer = oidcService.newIssuerUrl("us-east-1");
        cluster.setIdentity(new io.github.hectorvent.floci.services.eks.model.ClusterIdentity(
                new io.github.hectorvent.floci.services.eks.model.OidcIdentity(issuer)));
        oidcService.ensureKeyForAccount(ACCOUNT, clusterName, issuer);

        eksClusterManager.startCluster(cluster);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null");

        // Wait for k3s API server readiness (up to 60s)
        long deadline = System.currentTimeMillis() + 60000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            if (eksClusterManager.isReady(cluster)) {
                ready = true;
                break;
            }
            Thread.sleep(2000);
        }
        assertTrue(ready, "k3s API server must become ready within 60 seconds");
        eksClusterManager.finalizeCluster(cluster);

        String containerId = cluster.getContainerId();

        // Wait for default serviceaccount to be created by controller manager (up to 30s)
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

        // Create a pod with a projected service account token with audience sts.amazonaws.com
        String podYaml = """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: irsa-workload-pod
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
                """;

        execInContainer(containerId, new String[]{"sh", "-c",
                "cat << 'EOF' | kubectl apply -f -\n" + podYaml + "\nEOF"});

        // Wait for the pod to be running (up to 45s)
        long podDeadline = System.currentTimeMillis() + 45000;
        boolean podRunning = false;
        while (System.currentTimeMillis() < podDeadline) {
            ExecResult statusResult = execInContainerWithExitCode(containerId,
                    new String[]{"kubectl", "get", "pod", "irsa-workload-pod", "-o", "jsonpath={.status.phase}"});
            if ("Running".equalsIgnoreCase(statusResult.stdout().trim())) {
                podRunning = true;
                break;
            }
            Thread.sleep(2000);
        }
        assertTrue(podRunning, "irsa-workload-pod must reach Running state");

        // The pod is Running, so it was scheduled onto the node, which the kubelet
        // registered with its provider ID: the node is guaranteed to exist here.
        ExecResult nodeResult = execInContainerWithExitCode(containerId,
                new String[]{"kubectl", "get", "nodes", "-o", "jsonpath={.items[0].spec.providerID}"});
        assertEquals(0, nodeResult.exitCode(), "kubectl get nodes failed");
        assertEquals(eksClusterManager.deriveClusterNodeProviderId(cluster), nodeResult.stdout().trim(),
                "Node providerID must match the derived AWS provider ID");

        ExecResult zoneResult = execInContainerWithExitCode(containerId,
                new String[]{"kubectl", "get", "nodes", "-o",
                        "jsonpath={.items[0].metadata.labels.topology\\.kubernetes\\.io/zone}"});
        assertEquals(0, zoneResult.exitCode(), "kubectl get nodes failed");
        assertEquals(eksClusterManager.deriveClusterNodeAvailabilityZone(cluster), zoneResult.stdout().trim(),
                "Node topology zone label must match the derived availability zone");

        ExecResult regionResult = execInContainerWithExitCode(containerId,
                new String[]{"kubectl", "get", "nodes", "-o",
                        "jsonpath={.items[0].metadata.labels.topology\\.kubernetes\\.io/region}"});
        assertEquals(0, regionResult.exitCode(), "kubectl get nodes failed");
        assertEquals(eksClusterManager.clusterRegion(cluster), regionResult.stdout().trim(),
                "Node topology region label must match the cluster region");

        // Extract the projected token from the pod volume
        String token = execInContainer(containerId,
                new String[]{"kubectl", "exec", "irsa-workload-pod", "--", "cat", "/var/run/secrets/tokens/token"}).trim();
        assertNotNull(token);
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
    }

    private String createRole(String name, String trustPolicy) {
        return given()
            .contentType(FORM)
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
                .formParam("Action", "DeleteRole")
                .formParam("Version", "2010-05-08")
                .formParam("RoleName", name)
            .when()
                .post("/");
        } catch (Exception ignored) {
        }
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
                            if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR) {
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
