package io.github.hectorvent.floci.config;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates LocalStack Community environment variables into the Floci configuration they mean,
 * so a LocalStack setup works unchanged in the image and in a binary on the host alike.
 *
 * <p>An explicitly set Floci variable always wins: a mapping is produced only when the matching
 * {@code FLOCI_*} or {@code QUARKUS_*} environment variable is absent. The DNS suffixes are the
 * one exception: LocalStack's and Floci's hostname suffixes are appended to the configured ones,
 * wherever those are set ({@link #withDnsSuffixes}). The whole translation is off when
 * {@code LOCALSTACK_PARITY=false}.
 */
public final class LocalStackParity {

    private static final Logger LOG = Logger.getLogger(LocalStackParity.class);

    static final List<String> PARITY_SUFFIXES = List.of("localhost.localstack.cloud", "localhost.floci.io");

    /** {@code host:port}, {@code [v6]:port}, or a bare host; LocalStack accepts a comma-separated list. */
    private static final Pattern HOST_PORT = Pattern.compile("^(?:\\[([^\\]]+)\\]|([^:\\[\\]]+)):(\\d+)$");

    /** One translated setting: the Floci variable it stands for, its property, and its value. */
    record Mapping(String variable, String property, String value) {
    }

    private LocalStackParity() {
    }

    public static boolean enabled(Map<String, String> env) {
        return !"false".equals(env.get("LOCALSTACK_PARITY"));
    }

    /** The translation for this process's environment, as configuration property names. */
    public static Map<String, String> forCurrentEnvironment() {
        return translate(System.getenv());
    }

    /**
     * @return property name to value, for every LocalStack variable present whose Floci
     *         counterpart is not set; empty when parity is disabled
     */
    public static Map<String, String> translate(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Mapping mapping : mappings(env)) {
            out.put(mapping.property(), mapping.value());
        }
        return out;
    }

    /**
     * The same translation as {@code FLOCI_*}/{@code QUARKUS_*} variables, for child processes such
     * as init hooks: the entrypoint script used to export them, so hooks inherited them. Includes
     * {@code FLOCI_DNS_EXTRA_SUFFIXES} with the LocalStack suffixes appended, as the script did.
     */
    public static Map<String, String> environment(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!enabled(env)) {
            return out;
        }
        for (Mapping mapping : mappings(env)) {
            out.put(mapping.variable(), mapping.value());
        }
        String suffixes = env.get("FLOCI_DNS_EXTRA_SUFFIXES");
        out.put("FLOCI_DNS_EXTRA_SUFFIXES", String.join(",", withDnsSuffixes(
                isSet(suffixes) ? Optional.of(List.of(suffixes.split(","))) : Optional.empty(), env).orElseThrow()));
        return out;
    }

    /** The configured extra DNS suffixes, plus LocalStack's and Floci's when parity is on. */
    public static Optional<List<String>> withDnsSuffixes(Optional<List<String>> configured) {
        return withDnsSuffixes(configured, System.getenv());
    }

    static Optional<List<String>> withDnsSuffixes(Optional<List<String>> configured, Map<String, String> env) {
        if (!enabled(env)) {
            return configured;
        }
        Set<String> suffixes = new LinkedHashSet<>(configured.orElse(List.of()));
        suffixes.addAll(PARITY_SUFFIXES);
        return Optional.of(List.copyOf(suffixes));
    }

    private static List<Mapping> mappings(Map<String, String> env) {
        List<Mapping> out = new ArrayList<>();
        if (!enabled(env)) {
            return out;
        }

        String persist = firstSet(env, "PERSISTENCE", "PERSIST_STATE");
        if ("1".equals(persist) || "true".equals(persist)) {
            put(out, env, "FLOCI_STORAGE_MODE", "floci.storage.mode", "persistent");
        }

        // LocalStack listens on GATEWAY_LISTEN with no further opt-in, so it also gives the
        // consent Floci requires for a non-loopback address. Its first entry may carry a port,
        // which stands in for EDGE_PORT when that is unset.
        String gatewayHost = null;
        String gatewayPort = null;
        String gatewayListen = env.get("GATEWAY_LISTEN");
        if (isSet(gatewayListen)) {
            String first = gatewayListen.split(",")[0].trim();
            Matcher hostPort = HOST_PORT.matcher(first);
            if (hostPort.matches()) {
                gatewayHost = hostPort.group(1) != null ? hostPort.group(1) : hostPort.group(2);
                gatewayPort = hostPort.group(3);
            } else {
                gatewayHost = first;
            }
        }

        put(out, env, "FLOCI_PORT", "floci.port", isSet(env.get("EDGE_PORT")) ? env.get("EDGE_PORT") : gatewayPort);
        put(out, env, "FLOCI_HOSTNAME", "floci.hostname", firstSet(env, "LOCALSTACK_HOST", "LOCALSTACK_HOSTNAME"));

        if (isSet(gatewayHost)) {
            put(out, env, "QUARKUS_HTTP_HOST", "quarkus.http.host", gatewayHost);
            put(out, env, "FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE",
                    "floci.security.allow-unsafe-network-exposure", "true");
        }

        String lsLog = env.get("LS_LOG");
        if (isSet(lsLog)) {
            put(out, env, "QUARKUS_LOG_LEVEL", "quarkus.log.level", lsLog);
        } else if ("1".equals(env.get("DEBUG"))) {
            put(out, env, "QUARKUS_LOG_LEVEL", "quarkus.log.level", "DEBUG");
        }

        put(out, env, "FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK", "floci.services.lambda.docker-network",
                env.get("LAMBDA_DOCKER_NETWORK"));
        String removeContainers = env.get("LAMBDA_REMOVE_CONTAINERS");
        if ("1".equals(removeContainers) || "true".equals(removeContainers)) {
            put(out, env, "FLOCI_SERVICES_LAMBDA_EPHEMERAL", "floci.services.lambda.ephemeral", "true");
        }
        if (isSet(env.get("LAMBDA_REMOTE_DOCKER"))) {
            LOG.warn("LAMBDA_REMOTE_DOCKER is not fully supported by Floci. Use S3Bucket=hot-reload per function"
                    + " instead. See https://floci.io/docs/services/lambda");
        }

        put(out, env, "FLOCI_DOCKER_DOCKER_HOST", "floci.docker.docker-host", env.get("DOCKER_HOST"));
        // Shared by every container-backed service; per-service networks still take precedence.
        put(out, env, "FLOCI_SERVICES_DOCKER_NETWORK", "floci.services.docker-network", env.get("DOCKER_NETWORK"));

        if ("1".equals(env.get("USE_SSL"))) {
            put(out, env, "FLOCI_TLS_ENABLED", "floci.tls.enabled", "true");
        }
        // LocalStack takes one combined PEM (certificate and key); Floci accepts it in both fields.
        String customCert = env.get("CUSTOM_SSL_CERT_PATH");
        put(out, env, "FLOCI_TLS_CERT_PATH", "floci.tls.cert-path", customCert);
        put(out, env, "FLOCI_TLS_KEY_PATH", "floci.tls.key-path", customCert);

        // LAMBDA_EXECUTOR and SERVICES are intentionally ignored.
        return out;
    }

    private static void put(List<Mapping> out, Map<String, String> env, String flociVariable,
                            String property, String value) {
        if (isSet(value) && !isSet(env.get(flociVariable))) {
            out.add(new Mapping(flociVariable, property, value));
        }
    }

    private static String firstSet(Map<String, String> env, String first, String second) {
        String value = env.get(first);
        return isSet(value) ? value : env.get(second);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }
}
