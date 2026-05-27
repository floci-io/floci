package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog of OpenSearch / Elasticsearch engine versions Floci can advertise on
 * {@code ListVersions} and validate against on {@code CreateDomain} /
 * {@code UpdateDomainConfig} / {@code UpgradeDomain}.
 *
 * <p>Each entry maps an AWS-shaped engine version (e.g. {@code OpenSearch_3.0})
 * to the Docker image tag that {@link OpenSearchDomainManager} pulls when the
 * service runs in real-container mode. Without this map, every domain would
 * boot whatever {@code opensearchproject/opensearch:2} resolved to at pull
 * time, regardless of the user-requested {@code EngineVersion} — a footgun for
 * anyone testing version-specific behavior (mapping changes between OS 2 and
 * 3, plugin compatibility, removed APIs, etc.).
 *
 * <p>The Elasticsearch 7.10 entry deliberately points at the last
 * Apache-2-licensed image ({@code elasticsearch-oss:7.10.2}). Newer
 * Elasticsearch releases ship under the Elastic License v2 / SSPL and are
 * intentionally out of scope.
 */
public final class OpenSearchVersions {

    /**
     * Default engine version when {@code CreateDomain} omits {@code EngineVersion}.
     * Tracks the latest 2.x LTS-style line that's broadly compatible with
     * Elasticsearch 7.10 client SDKs (the most common reason users still use
     * Floci's OpenSearch surface).
     */
    public static final String DEFAULT_VERSION = "OpenSearch_2.19";

    private static final Map<String, String> VERSION_TO_IMAGE = new LinkedHashMap<>();
    private static final Map<String, List<String>> COMPAT_MATRIX = new LinkedHashMap<>();

    static {
        // OpenSearch 3.x line — pinned to a concrete image tag rather than the
        // floating "3" alias so test runs are reproducible.
        VERSION_TO_IMAGE.put("OpenSearch_3.6", "opensearchproject/opensearch:3.6.0");
        VERSION_TO_IMAGE.put("OpenSearch_3.5", "opensearchproject/opensearch:3.5.0");
        VERSION_TO_IMAGE.put("OpenSearch_3.4", "opensearchproject/opensearch:3.4.0");
        VERSION_TO_IMAGE.put("OpenSearch_3.3", "opensearchproject/opensearch:3.3.2");
        VERSION_TO_IMAGE.put("OpenSearch_3.2", "opensearchproject/opensearch:3.2.0");
        VERSION_TO_IMAGE.put("OpenSearch_3.1", "opensearchproject/opensearch:3.1.0");
        VERSION_TO_IMAGE.put("OpenSearch_3.0", "opensearchproject/opensearch:3.0.0");

        // OpenSearch 2.x — keep the historical entries Floci already accepted,
        // bump the head to 2.19.5 (current latest 2.x as of 2026-05).
        VERSION_TO_IMAGE.put("OpenSearch_2.19", "opensearchproject/opensearch:2.19.5");
        VERSION_TO_IMAGE.put("OpenSearch_2.17", "opensearchproject/opensearch:2.17.1");
        VERSION_TO_IMAGE.put("OpenSearch_2.15", "opensearchproject/opensearch:2.15.0");
        VERSION_TO_IMAGE.put("OpenSearch_2.13", "opensearchproject/opensearch:2.13.0");
        VERSION_TO_IMAGE.put("OpenSearch_2.11", "opensearchproject/opensearch:2.11.1");
        VERSION_TO_IMAGE.put("OpenSearch_2.9", "opensearchproject/opensearch:2.9.0");
        VERSION_TO_IMAGE.put("OpenSearch_2.7", "opensearchproject/opensearch:2.7.0");
        VERSION_TO_IMAGE.put("OpenSearch_2.5", "opensearchproject/opensearch:2.5.0");
        VERSION_TO_IMAGE.put("OpenSearch_2.3", "opensearchproject/opensearch:2.3.0");

        // OpenSearch 1.x — kept for legacy upgrade paths (1.x → 2.x).
        VERSION_TO_IMAGE.put("OpenSearch_1.3", "opensearchproject/opensearch:1.3.20");
        VERSION_TO_IMAGE.put("OpenSearch_1.2", "opensearchproject/opensearch:1.2.4");

        // Elasticsearch 7.x — last Apache-2 OSS line. Image is on Elastic's
        // registry, not Docker Hub; image string includes the host so the
        // Docker daemon doesn't try to resolve it under {@code library/}.
        VERSION_TO_IMAGE.put("Elasticsearch_7.10", "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2");
        VERSION_TO_IMAGE.put("Elasticsearch_7.9", "docker.elastic.co/elasticsearch/elasticsearch-oss:7.9.3");
        VERSION_TO_IMAGE.put("Elasticsearch_7.8", "docker.elastic.co/elasticsearch/elasticsearch-oss:7.8.1");

        // Compatibility matrix — mirrors AWS OpenSearch's allowed in-place
        // upgrade hops. Source versions that are still on the supported list
        // but that AWS no longer lets you upgrade out of are intentionally
        // omitted.
        //
        // Per AWS's version-migration guide, OpenSearch 1.3 / 2.x domains must
        // first upgrade to OpenSearch_2.19 before they can hop to the 3.x
        // line, so non-2.19 2.x sources do NOT list any 3.x targets directly.
        // https://docs.aws.amazon.com/opensearch-service/latest/developerguide/version-migration.html
        COMPAT_MATRIX.put("Elasticsearch_7.10", List.of("OpenSearch_1.3", "OpenSearch_2.19"));
        COMPAT_MATRIX.put("OpenSearch_1.3", List.of("OpenSearch_2.19", "OpenSearch_2.17", "OpenSearch_2.15", "OpenSearch_2.13"));
        COMPAT_MATRIX.put("OpenSearch_2.19", List.of("OpenSearch_3.0", "OpenSearch_3.1", "OpenSearch_3.6"));
        COMPAT_MATRIX.put("OpenSearch_2.17", List.of("OpenSearch_2.19"));
        COMPAT_MATRIX.put("OpenSearch_2.15", List.of("OpenSearch_2.19"));
        COMPAT_MATRIX.put("OpenSearch_2.13", List.of("OpenSearch_2.19"));
        COMPAT_MATRIX.put("OpenSearch_2.11", List.of("OpenSearch_2.13", "OpenSearch_2.19"));
        COMPAT_MATRIX.put("OpenSearch_2.9", List.of("OpenSearch_2.11", "OpenSearch_2.13"));
        COMPAT_MATRIX.put("OpenSearch_3.0", List.of("OpenSearch_3.1", "OpenSearch_3.6"));
        COMPAT_MATRIX.put("OpenSearch_3.1", List.of("OpenSearch_3.2", "OpenSearch_3.6"));
    }

