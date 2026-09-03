package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    private static final String WEBHOOK_CONFIG_DIR = "/etc";
    private static final String WEBHOOK_CONFIG_FILE = "token-webhook.yaml";
    private static final String WEBHOOK_CONFIG_PATH = WEBHOOK_CONFIG_DIR + "/" + WEBHOOK_CONFIG_FILE;
    // Tar entry extracted at /etc; the archive path creates /etc/rancher/k3s, which does not
    // exist yet in a created-but-not-started k3s container.
    private static final String REGISTRIES_TAR_ENTRY = "rancher/k3s/registries.yaml";
    private static final String ENDPOINT_MODE_NETWORK = "network";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final EcrRegistryManager ecrRegistryManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    @Inject
    public EksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EcrRegistryManager ecrRegistryManager,
                             EmulatorConfig config,
                             RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.ecrRegistryManager = ecrRegistryManager;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /**
     * Starts a k3s container for the given cluster. Updates the cluster with
     * the container ID and host port. The cluster status remains CREATING until
     * {@link #isReady(Cluster)} returns true and {@link #finalizeCluster(Cluster)} is called.
     */
    public void startCluster(Cluster cluster) {
        String image = config.services().eks().defaultImage();
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
        lifecycleManager.removeIfExists(containerName);

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

        List<String> serverArgs = buildServerArgs(config.services().eks().disableCni());

        // The account label comes from the cluster record when set (restore runs with no request
        // context); regionResolver is the fallback for the create path.
        String labelAccountId = cluster.getAccountId() != null
                ? cluster.getAccountId()
                : regionResolver.getAccountId();
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("K3S_KUBECONFIG_MODE", "644")
                .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                .withDockerNetwork(config.services().eks().dockerNetwork())
                .withPrivileged(true)
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "eks", cluster.getName(), labelAccountId, regionResolver.getDefaultRegion()));

        // Wire a token-authentication webhook so `aws eks get-token` bearer tokens are validated by
        // Floci and mapped to cluster-admin. The k3s API server POSTs a TokenReview to Floci's
        // _floci/eks/clusters/<cluster-name>/token-webhook endpoint. The kubeconfig is copied into
        // the container via the Docker API after create and before start (below), not bind-mounted,
        // so it works the same natively and in Docker-in-Docker, with no host-path /
        // host-persistent-path requirement.
        String webhookLocalFile = null;
        if (config.services().eks().iamAuthWebhook()) {
            webhookLocalFile = writeWebhookKubeconfig(cluster.getName());
            if (webhookLocalFile != null) {
                specBuilder.withHostDockerInternalOnLinux();
                serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-config-file="
                        + WEBHOOK_CONFIG_PATH);
                serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-version=v1");
                serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s");
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
        injectEcrRegistryMirror(containerId, cluster.getName());
        ContainerInfo info = lifecycleManager.startCreated(containerId, spec);

        applyEndpoints(cluster, containerName, hostPort, info);

        LOG.infov("k3s container {0} started for cluster {1} on port {2} (internal: {3})",
                containerId, cluster.getName(), String.valueOf(hostPort), cluster.getInternalEndpoint());
    }

    /**
     * Re-latches a persisted cluster onto its k3s container after a Floci restart. A surviving
     * container — running, or stopped by a Docker daemon reboot — is adopted (started if needed),
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
        if (cluster.getContainerId() == null) {
            return;
        }
        if (config.services().eks().keepRunningOnShutdown()) {
            LOG.infov("Leaving k3s container for cluster {0} running", cluster.getName());
            return;
        }
        lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        ContainerStorageHelper.removeNamedVolume(config, lifecycleManager, clusterResourceName(cluster));
        LOG.infov("Stopped k3s container for cluster {0}", cluster.getName());
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
        String legacy = ContainerStorageHelper.resourceName(config, "eks", null, cluster.getName());
        if (legacy.equals(qualified)) {
            return legacy; // default account: the names never diverged
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
     * Builds the k3s {@code server} command-line args. When {@code disableCni} is true, flannel,
     * k3s's default network policy controller, and kube-proxy are all disabled up front — see the
     * {@code disableCni} config javadoc for why this must happen at startup, not after the fact.
     */
    static List<String> buildServerArgs(boolean disableCni) {
        List<String> serverArgs = new ArrayList<>(List.of("server",
                "--disable=traefik",
                "--tls-san=localhost"));
        if (disableCni) {
            serverArgs.add("--flannel-backend=none");
            serverArgs.add("--disable-network-policy");
            serverArgs.add("--disable-kube-proxy");
        }
        return serverArgs;
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
    private String writeWebhookKubeconfig(String clusterName) {
        Path localFile = Paths.get(config.services().eks().dataPath(), "webhook", clusterName, WEBHOOK_CONFIG_FILE)
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, buildWebhookKubeconfig(webhookUrl(clusterName)));
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
     * Generates and injects {@code /etc/rancher/k3s/registries.yaml} into the (created,
     * not-yet-started) k3s container so its containerd can pull images pushed to the Floci ECR
     * registry. Mirrors every repository hostname the emulator can mint — default account across
     * the full region catalog, plus the path-style {@code localhost:<port>} form — to the registry
     * container's in-network endpoint. Public registries are never matched. A failure disables the
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
        List<String> regions = new ArrayList<>(AwsRegions.ALL);
        if (!regions.contains(config.defaultRegion())) {
            regions.add(config.defaultRegion());
        }
        String content = buildRegistriesYaml(config.defaultAccountId(), regions,
                ecrRegistryManager.effectivePort(), ecrRegistryManager.internalEndpoint());
        writeRegistriesYaml(clusterName, content);
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tarSingleFile(REGISTRIES_TAR_ENTRY, content)))
                    .withRemotePath("/etc")
                    .exec();
            LOG.infov("Injected ECR registry mirror ({0}) into k3s cluster {1}",
                    ecrRegistryManager.internalEndpoint(), clusterName);
        } catch (Exception e) {
            LOG.warnv("EKS cluster {0} gets no ECR registry mirror: could not copy registries.yaml "
                    + "into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    /** Best-effort local copy for inspection/debugging; the container copy streams from memory. */
    private void writeRegistriesYaml(String clusterName, String content) {
        Path localFile = Paths.get(config.services().eks().dataPath(), "registries", clusterName, "registries.yaml")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, content);
        } catch (IOException e) {
            LOG.debugv("Could not write local registries.yaml copy for cluster {0}: {1}",
                    clusterName, e.getMessage());
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
     * ({@code localhost:<port>}), all pointing at the registry's in-network endpoint. k3s supports
     * no partial wildcards and a {@code "*"} catch-all would also intercept public registries,
     * so the hostnames are enumerated explicitly.
     */
    static String buildRegistriesYaml(String accountId, List<String> regions, int hostPort, String endpoint) {
        StringBuilder yaml = new StringBuilder("mirrors:\n");
        for (String region : regions) {
            appendMirror(yaml, accountId + ".dkr.ecr." + region + ".localhost:" + hostPort, endpoint);
        }
        appendMirror(yaml, "localhost:" + hostPort, endpoint);
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
