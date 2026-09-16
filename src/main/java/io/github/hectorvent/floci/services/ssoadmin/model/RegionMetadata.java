package io.github.hectorvent.floci.services.ssoadmin.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record RegionMetadata(
        String regionName,
        String status,
        String addedDate,
        boolean primaryRegion) {
}
