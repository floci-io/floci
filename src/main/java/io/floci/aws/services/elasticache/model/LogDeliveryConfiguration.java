package io.floci.aws.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Where a replication group delivers one type of log: a CloudWatch Logs group or a Firehose delivery
 * stream, depending on {@code destinationType}. Floci delivers nothing, so it reports each one as active.
 */
@RegisterForReflection
public record LogDeliveryConfiguration(String logType,
                                       String destinationType,
                                       String logGroup,
                                       String deliveryStream,
                                       String logFormat) {
}