    private OpenSearchVersions() {
    }

    /** Versions advertised on {@code ListVersions}, newest-first. */
    public static List<String> supportedVersions() {
        return List.copyOf(VERSION_TO_IMAGE.keySet());
    }

    /**
     * Pick the image for a domain, honoring an optional operator-supplied
     * override that applies to every domain regardless of
     * {@code engineVersion} (private registry mirror, pinned patch). When the
     * override is absent we delegate to {@link #imageFor(String)}.
     */
    public static String resolveImage(Optional<String> override, String engineVersion) {
        return override.orElseGet(() -> imageFor(engineVersion));
    }

    /**
     * Docker image to pull for {@code engineVersion}. Falls back to the
     * default-version image only when {@code engineVersion} is null/blank;
     * unknown versions throw — call {@link #validate(String)} first.
     */
    public static String imageFor(String engineVersion) {
        if (engineVersion == null || engineVersion.isBlank()) {
            return VERSION_TO_IMAGE.get(DEFAULT_VERSION);
        }
        String image = VERSION_TO_IMAGE.get(engineVersion);
        if (image == null) {
            throw new AwsException("ValidationException",
                    "Unsupported EngineVersion: " + engineVersion, 400);
        }
        return image;
    }

    /** Targets reachable from {@code sourceVersion}, empty if no upgrade path is defined. */
    public static List<String> compatibleTargets(String sourceVersion) {
        return COMPAT_MATRIX.getOrDefault(sourceVersion, List.of());
    }

    /** Every {@code (source, targets)} pair in the matrix, in declaration order. */
    public static Map<String, List<String>> compatibilityMatrix() {
        return Map.copyOf(COMPAT_MATRIX);
    }

    /**
     * Throws {@code ValidationException} when {@code engineVersion} is set but
     * not in the supported list. A null/blank value is allowed — callers that
     * accept an omitted {@code EngineVersion} fall back to {@link #DEFAULT_VERSION}.
     */
    public static void validate(String engineVersion) {
        if (engineVersion == null || engineVersion.isBlank()) {
            return;
        }
        if (!VERSION_TO_IMAGE.containsKey(engineVersion)) {
            throw new AwsException("ValidationException",
                    "Unsupported EngineVersion: " + engineVersion
                            + ". Supported: " + VERSION_TO_IMAGE.keySet(), 400);
        }
    }

    /**
     * Throws {@code ValidationException} when {@code targetVersion} is not in
     * the supported list, or not a documented upgrade target from {@code sourceVersion}.
     */
    public static void validateUpgrade(String sourceVersion, String targetVersion) {
        validate(targetVersion);
        List<String> allowed = compatibleTargets(sourceVersion);
        if (!allowed.contains(targetVersion)) {
            throw new AwsException("ValidationException",
                    "Cannot upgrade from " + sourceVersion + " to " + targetVersion
                            + ". Allowed targets: " + allowed, 400);
        }
    }
}
