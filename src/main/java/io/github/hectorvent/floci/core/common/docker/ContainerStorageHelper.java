package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private ContainerStorageHelper() {}

    /**
     * Canonical container/volume name for a resource. Uses {@code volumeId} when set;
     * falls back to {@code fallbackId} (the resource name) for resources created before
     * this change.
     */
    public static String resourceName(String service, String volumeId, String fallbackId) {
        return "floci-" + service + "-" + (volumeId != null ? volumeId : fallbackId);
    }

    /**
     * Returns {@code true} when named-volume mode is active.
     * Returns {@code false} only when {@code FLOCI_STORAGE_HOST_PERSISTENT_PATH} is set to
     * an absolute path, indicating the caller should use a host bind-mount instead.
     * Volume names and relative paths are not supported in {@code host-persistent-path} —
     * they are treated as named-volume mode.
     */
    public static boolean isNamedVolumeMode(EmulatorConfig config) {
        String hostPath = config.storage().hostPersistentPath();
        if (hostPath.equals("/var/lib/floci") || hostPath.equals("/app/data")) {
            return true;
        }
        return !hostPath.startsWith("/");
    }

    /**
     * Ensures the named volume exists and mounts it to {@code internalMount} in the container.
     * Must only be called when {@link #isNamedVolumeMode} returns {@code true}.
     */
    public static void applyStorage(
            ContainerBuilder.Builder builder,
            ContainerLifecycleManager lifecycleManager,
            String service,
            String volumeId,
            String fallbackId,
            String internalMount) {
        String volumeName = resourceName(service, volumeId, fallbackId);
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
        String volumeName = resourceName(service, volumeId, fallbackId);
        boolean isMemory = "memory".equals(config.storage().mode());
        if (isMemory || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolume(volumeName);
        } else {
            LOG.infov("Retained Docker volume {0}. Remove manually: docker volume rm {0}", volumeName);
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
