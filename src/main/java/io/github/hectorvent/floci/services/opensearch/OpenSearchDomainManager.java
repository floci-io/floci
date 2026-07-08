package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;

/**
 * Manages the Docker lifecycle of OpenSearch containers for real-mode domains.
 * Not used when {@code floci.services.opensearch.mock=true}.
 */
@ApplicationScoped
public class OpenSearchDomainManager {

    private static final Logger LOG = Logger.getLogger(OpenSearchDomainManager.class);
    private static final int OPENSEARCH_PORT = 9200;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

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

    public void startDomain(Domain domain) {
        String image = resolveImage(domain.getEngineVersion());
        String containerName = containerName(domain);

        LOG.infov("Starting OpenSearch container for domain: {0} (version={1}, image={2})",
                domain.getDomainName(), domain.getEngineVersion(), image);

        int hostPort = portAllocator.allocate(
                config.services().opensearch().proxyBasePort(),
                config.services().opensearch().proxyMaxPort());

        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("discovery.type", "single-node")
                .withPortBinding(OPENSEARCH_PORT, hostPort)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation();

        applyEngineEnv(specBuilder, domain.getEngineVersion());

        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager, config,
                    "opensearch", domain.getVolumeId(), domain.getDomainName(),
                    "/usr/share/opensearch/data");
        } else {
            // Legacy host-path mode: host-persistent-path is an absolute path
            Path dataPath = ContainerStorageHelper.hostResourcePath(config, "opensearch", domain.getDomainName());
            if (!containerDetector.isRunningInContainer()) {
                ContainerStorageHelper.ensureHostDir(dataPath.toString());
            }
            String dataPathStr = dataPath.toAbsolutePath().normalize().toString();
            String persistentPathStr = Path.of(config.storage().persistentPath()).toAbsolutePath().normalize().toString();
            String hostDataPath = dataPathStr.replace(persistentPathStr, config.storage().hostPersistentPath());
            specBuilder.withBind(hostDataPath, "/usr/share/opensearch/data");
        }

        ContainerSpec spec = specBuilder.build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        domain.setContainerId(info.containerId());

        if (containerDetector.isRunningInContainer()) {
            domain.setEndpoint("http://" + containerName + ":" + OPENSEARCH_PORT);
        } else {
            domain.setEndpoint("http://localhost:" + hostPort);
        }

        LOG.infov("OpenSearch container {0} started for domain {1} on port {2}",
                info.containerId(), domain.getDomainName(), String.valueOf(hostPort));
    }

    public boolean isReady(Domain domain) {
        String containerName = containerName(domain);
        String url = "http://" + containerName + ":" + OPENSEARCH_PORT + "/_cluster/health";
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
        if (config.services().opensearch().keepRunningOnShutdown()) {
            LOG.infov("Leaving OpenSearch container for domain {0} running", domain.getDomainName());
            return;
        }
        lifecycleManager.stopAndRemove(domain.getContainerId(), null);
        LOG.infov("Stopped OpenSearch container for domain {0}", domain.getDomainName());
    }

    public void removeDomainStorage(Domain domain) {
        ContainerStorageHelper.removeStorage(config, lifecycleManager,
                "opensearch", domain.getVolumeId(), domain.getDomainName());
    }

    private String resolveImage(String engineVersion) {
        return OpenSearchVersions.resolveImage(
                config.services().opensearch().defaultImage(), engineVersion);
    }

    private String containerName(Domain domain) {
        return ContainerStorageHelper.resourceName(config, "opensearch", null, domain.getDomainName());
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
