package io.github.hectorvent.floci.services.pipes;

/** One Kafka record header, as decoded from a Kafka REST Proxy record response. */
record KafkaHeaderDto(String key, byte[] value) {
}
