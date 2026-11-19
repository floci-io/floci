package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's load balancing capability: whether it creates load balancers for the cluster. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElasticLoadBalancing(Boolean enabled) {}
