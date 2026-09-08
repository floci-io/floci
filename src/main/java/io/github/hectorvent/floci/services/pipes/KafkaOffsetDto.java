package io.github.hectorvent.floci.services.pipes;

/**
 * A partition offset to commit through a Kafka REST Proxy, in place of
 * {@code org.apache.kafka.common.TopicPartition} / {@code OffsetAndMetadata}. As with the native
 * client, {@code offset} is the next offset to consume (last delivered offset + 1), not the last
 * delivered offset itself.
 */
record KafkaOffsetDto(String topic, int partition, long offset) {
}
