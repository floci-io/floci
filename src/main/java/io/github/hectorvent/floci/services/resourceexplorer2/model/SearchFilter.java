package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Filter string attached to a view or {@code ListResources} request. */
@RegisterForReflection
public record SearchFilter(String filterString) {}
