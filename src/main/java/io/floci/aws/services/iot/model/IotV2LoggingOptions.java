package io.floci.aws.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record IotV2LoggingOptions(String roleArn, String defaultLogLevel, boolean disableAllLogs) {
}
