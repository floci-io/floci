package io.github.hectorvent.floci.services.floci.ui;

import java.util.Map;
import java.util.Optional;

import io.github.hectorvent.floci.config.EmulatorConfig.UiServiceConfig;
import org.jboss.logging.Logger;

/**
 * Resolves the {@link ConsoleProfile} for the configured console image.
 *
 * <p>Four layers, most specific first, each field resolved independently:
 *
 * <ol>
 *   <li>explicit {@code floci.services.ui.*} configuration</li>
 *   <li>{@code io.floci.console.*} labels on the image, when it declares
 *       {@code io.floci.console.contract=1}</li>
 *   <li>a built-in profile for a console Floci recognises by image name</li>
 *   <li>the contract-v1 defaults</li>
 * </ol>
 *
 * <p>The layering is what keeps a console that predates the contract working without asking its
 * operator for six environment variables, while a console that implements the contract needs none
 * of them. Everything here is static and free of Docker so the resolution order is directly
 * testable.
 */
public final class ConsoleProfileResolver {

    private static final Logger LOG = Logger.getLogger(ConsoleProfileResolver.class);

    /** Label prefix a console self-describes with. */
    public static final String LABEL_PREFIX = "io.floci.console.";
    /** Opt-in label. Only an image declaring this contract version has its other labels read. */
    public static final String LABEL_CONTRACT = LABEL_PREFIX + "contract";
    /** Contract version this Floci implements. */
    public static final String CONTRACT_VERSION = "1";

    /**
     * Value that turns the readiness field off from configuration. An empty string cannot be relied
     * on: an environment variable set to nothing reaches SmallRye as an absent property, which would
     * silently mean "use the default field" instead of "this console has no readiness field".
     */
    static final String NO_READY_FIELD = "none";

    private static final String FLOCI_UI_REPOSITORY = "floci-ui";

    private ConsoleProfileResolver() {
    }

    /** The defaults every console gets for free by implementing the contract. */
    public static ConsoleProfile contractV1() {
        return new ConsoleProfile(4500, "", "/api/health", "status", "ok", "unavailable",
                "the Floci web console");
    }

    /**
     * Resolves the full profile for an image.
     *
     * @param resolvedImage the image reference after registry resolution
     *                      ({@code ContainerBuilder.resolveImage})
     * @param labels        the image's labels, empty when they could not be read
     * @param config        the {@code floci.services.ui.*} configuration
     */
    public static ConsoleProfile resolve(String resolvedImage,
                                         Optional<Map<String, String>> labels,
                                         UiServiceConfig config) {
        ConsoleProfile profile = builtIn(resolvedImage);
        profile = fromLabels(labels.orElse(Map.of()), profile);
        return withOverrides(config, profile);
    }

    /**
     * The built-in profile for a console Floci ships with, or the contract defaults for anything else.
     *
     * <p>{@code floci/floci-ui} answers {@code /api/clouds/aws/status} with a {@code runtime} field,
     * which predates the contract. It is recognised by name so upgrading Floci does not strand the
     * console it already runs; the entry retires once floci-ui serves {@code /api/health}.
     */
    public static ConsoleProfile builtIn(String resolvedImage) {
        if (FLOCI_UI_REPOSITORY.equals(repositoryOf(resolvedImage))) {
            return new ConsoleProfile(4500, "", "/api/clouds/aws/status", "runtime", "reachable",
                    "unavailable", "the Floci web console");
        }
        return contractV1();
    }

