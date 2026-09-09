package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A property included in search results. Currently only {@code "tags"} is supported. */
@RegisterForReflection
public record IncludedProperty(String name) {}
