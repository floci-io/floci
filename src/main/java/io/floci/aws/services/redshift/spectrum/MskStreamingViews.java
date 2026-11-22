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

    /** The schema an unqualified view name is created in. */
    static final String DEFAULT_VIEW_SCHEMA = "public";
    static final String GROUP_PREFIX = "floci-redshift-msk-";
    static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    /**
     * A partition's last offsets may hold no record (transaction markers, compaction), so a poll that keeps
     * returning nothing ends the refresh even when a partition has not reached the offset it ended at.
     */
    static final int MAX_IDLE_POLLS = 3;

    private static final String REDSHIFT_PRINCIPAL = ServicePrincipals.of("redshift");
    private static final String BROKER_UNAVAILABLE = "58030";
    private static final int BATCH_BYTES = 4 * 1024 * 1024;
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
    private final Map<String, Object> topicLocks = new ConcurrentHashMap<>();

    @Inject
    public MskStreamingViews(MskViewRegistry registry, MskService msk, KarapaceManager karapace,
                             KafkaRestClient restClient, ServiceRoleAuthorizer authorizer, Clock clock) {
        this.registry = registry;
        this.msk = msk;
        this.karapace = karapace;
        this.restClient = restClient;
        this.authorizer = authorizer;
        this.clock = clock;
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
        registry.unbind(view.accountId(), view.clusterKey(), view.databaseName(), view.bindingName());
    }

    /** Unbinds a view captured earlier, unless a view of the same name over another topic has replaced it. */
    public void unbindIfCurrent(MskViewBinding captured) {
        synchronized (topicLock(captured.accountId(), captured.clusterKey(), captured.databaseName(),
                captured.topicSchema(), captured.topicName())) {
            registry.find(captured.accountId(), captured.clusterKey(), captured.databaseName(),
                    captured.bindingName()).filter(current -> readsSameTopic(current, captured))
                    .ifPresent(this::unbind);
        }
    }

    private static boolean readsSameTopic(MskViewBinding first, MskViewBinding second) {
        return first.topicSchema().equals(second.topicSchema()) && first.topicName().equals(second.topicName());
    }

    /** The SQL that drops a view's table, for the statement that drops a schema it reads from. */
    static String dropSql(MskViewBinding view, boolean ifExists, boolean cascade) {
        return "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + table(view.viewSchema(), view.viewName())
                + (cascade ? " CASCADE" : " RESTRICT");
    }

    /**
     * Drops a view's table and its binding, and the topic's staging table once no other view reads the topic.
     * Without that, a plain DROP SCHEMA of the topic's schema would keep failing on the leftover table.
     */
    public void drop(MskViewBinding named, boolean ifExists, boolean cascade, BackendSql backend) {
        synchronized (topicLock(named.accountId(), named.clusterKey(), named.databaseName(), named.topicSchema(),
                named.topicName())) {
            // the binding read before the lock may be gone or replaced by a view of the same name since
            Optional<MskViewBinding> registered = registry.find(named.accountId(), named.clusterKey(),
                    named.databaseName(), named.bindingName());
            if (registered.isEmpty() || !readsSameTopic(registered.get(), named)) {
                if (ifExists) {
                    return;
                }
                throw new SpectrumSqlException("42P01", "materialized view \"" + named.viewSchema() + "."
                        + named.viewName() + "\" does not exist");
            }
            MskViewBinding view = registered.get();
            boolean lastReader = registry.list(view.accountId(), view.clusterKey(), view.databaseName()).stream()
                    .noneMatch(other -> !other.bindingName().equals(view.bindingName())
                            && readsSameTopic(other, view));
            String sql = dropSql(view, ifExists, cascade);
            if (lastReader) {
                sql += "; DROP TABLE IF EXISTS " + table(view.topicSchema(), view.topicName());
            }
            backend.execute(sql);
            unbind(view);
        }
    }

    /**
     * Creates the view's table and the topic's staging table. Redshift reads the topic's partitions when the
     * view is created, so a cluster without a running broker, an unreachable broker and a missing topic fail here.
     */
    public void create(ExternalStatement.CreateStreamingView statement, String topicName, MskSchemaBinding schema,
                       SpectrumSession session, BackendSql backend) {
        String viewSchema = schemaOrDefault(statement.viewSchema());
        synchronized (topicLock(session.accountId(), session.clusterKey(), session.databaseName(),
                schema.schemaName(), topicName)) {
            if (find(session, viewSchema, statement.viewName()).isPresent()) {
                throw new SpectrumSqlException("42P07", "relation \"" + viewSchema + "." + statement.viewName()
                        + "\" already exists");
            }
            URI restBaseUri = restBaseUri(session.accountId(), schema);
            try {
                restClient.listPartitions(restBaseUri, topicName);
            } catch (KafkaTopicNotFoundException e) {
                throw new SpectrumSqlException("42P01", "topic \"" + topicName + "\" does not exist in MSK cluster "
                        + schema.clusterArn());
            } catch (KafkaRestClientException | UncheckedIOException e) {
                throw brokerUnavailable(topicName, schema, e);
            }
            backend.execute("CREATE TABLE IF NOT EXISTS " + table(schema.schemaName(), topicName)
                    + " (kafka_partition bigint, kafka_offset bigint, kafka_timestamp_type char(1), "
                    + "kafka_timestamp timestamp, kafka_key pg_catalog.varbyte, kafka_value pg_catalog.varbyte, "
                    + "kafka_headers pg_catalog.super, refresh_time timestamp); "
                    + "CREATE TABLE " + table(viewSchema, statement.viewName()) + " AS " + statement.select()
                    + " WITH NO DATA");
            registry.bind(new MskViewBinding(session.accountId(), session.clusterKey(), session.databaseName(),
                    viewSchema, statement.viewName(), schema.schemaName(), topicName, statement.select(), Map.of()));
        }
    }

    /** Loads what the topic holds past each partition's checkpoint into the view; returns the records loaded. */
    public long refresh(MskViewBinding view, MskSchemaBinding schema, BackendSql backend) {
        // refreshes of the views over one topic share its staging table, so they run one at a time
        synchronized (topicLock(view.accountId(), view.clusterKey(), view.databaseName(), view.topicSchema(),
                view.topicName())) {
            // the binding read before the lock may be stale: a refresh ahead of this one moved the checkpoints
            MskViewBinding latest = registry.find(view.accountId(), view.clusterKey(), view.databaseName(),
                    view.bindingName()).orElseThrow(() -> new SpectrumSqlException("42P01",
                            "relation \"" + view.viewSchema() + "." + view.viewName() + "\" does not exist"));
            return refreshLocked(latest, schema, backend);
        }
    }

    /** The consumer group a view reads through: its own, and never one of Pipes or Lambda. */
    static String groupOf(MskViewBinding view) {
        return GROUP_PREFIX + Digests.sha256Hex(String.join("\u001f", view.accountId(), view.clusterKey(),
                view.databaseName(), view.viewSchema(), view.viewName())).substring(0, 16);
    }

    private Object topicLock(String accountId, String clusterKey, String database, String topicSchema,
                             String topicName) {
        return topicLocks.computeIfAbsent(String.join("\u001f", accountId, clusterKey, database, topicSchema,
                topicName), key -> new Object());
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
        } catch (KafkaTopicNotFoundException e) {
            throw new SpectrumSqlException("42P01", "topic \"" + topic + "\" does not exist in MSK cluster "
                    + schema.clusterArn());
        } catch (KafkaRestClientException | UncheckedIOException e) {
            throw brokerUnavailable(topic, schema, e);
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
            for (List<KafkaRecordDto> batch : batches(records)) {
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
        return loaded;
    }

    /** Drops what a poll returns for a partition the refresh did not assign, or at an offset already loaded. */
    private static List<KafkaRecordDto> unseen(List<KafkaRecordDto> polled, Map<Integer, Long> ends,
                                               Map<Integer, Long> checkpoints) {
        List<KafkaRecordDto> records = new ArrayList<>();
        for (KafkaRecordDto record : polled) {
            Long loaded = checkpoints.get(record.partition());
            if (ends.containsKey(record.partition()) && (loaded == null || record.offset() > loaded)) {
                records.add(record);
            }
        }
        return records;
    }

    private URI restBaseUri(String accountId, MskSchemaBinding schema) {
        MskCluster cluster = msk.getClusterForAccount(accountId, schema.clusterArn()).orElseThrow(
                () -> new SpectrumSqlException("42704", "MSK cluster " + schema.clusterArn() + " does not exist"));
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

    private static SpectrumSqlException brokerUnavailable(String topic, MskSchemaBinding schema, RuntimeException cause) {
        return new SpectrumSqlException(BROKER_UNAVAILABLE, "Cannot read topic \"" + topic + "\" from MSK cluster "
                + schema.clusterArn() + ": " + cause.getMessage());
    }

    private void closeQuietly(KafkaConsumerHandle consumer) {
        try {
            restClient.close(consumer);
        } catch (RuntimeException e) {
            // the refresh outcome is already decided; an unreachable proxy takes its idle consumers down anyway
            LOG.debugv(e, "Could not close the Kafka REST consumer {0}: {1}", consumer.baseUri(), e.getMessage());
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
            AwsArnUtils.Arn cluster = AwsArnUtils.parse(schema.clusterArn());
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

    /** Splits a poll so no statement carries more than about {@value #BATCH_BYTES} bytes of record data. */
    private static List<List<KafkaRecordDto>> batches(List<KafkaRecordDto> records) {
        List<List<KafkaRecordDto>> batches = new ArrayList<>();
        List<KafkaRecordDto> batch = new ArrayList<>();
        long bytes = 0;
        for (KafkaRecordDto record : records) {
            long size = length(record.key()) + length(record.value());
            if (!batch.isEmpty() && bytes + size > BATCH_BYTES) {
                batches.add(batch);
                batch = new ArrayList<>();
                bytes = 0;
            }
            batch.add(record);
            bytes += size;
        }
        batches.add(batch);
        return batches;
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
     * one. A name that repeats keeps its last value: a JSON object holds a name once.
     */
    static String headersJson(List<KafkaHeaderDto> headers) {
        ObjectNode object = JSON.createObjectNode();
        Map<String, String> values = new LinkedHashMap<>();
        if (headers != null) {
            for (KafkaHeaderDto header : headers) {
                values.put(header.key(), header.value() == null ? null
                        : new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        values.forEach(object::put);
        return object.toString();
    }

    private static String varbyte(byte[] bytes) {
        return bytes == null ? "NULL::pg_catalog.varbyte"
                : "decode('" + HexFormat.of().formatHex(bytes) + "', 'hex')::pg_catalog.varbyte";
    }

    private static String timestamp(Instant instant) {
        return "TIMESTAMP '" + TIMESTAMP.format(instant.atOffset(ZoneOffset.UTC)) + "'";
    }

    private static String schemaOrDefault(String schema) {
        return schema == null ? DEFAULT_VIEW_SCHEMA : schema;
    }

    private static String table(String schema, String name) {
        return ExternalTableMaterializer.quote(schema) + "." + ExternalTableMaterializer.quote(name);
    }
}
