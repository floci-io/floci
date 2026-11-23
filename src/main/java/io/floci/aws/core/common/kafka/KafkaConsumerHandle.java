package io.floci.aws.core.common.kafka;

import java.net.URI;

/** A Kafka REST Proxy consumer instance: its own {@code base_uri} addresses every later call. */
public record KafkaConsumerHandle(String instanceId, URI baseUri) {
}
