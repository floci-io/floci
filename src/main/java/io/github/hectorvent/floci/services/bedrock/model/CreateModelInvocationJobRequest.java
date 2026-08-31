package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateModelInvocationJobRequest(
        @JsonProperty("jobName") String jobName,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("roleArn") String roleArn,
        @JsonProperty("clientRequestToken") String clientRequestToken,
        @JsonProperty("inputDataConfig") InputDataConfig inputDataConfig,
        @JsonProperty("outputDataConfig") OutputDataConfig outputDataConfig,
        @JsonProperty("vpcConfig") VpcConfig vpcConfig,
        @JsonProperty("timeoutDurationInHours") Integer timeoutDurationInHours,
        @JsonProperty("tags") List<Tag> tags
) {}
