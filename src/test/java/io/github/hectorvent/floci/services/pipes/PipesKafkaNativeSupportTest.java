package io.github.hectorvent.floci.services.pipes;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link PipesKafkaNativeSupport} against kafka-clients upgrades.
 *
 * <p>A class missing from the registration only fails in native mode, and only as a swallowed
 * poller warning, so this asserts the registration against the config kafka-clients actually
 * resolves rather than against a hand-maintained list.
 */
class PipesKafkaNativeSupportTest {

    private static final Predicate<String> TRANSPORT_SECURITY =
            key -> key.startsWith("sasl.") || key.startsWith("ssl.");

    @Test
    @DisplayName("classes ConsumerConfig resolves reflectively are registered")
    void registrationCoversReflectivelyResolvedClasses() {
        Set<String> missing = new TreeSet<>(resolvedClasses(TRANSPORT_SECURITY.negate()));
        missing.removeAll(registeredClassNames());

        assertTrue(missing.isEmpty(),
                "kafka-clients resolves these reflectively but PipesKafkaNativeSupport omits them, "
                        + "so a native build cannot construct a consumer: " + missing);
    }

    @Test
    @DisplayName("SASL and SSL defaults stay unregistered so native-image never links jose4j")
    void registrationExcludesTransportSecurityDefaults() {
        Set<String> unexpected = new TreeSet<>(resolvedClasses(TRANSPORT_SECURITY));
        unexpected.retainAll(registeredClassNames());

        assertTrue(unexpected.isEmpty(),
                "Floci connects over PLAINTEXT and never instantiates these; registering them makes "
                        + "native-image link optional dependencies and fail the build: " + unexpected);
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

    /** Class-valued settings kafka-clients resolves for the properties PipesKafkaConsumerManager sets. */
    private static Set<String> resolvedClasses(Predicate<String> keyFilter) {
        Set<String> classes = new TreeSet<>();
        consumerConfigValues().forEach((key, value) -> {
            if (keyFilter.test(key)) {
                collectClassNames(value, classes);
            }
        });
        return classes;
    }

    private static void collectClassNames(Object value, Set<String> into) {
        if (value instanceof Class<?> type) {
            into.add(type.getName());
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(element -> collectClassNames(element, into));
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
