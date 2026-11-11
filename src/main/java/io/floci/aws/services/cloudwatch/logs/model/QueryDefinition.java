package io.floci.aws.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Saved query metadata, independent of an executed Logs Insights query. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryDefinition(String queryDefinitionId, String name, String queryString,
                              List<String> logGroupNames, String queryLanguage,
                              List<QueryParameter> parameters, long lastModified, String clientToken) {

    public QueryDefinition {
        logGroupNames = logGroupNames == null ? List.of() : List.copyOf(logGroupNames);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryParameter(String name, String defaultValue, String description) {}
}
