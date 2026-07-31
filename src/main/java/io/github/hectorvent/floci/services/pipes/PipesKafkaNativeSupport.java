package io.github.hectorvent.floci.services.pipes;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.shaded.com.google.protobuf.ExtensionRegistry;

/**
 * Registers the kafka-clients classes that GraalVM cannot discover statically.
 *
 * <p>Floci uses kafka-clients directly rather than through the Quarkus Kafka extension, and
 * kafka-clients ships no native-image metadata of its own. {@code AbstractConfig} resolves every
 * {@code CLASS}-typed consumer setting by name, so without these entries a native build produces a
 * consumer that cannot be constructed. {@link PipesPoller} logs that failure and moves on, leaving
 * a Kafka or MSK pipe reported as RUNNING while it delivers nothing.
 *
 * <p>{@code sasl.*} and {@code ssl.*} defaults are excluded deliberately. Floci connects over
 * PLAINTEXT so they are never instantiated, and registering {@code DefaultJwtValidator} aborts the
 * native build outright: its constructor references jose4j, an optional kafka-clients dependency
 * Floci does not ship. {@code PipesKafkaNativeSupportTest} guards both directions.
 */
@RegisterForReflection(targets = {
    // key.deserializer / value.deserializer — set explicitly by PipesKafkaConsumerManager
    ByteArrayDeserializer.class,
    // partition.assignment.strategy — ConsumerConfig defaults
    RangeAssignor.class,
    CooperativeStickyAssignor.class,
    // metric.reporters — ConsumerConfig default
    JmxReporter.class,
    // MBean registered by AppInfoParser on consumer startup
    AppInfoParser.AppInfo.class,
    AppInfoParser.AppInfoMBean.class,
    // shaded protobuf reached from the client telemetry reporter
    ExtensionRegistry.class
})
public class PipesKafkaNativeSupport {
}
