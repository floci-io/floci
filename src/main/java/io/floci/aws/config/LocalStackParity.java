package io.floci.aws.config;

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

    private static final Pattern BRACKETED_IPV6 = Pattern.compile("^\\[([^\\]]+)\\](?::(\\d+))?$");

    /** One translated setting: the Floci variable it stands for, its property, and its value. */
    record Mapping(String variable, String property, String value) {
    }

    /** A host and a port, either of which may be absent (LocalStack then uses its default). */
    record HostAndPort(String host, String port) {
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
        // An empty host (":4566") keeps Floci's own bind address and takes just the port.
        String gatewayListen = env.get("GATEWAY_LISTEN");
        HostAndPort gateway = isSet(gatewayListen)
                ? parseHostAndPort(gatewayListen.split(",")[0])
                : new HostAndPort(null, null);
        String gatewayHost = gateway.host();

        put(out, env, "FLOCI_PORT", "floci.port", isSet(env.get("EDGE_PORT")) ? env.get("EDGE_PORT") : gateway.port());
        // LOCALSTACK_HOST is <hostname>:<port> (default localhost.localstack.cloud:4566); Floci's
        // hostname is the host alone, its port comes from floci.port.
        String localstackHost = firstSet(env, "LOCALSTACK_HOST", "LOCALSTACK_HOSTNAME");
        put(out, env, "FLOCI_HOSTNAME", "floci.hostname",
                isSet(localstackHost) ? parseHostAndPort(localstackHost).host() : null);

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

    /**
     * Parses the forms LocalStack's {@code HostAndPort.parse} accepts: {@code host:port},
     * {@code host}, {@code :port}, {@code [v6]:port} and {@code [v6]}. A missing part is null.
     */
    static HostAndPort parseHostAndPort(String input) {
        String value = input.trim();
        Matcher ipv6 = BRACKETED_IPV6.matcher(value);
        if (ipv6.matches()) {
            return new HostAndPort(ipv6.group(1), ipv6.group(2));
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            return new HostAndPort(value.isEmpty() ? null : value, null);
        }
        String host = value.substring(0, colon).trim();
        String port = value.substring(colon + 1).trim();
        return new HostAndPort(host.isEmpty() ? null : host, port.isEmpty() ? null : port);
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
