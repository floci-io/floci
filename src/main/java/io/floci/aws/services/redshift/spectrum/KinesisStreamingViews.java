package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.ServiceRoleAuthorizer.Permission;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.kinesis.model.KinesisRecord;
import io.floci.aws.services.kinesis.model.KinesisShard;
import io.floci.aws.services.kinesis.model.KinesisStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming materialized views over Kinesis Data Streams. A view is a PostgreSQL table, and the stream's relation
 * of its external schema is a staging table of the stream's metadata columns plus {@code kinesis_data}. A refresh
 * reads each shard from its checkpoint, loads a batch of records into the staging table, runs the view's query
 * over it into the view and empties the staging table again, as one statement string, which PostgreSQL runs as a
 * single implicit transaction. A checkpoint is saved only once that statement has committed, so it never runs
 * ahead of the data; a crash between the commit and the save re-reads the last batch.
 */
@ApplicationScoped
public class KinesisStreamingViews {

    /** The schema an unqualified view name is created in. */
    static final String DEFAULT_VIEW_SCHEMA = "public";

    private static final String REDSHIFT_PRINCIPAL = ServicePrincipals.of("redshift");
    private static final List<String> READ_ACTIONS = List.of("kinesis:DescribeStreamSummary",
            "kinesis:GetShardIterator", "kinesis:GetRecords", "kinesis:ListShards", "kinesis:DescribeStream");
    private static final int PAGE_LIMIT = 1000;
    private static final int BATCH_BYTES = 4 * 1024 * 1024;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final KinesisViewRegistry registry;
    private final KinesisService kinesis;
    private final ServiceRoleAuthorizer authorizer;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final Map<String, Object> streamLocks = new ConcurrentHashMap<>();

