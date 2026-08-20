package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central helper for child-container volume management across RDS, OpenSearch, MSK, and ECR.
 *
 * <p>Two modes:
 * <ul>
 *   <li>Named-volume (default) — Floci manages per-resource Docker named volumes labelled
 *       {@code floci=true}. Active when {@code FLOCI_STORAGE_HOST_PERSISTENT_PATH} is not set.</li>
 *   <li>Host-path (legacy) — active when {@code FLOCI_STORAGE_HOST_PERSISTENT_PATH} is set;
 *       callers fall through to their existing bind-mount logic.</li>
 * </ul>
 */
public final class ContainerStorageHelper {

    private static final Logger LOG = Logger.getLogger(ContainerStorageHelper.class);

    static final String CLOUD = "aws";

    private ContainerStorageHelper() {}

    /**
     * Canonical container/volume name for a resource. Uses {@code volumeId} when set;
     * falls back to {@code fallbackId} (the resource name) for resources created before
     * this change.
     */
    public static String resourceName(String service, String volumeId, String fallbackId) {
        return resourceName(null, service, volumeId, fallbackId);
    }

    public static String resourceName(EmulatorConfig config, String service, String volumeId, String fallbackId) {
        return dockerName(config, "floci-" + service + "-" + (volumeId != null ? volumeId : fallbackId));
    }

    public static String dockerName(EmulatorConfig config, String baseName) {
        String namespace = resourceNamespace(config);
        if (namespace.isBlank()) {
            return baseName;
        }
        if (baseName.startsWith("floci-")) {
            return "floci-" + namespace + "-" + baseName.substring("floci-".length());
        }
        return "floci-" + namespace + "-" + baseName;
    }

    /**
     * Like {@link #dockerName} but with a caller-supplied base prefix in place of the default
     * {@code floci}: {@code <prefix>-<rest>}, or {@code <prefix>-<namespace>-<rest>} when a
     * resource namespace is configured.
     */
    public static String prefixedDockerName(EmulatorConfig config, String prefix, String rest) {
        String namespace = resourceNamespace(config);
        if (namespace.isBlank()) {
            return prefix + "-" + rest;
        }
        return prefix + "-" + namespace + "-" + rest;
    }

    /**
     * Label keys reserved for the emulator itself. {@code floci} and {@code floci_emulator}
     * drive container/volume discovery and pruning (e.g.
     * {@code docker volume prune --filter label=floci=true}); {@code floci_namespace} scopes
     * resources when multiple Floci processes share one daemon. Extra labels using these keys
     * are ignored so user configuration can never break cleanup.
     */
    private static final java.util.Set<String> RESERVED_LABEL_KEYS =
            java.util.Set.of("floci", "floci_emulator", "floci_namespace");

