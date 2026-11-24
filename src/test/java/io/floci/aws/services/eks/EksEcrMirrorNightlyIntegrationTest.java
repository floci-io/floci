package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EcrRequests;
import io.floci.aws.testing.EcrTestImages;
import io.floci.aws.testing.EksTestNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The registry mirror Floci gives a k3s node, run for real: in a cluster of an account other than the default one, a pod
 * pulls an image by its own account's registry host as AWS names it, and another pod by the default account's, both
 * images pushed to Floci ECR, as a chart written for EKS names them. Nothing but Floci holds either image, so each pod
 * runs only if containerd took the mirror and Floci served the mirror request from that account's repository.
 *
 * <p>A nightly check: it starts a k3s cluster. Its own profile has Floci listen on the port it advertises, which is the
 * one the generated mirror sends the node to.
 */
@QuarkusTest
@TestProfile(EksEcrMirrorNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the k3s image's pull and the API server, two minutes, then two pods; the Nightly checks job's timeout
// counts the 10.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class EksEcrMirrorNightlyIntegrationTest {

    /** EKS runs its clusters for real, and Floci listens on the port its registry mirror names. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.mock", "false",
                    "quarkus.http.test-port", String.valueOf(PORT),
                    "floci.port", String.valueOf(PORT),
                    "floci.base-url", "http://localhost:" + PORT);
        }
    }

    private static final int PORT = 4593;
    private static final String REGION = "us-east-1";
    private static final String CLUSTER_ACCOUNT = "111122223333";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final Duration API_SERVER_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POD_TIMEOUT = Duration.ofSeconds(90);

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    private Cluster cluster;
    private final List<List<String>> repositories = new ArrayList<>();

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the EKS registry mirror check");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            eksClusterManager.stopCluster(cluster);
        }
        for (List<String> repository : repositories) {
            EcrRequests.ecr(repository.get(0), REGION, "DeleteRepository",
                    "{\"repositoryName\":\"" + repository.get(1) + "\",\"force\":true}");
        }
    }

    @Test
    void podsPullTheirAccountsAndTheDefaultAccountsImagesByTheAwsRegistryHost() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ownImage = pushImage(CLUSTER_ACCOUNT, "mirror-own-" + suffix);
        String defaultImage = pushImage(DEFAULT_ACCOUNT, "mirror-default-" + suffix);

        cluster = EksTestNode.cluster(CLUSTER_ACCOUNT, "ecr-mirror-" + suffix);
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, API_SERVER_TIMEOUT);

        runPod(node, "own-account", ownImage);
        runPod(node, "default-account", defaultImage);
    }

    /** Creates {@code repo} in {@code account} and pushes the image to it; returns its reference by the AWS host. */
    private String pushImage(String account, String repo) throws IOException {
        EcrRequests.ecr(account, REGION, "CreateRepository", "{\"repositoryName\":\"" + repo + "\"}");
        repositories.add(List.of(account, repo));
        EcrTestImages.pushSleepImage(dockerClient, account + ".dkr.ecr." + REGION + ".localhost:" + PORT, repo, "v1");
        return account + ".dkr.ecr." + REGION + ".amazonaws.com/" + repo + ":v1";
    }

    private static void runPod(EksTestNode node, String name, String image) {
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
                """.formatted(name, image));
        node.awaitPodRunning(name, POD_TIMEOUT);
    }
}
