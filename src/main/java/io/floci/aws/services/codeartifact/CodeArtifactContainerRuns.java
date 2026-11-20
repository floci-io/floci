package io.floci.aws.services.codeartifact;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.RunLabelledContainers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/** One process identity shared by all repository formats, including their admitted predecessors. */
@ApplicationScoped
public class CodeArtifactContainerRuns {

    private static final Logger LOG = Logger.getLogger(CodeArtifactContainerRuns.class);
    private final RunLabelledContainers containers;

    @Inject
    public CodeArtifactContainerRuns(ContainerLifecycleManager lifecycleManager, EmulatorConfig config) {
        containers = new RunLabelledContainers(lifecycleManager, config, "codeartifact", "io.floci.codeartifact.run", LOG);
    }

    @Produces
    @Singleton
    @Named("codeartifact")
    public RunLabelledContainers containers() {
        return containers;
    }
}