    /**
     * Labels applied to every emulator-created container and volume:
     * {@code floci=true} (umbrella across all Floci emulators),
     * {@code floci_emulator=floci-aws} (per-emulator discriminator), and
     * {@code floci_namespace} when a resource namespace is configured.
     * User-configured {@code floci.docker.extra-labels} entries are included first;
     * reserved keys always win on conflict.
     */
    public static Map<String, String> defaultLabels(EmulatorConfig config) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (config != null && config.docker() != null && config.docker().extraLabels() != null) {
            for (EmulatorConfig.DockerConfig.LabelEntry entry : config.docker().extraLabels()) {
                String key = entry.key() == null ? "" : entry.key().trim();
                if (key.isEmpty() || RESERVED_LABEL_KEYS.contains(key)) {
                    LOG.warnv("Ignoring extra Docker label with {0} key: \"{1}\"",
                            key.isEmpty() ? "blank" : "reserved", key);
                    continue;
                }
                labels.put(key, entry.value() == null ? "" : entry.value());
            }
        }
        labels.put("floci", "true");
        labels.put("floci_emulator", "floci-" + CLOUD);
        String namespace = resourceNamespace(config);
        if (!namespace.isBlank()) {
            labels.put("floci_namespace", namespace);
        }
        return labels;
    }

    public static Path hostResourcePath(EmulatorConfig config, String service, String resourceId) {
        String namespace = resourceNamespace(config);
        Path base = Path.of(config.storage().hostPersistentPath());
        if (namespace.isBlank()) {
            return base.resolve(service).resolve(resourceId);
        }
        return base.resolve(namespace).resolve(service).resolve(resourceId);
    }

    private static String resourceNamespace(EmulatorConfig config) {
        if (config == null || config.docker() == null || config.docker().resourceNamespace() == null) {
            return "";
        }
        return sanitizeNamePart(config.docker().resourceNamespace().orElse(""));
    }

    private static String sanitizeNamePart(String value) {
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9_.-]+", "-");
        while (cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "";
        }
        return cleaned;
    }

    /**
     * Returns {@code true} when named-volume mode is active.
     * Returns {@code false} only when {@code FLOCI_STORAGE_HOST_PERSISTENT_PATH} is set to
     * an absolute path, indicating the caller should use a host bind-mount instead.
     * Volume names and relative paths are not supported in {@code host-persistent-path} —
     * they are treated as named-volume mode.
     */
    public static boolean isNamedVolumeMode(EmulatorConfig config) {
        return !config.storage().hostPersistentPath().startsWith("/");
    }

    /**
     * Ensures the named volume exists and mounts it to {@code internalMount} in the container.
     * Must only be called when {@link #isNamedVolumeMode} returns {@code true}.
     */
    public static void applyStorage(
            ContainerBuilder.Builder builder,
            ContainerLifecycleManager lifecycleManager,
            EmulatorConfig config,
            String service,
            String volumeId,
            String fallbackId,
            String internalMount) {
        String volumeName = resourceName(config, service, volumeId, fallbackId);
        applyNamedVolume(builder, lifecycleManager, volumeName, internalMount);
    }

    /** Mounts a persisted, exact Docker volume name without applying namespace rules again. */
    public static void applyNamedVolume(
            ContainerBuilder.Builder builder,
            ContainerLifecycleManager lifecycleManager,
            String volumeName,
            String internalMount) {
        lifecycleManager.ensureVolume(volumeName);
        builder.withNamedVolume(volumeName, internalMount);
    }

    /**
     * Removes the named volume on resource delete, honouring the configured prune policy.
     *
     * <ul>
     *   <li>In {@code memory} storage mode: always removes (data cannot survive a restart anyway).</li>
     *   <li>In persistent modes: removes only when {@code prune-volumes-on-delete: true}.</li>
     * </ul>
     */
    public static void removeStorage(
            EmulatorConfig config,
            ContainerLifecycleManager lifecycleManager,
            String service,
            String volumeId,
            String fallbackId) {
        String volumeName = resourceName(config, service, volumeId, fallbackId);
        removeNamedVolume(config, lifecycleManager, volumeName);
    }

    /** Removes or retains a persisted, exact Docker volume name according to storage policy. */
    public static void removeNamedVolume(
            EmulatorConfig config,
            ContainerLifecycleManager lifecycleManager,
            String volumeName) {
        boolean isMemory = "memory".equals(config.storage().mode());
        if (isMemory || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolume(volumeName);
        } else {
            LOG.infov("Retained Docker volume {0}. Remove manually: docker volume rm {0}", volumeName);
        }
    }

    /**
     * Removes an exact named volume according to storage policy and propagates Docker failures.
     */
    public static void removeNamedVolumeStrict(
            EmulatorConfig config,
            ContainerLifecycleManager lifecycleManager,
            String volumeName) {
        boolean isMemory = "memory".equals(config.storage().mode());
        if (isMemory || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolumeStrict(volumeName);
        } else {
            LOG.infov("Retained Docker volume {0}. Remove manually: docker volume rm {0}",
                    volumeName);
        }
    }

    /**
     * Ensures the host data directory exists for host-path mode (absolute paths only).
     * Called by managers in their legacy host-path code paths.
     */
    public static void ensureHostDir(String hostDataPath) {
        try {
            Files.createDirectories(Path.of(hostDataPath));
        } catch (IOException e) {
            LOG.errorv("Failed to create data directory {0}: {1}", hostDataPath, e.getMessage());
        }
    }
}
