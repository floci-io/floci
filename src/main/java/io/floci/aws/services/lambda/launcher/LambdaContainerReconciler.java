package io.floci.aws.services.lambda.launcher;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerReconciler;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/** Reclaims this deployment's Lambda environments, which cannot survive their Runtime API server. */
final class LambdaContainerReconciler {

    static final String RUN_LABEL = "io.floci.lambda.run";
    private static final Logger LOG = Logger.getLogger(LambdaContainerReconciler.class);
    private static final ContainerStorageHelper.LabelAliases ALIASES = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;

    private final EmulatorConfig config;
    private final ContainerReconciler containers;
    private final String owner;
    private final String runId = UUID.randomUUID().toString();
    private boolean reconciled;

    LambdaContainerReconciler(EmulatorConfig config, ContainerLifecycleManager lifecycleManager) {
        this.config = config;
        this.containers = new ContainerReconciler(lifecycleManager, LOG);
        this.owner = ContainerStorageHelper.ownerIdentity(config);
    }

    Map<String, String> ownershipLabels() {
        return Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda",
                ContainerStorageHelper.OWNER_LABEL, owner, RUN_LABEL, runId);
    }

    synchronized void reconcile() {
        if (reconciled || !config.services().lambda().reconcileContainersOnStartup()) {
            return;
        }
        reconciled = containers.reconcile(
                Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda", ContainerStorageHelper.OWNER_LABEL, owner),
                container -> {
                    String previousRun = ALIASES.labelValue(container.getLabels(), RUN_LABEL);
                    return previousRun != null && !previousRun.isBlank() && !runId.equals(previousRun);
                });
    }
}
