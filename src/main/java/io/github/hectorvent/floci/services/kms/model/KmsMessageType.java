package io.github.hectorvent.floci.services.kms.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum KmsMessageType {
    RAW, DIGEST;

    public static KmsMessageType fromString(String messageType) {
        try {
            return KmsMessageType.valueOf(messageType);
        } catch (IllegalArgumentException _) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + messageType + "' at 'MessageType' failed to satisfy constraint", 400);
        }
    }
}
