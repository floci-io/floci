package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's storage capabilities. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageConfig(BlockStorage blockStorage) {}
