package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.GlueTableResolver;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftRoleAccess;
import io.github.hectorvent.floci.services.redshift.proxy.S3CopySimulator;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@ApplicationScoped
public class ExternalTableMaterializer {
    private static final Logger LOG = Logger.getLogger(ExternalTableMaterializer.class);
    public static final String SCRATCH_BUCKET = S3Service.INTERNAL_BUCKET_PREFIX + "redshift-spectrum-scratch";
    private static final String SQLSTATE_LOAD_FAILED = "58030";
    private static final String ICEBERG_SETUP = "INSTALL iceberg; LOAD iceberg;\n";
    private static final String COPY_OPTIONS = "WITH (FORMAT csv, HEADER true, NULL '\\N')";
    public enum Outcome { NOT_EXTERNAL, CURRENT, LOADED }

    private final FlociDuckClient duckClient;
    private final GlueService glueService;
    private final S3Service s3Service;
    private final IamService iamService;
    private final EmulatorConfig config;
    private final ConcurrentHashMap<String, String> fingerprints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ExternalTableMaterializer(FlociDuckClient duckClient, GlueService glueService, S3Service s3Service,
                                     IamService iamService, EmulatorConfig config) {
        this.duckClient = duckClient;
        this.glueService = glueService;
        this.s3Service = s3Service;
        this.iamService = iamService;
        this.config = config;
    }

    public Outcome ensureCurrent(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding,
                                 String tableName) {
        Table table;
        try {
            table = RequestScopes.callAs(session.accountId(), () -> glueService.getTable(binding.glueDatabase(), tableName));
        } catch (AwsException exception) {
            if ("EntityNotFoundException".equals(exception.getErrorCode())) {
                return Outcome.NOT_EXTERNAL;
            }
            throw exception;
        }
        String label = binding.schemaName() + "." + tableName;
        // DuckDB reads as the account, so the bound role's access is enforced here, ahead of the read,
        // with the same signed authorization COPY uses: identity policy and bucket policy.
        RedshiftRoleAccess.RoleSession roleSession = openRoleSession(binding, session);
        try {
            Location location = Location.parse(table);
            List<Partition> partitions = partitions(session.accountId(), binding, table);
            List<ReadSource> sources = readSources(session.accountId(), binding, roleSession, table, location, partitions);
            List<S3Object> objects = sources.stream().flatMap(source -> source.objects().stream()).toList();
            authorizeObjects(session.accountId(), binding, roleSession, sources);
            String cacheKey = cacheKey(session, binding, tableName);
            String fingerprint = fingerprint(table, partitions, objects);
            if (!session.inTransaction() && fingerprint.equals(fingerprints.get(cacheKey))) {
                return Outcome.CURRENT;
            }
            ReentrantLock lock = locks.computeIfAbsent(cacheKey, ignored -> new ReentrantLock());
            lock.lock();
            try {
                if (!session.inTransaction() && fingerprint.equals(fingerprints.get(cacheKey))) {
                    return Outcome.CURRENT;
                }
                load(backend, session, binding, table, sources);
                if (session.inTransaction()) {
                    fingerprints.remove(cacheKey);
                } else {
                    fingerprints.put(cacheKey, fingerprint);
                }
                return Outcome.LOADED;
            } finally {
                lock.unlock();
            }
        } catch (SpectrumSqlException | SpectrumReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SpectrumReadException(SQLSTATE_LOAD_FAILED, "Unable to load external table \"" + label + "\": " + exception.getMessage(), exception);
        } finally {
            RedshiftRoleAccess.releaseRoleSession(roleSession, binding.iamRoleArn(), iamService);
        }
    }

    public void forget(String clusterKey, String databaseName, String schemaName, String tableName) {
        fingerprints.remove(clusterKey + "|" + databaseName + "|" + schemaName + "|" + tableName);
    }

