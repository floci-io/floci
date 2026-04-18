package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Docker lifecycle of k3s containers for real-mode EKS clusters.
 * Not used when {@code floci.services.eks.mock=true}.
 */
@ApplicationScoped
public class EksClusterManager {

    private static final Logger LOG = Logger.getLogger(EksClusterManager.class);
    private static final int K3S_API_SERVER_PORT = 6443;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /**
     * Starts a k3s container for the given cluster. Updates the cluster with
     * the container ID and host port. The cluster status remains CREATING until
     * {@link #isReady(Cluster)} returns true and {@link #finalizeCluster(Cluster)} is called.
     */
    public void startCluster(Cluster cluster) {
        String image = config.services().eks().defaultImage();
        String containerName = "floci-eks-" + cluster.getName();

        LOG.infov("Starting k3s container for EKS cluster: {0} using image {1}",
                cluster.getName(), image);

        // Allocate host port for the k3s API server
        int hostPort = portAllocator.allocate(
                config.services().eks().apiServerBasePort(),
                config.services().eks().apiServerMaxPort());

        // Ensure data directory exists
        Path dataPath = Path.of(config.services().eks().dataPath(), cluster.getName());
        try {
            Files.createDirectories(dataPath);
        } catch (IOException e) {
            LOG.warnv("Could not create EKS data directory {0}: {1}", dataPath, e.getMessage());
        }

        // Remove any stale container
        lifecycleManager.removeIfExists(containerName);

        // Build container spec
        String hostDataPath;
        String hostPersistentPath = config.storage().hostPersistentPath();
        boolean isAbsoluteHostPath = hostPersistentPath.startsWith("/");

        if (isAbsoluteHostPath) {
            String dataPathStr = dataPath.toAbsolutePath().normalize().toString();
            String persistentPathStr = Path.of(config.storage().persistentPath()).toAbsolutePath().normalize().toString();
            hostDataPath = dataPathStr.replace(persistentPathStr, hostPersistentPath);
        } else {
            // Relative or named-volume path: when running inside Docker (DinD), a relative path
            // refers to a path inside this container that the host daemon cannot bind-mount.
            // Use a named Docker volume per cluster instead — the host daemon manages it directly.
            hostDataPath = "floci-eks-" + cluster.getName();
        }

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withCmd(List.of("server",
                        "--disable=traefik",
                        "--tls-san=localhost",
                        "--kube-apiserver-arg=storage-backend=sqlite3",
                        "--kube-apiserver-arg=etcd-servers=unix:///tmp/kine.sock"))
                .withEnv("K3S_KUBECONFIG_MODE", "644")
                .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                .withBind(hostDataPath, "/var/lib/rancher/k3s")
                .withDockerNetwork(config.services().eks().dockerNetwork())
                .withPrivileged(true)
                .withLogRotation()
                .build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        cluster.setContainerId(info.containerId());

        // Set a preliminary endpoint (will be confirmed after readiness check)
        if (containerDetector.isRunningInContainer()) {
            cluster.setEndpoint("https://" + containerName + ":" + K3S_API_SERVER_PORT);
        } else {
            cluster.setEndpoint("https://localhost:" + hostPort);
        }

        LOG.infov("k3s container {0} started for cluster {1} on port {2}",
                info.containerId(), cluster.getName(), hostPort);
    }

    /**
     * Checks whether the k3s API server is ready by polling its /readyz endpoint.
     */
    public boolean isReady(Cluster cluster) {
        String endpoint = cluster.getEndpoint();
        if (endpoint == null || cluster.getContainerId() == null) {
            return false;
        }

        // /livez endpoint on the k3s API server (usually unauthenticated)
        String livezUrl = endpoint + "/livez";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(livezUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            // k3s uses self-signed TLS — disable verification
            if (conn instanceof javax.net.ssl.HttpsURLConnection https) {
                disableSslVerification(https);
            }
            int code = conn.getResponseCode();
            return code == 200 || code == 401 || code == 403;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the kubeconfig from the running k3s container, rewrites the server URL,
     * and sets the certificate authority data on the cluster.
     */
    public void finalizeCluster(Cluster cluster) {
        String containerId = cluster.getContainerId();
        if (containerId == null) {
            return;
        }

        try {
            String kubeconfigYaml = execInContainer(containerId,
                    new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});

            // Extract CA data
            String caData = extractYamlField(kubeconfigYaml, "certificate-authority-data");
            if (caData != null) {
                cluster.setCertificateAuthority(new CertificateAuthority(caData.trim()));
            }

            LOG.infov("Finalized EKS cluster {0} with CA data extracted", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not extract kubeconfig for cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    /**
     * Stops and removes the k3s container for the given cluster.
     */
    public void stopCluster(Cluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        if (config.services().eks().keepRunningOnShutdown()) {
            LOG.infov("Leaving k3s container for cluster {0} running", cluster.getName());
            return;
        }
        lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        LOG.infov("Stopped k3s container for cluster {0}", cluster.getName());
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        var dockerClient = lifecycleManager.getDockerClient();
        ExecCreateCmdResponse exec = dockerClient
                .execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder output = new StringBuilder();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(10, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        return output.toString();
    }

    private String extractYamlField(String yaml, String fieldName) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(fieldName + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return null;
    }

    @SuppressWarnings("java:S4830")
    private void disableSslVerification(javax.net.ssl.HttpsURLConnection conn) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            LOG.debugv("Could not disable SSL verification: {0}", e.getMessage());
        }
    }
}
