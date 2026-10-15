package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.VolumeOwnership;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Manages the Docker lifecycle of OpenSearch containers for real-mode domains.
 * Not used when {@code floci.services.opensearch.mock=true}.
 */
@ApplicationScoped
public class OpenSearchDomainManager {

    private static final Logger LOG = Logger.getLogger(OpenSearchDomainManager.class);
    private static final int OPENSEARCH_PORT = 9200;
    private static final String CONTAINER_DATA_PATH = "/usr/share/opensearch/data";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private volatile boolean dockerUnavailableLogged;

    @Inject
    public OpenSearchDomainManager(ContainerBuilder containerBuilder,
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
     * Attempts {@link #startDomain} and reports the backend as unavailable instead of propagating
     * the failure, when the cause is that no Docker daemon is reachable from Floci: Floci running
     * inside Docker without a mounted socket, or a stopped daemon on the host. A failure raised
     * while the daemon <em>is</em> reachable is a genuine container problem and still propagates,
     * so nothing changes for a Floci that can start OpenSearch containers.
     *
     * @return {@code true} when the container started, {@code false} when no Docker daemon is
     *         reachable
     */
    public boolean tryStartDomain(Domain domain) {
        try {
            startDomain(domain);
            dockerUnavailableLogged = false;
            return true;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). OpenSearch metadata "
                        + "operations keep working and domains still report Processing=false, but "
                        + "they have no backing search container until a daemon becomes reachable.",
                        e.getMessage());
            }
            return false;
        }
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * container that failed for its own reasons.
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

    public void startDomain(Domain domain) {
        String image = resolveImage(domain.getEngineVersion());
        AwsArnUtils.Arn arn = AwsArnUtils.parse(domain.getArn());
        String containerName = ContainerStorageHelper.resourceName(config, "opensearch", null,
                arn.accountId() + "-" + arn.region() + "-" + domain.getDomainName());

        LOG.infov("Starting OpenSearch container for domain: {0} (version={1}, image={2})",
                domain.getDomainName(), domain.getEngineVersion(), image);

        // Older container names omit account and Region, so only the persisted ID proves ownership.
        if (domain.getContainerId() != null && !domain.getContainerId().isBlank()) {
            lifecycleManager.removeIfExists(domain.getContainerId());
        }
        ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, containerName);

