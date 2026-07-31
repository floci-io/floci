package io.github.hectorvent.floci.services.pipes;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PipesKafkaNativeSupport} to what {@code ConsumerConfig} resolves, in both directions:
 * a newly resolved class must be registered, and nothing else may be.
 *
 * <p>Scope: config-resolved classes only. Reflection always succeeds on the JVM, so a non-config
 * lookup added by a future kafka-clients is only catchable by running a Kafka pipe against a
 * native binary — no suite does that today.
 */
class PipesKafkaNativeSupportTest {

    private static final Predicate<String> TRANSPORT_SECURITY =
            key -> key.startsWith("sasl.") || key.startsWith("ssl.");

    @Test
    @DisplayName("classes ConsumerConfig resolves reflectively are registered")
    void registrationCoversReflectivelyResolvedClasses() {
        Set<String> missing = new TreeSet<>(expectedClassNames());
        missing.removeAll(registeredClassNames());

        assertTrue(missing.isEmpty(),
                "kafka-clients resolves these reflectively but PipesKafkaNativeSupport omits them, "
                        + "so a native build cannot construct a consumer: " + missing);
    }

    @Test
    @DisplayName("nothing is registered that ConsumerConfig does not resolve")
    void registrationContainsNothingUnjustified() {
        Set<String> unexpected = new TreeSet<>(registeredClassNames());
        unexpected.removeAll(expectedClassNames());

        assertTrue(unexpected.isEmpty(),
                "these are not config-resolved, so this test cannot guard them and they need a "
                        + "native run to justify. SASL/SSL defaults in particular break the build — "
                        + "DefaultJwtValidator drags in optional jose4j: " + unexpected);
    }

    /** Class-valued settings kafka-clients resolves, minus the transport security Floci never uses. */
    private static Set<String> expectedClassNames() {
        Set<String> classes = new TreeSet<>();
        consumerConfigValues().forEach((key, value) -> {
            if (TRANSPORT_SECURITY.negate().test(key)) {
                collectClassNames(value, classes);
            }
        });
        return classes;
    }

    private static Set<String> registeredClassNames() {
        RegisterForReflection annotation =
                PipesKafkaNativeSupport.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation, "PipesKafkaNativeSupport must carry @RegisterForReflection");

        Set<String> names = new TreeSet<>(Set.of(annotation.classNames()));
        for (Class<?> target : annotation.targets()) {
            names.add(target.getName());
        }
        return names;
    }

    /**
     * Mirrors {@code AbstractConfig.getConfiguredInstances}, which accepts a setting as either a
     * {@code Class} or a class name — {@code metric.reporters} resolves to the latter. Values that
     * do not name a class (bootstrap.servers, ssl.enabled.protocols) are not instantiable and so
     * are not candidates.
     */
    private static void collectClassNames(Object value, Set<String> into) {
        switch (value) {
            case Class<?> type -> into.add(type.getName());
            case Collection<?> collection -> collection.forEach(element -> collectClassNames(element, into));
            case String name -> loadable(name).ifPresent(into::add);
            case null, default -> { }
        }
    }

    private static Optional<String> loadable(String className) {
        try {
            return Optional.of(Class.forName(className, false, ConsumerConfig.class.getClassLoader()).getName());
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static Map<String, ?> consumerConfigValues() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "floci-pipes-test");
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "floci-pipes-test");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        return new ConsumerConfig(properties).values();
    }
}
