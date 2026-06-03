package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The network configuration for an ECS service. Required for task definitions that use
 * the {@code awsvpc} network mode so each task receives its own elastic network interface.
 */
@RegisterForReflection
public class NetworkConfiguration {

    private AwsVpcConfiguration awsvpcConfiguration;

    public NetworkConfiguration() {}

    public AwsVpcConfiguration getAwsvpcConfiguration() { return awsvpcConfiguration; }
    public void setAwsvpcConfiguration(AwsVpcConfiguration awsvpcConfiguration) {
        this.awsvpcConfiguration = awsvpcConfiguration;
    }
}
