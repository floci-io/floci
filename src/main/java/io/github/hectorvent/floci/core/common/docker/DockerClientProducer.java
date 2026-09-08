package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.SSLConfig;
import com.github.dockerjava.core.util.CertificateUtils;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;

/**
 * CDI producer for the DockerClient singleton bean.
 */
@ApplicationScoped
public class DockerClientProducer {

    private static final Logger LOG = Logger.getLogger(DockerClientProducer.class);

    /**
     * Docker Desktop's well-known named pipe on native Windows. Unlike the unix-socket default,
     * this isn't reachable by simply bind-mounting a path: Windows has no equivalent of
     * {@code /var/run/docker.sock}, so a platform-specific fallback is required.
     */
    private static final String WINDOWS_DEFAULT_DOCKER_HOST = "npipe:////./pipe/docker_engine";

    /**
     * The name Docker CLI uses for the built-in context that represents the platform default
     * endpoint. It has no metadata file on disk, so encountering it means "no active context
     * override; fall through to the hardcoded platform default".
     */
    private static final String DEFAULT_CONTEXT_NAME = "default";

    private static final ObjectMapper DOCKER_CONTEXT_JSON_MAPPER = new ObjectMapper();

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
     * The Docker CLI's standard directory-listing for a context's TLS client material, relative
     * to that context's TLS storage directory: {@code contexts/tls/<context-hash>/docker/}.
     */
    private static final String CONTEXT_TLS_ENDPOINT_DIR = "docker";

    /**
     * The host and, when the active context carries TLS material of its own, the directory
     * holding it ({@code ca.pem}, {@code cert.pem}, {@code key.pem}), resolved together so both
     * can be applied consistently to the {@link DefaultDockerClientConfig.Builder}. {@code
     * skipTlsVerify} mirrors the context's own {@code SkipTLSVerify} setting: when true, the
     * client certificate is still presented, but the daemon's server certificate is not
     * validated against the context's CA, matching what the Docker CLI itself does for such
     * a context.
     */
    record ResolvedDockerConnection(String host, Optional<Path> tlsCertPath, boolean skipTlsVerify) {
    }

    /**
     * Resolves the effective Docker host to use when creating the client, reading the active
     * Docker context from the current user's {@code ~/.docker} directory.
     *
     * @see #resolveEffectiveDockerHost(String, String, boolean, Path, String)
     */
    static String resolveEffectiveDockerHost(String configuredHost, String dockerHostEnv, boolean isWindows) {
        return resolveEffectiveDockerHost(configuredHost, dockerHostEnv, isWindows, defaultDockerConfigDir(),
                System.getenv("DOCKER_CONTEXT"));
    }

    /**
     * @see #resolveEffectiveDockerHost(String, String, boolean, Path, String)
     */
    static String resolveEffectiveDockerHost(
            String configuredHost, String dockerHostEnv, boolean isWindows, Path dockerConfigDir) {
        return resolveEffectiveDockerHost(configuredHost, dockerHostEnv, isWindows, dockerConfigDir, null);
    }

    /**
     * Resolves the effective Docker host to use when creating the client.
     *
     * Priority:
     * 1. If {@code floci.docker.docker-host} is explicitly configured (non-default), use it.
     * 2. Otherwise fall back to the standard {@code DOCKER_HOST} env var (normalized).
     * 3. Otherwise resolve the active Docker context (e.g. Colima, OrbStack, Rancher Desktop,
     *    Podman) from {@code dockerConfigDir}, the way the Docker CLI and Testcontainers do, and
     *    use its endpoint if one is found. The active context is the standard {@code
     *    DOCKER_CONTEXT} env var when set and non-blank, otherwise {@code config.json}'s
     *    {@code currentContext} field.
     * 4. Otherwise use the platform default: the unix socket on Linux/macOS, or Docker Desktop's
     *    named pipe on native Windows, which has no {@code /var/run/docker.sock} equivalent, so
     *    the unix-socket default is unreachable there unless a user overrides it manually.
     *
     * Both the configured value and the env var are normalized to ensure a valid URI scheme.
     */
    static String resolveEffectiveDockerHost(
            String configuredHost, String dockerHostEnv, boolean isWindows, Path dockerConfigDir,
            String dockerContextEnv) {
        return resolveDockerConnection(configuredHost, dockerHostEnv, isWindows, dockerConfigDir, dockerContextEnv)
                .host();
    }

