package io.floci.aws.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's storage capabilities. */
@RegisterForReflection
public record StorageConfig(BlockStorage blockStorage) {}
