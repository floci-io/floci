package io.floci.aws.services.autoscaling;

import io.floci.aws.services.elbv2.model.TargetDescription;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;

/** Mockito matchers for the targets Auto Scaling registers with and deregisters from a target group. */
final class TargetMatchers {

    private TargetMatchers() {}

    /** Matches a target list holding {@code instanceId} and nothing else. */
    static List<TargetDescription> onlyTarget(String instanceId) {
        return argThat(targets -> targets.size() == 1 && instanceId.equals(targets.getFirst().getId()));
    }
}
