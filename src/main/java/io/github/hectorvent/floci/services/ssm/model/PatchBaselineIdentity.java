package io.github.hectorvent.floci.services.ssm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Identity of an AWS-owned predefined patch baseline, as returned by DescribePatchBaselines.
 */
@RegisterForReflection
public record PatchBaselineIdentity(String baselineId,
                                    String baselineName,
                                    String operatingSystem,
                                    String baselineDescription,
                                    boolean defaultBaseline) {
}
