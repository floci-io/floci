package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scope(
        @JsonProperty("ComplianceResourceTypes") List<String> complianceResourceTypes,
        @JsonProperty("TagKey") String tagKey,
        @JsonProperty("TagValue") String tagValue,
        @JsonProperty("ComplianceResourceId") String complianceResourceId) {
}
