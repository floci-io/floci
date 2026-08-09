package io.github.hectorvent.floci.services.resourceexplorer2.query;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record ParsedQuery(
        List<Keyword> keywords,
        List<Filter> filters
) {

    @RegisterForReflection
    public record Keyword(String value, boolean negated) {}

    @RegisterForReflection
    public record Filter(
            FilterAttribute attribute,
            List<FilterValue> values,
            boolean negated
    ) {}

    @RegisterForReflection
    public record FilterValue(String value, boolean prefixMatch) {}
}
