package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AssociatedAccessPolicy(String policyArn, AccessScope accessScope, double associatedAt,
                                     double modifiedAt) {}
