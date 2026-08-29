package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationResult(
        @JsonProperty("EvaluationResultIdentifier") EvaluationResultIdentifier evaluationResultIdentifier,
        @JsonProperty("ComplianceType") String complianceType,
        @JsonProperty("ResultRecordedTime") Long resultRecordedTime,
        @JsonProperty("ConfigRuleInvokedTime") Long configRuleInvokedTime,
        @JsonProperty("Annotation") String annotation,
        @JsonProperty("ResultToken") String resultToken) {
}
