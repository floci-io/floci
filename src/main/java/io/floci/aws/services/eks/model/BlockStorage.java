package io.floci.aws.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's block storage capability: whether it provisions EBS volumes for the cluster. */
@RegisterForReflection
public record BlockStorage(Boolean enabled) {}
