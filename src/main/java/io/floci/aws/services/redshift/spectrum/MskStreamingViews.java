package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.kafka.KafkaConsumerHandle;
import io.floci.aws.core.common.kafka.KafkaHeaderDto;
import io.floci.aws.core.common.kafka.KafkaOffsetDto;
import io.floci.aws.core.common.kafka.KafkaPartitionOffsets;
import io.floci.aws.core.common.kafka.KafkaRecordDto;
import io.floci.aws.core.common.kafka.KafkaRestClient;
import io.floci.aws.core.common.kafka.KafkaRestClientException;
import io.floci.aws.core.common.kafka.KafkaTopicNotFoundException;
import io.floci.aws.core.common.kafka.KafkaTopicPartition;
import io.floci.aws.core.common.kafka.KarapaceManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.ServiceRoleAuthorizer.Permission;
import io.floci.aws.services.msk.MskService;
import io.floci.aws.services.msk.model.ClusterState;
import io.floci.aws.services.msk.model.MskCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static io.floci.aws.services.redshift.spectrum.StreamingViewSupport.schemaOrDefault;
import static io.floci.aws.services.redshift.spectrum.StreamingViewSupport.table;

/**
 * Streaming materialized views over Amazon MSK topics. A view is a PostgreSQL table, and the topic's relation of
 * its external schema is a staging table of Redshift's eight Kafka metadata columns. A refresh reads each
 * partition from its checkpoint up to the offset the partition ends at when the refresh starts, loads a batch of
 * records into the staging table, runs the view's query over it into the view and empties the staging table again,
 * as one statement string, which PostgreSQL runs as a single implicit transaction. A checkpoint is saved only once
 * that statement has committed, so it never runs ahead of the data; a crash between the commit and the save
 * re-reads the last batch.
 *
 * <p>Floci positions every partition itself, by manual assignment and an explicit seek, so no group rebalance and
 * no offset the broker keeps for a group decides what is read. Each view reads through its own consumer group,
 * apart from those of Pipes and Lambda, and the consumer instance is closed when the refresh ends.
 */
@ApplicationScoped
public class MskStreamingViews {

    private static final Logger LOG = Logger.getLogger(MskStreamingViews.class);

    static final String GROUP_PREFIX = "floci-redshift-msk-";
    static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    /**
     * A partition's last offsets may hold no record (transaction markers, compaction), so a poll that keeps
     * returning nothing ends the refresh even when a partition has not reached the offset it ended at.
     */
    static final int MAX_IDLE_POLLS = 3;

    private static final String REDSHIFT_PRINCIPAL = ServicePrincipals.of("redshift");
    private static final String BROKER_UNAVAILABLE = "58030";
    private static final String EARLIEST = "earliest";
    private static final String TIMESTAMP_CREATE_TIME = "C";
    private static final String TIMESTAMP_UNKNOWN = "U";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final MskViewRegistry registry;
    private final MskService msk;
    private final KarapaceManager karapace;
    private final KafkaRestClient restClient;
    private final ServiceRoleAuthorizer authorizer;
    private final Clock clock;
    private final StreamingViewSupport<MskViewBinding> support;
    private final Map<String, Object> schemaLocks = new ConcurrentHashMap<>();

    @Inject
    public MskStreamingViews(MskViewRegistry registry, MskService msk, KarapaceManager karapace,
                             KafkaRestClient restClient, ServiceRoleAuthorizer authorizer, Clock clock) {
        this.registry = registry;
        this.msk = msk;
        this.karapace = karapace;
        this.restClient = restClient;
        this.authorizer = authorizer;
        this.clock = clock;
        this.support = new StreamingViewSupport<>(registry, MskViewBinding::viewSchema, MskViewBinding::viewName,
                MskViewBinding::topicSchema, MskViewBinding::topicName);
    }

    /**
     * Views kept in memory and bound to no MSK, Karapace, Kafka REST or role service, for the statements a test
     * prepares that never read a topic: anything that would reach one fails on its null collaborator.
     */
    static MskStreamingViews inMemory() {
        return new MskStreamingViews(new MskViewRegistry(
                AccountAwareStorageBackend.<MskViewBinding>inMemory("000000000000")),
                null, null, null, null, Clock.systemUTC());
    }

    public Optional<MskViewBinding> find(SpectrumSession session, String viewSchema, String viewName) {
        return registry.find(session.accountId(), session.clusterKey(), session.databaseName(),
                MskViewBinding.nameOf(schemaOrDefault(viewSchema), viewName));
    }

