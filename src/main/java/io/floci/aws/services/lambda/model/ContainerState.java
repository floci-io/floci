package io.floci.aws.services.lambda.model;

public enum ContainerState {
    COLD,
    WARMING,
    WARM,
    BUSY,
    STOPPED
}
