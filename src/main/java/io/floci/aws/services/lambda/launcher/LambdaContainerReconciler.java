package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reclaims this deployment's Lambda environments, which cannot survive their Runtime API server. */
final class LambdaContainerReconciler {

    static final String RUN_LABEL = "io.floci.lambda.run";
    private static final Logger LOG = Logger.getLogger(LambdaContainerReconciler.class);
    private static final ContainerStorageHelper.LabelAliases ALIASES = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;

    private final ContainerLifecycleManager lifecycleManager;
    private final String owner;
    private final String runId = UUID.randomUUID().toString();
    private boolean reconciled;

    LambdaContainerReconciler(EmulatorConfig config, ContainerLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
        this.owner = ContainerStorageHelper.ownerIdentity(config);
    }

    Map<String, String> ownershipLabels() {
        return Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda",
                ContainerStorageHelper.OWNER_LABEL, owner, RUN_LABEL, runId);
    }

    synchronized void reconcile() {
        if (reconciled) {
            return;
        }
        List<Container> containers;
        try {
            containers = ALIASES.listByLabels(
                    Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda", ContainerStorageHelper.OWNER_LABEL, owner),
                    filter -> Objects.requireNonNull(lifecycleManager.getDockerClient().listContainersCmd()
                            .withShowAll(true).withLabelFilter(filter).exec(), "Missing Docker container inventory"),
                    Container::getId);
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "Could not list leftover Lambda containers for owner {0}", owner);
            return;
        }
        boolean complete = true;
        for (Container container : containers) {
            Map<String, String> labels = container.getLabels();
            String previousRun = ALIASES.labelValue(labels, RUN_LABEL);
            if (!ALIASES.consistent(container.getId(), labels)
                    || !ALIASES.matches(labels, ContainerStorageHelper.SERVICE_LABEL, "lambda")
                    || !ALIASES.matches(labels, ContainerStorageHelper.OWNER_LABEL, owner)
                    || previousRun == null || previousRun.isBlank() || runId.equals(previousRun)) {
                continue;
            }
            try {
                lifecycleManager.removeIfExistsStrict(container.getId(), true);
                LOG.infov("Removed Lambda container {0} left by a previous run", container.getId());
            } catch (RuntimeException failure) {
                complete = false;
                LOG.warnv(failure, "Could not remove leftover Lambda container {0}", container.getId());
            }
        }
        reconciled = complete;
    }
}
