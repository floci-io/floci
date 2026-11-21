package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's block storage capability: whether it provisions EBS volumes for the cluster. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockStorage(Boolean enabled) {}
