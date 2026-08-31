package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateModelInvocationJobResponse(
        @JsonProperty("jobArn") String jobArn
) {}
