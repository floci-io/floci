package io.floci.aws.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Where a replication group delivers one type of log: a CloudWatch Logs group or a Firehose delivery
 * stream, depending on {@code destinationType}. Floci delivers nothing, so a configuration is active
 * unless its destination is missing, when it is an error saying so in {@code message}.
 */
@RegisterForReflection
public record LogDeliveryConfiguration(String logType,
                                       String destinationType,
                                       String logGroup,
                                       String deliveryStream,
                                       String logFormat,
                                       String status,
                                       String message) {

    /** This configuration settled to an error, as AWS reports a destination it cannot deliver to. */
    public LogDeliveryConfiguration failed(String reason) {
        return new LogDeliveryConfiguration(logType, destinationType, logGroup, deliveryStream, logFormat,
                "error", reason);
    }
}
