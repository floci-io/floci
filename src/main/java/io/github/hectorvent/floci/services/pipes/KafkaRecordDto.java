package io.github.hectorvent.floci.services.pipes;

import java.util.List;

/**
 * One Kafka record as returned by a Kafka REST Proxy (Karapace, Pandaproxy) consumer poll, in
 * place of {@code org.apache.kafka.clients.consumer.ConsumerRecord} so Floci's own process never
 * needs {@code kafka-clients} on its classpath. See #2916.
 *
 * <p>{@code timestampType} is not part of the REST Proxy record schema (Karapace reads it
 * internally but does not expose it), so it is always reported as {@code CreateTime}, the default
 * for any topic that does not explicitly configure {@code message.timestamp.type}. This is a
 * documented, intentional deviation from the native Kafka protocol's per-record fidelity.
 */
record KafkaRecordDto(
        String topic,
        int partition,
        long offset,
        long timestamp,
        String timestampType,
        byte[] key,
        byte[] value,
        List<KafkaHeaderDto> headers) {

    static final String DEFAULT_TIMESTAMP_TYPE = "CreateTime";
}
