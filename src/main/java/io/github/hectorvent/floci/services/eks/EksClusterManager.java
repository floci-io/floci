package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterOidcKey;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.services.ec2.ClusterNodeInstanceProvider;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataProxy;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages the Docker lifecycle of k3s containers for real-mode EKS clusters.
 * Not used when {@code floci.services.eks.mock=true}.
 */
@ApplicationScoped
public class EksClusterManager implements ClusterNodeInstanceProvider {

    private static final Logger LOG = Logger.getLogger(EksClusterManager.class);
    private static final int K3S_API_SERVER_PORT = 6443;

    private static final String WEBHOOK_CONFIG_DIR = "/etc";
    private static final String WEBHOOK_CONFIG_FILE = "token-webhook.yaml";
    private static final String WEBHOOK_CONFIG_PATH = WEBHOOK_CONFIG_DIR + "/" + WEBHOOK_CONFIG_FILE;
    // Tar entry extracted at /etc; the archive path creates /etc/rancher/k3s, which does not
    // exist yet in a created-but-not-started k3s container.
    private static final String REGISTRIES_TAR_ENTRY = "rancher/k3s/registries.yaml";
    // k3s applies every manifest in its server manifests directory at startup, and again whenever
    // one changes on disk, so dropping the file in before the container starts is enough to get the
    // MutatingWebhookConfiguration registered. The directory sits under the cluster's named data
    // volume; the Docker copy resolves through the container's mounts, so the file lands there.
    static final String K3S_DATA_DIR = "/var/lib/rancher/k3s";
    static final String POD_IDENTITY_MANIFEST_FILE = "floci-eks-pod-identity.yaml";
    static final String POD_IDENTITY_MANIFEST_TAR_ENTRY = "server/manifests/" + POD_IDENTITY_MANIFEST_FILE;
    private static final String ENDPOINT_MODE_NETWORK = "network";
    public static final String DEFAULT_POD_CIDR = "10.42.0.0/16";

    public static final Map<String, String> SUPPORTED_K8S_VERSIONS = Map.of(
            "1.28", "rancher/k3s:v1.28.15-k3s1",
            "1.29", "rancher/k3s:v1.29.14-k3s1",
            "1.30", "rancher/k3s:v1.30.10-k3s1",
            "1.31", "rancher/k3s:v1.31.5-k3s1",
            "1.32", "rancher/k3s:v1.32.2-k3s1",
            "1.33", "rancher/k3s:v1.33.1-k3s1",
            "1.34", "rancher/k3s:v1.34.1-k3s1",
            "1.35", "rancher/k3s:v1.35.0-k3s1",
            "1.36", "rancher/k3s:v1.36.0-k3s1"
    );

    static final String SA_SIGNING_KEY_FILE = "sa-signing-key.pem";
    static final String SA_PUBLIC_KEY_FILE = "sa-public-key.pem";
    static final String SA_SIGNING_KEY_CONTAINER_PATH = WEBHOOK_CONFIG_DIR + "/" + SA_SIGNING_KEY_FILE;
    static final String SA_PUBLIC_KEY_CONTAINER_PATH = WEBHOOK_CONFIG_DIR + "/" + SA_PUBLIC_KEY_FILE;
    static final String KUBERNETES_DEFAULT_ISSUER = "https://kubernetes.default.svc.cluster.local";

    static final String AUDIT_POLICY_FILE = "audit-policy.yaml";
    static final String AUDIT_POLICY_DIR = "/etc";
    static final String AUDIT_POLICY_CONTAINER_PATH = AUDIT_POLICY_DIR + "/" + AUDIT_POLICY_FILE;
    static final String AUDIT_LOG_CONTAINER_PATH = "/var/log/audit.log";
    static final String AUDIT_LOG_MAXAGE = "30";
    static final String AUDIT_LOG_MAXBACKUP = "10";
    static final String AUDIT_LOG_MAXSIZE = "100";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final EcrRegistryManager ecrRegistryManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Ec2MetadataServer metadataServer;
    private final EksOidcService oidcService;
    private final FlociCertificateAuthority certificateAuthority;
    private final ContainerLogStreamer logStreamer;
    private final Map<String, ClusterNodeRecord> clusterNodeInstances = new ConcurrentHashMap<>();
    private final Map<String, Closeable> clusterLogHandles = new ConcurrentHashMap<>();
    private final List<Consumer<Instance>> nodeRegistrationListeners = new CopyOnWriteArrayList<>();

    public void addNodeRegistrationListener(Consumer<Instance> listener) {
        this.nodeRegistrationListeners.add(listener);
    }

