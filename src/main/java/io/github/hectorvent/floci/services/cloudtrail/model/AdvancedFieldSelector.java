package io.github.hectorvent.floci.services.cloudtrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdvancedFieldSelector(
        @JsonProperty("Field") String field,
        @JsonProperty("Equals") List<String> equalsValues,
        @JsonProperty("NotEquals") List<String> notEquals,
        @JsonProperty("StartsWith") List<String> startsWith,
        @JsonProperty("NotStartsWith") List<String> notStartsWith,
        @JsonProperty("EndsWith") List<String> endsWith,
        @JsonProperty("NotEndsWith") List<String> notEndsWith) {
}
