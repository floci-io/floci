package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DeadLetterConfig {

    private String arn;

    public DeadLetterConfig() {}

    public DeadLetterConfig(String arn) {
        this.arn = arn;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
}
