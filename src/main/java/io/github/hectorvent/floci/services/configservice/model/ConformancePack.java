package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConformancePack(
        @JsonProperty("ConformancePackName") String conformancePackName,
        @JsonProperty("ConformancePackArn") String conformancePackArn,
        @JsonProperty("ConformancePackId") String conformancePackId,
        @JsonProperty("TemplateS3Uri") String templateS3Uri,
        @JsonProperty("TemplateBody") String templateBody) {
}
