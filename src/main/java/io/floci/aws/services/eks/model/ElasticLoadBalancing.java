package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's load balancing capability: whether it creates load balancers for the cluster. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElasticLoadBalancing(Boolean enabled) {

    /** Whether {@code loadBalancing} turns the load balancing capability on: absent, or not enabled, is off. */
    public static boolean loadBalancingOn(ElasticLoadBalancing loadBalancing) {
        return loadBalancing != null && Boolean.TRUE.equals(loadBalancing.enabled());
    }
}
