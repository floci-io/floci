package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SourceDetail(
        @JsonProperty("EventSource") String eventSource,
        @JsonProperty("MessageType") String messageType,
        @JsonProperty("MaximumExecutionFrequency") String maximumExecutionFrequency) {
}