    /** The views that read a topic of {@code topicSchema}. */
    public List<MskViewBinding> overSchema(SpectrumSession session, String topicSchema) {
        return registry.list(session.accountId(), session.clusterKey(), session.databaseName()).stream()
                .filter(view -> view.topicSchema().equals(topicSchema)).toList();
    }

    public void forgetCluster(String accountId, String clusterKey) {
        registry.removeCluster(accountId, clusterKey);
    }

    public void unbind(MskViewBinding view) {
        support.unbind(view);
    }

    /** Unbinds a view captured earlier, unless a view of the same name over another topic has replaced it. */
    public void unbindIfCurrent(MskViewBinding captured) {
        support.unbindIfCurrent(captured);
    }

    /** Drops a view's table and its binding, and the topic's staging table once no other view reads the topic. */
    public void drop(MskViewBinding named, boolean ifExists, boolean cascade, BackendSql backend) {
        support.drop(named, ifExists, cascade, backend);
    }

    /**
     * Creates the view's table and the topic's staging table. Redshift reads the topic's partitions when the
     * view is created, so a cluster without a running broker, an unreachable broker and a missing topic fail here.
     */
    public void create(ExternalStatement.CreateStreamingView statement, String topicName, MskSchemaBinding schema,
                       SpectrumSession session, BackendSql backend) {
        // the schema lock keeps a DROP SCHEMA of the topic's schema from running between the view's creation and its
        // binding, which would leave a binding over a staging table that is gone
        synchronized (schemaLock(session.accountId(), session.clusterKey(), session.databaseName(),
                schema.schemaName())) {
            createLocked(statement, topicName, schema, session, backend);
        }
    }

    /**
     * Runs {@code action} while no view is created in the schema: a DROP SCHEMA reads the schema's views, drops
     * them and unbinds them as one step.
     */
    public void exclusivelyOnSchema(SpectrumSession session, String schemaName, Runnable action) {
        synchronized (schemaLock(session.accountId(), session.clusterKey(), session.databaseName(), schemaName)) {
            action.run();
        }
    }

    private void createLocked(ExternalStatement.CreateStreamingView statement, String topicName,
                              MskSchemaBinding schema, SpectrumSession session, BackendSql backend) {
        String viewSchema = schemaOrDefault(statement.viewSchema());
        synchronized (support.lock(session.accountId(), session.clusterKey(), session.databaseName(),
                schema.schemaName(), topicName)) {
            if (find(session, viewSchema, statement.viewName()).isPresent()) {
                throw new SpectrumSqlException("42P07", "relation \"" + viewSchema + "." + statement.viewName()
                        + "\" already exists");
            }
            MskViewBinding candidate = new MskViewBinding(session.accountId(), session.clusterKey(),
                    session.databaseName(), viewSchema, statement.viewName(), schema.schemaName(), topicName,
                    statement.select(), Map.of());
            // before any broker lookup, so a role that may not read the topic cannot tell whether it exists
            requireAuthorized(candidate, schema, groupOf(candidate));
            URI restBaseUri = restBaseUri(session.accountId(), schema);
            try {
                restClient.listPartitions(restBaseUri, topicName);
            } catch (KafkaTopicNotFoundException | KafkaRestClientException | UncheckedIOException e) {
                throw readFailure(topicName, schema, e);
            }
            backend.execute("CREATE TABLE IF NOT EXISTS " + table(schema.schemaName(), topicName)
                    + " (kafka_partition bigint, kafka_offset bigint, kafka_timestamp_type char(1), "
                    + "kafka_timestamp timestamp, kafka_key pg_catalog.varbyte, kafka_value pg_catalog.varbyte, "
                    + "kafka_headers pg_catalog.super, refresh_time timestamp); "
                    + "CREATE TABLE " + table(viewSchema, statement.viewName()) + " AS " + statement.select()
                    + " WITH NO DATA");
            registry.bind(candidate);
        }
    }

    /** Loads what the topic holds past each partition's checkpoint into the view; returns the records loaded. */
    public long refresh(MskViewBinding view, MskSchemaBinding schema, BackendSql backend) {
        // refreshes of the views over one topic share its staging table, so they run one at a time
        synchronized (support.lock(view)) {
            // the binding read before the lock may be stale: a refresh ahead of this one moved the checkpoints
            MskViewBinding latest = registry.find(view.accountId(), view.clusterKey(), view.databaseName(),
                    view.bindingName()).orElseThrow(() -> new SpectrumSqlException("42P01",
                            "relation \"" + view.viewSchema() + "." + view.viewName() + "\" does not exist"));
            if (!support.readsSameSource(latest, view)) {
                // dropped and created again over another topic while this refresh waited: that view has its own
                // topic's lock, and this call holds another one
                throw new SpectrumSqlException("42P01", "materialized view \"" + view.viewSchema() + "."
                        + view.viewName() + "\" was replaced by a view over another topic; retry the statement");
            }
            return refreshLocked(latest, schema, backend);
        }
    }