        // A restart of an existing domain has just removed the old container, so its
        // reservation is stale; hand the port back before allocating a fresh one.
        if (domain.getHostPort() != null) {
            portAllocator.release(domain.getHostPort());
            domain.setHostPort(null);
        }

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("discovery.type", "single-node")
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                // The OpenSearch image's entrypoint is a bash script that runs the server as its
                // child rather than exec'ing it. As PID 1 that script ignores SIGTERM, so every
                // DeleteDomain, and every shutdown for each domain, waited out the whole stop timeout.
                .withInit()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "opensearch", domain.getDomainName(), arn.accountId(), arn.region()));

        // Container-name DNS only resolves on a user-defined Docker network, and
        // nothing guarantees the spawned OpenSearch container and floci itself land
        // on one together (see withDockerNetwork's own fallback chain) — a default
        // `docker run` leaves both on the anonymous bridge, which routes IPs
        // between containers but does no by-name resolution. The other
        // container-backed services (Neptune, MemoryDB, RDS) already address their
        // backends via the container's resolved IP (EndpointInfo) rather than its
        // Docker name; this container follows the same pattern below.
        //
        // Unlike those services, OpenSearch has no floci-internal proxy fronting the
        // backend, so the Docker host-port binding is the ONLY way a client outside
        // the Docker network (e.g. on the host, with floci itself containerized)
        // can reach the domain. Publish it in both topologies: dropping it for the
        // in-container case cut off host clients entirely (#2746 follow-up).
        int hostPort = portAllocator.allocate(
                config.services().opensearch().proxyBasePort(),
                config.services().opensearch().proxyMaxPort());
        specBuilder.withPortBinding(OPENSEARCH_PORT, hostPort);

        ContainerInfo info;
        try {
            applyEngineEnv(specBuilder, domain.getEngineVersion());

            Optional<String> configuredDataVolume = config.services().opensearch().dataVolume()
                    .filter(dataVolume -> !dataVolume.isBlank());
            if (configuredDataVolume.isPresent()) {
                applyConfiguredDataVolume(specBuilder, domain, configuredDataVolume.get(), image);
            } else if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                domain.setVolumeOwnership(VolumeOwnership.FLOCI_MANAGED);
                ContainerStorageHelper.applyNamedVolume(specBuilder, lifecycleManager,
                        resolveVolumeName(domain), CONTAINER_DATA_PATH);
            } else {
                // Legacy host-path mode: host-persistent-path is an absolute path
                Path dataPath = ContainerStorageHelper.hostResourcePath(config, "opensearch", domain.getDomainName());
                if (!containerDetector.isRunningInContainer()) {
                    ContainerStorageHelper.ensureHostDir(dataPath.toString());
                }
                String dataPathStr = dataPath.toAbsolutePath().normalize().toString();
                String persistentPathStr = Path.of(config.storage().persistentPath()).toAbsolutePath().normalize().toString();
                String hostDataPath = dataPathStr.replace(persistentPathStr, config.storage().hostPersistentPath());
                specBuilder.withBind(hostDataPath, CONTAINER_DATA_PATH);
            }

            ContainerSpec spec = specBuilder.build();

            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            portAllocator.release(hostPort);
            throw e;
        }
        domain.setContainerId(info.containerId());
        domain.setHostPort(hostPort);

        EndpointInfo endpoint = info.getEndpoint(OPENSEARCH_PORT);
        domain.setEndpoint("http://" + endpoint.host() + ":" + endpoint.port());

        LOG.infov("OpenSearch container {0} started for domain {1} at {2} (host port {3})",
                info.containerId(), domain.getDomainName(), endpoint, String.valueOf(hostPort));
    }

    public boolean isReady(Domain domain) {
        String endpoint = domain.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        String url = endpoint + "/_cluster/health";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = new String(conn.getInputStream().readAllBytes());
                boolean ready = body.contains("\"green\"") || body.contains("\"yellow\"");
                if (ready) {
                    LOG.infov("OpenSearch domain {0} is ready (internal check)", domain.getDomainName());
                }
                return ready;
            }
            return false;
        } catch (Exception e) {
            // Silently ignore during polling
            return false;
        }
    }

    public void stopDomain(Domain domain) {
        if (domain.getContainerId() == null) {
            return;
        }
        lifecycleManager.stopAndRemove(domain.getContainerId(), null);
        // The container no longer holds the binding, so the reservation must go with
        // it. Repeated create/delete would otherwise exhaust the configured range.
        if (domain.getHostPort() != null) {
            portAllocator.release(domain.getHostPort());
            domain.setHostPort(null);
        }
        LOG.infov("Stopped OpenSearch container for domain {0}", domain.getDomainName());
    }

    /**
     * Removes the domain's Docker volume on delete, honouring the configured prune policy.
     * The decision rides on the {@link VolumeOwnership} marker persisted when the storage
     * was selected, not on names recomputed from the configuration held at delete time: a
     * recomputed name stops matching the persisted one as soon as the resource namespace
     * changes, and a name composed from {@code floci.services.opensearch.data-volume} can
     * be spelled exactly like one Floci derives. Records predating the marker also predate
     * configured data volumes, so their volumes are always Floci-managed.
     */
    public void removeDomainStorage(Domain domain) {
        VolumeOwnership ownership = domain.getVolumeOwnership();
        if (ownership == VolumeOwnership.OPERATOR_BIND) {
            LOG.infov("Retained the bind-mounted data directory of domain {0}. It was named by "
                    + "floci.services.opensearch.data-volume, which Floci never removes.",
                    domain.getDomainName());
            return;
        }
        String volumeName = resolveVolumeName(domain);
        if (ownership == VolumeOwnership.OPERATOR_NAMED) {
            LOG.infov("Retained Docker volume {0}. It was named by "
                    + "floci.services.opensearch.data-volume, which Floci never removes. "
                    + "Remove manually: docker volume rm {0}", volumeName);
            return;
        }
        ContainerStorageHelper.removeNamedVolume(config, lifecycleManager, volumeName);
    }

    /**
     * The domain's Docker volume name, backfilled once for records written before the field
     * existed: those predate the {@code floci-aws-} migration, so their data is in the
     * legacy-named volume and must keep resolving there. Never use the live helper here, which
     * would strand that data under a freshly created volume.
     */
    private String resolveVolumeName(Domain domain) {
        if (domain.getDockerVolumeName() == null || domain.getDockerVolumeName().isBlank()) {
            domain.setDockerVolumeName(ContainerStorageHelper.legacyResourceName(
                    config, "opensearch", domain.getVolumeId(), domain.getDomainName()));
        }
        return domain.getDockerVolumeName();
    }

    private String resolveImage(String engineVersion) {
        return OpenSearchVersions.resolveImage(
                config.services().opensearch().defaultImage(), engineVersion);
    }

    /**
     * Mounts the operator-configured {@code floci.services.opensearch.data-volume}. An
     * absolute path is bind-mounted as {@code <path>/<account-id>/<region>/<domain-name>}, nested under a
     * {@code <namespace>} subdirectory when a resource namespace is configured; any
     * other value is a Docker volume name and the domain gets its own
     * {@code <name>-<namespace>-<account-id>-<region>-<domain-name>} volume of it, without the namespace
     * segment when none is configured. Per-domain, per-deployment naming is deliberate:
     * two OpenSearch nodes sharing one data directory corrupt each other's cluster
     * state, and two namespaced deployments on one Docker daemon must not converge on
     * one volume through the same configured base. The domain record is stamped with
     * the volume name and the operator ownership marker so
     * {@link #removeDomainStorage} can tell operator-named storage, which it never
     * deletes, from the kind Floci derives. The bind branch clears any managed name
     * stamped at creation: a stale one would read as a volume the domain never mounted.
     * A volume created when absent carries no Floci label, so no sweep of
     * Floci-labelled volumes, a floci-scoped prune or a testcontainers run's
     * labelled-children reap, collects operator storage.
     */
    private void applyConfiguredDataVolume(ContainerBuilder.Builder specBuilder, Domain domain,
                                           String dataVolume, String image) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(domain.getArn());
        if (dataVolume.startsWith("/") || Path.of(dataVolume).isAbsolute()) {
            domain.setVolumeOwnership(VolumeOwnership.OPERATOR_BIND);
            domain.setDockerVolumeName(null);
            String namespace = ContainerStorageHelper.resourceNamespace(config);
            String hostPath;
            if (dataVolume.startsWith("/") && File.separatorChar != '/') {
                // A Linux daemon path must not be resolved against the Windows client's drive.
                String base = dataVolume.endsWith("/") ? dataVolume : dataVolume + "/";
                String prefix = namespace.isBlank() ? base : base + namespace + "/";
                hostPath = prefix + arn.accountId() + "/" + arn.region() + "/" + domain.getDomainName();
            } else {
                Path domainDataPath = namespace.isBlank()
                        ? Path.of(dataVolume, arn.accountId(), arn.region(), domain.getDomainName())
                        : Path.of(dataVolume, namespace, arn.accountId(), arn.region(), domain.getDomainName());
                if (!containerDetector.isRunningInContainer()) {
                    ContainerStorageHelper.ensureHostDir(domainDataPath.toString());
                }
                hostPath = domainDataPath.toAbsolutePath().normalize().toString();
            }
            lifecycleManager.ensureBindDirectory(hostPath, image, CONTAINER_DATA_PATH);
            specBuilder.withBind(hostPath, CONTAINER_DATA_PATH);
            return;
        }
        String volumeName = ContainerStorageHelper.prefixedDockerName(
                config, dataVolume, arn.accountId() + "-" + arn.region() + "-" + domain.getDomainName());
        domain.setDockerVolumeName(volumeName);
        domain.setVolumeOwnership(VolumeOwnership.OPERATOR_NAMED);
        ContainerStorageHelper.applyOperatorNamedVolume(specBuilder, lifecycleManager, volumeName, CONTAINER_DATA_PATH);
    }

    /**
     * Engine env that differs between OpenSearch lines and Elasticsearch. Both
     * the security-plugin disable flag and the v2.12+ initial admin password
     * are baked here rather than the call site so the {@link #startDomain}
     * builder chain stays linear.
     */
    private void applyEngineEnv(ContainerBuilder.Builder specBuilder, String engineVersion) {
        if (engineVersion != null && engineVersion.startsWith("Elasticsearch")) {
            // The OSS distribution of Elasticsearch ships without x-pack, so
            // any xpack.* setting is rejected as unknown and the node refuses
            // to boot. The default OSS build has no security plugin to disable
            // — leave the env empty and let the image use its bare defaults.
            return;
        }
        specBuilder.withEnv("DISABLE_SECURITY_PLUGIN", "true");
        // OpenSearch 2.12+ refuses to start without an initial admin password
        // even when the security plugin is disabled (the bootstrap check fires
        // before plugin config). Provide a fixed value — the security plugin
        // is off so this isn't a real credential.
        if (requiresInitialAdminPassword(engineVersion)) {
            specBuilder.withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "FlociAdmin1!");
        }
    }

    private boolean requiresInitialAdminPassword(String engineVersion) {
        if (engineVersion == null || !engineVersion.startsWith("OpenSearch_")) {
            return false;
        }
        String numeric = engineVersion.substring("OpenSearch_".length());
        int dot = numeric.indexOf('.');
        if (dot < 0) {
            return false;
        }
        try {
            int major = Integer.parseInt(numeric.substring(0, dot));
            int minor = Integer.parseInt(numeric.substring(dot + 1));
            return major > 2 || (major == 2 && minor >= 12);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
