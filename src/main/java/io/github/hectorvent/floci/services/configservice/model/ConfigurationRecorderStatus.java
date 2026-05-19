package io.github.hectorvent.floci.services.configservice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigurationRecorderStatus(
        @JsonProperty("name") String name,
        @JsonProperty("recording") Boolean recording,
        @JsonProperty("lastStatus") String lastStatus,
        @JsonProperty("lastStartTime") Long lastStartTime,
        @JsonProperty("lastStopTime") Long lastStopTime) {
}
