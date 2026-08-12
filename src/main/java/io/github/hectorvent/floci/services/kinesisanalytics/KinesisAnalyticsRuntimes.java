package io.github.hectorvent.floci.services.kinesisanalytics;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog of Managed Service for Apache Flink runtime environments Floci supports, mapping each
 * AWS-shaped {@code RuntimeEnvironment} (e.g. {@code FLINK-1_18}) to the Docker image tag that
 * {@link container.FlinkContainerManager} launches for the JobManager.
 *
 * <p>Modelled on {@code services/opensearch/OpenSearchVersions}: without this map every application
 * would boot whatever {@code apache/flink:1.18} resolved to regardless of the requested
 * {@code RuntimeEnvironment} — a footgun for anyone testing version-specific Flink behaviour. Images
 * are pinned to the {@code major.minor} tag (guaranteed to exist on Docker Hub; e.g.
 * {@code apache/flink:1.18} resolves to a concrete 1.18.x).
 *
 * <p>Only the {@code FLINK-1_x} runtimes are in scope — they map to a plain Flink JobManager image.
 * The {@code SQL-1_0} and {@code ZEPPELIN-FLINK-*} (Studio) runtimes have no equivalent stock image
 * and are rejected by {@link #validate(String)}.
 */
public final class KinesisAnalyticsRuntimes {

    /** Default runtime when {@code CreateApplication} omits {@code RuntimeEnvironment}. AWS actually
     *  requires the field, so this is only a defensive fallback for {@link #imageFor(String)}. */
    public static final String DEFAULT_RUNTIME = "FLINK-1_20";

    private static final Map<String, String> RUNTIME_TO_IMAGE = new LinkedHashMap<>();

    static {
        // Pinned to major.minor tags — reproducible and guaranteed present on Docker Hub. The set
        // mirrors the runtimes AWS Managed Service for Apache Flink offers: the 2.x line, plus the
        // supported 1.x versions. AWS does not offer FLINK-1_16 / FLINK-1_17, so they are omitted.
        RUNTIME_TO_IMAGE.put("FLINK-2_3", "apache/flink:2.3");
        RUNTIME_TO_IMAGE.put("FLINK-2_2", "apache/flink:2.2");
        RUNTIME_TO_IMAGE.put("FLINK-2_1", "apache/flink:2.1");
        RUNTIME_TO_IMAGE.put("FLINK-2_0", "apache/flink:2.0");
        RUNTIME_TO_IMAGE.put("FLINK-1_20", "apache/flink:1.20");
        RUNTIME_TO_IMAGE.put("FLINK-1_19", "apache/flink:1.19");
        RUNTIME_TO_IMAGE.put("FLINK-1_18", "apache/flink:1.18");
        RUNTIME_TO_IMAGE.put("FLINK-1_15", "apache/flink:1.15");
    }

    private KinesisAnalyticsRuntimes() {
    }

    /** Runtimes Floci supports, newest-first. */
    public static List<String> supportedRuntimes() {
        return List.copyOf(RUNTIME_TO_IMAGE.keySet());
    }

    /**
     * Pick the image for an application, honouring an optional operator-supplied override that
     * applies to every application regardless of {@code runtimeEnvironment} (private registry mirror,
     * pinned patch). When the override is absent this delegates to {@link #imageFor(String)}.
     */
    public static String resolveImage(Optional<String> override, String runtimeEnvironment) {
        return override.filter(o -> !o.isBlank()).orElseGet(() -> imageFor(runtimeEnvironment));
    }

    /**
     * Docker image to launch for {@code runtimeEnvironment}. Falls back to the default-runtime image
     * only when {@code runtimeEnvironment} is null/blank; unsupported runtimes throw — call
     * {@link #validate(String)} first.
     */
    public static String imageFor(String runtimeEnvironment) {
        if (runtimeEnvironment == null || runtimeEnvironment.isBlank()) {
            return RUNTIME_TO_IMAGE.get(DEFAULT_RUNTIME);
        }
        String image = RUNTIME_TO_IMAGE.get(runtimeEnvironment);
        if (image == null) {
            throw new AwsException("InvalidArgumentException",
                    "Unsupported RuntimeEnvironment: " + runtimeEnvironment
                            + ". Supported: " + RUNTIME_TO_IMAGE.keySet(), 400);
        }
        return image;
    }

    /**
     * Throws {@code InvalidArgumentException} when {@code runtimeEnvironment} is set but not supported.
     * A null/blank value is allowed — callers that accept an omitted value fall back to
     * {@link #DEFAULT_RUNTIME}. (Floci's service requires the field, so blanks are rejected upstream.)
     */
    public static void validate(String runtimeEnvironment) {
        if (runtimeEnvironment == null || runtimeEnvironment.isBlank()) {
            return;
        }
        if (!RUNTIME_TO_IMAGE.containsKey(runtimeEnvironment)) {
            throw new AwsException("InvalidArgumentException",
                    "Unsupported RuntimeEnvironment: " + runtimeEnvironment
                            + ". Supported: " + RUNTIME_TO_IMAGE.keySet(), 400);
        }
    }
}
