package io.floci.aws.core.common.kafka;

/**
 * The offsets a partition holds right now: {@code beginningOffset} is the first offset still on the broker
 * and {@code endOffset} the offset the next record will get, so the partition is empty when they are equal.
 */
public record KafkaPartitionOffsets(long beginningOffset, long endOffset) {
}
