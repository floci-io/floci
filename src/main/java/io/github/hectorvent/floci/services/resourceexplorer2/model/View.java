package io.github.hectorvent.floci.services.resourceexplorer2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public record View(
        String viewArn,
        String viewName,
        String owner,
        String scope,
        SearchFilter filters,
        List<IncludedProperty> includedProperties,
        Map<String, String> tags,
        Instant lastUpdatedAt) implements Taggable {

    public View withFilters(SearchFilter filters) {
        return new View(viewArn, viewName, owner, scope, filters, includedProperties, tags, lastUpdatedAt);
    }

    public View withIncludedProperties(List<IncludedProperty> includedProperties) {
        return new View(viewArn, viewName, owner, scope, filters, includedProperties, tags, lastUpdatedAt);
    }

    public View withLastUpdatedAt(Instant lastUpdatedAt) {
        return new View(viewArn, viewName, owner, scope, filters, includedProperties, tags, lastUpdatedAt);
    }

    @Override
    public View withTags(Map<String, String> tags) {
        return new View(viewArn, viewName, owner, scope, filters, includedProperties, tags, lastUpdatedAt);
    }

    /** Whether this view includes tag data in results. */
    public boolean includesTags() {
        return includedProperties != null &&
                includedProperties.stream().anyMatch(p -> "tags".equals(p.name()));
    }
}
