package io.github.hectorvent.floci.services.pipes;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * Registers the kafka-clients classes that GraalVM cannot discover statically.
 *
 * <p>kafka-clients ships no native-image metadata and Floci uses it directly rather than through
 * the Quarkus Kafka extension. {@code AbstractConfig} resolves {@code CLASS}-typed settings by
 * name, so without these a native build cannot construct a consumer — which {@link PipesPoller}
 * swallows per poll cycle, leaving the pipe RUNNING and delivering nothing.
 *
 * <p>Entries are exactly what {@link PipesKafkaConsumerManager#consumerProperties} resolves, and
 * {@code PipesKafkaNativeSupportTest} reads that same method, so the two cannot drift.
 *
 * <p>SASL and SSL defaults are excluded: Floci is PLAINTEXT-only, and {@code DefaultJwtValidator}
 * fails the native build by referencing jose4j, an optional dependency Floci omits.
 */
@RegisterForReflection(targets = {
    // key.deserializer / value.deserializer — set explicitly by PipesKafkaConsumerManager
    ByteArrayDeserializer.class,
    // partition.assignment.strategy — ConsumerConfig defaults
    RangeAssignor.class,
    CooperativeStickyAssignor.class,
    // metric.reporters — ConsumerConfig default
    JmxReporter.class
})
public class PipesKafkaNativeSupport {
}