    /** The consumer group a view reads through: its own, and never one of Pipes or Lambda. */
    static String groupOf(MskViewBinding view) {
        return GROUP_PREFIX + Digests.sha256Hex(String.join("\u001f", view.accountId(), view.clusterKey(),
                view.databaseName(), view.viewSchema(), view.viewName())).substring(0, 16);
    }

    private Object schemaLock(String accountId, String clusterKey, String database, String schemaName) {
        return schemaLocks.computeIfAbsent(String.join("\u001f", accountId, clusterKey, database, schemaName),
                key -> new Object());
    }

    private long refreshLocked(MskViewBinding view, MskSchemaBinding schema, BackendSql backend) {
        String group = groupOf(view);
        requireAuthorized(view, schema, group);
        URI restBaseUri = restBaseUri(view.accountId(), schema);
        String topic = view.topicName();
        Instant refreshTime = clock.instant();
        try {
            Map<Integer, Long> ends = new TreeMap<>();
            Map<Integer, Long> checkpoints = new TreeMap<>();
            List<KafkaTopicPartition> fromBeginning = new ArrayList<>();
            List<KafkaOffsetDto> resumeAt = new ArrayList<>();
            for (int partition : restClient.listPartitions(restBaseUri, topic)) {
                KafkaPartitionOffsets offsets = restClient.partitionOffsets(restBaseUri, topic, partition);
                Long lastLoaded = view.checkpoints().get(Integer.toString(partition));
                if (lastLoaded != null && lastLoaded + 1 > offsets.endOffset()) {
                    // the partition ends before its checkpoint, so the topic was deleted and created again: its
                    // offsets started over, and waiting for them to pass the old checkpoint would skip its records
                    LOG.warnv("Partition {0} of topic {1} ends at offset {2}, before the checkpoint {3} of view {4}.{5}; "
                            + "reading it from the beginning", partition, topic, offsets.endOffset(), lastLoaded,
                            view.viewSchema(), view.viewName());
                    lastLoaded = null;
                }
                long next = lastLoaded == null ? offsets.beginningOffset()
                        : Math.max(lastLoaded + 1, offsets.beginningOffset());
                if (next >= offsets.endOffset()) {
                    continue;
                }
                ends.put(partition, offsets.endOffset());
                if (lastLoaded == null) {
                    fromBeginning.add(new KafkaTopicPartition(topic, partition));
                } else {
                    resumeAt.add(new KafkaOffsetDto(topic, partition, lastLoaded + 1));
                    checkpoints.put(partition, lastLoaded);
                }
            }
            if (ends.isEmpty()) {
                return 0;
            }
            KafkaConsumerHandle consumer = restClient.createConsumer(restBaseUri, group, EARLIEST);
            try {
                List<KafkaTopicPartition> assigned = new ArrayList<>();
                ends.keySet().forEach(partition -> assigned.add(new KafkaTopicPartition(topic, partition)));
                restClient.assign(consumer, assigned);
                restClient.seekToBeginning(consumer, fromBeginning);
                restClient.seek(consumer, resumeAt);
                return load(view, backend, consumer, ends, checkpoints, refreshTime);
            } finally {
                closeQuietly(consumer);
            }
        } catch (KafkaTopicNotFoundException | KafkaRestClientException | UncheckedIOException e) {
            throw readFailure(topic, schema, e);
        }
    }