    /**
     * Resolves the effective Docker host together with the active context's TLS material, when
     * it has any. See {@link #resolveEffectiveDockerHost(String, String, boolean, Path, String)}
     * for the host resolution priority; TLS material is only ever sourced from an active,
     * non-default context, never from {@code floci.docker.docker-host} or {@code DOCKER_HOST}
     * (both of those already flow through docker-java's own {@code DOCKER_TLS_VERIFY} /
     * {@code DOCKER_CERT_PATH} env-var handling).
     */
    static ResolvedDockerConnection resolveDockerConnection(
            String configuredHost, String dockerHostEnv, boolean isWindows, Path dockerConfigDir,
            String dockerContextEnv) {
        String normalizedEnvHost = normalizeDockerHost(dockerHostEnv);
        boolean atDefault = "unix:///var/run/docker.sock".equals(configuredHost);
        if (atDefault && normalizedEnvHost != null && !normalizedEnvHost.isBlank()) {
            return new ResolvedDockerConnection(normalizedEnvHost, Optional.empty(), false);
        }
        if (atDefault) {
            DockerContextEndpoint contextEndpoint =
                    resolveActiveDockerContextEndpoint(dockerConfigDir, dockerContextEnv);
            if (contextEndpoint != null) {
                String normalizedContextHost = normalizeDockerHost(contextEndpoint.host());
                LOG.infov("No DOCKER_HOST override set; using endpoint ''{0}'' from the active "
                        + "Docker context instead of the platform default.", normalizedContextHost);
                return new ResolvedDockerConnection(normalizedContextHost, contextEndpoint.tlsCertPath(),
                        contextEndpoint.skipTlsVerify());
            }
        }
        if (atDefault && isWindows) {
            LOG.infov("Docker host is at its unix-socket default on Windows, which has no "
                    + "/var/run/docker.sock equivalent; using named pipe ''{0}'' instead.",
                    WINDOWS_DEFAULT_DOCKER_HOST);
            return new ResolvedDockerConnection(WINDOWS_DEFAULT_DOCKER_HOST, Optional.empty(), false);
        }
        return new ResolvedDockerConnection(normalizeDockerHost(configuredHost), Optional.empty(), false);
    }

    private static Path defaultDockerConfigDir() {
        return Paths.get(System.getProperty("user.home", ""), ".docker");
    }

    /**
     * Resolves the Docker config directory to use for context discovery, matching the Docker
     * CLI's own precedence: {@code floci.docker.docker-config-path}, when configured, wins
     * outright; otherwise the standard {@code DOCKER_CONFIG} env var relocates the whole
     * {@code ~/.docker} directory; otherwise the {@code ~/.docker} default applies.
     */
    static Path resolveDockerConfigDir(Optional<String> configuredDockerConfigPath, String dockerConfigEnv) {
        if (configuredDockerConfigPath != null && configuredDockerConfigPath.isPresent()
                && !configuredDockerConfigPath.get().isBlank()) {
            return Paths.get(configuredDockerConfigPath.get());
        }
        if (dockerConfigEnv != null && !dockerConfigEnv.isBlank()) {
            return Paths.get(dockerConfigEnv);
        }
        return defaultDockerConfigDir();
    }

    private record DockerContextEndpoint(String host, Optional<Path> tlsCertPath, boolean skipTlsVerify) {
    }

