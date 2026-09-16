package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record ApplicationAccessScope(String applicationArn, String scope, List<String> authorizedTargets) {
    public ApplicationAccessScope {
        authorizedTargets = authorizedTargets == null ? List.of() : List.copyOf(authorizedTargets);
    }
}