    record ClusterNodeRecord(String accountId, String region, Instance instance) {}

    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver) {
        this(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, null, null, null, null);
    }

    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver,
                             Ec2MetadataServer metadataServer) {
        this(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, null, null, null);
    }

    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver,
                             Ec2MetadataServer metadataServer,
                             EksOidcService oidcService) {
        this(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, oidcService, null, null);
    }

    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver,
                             Ec2MetadataServer metadataServer,
                             EksOidcService oidcService,
                             FlociCertificateAuthority certificateAuthority) {
        this(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, oidcService,
                certificateAuthority, null);
    }

    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver,
                             Ec2MetadataServer metadataServer,
                             EksOidcService oidcService,
                             ContainerLogStreamer logStreamer) {
        this(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                dockerHostResolver, ecrRegistryManager, config, regionResolver, metadataServer, oidcService,
                null, logStreamer);
    }

    @Inject
    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver,
                             Ec2MetadataServer metadataServer,
                             EksOidcService oidcService,
                             FlociCertificateAuthority certificateAuthority,
                             ContainerLogStreamer logStreamer) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.ecrRegistryManager = ecrRegistryManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.metadataServer = metadataServer;
        this.oidcService = oidcService;
        this.certificateAuthority = certificateAuthority;
        this.logStreamer = logStreamer;
    }

    /**
     * Attempts {@link #startCluster} and reports the k3s backend as unavailable instead of
     * propagating the failure, when the cause is that no Docker daemon is reachable from Floci:
     * Floci running inside Docker without a mounted socket, or a stopped daemon on the host. A
     * failure raised while the daemon <em>is</em> reachable is a genuine provisioning error and
     * still propagates, so a cluster only reaches FAILED for a reason AWS would also fail on.
     *
     * @return true when the k3s container was created and started
     */
    public boolean tryStartCluster(Cluster cluster) {
        try {
            startCluster(cluster);
            return true;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            LOG.warnv("No Docker daemon is reachable from Floci ({0}). EKS cluster {1} is created as "
                    + "metadata only: describe, list, tag, nodegroups, Fargate profiles and delete "
                    + "work, but the cluster has no Kubernetes API server and kubectl cannot connect "
                    + "to the endpoint it reports.", e.getMessage(), cluster.getName());
            return false;
        }
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * k3s container that failed for its own reasons.
     */
    public boolean isDockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Starts a k3s container for the given cluster. Updates the cluster with
     * the container ID and host port. The cluster status remains CREATING until
     * {@link #isReady(Cluster)} returns true and {@link #finalizeCluster(Cluster)} is called.
     */
    public void startCluster(Cluster cluster) {
        String image = resolveClusterImage(cluster);
        if (cluster.getDockerName() == null) {
            cluster.setDockerName(accountQualifiedName(cluster));
        }
        String containerName = cluster.getDockerName();

        LOG.infov("Starting k3s container for EKS cluster: {0} using image {1}",
                cluster.getName(), image);

        // Allocate host port for the k3s API server
        int hostPort = portAllocator.allocate(
                config.services().eks().apiServerBasePort(),
                config.services().eks().apiServerMaxPort());

        cluster.setHostPort(hostPort);

        // Remove any stale container
        ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, containerName);

        // k3s v1.34+ removed support for --kube-apiserver-arg=storage-backend and
        // --kube-apiserver-arg=etcd-servers. k3s now manages kine (embedded SQLite)
        // internally without those flags.
        //
        // A named Docker volume is used for the k3s data directory instead of a host
        // bind mount. Bind-mounting to a macOS host path causes kine to create its Unix
        // socket (kine.sock) on macOS APFS, which returns EINVAL on chmod — crashing
        // k3s before it can start. Named volumes live in the Docker VM's Linux
        // filesystem, so chmod works correctly and data persists across container restarts.
        String volumeName = cluster.getDockerName();

        String serviceCidr = cluster.getKubernetesNetworkConfig() != null
                && cluster.getKubernetesNetworkConfig().getServiceIpv4Cidr() != null
                ? cluster.getKubernetesNetworkConfig().getServiceIpv4Cidr()
                : EksService.DEFAULT_SERVICE_IPV4_CIDR;
        String clusterCidr = cluster.getPodCidr() != null && !cluster.getPodCidr().isBlank()
                ? cluster.getPodCidr()
                : DEFAULT_POD_CIDR;

        List<String> serverArgs = buildServerArgs(config.services().eks().disableCni(), serviceCidr, clusterCidr);

        try {
            String providerId = deriveClusterNodeProviderId(cluster);
            serverArgs.add("--kubelet-arg=provider-id=" + providerId);
        } catch (Exception e) {
            String clusterName = cluster != null ? cluster.getName() : "unknown";
            LOG.warnv("EKS node provider ID injection disabled for cluster {0}: could not derive provider ID: {1}",
                    clusterName, e.getMessage());
        }

        try {
            String region = clusterRegion(cluster);
            String az = deriveClusterNodeAvailabilityZone(cluster, region);
            serverArgs.add("--kubelet-arg=node-labels=topology.kubernetes.io/zone=" + az
                    + ",topology.kubernetes.io/region=" + region);
        } catch (Exception e) {
            String clusterName = cluster != null ? cluster.getName() : "unknown";
            LOG.warnv("EKS node topology labels injection disabled for cluster {0}: could not derive topology labels: {1}",
                    clusterName, e.getMessage());
        }

        // The account label comes from the cluster record when set (restore runs with no request
        // context); regionResolver is the fallback for the create path.
        String labelAccountId = resolveClusterAccountId(cluster);
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("K3S_KUBECONFIG_MODE", "644")
                .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                .withNamedVolume(volumeName, K3S_DATA_DIR)
                .withDockerNetwork(config.services().eks().dockerNetwork())
                .withPrivileged(true)
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "eks", cluster.getName(), labelAccountId, clusterRegion(cluster)));

        if (config.services().eks().ecrRegistryMirror() && config.services().ecr().enabled()) {
            specBuilder.withHostDockerInternalOnLinux();
        }

        // Wire a token-authentication webhook so `aws eks get-token` bearer tokens are validated by
        // Floci and mapped to their Kubernetes identity. The k3s API server POSTs a TokenReview to Floci's
        // _floci/eks/clusters/<cluster-name>/token-webhook endpoint. The kubeconfig is copied into
        // the container via the Docker API after create and before start (below), not bind-mounted,
        // so it works the same natively and in Docker-in-Docker, with no host-path /
        // host-persistent-path requirement.
        String webhookLocalFile = null;
        if (config.services().eks().iamAuthWebhook()) {
            webhookLocalFile = writeWebhookKubeconfig(cluster);
            if (webhookLocalFile != null) {
                specBuilder.withHostDockerInternalOnLinux();
                serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-config-file="
                        + WEBHOOK_CONFIG_PATH);
                serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-version=v1");
                serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s");
            }
        }

        SigningKeyFiles signingKeyFiles = null;
        if (config.services().eks().irsaSigningKey() && oidcService != null) {
            try {
                String accountId = resolveClusterAccountId(cluster);
                String issuer = resolveClusterIssuer(cluster);
                ClusterOidcKey oidcKey = oidcService.ensureKeyForAccount(accountId, cluster.getName(), issuer);
                signingKeyFiles = writeSigningKeyFiles(cluster, oidcKey);
                if (signingKeyFiles != null) {
                    serverArgs.addAll(buildIrsaServerArgs(oidcKey.getIssuer()));
                }
            } catch (Exception e) {
                LOG.warnv("EKS IRSA signing key injection disabled for cluster {0}: could not prepare keys: {1}",
                        cluster.getName(), e.getMessage());
            }
        }

        String auditPolicyLocalFile = null;
        if (hasLoggingEnabled(cluster, "audit")) {
            auditPolicyLocalFile = writeAuditPolicyFile(cluster);
            if (auditPolicyLocalFile != null) {
                serverArgs.add("--kube-apiserver-arg=audit-policy-file=" + AUDIT_POLICY_CONTAINER_PATH);
                serverArgs.add("--kube-apiserver-arg=audit-log-path=" + AUDIT_LOG_CONTAINER_PATH);
                serverArgs.add("--kube-apiserver-arg=audit-log-maxage=" + AUDIT_LOG_MAXAGE);
                serverArgs.add("--kube-apiserver-arg=audit-log-maxbackup=" + AUDIT_LOG_MAXBACKUP);
                serverArgs.add("--kube-apiserver-arg=audit-log-maxsize=" + AUDIT_LOG_MAXSIZE);
            }
        }

        if (config.services().eks().disableCni()) {
            // A container's /sys mount defaults to private propagation, which breaks
            // Cilium's BPF filesystem mount ("mounted on /sys but it is not a shared or
            // slave mount") — real EKS/kubeadm nodes don't hit this since they're VMs,
            // not nested containers. `mount --make-rshared /` before k3s starts fixes
            // it; kind's own node image runs the same fix in its entrypoint for the
            // same reason. RSHARE_ENTRYPOINT is POSIX sh-compatible (no bashisms) — the
            // k3s image has no bash, only busybox sh.
            specBuilder.withEntrypoint(RSHARE_ENTRYPOINT);
            specBuilder.withCmd(buildRshareWrappedCmd(serverArgs));
        } else {
            specBuilder.withCmd(serverArgs);
        }
        ContainerSpec spec = specBuilder.build();

        // create -> inject webhook kubeconfig -> start, so the file exists before the API server boots.
        String containerId = lifecycleManager.create(spec);
        cluster.setContainerId(containerId);
        if (webhookLocalFile != null) {
            copyWebhookIntoContainer(containerId, webhookLocalFile, cluster.getName());
        }
        if (auditPolicyLocalFile != null) {
            copyAuditPolicyIntoContainer(containerId, auditPolicyLocalFile, cluster.getName());
        }
        injectEcrRegistryMirror(containerId, cluster.getName());
        registerPodIdentityWebhook(containerId, cluster);
        if (signingKeyFiles != null) {
            copySigningKeysIntoContainer(containerId, signingKeyFiles, cluster.getName());
        }
        ContainerInfo info;
        try {
            info = lifecycleManager.startCreated(containerId, spec);
        } catch (Exception e) {
            lifecycleManager.removeIfExists(containerName);
            throw e;
        }

        applyEndpoints(cluster, containerName, hostPort, info);
        registerClusterNodeInstance(cluster, containerId);
        configureLinkLocalMetadataEndpoint(cluster, containerId);
        configurePodIdentityRelay(cluster, containerId);
        attachClusterLogs(cluster);

        LOG.infov("k3s container {0} started for cluster {1} on port {2} (internal: {3})",
                containerId, cluster.getName(), String.valueOf(hostPort), cluster.getInternalEndpoint());
    }

    /**
     * Re-latches a persisted cluster onto its k3s container after a Floci restart. A surviving
     * container - running, or stopped by a Docker daemon reboot - is adopted (started if needed),
     * keeping its published API server port and data volume, so the cluster's workloads come back
     * as they were. When the container is gone, the cluster is recreated via {@link #startCluster};
     * the named k3s data volume is reused if it survived. Callers should put the cluster back into
     * CREATING so the readiness poller re-verifies the API server and re-extracts the certificate
     * authority before marking it ACTIVE again.
     */
    public void restoreCluster(Cluster cluster) {
        if (cluster.getDockerName() == null) {
            cluster.setDockerName(resolveRestoredDockerName(cluster));
        }
        String containerName = cluster.getDockerName();
        var existing = lifecycleManager.findByName(containerName);
        if (existing.isEmpty()) {
            LOG.infov("No surviving k3s container for EKS cluster {0}; recreating it "
                    + "(a surviving data volume is reused)", cluster.getName());
            startCluster(cluster);
            return;
        }

        if (config.services().eks().irsaSigningKey() && oidcService != null) {
            reinjectSigningKeys(existing.get().getId(), cluster);
        }

        ContainerInfo info;
        try {
            info = lifecycleManager.adopt(existing.get().getId(), List.of(K3S_API_SERVER_PORT));
        } catch (Exception e) {
            LOG.warnv("Could not adopt surviving k3s container {0} for EKS cluster {1} ({2}); recreating it",
                    containerName, cluster.getName(), e.getMessage());
            startCluster(cluster);
            return;
        }

        var publishedPort = info.publishedHostPort(K3S_API_SERVER_PORT);
        if (publishedPort.isEmpty()) {
            LOG.warnv("Surviving k3s container {0} publishes no API server port; recreating it", containerName);
            startCluster(cluster);
            return;
        }

        int hostPort = publishedPort.getAsInt();
        // Keep the allocator away from a port Docker already holds for this cluster.
        portAllocator.markReserved(hostPort);
        cluster.setContainerId(info.containerId());
        cluster.setHostPort(hostPort);
        applyEndpoints(cluster, containerName, hostPort, info);
        registerClusterNodeInstance(cluster, info.containerId());
        configureLinkLocalMetadataEndpoint(cluster, info.containerId());
        configurePodIdentityRelay(cluster, info.containerId());
        attachClusterLogsFromNow(cluster);

        LOG.infov("Adopted surviving k3s container {0} for EKS cluster {1} on port {2} (internal: {3})",
                info.containerId(), cluster.getName(), String.valueOf(hostPort), cluster.getInternalEndpoint());
    }

    /**
     * Sets the cluster's public and internal endpoints for a started or adopted container.
     * Public endpoint: see floci.services.eks.endpoint-mode. `host` (default) is the host-reachable
     * published port (k3s cert carries `--tls-san=localhost`, so it verifies against the CA that
     * describe-cluster returns); `network` is the container DNS name (pre-#1118 behaviour).
     * The internal endpoint uses the resolved container IP so the readiness poller works from inside
     * the Docker network (where localhost:<hostPort> would not reach the k3s container).
     */
    private void applyEndpoints(Cluster cluster, String containerName, int hostPort, ContainerInfo info) {
        cluster.setEndpoint(resolvePublicEndpoint(
                containerDetector.isRunningInContainer(), config.services().eks().endpointMode(),
                containerName, hostPort));

        if (containerDetector.isRunningInContainer()) {
            ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(K3S_API_SERVER_PORT);
            cluster.setInternalEndpoint(ep != null
                    ? "https://" + ep.host() + ":" + ep.port()
                    : "https://localhost:" + hostPort);
        } else {
            cluster.setInternalEndpoint("https://localhost:" + hostPort);
        }
    }

    /**
     * Checks whether the k3s API server is ready by polling its /readyz endpoint.
     */
    public boolean isReady(Cluster cluster) {
        // Prefer internalEndpoint (IP-based) for connectivity — works on both user-defined
        // networks and the default bridge where container-name DNS is unavailable.
        String endpoint = cluster.getInternalEndpoint() != null
                ? cluster.getInternalEndpoint()
                : cluster.getEndpoint();
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
     * Stops and removes the k3s container for the given cluster. The k3s data volume follows the
     * storage prune policy ({@link ContainerStorageHelper#removeNamedVolume}): it is only removed
     * in {@code memory} storage mode or when {@code prune-volumes-on-delete} is set, so a persisted
     * cluster's workloads survive a Floci restart and are re-latched by {@link #restoreCluster}.
     */
    public void stopCluster(Cluster cluster) {
        unregisterMetadataEndpoint(cluster);
        Closeable logStream = clusterLogHandles.remove(clusterResourceName(cluster));
        if (cluster.getContainerId() == null) {
            closeQuietly(logStream);
            return;
        }
        lifecycleManager.stopAndRemove(cluster.getContainerId(), logStream);
        ContainerStorageHelper.removeNamedVolume(config, lifecycleManager, clusterResourceName(cluster));
        LOG.infov("Stopped k3s container for cluster {0}", cluster.getName());
    }

    /**
     * Releases Floci's hold on the given cluster (its metadata endpoint and log stream) and leaves
     * the k3s container running, so a later Floci start can re-latch it through
     * {@link #restoreCluster}. Used on shutdown when keep-running-on-shutdown is enabled.
     */
    public void detachCluster(Cluster cluster) {
        unregisterMetadataEndpoint(cluster);
        closeQuietly(clusterLogHandles.remove(clusterResourceName(cluster)));
        if (cluster.getContainerId() != null) {
            LOG.infov("Leaving k3s container for cluster {0} running", cluster.getName());
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Swallowing is safe because closing an already closed or failed log stream during shutdown is best-effort.
            }
        }
    }

    /**
     * Checks whether the cluster has control plane logging enabled for either api or audit.
     */
    public static boolean hasLoggingEnabled(Cluster cluster) {
        return hasLoggingEnabled(cluster, "api") || hasLoggingEnabled(cluster, "audit");
    }

    /**
     * Checks whether the cluster has the specified control plane log type enabled.
     */
    public static boolean hasLoggingEnabled(Cluster cluster, String logType) {
        if (cluster == null || cluster.getLogging() == null || logType == null) {
            return false;
        }
        List<LogSetup> clusterLogging = cluster.getLogging().getClusterLogging();
        if (clusterLogging == null || clusterLogging.isEmpty()) {
            return false;
        }
        for (LogSetup setup : clusterLogging) {
            if (Boolean.TRUE.equals(setup.getEnabled()) && setup.getTypes() != null
                    && setup.getTypes().contains(logType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attaches CloudWatch Logs delivery for the cluster container if logging is enabled.
     */
    public void attachClusterLogs(Cluster cluster) {
        attachClusterLogs(cluster, false);
    }

    /**
     * Attaches CloudWatch Logs delivery for an adopted cluster container, only forwarding lines
     * emitted from now on.
     */
    public void attachClusterLogsFromNow(Cluster cluster) {
        attachClusterLogs(cluster, true);
    }

    private void attachClusterLogs(Cluster cluster, boolean fromNow) {
        if (logStreamer == null || cluster == null || !hasLoggingEnabled(cluster)) {
            return;
        }
        String containerId = cluster.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        String resourceName = clusterResourceName(cluster);
        if (clusterLogHandles.containsKey(resourceName)) {
            return;
        }
        String logGroup = "/aws/eks/" + cluster.getName() + "/cluster";
        String hash = containerId.length() >= 32 ? containerId.substring(0, 32) : containerId;
        String region = clusterRegion(cluster);
        String accountId = resolveClusterAccountId(cluster);

        List<Closeable> handles = new ArrayList<>();
        if (hasLoggingEnabled(cluster, "api")) {
            try {
                String logStream = "kube-apiserver-" + hash;
                Closeable handle = fromNow
                        ? logStreamer.attachFromNowForAccount(
                                accountId, containerId, logGroup, logStream, region, "eks:" + cluster.getName())
                        : logStreamer.attachForAccount(
                                accountId, containerId, logGroup, logStream, region, "eks:" + cluster.getName());
                if (handle != null) {
                    handles.add(handle);
                }
            } catch (Exception e) {
                LOG.warnv("Could not attach control plane log stream for EKS cluster {0}: {1}",
                        cluster.getName(), e.getMessage());
            }
        }

        if (hasLoggingEnabled(cluster, "audit")) {
            try {
                Closeable auditHandle = attachAuditLogFollower(
                        containerId, accountId, logGroup, hash, region, cluster.getName(), fromNow);
                if (auditHandle != null) {
                    handles.add(auditHandle);
                }
            } catch (Exception e) {
                LOG.warnv("Could not attach audit log follower for EKS cluster {0}: {1}",
                        cluster.getName(), e.getMessage());
            }
        }

        if (handles.size() == 1) {
            clusterLogHandles.put(resourceName, handles.getFirst());
        } else if (handles.size() > 1) {
            clusterLogHandles.put(resourceName, () -> {
                for (Closeable h : handles) {
                    closeQuietly(h);
                }
            });
        }
    }

    private Closeable attachAuditLogFollower(String containerId, String accountId, String logGroup,
                                             String hash, String region, String clusterName, boolean fromNow) {
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        if (dockerClient == null) {
            return null;
        }
        String logStream = "kube-apiserver-audit-" + hash;
        logStreamer.ensureLogGroupAndStreamForAccount(accountId, logGroup, logStream, region);
        String tailLineArg = fromNow ? "0" : "+1";
        String[] cmd = new String[] {
                "sh", "-c", "touch " + AUDIT_LOG_CONTAINER_PATH + " && exec tail -n " + tailLineArg + " -F " + AUDIT_LOG_CONTAINER_PATH
        };
        ExecCreateCmdResponse execCreate = dockerClient
                .execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(false)
                .exec();
        return dockerClient
                .execStartCmd(execCreate.getId())
                .exec(logStreamer.execLogCallbackForAccount(
                        accountId, logGroup, logStream, region, "eks-audit:" + clusterName));
    }

    Closeable getLogHandle(Cluster cluster) {
        return clusterLogHandles.get(clusterResourceName(cluster));
    }

    /**
     * Docker container/volume name for a cluster: the name already resolved for this record
     * ({@link Cluster#getDockerName()}, set by startCluster/restoreCluster), or the
     * account-qualified name for a record no container operation has touched yet.
     */
    String clusterResourceName(Cluster cluster) {
        return cluster.getDockerName() != null ? cluster.getDockerName() : accountQualifiedName(cluster);
    }

    /**
     * Account-qualified Docker name for a cluster. Cluster names are unique only within an
     * account, so a non-default account's cluster is qualified with its account ID — otherwise
     * two accounts' same-named clusters would resolve to the same container and data volume,
     * letting one account reach (or, via startCluster's stale-container removal, destroy) the
     * other's workloads. The default account keeps the historical unqualified name so existing
     * containers, volumes, and {@code endpoint-mode=network} DNS names keep working.
     *
     * <p>The qualifier separator is a dot: EksService validates cluster names against the AWS
     * charset ({@code [0-9A-Za-z][A-Za-z0-9\-_]*}), which admits no dot, so no default-account
     * cluster name can spell out {@code <accountId>.<name>} and collide with another account's
     * qualified name — a dash separator would (cluster "999999999999-demo" vs account
     * 999999999999's "demo"). Dots are valid in Docker container and volume names.
     *
     * <p>The record's accountId is set before every call path reaches here (createCluster on
     * create; the startup account rehydration on restore/stop); a null falls back to the
     * default-account name.
     */
    private String accountQualifiedName(Cluster cluster) {
        String accountId = cluster.getAccountId();
        boolean defaultAccount = accountId == null || accountId.equals(config.defaultAccountId());
        return ContainerStorageHelper.resourceName(config, "eks", null,
                defaultAccount ? cluster.getName() : accountId + "." + cluster.getName());
    }

    /**
     * Resolves which Docker name a restored record's resources actually live under. Clusters
     * created before account-qualified naming used the account-independent legacy name
     * {@code floci-eks-<name>} for every account — a non-default account's cluster must keep
     * that name when its own container survived there, or the upgrade would recreate the
     * cluster under the qualified name and orphan the historical workloads. The legacy
     * container is claimed only when its {@code io.floci.account} label matches the owning
     * account; another account's container — or one with no verifiable owner — is left
     * untouched and the cluster starts fresh under the qualified name. A surviving legacy
     * volume without its container carries no ownership label and is deliberately not claimed —
     * it is reported via {@link #warnUnclaimedLegacyState} so the operator can migrate the data
     * by hand. The result is deterministic across restarts for a given Docker state.
     */
    private String resolveRestoredDockerName(Cluster cluster) {
        String qualified = accountQualifiedName(cluster);

        // A cluster created after account qualification but before the floci-aws- rename lives
        // under the qualified name with the old prefix. Nothing else about it changed, so adopt
        // it outright rather than putting it through the ownership check below.
        String prefixLegacyQualified = ContainerStorageHelper.legacyDockerName(config, qualified);
        if (!prefixLegacyQualified.equals(qualified)
                && lifecycleManager.findByName(prefixLegacyQualified).isPresent()) {
            LOG.infov("EKS cluster {0} keeps its pre-rename Docker name {1}",
                    cluster.getName(), prefixLegacyQualified);
            return prefixLegacyQualified;
        }

        // Clusters predating account qualification used the unqualified name, which also predates
        // the rename, so it is resolved with the frozen legacy prefix.
        String legacy = ContainerStorageHelper.legacyResourceName(config, "eks", null, cluster.getName());
        if (legacy.equals(ContainerStorageHelper.legacyDockerName(config, qualified))) {
            return qualified; // default account: the names never diverged
        }
        var legacySurvivor = lifecycleManager.findByName(legacy);
        if (legacySurvivor.isPresent()) {
            var labels = legacySurvivor.get().getLabels();
            String owner = labels != null ? labels.get("io.floci.account") : null;
            if (cluster.getAccountId() != null && cluster.getAccountId().equals(owner)) {
                LOG.infov("EKS cluster {0} (account {1}) keeps its pre-upgrade Docker name {2}",
                        cluster.getName(), cluster.getAccountId(), legacy);
                return legacy;
            }
            if (owner == null) {
                warnUnclaimedLegacyState(cluster, legacy, "container");
            }
            // A container labeled with another account is simply not this cluster's — no warning.
        } else if (volumeExists(legacy)) {
            warnUnclaimedLegacyState(cluster, legacy, "data volume");
        }
        return qualified;
    }

    /**
     * A legacy-named container or volume whose owning account cannot be verified is never claimed
     * for a non-default account — handing it over on a guess would expose another account's data,
     * the very cross-bind the qualified names exist to prevent. It is reported instead of being
     * silently orphaned, so an operator who knows the data belongs to this cluster can migrate it
     * into the qualified volume by hand.
     */
    private void warnUnclaimedLegacyState(Cluster cluster, String legacy, String kind) {
        LOG.warnv("EKS cluster {0} (account {1}) starts under its account-qualified Docker name; "
                + "a pre-upgrade {2} named {3} survives but carries no verifiable owning account, "
                + "so it is NOT adopted. If its data belongs to this cluster, copy it into the "
                + "cluster's qualified volume manually (docker volume inspect {3}).",
                cluster.getName(), cluster.getAccountId(), kind, legacy);
    }

    /** Whether a Docker volume with this exact name exists. Any lookup failure counts as absent. */
    private boolean volumeExists(String volumeName) {
        try {
            lifecycleManager.getDockerClient().inspectVolumeCmd(volumeName).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves the k3s container image for a cluster.
     * Uses imageTemplate if configured, otherwise maps explicitly requested Kubernetes versions
     * to stable k3s images, falling back to the configured defaultImage when no version was requested.
     */
    String resolveClusterImage(Cluster cluster) {
        String configuredDefault = config.services().eks().defaultImage();
        boolean hasExplicitVersion = cluster != null
                && (cluster.isExplicitVersion()
                        || (cluster.getVersion() != null && !EksService.DEFAULT_K8S_VERSION.equals(cluster.getVersion())));
        if (hasExplicitVersion) {
            String version = cluster.getVersion();
            if (config.services().eks().imageTemplate().isPresent()) {
                String template = config.services().eks().imageTemplate().get();
                return template.contains("%s") ? String.format(template, version) : template;
            }
            String mapped = SUPPORTED_K8S_VERSIONS.get(version);
            if (mapped != null) {
                return mapped;
            }
            return "rancher/k3s:v" + version + ".0-k3s1";
        }
        return configuredDefault != null && !configuredDefault.isBlank()
                ? configuredDefault
                : "rancher/k3s:latest";
    }

    /**
     * Builds the k3s {@code server} command-line args. When {@code disableCni} is true, flannel,
     * k3s's default network policy controller, and kube-proxy are all disabled up front: see the
     * {@code disableCni} config javadoc for why this must happen at startup, not after the fact.
     */
    static List<String> buildServerArgs(boolean disableCni, String serviceCidr, String clusterCidr) {
        List<String> serverArgs = new ArrayList<>(List.of("server",
                "--disable=traefik",
                "--tls-san=localhost"));
        if (disableCni) {
            serverArgs.add("--flannel-backend=none");
            serverArgs.add("--disable-network-policy");
            serverArgs.add("--disable-kube-proxy");
        }
        if (serviceCidr != null && !serviceCidr.isBlank()) {
            serverArgs.add("--service-cidr=" + serviceCidr);
        }
        if (clusterCidr != null && !clusterCidr.isBlank()) {
            serverArgs.add("--cluster-cidr=" + clusterCidr);
        }
        return serverArgs;
    }

    static List<String> buildServerArgs(boolean disableCni) {
        return buildServerArgs(disableCni, null, null);
    }

    /**
     * Overrides the image's default {@code ["/bin/k3s"]} entrypoint so a {@code mount
     * --make-rshared /} can run immediately before k3s starts (see the disableCni branch
     * in {@link #startCluster} for why). POSIX sh-compatible — the k3s image has no bash.
     * A failed mount is logged to stderr rather than silently ignored, since it means the
     * external CNI's BPF filesystem mount will fail later in a much more confusing way.
     */
    static final List<String> RSHARE_ENTRYPOINT = List.of("sh", "-c",
            "mount --make-rshared / || echo 'floci: WARN: mount --make-rshared / failed; "
                    + "external CNI may not work' >&2; exec /bin/k3s \"$@\"");

    /**
     * Builds the CMD to pair with {@link #RSHARE_ENTRYPOINT}: an unused $0 placeholder
     * followed by the real k3s server args, so the entrypoint's "$@" expands to exactly
     * {@code serverArgs} — the same args {@code withCmd(serverArgs)} would pass directly
     * when the entrypoint isn't overridden.
     */
    static List<String> buildRshareWrappedCmd(List<String> serverArgs) {
        List<String> wrappedCmd = new ArrayList<>();
        wrappedCmd.add("floci-k3s");
        wrappedCmd.addAll(serverArgs);
        return wrappedCmd;
    }

    /**
     * Resolves the public {@code describe-cluster} endpoint. Returns the container DNS name only when
     * Floci runs in a container and {@code endpoint-mode=network}; otherwise the host-reachable
     * published port (the default, and the only usable value in native mode).
     */
    static String resolvePublicEndpoint(boolean inContainer, String endpointMode,
                                        String containerName, int hostPort) {
        if (inContainer && ENDPOINT_MODE_NETWORK.equalsIgnoreCase(endpointMode)) {
            return "https://" + containerName + ":" + K3S_API_SERVER_PORT;
        }
        return "https://localhost:" + hostPort;
    }

    /**
     * Writes the token-webhook kubeconfig for the given cluster to Floci's local filesystem and
     * returns its path (basename {@value #WEBHOOK_CONFIG_FILE}), or {@code null} if it could not be
     * written (in which case the caller skips the webhook so cluster creation still succeeds). The
     * file is later streamed into the container via the Docker API, so no host path is involved.
     */
    private String writeWebhookKubeconfig(Cluster cluster) {
        String clusterName = cluster.getName();
        Path localFile = Paths.get(config.services().eks().dataPath(), "webhook", clusterName, WEBHOOK_CONFIG_FILE)
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, buildWebhookKubeconfig("http://" + dockerHostResolver.resolve() + ":"
                    + config.port() + webhookPath(cluster)));
        } catch (IOException e) {
            LOG.warnv("EKS token-webhook disabled for cluster {0}: could not write kubeconfig: {1}",
                    clusterName, e.getMessage());
            return null;
        }
        return localFile.toString();
    }

    /**
     * Streams the webhook kubeconfig from Floci's filesystem into the (created, not-yet-started)
     * k3s container at {@value #WEBHOOK_CONFIG_PATH}, using the Docker API. Reading the file
     * client-side avoids any host bind-mount, so this works in native and Docker-in-Docker modes
     * alike. A failure here disables the webhook for the cluster but does not abort its startup.
     */
    private void copyWebhookIntoContainer(String containerId, String localFile, String clusterName) {
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withHostResource(localFile)
                    .withRemotePath(WEBHOOK_CONFIG_DIR)
                    .exec();
        } catch (Exception e) {
            LOG.warnv("EKS token-webhook may not authenticate for cluster {0}: could not copy kubeconfig "
                    + "into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    /**
     * Writes the official Amazon EKS audit policy YAML to Floci's local data directory and
     * returns its path, or {@code null} if writing failed.
     */
    String writeAuditPolicyFile(Cluster cluster) {
        String clusterName = cluster.getName();
        try {
            String dataPath = config.services().eks().dataPath();
            if (dataPath == null || dataPath.isBlank()) {
                return null;
            }
            Path localFile = Paths.get(dataPath, "audit", clusterName, AUDIT_POLICY_FILE)
                    .toAbsolutePath().normalize();
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, buildAuditPolicy());
            return localFile.toString();
        } catch (Exception e) {
            LOG.warnv("EKS audit logging disabled for cluster {0}: could not write audit policy file: {1}",
                    clusterName, e.getMessage());
            return null;
        }
    }

    /**
     * Streams the audit policy from Floci's filesystem into the (created, not-yet-started)
     * k3s container at {@value #AUDIT_POLICY_CONTAINER_PATH}, using the Docker API.
     */
    void copyAuditPolicyIntoContainer(String containerId, String localFile, String clusterName) {
        try {
            DockerClient dockerClient = lifecycleManager.getDockerClient();
            if (dockerClient != null) {
                dockerClient.copyArchiveToContainerCmd(containerId)
                        .withHostResource(localFile)
                        .withRemotePath(AUDIT_POLICY_DIR)
                        .exec();
                LOG.debugv("Injected audit policy file into k3s container {0} for cluster {1}",
                        containerId, clusterName);
            }
        } catch (Exception e) {
            LOG.warnv("EKS audit logs may not be emitted for cluster {0}: could not copy "
                    + "audit policy into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    /**
     * Official Amazon EKS control plane audit policy documented in the Amazon EKS Best Practices Guide.
     */
    public static String buildAuditPolicy() {
        return """
                apiVersion: audit.k8s.io/v1
                kind: Policy
                rules:
                  # Log full request and response for changes to aws-auth ConfigMap in kube-system namespace
                  - level: RequestResponse
                    namespaces: ["kube-system"]
                    verbs: ["update", "patch", "delete"]
                    resources:
                      - group: ""
                        resources: ["configmaps"]
                        resourceNames: ["aws-auth"]
                    omitStages:
                      - "RequestReceived"

                  # Do not log watch operations performed by kube-proxy on endpoints and services
                  - level: None
                    users: ["system:kube-proxy"]
                    verbs: ["watch"]
                    resources:
                      - group: ""
                        resources: ["endpoints", "services", "services/status"]

                  # Do not log get operations performed by kubelet on nodes and their statuses
                  - level: None
                    users: ["kubelet"]
                    verbs: ["get"]
                    resources:
                      - group: ""
                        resources: ["nodes", "nodes/status"]

                  # Do not log get operations performed by the system:nodes group on nodes and their statuses
                  - level: None
                    userGroups: ["system:nodes"]
                    verbs: ["get"]
                    resources:
                      - group: ""
                        resources: ["nodes", "nodes/status"]

                  # Do not log get and update operations performed by controller manager, scheduler, and endpoint-controller on endpoints in kube-system namespace
                  - level: None
                    users:
                      - system:kube-controller-manager
                      - system:kube-scheduler
                      - system:serviceaccount:kube-system:endpoint-controller
                    verbs: ["get", "update"]
                    namespaces: ["kube-system"]
                    resources:
                      - group: ""
                        resources: ["endpoints"]

                  # Do not log get operations performed by apiserver on namespaces and their statuses/finalizations
                  - level: None
                    users: ["system:apiserver"]
                    verbs: ["get"]
                    resources:
                      - group: ""
                        resources: ["namespaces", "namespaces/status", "namespaces/finalize"]

                  # Do not log get and list operations performed by controller manager on metrics.k8s.io resources
                  - level: None
                    users:
                      - system:kube-controller-manager
                    verbs: ["get", "list"]
                    resources:
                      - group: "metrics.k8s.io"

                  # Do not log access to health, version, and swagger non-resource URLs
                  - level: None
                    nonResourceURLs:
                      - /healthz*
                      - /version
                      - /swagger*

                  # Do not log events resources
                  - level: None
                    resources:
                      - group: ""
                        resources: ["events"]

                  # Log request for updates/patches to nodes and pods statuses by kubelet and node problem detector
                  - level: Request
                    users: ["kubelet", "system:node-problem-detector", "system:serviceaccount:kube-system:node-problem-detector"]
                    verbs: ["update", "patch"]
                    resources:
                      - group: ""
                        resources: ["nodes/status", "pods/status"]
                    omitStages:
                      - "RequestReceived"

                  # Log request for updates/patches to nodes and pods statuses by system:nodes group
                  - level: Request
                    userGroups: ["system:nodes"]
                    verbs: ["update", "patch"]
                    resources:
                      - group: ""
                        resources: ["nodes/status", "pods/status"]
                    omitStages:
                      - "RequestReceived"

                  # Log delete collection requests by namespace-controller in kube-system namespace
                  - level: Request
                    users: ["system:serviceaccount:kube-system:namespace-controller"]
                    verbs: ["deletecollection"]
                    omitStages:
                      - "RequestReceived"

                  # Log metadata for secrets, configmaps, and tokenreviews to protect sensitive data
                  - level: Metadata
                    resources:
                      - group: ""
                        resources: ["secrets", "configmaps"]
                      - group: authentication.k8s.io
                        resources: ["tokenreviews"]
                    omitStages:
                      - "RequestReceived"

                  # Log requests for serviceaccounts/token resources
                  - level: Request
                    resources:
                      - group: ""
                        resources: ["serviceaccounts/token"]

                  # Log get, list, and watch requests for various resource groups
                  - level: Request
                    verbs: ["get", "list", "watch"]
                    resources:
                      - group: ""
                      - group: "admissionregistration.k8s.io"
                      - group: "apiextensions.k8s.io"
                      - group: "apiregistration.k8s.io"
                      - group: "apps"
                      - group: "authentication.k8s.io"
                      - group: "authorization.k8s.io"
                      - group: "autoscaling"
                      - group: "batch"
                      - group: "certificates.k8s.io"
                      - group: "extensions"
                      - group: "metrics.k8s.io"
                      - group: "networking.k8s.io"
                      - group: "policy"
                      - group: "rbac.authorization.k8s.io"
                      - group: "scheduling.k8s.io"
                      - group: "settings.k8s.io"
                      - group: "storage.k8s.io"
                    omitStages:
                      - "RequestReceived"

                  # Default logging level for known APIs to log request and response
                  - level: RequestResponse
                    resources:
                      - group: ""
                      - group: "admissionregistration.k8s.io"
                      - group: "apiextensions.k8s.io"
                      - group: "apiregistration.k8s.io"
                      - group: "apps"
                      - group: "authentication.k8s.io"
                      - group: "authorization.k8s.io"
                      - group: "autoscaling"
                      - group: "batch"
                      - group: "certificates.k8s.io"
                      - group: "extensions"
                      - group: "metrics.k8s.io"
                      - group: "networking.k8s.io"
                      - group: "policy"
                      - group: "rbac.authorization.k8s.io"
                      - group: "scheduling.k8s.io"
                      - group: "settings.k8s.io"
                      - group: "storage.k8s.io"
                    omitStages:
                      - "RequestReceived"

                  # Default logging level for all other requests to log metadata only
                  - level: Metadata
                    omitStages:
                      - "RequestReceived"
                """;
    }

    /**
     * Builds the API server arguments configuring k3s to sign service account tokens with the
     * cluster's OIDC keypair and advertise the cluster's OIDC issuer URL. api-audiences includes
     * both the standard Kubernetes in-cluster audience and STS_AUDIENCE.
     */
    static List<String> buildIrsaServerArgs(String issuerUrl) {
        if (issuerUrl == null || issuerUrl.isBlank()) {
            throw new IllegalArgumentException("issuerUrl is required");
        }
        return List.of(
                "--kube-apiserver-arg=service-account-signing-key-file=" + SA_SIGNING_KEY_CONTAINER_PATH,
                "--kube-apiserver-arg=service-account-key-file=" + SA_PUBLIC_KEY_CONTAINER_PATH,
                "--kube-apiserver-arg=service-account-issuer=" + issuerUrl,
                "--kube-apiserver-arg=service-account-issuer=" + KUBERNETES_DEFAULT_ISSUER,
                "--kube-apiserver-arg=api-audiences=" + KUBERNETES_DEFAULT_ISSUER + "," + EksOidcService.STS_AUDIENCE
        );
    }

    String resolveClusterIssuer(Cluster cluster) {
        if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                && cluster.getIdentity().getOidc().getIssuer() != null
                && !cluster.getIdentity().getOidc().getIssuer().isBlank()) {
            return cluster.getIdentity().getOidc().getIssuer();
        }
        String region = clusterRegion(cluster);
        String issuer = oidcService.newIssuerUrl(region);
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        return issuer;
    }

    record SigningKeyFiles(Path signingKeyPath, Path publicKeyPath) {}

    String resolveClusterAccountId(Cluster cluster) {
        if (cluster != null) {
            if (cluster.getAccountId() != null && !cluster.getAccountId().isBlank()) {
                return cluster.getAccountId();
            }
            if (cluster.getArn() != null) {
                String[] parts = cluster.getArn().split(":");
                if (parts.length > 4 && !parts[4].isBlank()) {
                    return parts[4];
                }
            }
        }
        if (regionResolver != null && regionResolver.getAccountId() != null && !regionResolver.getAccountId().isBlank()) {
            return regionResolver.getAccountId();
        }
        if (config != null && config.defaultAccountId() != null && !config.defaultAccountId().isBlank()) {
            return config.defaultAccountId();
        }
        return "000000000000";
    }

    Path resolveKeysDir(Cluster cluster) {
        String accountId = resolveClusterAccountId(cluster);
        String region = clusterRegion(cluster);
        return Paths.get(config.services().eks().dataPath(), "keys", accountId, region, cluster.getName())
                .toAbsolutePath().normalize();
    }

    /**
     * Writes the cluster's RSA signing key and public key PEM files to Floci's local filesystem
     * under the account- and region-qualified EKS data path with restrictive permissions (0600 for
     * files, 0700 for directories). Files are created with owner-only permissions atomically from
     * creation, avoiding any window with default umask permissions. Returns the paths or null if
     * writing failed.
     */
    SigningKeyFiles writeSigningKeyFiles(Cluster cluster, ClusterOidcKey oidcKey) {
        Path keysDir = resolveKeysDir(cluster);
        try {
            if (!Files.isDirectory(keysDir)) {
                if (Files.getFileAttributeView(keysDir.getParent(), PosixFileAttributeView.class) != null) {
                    Files.createDirectories(keysDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(keysDir);
                }
            }
            setRestrictivePermissions(keysDir, true);

            Path signingKeyPath = keysDir.resolve(SA_SIGNING_KEY_FILE);
            writeSecureFile(signingKeyPath, oidcService.exportSigningKeyPem(oidcKey), "rw-------");

            Path publicKeyPath = keysDir.resolve(SA_PUBLIC_KEY_FILE);
            writeSecureFile(publicKeyPath, oidcService.exportPublicKeyPem(oidcKey), "rw-------");

            return new SigningKeyFiles(signingKeyPath, publicKeyPath);
        } catch (IOException e) {
            LOG.warnv("EKS IRSA signing key disabled for cluster {0}: could not write key files: {1}",
                    cluster.getName(), e.getMessage());
            return null;
        }
    }

    private static void writeSecureFile(Path path, String content, String posixPerms) throws IOException {
        Files.deleteIfExists(path);
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(posixPerms)));
        } else {
            Files.createFile(path);
        }
        Files.writeString(path, content);
        setRestrictivePermissions(path, false);
    }

    private static void setRestrictivePermissions(Path path, boolean isDirectory) {
        try {
            Set<PosixFilePermission> perms = isDirectory
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            File file = path.toFile();
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            if (isDirectory) {
                file.setExecutable(false, false);
                file.setExecutable(true, true);
            } else {
                file.setExecutable(false, false);
            }
        }
    }

    /**
     * Streams the signing key and public key PEM files into the k3s container at /etc using the Docker API.
     * A failure logs a warning and lets cluster startup continue.
     */
    void copySigningKeysIntoContainer(String containerId, SigningKeyFiles keyFiles, String clusterName) {
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withHostResource(keyFiles.signingKeyPath().toString())
                    .withRemotePath(WEBHOOK_CONFIG_DIR)
                    .exec();
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withHostResource(keyFiles.publicKeyPath().toString())
                    .withRemotePath(WEBHOOK_CONFIG_DIR)
                    .exec();
            LOG.debugv("Injected IRSA OIDC signing keypair into k3s container {0} for cluster {1}",
                    containerId, clusterName);
        } catch (Exception e) {
            LOG.warnv("EKS IRSA service account tokens may not verify for cluster {0}: could not copy "
                    + "key files into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    void reinjectSigningKeys(String containerId, Cluster cluster) {
        try {
            String accountId = resolveClusterAccountId(cluster);
            String issuer = resolveClusterIssuer(cluster);
            ClusterOidcKey oidcKey = oidcService.ensureKeyForAccount(accountId, cluster.getName(), issuer);
            SigningKeyFiles keyFiles = writeSigningKeyFiles(cluster, oidcKey);
            if (keyFiles != null) {
                copySigningKeysIntoContainer(containerId, keyFiles, cluster.getName());
            }
        } catch (Exception e) {
            LOG.warnv("Could not re-inject IRSA signing keys for surviving EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    /**
     * Generates and injects {@code /etc/rancher/k3s/registries.yaml} into the (created,
     * not-yet-started) k3s container so its containerd can pull images pushed to the Floci ECR
     * registry. Mirrors every repository hostname the emulator can mint: the default account across
     * the full region catalog and the path-style {@code localhost:<port>} form, including
     * {@code localhost.floci.io} aliases when TLS registry URIs are enabled, to Floci's
     * in-network data plane. Public registries are never matched. A failure disables the
     * mirror for this cluster but does not abort its startup, matching the webhook contract.
     */
    void injectEcrRegistryMirror(String containerId, String clusterName) {
        if (!config.services().eks().ecrRegistryMirror() || !config.services().ecr().enabled()) {
            return;
        }
        try {
            ecrRegistryManager.ensureStarted();
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no ECR registry mirror: registry unavailable: {1}",
                    clusterName, e.getMessage());
            return;
        }
        List<String> regions = new ArrayList<>(AwsRegions.advertised(AwsRegions.partitionFor(config.defaultRegion())));
        if (!regions.contains(config.defaultRegion())) {
            regions.add(config.defaultRegion());
        }
        String endpoint = "http://" + dockerHostResolver.resolve() + ":" + config.port();
        boolean tlsUri = config.services().ecr().tlsUri() && config.tls().enabled();
        String content = buildRegistriesYaml(config.defaultAccountId(), regions, config.port(), endpoint, tlsUri);
        writeLocalCopy(Paths.get(config.services().eks().dataPath(), "registries", clusterName,
                "registries.yaml"), content, clusterName);
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tarSingleFile(REGISTRIES_TAR_ENTRY, content)))
                    .withRemotePath("/etc")
                    .exec();
            LOG.infov("Injected ECR registry mirror ({0}) into k3s cluster {1}", endpoint, clusterName);
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no ECR registry mirror: could not copy registries.yaml "
                    + "into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    /**
     * Drops the cluster's {@code MutatingWebhookConfiguration} into the k3s server manifests
     * directory of the (created, not-yet-started) container, so the API server registers it as it
     * comes up and starts sending pod CREATE admission reviews to Floci.
     *
     * <p>Kubernetes requires an {@code https} {@code clientConfig.url} and a {@code caBundle} it
     * trusts, neither of which Floci can offer with TLS off, so the webhook is skipped with a
     * warning in that case. A failure here leaves the cluster running without pod identity
     * injection, matching the token webhook and the ECR mirror.
     */
    void registerPodIdentityWebhook(String containerId, Cluster cluster) {
        if (!config.services().eks().podIdentityWebhook()) {
            return;
        }
        String clusterName = cluster.getName();
        if (!config.tls().enabled()) {
            LOG.warnv("EKS Pod Identity injection is off for cluster {0}: Kubernetes only accepts an "
                    + "https admission webhook URL, and Floci serves HTTP with floci.tls.enabled=false. "
                    + "Set FLOCI_TLS_ENABLED=true to have pods mutated. Pods still start, without the "
                    + "pod identity token or credentials environment variables.", clusterName);
            return;
        }
        if (certificateAuthority == null) {
            LOG.warnv("EKS Pod Identity injection is off for cluster {0}: no local CA is available to "
                    + "put in the webhook caBundle", clusterName);
            return;
        }
        String url = "https://" + dockerHostResolver.resolve() + ":" + config.port()
                + podIdentityWebhookPath(clusterName, resolveClusterAccountId(cluster));
        String manifest = buildPodIdentityWebhookConfiguration(url, certificateAuthority.caPem());
        writeLocalCopy(Paths.get(config.services().eks().dataPath(), "webhook", clusterName,
                POD_IDENTITY_MANIFEST_FILE), manifest, clusterName);
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(
                            tarSingleFile(POD_IDENTITY_MANIFEST_TAR_ENTRY, manifest)))
                    .withRemotePath(K3S_DATA_DIR)
                    .exec();
            LOG.infov("Registered the EKS Pod Identity mutating webhook ({0}) for cluster {1}", url, clusterName);
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no pod identity injection: could not copy {1} into the k3s "
                    + "container: {2}", clusterName, POD_IDENTITY_MANIFEST_FILE, e.getMessage());
        }
    }

    /**
     * The Floci pod identity admission route. The account is in the path for the same reason the
     * token webhook puts its scope there: the API server calls Floci with no AWS credentials, so a
     * cluster owned by a non-default account would otherwise never be found.
     */
    static String podIdentityWebhookPath(String clusterName, String accountId) {
        return "/_floci/eks/clusters/" + clusterName + "/pod-identity-webhook/scope/" + accountId;
    }

    /**
     * Builds the {@code MutatingWebhookConfiguration} k3s auto-applies. Scoped to pod {@code CREATE}
     * alone, and {@code failurePolicy: Ignore} so an unreachable or failing Floci never blocks a pod
     * from being created. The {@code caBundle} is Floci's local CA, base64 of the PEM as Kubernetes
     * expects.
     *
     * <p>{@code timeoutSeconds} is 3, not the Kubernetes default of 10: Floci is a local process, so
     * a healthy call takes milliseconds, and the timeout only ever runs down when Floci is
     * unreachable. Every pod creation in the cluster pays it in that case, so it is kept short.
     */
    static String buildPodIdentityWebhookConfiguration(String url, String caPem) {
        return """
                apiVersion: admissionregistration.k8s.io/v1
                kind: MutatingWebhookConfiguration
                metadata:
                  name: floci-eks-pod-identity
                webhooks:
                  - name: pod-identity.eks.floci.io
                    admissionReviewVersions: ["v1"]
                    sideEffects: None
                    failurePolicy: Ignore
                    reinvocationPolicy: Never
                    timeoutSeconds: 3
                    clientConfig:
                      url: "%s"
                      caBundle: "%s"
                    rules:
                      - operations: ["CREATE"]
                        apiGroups: [""]
                        apiVersions: ["v1"]
                        resources: ["pods"]
                        scope: "*"
                """.formatted(url, Base64.getEncoder().encodeToString(caPem.getBytes(StandardCharsets.UTF_8)));
    }

    /** Best-effort local copy for inspection/debugging; the container copy streams from memory. */
    private void writeLocalCopy(Path file, String content, String clusterName) {
        Path localFile = file.toAbsolutePath().normalize();
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, content);
        } catch (IOException e) {
            LOG.debugv("Could not write local {0} copy for cluster {1}: {2}",
                    localFile.getFileName(), clusterName, e.getMessage());
        }
    }

    private static byte[] tarSingleFile(String entryName, String content) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(data.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build in-memory tar for " + entryName, e);
        }
    }

    /**
     * Builds the k3s registries.yaml content. One mirror entry per hostname-style repository URI
     * ({@code <account>.dkr.ecr.<region>.localhost:<port>}) plus one for the path-style form
     * ({@code localhost:<port>}), all pointing at Floci's in-network data plane. The TLS URI
     * mode adds the corresponding {@code localhost.floci.io} aliases. k3s supports
     * no partial wildcards and a {@code "*"} catch-all would also intercept public registries,
     * so the hostnames are enumerated explicitly.
     */
    static String buildRegistriesYaml(String accountId, List<String> regions, int dataPlanePort, String endpoint) {
        return buildRegistriesYaml(accountId, regions, dataPlanePort, endpoint, false);
    }

    static String buildRegistriesYaml(String accountId, List<String> regions, int dataPlanePort,
                                     String endpoint, boolean tlsUri) {
        StringBuilder yaml = new StringBuilder("mirrors:\n");
        for (String region : regions) {
            appendMirror(yaml, accountId + ".dkr.ecr." + region + ".localhost:" + dataPlanePort, endpoint);
            if (tlsUri) {
                appendMirror(yaml, accountId + ".dkr.ecr." + region + ".localhost.floci.io:" + dataPlanePort, endpoint);
            }
        }
        appendMirror(yaml, "localhost:" + dataPlanePort, endpoint);
        if (tlsUri) {
            appendMirror(yaml, "localhost.floci.io:" + dataPlanePort, endpoint);
        }
        return yaml.toString();
    }

    private static void appendMirror(StringBuilder yaml, String host, String endpoint) {
        yaml.append("  \"").append(host).append("\":\n")
                .append("    endpoint:\n")
                .append("      - \"").append(endpoint).append("\"\n");
    }

    /** The Floci token-webhook URL as reachable from inside the k3s container. */
    String webhookUrl(String clusterName) {
        return "http://" + dockerHostResolver.resolve() + ":" + config.port() + webhookPath(clusterName);
    }

    static String webhookPath(Cluster cluster) {
        // client-go replaces a server URL query when constructing its TokenReview request.
        String accountId = cluster.getAccountId() != null && !cluster.getAccountId().isBlank()
                ? cluster.getAccountId()
                : (cluster.getArn() != null && cluster.getArn().split(":", 6).length > 4 ? cluster.getArn().split(":", 6)[4] : "000000000000");
        String region = "us-east-1";
        if (cluster.getArn() != null) {
            String[] parts = cluster.getArn().split(":", 6);
            if (parts.length > 3 && !parts[3].isBlank()) {
                region = parts[3];
            }
        }
        Instant createdAt = cluster.getCreatedAt() != null ? cluster.getCreatedAt() : Instant.EPOCH;
        return webhookPath(cluster.getName()) + "/scope/" + accountId
                + "/" + region + "/" + createdAt;
    }

    static String webhookPath(String clusterName) {
        return "/_floci/eks/clusters/" + clusterName + "/token-webhook";
    }

    /**
     * Builds a minimal kubeconfig that points the k3s API server's token-authentication webhook
     * at Floci. The webhook server uses anonymous access (no client credentials needed).
     */
    static String buildWebhookKubeconfig(String serverUrl) {
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: floci-token-webhook
                  cluster:
                    server: %s
                users:
                - name: floci-token-webhook
                contexts:
                - name: floci-token-webhook
                  context:
                    cluster: floci-token-webhook
                    user: floci-token-webhook
                current-context: floci-token-webhook
                """.formatted(serverUrl);
    }

    void registerClusterNodeInstance(Cluster cluster, String containerId) {
        try {
            String accountId = resolveClusterAccountId(cluster);
            String region = clusterRegion(cluster);
            ContainerIps containerIps = resolveContainerIps(containerId);
            Instance nodeInstance = synthesizeClusterNodeInstance(cluster, containerIps.primaryIp(), region, accountId);
            nodeInstance.setDockerContainerId(containerId);
            clusterNodeInstances.put(clusterResourceName(cluster), new ClusterNodeRecord(accountId, region, nodeInstance));
            for (Consumer<Instance> listener : nodeRegistrationListeners) {
                try {
                    listener.accept(nodeInstance);
                } catch (Exception e) {
                    LOG.warnv("Node registration listener failed for cluster {0}: {1}",
                            cluster.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not register cluster node instance for EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    void configureLinkLocalMetadataEndpoint(Cluster cluster, String containerId) {
        if (!config.services().eks().imds()) {
            return;
        }
        try {
            ClusterNodeRecord record = clusterNodeInstances.get(clusterResourceName(cluster));
            Instance nodeInstance = record != null ? record.instance() : null;
            ContainerIps containerIps = resolveContainerIps(containerId);
            if (nodeInstance == null) {
                String accountId = resolveClusterAccountId(cluster);
                String region = clusterRegion(cluster);
                nodeInstance = synthesizeClusterNodeInstance(cluster, containerIps.primaryIp(), region, accountId);
                nodeInstance.setDockerContainerId(containerId);
                clusterNodeInstances.put(clusterResourceName(cluster), new ClusterNodeRecord(accountId, region, nodeInstance));
            } else if (nodeInstance.getDockerContainerId() == null) {
                nodeInstance.setDockerContainerId(containerId);
            }

            if (metadataServer != null) {
                metadataServer.reconcileContainerAddresses(containerIps.allIps(), nodeInstance);
            }

            ContainerExecResult install = execInContainerForResult(containerId,
                    Ec2MetadataProxy.installCommand(), 180);
            if (install.exitCode() != 0) {
                LOG.warnv("Could not install IMDS proxy dependencies for EKS cluster {0}: {1}",
                        cluster.getName(), install.summary());
                return;
            }

            String flociHost = dockerHostResolver.resolve();
            int imdsPort = config.services().ec2().imdsPort();

            ContainerExecResult start = execInContainerForResult(containerId,
                    Ec2MetadataProxy.startCommand(flociHost, imdsPort), 30);
            if (start.exitCode() != 0) {
                LOG.warnv("Could not start link-local IMDS proxy for EKS cluster {0}: {1}",
                        cluster.getName(), start.summary());
                return;
            }

            if (config.services().eks().imdsPodNetwork()) {
                configurePodNetworkRouting(cluster, containerId);
            }

            LOG.infov("Configured link-local IMDS endpoint for EKS cluster {0}", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not configure link-local IMDS endpoint for EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    void configurePodIdentityRelay(Cluster cluster, String containerId) {
        if (!config.services().eks().podIdentityWebhook() || !config.tls().enabled()) {
            return;
        }
        try {
            ContainerExecResult install = execInContainerForResult(containerId,
                    Ec2MetadataProxy.installCommand(), 180);
            if (install.exitCode() != 0) {
                LOG.warnv("Could not install Pod Identity relay dependencies for EKS cluster {0}: {1}",
                        cluster.getName(), install.summary());
                return;
            }

            String flociHost = dockerHostResolver.resolve();
            int flociPort = config.port();

            ContainerExecResult start = execInContainerForResult(containerId,
                    Ec2MetadataProxy.podIdentityStartCommand(flociHost, flociPort), 30);
            if (start.exitCode() != 0) {
                LOG.warnv("Could not start link-local Pod Identity relay for EKS cluster {0}: {1}",
                        cluster.getName(), start.summary());
                return;
            }

            configurePodNetworkRouting(cluster, containerId, List.of(EksPodNetworkRouting.POD_IDENTITY_ENDPOINT));

            LOG.infov("Configured link-local Pod Identity relay for EKS cluster {0}", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not configure link-local Pod Identity relay for EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    void configurePodNetworkRouting(Cluster cluster, String containerId) {
        configurePodNetworkRouting(cluster, containerId, EksPodNetworkRouting.DEFAULT_ENDPOINTS);
    }

    void configurePodNetworkRouting(Cluster cluster, String containerId, List<LinkLocalEndpoint> endpoints) {
        try {
            String[] routingCmd = EksPodNetworkRouting.buildRoutingCommand(
                    EksPodNetworkRouting.DEFAULT_POD_CIDR,
                    endpoints);
            ContainerExecResult routing = execInContainerForResult(containerId, routingCmd, 15);
            if (routing.exitCode() != 0) {
                LOG.warnv("Could not configure link-local pod network routing for EKS cluster {0}: {1}",
                        cluster.getName(), routing.summary());
            }
        } catch (Exception e) {
            LOG.warnv("Could not configure link-local pod network routing for EKS cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    void unregisterMetadataEndpoint(Cluster cluster) {
        ClusterNodeRecord record = clusterNodeInstances.remove(clusterResourceName(cluster));
        Instance nodeInstance = record != null ? record.instance() : null;
        if (metadataServer != null && nodeInstance != null) {
            metadataServer.unregisterInstance(nodeInstance);
        }
    }

    String clusterRegion(Cluster cluster) {
        if (cluster != null && cluster.getArn() != null) {
            String[] parts = cluster.getArn().split(":");
            if (parts.length > 3 && !parts[3].isBlank()) {
                return parts[3];
            }
        }
        if (regionResolver != null && regionResolver.getDefaultRegion() != null && !regionResolver.getDefaultRegion().isBlank()) {
            return regionResolver.getDefaultRegion();
        }
        if (config != null && config.defaultRegion() != null && !config.defaultRegion().isBlank()) {
            return config.defaultRegion();
        }
        return "us-east-1";
    }

    String deriveClusterNodeAvailabilityZone(Cluster cluster, String region) {
        String safeRegion = (region != null && !region.isBlank()) ? region : clusterRegion(cluster);
        return safeRegion + "a";
    }

    String deriveClusterNodeAvailabilityZone(Cluster cluster) {
        return deriveClusterNodeAvailabilityZone(cluster, clusterRegion(cluster));
    }

    String deriveClusterNodeInstanceId(Cluster cluster, String region, String accountId) {
        String safeClusterName = (cluster != null && cluster.getName() != null && !cluster.getName().isBlank())
                ? cluster.getName()
                : "eks-cluster";
        String safeAccountId = (accountId != null && !accountId.isBlank())
                ? accountId
                : resolveClusterAccountId(cluster);
        String safeRegion = (region != null && !region.isBlank())
                ? region
                : clusterRegion(cluster);

        String seed = safeClusterName + "-" + safeAccountId + "-" + safeRegion;
        String hex = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return "i-" + (hex.length() >= 17 ? hex.substring(0, 17) : (hex + "00000000000000000").substring(0, 17));
    }

    String deriveClusterNodeProviderId(Cluster cluster, String region, String accountId) {
        String az = deriveClusterNodeAvailabilityZone(cluster, region);
        String instanceId = deriveClusterNodeInstanceId(cluster, region, accountId);
        return "aws:///" + az + "/" + instanceId;
    }

    String deriveClusterNodeProviderId(Cluster cluster) {
        String accountId = resolveClusterAccountId(cluster);
        String region = clusterRegion(cluster);
        return deriveClusterNodeProviderId(cluster, region, accountId);
    }

    Instance synthesizeClusterNodeInstance(Cluster cluster, String containerIp, String region, String accountId) {
        Instance inst = new Instance();
        String safeClusterName = (cluster != null && cluster.getName() != null && !cluster.getName().isBlank())
                ? cluster.getName()
                : "eks-cluster";
        String safeAccountId = (accountId != null && !accountId.isBlank())
                ? accountId
                : resolveClusterAccountId(cluster);
        String safeRegion = (region != null && !region.isBlank())
                ? region
                : clusterRegion(cluster);

        String instanceId = deriveClusterNodeInstanceId(cluster, safeRegion, safeAccountId);
        String az = deriveClusterNodeAvailabilityZone(cluster, safeRegion);

        inst.setInstanceId(instanceId);
        inst.setImageId("ami-eks-k3s");
        inst.setInstanceType("m5.large");
        inst.setPlacement(new Placement(az));
        inst.setRegion(safeRegion);
        inst.setState(InstanceState.running());

        String ip = (containerIp != null && !containerIp.isBlank()) ? containerIp : "10.0.0.1";
        inst.setPrivateIpAddress(ip);
        inst.setPrivateDnsName("ip-" + ip.replace('.', '-') + "." + safeRegion + ".compute.internal");

        // AWS EKS nodes receive credentials from a node IAM role through an EC2 instance profile,
        // never from the cluster control-plane role (cluster.getRoleArn()). Synthesize a distinct
        // node instance profile identity so /latest/meta-data/iam/info returns a valid profile ARN.
        String nodeProfileName = safeClusterName + "-node-profile";
        inst.setIamInstanceProfileArn(regionResolver.buildGlobalArn("iam", safeAccountId, "instance-profile/" + nodeProfileName));

        if (cluster.getResourcesVpcConfig() != null) {
            inst.setVpcId(cluster.getResourcesVpcConfig().getVpcId());
            if (cluster.getResourcesVpcConfig().getSubnetIds() != null && !cluster.getResourcesVpcConfig().getSubnetIds().isEmpty()) {
                inst.setSubnetId(cluster.getResourcesVpcConfig().getSubnetIds().getFirst());
            }
        }
        inst.setLaunchTime(cluster.getCreatedAt() != null ? cluster.getCreatedAt() : Instant.now());
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag("Name", safeClusterName + "-node"));
        tags.add(new Tag("kubernetes.io/cluster/" + safeClusterName, "owned"));
        tags.add(new Tag("eks:cluster-name", safeClusterName));
        inst.setTags(tags);
        return inst;
    }

    @Override
    public Optional<Instance> findInstance(String accountId, String region, String instanceId) {
        if (accountId == null || accountId.isBlank() || instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        return clusterNodeInstances.values().stream()
                .filter(rec -> accountId.equals(rec.accountId()))
                .filter(rec -> region == null || region.equals(rec.region()))
                .map(ClusterNodeRecord::instance)
                .filter(i -> instanceId.equals(i.getInstanceId()))
                .findFirst();
    }

    @Override
    public List<Instance> listInstances(String accountId, String region) {
        if (accountId == null || accountId.isBlank()) {
            return List.of();
        }
        return clusterNodeInstances.values().stream()
                .filter(rec -> accountId.equals(rec.accountId()))
                .filter(rec -> region == null || region.equals(rec.region()))
                .map(ClusterNodeRecord::instance)
                .toList();
    }

    record ContainerIps(String primaryIp, Set<String> allIps) {}

    ContainerIps resolveContainerIps(String containerId) {
        Set<String> ips = new LinkedHashSet<>();
        String preferred = null;
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() != null) {
                Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
                if (networks != null) {
                    preferred = Ec2MetadataProxy.preferredMetadataSourceIp(networks).orElse(null);
                    for (ContainerNetwork network : networks.values()) {
                        if (network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank()) {
                            ips.add(network.getIpAddress());
                        }
                    }
                }
                String ip = inspect.getNetworkSettings().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    ips.add(ip);
                    if (preferred == null) {
                        preferred = ip;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not inspect container {0} for IPs: {1}", containerId, e.getMessage());
        }
        return new ContainerIps(preferred != null ? preferred : "10.0.0.1", ips);
    }

    Instance getRegisteredClusterNodeInstance(Cluster cluster) {
        ClusterNodeRecord record = clusterNodeInstances.get(clusterResourceName(cluster));
        return record != null ? record.instance() : null;
    }

    ContainerExecResult execInContainerForResult(String containerId, String[] cmd, int timeoutSeconds) throws Exception {
        DockerClient dockerClient = lifecycleManager.getDockerClient();
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
                        if (frame != null && frame.getPayload() != null) {
                            output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }
                })
                .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

        if (!completed) {
            return new ContainerExecResult(-1, "Timed out after " + timeoutSeconds + "s");
        }
        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return new ContainerExecResult(exitCode != null ? exitCode : -1, output.toString());
    }

    record ContainerExecResult(long exitCode, String output) {
        String summary() {
            return output == null || output.isBlank() ? "(no output)" : output.trim();
        }
    }

    /**
     * Executes launch template UserData script(s) inside the running cluster container.
     *
     * @param cluster the cluster whose container will execute the user data
     * @param nodegroupName the name of the nodegroup requesting execution
     * @param userData raw UserData payload from the launch template
     * @return result indicating success, failure, or skipped
     */
    public UserDataPipeline.ExecutionResult executeUserData(
            Cluster cluster,
            String nodegroupName,
            String userData) {
        if (cluster == null || cluster.getContainerId() == null || cluster.getContainerId().isBlank()) {
            return UserDataPipeline.ExecutionResult.skipped("Cluster has no running container");
        }
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        if (dockerClient == null) {
            return UserDataPipeline.ExecutionResult.skipped("No Docker daemon reachable");
        }
        String context = "EKS cluster " + cluster.getName() + " (nodegroup " + nodegroupName + ")";
        return UserDataPipeline.executeUserData(
                dockerClient,
                cluster.getContainerId(),
                context,
                userData,
                Duration.ofMinutes(30),
                null,
                null
        );
    }


    private String execInContainer(String containerId, String[] cmd) throws Exception {
        ContainerExecResult result = execInContainerForResult(containerId, cmd, 10);
        if (result.exitCode() == -1 && result.output().startsWith("Timed out")) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        return result.output();
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
