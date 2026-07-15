package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS Fargate profile lifecycle status (serialized as the enum name, e.g. "ACTIVE"). */
@RegisterForReflection
public enum FargateProfileStatus {
    CREATING,
    ACTIVE,
    DELETING,
    CREATE_FAILED,
    DELETE_FAILED
}