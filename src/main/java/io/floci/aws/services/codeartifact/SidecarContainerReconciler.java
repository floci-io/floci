package io.floci.aws.services.codeartifact;

import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PerKeyContainerPool;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reclaims a repository's abandoned starts while its pool serializes replacement for that key. */
final class SidecarContainerReconciler {

    private SidecarContainerReconciler() {}

    static void reconcile(EmulatorConfig config, ContainerLifecycleManager lifecycle,
                          PerKeyContainerPool pool, String key, String baseName) {
        String currentName = ContainerStorageHelper.dockerName(config, baseName);
        String legacyName = ContainerStorageHelper.legacyDockerName(config, baseName);
        String namespace = ContainerStorageHelper.defaultLabels(config).get("floci_namespace");
        RuntimeException failure = null;
        for (Container container : lifecycle.listContainersForReconciliation()) {
            Map<String, String> labels = container.getLabels();
            if (labels == null || !"true".equals(labels.get("floci"))
                    || !"floci-aws".equals(labels.get("floci_emulator"))
                    || !Objects.equals(namespace, labels.get("floci_namespace"))
                    || container.getId() == null || pool.tracksContainer(key, container.getId())
                    || !matches(container.getNames(), currentName, legacyName)) {
                continue;
            }
            try {
                lifecycle.removeIfExistsStrict(container.getId(), true);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static boolean matches(String[] names, String current, String legacy) {
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String normalized = name.startsWith("/") ? name.substring(1) : name;
            if (matches(normalized, current) || matches(normalized, legacy)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String name, String base) {
        if (name.equals(base)) {
            return true;
        }
        if (!name.startsWith(base + "-")) {
            return false;
        }
        String suffix = name.substring(base.length() + 1);
        try {
            return UUID.fromString(suffix).toString().equals(suffix);
        } catch (IllegalArgumentException ignored) {
            // Similar prefixes belonging to another repository are not owned by this key.
            return false;
        }
    }
}
