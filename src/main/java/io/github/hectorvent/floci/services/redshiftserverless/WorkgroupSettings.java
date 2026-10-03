package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.services.redshiftserverless.model.ConfigParameter;
import io.github.hectorvent.floci.services.redshiftserverless.model.PricePerformanceTarget;

import java.util.List;

/**
 * The optional members shared by CreateWorkgroup and UpdateWorkgroup. A {@code null} member is one
 * the caller left out: create then applies the AWS default and update keeps the stored value.
 */
public record WorkgroupSettings(
        Integer baseCapacity,
        Integer maxCapacity,
        Boolean enhancedVpcRouting,
        Boolean publiclyAccessible,
        Boolean extraComputeForAutomaticOptimization,
        List<ConfigParameter> configParameters,
        List<String> securityGroupIds,
        List<String> subnetIds,
        Integer port,
        PricePerformanceTarget pricePerformanceTarget,
        String ipAddressType,
        String trackName) {
}
