package io.github.hectorvent.floci.services.pipes;

/** A Kafka topic/partition pair, used as a map key when grouping records for delivery. */
record KafkaTopicPartition(String topic, int partition) {
}
