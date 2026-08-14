package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.github.hectorvent.floci.services.msk.MskService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PipesKafkaNativeSupport} to what {@code ConsumerConfig} resolves from
 * {@link PipesKafkaConsumerManager#consumerProperties}, both ways: a newly resolved class must be
 * registered, and nothing else may be. Reading that method rather than copying it means a new
 * setting there is covered here too.
 *
 * <p>Scope: config-resolved classes only. Reflection always succeeds on the JVM, so a non-config
 * lookup from a future kafka-clients needs a Kafka pipe run against a native binary. The
 * compatibility suite already runs against the native image; it has no Kafka source case yet.
 */
class PipesKafkaNativeSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Predicate<String> TRANSPORT_SECURITY =
            key -> key.startsWith("sasl.") || key.startsWith("ssl.");

    @Test
    @DisplayName("classes ConsumerConfig resolves reflectively are registered")
    void registrationCoversReflectivelyResolvedClasses() throws Exception {
        Set<String> missing = new TreeSet<>(expectedClassNames());
        missing.removeAll(registeredClassNames());

        assertTrue(missing.isEmpty(),
                "kafka-clients resolves these reflectively but PipesKafkaNativeSupport omits them, "
                        + "so a native build cannot construct a consumer: " + missing);
    }

    @Test
    @DisplayName("nothing is registered that ConsumerConfig does not resolve")
    void registrationContainsNothingUnjustified() throws Exception {
        Set<String> unexpected = new TreeSet<>(registeredClassNames());
        unexpected.removeAll(expectedClassNames());

        assertTrue(unexpected.isEmpty(),
                "these are not config-resolved, so this test cannot guard them and they need a "
                        + "native run to justify. SASL/SSL defaults in particular break the build — "
                        + "DefaultJwtValidator drags in optional jose4j: " + unexpected);
    }

    /** Class-valued settings kafka-clients resolves, minus the transport security Floci never uses. */
    private static Set<String> expectedClassNames() throws Exception {
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
     * Mirrors {@code AbstractConfig.getConfiguredInstances}: a setting may be a {@code Class} or a
     * class name — {@code metric.reporters} is the latter. Values naming no class are not candidates.
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

    private static Map<String, ?> consumerConfigValues() throws Exception {
        PipesKafkaConsumerManager manager = new PipesKafkaConsumerManager(Mockito.mock(MskService.class));

        Pipe pipe = new Pipe();
        pipe.setName("native-support");
        pipe.setSource("smk://localhost:9092");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders"
                  }
                }
                """));

        return new ConsumerConfig(manager.consumerProperties(pipe)).values();
    }
}
