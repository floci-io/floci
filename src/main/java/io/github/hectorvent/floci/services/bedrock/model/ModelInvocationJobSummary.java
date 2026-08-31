package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelInvocationJobSummary(
        @JsonProperty("clientRequestToken") String clientRequestToken,
        @JsonProperty("endTime") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant endTime,
        @JsonProperty("inputDataConfig") InputDataConfig inputDataConfig,
        @JsonProperty("jobArn") String jobArn,
        @JsonProperty("jobExpirationTime") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant jobExpirationTime,
        @JsonProperty("jobName") String jobName,
        @JsonProperty("lastModifiedTime") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastModifiedTime,
        @JsonProperty("message") String message,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("outputDataConfig") OutputDataConfig outputDataConfig,
        @JsonProperty("roleArn") String roleArn,
        @JsonProperty("status") ModelInvocationJobStatus status,
        @JsonProperty("submitTime") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant submitTime,
        @JsonProperty("timeoutDurationInHours") Integer timeoutDurationInHours,
        @JsonProperty("vpcConfig") VpcConfig vpcConfig
) {
    public static ModelInvocationJobSummary from(ModelInvocationJob job) {
        if (job == null) {
            return null;
        }
        return new ModelInvocationJobSummary(
                job.getClientRequestToken(),
                job.getEndTime(),
                job.getInputDataConfig(),
                job.getJobArn(),
                job.getJobExpirationTime(),
                job.getJobName(),
                job.getLastModifiedTime(),
                job.getMessage(),
                job.getModelId(),
                job.getOutputDataConfig(),
                job.getRoleArn(),
                job.getStatus(),
                job.getSubmitTime(),
                job.getTimeoutDurationInHours(),
                job.getVpcConfig()
        );
    }
}
