package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Auto Mode's storage capabilities. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageConfig(BlockStorage blockStorage) {

    /** Whether {@code storage} turns the block storage capability on: absent, or not enabled, is off. */
    public static boolean blockStorageOn(StorageConfig storage) {
        return storage != null && storage.blockStorage() != null && Boolean.TRUE.equals(storage.blockStorage().enabled());
    }
}
