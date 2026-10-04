package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@ApplicationScoped
public class ExternalTableMaterializer {
    private static final Logger LOG = Logger.getLogger(ExternalTableMaterializer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String SCRATCH_BUCKET = S3Service.REDSHIFT_SPECTRUM_SCRATCH_BUCKET;
    private static final String SQLSTATE_LOAD_FAILED = "58030";
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    private static final long LOCK_TIMEOUT_SECONDS = 30;
    private static final String SCRATCH_KEY_PREFIX = "spectrum-";
    private static final Duration STALE_SCRATCH_AGE = Duration.ofHours(1);
    private static final String ICEBERG_SETUP = "INSTALL iceberg; LOAD iceberg;\n";
    private static final String ICEBERG_MANIFESTS = "SELECT DISTINCT manifest_path FROM read_avro(%s)";
    private static final String ICEBERG_DATA_FILES = "SELECT DISTINCT file_path FROM iceberg_metadata(%s) "
            + "WHERE status IN ('ADDED', 'EXISTING')";
    private static final String COPY_OPTIONS = "WITH (FORMAT csv, HEADER true, NULL '\\N')";
    public enum Outcome { NOT_EXTERNAL, CURRENT, LOADED }

    private final FlociDuckClient duckClient;
    private final GlueService glueService;
    private final S3Service s3Service;
    private final IamService iamService;
    private final EmulatorConfig config;
    private final ConcurrentHashMap<String, String> fingerprints = new ConcurrentHashMap<>();
    /** Column definitions each loaded table was created with, to tell a data reload from a schema change. */
    private final ConcurrentHashMap<String, String> schemaSignatures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Set<String> sweptAccounts = ConcurrentHashMap.newKeySet();

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
            authorizeIcebergMetadata(session.accountId(), binding, roleSession, table, location);
            authorizeIcebergManifests(session.accountId(), binding, roleSession, table, location);
            List<Partition> partitions = partitions(session.accountId(), binding, table);
            List<ReadSource> sources = readSources(session.accountId(), binding, roleSession, table, location, partitions);
            List<S3Object> objects = sources.stream().flatMap(source -> source.objects().stream()).toList();
            authorizeObjects(session.accountId(), binding, roleSession, sources);
            authorizeIcebergDataFiles(session.accountId(), binding, roleSession, table, location);
            String cacheKey = cacheKey(session, binding, tableName);
            String fingerprint = fingerprint(table, partitions, objects);
            // An Iceberg snapshot can change through its metadata or manifests while the Glue table and the
            // listed objects stay the same, so no fingerprint can vouch for it: it is read on every query.
            boolean cacheable = !session.inTransaction() && !GlueTableResolver.isIcebergTable(table);
            if (cacheable && fingerprint.equals(fingerprints.get(cacheKey))) {
                return Outcome.CURRENT;
            }
            ReentrantLock lock = locks.computeIfAbsent(cacheKey, ignored -> new ReentrantLock());
            acquire(lock, label, session.inTransaction());
            try {
                if (cacheable && fingerprint.equals(fingerprints.get(cacheKey))) {
                    return Outcome.CURRENT;
                }
                String previousSignature = schemaSignatures.get(cacheKey);
                String definitions = load(backend, session, binding, table, sources, previousSignature);
                if (session.inTransaction()) {
                    // Transactional DDL may roll back, so the rows cannot be trusted afterwards. The schema
                    // signature stays when this load kept the committed columns, so a view that depends on the
                    // table can still be refilled in place; a replaced table may roll back to the old columns.
                    fingerprints.remove(cacheKey);
                    if (!definitions.equals(previousSignature)) {
                        schemaSignatures.remove(cacheKey);
                    }
                } else {
                    if (cacheable) {
                        fingerprints.put(cacheKey, fingerprint);
                    } else {
                        fingerprints.remove(cacheKey);
                    }
                    schemaSignatures.put(cacheKey, definitions);
                }
                return Outcome.LOADED;
            } finally {
                lock.unlock();
            }
        } catch (SpectrumSqlException | SpectrumReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // the cause can carry DuckDB request text, so it goes to the log and not to the client
            LOG.warnv(exception, "Unable to load external table {0}", label);
            throw new SpectrumReadException(SQLSTATE_LOAD_FAILED, "Unable to load external table \"" + label + "\"", exception);
        } finally {
            RedshiftRoleAccess.releaseRoleSession(roleSession, binding.iamRoleArn(), iamService);
        }
    }

    /**
     * A session inside a transaction can hold a PostgreSQL lock on the table until it commits, so
     * waiting forever on the Java lock here could deadlock in a way PostgreSQL's detector cannot see.
     * A session outside a transaction holds no PostgreSQL lock, so it can wait out a slow load.
     */
    private static void acquire(ReentrantLock lock, String label, boolean inTransaction) {
        try {
            if (!inTransaction) {
                lock.lockInterruptibly();
            } else if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new SpectrumReadException(SQLSTATE_LOAD_FAILED,
                        "Timed out waiting to load external table \"" + label + "\"");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpectrumReadException(SQLSTATE_LOAD_FAILED,
                    "Interrupted while waiting to load external table \"" + label + "\"", exception);
        }
    }

    public void forget(String clusterKey, String databaseName, String schemaName, String tableName) {
        forgetMatching(key -> key.equals(clusterKey + "|" + databaseName + "|" + schemaName + "|" + tableName));
    }

    /** Forgets every table of one external schema, so a dropped and recreated schema is reloaded. */
    public void forgetSchema(String clusterKey, String databaseName, String schemaName) {
        String prefix = schemaPrefix(clusterKey, databaseName, schemaName);
        forgetMatching(key -> key.startsWith(prefix));
    }

    private void forgetMatching(Predicate<String> matches) {
        fingerprints.keySet().removeIf(matches);
        schemaSignatures.keySet().removeIf(matches);
        // Keep locks: removing a held lock lets another thread create a second lock for the same table.
    }

    /** Tables of one external schema this materializer has loaded into PostgreSQL and still tracks. */
    public Set<String> loadedTables(String clusterKey, String databaseName, String schemaName) {
        String prefix = schemaPrefix(clusterKey, databaseName, schemaName);
        return fingerprints.keySet().stream().filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length())).collect(Collectors.toSet());
    }

    public void forgetCluster(String clusterKey) {
        forgetMatching(key -> key.startsWith(clusterKey + "|"));
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
                    s3Service, iamService, roleSession, binding.iamRoleArn(), location.bucket(), location.prefix()));
        } catch (S3CopySimulator.S3TransferException exception) {
            throw new SpectrumSqlException(exception.sqlState(), exception.getMessage());
        }
    }

    /**
     * DuckDB follows an Iceberg table's {@code metadata_location}, a free-form Glue parameter, so it
     * must stay inside the location the role was authorized for and be readable by the role itself.
     */
    private void authorizeIcebergMetadata(String accountId, ExternalSchemaBinding binding,
                                          RedshiftRoleAccess.RoleSession roleSession, Table table, Location location) {
        if (!GlueTableResolver.isIcebergTable(table)) {
            return;
        }
        String metadata = GlueTableResolver.icebergMetadataLocation(table);
        if (metadata == null || metadata.isBlank()) {
            return;
        }
        if (!metadata.startsWith("s3://")) {
            throw new SpectrumSqlException("0A000", "Iceberg table \"" + table.getName() + "\" has an unsupported metadata_location");
        }
        String rest = metadata.substring(5);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String key = slash < 0 ? "" : rest.substring(slash + 1);
        if (!bucket.equals(location.bucket()) || !key.startsWith(location.prefix())) {
            throw new SpectrumSqlException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "Iceberg metadata_location of \"" + table.getName() + "\" is outside the table location");
        }
        try {
            RequestScopes.runAs(accountId, () -> RedshiftRoleAccess.authorizeRoleRead(
                    s3Service, iamService, roleSession, binding.iamRoleArn(), bucket, key));
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

    /**
     * Iceberg manifests can name data files outside the table location. DuckDB performs the scan
     * with account credentials, so authorize each live data-file path as the bound role first.
     * The scan re-reads the metadata afterwards, so a metadata object overwritten between the two
     * steps is not covered. Before routing user queries here, pin the authorized snapshot and its
     * file list for the scan, or enforce the role during the scan in the sidecar.
     */
    private void authorizeIcebergDataFiles(String accountId, ExternalSchemaBinding binding,
                                           RedshiftRoleAccess.RoleSession roleSession, Table table, Location location) {
        if (!GlueTableResolver.isIcebergTable(table)) {
            return;
        }
        String metadata = GlueTableResolver.icebergMetadataLocation(table);
        if (metadata == null || metadata.isBlank()) {
            throw new SpectrumSqlException("0A000", "Iceberg table \"" + table.getName()
                    + "\" has no supported metadata_location");
        }
        String sql = ICEBERG_DATA_FILES.formatted(sqlPathLiteral(metadata));
        List<Map<String, Object>> rows = duckClient.query(sql, ICEBERG_SETUP, accountId);
        for (Map<String, Object> row : rows) {
            Object value = row.get("file_path");
            if (!(value instanceof String filePath) || filePath.isBlank()) {
                throw new SpectrumSqlException("XX000", "Iceberg manifest contains an invalid data-file path");
            }
            S3Location file = icebergFileLocation(filePath, location);
            try {
                RequestScopes.runAs(accountId, () -> RedshiftRoleAccess.authorizeRoleRead(
                        s3Service, iamService, roleSession, binding.iamRoleArn(), file.bucket(), file.key()));
            } catch (S3CopySimulator.S3TransferException exception) {
                throw new SpectrumSqlException(exception.sqlState(), exception.getMessage());
            }
        }
    }

    /**
     * Iceberg's metadata scan reads manifest lists and manifests before it can return data-file paths.
     * Resolve and authorize both levels first, so the sidecar cannot read them using the emulator's
     * account credentials when the bound Redshift role is denied access.
     */
    private void authorizeIcebergManifests(String accountId, ExternalSchemaBinding binding,
                                           RedshiftRoleAccess.RoleSession roleSession, Table table, Location location) {
        if (!GlueTableResolver.isIcebergTable(table)) {
            return;
        }
        String metadata = GlueTableResolver.icebergMetadataLocation(table);
        if (metadata == null || metadata.isBlank()) {
            return;
        }
        S3Location metadataObjectLocation = icebergFileLocation(metadata, location);
        S3Object metadataObject = RequestScopes.callAs(accountId,
                () -> s3Service.getObject(metadataObjectLocation.bucket(), metadataObjectLocation.key()));
        if (metadataObject == null || metadataObject.getData() == null) {
            throw new SpectrumSqlException("XX000", "Unable to read Iceberg table metadata");
        }
        JsonNode metadataJson;
        try {
            metadataJson = JSON.readTree(metadataObject.getData());
        } catch (IOException exception) {
            throw new SpectrumSqlException("XX000", "Iceberg table metadata is not valid JSON");
        }
        String currentSnapshotId = metadataJson.path("current-snapshot-id").asText();
        if (currentSnapshotId.isBlank() || "-1".equals(currentSnapshotId)) {
            if (metadataJson.path("snapshots").size() > 0) {
                // the scan could pick a snapshot whose manifests were never authorized
                throw new SpectrumSqlException("XX000", "Iceberg table metadata has snapshots but no current snapshot");
            }
            return;
        }
        String manifestListPath = null;
        for (JsonNode snapshot : metadataJson.path("snapshots")) {
            if (currentSnapshotId.equals(snapshot.path("snapshot-id").asText())) {
                manifestListPath = snapshot.path("manifest-list").asText();
                break;
            }
        }
        if (manifestListPath == null || manifestListPath.isBlank()) {
            throw new SpectrumSqlException("XX000", "Iceberg current snapshot has no manifest-list path");
        }
        S3Location manifestList = icebergFileLocation(manifestListPath, location);
        authorizeIcebergObject(accountId, binding, roleSession, manifestList);
        String manifestListUri = "s3://" + manifestList.bucket() + "/" + manifestList.key();
        List<Map<String, Object>> manifests = duckClient.query(
                ICEBERG_MANIFESTS.formatted(sqlPathLiteral(manifestListUri)), ICEBERG_SETUP, accountId);
        for (Map<String, Object> manifest : manifests) {
            Object manifestValue = manifest.get("manifest_path");
            if (!(manifestValue instanceof String manifestPath) || manifestPath.isBlank()) {
                throw new SpectrumSqlException("XX000", "Iceberg manifest list contains an invalid manifest path");
            }
            authorizeIcebergObject(accountId, binding, roleSession, icebergFileLocation(manifestPath, location));
        }
    }

    private void authorizeIcebergObject(String accountId, ExternalSchemaBinding binding,
                                        RedshiftRoleAccess.RoleSession roleSession, S3Location object) {
        try {
            RequestScopes.runAs(accountId, () -> RedshiftRoleAccess.authorizeRoleRead(
                    s3Service, iamService, roleSession, binding.iamRoleArn(), object.bucket(), object.key()));
        } catch (S3CopySimulator.S3TransferException exception) {
            throw new SpectrumSqlException(exception.sqlState(), exception.getMessage());
        }
    }

    private static S3Location icebergFileLocation(String filePath, Location tableLocation) {
        if (filePath.startsWith("s3://")) {
            String rest = filePath.substring(5);
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash == rest.length() - 1) {
                throw new SpectrumSqlException("XX000", "Iceberg manifest contains an invalid S3 data-file path");
            }
            return new S3Location(rest.substring(0, slash), rest.substring(slash + 1));
        }
        if (filePath.contains("://") || filePath.isBlank()) {
            throw new SpectrumSqlException("0A000", "Iceberg data-file path uses an unsupported location");
        }
        String key = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        if (!tableLocation.prefix().isEmpty() && !key.startsWith(tableLocation.prefix())) {
            key = tableLocation.prefix() + key;
        }
        return new S3Location(tableLocation.bucket(), key);
    }

    private static String sqlPathLiteral(String path) {
        String globLiteral = path.replace("[", "[[]").replace("*", "[*]").replace("?", "[?]");
        return "'" + globLiteral.replace("'", "''") + "'";
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
        if (partitions.isEmpty() && hasPartitionKeys(table)) {
            // Spectrum reads only registered partitions, so a partitioned table with none returns no rows.
            return List.of();
        }
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

    private static boolean hasPartitionKeys(Table table) {
        return !GlueTableResolver.isIcebergTable(table)
                && table.getPartitionKeys() != null && !table.getPartitionKeys().isEmpty();
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
            objects.removeIf(object -> isIgnoredBySpectrum(object.getKey(), location.prefix()));
            objects.sort(Comparator.comparing(S3Object::getKey));
            return objects;
        });
    }

    /**
     * Spectrum ignores hidden files (names starting with a period, underscore or hash mark, or ending with
     * a tilde), which job output such as {@code _SUCCESS} relies on, and a folder marker has no data.
     * A file under a hidden folder such as {@code _temporary/} is intermediate output, so every path
     * component below the table or partition prefix is checked, not only the file name.
     */
    private static boolean isIgnoredBySpectrum(String key, String prefix) {
        String relative = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
        if (relative.isEmpty() || relative.endsWith("/")) {
            return true;
        }
        for (String component : relative.split("/")) {
            if (component.startsWith(".") || component.startsWith("_") || component.startsWith("#")
                    || component.endsWith("~")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A partition's values and everything in its descriptor that the read depends on (location, columns,
     * formats, SerDe and its parameters), so that any change to how its files are decoded reloads the table.
     */
    private static String partitionEntry(Table table, Partition partition) {
        StorageDescriptor descriptor = partitionDescriptor(table, partition);
        StringBuilder entry = new StringBuilder().append(partition.getValues()).append('=');
        if (descriptor == null) {
            return entry.toString();
        }
        entry.append(descriptor.getLocation()).append('|')
                .append(descriptor.getInputFormat()).append('|')
                .append(descriptor.getOutputFormat()).append('|')
                .append(descriptor.getCompressed()).append('|');
        if (descriptor.getColumns() != null) {
            descriptor.getColumns().forEach(column -> entry.append(column.getName()).append(':')
                    .append(column.getType()).append(','));
        }
        entry.append('|').append(new TreeMap<>(descriptor.getParameters() == null
                ? Map.of() : descriptor.getParameters())).append('|');
        if (descriptor.getSerdeInfo() != null) {
            entry.append(descriptor.getSerdeInfo().getSerializationLibrary()).append(new TreeMap<>(
                    descriptor.getSerdeInfo().getParameters() == null
                            ? Map.of() : descriptor.getSerdeInfo().getParameters()));
        }
        return entry.toString();
    }

    private String fingerprint(Table table, List<Partition> partitions, List<S3Object> objects) {
        StringBuilder value = new StringBuilder().append(table.getVersionId()).append('|').append(table.getUpdateTime()).append('|');
        if (!partitions.isEmpty()) {
            partitions.stream().map(partition -> partitionEntry(table, partition)).sorted()
                    .forEach(entry -> value.append(entry).append(';'));
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

    /**
     * Loads the table into PostgreSQL and returns the column definitions it was created with. When they
     * match {@code loadedDefinitions} the target table is refilled in place, so views and grants that
     * depend on it survive; a schema change replaces the table instead.
     */
    private String load(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding, Table table,
                        List<ReadSource> sources, String loadedDefinitions) {
        GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(table);
        if (plan.columns().isEmpty()) {
            throw new SpectrumSqlException("0A000", "Glue table \"" + binding.schemaName() + "." + table.getName() + "\" declares no columns");
        }
        String schema = quote(binding.schemaName());
        String target = schema + "." + quote(table.getName());
        String stagingName = "floci_spectrum_stg_" + UUID.randomUUID().toString().replace("-", "");
        String staging = "pg_temp." + quote(stagingName);
        String definitions = plan.columns().stream().map(column -> quote(column.getName()) + " " + GlueTypeMapper.toPostgres(column.getType())).collect(Collectors.joining(", "));
        boolean stagingCreated = false;
        String scratchKey = null;
        try {
            backend.execute("CREATE TEMP TABLE " + quote(stagingName) + " (" + definitions + ")");
            stagingCreated = true;
            boolean hasObjects = sources.stream().anyMatch(source -> !source.objects().isEmpty());
            if (plan.iceberg() || hasObjects) {
                // before a key exists: a scratch bucket that is not ours must see no write and no delete
                prepareScratchBucket(session.accountId());
                scratchKey = SCRATCH_KEY_PREFIX + UUID.randomUUID() + ".csv";
                readWithDuckDb(session.accountId(), table, sources,
                        config.services().redshift().spectrumMaxRows(), scratchKey);
                long rows;
                String resultKey = scratchKey;
                try (InputStream csv = RequestScopes.callAs(session.accountId(),
                        () -> s3Service.openObjectStream(SCRATCH_BUCKET, resultKey, null))) {
                    rows = backend.copyIn("COPY " + staging + " FROM STDIN " + COPY_OPTIONS, csv);
                } catch (IOException exception) {
                    throw new SpectrumReadException(SQLSTATE_LOAD_FAILED,
                            "Unable to stream external table data into PostgreSQL", exception);
                }
                if (rows > config.services().redshift().spectrumMaxRows()) {
                    throw new SpectrumReadException(SQLSTATE_LOAD_FAILED, "External table \"" + binding.schemaName() + "." + table.getName() + "\" exceeds configured row limit");
                }
            }
            if (definitions.equals(loadedDefinitions)) {
                backend.execute("CREATE TABLE IF NOT EXISTS " + target + " (" + definitions + "); TRUNCATE " + target
                        + "; INSERT INTO " + target + " SELECT * FROM " + staging + "; DROP TABLE " + staging);
            } else {
                backend.execute("DROP TABLE IF EXISTS " + target + "; CREATE TABLE " + target + " (" + definitions
                        + "); INSERT INTO " + target + " SELECT * FROM " + staging + "; DROP TABLE " + staging);
            }
            stagingCreated = false;
            return definitions;
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

    private void prepareScratchBucket(String accountId) {
        RequestScopes.runAs(accountId, () -> {
            ensureScratchBucket();
            if (sweptAccounts.add(accountId)) {
                sweepStaleScratchObjects();
            }
        });
    }

    private void readWithDuckDb(String accountId, Table table, List<ReadSource> sources, long maxRows,
                                String scratchKey) {
        List<String> selects = sources.stream().filter(source -> source.partition() == null || !source.objects().isEmpty())
                .map(this::readSelect).toList();
        if (selects.isEmpty() && GlueTableResolver.isIcebergTable(table)) {
            GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(table);
            selects = List.of("SELECT " + projection(plan.columns()) + " FROM " + fromClause(plan, table));
        }
        String query = selects.size() == 1 ? selects.getFirst()
                : "SELECT * FROM (" + String.join(" UNION ALL ", selects) + ") AS spectrum_partitions";
        String setup = GlueTableResolver.isIcebergTable(table) ? ICEBERG_SETUP : null;
        duckClient.execute(query + " LIMIT " + (maxRows + 1), setup,
                "s3://" + SCRATCH_BUCKET + "/" + scratchKey, accountId);
    }

    /**
     * Creates the scratch bucket and tags it as ours. A bucket of that name that carries no tag belongs to
     * the user: it is neither written to nor swept, and the load fails instead of mixing with it.
     */
    private void ensureScratchBucket() {
        boolean created = true;
        try {
            s3Service.createBucket(SCRATCH_BUCKET, config.defaultRegion());
        } catch (AwsException exception) {
            if (!"BucketAlreadyOwnedByYou".equals(exception.getErrorCode())) {
                throw exception;
            }
            created = false;
        }
        if (created) {
            s3Service.putBucketTagging(SCRATCH_BUCKET,
                    Map.of(S3Service.INTERNAL_BUCKET_TAG_KEY, S3Service.REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE));
        } else if (!S3Service.REDSHIFT_SPECTRUM_SCRATCH_TAG_VALUE.equals(
                s3Service.getBucketTagging(SCRATCH_BUCKET).get(S3Service.INTERNAL_BUCKET_TAG_KEY))) {
            throw new SpectrumReadException(SQLSTATE_LOAD_FAILED, "Bucket \"" + SCRATCH_BUCKET
                    + "\" already exists and is not the Spectrum scratch bucket");
        }
    }

    /**
     * A JVM that dies mid-load leaves its scratch object behind. The first load of an account removes the
     * old ones; a recent object may belong to a load still running, so only stale ones go.
     */
    private void sweepStaleScratchObjects() {
        Instant cutoff = Instant.now().minus(STALE_SCRATCH_AGE);
        try {
            String token = null;
            do {
                S3Service.ListObjectsResult page = s3Service.listObjectsWithPrefixes(
                        SCRATCH_BUCKET, SCRATCH_KEY_PREFIX, "", 1000, token, null);
                for (S3Object object : page.objects()) {
                    if (object.getLastModified().isBefore(cutoff)) {
                        s3Service.deleteObject(SCRATCH_BUCKET, object.getKey());
                    }
                }
                token = page.isTruncated() ? page.nextContinuationToken() : null;
            } while (token != null);
        } catch (RuntimeException exception) {
            LOG.warnv(exception, "Could not sweep stale Spectrum scratch objects from {0}", SCRATCH_BUCKET);
        }
    }

    private String readSelect(ReadSource source) {
        List<String> objectUris = source.objects().stream()
                .map(object -> "s3://" + source.location().bucket() + "/" + object.getKey()).toList();
        GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(source.table(), objectUris);
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
                if (value != null && GlueTypeMapper.exceedsWidth(key.getType(), value)) {
                    // a data column wider than its width is NULL, so a partition value is as well
                    value = null;
                }
                String expression = value == null || "__HIVE_DEFAULT_PARTITION__".equals(value)
                        ? "CAST(NULL AS VARCHAR)"
                        : "CAST('" + value.replace("'", "''") + "' AS VARCHAR)";
                parts.add("COALESCE(" + expression + ", '\\N') AS " + quote(key.getName()));
            }
            projection = String.join(", ", parts);
        }
        return "SELECT " + projection + " FROM " + fromClause(plan, source.table());
    }

    /**
     * An Iceberg scan reads the same glob-escaped metadata literal that the authorization queries used,
     * so that every step sees one path and none can match a different object.
     */
    private static String fromClause(GlueTableResolver.ReadPlan plan, Table table) {
        if (!plan.iceberg()) {
            return plan.fromClause();
        }
        return "iceberg_scan(" + sqlPathLiteral(GlueTableResolver.icebergMetadataLocation(table)) + ")";
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

    private record S3Location(String bucket, String key) {
    }
}
