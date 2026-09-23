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

    /**
     * The base name this emulator owns, without a trailing separator. Each Floci emulator takes
     * its own cloud token so several can share one Docker daemon without colliding on names or
     * on {@code docker volume prune} filters.
     */
    public static final String NAME_PREFIX = "floci-" + CLOUD;

    /** {@link #NAME_PREFIX} with its separator, the literal prefix of every name produced here. */
    static final String CONTAINER_PREFIX = NAME_PREFIX + "-";

    /**
     * The prefix this emulator used before it took the shared {@code floci-<cloud>-} convention.
     *
     * <p>Frozen forever. It exists only so resources created by earlier versions, above all
     * data-bearing Docker volumes, keep resolving to the names their data actually lives under.
     * Never change it, and never use it to name a newly created resource.
     */
    static final String LEGACY_PREFIX = "floci-";

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
        return dockerName(config, service + "-" + (volumeId != null ? volumeId : fallbackId));
    }

    /**
     * Prefixes {@code baseName} with {@code floci-aws-} and the configured resource namespace,
     * which lands after the cloud token: {@code floci-aws-<namespace>-<base>}.
     *
     * <p>A name that already carries a prefix, current or legacy, is normalised rather than
     * prefixed a second time. That is what lets a container be named after the legacy volume it
     * mounts: pre-migration volumes keep their old name forever, while their containers, which
     * are disposable, always take the current one.
     */
    public static String dockerName(EmulatorConfig config, String baseName) {
        return applyPrefix(CONTAINER_PREFIX, config, baseName);
    }

    /**
     * {@link #resourceName(EmulatorConfig, String, String, String)} as it behaved before the
     * {@code floci-aws-} migration. Only for resolving resources an earlier version created;
     * never for naming a new one.
     */
    public static String legacyResourceName(
            EmulatorConfig config, String service, String volumeId, String fallbackId) {
        return legacyDockerName(config, service + "-" + (volumeId != null ? volumeId : fallbackId));
    }

    /**
     * {@link #dockerName(EmulatorConfig, String)} as it behaved before the {@code floci-aws-}
     * migration. Only for resolving resources an earlier version created; never for naming a
     * new one.
     */
    public static String legacyDockerName(EmulatorConfig config, String baseName) {
        return applyPrefix(LEGACY_PREFIX, config, baseName);
    }

    private static String applyPrefix(String prefix, EmulatorConfig config, String baseName) {
        String base = stripPrefix(baseName);
        String namespace = resourceNamespace(config);
        if (namespace.isBlank() || base.startsWith(namespace + "-")) {
            return prefix + base;
        }
        return prefix + namespace + "-" + base;
    }

    /** Removes whichever prefix {@code baseName} carries, so prefixing is idempotent. */
    private static String stripPrefix(String baseName) {
        if (baseName.startsWith(CONTAINER_PREFIX)) {
            return baseName.substring(CONTAINER_PREFIX.length());
        }
        if (baseName.startsWith(LEGACY_PREFIX)) {
            return baseName.substring(LEGACY_PREFIX.length());
        }
        return baseName;
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

    /**
     * Labels tying a container to the specific AWS resource it emulates: {@code io.floci}
     * (cloud provider, for multi-cloud discovery), {@code io.floci.service},
     * {@code io.floci.resource-id}, {@code io.floci.account}, and {@code io.floci.region}.
     * Merged into a spec's own labels (never into {@link #defaultLabels}), so callers pass
     * this to {@link ContainerBuilder.Builder#withLabels}. A blank or null value omits that
     * key entirely, e.g. ECR's shared registry container has no per-resource id.
     */
    public static Map<String, String> resourceIdentityLabels(
            String service, String resourceId, String accountId, String region) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("io.floci", CLOUD);
        putIfNotBlank(labels, "io.floci.service", service);
        putIfNotBlank(labels, "io.floci.resource-id", resourceId);
        putIfNotBlank(labels, "io.floci.account", accountId);
        putIfNotBlank(labels, "io.floci.region", region);
        return labels;
    }

    private static void putIfNotBlank(Map<String, String> labels, String key, String value) {
        if (value != null && !value.isBlank()) {
            labels.put(key, value);
        }
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
        String path = config.storage().hostPersistentPath();
        if (path == null || path.isBlank()) {
            return true;
        }
        return !java.nio.file.Paths.get(path).isAbsolute() && !path.startsWith("/");
    }

    /**
     * Removes a stale container by name, including one an earlier version left under the legacy
     * prefix.
     *
     * <p>A container surviving from before the {@code floci-aws-} migration still holds its data
     * volume's lock, so a start that only cleared the current name would fail to acquire it. Both
     * names are cleared, and neither being present is fine.
     */
    public static void removeStaleContainer(
            EmulatorConfig config,
            ContainerLifecycleManager lifecycleManager,
            String containerName) {
        lifecycleManager.removeIfExists(containerName);
        String legacyName = legacyDockerName(config, containerName);
        if (!legacyName.equals(containerName)) {
            lifecycleManager.removeIfExists(legacyName);
        }
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
