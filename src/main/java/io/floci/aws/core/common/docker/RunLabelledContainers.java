package io.floci.aws.core.common.docker;

import io.floci.aws.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The containers one service starts for work that does not outlive the process, such as builds or jobs: each carries
 * the deployment's owner label and this process's run label, so the containers an earlier run left behind (a crash
 * keeps them running, holding their network addresses, with nothing left to finish) are known and removed. Removal is
 * retried before each new container until one pass removes them all.
 */
public final class RunLabelledContainers {

    private final String service;
    private final String runLabel;
    private final EmulatorConfig config;
    private final ContainerReconciler reconciler;
    private final String runId = UUID.randomUUID().toString();
    private final AtomicBoolean leftoversRemoved = new AtomicBoolean();

    /**
     * @param service  the service label the containers carry, such as {@code batch}
     * @param runLabel the label naming the process run that created a container, such as {@code io.floci.batch.run}
     */
    public RunLabelledContainers(ContainerLifecycleManager lifecycleManager, EmulatorConfig config, String service,
                                 String runLabel, Logger log) {
        this.service = service;
        this.runLabel = runLabel;
        this.config = config;
        this.reconciler = new ContainerReconciler(lifecycleManager, log);
    }

    /** The owner and run labels a container of this run carries, beside its resource identity. */
    public Map<String, String> labels() {
        return Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                runLabel, runId);
    }

    /** Removes the service's containers an earlier run of this deployment left, until a pass removes them all. */
    public void removeLeftovers() {
        if (!leftoversRemoved.get() && reconciler.reconcile(
                Map.of(ContainerStorageHelper.SERVICE_LABEL, service,
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)),
                container -> !runId.equals(ContainerStorageHelper.CONTAINER_LABEL_ALIASES
                        .labelValue(container.getLabels(), runLabel)))) {
            leftoversRemoved.set(true);
        }
    }
}
