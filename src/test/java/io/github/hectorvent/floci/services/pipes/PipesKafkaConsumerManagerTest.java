package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.msk.MskService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipesKafkaConsumerManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MskService mskService;
    private KarapaceManager karapaceManager;
    private PipesKafkaRestClient restClient;
    private PipesKafkaConsumerManager manager;

    @BeforeEach
    void setUp() {
        mskService = Mockito.mock(MskService.class);
        karapaceManager = Mockito.mock(KarapaceManager.class);
        restClient = Mockito.mock(PipesKafkaRestClient.class);
        manager = new PipesKafkaConsumerManager(mskService, karapaceManager, restClient);
    }

    @Test
    void pollClosesOrphanedConsumerWhenSubscriptionFails() throws Exception {
        Pipe pipe = new Pipe();
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/orders-pipe");
        pipe.setName("orders-pipe");
        pipe.setSource("smk://broker-1:9092");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders"
                  }
                }
                """));

        URI restBaseUri = URI.create("http://localhost:8082/");
        KafkaConsumerHandle handle = new KafkaConsumerHandle("instance-1",
                URI.create("http://localhost:8082/consumers/g/instances/instance-1"));
        when(karapaceManager.ensureStarted("broker-1:9092")).thenReturn(restBaseUri);
        when(restClient.createConsumer(eq(restBaseUri), anyString(), anyString())).thenReturn(handle);
        Mockito.doThrow(new RuntimeException("subscribe failed"))
                .when(restClient).subscribe(eq(handle), anyList());

        assertThrows(RuntimeException.class, () -> manager.poll(pipe));

        // The consumer already exists server-side once createConsumer succeeds; a failed
        // subscribe must delete it here, since the map never learns its handle to close it later.
        verify(restClient).close(handle);
    }

    @Test
    void resolveBootstrapServersForSelfManagedKafkaIncludesAdditionalServers() throws Exception {
        Pipe pipe = new Pipe();
        pipe.setSource("smk://broker-1:9092");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders",
                    "AdditionalBootstrapServers": ["broker-2:9092", "broker-3:9092"]
                  }
                }
                """));

        assertEquals("broker-1:9092,broker-2:9092,broker-3:9092", manager.resolveBootstrapServers(pipe));
    }

    @Test
    void resolveBootstrapServersForManagedKafkaUsesMskService() throws Exception {
        Pipe pipe = new Pipe();
        pipe.setSource("arn:aws:kafka:us-east-1:000000000000:cluster/demo/uuid");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "ManagedStreamingKafkaParameters": {
                    "TopicName": "orders"
                  }
                }
                """));
        when(mskService.getBootstrapBrokers(pipe.getSource())).thenReturn("localhost:19092");

        assertEquals("localhost:19092", manager.resolveBootstrapServers(pipe));
    }

    @Test
    void resolveTopicNameUsesMatchingParameterBlock() throws Exception {
        Pipe managed = new Pipe();
        managed.setSource("arn:aws:kafka:us-east-1:000000000000:cluster/demo/uuid");
        managed.setSourceParameters(MAPPER.readTree("""
                {
                  "ManagedStreamingKafkaParameters": {
                    "TopicName": "managed-topic"
                  }
                }
                """));

        Pipe selfManaged = new Pipe();
        selfManaged.setSource("smk://localhost:9092");
        selfManaged.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "self-topic"
                  }
                }
                """));

        assertEquals("managed-topic", manager.resolveTopicName(managed));
        assertEquals("self-topic", manager.resolveTopicName(selfManaged));
    }

    @Test
    @DisplayName("resolveTopicName reports the matching parameter block")
    void resolveTopicNameReportsMatchingParameterBlock() throws Exception {
        Pipe managed = new Pipe();
        managed.setSource("arn:aws:kafka:us-east-1:000000000000:cluster/demo/uuid");
        managed.setSourceParameters(MAPPER.readTree("""
                {
                  "ManagedStreamingKafkaParameters": {}
                }
                """));

        Pipe selfManaged = new Pipe();
        selfManaged.setSource("smk://localhost:9092");
        selfManaged.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {}
                }
                """));

        assertEquals("SourceParameters.ManagedStreamingKafkaParameters.TopicName is required",
                assertThrows(io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> manager.resolveTopicName(managed)).getMessage());
        assertEquals("SourceParameters.SelfManagedKafkaParameters.TopicName is required",
                assertThrows(io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> manager.resolveTopicName(selfManaged)).getMessage());
    }

    @Test
    void resolveTopicNameReportsMissingParameterBlock() {
        Pipe managed = new Pipe();
        managed.setSource("arn:aws:kafka:us-east-1:000000000000:cluster/demo/uuid");
        managed.setSourceParameters(MAPPER.createObjectNode());

        Pipe selfManaged = new Pipe();
        selfManaged.setSource("smk://localhost:9092");
        selfManaged.setSourceParameters(MAPPER.createObjectNode());

        assertEquals("SourceParameters.ManagedStreamingKafkaParameters is required",
                assertThrows(io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> manager.resolveTopicName(managed)).getMessage());
        assertEquals("SourceParameters.SelfManagedKafkaParameters is required",
                assertThrows(io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> manager.resolveTopicName(selfManaged)).getMessage());
    }

    @Test
    void resolveConsumerGroupIdUsesConfiguredValueOrStableFallback() throws Exception {
        Pipe configured = new Pipe();
        configured.setName("orders-pipe");
        configured.setSource("smk://broker-1:9092");
        configured.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders",
                    "ConsumerGroupID": "custom-group"
                  }
                }
                """));

        Pipe fallback = new Pipe();
        fallback.setName("orders-pipe");
        fallback.setSource("arn:aws:kafka:us-east-1:000000000000:cluster/demo/uuid");
        fallback.setSourceParameters(MAPPER.readTree("""
                {
                  "ManagedStreamingKafkaParameters": {
                    "TopicName": "orders"
                  }
                }
                """));

        assertEquals("custom-group", manager.resolveConsumerGroupId(configured));

        String fallbackGroupId = manager.resolveConsumerGroupId(fallback);
        assertEquals(fallbackGroupId, manager.resolveConsumerGroupId(fallback));
        org.junit.jupiter.api.Assertions.assertTrue(
                fallbackGroupId.startsWith("floci-pipes-orders-pipe-"),
                "Expected generated consumer group id prefix, got: " + fallbackGroupId);
    }
}
