package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.model.Container;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Removes owned Docker leftovers, retaining each caller's run-label eligibility policy. */
public final class ContainerReconciler {

    private static final ContainerStorageHelper.LabelAliases ALIASES = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;

    private final ContainerLifecycleManager lifecycleManager;
    private final Logger log;
    private final Set<String> removalFailuresReported = new HashSet<>();
    private boolean listFailureReported;

    public ContainerReconciler(ContainerLifecycleManager lifecycleManager, Logger log) {
        this.lifecycleManager = lifecycleManager;
        this.log = log;
    }

    /**
     * Returns whether every eligible leftover was removed. Filters must identify a service and
     * owner; both current and legacy label spellings are inventoried before anything is removed.
     * Each failed listing or removal warns once, then logs at DEBUG until it recovers.
     */
    public synchronized boolean reconcile(Map<String, String> filter, Predicate<Container> isLeftover) {
        if (!filter.containsKey(ContainerStorageHelper.SERVICE_LABEL)
                || !filter.containsKey(ContainerStorageHelper.OWNER_LABEL)) {
            throw new IllegalArgumentException("Container reconciliation requires service and owner labels");
        }
        List<Container> containers;
        try {
            containers = ALIASES.listByLabels(filter,
                    labels -> Objects.requireNonNull(lifecycleManager.getDockerClient().listContainersCmd()
                            .withShowAll(true).withLabelFilter(labels).exec(), "Missing Docker container inventory"),
                    Container::getId);
        } catch (RuntimeException failure) {
            log.logv(listFailureReported ? Logger.Level.DEBUG : Logger.Level.WARN, failure,
                    "Could not list leftover containers matching {0}", filter);
            listFailureReported = true;
            return false;
        }
        listFailureReported = false;
        boolean complete = true;
        for (Container container : containers) {
            Map<String, String> labels = container.getLabels();
            if (!ALIASES.consistent(container.getId(), labels)
                    || !filter.entrySet().stream().allMatch(entry -> ALIASES.matches(labels,
                            entry.getKey(), entry.getValue()))
                    || !isLeftover.test(container)) {
                continue;
            }
            try {
                lifecycleManager.removeIfExistsStrict(container.getId(), true);
                removalFailuresReported.remove(container.getId());
                log.infov("Removed {0} container {1} left by a previous run",
                        filter.get(ContainerStorageHelper.SERVICE_LABEL), container.getId());
            } catch (RuntimeException failure) {
                complete = false;
                Logger.Level level = removalFailuresReported.add(container.getId())
                        ? Logger.Level.WARN : Logger.Level.DEBUG;
                log.logv(level, failure, "Could not remove leftover container {0}", container.getId());
            }
        }
        if (complete) {
            removalFailuresReported.clear();
        }
        return complete;
    }
}