    /** Polls until every partition has reached the offset it ended at, or the topic stops returning records. */
    private long load(MskViewBinding view, BackendSql backend, KafkaConsumerHandle consumer, Map<Integer, Long> ends,
                      Map<Integer, Long> checkpoints, Instant refreshTime) {
        Map<Integer, Long> pending = new TreeMap<>(ends);
        MskViewBinding current = view;
        long loaded = 0;
        int idlePolls = 0;
        while (!pending.isEmpty() && idlePolls < MAX_IDLE_POLLS) {
            List<KafkaRecordDto> records = unseen(restClient.poll(consumer, POLL_TIMEOUT), ends, checkpoints);
            if (records.isEmpty()) {
                idlePolls++;
                continue;
            }
            idlePolls = 0;
            for (List<KafkaRecordDto> batch : StreamingViewSupport.batches(records,
                    record -> length(record.key()) + length(record.value()))) {
                backend.execute(loadSql(view, batch, refreshTime));
                Map<Integer, Long> lastLoaded = new TreeMap<>();
                for (KafkaRecordDto record : batch) {
                    lastLoaded.merge(record.partition(), record.offset(), Math::max);
                }
                checkpoints.putAll(lastLoaded);
                current = current.withCheckpoints(lastLoaded);
                registry.replace(current);
                loaded += batch.size();
            }
            for (KafkaRecordDto record : records) {
                Long end = pending.get(record.partition());
                if (end != null && record.offset() >= end - 1) {
                    pending.remove(record.partition());
                }
            }
        }
        if (!pending.isEmpty()) {
            // the offsets left may hold no record (transaction markers, compaction), or the proxy may be slow; either
            // way the next refresh continues from the checkpoints
            LOG.warnv("Refresh of {0}.{1} stopped after {2} polls with no records, before partitions {3} reached the "
                    + "offsets they ended at ({4}); the next refresh continues from the checkpoints",
                    view.viewSchema(), view.viewName(), MAX_IDLE_POLLS, pending.keySet(), pending);
        }
        return loaded;
    }

    /** Drops what a poll returns for a partition the refresh did not assign, or at an offset already loaded. */
    private static List<KafkaRecordDto> unseen(List<KafkaRecordDto> polled, Map<Integer, Long> ends,
                                               Map<Integer, Long> checkpoints) {
        List<KafkaRecordDto> records = new ArrayList<>();
        for (KafkaRecordDto record : polled) {
            Long loaded = checkpoints.get(record.partition());
            Long end = ends.get(record.partition());
            // a record at or past the end the refresh started with belongs to the next one
            if (end != null && record.offset() < end && (loaded == null || record.offset() > loaded)) {
                records.add(record);
            }
        }
        return records;
    }

    private URI restBaseUri(String accountId, MskSchemaBinding schema) {
        MskCluster cluster = msk.getClusterForAccount(accountId, schema.clusterArn())
                .orElseThrow(() -> clusterNotFound(schema, null));
        String bootstrapBrokers = cluster.getBootstrapBrokers();
        if (cluster.getState() != ClusterState.ACTIVE || bootstrapBrokers == null || bootstrapBrokers.isBlank()) {
            throw new SpectrumSqlException(BROKER_UNAVAILABLE, "MSK cluster " + schema.clusterArn()
                    + " has no running broker (state " + cluster.getState() + ")");
        }
        try {
            return karapace.ensureStarted(bootstrapBrokers);
        } catch (RuntimeException e) {
            throw new SpectrumSqlException(BROKER_UNAVAILABLE, "The Kafka REST bridge for MSK cluster "
                    + schema.clusterArn() + " is unavailable: " + e.getMessage());
        }
    }

    /** What a failed read of the topic is reported as, by CREATE and REFRESH alike. */
    private static SpectrumSqlException readFailure(String topic, MskSchemaBinding schema, RuntimeException cause) {
        if (cause instanceof KafkaTopicNotFoundException) {
            return new SpectrumSqlException("42P01", "topic \"" + topic + "\" does not exist in MSK cluster "
                    + schema.clusterArn());
        }
        return new SpectrumSqlException(BROKER_UNAVAILABLE, "Cannot read topic \"" + topic + "\" from MSK cluster "
                + schema.clusterArn() + ": " + cause.getMessage());
    }

    private void closeQuietly(KafkaConsumerHandle consumer) {
        try {
            restClient.close(consumer);
        } catch (RuntimeException e) {
            // the refresh outcome is already decided, so this must not replace it; the instance stays on the proxy
            // until its idle timeout, which is worth a trace
            LOG.warnv(e, "Could not close the Kafka REST consumer {0}: {1}", consumer.baseUri(), e.getMessage());
        }
    }

