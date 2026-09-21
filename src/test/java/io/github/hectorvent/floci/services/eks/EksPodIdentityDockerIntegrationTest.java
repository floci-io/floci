package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterOidcKey;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.iam.IamService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EksPodIdentityDockerIntegrationTest.Profile.class)
class EksPodIdentityDockerIntegrationTest {

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.pod-identity-webhook", "true");
        }
    }

    private static final Logger LOG = Logger.getLogger(EksPodIdentityDockerIntegrationTest.class);
    private static final String TEST_IMAGE = "alpine:3.21";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EmulatorConfig config;

    @Inject
    EksService eksService;

    @Inject
    EksOidcService oidcService;

    @Inject
    EksPodIdentityAssociationService associationService;

    @Inject
    IamService iamService;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    DockerHostResolver dockerHostResolver;

    private String containerId;
    private Cluster cluster;
    private String clusterName;
    private String roleName;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksPodIdentityDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS Pod Identity integration test");
        Assumptions.assumeTrue(dockerHostResolver.isLinuxHost(),
                "Link-local Pod Identity container test requires a Linux host; on macOS/Windows, container-to-host proxy traffic arrives from 127.0.0.1 which collides across clusters");
    }

    @AfterEach
    void tearDown() {
        if (clusterName != null) {
            try {
                eksService.deleteClusterForAccount("000000000000", clusterName);
            } catch (Exception ignored) {
            }
        }
        if (roleName != null) {
            try {
                iamService.deleteRole(roleName);
            } catch (Exception ignored) {
            }
        }
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void linkLocalPodIdentityEndpointAnswersInsideClusterContainer() throws Exception {
        clusterName = "pi-it-" + UUID.randomUUID().toString().substring(0, 8);
        roleName = "pi-it-role-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::000000000000:role/" + roleName;
        iamService.createRole(roleName, "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null, 3600, null);

        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId("000000000000");
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setStatus(ClusterStatus.ACTIVE);
        String issuer = "https://oidc.eks.us-east-1.amazonaws.com/id/" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        eksService.putClusterForAccount("000000000000", cluster);

        ClusterOidcKey key = oidcService.ensureKeyForAccount("000000000000", clusterName, issuer);
        assertNotNull(key);

        associationService.create(cluster, new CreatePodIdentityAssociationRequest(
                clusterName, "default", "workload-sa", roleArn, null, null, null, null, null
        ));

        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-pi-test-" + clusterName)
                .withPrivileged(true)
                .withHostDockerInternalOnLinux()
                .withCmd(List.of("sleep", "300"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");

        execInContainer(containerId, "command -v curl >/dev/null 2>&1 && command -v iptables >/dev/null 2>&1 && command -v ip >/dev/null 2>&1 || apk add --no-cache curl iptables iproute2");

        eksClusterManager.configurePodIdentityRelay(cluster, containerId);

        String validToken = oidcService.mintServiceAccountToken(clusterName, issuer, "default", "workload-sa",
                "pods.eks.amazonaws.com", 3600);

        String credsJson = execInContainer(containerId, "curl -s -f -H 'Authorization: " + validToken + "' http://169.254.170.23/v1/credentials");
        assertNotNull(credsJson);
        assertTrue(credsJson.contains("\"AccessKeyId\""));
        assertTrue(credsJson.contains("ASIA"));

        String unassociatedToken = oidcService.mintServiceAccountToken(clusterName, issuer, "default", "other-sa",
                "pods.eks.amazonaws.com", 3600);
        String missingCode = execInContainer(containerId, "curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: " + unassociatedToken + "' http://169.254.170.23/v1/credentials");
        assertEquals("404", missingCode.trim());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String execInContainer(String containerId, String cmd) throws Exception {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        StringBuilder stdout = new StringBuilder();
        dockerClient.execStartCmd(exec.getId()).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                if (frame != null && frame.getPayload() != null) {
                    stdout.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }
        }).awaitCompletion(30, TimeUnit.SECONDS);
        return stdout.toString();
    }
}
