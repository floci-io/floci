package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record CustomerManagedPolicyReference(String name, String path) {
}
