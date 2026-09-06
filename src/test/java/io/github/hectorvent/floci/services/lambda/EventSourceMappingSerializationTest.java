package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventSourceMappingSerializationTest {

    private ObjectMapper objectMapper;
    private LambdaController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new LambdaController(null, null, objectMapper);
    }

    @Test
    void testModelGettersAndSetters() {
        EventSourceMapping esm = new EventSourceMapping();
        Map<String, Object> endpoints = Map.of("KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092"));
        Map<String, Object> selfManagedEventSource = Map.of("Endpoints", endpoints);
        List<String> topics = List.of("orders-topic", "events-topic");
        List<Map<String, Object>> sourceAccess = List.of(
                Map.of("Type", "BASIC_AUTH", "URI", "arn:aws:secretsmanager:us-east-1:123456789012:secret:my-secret")
        );

        esm.setSelfManagedEventSource(selfManagedEventSource);
        esm.setTopics(topics);
        esm.setSourceAccessConfigurations(sourceAccess);

        assertEquals(selfManagedEventSource, esm.getSelfManagedEventSource());
        assertEquals(topics, esm.getTopics());
        assertEquals(sourceAccess, esm.getSourceAccessConfigurations());

        // Verify null-safety when passing null for List
        esm.setTopics(null);
        assertNotNull(esm.getTopics());
        assertTrue(esm.getTopics().isEmpty());

        esm.setSourceAccessConfigurations(null);
        assertNotNull(esm.getSourceAccessConfigurations());
        assertTrue(esm.getSourceAccessConfigurations().isEmpty());
    }

    @Test
    void testJacksonSerializationDeserialization() throws Exception {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("test-uuid-123");
        esm.setFunctionName("myFunction");
        esm.setBatchSize(100);
        esm.setSelfManagedEventSource(Map.of("Endpoints", Map.of("KAFKA_BOOTSTRAP_SERVERS", List.of("10.0.0.1:9092"))));
        esm.setTopics(List.of("kafka-test-topic"));
        esm.setSourceAccessConfigurations(List.of(Map.of("Type", "SASL_SCRAM_256_AUTH", "URI", "arn:aws:secretsmanager:test")));

        String json = objectMapper.writeValueAsString(esm);
        EventSourceMapping deserialized = objectMapper.readValue(json, EventSourceMapping.class);

        assertEquals("test-uuid-123", deserialized.getUuid());
        assertEquals("myFunction", deserialized.getFunctionName());
        assertEquals(100, deserialized.getBatchSize());
        assertNotNull(deserialized.getSelfManagedEventSource());
        assertEquals(List.of("kafka-test-topic"), deserialized.getTopics());
        assertEquals(1, deserialized.getSourceAccessConfigurations().size());
    }

    @Test
    void testBuildEsmResponseIncludesKafkaFieldsAndOmitsNullEventSourceArn() {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-kafka-001");
        esm.setFunctionArn("arn:aws:lambda:us-east-1:123456789012:function:kafkaConsumer");
        // Self-managed Kafka does not have EventSourceArn
        esm.setEventSourceArn(null);
        esm.setBatchSize(50);
        esm.setState("Enabled");
        esm.setLastModified(1700000000000L);
        esm.setSelfManagedEventSource(Map.of("Endpoints", Map.of("KAFKA_BOOTSTRAP_SERVERS", List.of("192.168.1.10:9092"))));
        esm.setTopics(List.of("telemetry-topic"));
        esm.setSourceAccessConfigurations(List.of(
                Map.of("Type", "CLIENT_CERTIFICATE_TLS_AUTH", "URI", "arn:aws:secretsmanager:us-east-1:123456789012:secret:cert")
        ));

        Map<String, Object> response = controller.buildEsmResponse(esm);

        assertEquals("esm-kafka-001", response.get("UUID"));
        assertEquals("arn:aws:lambda:us-east-1:123456789012:function:kafkaConsumer", response.get("FunctionArn"));
        assertFalse(response.containsKey("EventSourceArn"), "EventSourceArn must be omitted when null per AWS wire protocol");
        assertNotNull(response.get("SelfManagedEventSource"));
        assertEquals(List.of("telemetry-topic"), response.get("Topics"));
        assertNotNull(response.get("SourceAccessConfigurations"));
    }

    @Test
    void testBuildEsmResponseWithEventSourceArnPresent() {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-sqs-001");
        esm.setFunctionArn("arn:aws:lambda:us-east-1:123456789012:function:sqsConsumer");
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:my-queue");
        esm.setBatchSize(10);
        esm.setState("Enabled");
        esm.setLastModified(1700000000000L);

        Map<String, Object> response = controller.buildEsmResponse(esm);

        assertEquals("arn:aws:sqs:us-east-1:123456789012:my-queue", response.get("EventSourceArn"));
        assertFalse(response.containsKey("SelfManagedEventSource"));
        assertFalse(response.containsKey("Topics"));
        assertFalse(response.containsKey("SourceAccessConfigurations"));
    }
}
