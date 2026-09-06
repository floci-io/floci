package io.github.hectorvent.floci.services.pipes;

import java.net.URI;

/** A Kafka REST Proxy consumer instance: its own {@code base_uri} addresses every later call. */
record KafkaConsumerHandle(String instanceId, URI baseUri) {
}
