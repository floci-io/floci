package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.msk.MskService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls Kafka pipe sources (MSK and self-managed, {@code smk://}) over a Kafka REST Proxy
 * (Karapace) instead of embedding {@code kafka-clients} directly: see #2916, where kafka-clients
 * and the zstd-jni/lz4-java it pulls in were reachable in every build regardless of whether a
 * Pipe ever used a Kafka source. {@link KarapaceManager} starts a Karapace sidecar per distinct
 * target on demand, and {@link PipesKafkaRestClient} speaks its REST API.
 */
@ApplicationScoped
public class PipesKafkaConsumerManager {

    private static final Logger LOG = Logger.getLogger(PipesKafkaConsumerManager.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final String DEFAULT_STARTING_POSITION = "LATEST";

    private final MskService mskService;
    private final KarapaceManager karapaceManager;
    private final PipesKafkaRestClient restClient;
    private final ConcurrentHashMap<String, KafkaConsumerHandle> consumers = new ConcurrentHashMap<>();

    @Inject
    public PipesKafkaConsumerManager(MskService mskService, KarapaceManager karapaceManager,
                                      PipesKafkaRestClient restClient) {
        this.mskService = mskService;
        this.karapaceManager = karapaceManager;
        this.restClient = restClient;
    }

    @PreDestroy
    void shutdown() {
        consumers.values().forEach(this::closeQuietly);
        consumers.clear();
    }

    public List<KafkaRecordDto> poll(Pipe pipe) {
        KafkaConsumerHandle consumer = consumers.computeIfAbsent(pipe.getArn(), ignored -> createConsumer(pipe));
        try {
            return restClient.poll(consumer, POLL_TIMEOUT);
        } catch (RuntimeException e) {
            close(pipe);
            throw e;
        }
    }

    public void commit(Pipe pipe, List<KafkaOffsetDto> offsets) {
        if (offsets.isEmpty()) {
            return;
        }
        KafkaConsumerHandle consumer = consumers.get(pipe.getArn());
        if (consumer == null) {
            return;
        }
        try {
            restClient.commit(consumer, offsets);
        } catch (RuntimeException e) {
            close(pipe);
            throw e;
        }
    }

    public void close(Pipe pipe) {
        KafkaConsumerHandle consumer = consumers.remove(pipe.getArn());
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

    private KafkaConsumerHandle createConsumer(Pipe pipe) {
        String bootstrapServers = resolveBootstrapServers(pipe);
        String topicName = resolveTopicName(pipe);
        String groupId = resolveConsumerGroupId(pipe);
        String offsetReset = resolveOffsetReset(kafkaParameters(pipe));

        URI restBaseUri = karapaceManager.ensureStarted(bootstrapServers);
        KafkaConsumerHandle consumer = restClient.createConsumer(restBaseUri, groupId, offsetReset);
        try {
            restClient.subscribe(consumer, List.of(topicName));
        } catch (RuntimeException e) {
            // The consumer already exists server-side; this method's caller only learns about it
            // through the returned handle, so a failure here must delete it itself or it is never
            // reachable again to close, and every retry creates another orphaned server consumer.
            closeQuietly(consumer);
            throw e;
        }
        LOG.infov("Pipe {0}: subscribed Kafka REST consumer to topic {1} via {2}",
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

    private void closeQuietly(KafkaConsumerHandle consumer) {
        try {
            restClient.close(consumer);
        } catch (Exception e) {
            LOG.debugv("Ignoring Kafka REST consumer close error: {0}", e.getMessage());
        }
    }
}
