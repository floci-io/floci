package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigRule(
        @JsonProperty("ConfigRuleName") String configRuleName,
        @JsonProperty("ConfigRuleArn") String configRuleArn,
        @JsonProperty("ConfigRuleId") String configRuleId,
        @JsonProperty("Description") String description,
        @JsonProperty("Scope") Scope scope,
        @JsonProperty("Source") ConfigRuleSource source,
        @JsonProperty("InputParameters") String inputParameters,
        @JsonProperty("MaximumExecutionFrequency") String maximumExecutionFrequency,
        @JsonProperty("ConfigRuleState") String configRuleState,
        @JsonProperty("CreatedBy") String createdBy,
        @JsonProperty("EvaluationModes") List<EvaluationModeConfiguration> evaluationModes) {
}
