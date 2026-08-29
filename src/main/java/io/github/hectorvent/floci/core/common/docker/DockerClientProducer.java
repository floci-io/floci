package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * CDI producer for the DockerClient singleton bean.
 */
@ApplicationScoped
public class DockerClientProducer {

    private static final Logger LOG = Logger.getLogger(DockerClientProducer.class);

    /**
     * Docker Desktop's well-known named pipe on native Windows. Unlike the unix-socket default,
     * this isn't reachable by simply bind-mounting a path — Windows has no equivalent of
     * {@code /var/run/docker.sock}, so a platform-specific fallback is required.
     */
    private static final String WINDOWS_DEFAULT_DOCKER_HOST = "npipe:////./pipe/docker_engine";

    private final EmulatorConfig config;

    @Inject
    public DockerClientProducer(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Normalizes a Docker host value by prepending {@code tcp://} when no recognized
     * URI scheme ({@code tcp://}, {@code unix://}, {@code npipe://}) is present.
     *
     * @param dockerHost the raw Docker host configuration value
     * @return the normalized Docker host value, or the original value if it already has a scheme
     */
    static String normalizeDockerHost(String dockerHost) {
        if (dockerHost == null) {
            return null;
        }
        if (dockerHost.isEmpty()) {
            return dockerHost;
        }
        String lower = dockerHost.toLowerCase();
        if (lower.startsWith("tcp://") || lower.startsWith("unix://") || lower.startsWith("npipe://")) {
            return dockerHost;
        }
        String normalized = "tcp://" + dockerHost;
        LOG.infov("Docker host value ''{0}'' has no URI scheme; normalizing to ''{1}''", dockerHost, normalized);
        return normalized;
    }

    /**
     * Resolves the effective Docker host to use when creating the client.
     *
     * Priority:
     * 1. If {@code floci.docker.docker-host} is explicitly configured (non-default), use it.
     * 2. Otherwise fall back to the standard {@code DOCKER_HOST} env var (normalized).
     * 3. Otherwise use the platform default: the unix socket on Linux/macOS, or Docker Desktop's
     *    named pipe on native Windows — which has no {@code /var/run/docker.sock} equivalent, so
     *    the unix-socket default is unreachable there unless a user overrides it manually.
     *
     * Both the configured value and the env var are normalized to ensure a valid URI scheme.
     */
    static String resolveEffectiveDockerHost(String configuredHost, String dockerHostEnv, boolean isWindows) {
        String normalizedEnvHost = normalizeDockerHost(dockerHostEnv);
        boolean atDefault = "unix:///var/run/docker.sock".equals(configuredHost);
        if (atDefault && normalizedEnvHost != null && !normalizedEnvHost.isBlank()) {
            return normalizedEnvHost;
        }
        if (atDefault && isWindows) {
            LOG.infov("Docker host is at its unix-socket default on Windows, which has no "
                    + "/var/run/docker.sock equivalent; using named pipe ''{0}'' instead.",
                    WINDOWS_DEFAULT_DOCKER_HOST);
            return WINDOWS_DEFAULT_DOCKER_HOST;
        }
        return normalizeDockerHost(configuredHost);
    }

    private static DefaultDockerClientConfig.Builder createDockerConfigBuilder() {
        try {
            return DefaultDockerClientConfig.createDefaultConfigBuilder();
        } catch (IllegalArgumentException e) {
            // DOCKER_HOST env var is set without a URI scheme (e.g. "10.37.124.101:2375").
            // docker-java calls URI.create() on it immediately inside createDefaultConfigBuilder(),
            // which throws before Floci's withDockerHost() override can take effect.
            // Fall back to a fresh builder; the caller will supply the normalized host.
            LOG.warnv("Could not initialize Docker config from environment "
                    + "(DOCKER_HOST env var may be missing a URI scheme): {0}. "
                    + "Using Floci''s configured host.", e.getMessage());
            return new DefaultDockerClientConfig.Builder();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Produces
    @ApplicationScoped
    public DockerClient dockerClient() {
        String dockerHost = resolveEffectiveDockerHost(
                config.docker().dockerHost(), System.getenv("DOCKER_HOST"), isWindows());
        LOG.infov("Creating DockerClient for host: {0}", dockerHost);

        // createDefaultConfigBuilder() reads DOCKER_HOST directly from System.getenv() and passes
        // it to withDockerHost(), which calls URI.create() immediately. If DOCKER_HOST is set
        // without a URI scheme (e.g. "10.37.124.101:2375" in Bitbucket Pipelines), the
        // URI.create() call throws before Floci's override takes effect. Fall back to a fresh
        // builder in that case so we can supply the normalized host ourselves.
        DefaultDockerClientConfig.Builder builder = createDockerConfigBuilder();
        builder.withDockerHost(dockerHost);
        config.docker().dockerConfigPath().ifPresent(path -> {
            LOG.infov("Using Docker config path: {0}", path);
            builder.withDockerConfig(path);
        });
        DefaultDockerClientConfig clientConfig = builder.build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofMinutes(5))
                .build();

        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}
