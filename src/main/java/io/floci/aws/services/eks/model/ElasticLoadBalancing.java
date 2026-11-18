package io.floci.aws.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's load balancing capability: whether it creates load balancers for the cluster. */
@RegisterForReflection
public record ElasticLoadBalancing(Boolean enabled) {}
