package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

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

    public static final String SERVICE_LABEL = "io.floci.service";
    public static final String RESOURCE_ID_LABEL = "io.floci.resource-id";
    public static final String ACCOUNT_LABEL = "io.floci.account";
    public static final String REGION_LABEL = "io.floci.region";
    /** The label carrying {@link #ownerIdentity(EmulatorConfig)}. */
    public static final String OWNER_LABEL = "io.floci.owner";
    /** Names a Floci-internal part that backs no single emulated resource on its own. */
    public static final String COMPONENT_LABEL = "io.floci.component";
    /** The Floci process run that created an ECS container. */
    public static final String ECS_RUN_LABEL = "io.floci.ecs.run";
    /** Marks the proxy holding the ECS container-credentials address on a network. */
    public static final String ECS_CREDENTIALS_PROXY_LABEL = "io.floci.ecs.credentials-proxy";
    /** Marks the helper that owns a protected workload's network namespace and firewall. */
    public static final String SECURITY_GROUP_HELPER_LABEL = "io.floci.security-group.helper";
    /** Marks a workload running inside a security-group helper's network namespace. */
    public static final String SECURITY_GROUP_WORKLOAD_LABEL = "io.floci.security-group.workload";

    /**
     * The labels Floci wrote on containers before it took the {@code io.floci.*} namespace, each
     * mapped from the key that replaced it. The legacy key is still written next to its new key
     * with the same value, because containers outlive the process that labelled them and users
     * may filter on the old key. This table is the only place a legacy container key is spelled.
     */
    public static final LabelAliases CONTAINER_LABEL_ALIASES = new LabelAliases(
            Map.of(OWNER_LABEL, "floci_owner_port",
                    ECS_RUN_LABEL, "floci.ecs-run",
                    ECS_CREDENTIALS_PROXY_LABEL, "floci.ecs-task-role-credentials-proxy",
                    SECURITY_GROUP_HELPER_LABEL, "floci.security-group-helper",
                    SECURITY_GROUP_WORKLOAD_LABEL, "floci.security-group-workload",
                    COMPONENT_LABEL, "floci.component"),
            List.of());

    /**
     * {@link #CONTAINER_LABEL_ALIASES} for the Docker networks backing VPCs. The same new key can
     * have a different legacy key here: a network's owner was {@code floci_vpc_owner_port}.
     */
    public static final LabelAliases NETWORK_LABEL_ALIASES = new LabelAliases(
            Map.of(OWNER_LABEL, "floci_vpc_owner_port",
                    RESOURCE_ID_LABEL, "floci_vpc_id",
                    REGION_LABEL, "floci_vpc_region"),
            List.of(new LabelAliases.Composite(
                    Map.of(SERVICE_LABEL, "ec2", COMPONENT_LABEL, "vpc-network"), "floci_component", "ec2-vpc")));

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
     * are ignored so user configuration can never break cleanup. Every key in
     * {@link #CONTAINER_LABEL_ALIASES} and {@link #NETWORK_LABEL_ALIASES}, new and legacy, is
     * reserved too, so a user label can never fake an owner or identity a cleanup path trusts.
     */
    private static final Set<String> RESERVED_LABEL_KEYS = reservedLabelKeys();

    private static Set<String> reservedLabelKeys() {
        Set<String> keys = new LinkedHashSet<>(List.of("floci", "floci_emulator", "floci_namespace"));
        keys.addAll(CONTAINER_LABEL_ALIASES.keys());
        keys.addAll(NETWORK_LABEL_ALIASES.keys());
        return Set.copyOf(keys);
    }

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
        putIfNotBlank(labels, SERVICE_LABEL, service);
        putIfNotBlank(labels, RESOURCE_ID_LABEL, resourceId);
        putIfNotBlank(labels, ACCOUNT_LABEL, accountId);
        putIfNotBlank(labels, REGION_LABEL, region);
        return labels;
    }

    /** {@link LabelAliases#labelValue} for a container's labels. */
    public static String labelValue(Map<String, String> labels, String key) {
        return CONTAINER_LABEL_ALIASES.labelValue(labels, key);
    }

    /**
     * Identity of the Floci deployment that owns a container, stamped as {@link #OWNER_LABEL} so a
     * startup sweep collects only its own leftovers. The API port alone collides when two
     * independently namespaced Flocis share a Docker daemon on the same internal port, so the
     * resource namespace goes in front of it; an unnamespaced Floci keeps the bare port.
     */
    public static String ownerIdentity(EmulatorConfig config) {
        String namespace = config.docker() == null || config.docker().resourceNamespace() == null
                ? "" : config.docker().resourceNamespace().orElse("");
        return namespace.isBlank() ? String.valueOf(config.port()) : namespace + "/" + config.port();
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

    /**
     * One kind of Docker object's legacy label keys, each mapped from the {@code io.floci.*} key
     * that replaced it. Writers set only the new key and pass the labels through
     * {@link #withLegacyAliases}; readers go through {@link #labelValue}, {@link #matches} and
     * {@link #consistent}; a Docker-side label filter goes through {@link #listByLabels}, since a
     * filter is an AND and cannot ask for "new key or legacy key".
     */
    public static final class LabelAliases {

        /**
         * A legacy key whose single value stood for several new labels at once, such as
         * {@code floci_component=ec2-vpc} for {@code io.floci.service=ec2} plus
         * {@code io.floci.component=vpc-network}.
         */
        record Composite(Map<String, String> labels, String legacyKey, String legacyValue) {}

        private final Map<String, String> legacyByKey;
        private final List<Composite> composites;

        LabelAliases(Map<String, String> legacyByKey, List<Composite> composites) {
            this.legacyByKey = Map.copyOf(legacyByKey);
            this.composites = List.copyOf(composites);
        }

        /** Every key this table names, new and legacy. */
        public Set<String> keys() {
            Set<String> keys = new LinkedHashSet<>();
            legacyByKey.forEach((key, legacy) -> {
                keys.add(key);
                keys.add(legacy);
            });
            for (Composite composite : composites) {
                keys.addAll(composite.labels().keySet());
                keys.add(composite.legacyKey());
            }
            return keys;
        }

        /** {@code labels} plus the legacy alias of each new key present, carrying the same value. */
        public Map<String, String> withLegacyAliases(Map<String, String> labels) {
            Map<String, String> aliased = new LinkedHashMap<>(labels);
            legacyByKey.forEach((key, legacy) -> {
                String value = labels.get(key);
                if (value != null) {
                    aliased.put(legacy, value);
                }
            });
            for (Composite composite : composites) {
                if (containsAll(labels, composite.labels())) {
                    aliased.put(composite.legacyKey(), composite.legacyValue());
                }
            }
            return aliased;
        }

        /** The value under {@code key}, or under its legacy alias when {@code key} is absent. */
        public String labelValue(Map<String, String> labels, String key) {
            if (labels == null) {
                return null;
            }
            String value = labels.get(key);
            return value != null ? value : legacyValue(labels, key);
        }

        /**
         * Whether {@code labels} carry {@code expected} under {@code key}, for a decision that
         * removes or trusts the object: at least one of the key and its legacy alias is present,
         * and every one present equals {@code expected}. An object whose new and legacy values
         * disagree never matches.
         */
        public boolean matches(Map<String, String> labels, String key, String expected) {
            if (labels == null) {
                return false;
            }
            String value = labels.get(key);
            String legacy = legacyValue(labels, key);
            if (value == null && legacy == null) {
                return false;
            }
            return (value == null || value.equals(expected)) && (legacy == null || legacy.equals(expected));
        }

        /**
         * False, with a warning, when any new key and its legacy alias are both present with
         * different values. Floci always writes the two together, so a disagreement means
         * something outside Floci relabelled the object, which a destructive path then leaves alone.
         */
        public boolean consistent(String objectId, Map<String, String> labels) {
            if (labels == null) {
                return true;
            }
            Set<String> keys = new LinkedHashSet<>(legacyByKey.keySet());
            composites.forEach(composite -> keys.addAll(composite.labels().keySet()));
            for (String key : keys) {
                String value = labels.get(key);
                String legacy = legacyValue(labels, key);
                if (value != null && legacy != null && !value.equals(legacy)) {
                    LOG.warnv("Leaving Docker object {0} alone: label {1}={2} disagrees with its legacy alias ({3})",
                            objectId, key, value, legacy);
                    return false;
                }
            }
            return true;
        }

        /**
         * The label filters that find an object labelled with {@code filter}: the filter itself
         * and, when any of its keys has a legacy alias, the same filter spelled with the legacy keys.
         */
        public List<Map<String, String>> labelFilters(Map<String, String> filter) {
            Map<String, String> legacy = new LinkedHashMap<>(filter);
            for (Composite composite : composites) {
                if (containsAll(filter, composite.labels())) {
                    composite.labels().keySet().forEach(legacy::remove);
                    legacy.put(composite.legacyKey(), composite.legacyValue());
                }
            }
            filter.forEach((key, value) -> {
                String legacyKey = legacyByKey.get(key);
                if (legacyKey != null) {
                    legacy.remove(key);
                    legacy.put(legacyKey, value);
                }
            });
            return legacy.equals(filter) ? List.of(filter) : List.of(filter, legacy);
        }

        /**
         * Runs {@code query} once per filter of {@link #labelFilters} and merges the results by
         * object id, so an object labelled with the new keys, the legacy keys or both is listed once.
         */
        public <T> List<T> listByLabels(Map<String, String> filter, Function<Map<String, String>, List<T>> query,
                                        Function<T, String> idOf) {
            Map<String, T> byId = new LinkedHashMap<>();
            for (Map<String, String> variant : labelFilters(filter)) {
                List<T> found = query.apply(variant);
                if (found != null) {
                    found.forEach(object -> byId.putIfAbsent(idOf.apply(object), object));
                }
            }
            return new ArrayList<>(byId.values());
        }

        /** The value under {@code key}'s legacy alias, or null when it has none or the label is absent. */
        public String legacyValue(Map<String, String> labels, String key) {
            String legacyKey = legacyByKey.get(key);
            if (legacyKey != null) {
                return labels.get(legacyKey);
            }
            for (Composite composite : composites) {
                if (composite.labels().containsKey(key) && labels.containsKey(composite.legacyKey())) {
                    String legacy = labels.get(composite.legacyKey());
                    return composite.legacyValue().equals(legacy) ? composite.labels().get(key) : legacy;
                }
            }
            return null;
        }

        private static boolean containsAll(Map<String, String> labels, Map<String, String> expected) {
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                if (!Objects.equals(labels.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }
}
