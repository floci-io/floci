package io.floci.aws.services.autoscaling;

import io.floci.aws.services.elbv2.model.TargetDescription;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;

/** Mockito matchers for the targets Auto Scaling registers with and deregisters from a target group. */
final class TargetMatchers {

    private TargetMatchers() {}

    /** Matches a target list holding {@code instanceId} and nothing else. */
    static List<TargetDescription> onlyTarget(String instanceId) {
        return exactlyTargets(instanceId);
    }

    /** Matches a target list holding exactly {@code instanceIds}, in that order. */
    static List<TargetDescription> exactlyTargets(String... instanceIds) {
        return argThat(targets -> targets.stream().map(TargetDescription::getId).toList().equals(List.of(instanceIds)));
    }
}