    /**
     * Applies an image's self-description over a base profile.
     *
     * <p>Ignored entirely unless the image declares the contract version Floci implements: an image
     * that happens to carry an {@code io.floci.console.*} label for some future contract must not
     * have it read under this one's meaning.
     */
    public static ConsoleProfile fromLabels(Map<String, String> labels, ConsoleProfile base) {
        if (labels == null || !CONTRACT_VERSION.equals(trimmed(labels.get(LABEL_CONTRACT)))) {
            return base;
        }
        return new ConsoleProfile(
                labelPort(labels, base.internalPort()),
                label(labels, "endpoint-env", base.endpointEnv()),
                normalizePath(label(labels, "health-path", base.healthPath())),
                labelReadyField(labels, base.healthReadyField()),
                label(labels, "health-ready-value", base.healthReadyValue()),
                label(labels, "health-unavailable-value", base.healthUnavailableValue()),
                label(labels, "name", base.displayName()));
    }

    /** Applies explicit configuration over a base profile. Configuration always wins. */
    public static ConsoleProfile withOverrides(UiServiceConfig config, ConsoleProfile base) {
        return new ConsoleProfile(
                config.internalPort().orElse(base.internalPort()),
                override(config.endpointEnv(), base.endpointEnv()),
                normalizePath(override(config.statusPath(), base.healthPath())),
                configuredReadyField(config.statusReadyField(), base.healthReadyField()),
                override(config.statusReadyValue(), base.healthReadyValue()),
                override(config.statusUnavailableValue(), base.healthUnavailableValue()),
                base.displayName());
    }

    /**
     * The repository name of an image reference, without registry, namespace, tag or digest:
     * {@code ghcr.io/floci-io/floci-ui@sha256:abc} and {@code floci/floci-ui:latest} both yield
     * {@code floci-ui}. A fork republished under another namespace is still the same console, so
     * only the final path segment is compared.
     */
    static String repositoryOf(String image) {
        if (image == null || image.isBlank()) {
            return "";
        }
        String reference = image.trim();
        int digest = reference.indexOf('@');
        if (digest >= 0) {
            reference = reference.substring(0, digest);
        }
        int lastSlash = reference.lastIndexOf('/');
        String lastSegment = reference.substring(lastSlash + 1);
        int tag = lastSegment.lastIndexOf(':');
        if (tag >= 0) {
            lastSegment = lastSegment.substring(0, tag);
        }
        return lastSegment;
    }

    /** Normalizes a health path to a leading slash, so a bare {@code api/health} still forms a URL. */
    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return contractV1().healthPath();
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static int labelPort(Map<String, String> labels, int fallback) {
        String raw = trimmed(labels.get(LABEL_PREFIX + "port"));
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(raw);
            if (port > 0 && port <= 65535) {
                return port;
            }
            LOG.warnv("Ignoring image label {0}port=\"{1}\": not a usable port number.", LABEL_PREFIX, raw);
        } catch (NumberFormatException e) {
            LOG.warnv("Ignoring image label {0}port=\"{1}\": not a number ({2}).",
                    LABEL_PREFIX, raw, e.getMessage());
        }
        return fallback;
    }

    /**
     * The readiness field from a label. Unlike every other label, a declared-but-empty value is
     * meaningful: it is how a console with a plain liveness endpoint says it has no field to read.
     */
    private static String labelReadyField(Map<String, String> labels, String fallback) {
        String key = LABEL_PREFIX + "health-ready-field";
        if (!labels.containsKey(key)) {
            return fallback;
        }
        String value = trimmed(labels.get(key));
        return NO_READY_FIELD.equalsIgnoreCase(value) ? "" : value;
    }

    private static String configuredReadyField(Optional<String> configured, String fallback) {
        String value = configured.map(String::trim).orElse("");
        if (value.isEmpty()) {
            return fallback;
        }
        return NO_READY_FIELD.equalsIgnoreCase(value) ? "" : value;
    }

    private static String label(Map<String, String> labels, String suffix, String fallback) {
        String value = trimmed(labels.get(LABEL_PREFIX + suffix));
        return value.isEmpty() ? fallback : value;
    }

    private static String override(Optional<String> configured, String fallback) {
        String value = configured.map(String::trim).orElse("");
        return value.isEmpty() ? fallback : value;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