    @Inject
    public KinesisStreamingViews(KinesisViewRegistry registry, KinesisService kinesis,
                                 ServiceRoleAuthorizer authorizer, RegionResolver regionResolver, Clock clock) {
        this.registry = registry;
        this.kinesis = kinesis;
        this.authorizer = authorizer;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    public Optional<KinesisViewBinding> find(SpectrumSession session, String viewSchema, String viewName) {
        return registry.find(session.accountId(), session.clusterKey(), session.databaseName(),
                KinesisViewBinding.nameOf(schemaOrDefault(viewSchema), viewName));
    }

    /** The views that read a stream of {@code streamSchema}. */
    public List<KinesisViewBinding> overSchema(SpectrumSession session, String streamSchema) {
        return registry.list(session.accountId(), session.clusterKey(), session.databaseName()).stream()
                .filter(view -> view.streamSchema().equals(streamSchema)).toList();
    }

    public void forgetCluster(String accountId, String clusterKey) {
        registry.removeCluster(accountId, clusterKey);
    }

    public void unbind(KinesisViewBinding view) {
        registry.unbind(view.accountId(), view.clusterKey(), view.databaseName(), view.bindingName());
    }

    /** Unbinds a view captured earlier, unless a view of the same name over another stream has replaced it. */
    public void unbindIfCurrent(KinesisViewBinding captured) {
        synchronized (streamLock(captured.accountId(), captured.clusterKey(), captured.databaseName(),
                captured.streamSchema(), captured.streamName())) {
            registry.find(captured.accountId(), captured.clusterKey(), captured.databaseName(),
                    captured.bindingName()).filter(current -> readsSameStream(current, captured))
                    .ifPresent(this::unbind);
        }
    }

    private static boolean readsSameStream(KinesisViewBinding first, KinesisViewBinding second) {
        return first.streamSchema().equals(second.streamSchema()) && first.streamName().equals(second.streamName());
    }

    /** The SQL that drops a view's table, for the statement that drops a schema it reads from. */
    static String dropSql(KinesisViewBinding view, boolean ifExists, boolean cascade) {
        return "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + table(view.viewSchema(), view.viewName())
                + (cascade ? " CASCADE" : " RESTRICT");
    }

    /**
     * Drops a view's table and its binding, and the stream's staging table once no other view reads the stream.
     * Without that, a plain DROP SCHEMA of the stream's schema would keep failing on the leftover table.
     */
    public void drop(KinesisViewBinding named, boolean ifExists, boolean cascade, BackendSql backend) {
        synchronized (streamLock(named.accountId(), named.clusterKey(), named.databaseName(), named.streamSchema(),
                named.streamName())) {
            // the binding read before the lock may be gone or replaced by a view of the same name since
            Optional<KinesisViewBinding> registered = registry.find(named.accountId(), named.clusterKey(),
                    named.databaseName(), named.bindingName());
            if (registered.isEmpty() || !readsSameStream(registered.get(), named)) {
                if (ifExists) {
                    return;
                }
                throw new SpectrumSqlException("42P01", "materialized view \"" + named.viewSchema() + "."
                        + named.viewName() + "\" does not exist");
            }
            KinesisViewBinding view = registered.get();
            boolean lastReader = registry.list(view.accountId(), view.clusterKey(), view.databaseName()).stream()
                    .noneMatch(other -> !other.bindingName().equals(view.bindingName())
                            && other.streamSchema().equals(view.streamSchema())
                            && other.streamName().equals(view.streamName()));
            String sql = dropSql(view, ifExists, cascade);
            if (lastReader) {
                sql += "; DROP TABLE IF EXISTS " + table(view.streamSchema(), view.streamName());
            }
            backend.execute(sql);
            unbind(view);
        }
    }

    public void create(ExternalStatement.CreateStreamingView statement, String streamName,
                       KinesisSchemaBinding schema, SpectrumSession session, String clusterRegion,
                       BackendSql backend) {
        String viewSchema = schemaOrDefault(statement.viewSchema());
        synchronized (streamLock(session.accountId(), session.clusterKey(), session.databaseName(),
                schema.schemaName(), streamName)) {
            if (find(session, viewSchema, statement.viewName()).isPresent()) {
                throw new SpectrumSqlException("42P07", "relation \"" + viewSchema + "." + statement.viewName()
                        + "\" already exists");
            }
            // Redshift reads the stream's shards when the view is created, so a missing stream fails here
            kinesis.describeStreamForAccount(session.accountId(), streamName, region(schema, clusterRegion));
            backend.execute("CREATE TABLE IF NOT EXISTS " + table(schema.schemaName(), streamName)
                    + " (approximate_arrival_timestamp timestamp, partition_key varchar(256), shard_id char(20), "
                    + "sequence_number varchar(128), refresh_time timestamp, kinesis_data pg_catalog.varbyte); "
                    + "CREATE TABLE " + table(viewSchema, statement.viewName()) + " AS " + statement.select()
                    + " WITH NO DATA");
            registry.bind(new KinesisViewBinding(session.accountId(), session.clusterKey(), session.databaseName(),
                    viewSchema, statement.viewName(), schema.schemaName(), streamName, statement.select(), Map.of()));
        }
    }

    /** Loads what the stream holds past each shard's checkpoint into the view; returns the records loaded. */
    public long refresh(KinesisViewBinding view, KinesisSchemaBinding schema, String clusterRegion,
                        BackendSql backend) {
        // refreshes of the views over one stream share its staging table, so they run one at a time
        synchronized (streamLock(view.accountId(), view.clusterKey(), view.databaseName(), view.streamSchema(),
                view.streamName())) {
            // the binding read before the lock may be stale: a refresh ahead of this one moved the checkpoints
            KinesisViewBinding latest = registry.find(view.accountId(), view.clusterKey(), view.databaseName(),
                    view.bindingName()).orElseThrow(() -> new SpectrumSqlException("42P01",
                            "relation \"" + view.viewSchema() + "." + view.viewName() + "\" does not exist"));
            return refreshLocked(latest, schema, clusterRegion, backend);
        }
    }

    private Object streamLock(String accountId, String clusterKey, String database, String streamSchema,
                              String streamName) {
        return streamLocks.computeIfAbsent(String.join("\u001f", accountId, clusterKey, database, streamSchema,
                streamName), key -> new Object());
    }

    private long refreshLocked(KinesisViewBinding view, KinesisSchemaBinding schema, String clusterRegion,
                               BackendSql backend) {
        String region = region(schema, clusterRegion);
        requireAuthorized(view, schema, region);
        KinesisStream stream = kinesis.describeStreamForAccount(view.accountId(), view.streamName(), region);
        Instant refreshTime = clock.instant();
        KinesisViewBinding current = view;
        long loaded = 0;
        for (KinesisShard shard : stream.getShards()) {
            String shardId = shard.getShardId();
            String checkpoint = current.checkpoints().get(shardId);
            String iterator = checkpoint == null
                    ? kinesis.getShardIteratorForAccount(view.accountId(), view.streamName(), shardId,
                            "TRIM_HORIZON", null, region)
                    : kinesis.getShardIteratorForAccount(view.accountId(), view.streamName(), shardId,
                            "AFTER_SEQUENCE_NUMBER", checkpoint, region);
            while (iterator != null) {
                Map<String, Object> page = kinesis.getRecordsForAccount(view.accountId(), iterator, PAGE_LIMIT, region);
                @SuppressWarnings("unchecked")
                List<KinesisRecord> records = (List<KinesisRecord>) page.get("Records");
                iterator = (String) page.get("NextShardIterator");
                if (records == null || records.isEmpty()) {
                    break;
                }
                for (List<KinesisRecord> batch : batches(records)) {
                    backend.execute(loadSql(view, shardId, batch, refreshTime));
                    current = current.withCheckpoint(shardId, batch.getLast().getSequenceNumber());
                    registry.replace(current);
                    loaded += batch.size();
                }
            }
        }
        return loaded;
    }

    private void requireAuthorized(KinesisViewBinding view, KinesisSchemaBinding schema, String region) {
        String streamArn = new AwsArnUtils.Arn(regionResolver.partitionForRegion(region), "kinesis", region,
                view.accountId(), "stream/" + view.streamName()).toString();
        List<Permission> permissions = new ArrayList<>();
        for (String action : READ_ACTIONS) {
            permissions.add(new Permission(action, streamArn));
        }
        permissions.add(new Permission("kinesis:ListStreams", "*"));
        Optional<String> refusal = authorizer.refusal(schema.iamRoleArn(), view.accountId(), REDSHIFT_PRINCIPAL,
                null, permissions);
        if (refusal.isPresent()) {
            throw new SpectrumSqlException("42501", refusal.get());
        }
    }

    private String region(KinesisSchemaBinding schema, String clusterRegion) {
        if (schema.region() != null) {
            return schema.region();
        }
        return clusterRegion != null ? clusterRegion : regionResolver.getDefaultRegion();
    }

    /** Splits a page so no statement carries more than about {@value #BATCH_BYTES} bytes of record data. */
    private static List<List<KinesisRecord>> batches(List<KinesisRecord> records) {
        List<List<KinesisRecord>> batches = new ArrayList<>();
        List<KinesisRecord> batch = new ArrayList<>();
        long bytes = 0;
        for (KinesisRecord record : records) {
            long size = record.getData() == null ? 0 : record.getData().length;
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

    private static String loadSql(KinesisViewBinding view, String shardId, List<KinesisRecord> batch,
                                  Instant refreshTime) {
        String staging = table(view.streamSchema(), view.streamName());
        StringBuilder rows = new StringBuilder();
        for (KinesisRecord record : batch) {
            if (!rows.isEmpty()) {
                rows.append(", ");
            }
            byte[] data = record.getData() == null ? new byte[0] : record.getData();
            rows.append('(').append(timestamp(record.getApproximateArrivalTimestamp())).append(", ")
                    .append(PostgresWire.quoteLiteral(record.getPartitionKey())).append(", ")
                    .append(PostgresWire.quoteLiteral(shardId)).append(", ")
                    .append(PostgresWire.quoteLiteral(record.getSequenceNumber())).append(", ")
                    .append(timestamp(refreshTime)).append(", decode('").append(HexFormat.of().formatHex(data))
                    .append("', 'hex')::pg_catalog.varbyte)");
        }
        return "INSERT INTO " + staging + " VALUES " + rows + "; INSERT INTO "
                + table(view.viewSchema(), view.viewName()) + " " + view.select() + "; TRUNCATE " + staging;
    }

    private static String timestamp(Instant instant) {
        return instant == null ? "NULL::timestamp"
                : "TIMESTAMP '" + TIMESTAMP.format(instant.atOffset(ZoneOffset.UTC)) + "'";
    }

    private static String schemaOrDefault(String schema) {
        return schema == null ? DEFAULT_VIEW_SCHEMA : schema;
    }

    private static String table(String schema, String name) {
        return ExternalTableMaterializer.quote(schema) + "." + ExternalTableMaterializer.quote(name);
    }
}
