package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A CloudWatch alarm an environment names for AppConfig to watch during deployments. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Monitor(@JsonProperty("AlarmArn") String alarmArn,
                      @JsonProperty("AlarmRoleArn") String alarmRoleArn) {
}
