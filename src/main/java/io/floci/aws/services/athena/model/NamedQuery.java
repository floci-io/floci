package io.floci.aws.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record NamedQuery(
        @JsonProperty("NamedQueryId") String namedQueryId,
        @JsonProperty("Name") String name,
        @JsonProperty("Database") String database,
        @JsonProperty("QueryString") String queryString,
        @JsonProperty("Description") String description,
        @JsonProperty("WorkGroup") String workGroup) {
}
