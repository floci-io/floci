package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS managed node group lifecycle status (serialized as the enum name, e.g. "ACTIVE"). */
@RegisterForReflection
public enum NodegroupStatus {
    CREATING,
    ACTIVE,
    UPDATING,
    DELETING,
    CREATE_FAILED,
    DELETE_FAILED,
    DEGRADED
}
