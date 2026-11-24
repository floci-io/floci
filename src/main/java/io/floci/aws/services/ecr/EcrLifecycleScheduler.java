package io.floci.aws.services.ecr;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Applies repository lifecycle policies in the background. ECR expires an image within a day of it meeting a rule
 * rather than on any request, so a sweep on a fixed interval matches that and leaves nothing to reconcile after a
 * restart.
 */
@ApplicationScoped
public class EcrLifecycleScheduler extends SweepScheduler {

    @Inject
    public EcrLifecycleScheduler(EcrService service, EmulatorConfig config) {
        super(new SweepWorker("ecr-lifecycle-sweep",
                config.services().ecr().enabled() && config.services().ecr().lifecycleSweepEnabled(),
                config.services().ecr().lifecycleSweepIntervalSeconds(), false, service::applyLifecyclePolicies));
    }
}