    /**
     * Resolves the Docker endpoint of the active Docker context, mirroring how the Docker CLI
     * and Testcontainers' {@code DockerClientProviderStrategy} do it: read the active context
     * name (the built-in {@code "default"} context has no endpoint of its own, so it is treated
     * the same as no context being configured at all), then read that context's {@code Host}
     * endpoint from its metadata file under {@code contexts/meta/}, along with any TLS client
     * material under {@code contexts/tls/}.
     *
     * @return the resolved endpoint, or {@code null} if no active non-default context is
     *         configured, its metadata is missing, or {@code config.json} is missing or malformed
     */
    private static DockerContextEndpoint resolveActiveDockerContextEndpoint(
            Path dockerConfigDir, String dockerContextEnv) {
        if (dockerConfigDir == null) {
            return null;
        }
        String currentContext = resolveActiveDockerContextName(dockerConfigDir, dockerContextEnv);
        if (currentContext == null || currentContext.isBlank() || DEFAULT_CONTEXT_NAME.equals(currentContext)) {
            return null;
        }
        String host = readContextDockerHost(dockerConfigDir, currentContext);
        if (host == null || host.isBlank()) {
            return null;
        }
        return new DockerContextEndpoint(host, resolveActiveDockerContextTlsMaterial(dockerConfigDir, currentContext),
                readContextSkipTlsVerify(dockerConfigDir, currentContext));
    }

    /**
     * Resolves the name of the active Docker context, honoring the standard {@code DOCKER_CONTEXT}
     * env var override before falling back to {@code config.json}'s {@code currentContext} field,
     * matching the real Docker CLI's own precedence.
     */
    private static String resolveActiveDockerContextName(Path dockerConfigDir, String dockerContextEnv) {
        if (dockerContextEnv != null && !dockerContextEnv.isBlank()) {
            return dockerContextEnv;
        }
        return readCurrentContextName(dockerConfigDir.resolve("config.json"));
    }

    private static String readCurrentContextName(Path configJsonPath) {
        if (!Files.isRegularFile(configJsonPath)) {
            return null;
        }
        try {
            JsonNode root = DOCKER_CONTEXT_JSON_MAPPER.readTree(configJsonPath.toFile());
            if (root == null || root.isMissingNode()) {
                return null;
            }
            JsonNode contextNode = root.get("currentContext");
            return contextNode == null || contextNode.isNull() ? null : contextNode.asText();
        } catch (IOException e) {
            LOG.warnv("Could not read Docker config file ''{0}'' to resolve the active context: {1}",
                    configJsonPath, e.getMessage());
            return null;
        }
    }

    private static String readContextDockerHost(Path dockerConfigDir, String contextName) {
        Path metaFile = dockerConfigDir.resolve("contexts").resolve("meta")
                .resolve(sha256Hex(contextName)).resolve("meta.json");
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        try {
            JsonNode root = DOCKER_CONTEXT_JSON_MAPPER.readTree(metaFile.toFile());
            if (root == null || root.isMissingNode()) {
                return null;
            }
            JsonNode hostNode = root.path("Endpoints").path("docker").get("Host");
            return hostNode == null || hostNode.isNull() ? null : hostNode.asText();
        } catch (IOException e) {
            LOG.warnv("Could not read Docker context metadata file ''{0}'' for context ''{1}'': {2}",
                    metaFile, contextName, e.getMessage());
            return null;
        }
    }

