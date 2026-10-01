package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Target {

    private String id;
    private String arn;
    private String input;
    private String inputPath;
    private InputTransformer inputTransformer;
    private SqsParameters sqsParameters;
    private BatchParameters batchParameters;
    private EcsParameters ecsParameters;
    private RetryPolicy retryPolicy;
    private DeadLetterConfig deadLetterConfig;

    public Target() {}

    public Target(String id, String arn, String input, String inputPath) {
        this.id = id;
        this.arn = arn;
        this.input = input;
        this.inputPath = inputPath;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getInputPath() { return inputPath; }
    public void setInputPath(String inputPath) { this.inputPath = inputPath; }

    public InputTransformer getInputTransformer() { return inputTransformer; }
    public void setInputTransformer(InputTransformer inputTransformer) { this.inputTransformer = inputTransformer; }

    public SqsParameters getSqsParameters() { return sqsParameters; }
    public void setSqsParameters(SqsParameters sqsParameters) { this.sqsParameters = sqsParameters; }

    public BatchParameters getBatchParameters() { return batchParameters; }
    public void setBatchParameters(BatchParameters batchParameters) { this.batchParameters = batchParameters; }

    public EcsParameters getEcsParameters() { return ecsParameters; }
    public void setEcsParameters(EcsParameters ecsParameters) { this.ecsParameters = ecsParameters; }

    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; }

    public DeadLetterConfig getDeadLetterConfig() { return deadLetterConfig; }
    public void setDeadLetterConfig(DeadLetterConfig deadLetterConfig) { this.deadLetterConfig = deadLetterConfig; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RetryPolicy(@JsonProperty("MaximumRetryAttempts") Integer maximumRetryAttempts,
                              @JsonProperty("MaximumEventAgeInSeconds") Integer maximumEventAgeInSeconds) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeadLetterConfig(@JsonProperty("Arn") String arn) {}
}
