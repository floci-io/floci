package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.msk.MskService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PipesKafkaConsumerManager {

    private static final Logger LOG = Logger.getLogger(PipesKafkaConsumerManager.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final String DEFAULT_STARTING_POSITION = "LATEST";

    private final MskService mskService;
    private final ConcurrentHashMap<String, KafkaConsumer<byte[], byte[]>> consumers = new ConcurrentHashMap<>();

    @Inject
    public PipesKafkaConsumerManager(MskService mskService) {
        this.mskService = mskService;
    }

    @PreDestroy
    void shutdown() {
        consumers.values().forEach(this::closeQuietly);
        consumers.clear();
    }

    public ConsumerRecords<byte[], byte[]> poll(Pipe pipe) {
        KafkaConsumer<byte[], byte[]> consumer = consumers.computeIfAbsent(pipe.getArn(), ignored -> createConsumer(pipe));
        try {
            return consumer.poll(POLL_TIMEOUT);
        } catch (RuntimeException e) {
            close(pipe);
            throw e;
        }
    }

    public void commit(Pipe pipe) {
        KafkaConsumer<byte[], byte[]> consumer = consumers.get(pipe.getArn());
        if (consumer == null) {
            return;
        }
        try {
            consumer.commitSync();
        } catch (RuntimeException e) {
            close(pipe);
            throw e;
        }
    }

    public void commit(Pipe pipe, Map<TopicPartition, OffsetAndMetadata> offsets) {
        if (offsets.isEmpty()) {
            return;
        }
        KafkaConsumer<byte[], byte[]> consumer = consumers.get(pipe.getArn());
        if (consumer == null) {
            return;
        }
        try {
            consumer.commitSync(offsets);
        } catch (RuntimeException e) {
            close(pipe);
            throw e;
        }
    }

    public void close(Pipe pipe) {
        KafkaConsumer<byte[], byte[]> consumer = consumers.remove(pipe.getArn());
        if (consumer != null) {
            closeQuietly(consumer);
        }
    }

    String resolveBootstrapServers(Pipe pipe) {
        if (isSelfManagedSource(pipe)) {
            List<String> servers = new ArrayList<>();
            servers.add(pipe.getSource().substring("smk://".length()));
            JsonNode additionalServers = kafkaParameters(pipe).path("AdditionalBootstrapServers");
            if (additionalServers.isArray()) {
                additionalServers.forEach(node -> {
                    String server = node.asText(null);
                    if (server != null && !server.isBlank()) {
                        servers.add(server);
                    }
                });
            }
            return String.join(",", servers);
        }
        if (isManagedSource(pipe)) {
            return mskService.getBootstrapBrokers(pipe.getSource());
        }
        throw new AwsException("ValidationException", "Unsupported Kafka source: " + pipe.getSource(), 400);
    }

    String resolveTopicName(Pipe pipe) {
        JsonNode params = kafkaParameters(pipe);
        String topic = params.path("TopicName").asText(null);
        if (topic == null || topic.isBlank()) {
            throw new AwsException("ValidationException",
                    "SourceParameters." + parameterBlockName(pipe) + ".TopicName is required", 400);
        }
        return topic;
    }

    int resolveBatchSize(Pipe pipe, int defaultBatchSize) {
        JsonNode params = kafkaParameters(pipe);
        return params.path("BatchSize").asInt(defaultBatchSize);
    }

    String resolveConsumerGroupId(Pipe pipe) {
        return resolveConsumerGroupId(pipe, kafkaParameters(pipe));
    }

    private KafkaConsumer<byte[], byte[]> createConsumer(Pipe pipe) {
        String bootstrapServers = resolveBootstrapServers(pipe);
        String topicName = resolveTopicName(pipe);
        JsonNode params = kafkaParameters(pipe);

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resolveOffsetReset(params));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, resolveConsumerGroupId(pipe, params));
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "floci-pipes-" + UUID.randomUUID());
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.toString(resolveBatchSize(pipe, 100)));

        KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(topicName));
        LOG.infov("Pipe {0}: subscribed Kafka consumer to topic {1} via {2}",
                pipe.getName(), topicName, bootstrapServers);
        return consumer;
    }

    private JsonNode kafkaParameters(Pipe pipe) {
        JsonNode sourceParameters = pipe.getSourceParameters();
        if (sourceParameters == null) {
            throw new AwsException("ValidationException", "Kafka pipe SourceParameters are required", 400);
        }
        JsonNode parameters = sourceParameters.path(parameterBlockName(pipe));
        if (parameters.isMissingNode()) {
            throw new AwsException("ValidationException",
                    "SourceParameters." + parameterBlockName(pipe) + " is required", 400);
        }
        return parameters;
    }

    private String resolveOffsetReset(JsonNode params) {
        String startingPosition = params.path("StartingPosition").asText(DEFAULT_STARTING_POSITION);
        return "TRIM_HORIZON".equalsIgnoreCase(startingPosition) ? "earliest" : "latest";
    }

    private String resolveConsumerGroupId(Pipe pipe, JsonNode params) {
        String configured = params.path("ConsumerGroupID").asText(null);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        String sourceFingerprint = isSelfManagedSource(pipe)
                ? pipe.getSource().substring("smk://".length())
                : AwsArnUtils.parse(pipe.getSource()).resource();
        return "floci-pipes-" + pipe.getName() + "-" + Integer.toUnsignedString(sourceFingerprint.hashCode());
    }

    private static boolean isManagedSource(Pipe pipe) {
        return pipe.getSource().contains(":kafka:");
    }

    private static boolean isSelfManagedSource(Pipe pipe) {
        return pipe.getSource().startsWith("smk://");
    }

    private static String parameterBlockName(Pipe pipe) {
        if (isManagedSource(pipe)) {
            return "ManagedStreamingKafkaParameters";
        }
        if (isSelfManagedSource(pipe)) {
            return "SelfManagedKafkaParameters";
        }
        throw new AwsException("ValidationException", "Unsupported Kafka source: " + pipe.getSource(), 400);
    }

    private void closeQuietly(KafkaConsumer<byte[], byte[]> consumer) {
        try {
            consumer.close();
        } catch (Exception e) {
            LOG.debugv("Ignoring Kafka consumer close error: {0}", e.getMessage());
        }
    }
}
