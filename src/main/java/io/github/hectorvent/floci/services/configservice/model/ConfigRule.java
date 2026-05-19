package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigRule(
        @JsonProperty("ConfigRuleName") String configRuleName,
        @JsonProperty("ConfigRuleArn") String configRuleArn,
        @JsonProperty("ConfigRuleId") String configRuleId,
        @JsonProperty("ConfigRuleState") String configRuleState,
        @JsonProperty("Source") ConfigRuleSource source) {
}
