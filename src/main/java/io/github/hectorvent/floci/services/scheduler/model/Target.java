package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Target {

    private String arn;
    private String roleArn;
    private String input;
    private RetryPolicy retryPolicy;
    private DeadLetterConfig deadLetterConfig;
    private SqsParameters sqsParameters;
    private EcsParameters ecsParameters;
    private EventBridgeParameters eventBridgeParameters;

    public Target() {}

    public Target(String arn, String roleArn, String input, RetryPolicy retryPolicy) {
        this.arn = arn;
        this.roleArn = roleArn;
        this.input = input;
        this.retryPolicy = retryPolicy;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; }

    public DeadLetterConfig getDeadLetterConfig() { return deadLetterConfig; }
    public void setDeadLetterConfig(DeadLetterConfig deadLetterConfig) { this.deadLetterConfig = deadLetterConfig; }

    public SqsParameters getSqsParameters() { return sqsParameters; }
    public void setSqsParameters(SqsParameters sqsParameters) { this.sqsParameters = sqsParameters; }

    public EcsParameters getEcsParameters() { return ecsParameters; }
    public void setEcsParameters(EcsParameters ecsParameters) { this.ecsParameters = ecsParameters; }

    public EventBridgeParameters getEventBridgeParameters() { return eventBridgeParameters; }
    public void setEventBridgeParameters(EventBridgeParameters eventBridgeParameters) { this.eventBridgeParameters = eventBridgeParameters; }
}
