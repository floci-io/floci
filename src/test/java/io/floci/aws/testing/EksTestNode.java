package io.floci.aws.testing;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.EksClusterManager;
import io.floci.aws.services.eks.model.Cluster;

import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The k3s node of a cluster that a Docker integration test starts through {@link EksClusterManager}, with
 * {@code kubectl} and other commands run in it.
 */
public final class EksTestNode {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final DockerClient dockerClient;
    private final EksClusterManager eksClusterManager;
    private final Cluster cluster;

    private EksTestNode(DockerClient dockerClient, EksClusterManager eksClusterManager, Cluster cluster) {
        this.dockerClient = dockerClient;
        this.eksClusterManager = eksClusterManager;
        this.cluster = cluster;
    }

    /** A cluster named {@code name} in us-east-1 of {@code account}, not started yet. */
    public static Cluster cluster(String account, String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAccountId(account);
        cluster.setArn("arn:aws:eks:us-east-1:" + account + ":cluster/" + name);
        cluster.setCreatedAt(Instant.now());
        cluster.setRoleArn("arn:aws:iam::" + account + ":role/eks-service-role");
        return cluster;
    }

    /**
     * Starts {@code cluster}'s node and waits until its API server answers and the controller manager has created the
     * default service account, which a pod needs before it can be created.
     */
    public static EksTestNode start(DockerClient dockerClient, EksClusterManager eksClusterManager, Cluster cluster) {
        eksClusterManager.startCluster(cluster);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null");
        EksTestNode node = new EksTestNode(dockerClient, eksClusterManager, cluster);
        node.awaitApiServer();
        eksClusterManager.finalizeCluster(cluster);
        await("default serviceaccount created").atMost(Duration.ofSeconds(30)).pollInterval(POLL_INTERVAL)
                .until(() -> node.kubectl("get", "serviceaccount", "default").exitCode() == 0);
        return node;
    }

    /** The node's container, as the cluster names it now. */
    public String containerId() {
        return cluster.getContainerId();
    }

    /** Waits until the node's API server answers, as after the node starts again. */
    public void awaitApiServer() {
        await("k3s API server ready").atMost(Duration.ofSeconds(90)).pollInterval(POLL_INTERVAL)
                .until(() -> eksClusterManager.isReady(cluster));
    }

    /** Applies {@code yaml} with {@code kubectl apply}. */
    public void apply(String yaml) {
        ContainerExec.Result applied = exec("sh", "-c", "cat << 'EOF' | kubectl apply -f -\n" + yaml + "\nEOF");
        assertEquals(0, applied.exitCode(), "kubectl apply failed: " + applied.stderr());
    }

    /** Waits until pod {@code pod} of the default namespace runs, reporting its container's state if it doesn't. */
    public void awaitPodRunning(String pod) {
        await("pod " + pod + " running").atMost(Duration.ofSeconds(120)).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertEquals("Running",
                        kubectl("get", "pod", pod, "-o", "jsonpath={.status.phase}").stdout().trim(),
                        () -> "the pod must start, but its container reports: " + kubectl("get", "pod", pod, "-o",
                                "jsonpath={.status.containerStatuses[0].state}").stdout()));
    }

    public ContainerExec.Result kubectl(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "kubectl";
        System.arraycopy(args, 0, command, 1, args.length);
        return exec(command);
    }

    public ContainerExec.Result exec(String... command) {
        return ContainerExec.run(dockerClient, containerId(), command, 30).throwIfTimedOut(containerId());
    }
}
