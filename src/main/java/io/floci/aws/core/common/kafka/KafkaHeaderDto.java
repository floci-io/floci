package io.floci.aws.core.common.kafka;

/** One Kafka record header, as decoded from a Kafka REST Proxy record response. */
public record KafkaHeaderDto(String key, byte[] value) {
}