    /**
     * Refreshing reads the cluster's bootstrap brokers and, with IAM authentication, connects and reads the topic
     * as a member of the view's group, as the role Redshift assumes.
     */
    private void requireAuthorized(MskViewBinding view, MskSchemaBinding schema, String group) {
        List<Permission> permissions = new ArrayList<>();
        permissions.add(new Permission("kafka:GetBootstrapBrokers", schema.clusterArn()));
        if ("IAM".equals(schema.authentication())) {
            AwsArnUtils.Arn cluster = clusterArn(schema);
            // cluster/<name>/<uuid>, whose topics and groups are topic/<name>/<uuid>/<topic> and group/...
            String clusterPath = cluster.resource().substring(cluster.resource().indexOf('/') + 1);
            String topicArn = AwsArnUtils.Arn.of("kafka", cluster.region(), cluster.accountId(),
                    "topic/" + clusterPath + "/" + view.topicName()).toString();
            String groupArn = AwsArnUtils.Arn.of("kafka", cluster.region(), cluster.accountId(),
                    "group/" + clusterPath + "/" + group).toString();
            permissions.add(new Permission("kafka-cluster:Connect", schema.clusterArn()));
            permissions.add(new Permission("kafka-cluster:DescribeTopic", topicArn));
            permissions.add(new Permission("kafka-cluster:ReadData", topicArn));
            permissions.add(new Permission("kafka-cluster:AlterGroup", groupArn));
            permissions.add(new Permission("kafka-cluster:DescribeGroup", groupArn));
        }
        Optional<String> refusal = authorizer.refusal(schema.iamRoleArn(), view.accountId(), REDSHIFT_PRINCIPAL,
                null, permissions);
        if (refusal.isPresent()) {
            throw new SpectrumSqlException("42501", refusal.get());
        }
    }

    /**
     * The schema's cluster ARN, which CREATE EXTERNAL SCHEMA keeps as written. One that is not the ARN of a
     * cluster names no cluster, so it is the same error as a cluster that is gone.
     */
    private static AwsArnUtils.Arn clusterArn(MskSchemaBinding schema) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(schema.clusterArn());
        } catch (IllegalArgumentException e) {
            throw clusterNotFound(schema, e);
        }
        String resource = arn.resource();
        boolean namedCluster = resource.startsWith("cluster/") && resource.indexOf('/') != resource.lastIndexOf('/');
        if (!"kafka".equals(arn.service()) || arn.region().isBlank() || !namedCluster) {
            throw clusterNotFound(schema, null);
        }
        return arn;
    }

    private static SpectrumSqlException clusterNotFound(MskSchemaBinding schema, RuntimeException cause) {
        SpectrumSqlException error = new SpectrumSqlException("42704",
                "MSK cluster " + schema.clusterArn() + " does not exist");
        if (cause != null) {
            error.addSuppressed(cause);
        }
        return error;
    }

    private static long length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private static String loadSql(MskViewBinding view, List<KafkaRecordDto> batch, Instant refreshTime) {
        String staging = table(view.topicSchema(), view.topicName());
        StringBuilder rows = new StringBuilder();
        for (KafkaRecordDto record : batch) {
            if (!rows.isEmpty()) {
                rows.append(", ");
            }
            boolean timestamped = record.timestamp() >= 0;
            rows.append('(').append(record.partition()).append(", ").append(record.offset()).append(", ")
                    .append(PostgresWire.quoteLiteral(timestamped ? TIMESTAMP_CREATE_TIME : TIMESTAMP_UNKNOWN))
                    .append(", ").append(timestamped ? timestamp(Instant.ofEpochMilli(record.timestamp()))
                            : "NULL::timestamp")
                    .append(", ").append(varbyte(record.key())).append(", ").append(varbyte(record.value()))
                    .append(", ").append(PostgresWire.quoteLiteral(headersJson(record.headers())))
                    .append("::jsonb::pg_catalog.super, ").append(timestamp(refreshTime)).append(')');
        }
        return "INSERT INTO " + staging + " VALUES " + rows + "; INSERT INTO "
                + table(view.viewSchema(), view.viewName()) + " " + view.select() + "; TRUNCATE " + staging;
    }

    /**
     * The headers as a JSON object of header name to value, the value as UTF-8 text or null for a header without
     * one. A name that repeats keeps its last value: a JSON object holds a name once. Bytes that are not UTF-8 text,
     * and a NUL, which jsonb refuses, become U+FFFD, so one binary header cannot fail every refresh of the view.
     */
    static String headersJson(List<KafkaHeaderDto> headers) {
        ObjectNode object = JSON.createObjectNode();
        Map<String, String> values = new LinkedHashMap<>();
        if (headers != null) {
            for (KafkaHeaderDto header : headers) {
                values.put(jsonbText(header.key()), header.value() == null ? null
                        : jsonbText(new String(header.value(), StandardCharsets.UTF_8)));
            }
        }
        values.forEach(object::put);
        return object.toString();
    }

    private static String jsonbText(String text) {
        return text == null ? null : text.replace('\u0000', '�');
    }

    private static String varbyte(byte[] bytes) {
        return bytes == null ? "NULL::pg_catalog.varbyte"
                : "decode('" + HexFormat.of().formatHex(bytes) + "', 'hex')::pg_catalog.varbyte";
    }

    private static String timestamp(Instant instant) {
        return "TIMESTAMP '" + TIMESTAMP.format(instant.atOffset(ZoneOffset.UTC)) + "'";
    }
}
