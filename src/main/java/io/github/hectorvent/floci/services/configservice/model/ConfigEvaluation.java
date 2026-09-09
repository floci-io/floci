package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigEvaluation(
        @JsonProperty("ComplianceResourceType") String complianceResourceType,
        @JsonProperty("ComplianceResourceId") String complianceResourceId,
        @JsonProperty("ComplianceType") String complianceType,
        @JsonProperty("Annotation") String annotation,
        @JsonProperty("OrderingTimestamp") Double orderingTimestamp,
        @JsonProperty("ResultRecordedTime") Long resultRecordedTime,
        @JsonProperty("ConfigRuleInvokedTime") Long configRuleInvokedTime) {
}