    /** Forgets every table of one external schema, so a dropped and recreated schema is reloaded. */
    public void forgetSchema(String clusterKey, String databaseName, String schemaName) {
        String prefix = schemaPrefix(clusterKey, databaseName, schemaName);
        fingerprints.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Tables of one external schema this materializer has loaded into PostgreSQL and still tracks. */
    public Set<String> loadedTables(String clusterKey, String databaseName, String schemaName) {
        String prefix = schemaPrefix(clusterKey, databaseName, schemaName);
        return fingerprints.keySet().stream().filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length())).collect(Collectors.toSet());
    }

    public void forgetCluster(String clusterKey) {
        fingerprints.keySet().removeIf(key -> key.startsWith(clusterKey + "|"));
    }

    private static String schemaPrefix(String clusterKey, String databaseName, String schemaName) {
        return clusterKey + "|" + databaseName + "|" + schemaName + "|";
    }

    private RedshiftRoleAccess.RoleSession openRoleSession(ExternalSchemaBinding binding, SpectrumSession session) {
        try {
            return RedshiftRoleAccess.resolveRoleSession(binding.iamRoleArn(), iamService, session.accountId(),
                    session.iamRoleArns());
        } catch (S3CopySimulator.S3TransferException exception) {
            throw new SpectrumSqlException(exception.sqlState(), exception.getMessage());
        }
    }

    private void authorizeList(String accountId, ExternalSchemaBinding binding,
                               RedshiftRoleAccess.RoleSession roleSession, Location location) {
        try {
            RequestScopes.runAs(accountId, () -> RedshiftRoleAccess.authorizeRoleList(
                    s3Service, iamService, roleSession, binding.iamRoleArn(), location.bucket()));
        } catch (S3CopySimulator.S3TransferException exception) {
            throw new SpectrumSqlException(exception.sqlState(), exception.getMessage());
        }
    }

    private void authorizeObjects(String accountId, ExternalSchemaBinding binding,
                                  RedshiftRoleAccess.RoleSession roleSession, List<ReadSource> sources) {
        try {
            for (ReadSource source : sources) {
                for (S3Object object : source.objects()) {
                    RequestScopes.runAs(accountId, () -> RedshiftRoleAccess.authorizeRoleRead(
                            s3Service, iamService, roleSession, binding.iamRoleArn(),
                            source.location().bucket(), object.getKey()));
                }
            }
        } catch (S3CopySimulator.S3TransferException exception) {
            throw new SpectrumSqlException(exception.sqlState(), exception.getMessage());
        }
    }

    private String cacheKey(SpectrumSession session, ExternalSchemaBinding binding, String tableName) {
        return session.clusterKey() + "|" + session.databaseName() + "|" + binding.schemaName() + "|" + tableName;
    }

    private List<Partition> partitions(String accountId, ExternalSchemaBinding binding, Table table) {
        if (GlueTableResolver.isIcebergTable(table)) {
            return List.of();
        }
        List<Partition> partitions = RequestScopes.callAs(accountId,
                () -> glueService.getPartitions(binding.glueDatabase(), table.getName()));
        return partitions == null ? List.of() : partitions;
    }

    private List<ReadSource> readSources(String accountId, ExternalSchemaBinding binding,
                                         RedshiftRoleAccess.RoleSession roleSession, Table table,
                                         Location tableLocation, List<Partition> partitions) {
        if (partitions.isEmpty()) {
            authorizeList(accountId, binding, roleSession, tableLocation);
            return List.of(new ReadSource(table, null, tableLocation, listObjects(accountId, tableLocation)));
        }
        List<ReadSource> sources = new ArrayList<>();
        for (Partition partition : partitions) {
            StorageDescriptor descriptor = partitionDescriptor(table, partition);
            Table partitionTable = new Table();
            partitionTable.setName(table.getName());
            partitionTable.setParameters(table.getParameters());
            partitionTable.setPartitionKeys(table.getPartitionKeys());
            partitionTable.setStorageDescriptor(descriptor);
            Location location = Location.parse(descriptor.getLocation(), table.getName());
            authorizeList(accountId, binding, roleSession, location);
            sources.add(new ReadSource(partitionTable, partition, location, listObjects(accountId, location)));
        }
        return List.copyOf(sources);
    }

    /**
     * A partition's descriptor with whatever it leaves unset filled in from the table, so the
     * columns, format and CSV options the read depends on are never lost for a sparse partition.
     */
    private static StorageDescriptor partitionDescriptor(Table table, Partition partition) {
        StorageDescriptor tableDescriptor = table.getStorageDescriptor();
        StorageDescriptor own = partition.getStorageDescriptor();
        if (own == null) {
            return tableDescriptor;
        }
        StorageDescriptor merged = new StorageDescriptor();
        merged.setLocation(own.getLocation() == null || own.getLocation().isBlank()
                ? tableDescriptor.getLocation() : own.getLocation());
        merged.setColumns(own.getColumns() == null || own.getColumns().isEmpty()
                ? tableDescriptor.getColumns() : own.getColumns());
        merged.setInputFormat(own.getInputFormat() != null ? own.getInputFormat() : tableDescriptor.getInputFormat());
        merged.setOutputFormat(own.getOutputFormat() != null ? own.getOutputFormat() : tableDescriptor.getOutputFormat());
        merged.setSerdeInfo(own.getSerdeInfo() != null ? own.getSerdeInfo() : tableDescriptor.getSerdeInfo());
        merged.setParameters(own.getParameters() != null ? own.getParameters() : tableDescriptor.getParameters());
        merged.setCompressed(own.getCompressed() != null ? own.getCompressed() : tableDescriptor.getCompressed());
        return merged;
    }

    private List<S3Object> listObjects(String accountId, Location location) {
        return RequestScopes.callAs(accountId, () -> {
            List<S3Object> objects = new ArrayList<>();
            String token = null;
            do {
                S3Service.ListObjectsResult page = s3Service.listObjectsWithPrefixes(location.bucket(), location.prefix(), "", 1000, token, null);
                objects.addAll(page.objects());
                token = page.isTruncated() ? page.nextContinuationToken() : null;
            } while (token != null);
            objects.sort(Comparator.comparing(S3Object::getKey));
            return objects;
        });
    }

    private String fingerprint(Table table, List<Partition> partitions, List<S3Object> objects) {
        StringBuilder value = new StringBuilder().append(table.getVersionId()).append('|').append(table.getUpdateTime()).append('|');
        if (!partitions.isEmpty()) {
            partitions.stream().map(partition -> partition.getValues() + "=" + (partition.getStorageDescriptor() == null ? "" : partition.getStorageDescriptor().getLocation())).sorted().forEach(entry -> value.append(entry).append(';'));
        }
        for (S3Object object : objects) {
            value.append(object.getKey()).append(':').append(object.getSize()).append(':').append(object.getETag()).append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void load(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding, Table table,
                      List<ReadSource> sources) {
        GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(table);
        if (plan.columns().isEmpty()) {
            throw new SpectrumSqlException("0A000", "Glue table \"" + binding.schemaName() + "." + table.getName() + "\" declares no columns");
        }
        String schema = quote(binding.schemaName());
        String target = schema + "." + quote(table.getName());
        String staging = schema + "." + quote(table.getName() + "__stg");
        String definitions = plan.columns().stream().map(column -> quote(column.getName()) + " " + GlueTypeMapper.toPostgres(column.getType())).collect(Collectors.joining(", "));
        boolean stagingCreated = false;
        String scratchKey = null;
        try {
            backend.execute("DROP TABLE IF EXISTS " + staging + "; CREATE TABLE " + staging + " (" + definitions + ")");
            stagingCreated = true;
            boolean hasObjects = sources.stream().anyMatch(source -> !source.objects().isEmpty());
            if (plan.iceberg() || hasObjects) {
                scratchKey = "spectrum-" + UUID.randomUUID() + ".csv";
                byte[] csv = readWithDuckDb(session.accountId(), table, sources,
                        config.services().redshift().spectrumMaxRows(), scratchKey);
                long rows = backend.copyIn("COPY " + staging + " FROM STDIN " + COPY_OPTIONS, new ByteArrayInputStream(csv));
                if (rows > config.services().redshift().spectrumMaxRows()) {
                    throw new SpectrumReadException(SQLSTATE_LOAD_FAILED, "External table \"" + binding.schemaName() + "." + table.getName() + "\" exceeds configured row limit");
                }
            }
            backend.execute("DROP TABLE IF EXISTS " + target + "; ALTER TABLE " + staging + " RENAME TO " + quote(table.getName()));
            stagingCreated = false;
        } catch (RuntimeException exception) {
            if (stagingCreated) {
                try {
                    backend.execute("DROP TABLE IF EXISTS " + staging);
                } catch (RuntimeException cleanupFailure) {
                    LOG.warnv(cleanupFailure, "Could not drop Spectrum staging table {0}", staging);
                }
            }
            throw exception;
        } finally {
            if (scratchKey != null) {
                String cleanupKey = scratchKey;
                try {
                    RequestScopes.runAs(session.accountId(), () -> s3Service.deleteObject(SCRATCH_BUCKET, cleanupKey));
                } catch (RuntimeException exception) {
                    LOG.warnv(exception, "Could not delete Spectrum scratch object {0}", cleanupKey);
                }
            }
        }
    }

    private byte[] readWithDuckDb(String accountId, Table table, List<ReadSource> sources, long maxRows,
                                  String scratchKey) {
        RequestScopes.runAs(accountId, () -> {
            try {
                s3Service.createBucket(SCRATCH_BUCKET, config.defaultRegion());
            } catch (AwsException exception) {
                if (!"BucketAlreadyOwnedByYou".equals(exception.getErrorCode())) {
                    throw exception;
                }
            }
        });
        List<String> selects = sources.stream().filter(source -> source.partition() == null || !source.objects().isEmpty())
                .map(this::readSelect).toList();
        if (selects.isEmpty() && GlueTableResolver.isIcebergTable(table)) {
            GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(table);
            selects = List.of("SELECT " + projection(plan.columns()) + " FROM " + plan.fromClause());
        }
        String query = selects.size() == 1 ? selects.getFirst()
                : "SELECT * FROM (" + String.join(" UNION ALL ", selects) + ") AS spectrum_partitions";
        String setup = GlueTableResolver.isIcebergTable(table) ? ICEBERG_SETUP : null;
        duckClient.execute(query + " LIMIT " + (maxRows + 1), setup,
                "s3://" + SCRATCH_BUCKET + "/" + scratchKey, accountId);
        return RequestScopes.callAs(accountId, () -> s3Service.getObject(SCRATCH_BUCKET, scratchKey).getData());
    }

    private String readSelect(ReadSource source) {
        GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(source.table());
        String projection;
        if (source.partition() == null) {
            projection = projection(plan.columns());
        } else {
            List<Column> dataColumns = source.table().getStorageDescriptor().getColumns();
            List<String> parts = new ArrayList<>(dataColumns.stream()
                    .map(column -> GlueTypeMapper.duckProjection(column.getName(), column.getType())).toList());
            List<Column> partitionKeys = source.table().getPartitionKeys();
            List<String> values = source.partition().getValues();
            for (int index = 0; index < partitionKeys.size(); index++) {
                Column key = partitionKeys.get(index);
                String value = index < values.size() ? values.get(index) : null;
                String expression = value == null || "__HIVE_DEFAULT_PARTITION__".equals(value)
                        ? "CAST(NULL AS VARCHAR)"
                        : "CAST('" + value.replace("'", "''") + "' AS VARCHAR)";
                parts.add("COALESCE(" + expression + ", '\\N') AS " + quote(key.getName()));
            }
            projection = String.join(", ", parts);
        }
        return "SELECT " + projection + " FROM " + plan.fromClause();
    }

    private static String projection(List<Column> columns) {
        return columns.stream().map(column -> GlueTypeMapper.duckProjection(column.getName(), column.getType()))
                .collect(Collectors.joining(", "));
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record Location(String bucket, String prefix) {
        static Location parse(Table table) {
            String location = table.getStorageDescriptor() == null ? null : table.getStorageDescriptor().getLocation();
            return parse(location, table.getName());
        }

        static Location parse(String location, String tableName) {
            if (location == null || location.isBlank() || !location.startsWith("s3://")) {
                throw new SpectrumSqlException("0A000", "Glue table \"" + tableName + "\" has no supported storage location");
            }
            String rest = location.substring(5);
            int slash = rest.indexOf('/');
            String bucket = slash < 0 ? rest : rest.substring(0, slash);
            String prefix = slash < 0 ? "" : rest.substring(slash + 1);
            return new Location(bucket, prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/");
        }
    }

    private record ReadSource(Table table, Partition partition, Location location, List<S3Object> objects) {
    }
}
