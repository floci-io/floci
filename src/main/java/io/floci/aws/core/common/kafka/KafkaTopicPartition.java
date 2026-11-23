package io.floci.aws.core.common.kafka;

/** A Kafka topic/partition pair, used as a map key when grouping records for delivery. */
public record KafkaTopicPartition(String topic, int partition) {
}
