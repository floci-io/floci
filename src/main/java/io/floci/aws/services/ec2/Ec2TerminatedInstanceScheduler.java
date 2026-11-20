package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Removes terminated EC2 instances once they have been listed for the retention period. */
@ApplicationScoped
public class Ec2TerminatedInstanceScheduler extends SweepScheduler {
    private static final long SWEEP_INTERVAL_SECONDS = 60;

    @Inject
    public Ec2TerminatedInstanceScheduler(Ec2Service service, EmulatorConfig config) {
        super(new SweepWorker("ec2-terminated-instance-pruning", config.services().ec2().enabled(),
                SWEEP_INTERVAL_SECONDS, false, service::pruneTerminatedInstances));
    }
}
