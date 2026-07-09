package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class NetworkConfiguration {

    private AwsVpcConfiguration awsvpcConfiguration;

    public AwsVpcConfiguration getAwsvpcConfiguration() { return awsvpcConfiguration; }
    public void setAwsvpcConfiguration(AwsVpcConfiguration awsvpcConfiguration) { this.awsvpcConfiguration = awsvpcConfiguration; }
}
