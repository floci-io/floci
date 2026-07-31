package io.github.hectorvent.floci.services.pipes;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * Registers the kafka-clients classes that GraalVM cannot discover statically.
 *
 * <p>Floci uses kafka-clients directly rather than through the Quarkus Kafka extension, and
 * kafka-clients ships no native-image metadata of its own. {@code AbstractConfig} resolves every
 * {@code CLASS}-typed consumer setting by name, so without these entries a native build produces a
 * consumer that cannot be constructed. {@link PipesPoller} logs that failure and moves on, leaving
 * a Kafka or MSK pipe reported as RUNNING while it delivers nothing.
 *
 * <p>Every entry is config-resolved, and {@code PipesKafkaNativeSupportTest} derives its
 * expectations the same way, so list and guard cannot drift. The tracing agent also reported
 * {@code AppInfoParser.AppInfo}, {@code AppInfoParser.AppInfoMBean} and the shaded
 * {@code ExtensionRegistry}; native pipes deliver without them, so they are omitted.
 *
 * <p>SASL and SSL defaults are excluded: Floci is PLAINTEXT-only, and {@code DefaultJwtValidator}
 * fails the native build outright by referencing jose4j, an optional dependency Floci ships without.
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
