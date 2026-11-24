package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
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

import static io.floci.aws.services.redshift.spectrum.StreamingViewSupport.schemaOrDefault;
import static io.floci.aws.services.redshift.spectrum.StreamingViewSupport.table;

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

    private static final String REDSHIFT_PRINCIPAL = ServicePrincipals.of("redshift");
    private static final List<String> READ_ACTIONS = List.of("kinesis:DescribeStreamSummary",
            "kinesis:GetShardIterator", "kinesis:GetRecords", "kinesis:ListShards", "kinesis:DescribeStream");
    private static final int PAGE_LIMIT = 1000;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final KinesisViewRegistry registry;
    private final KinesisService kinesis;
    private final ServiceRoleAuthorizer authorizer;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final StreamingViewSupport<KinesisViewBinding> support;

    @Inject
    public KinesisStreamingViews(KinesisViewRegistry registry, KinesisService kinesis,
                                 ServiceRoleAuthorizer authorizer, RegionResolver regionResolver, Clock clock) {
        this.registry = registry;
        this.kinesis = kinesis;
        this.authorizer = authorizer;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.support = new StreamingViewSupport<>(registry, KinesisViewBinding::viewSchema,
                KinesisViewBinding::viewName, KinesisViewBinding::streamSchema, KinesisViewBinding::streamName);
    }

    /**
     * Views kept in memory and bound to no Kinesis, role or Region service, for the statements a test prepares that
     * never read a stream: anything that would reach one fails on its null collaborator.
     */
    static KinesisStreamingViews inMemory() {
        return new KinesisStreamingViews(new KinesisViewRegistry(
                AccountAwareStorageBackend.<KinesisViewBinding>inMemory("000000000000")),
                null, null, null, Clock.systemUTC());
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
        support.unbind(view);
    }

    /** Unbinds a view captured earlier, unless a view of the same name over another stream has replaced it. */
    public void unbindIfCurrent(KinesisViewBinding captured) {
        support.unbindIfCurrent(captured);
    }

    /** Drops a view's table and its binding, and the stream's staging table once no other view reads the stream. */
    public void drop(KinesisViewBinding named, boolean ifExists, boolean cascade, BackendSql backend) {
        support.drop(named, ifExists, cascade, backend);
    }

    public void create(ExternalStatement.CreateStreamingView statement, String streamName,
                       KinesisSchemaBinding schema, SpectrumSession session, String clusterRegion,
                       BackendSql backend) {
        String viewSchema = schemaOrDefault(statement.viewSchema());
        synchronized (support.lock(session.accountId(), session.clusterKey(), session.databaseName(),
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
        synchronized (support.lock(view)) {
            // the binding read before the lock may be stale: a refresh ahead of this one moved the checkpoints
            KinesisViewBinding latest = registry.find(view.accountId(), view.clusterKey(), view.databaseName(),
                    view.bindingName()).orElseThrow(() -> new SpectrumSqlException("42P01",
                            "relation \"" + view.viewSchema() + "." + view.viewName() + "\" does not exist"));
            return refreshLocked(latest, schema, clusterRegion, backend);
        }
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
                for (List<KinesisRecord> batch : StreamingViewSupport.batches(records,
                        record -> record.getData() == null ? 0 : record.getData().length)) {
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
}
