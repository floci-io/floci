package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigRuleSource(
        @JsonProperty("Owner") String owner,
        @JsonProperty("SourceIdentifier") String sourceIdentifier,
        @JsonProperty("SourceDetails") List<SourceDetail> sourceDetails,
        @JsonProperty("CustomPolicyDetails") CustomPolicyDetails customPolicyDetails) {
}