    /**
     * Reads the context's own {@code SkipTLSVerify} setting, defaulting to {@code false} (the
     * same default the Docker CLI itself uses) when the field, the metadata file, or the
     * {@code Endpoints.docker} section is absent or malformed.
     */
    private static boolean readContextSkipTlsVerify(Path dockerConfigDir, String contextName) {
        Path metaFile = dockerConfigDir.resolve("contexts").resolve("meta")
                .resolve(sha256Hex(contextName)).resolve("meta.json");
        if (!Files.isRegularFile(metaFile)) {
            return false;
        }
        try {
            JsonNode root = DOCKER_CONTEXT_JSON_MAPPER.readTree(metaFile.toFile());
            if (root == null || root.isMissingNode()) {
                return false;
            }
            return root.path("Endpoints").path("docker").path("SkipTLSVerify").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Locates the active context's TLS client-certificate directory, mirroring how the Docker
     * CLI stores it under {@code contexts/tls/<context-hash>/docker/} alongside the metadata
     * directory. A context with no TLS material configured (a plaintext remote daemon, or a
     * context pointing at a local unix socket or named pipe) simply has no such directory, and
     * is left untouched here. Whether the context's own {@code SkipTLSVerify} setting is honored
     * once material is found is resolved separately by {@link #readContextSkipTlsVerify}, since
     * that flag is meaningless without TLS material to apply it to.
     *
     * <p>When the directory exists but is incomplete, that is treated as a broken TLS setup
     * rather than silently connecting without a client certificate or falling back to plaintext.
     *
     * @return the TLS directory when all of {@code ca.pem}, {@code cert.pem} and {@code key.pem}
     *         are present, or {@link Optional#empty()} when the context has no TLS material at all
     * @throws IllegalStateException if the TLS directory exists but is missing one of those files
     */
    private static Optional<Path> resolveActiveDockerContextTlsMaterial(Path dockerConfigDir, String contextName) {
        Path tlsDir = dockerConfigDir.resolve("contexts").resolve("tls")
                .resolve(sha256Hex(contextName)).resolve(CONTEXT_TLS_ENDPOINT_DIR);
        if (!Files.isDirectory(tlsDir)) {
            return Optional.empty();
        }
        boolean hasCa = Files.isRegularFile(tlsDir.resolve("ca.pem"));
        boolean hasCert = Files.isRegularFile(tlsDir.resolve("cert.pem"));
        boolean hasKey = Files.isRegularFile(tlsDir.resolve("key.pem"));
        if (hasCa && hasCert && hasKey) {
            LOG.infov("Docker context ''{0}'' has TLS material at ''{1}''; using it for the "
                    + "Docker client connection.", contextName, tlsDir);
            return Optional.of(tlsDir);
        }
        if (hasCa || hasCert || hasKey) {
            throw new IllegalStateException(String.format(
                    "Docker context '%s' has an incomplete TLS material directory at '%s' "
                            + "(expected ca.pem, cert.pem and key.pem); refusing to silently fall back "
                            + "to an unauthenticated or unverified connection.", contextName, tlsDir));
        }
        return Optional.empty();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
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
        Path dockerConfigDir =
                resolveDockerConfigDir(config.docker().dockerConfigPath(), System.getenv("DOCKER_CONFIG"));
        ResolvedDockerConnection connection = resolveDockerConnection(
                config.docker().dockerHost(), System.getenv("DOCKER_HOST"), isWindows(),
                dockerConfigDir, System.getenv("DOCKER_CONTEXT"));
        String dockerHost = connection.host();
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
        connection.tlsCertPath().ifPresent(path -> {
            if (connection.skipTlsVerify()) {
                LOG.infov("Using TLS material from the active Docker context at: {0}, with server "
                        + "certificate verification skipped per the context's SkipTLSVerify setting.",
                        path);
                builder.withCustomSslConfig(new SkipVerifySslConfig(path.toString()));
            } else {
                LOG.infov("Using TLS material from the active Docker context at: {0}", path);
                builder.withDockerTlsVerify(true);
                builder.withDockerCertPath(path.toString());
            }
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

    /**
     * Presents the context's client certificate for mutual TLS, like {@code
     * LocalDirectorySSLConfig}, but trusts the daemon's server certificate unconditionally
     * instead of validating it against the context's CA. docker-java's own {@code
     * LocalDirectorySSLConfig} always validates the server certificate, with no built-in way to
     * skip that step, so a context with {@code SkipTLSVerify: true} needs a dedicated {@link
     * SSLConfig} to reproduce the Docker CLI's own behavior for it.
     */
    private static final class SkipVerifySslConfig implements SSLConfig, Serializable {

        private static final long serialVersionUID = 1L;
        private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        private final String dockerCertPath;

        SkipVerifySslConfig(String dockerCertPath) {
            this.dockerCertPath = dockerCertPath;
        }

        @Override
        public SSLContext getSSLContext() {
            try {
                String keyPem = Files.readString(Path.of(dockerCertPath, "key.pem"));
                String certPem = Files.readString(Path.of(dockerCertPath, "cert.pem"));
                KeyManagerFactory keyManagerFactory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(CertificateUtils.createKeyStore(keyPem, certPem), "docker".toCharArray());
                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                sslContext.init(keyManagerFactory.getKeyManagers(), new TrustManager[] {TRUST_ALL}, null);
                return sslContext;
            } catch (Exception e) {
                throw new DockerClientException(e.getMessage(), e);
            }
        }
    }
}
