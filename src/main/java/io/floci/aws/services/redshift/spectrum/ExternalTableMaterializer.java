package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.redshift.proxy.RedshiftRoleAccess;
import io.floci.aws.services.redshift.proxy.S3CopySimulator;
import io.floci.aws.services.s3.S3ResourceArns;
import io.floci.aws.services.s3.S3ScratchSpace;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class ExternalTableMaterializer {
    private static final Logger LOG = Logger.getLogger(ExternalTableMaterializer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String SCRATCH_BUCKET = S3Service.REDSHIFT_SPECTRUM_SCRATCH_BUCKET;
    private static final String SQLSTATE_LOAD_FAILED = "58030";
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    private static final Pattern DUCKDB_HTTP_403 = Pattern.compile(
            "\\bHTTP(?:/[0-9.]+)?\\s+(?:Error:\\s+)?(?:HTTP(?:/[0-9.]+)?\\s+)?403\\b|\\b403\\s*\\(Forbidden\\)|\\b403\\s+Forbidden\\b");
    private static final long LOCK_TIMEOUT_SECONDS = 30;
    private static final String SCRATCH_KEY_PREFIX = "spectrum-";
    private static final String HIVE_DEFAULT_PARTITION = "__HIVE_DEFAULT_PARTITION__";
    private static final String NULL_KEY = "\u0000null";
    private static final Set<String> INTEGER_TYPES = Set.of("tinyint", "smallint", "int", "integer", "bigint");
    private static final Pattern DECIMAL_SCALE = Pattern.compile("^decimal\\(\\d+,(\\d+)\\)$");
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
    private final ExternalCatalogLocks catalogLocks;
    private final ConcurrentHashMap<String, String> fingerprints = new ConcurrentHashMap<>();
    /** Column definitions each loaded table was created with, to tell a data reload from a schema change. */
    private final ConcurrentHashMap<String, String> schemaSignatures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Set<String> sweptAccounts = ConcurrentHashMap.newKeySet();
    private final Map<BackendSql, Map<String, PendingLoad>> pendingLoads = new ConcurrentHashMap<>();
    private final Map<String, PendingLoad> latestLoads = new ConcurrentHashMap<>();
    /** The partitions each partitioned table holds in PostgreSQL, so a query loads only the ones it reads. */
    private final ConcurrentHashMap<String, LoadedPartitions> loadedPartitions = new ConcurrentHashMap<>();

    /** Bumped whenever what a table holds changes, so state staged against an older content is recognized as stale. */
    private final ConcurrentHashMap<String, Long> generations = new ConcurrentHashMap<>();
    /** Partitions loaded inside a transaction, per client connection, until that transaction commits or rolls back. */
    private final Map<Object, Map<String, PendingPartitions>> pendingPartitions = new ConcurrentHashMap<>();

    private record PendingLoad(String fingerprint, String definitions) { }

    /** What a partitioned table holds: the table it was loaded for, and a fingerprint per loaded partition. */
    private record LoadedPartitions(String identity, Map<List<String>, String> partitions) { }

    /** Partitions an uncommitted transaction loaded, valid only while the table's generation is unchanged. */
    private record PendingPartitions(LoadedPartitions state, String definitions, long generation) { }

    public void finishCycle(BackendSql backend) {
        pendingLoads.remove(backend);
        discardPendingPartitions(backend);
    }

    private void discardPendingPartitions(BackendSql backend) {
        Object connection = backend.connectionKey();
        if (connection != null) {
            pendingPartitions.remove(connection);
        }
    }

    /** Forgets what the table holds: its content is about to change or can no longer be vouched for. */
    private void dropHeld(String cacheKey) {
        loadedPartitions.remove(cacheKey);
        generations.merge(cacheKey, 1L, Long::sum);
    }

    private void dropStaged(BackendSql backend, String cacheKey) {
        Object connection = backend.connectionKey();
        Map<String, PendingPartitions> staged = connection == null ? null : pendingPartitions.get(connection);
        if (staged != null) {
            staged.remove(cacheKey);
        }
    }

    private long generation(String cacheKey) {
        return generations.getOrDefault(cacheKey, 0L);
    }

    private void publishPendingPartitions(BackendSql backend) {
        Object connection = backend.connectionKey();
        Map<String, PendingPartitions> completed = connection == null ? null : pendingPartitions.remove(connection);
        if (completed == null) {
            return;
        }
        completed.forEach((key, pending) -> {
            ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
            lock.lock();
            try {
                // another load changed the table since this transaction did, so what it staged no longer describes it
                if (generation(key) == pending.generation()) {
                    schemaSignatures.put(key, pending.definitions());
                    loadedPartitions.put(key, pending.state());
                }
            } finally {
                lock.unlock();
            }
        });
    }

    public void finishCycle(BackendSql backend, boolean committed) {
        if (committed) {
            publishPendingPartitions(backend);
        } else {
            discardPendingPartitions(backend);
        }
        Map<String, PendingLoad> completed = pendingLoads.remove(backend);
        if (committed && completed != null) {
            completed.forEach((key, loaded) -> {
                ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
                lock.lock();
                try {
                    if (latestLoads.get(key) == loaded) {
                        fingerprints.put(key, loaded.fingerprint());
                        schemaSignatures.put(key, loaded.definitions());
                    }
                } finally {
                    lock.unlock();
                }
            });
        }
    }

    public ExternalTableMaterializer(FlociDuckClient duckClient, GlueService glueService, S3Service s3Service,
                                     IamService iamService, EmulatorConfig config) {
        this(duckClient, glueService, s3Service, iamService, config, new ExternalCatalogLocks());
    }

    @Inject
    public ExternalTableMaterializer(FlociDuckClient duckClient, GlueService glueService, S3Service s3Service,
                                     IamService iamService, EmulatorConfig config, ExternalCatalogLocks catalogLocks) {
        this.duckClient = duckClient;
        this.glueService = glueService;
        this.s3Service = s3Service;
        this.iamService = iamService;
        this.config = config;
        this.catalogLocks = catalogLocks;
    }

    ExternalCatalogLocks catalogLocks() {
        return catalogLocks;
    }

    public Outcome ensureCurrent(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding,
                                 String tableName) {
        return ensureCurrent(backend, session, binding, tableName, null);
    }

    /**
     * Loads the table the way the query needs it. Given the text of the query, a partitioned table loads only
     * the partitions its WHERE can select, as Spectrum reads only those; without it every partition loads.
     */
    public Outcome ensureCurrent(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding,
                                 String tableName, String sql) {
        return ensureCurrent(backend, session, binding, tableName, sql, List.of());
    }

    /**
     * As {@link #ensureCurrent(BackendSql, SpectrumSession, ExternalSchemaBinding, String, String)} for a query
     * with bind parameters: {@code parameters} holds the text of each bound value, a null where it is unusable.
     * Null {@code parameters} means the statement is only being parsed and its values are not known yet; a
     * partitioned table then loads no partition, enough for PostgreSQL to plan it, and the Bind loads the ones
     * its values select.
     */
    public Outcome ensureCurrent(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding,
                                 String tableName, String sql, List<String> parameters) {
        try (ExternalCatalogLocks.Guard ignored = catalogLocks.read(binding,
                session.inTransaction() || !backend.permitsCachePublication())) {
            return ensureCurrentLocked(backend, session, binding, tableName, sql, parameters);
        }
    }

    private Outcome ensureCurrentLocked(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding,
                                        String tableName, String sql, List<String> parameters) {
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
            if (holdsPartitions(table, session, backend, partitions)) {
                return ensurePartitions(backend, session, binding, roleSession, table, location, partitions, sql,
                        parameters);
            }
            List<ReadSource> sources = readSources(session.accountId(), binding, roleSession, table, location, partitions);
            List<S3Object> objects = sources.stream().flatMap(source -> source.objects().stream()).toList();
            authorizeObjects(session.accountId(), binding, roleSession, sources);
            authorizeIcebergDataFiles(session.accountId(), binding, roleSession, table, location);
            String cacheKey = cacheKey(session, binding, tableName);
            String fingerprint = fingerprint(table, partitions, objects);
            // An Iceberg snapshot can change through its metadata or manifests while the Glue table and the
            // listed objects stay the same, so no fingerprint can vouch for it: it is read on every query.
            boolean cacheable = !session.inTransaction() && !GlueTableResolver.isIcebergTable(table);
            Map<String, PendingLoad> localLoads = backend.permitsCachePublication() ? Map.of()
                    : pendingLoads.computeIfAbsent(backend, ignored -> new ConcurrentHashMap<>());
            PendingLoad local = localLoads.get(cacheKey);
            if (!GlueTableResolver.isIcebergTable(table) && local != null && fingerprint.equals(local.fingerprint())) {
                return Outcome.CURRENT;
            }
            if (cacheable && fingerprint.equals(fingerprints.get(cacheKey))) {
                return Outcome.CURRENT;
            }
            ReentrantLock lock = locks.computeIfAbsent(cacheKey, ignored -> new ReentrantLock());
            acquire(lock, label, session.inTransaction());
            try {
                if (cacheable && fingerprint.equals(fingerprints.get(cacheKey))) {
                    return Outcome.CURRENT;
                }
                String previousSignature = local == null ? schemaSignatures.get(cacheKey) : local.definitions();
                latestLoads.remove(cacheKey);
                // a load of every partition replaces what the table held, so the partitions it holds are no longer known
                dropHeld(cacheKey);
                dropStaged(backend, cacheKey);
                String definitions = load(backend, session, binding, roleSession, table, sources, previousSignature, null);
                if (!backend.permitsCachePublication()) {
                    fingerprints.remove(cacheKey);
                    PendingLoad loaded = new PendingLoad(fingerprint, definitions);
                    localLoads.put(cacheKey, loaded);
                    latestLoads.put(cacheKey, loaded);
                    if (!definitions.equals(schemaSignatures.get(cacheKey))) {
                        schemaSignatures.remove(cacheKey);
                    }
                } else if (session.inTransaction()) {
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
            if (isAccessDenied(exception)) {
                throw new SpectrumReadException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                        "Access denied for external table \"" + label + "\"", exception);
            }
            // the cause can carry DuckDB request text, so it goes to the log and not to the client
            LOG.warnv(exception, "Unable to load external table {0}", label);
            throw new SpectrumReadException(SQLSTATE_LOAD_FAILED, "Unable to load external table \"" + label + "\"", exception);
        } finally {
            RedshiftRoleAccess.releaseRoleSession(roleSession, binding.iamRoleArn(), iamService);
        }
    }

    /** Whether what a load puts in PostgreSQL may be undone, so it is only known to be there once the transaction commits. */
    private static boolean loadsMayRollBack(SpectrumSession session, BackendSql backend) {
        return session.inTransaction() || !backend.permitsCachePublication();
    }

    /**
     * Whether the table is loaded partition by partition: a partitioned table that has partitions. A load that may
     * roll back is staged for its connection and published when the transaction commits, which needs a backend that
     * reports where the transaction ends; without one the table is loaded whole.
     */
    private static boolean holdsPartitions(Table table, SpectrumSession session, BackendSql backend,
                                           List<Partition> partitions) {
        return hasPartitionKeys(table) && !partitions.isEmpty()
                && (!loadsMayRollBack(session, backend) || backend.connectionKey() != null);
    }

    /**
     * The table in PostgreSQL holds the partitions loaded so far, and a query adds the ones it reads that are
     * missing or have changed and drops the ones Glue no longer has. A query never removes a partition another
     * query loaded, so concurrent queries with different predicates cannot take each other's rows away, and the
     * predicates of each query still run on the rows in PostgreSQL.
     */
    private Outcome ensurePartitions(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding,
                                     RedshiftRoleAccess.RoleSession roleSession, Table table, Location location,
                                     List<Partition> partitions, String sql, List<String> parameters) {
        String accountId = session.accountId();
        Optional<PartitionPruner.Filter> filter = PartitionPruner.filterFor(sql, binding.schemaName(),
                table.getName(), table.getPartitionKeys(), parameters);
        // partitions that PostgreSQL's casts make equal cannot be told apart by key value, so they are all read
        boolean overlapping = hasOverlappingPartitions(table, partitions);
        // a statement still being parsed has no values to select with, and its Bind loads what it needs
        boolean awaitingValues = parameters == null && PartitionPruner.hasParameters(sql);
        List<Partition> qualified = awaitingValues ? List.of()
                : filter.isEmpty() || overlapping ? partitions
                : partitions.stream().filter(partition -> filter.get().matches(partition.getValues())).toList();
        List<ReadSource> sources = readSources(accountId, binding, roleSession, table, location, qualified);
        authorizeObjects(accountId, binding, roleSession, sources);
        String definitions = columnDefinitions(readPlanWithColumns(binding, table));
        String identity = Digests.sha256Hex(table.getVersionId() + "|" + table.getUpdateTime() + "|" + definitions);
        String cacheKey = cacheKey(session, binding, table.getName());
        String label = binding.schemaName() + "." + table.getName();
        ReentrantLock lock = locks.computeIfAbsent(cacheKey, ignored -> new ReentrantLock());
        acquire(lock, label, session.inTransaction());
        try {
            boolean mayRollBack = loadsMayRollBack(session, backend);
            Object connection = backend.connectionKey();
            PendingPartitions staged = !mayRollBack || connection == null ? null
                    : pendingPartitions.getOrDefault(connection, Map.of()).get(cacheKey);
            LoadedPartitions state;
            String previousSignature;
            if (staged != null) {
                // this transaction's own loads are in the table it reads; they hold only while nothing else changed it
                boolean current = staged.generation() == generation(cacheKey);
                state = current ? staged.state() : null;
                previousSignature = current ? staged.definitions() : null;
            } else {
                state = loadedPartitions.get(cacheKey);
                previousSignature = schemaSignatures.get(cacheKey);
            }
            boolean reset = state == null || !state.identity().equals(identity) || !definitions.equals(previousSignature);
            if (awaitingValues && !reset) {
                // a statement still being parsed loads nothing; its Bind reconciles the table, because a delete by
                // key value here could remove rows of partitions that stay recorded as loaded
                return Outcome.CURRENT;
            }
            Map<List<String>, String> held = reset ? Map.of() : state.partitions();
            Set<List<String>> existing = partitions.stream().map(Partition::getValues).collect(Collectors.toSet());
            List<List<String>> deletions = new ArrayList<>(held.keySet().stream()
                    .filter(values -> !existing.contains(values)).toList());
            List<ReadSource> toLoad = new ArrayList<>();
            Map<List<String>, String> loaded = new HashMap<>(held);
            deletions.forEach(loaded::remove);
            for (ReadSource source : sources) {
                List<String> values = source.partition().getValues();
                String fingerprint = partitionFingerprint(table, source);
                if (!fingerprint.equals(held.get(values))) {
                    toLoad.add(source);
                    if (held.containsKey(values)) {
                        deletions.add(values);
                    }
                    loaded.put(values, fingerprint);
                }
            }
            if (overlapping && !reset &&(!deletions.isEmpty() || !toLoad.isEmpty())) {
                // a delete by key value cannot tell overlapping partitions apart, so the whole table is replaced
                reset = true;
                toLoad.clear();
                toLoad.addAll(sources);
                loaded.clear();
                for (ReadSource source : sources) {
                    loaded.put(source.partition().getValues(), partitionFingerprint(table, source));
                }
            }
            if (!reset && deletions.isEmpty() && toLoad.isEmpty()) {
                return Outcome.CURRENT;
            }
            // what the table holds is about to change, so no fingerprint of a full load can vouch for it
            fingerprints.remove(cacheKey);
            latestLoads.remove(cacheKey);
            pendingLoads.values().forEach(loads -> loads.remove(cacheKey));
            dropHeld(cacheKey);
            dropStaged(backend, cacheKey);
            load(backend, session, binding, roleSession, table, toLoad, previousSignature, reset ? null : deletions);
            LoadedPartitions loadedState = new LoadedPartitions(identity, Map.copyOf(loaded));
            if (mayRollBack) {
                // a rollback restores the committed columns, so a changed signature cannot be trusted afterwards
                if (!definitions.equals(schemaSignatures.get(cacheKey))) {
                    schemaSignatures.remove(cacheKey);
                }
                pendingPartitions.computeIfAbsent(connection, ignored -> new ConcurrentHashMap<>())
                        .put(cacheKey, new PendingPartitions(loadedState, definitions, generation(cacheKey)));
            } else {
                schemaSignatures.put(cacheKey, definitions);
                loadedPartitions.put(cacheKey, loadedState);
            }
            return Outcome.LOADED;
        } finally {
            lock.unlock();
        }
    }

    /** What a partition was loaded from: its descriptor and the objects it listed, with their sizes and ETags. */
    private static String partitionFingerprint(Table table, ReadSource source) {
        StringBuilder value = new StringBuilder(partitionEntry(table, source.partition())).append('|');
        for (S3Object object : source.objects()) {
            value.append(object.getKey()).append(':').append(object.getSize()).append(':').append(object.getETag()).append(';');
        }
        return Digests.sha256Hex(value.toString());
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

    /** Reloads data after a catalog change without dropping the table's grants and dependent views. */
    public void invalidateTable(String clusterKey, String databaseName, String schemaName, String tableName) {
        String key = clusterKey + "|" + databaseName + "|" + schemaName + "|" + tableName;
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            fingerprints.remove(key);
            dropHeld(key);
            latestLoads.remove(key);
            pendingLoads.values().forEach(loads -> loads.remove(key));
        } finally {
            lock.unlock();
        }
    }

    /** Forgets every table of one external schema, so a dropped and recreated schema is reloaded. */
    public void forgetSchema(String clusterKey, String databaseName, String schemaName) {
        String prefix = schemaPrefix(clusterKey, databaseName, schemaName);
        forgetMatching(key -> key.startsWith(prefix));
    }

    private void forgetMatching(Predicate<String> matches) {
        locks.forEach((key, lock) -> {
            if (matches.test(key)) {
                lock.lock();
                try {
                    fingerprints.remove(key);
                    dropHeld(key);
                    schemaSignatures.remove(key);
                    latestLoads.remove(key);
                    pendingLoads.values().forEach(loads -> loads.remove(key));
                } finally {
                    lock.unlock();
                }
            }
        });
        // Keep locks: removing a held lock lets another thread create a second lock for the same table.
    }

    /** Tables of one external schema this materializer has loaded into PostgreSQL and still tracks. */
    public Set<String> loadedTables(String clusterKey, String databaseName, String schemaName) {
        String prefix = schemaPrefix(clusterKey, databaseName, schemaName);
        return Stream.concat(fingerprints.keySet().stream(), loadedPartitions.keySet().stream())
                .filter(key -> key.startsWith(prefix))
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
                    session.iamRoleArns(), RedshiftRoleAccess.STREAMING_ROLE_SESSION_TTL);
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
        List<Map<String, Object>> rows = duckQuery(accountId, roleSession, sql);
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
        List<Map<String, Object>> manifests = duckQuery(accountId, roleSession,
                ICEBERG_MANIFESTS.formatted(sqlPathLiteral(manifestListUri)));
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
        return Digests.sha256Hex(value.toString());
    }

    /**
     * Loads the table into PostgreSQL and returns the column definitions it was created with. When they
     * match {@code loadedDefinitions} the target table is refilled in place, so views and grants that
     * depend on it survive; a schema change replaces the table instead.
     */
    private String load(BackendSql backend, SpectrumSession session, ExternalSchemaBinding binding,
                        RedshiftRoleAccess.RoleSession roleSession, Table table,
                        List<ReadSource> sources, String loadedDefinitions, List<List<String>> deletions) {
        GlueTableResolver.ReadPlan plan = readPlanWithColumns(binding, table);
        String schema = quote(binding.schemaName());
        String target = schema + "." + quote(table.getName());
        String stagingName = "floci_spectrum_stg_" + UUID.randomUUID().toString().replace("-", "");
        String staging = "pg_temp." + quote(stagingName);
        String definitions = columnDefinitions(plan);
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
                if (roleSession != null && roleSession.accessKeyId() != null) {
                    String partition = AwsRegions.partitionFor(config.defaultRegion());
                    String scratchArn = S3ResourceArns.object(partition, SCRATCH_BUCKET, scratchKey);
                    iamService.registerScratchResource(roleSession.accessKeyId(), scratchArn);
                }
                readWithDuckDb(session.accountId(), roleSession, table, sources,
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
            if (deletions != null) {
                // the table already holds other partitions, so only the ones named here are replaced
                String delete = deletions.isEmpty() ? "" : "DELETE FROM " + target + " WHERE "
                        + deletions.stream().map(values -> "(" + partitionCondition(table, values) + ")")
                        .distinct().collect(Collectors.joining(" OR ")) + "; ";
                backend.execute("CREATE TABLE IF NOT EXISTS " + target + " (" + definitions + "); " + delete
                        + "INSERT INTO " + target + " SELECT * FROM " + staging + "; DROP TABLE " + staging);
            } else if (definitions.equals(loadedDefinitions)) {
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
            if (roleSession != null && roleSession.accessKeyId() != null) {
                iamService.clearScratchResource(roleSession.accessKeyId());
            }
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

    private static GlueTableResolver.ReadPlan readPlanWithColumns(ExternalSchemaBinding binding, Table table) {
        GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(table);
        if (plan.columns().isEmpty()) {
            throw new SpectrumSqlException("0A000", "Glue table \"" + binding.schemaName() + "." + table.getName() + "\" declares no columns");
        }
        return plan;
    }

    private static String columnDefinitions(GlueTableResolver.ReadPlan plan) {
        return plan.columns().stream().map(column -> quote(column.getName()) + " " + GlueTypeMapper.toPostgres(column.getType()))
                .collect(Collectors.joining(", "));
    }

    /**
     * Matches the rows a partition loaded: its key columns hold the partition values cast to the column types,
     * with the Hive default partition and a value wider than its column as NULL, as {@link #readSelect} projects them.
     */
    private static String partitionCondition(Table table, List<String> values) {
        List<Column> keys = table.getPartitionKeys();
        List<String> terms = new ArrayList<>();
        for (int index = 0; index < keys.size(); index++) {
            Column key = keys.get(index);
            String value = partitionValue(key, values, index);
            terms.add(value == null ? quote(key.getName()) + " IS NULL"
                    : quote(key.getName()) + " = CAST('" + value.replace("'", "''") + "' AS "
                            + GlueTypeMapper.toPostgres(key.getType()) + ")");
        }
        return String.join(" AND ", terms);
    }

    /** A partition key value as it is loaded: null when it is missing, the Hive default partition or too wide. */
    private static String partitionValue(Column key, List<String> values, int index) {
        String value = index < values.size() ? values.get(index) : null;
        if (value == null || HIVE_DEFAULT_PARTITION.equals(value) || GlueTypeMapper.exceedsWidth(key.getType(), value)) {
            return null;
        }
        return value;
    }

    /** Whether two partitions hold key values that PostgreSQL casts to the same value, or may do so. */
    static boolean hasOverlappingPartitions(Table table, List<Partition> partitions) {
        Set<List<String>> seen = new HashSet<>();
        for (Partition partition : partitions) {
            List<String> canonical = canonicalPartitionValues(table, partition.getValues());
            if (canonical == null || !seen.add(canonical)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The key values as PostgreSQL holds them after the cast to the column types, or null when a value cannot be
     * reduced with confidence, which counts as an overlap so that the table is read whole.
     */
    private static List<String> canonicalPartitionValues(Table table, List<String> values) {
        List<Column> keys = table.getPartitionKeys();
        List<String> canonical = new ArrayList<>();
        for (int index = 0; index < keys.size(); index++) {
            Column key = keys.get(index);
            String value = partitionValue(key, values, index);
            String converted = value == null ? NULL_KEY
                    : canonicalKeyValue(GlueTypeMapper.canonicalGlueType(key.getType()), value);
            if (converted == null) {
                return null;
            }
            canonical.add(converted);
        }
        return canonical;
    }

    private static String canonicalKeyValue(String type, String value) {
        String trimmed = value.trim();
        try {
            if (INTEGER_TYPES.contains(type)) {
                return new BigInteger(trimmed).toString();
            }
            if (type.startsWith("decimal")) {
                BigDecimal number = new BigDecimal(trimmed);
                Matcher scale = DECIMAL_SCALE.matcher(type);
                if (scale.find()) {
                    number = number.setScale(Integer.parseInt(scale.group(1)), RoundingMode.HALF_UP);
                }
                return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
            }
            if (type.equals("float") || type.equals("real") || type.equals("double")) {
                // the narrowest float makes the most values equal, so it finds every overlap of the wider ones
                return Float.toString(Float.parseFloat(trimmed));
            }
            return switch (type) {
                case "boolean" -> booleanKey(trimmed);
                case "date" -> LocalDate.parse(trimmed).toString();
                case "timestamp" -> LocalDateTime.parse(trimmed.replace(' ', 'T')).truncatedTo(ChronoUnit.MICROS).toString();
                default -> type.startsWith("char") ? value.stripTrailing() : value;
            };
        } catch (NumberFormatException | DateTimeParseException unparseable) {
            LOG.debugv("Partition key value {0} of type {1} is not reduced, so the table is read whole", value, type);
            return null;
        }
    }

    /** PostgreSQL reads any unique prefix of true, false, yes, no, on and off, and 1 and 0. */
    private static String booleanKey(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return null;
        }
        if (lower.equals("1") || "true".startsWith(lower) || "yes".startsWith(lower) || lower.equals("on")) {
            return "true";
        }
        if (lower.equals("0") || "false".startsWith(lower) || "no".startsWith(lower) || lower.equals("of")
                || lower.equals("off")) {
            return "false";
        }
        return null;
    }

    private void prepareScratchBucket(String accountId) {
        RequestScopes.runAs(accountId, () -> {
            try {
                S3ScratchSpace.SPECTRUM.ensureBucket(s3Service, config.defaultRegion());
            } catch (IOException exception) {
                throw new SpectrumReadException(SQLSTATE_LOAD_FAILED, exception.getMessage(), exception);
            }
            if (sweptAccounts.add(accountId)) {
                sweepStaleScratchObjects();
            }
        });
    }

    private void readWithDuckDb(String accountId, RedshiftRoleAccess.RoleSession roleSession,
                                Table table, List<ReadSource> sources, long maxRows,
                                String scratchKey) {
        Map<String, Set<String>> columnsByObject = parquetColumnsByObject(accountId, roleSession, sources);
        List<String> selects = new ArrayList<>();
        for (ReadSource source : sources) {
            if (source.partition() == null || !source.objects().isEmpty()) {
                selects.add(readSelect(source, fileColumns(source, columnsByObject)));
            }
        }
        if (selects.isEmpty() && GlueTableResolver.isIcebergTable(table)) {
            GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(table);
            selects = List.of("SELECT " + projection(plan.columns()) + " FROM " + fromClause(plan, table));
        }
        String query = selects.size() == 1 ? selects.getFirst()
                : "SELECT * FROM (" + String.join(" UNION ALL ", selects) + ") AS spectrum_partitions";
        String setup = GlueTableResolver.isIcebergTable(table) ? ICEBERG_SETUP : null;
        String outputPath = "s3://" + SCRATCH_BUCKET + "/" + scratchKey;
        try {
            if (s3Service.isAuthEnforced()) {
                requireSignedSession(roleSession);
                duckClient.execute(query + " LIMIT " + (maxRows + 1), setup, outputPath,
                        roleSession.accessKeyId(), roleSession.secretAccessKey(), roleSession.sessionToken());
            } else {
                duckClient.execute(query + " LIMIT " + (maxRows + 1), setup, outputPath, accountId);
            }
        } catch (RuntimeException exception) {
            if (isAccessDenied(exception)) {
                throw new SpectrumReadException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                        "Access denied during external table transfer for \"" + table.getName() + "\"", exception);
            }
            throw exception;
        }
    }

    private List<Map<String, Object>> duckQuery(String accountId, RedshiftRoleAccess.RoleSession roleSession,
                                                String sql) {
        return duckQuery(accountId, roleSession, sql, ICEBERG_SETUP);
    }

    private List<Map<String, Object>> duckQuery(String accountId, RedshiftRoleAccess.RoleSession roleSession,
                                                String sql, String setup) {
        try {
            if (!s3Service.isAuthEnforced()) {
                return duckClient.query(sql, setup, accountId);
            }
            requireSignedSession(roleSession);
            return duckClient.query(sql, setup,
                    roleSession.accessKeyId(), roleSession.secretAccessKey(), roleSession.sessionToken());
        } catch (RuntimeException exception) {
            if (isAccessDenied(exception)) {
                throw new SpectrumReadException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                        "Access denied during external table metadata scan", exception);
            }
            throw exception;
        }
    }

    /**
     * Whether an exception represents an S3 or IAM access denial (e.g. HTTP 403 from S3/IAM,
     * DuckDB's HTTP 403 error, or an AccessDenied error response).
     */
    private static boolean isAccessDenied(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AwsException aws && (aws.getHttpStatus() == 403
                    || "AccessDenied".equalsIgnoreCase(aws.getErrorCode()))) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (DUCKDB_HTTP_403.matcher(message).find()
                    || message.contains("AccessDenied")
                    || message.contains("Access Denied"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Under S3 auth enforcement a transfer never falls back to the account-scoped development credentials. */
    private static void requireSignedSession(RedshiftRoleAccess.RoleSession roleSession) {
        if (roleSession == null || roleSession.accessKeyId() == null || roleSession.secretAccessKey() == null) {
            throw new SpectrumReadException(SQLSTATE_LOAD_FAILED,
                    "Unable to load external table: no signed role session for the S3 transfer");
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

    /**
     * The field names, lower-cased, that each Parquet object of the read carries, from one query over all of
     * them. A Parquet file written before a column was added to the table lacks it, and Spectrum reads that
     * column as NULL, where naming it in the SELECT would fail the whole query. Nested field names are
     * listed too, so a nested field that shares the name of a missing column leaves the column to be read,
     * and fail, as before. Empty when no source is Parquet or the files cannot be described, which leaves
     * every declared column to be read as declared.
     */
    private Map<String, Set<String>> parquetColumnsByObject(String accountId,
                                                            RedshiftRoleAccess.RoleSession roleSession,
                                                            List<ReadSource> sources) {
        List<String> objectUris = new ArrayList<>();
        for (ReadSource source : sources) {
            if (GlueTableResolver.readsParquet(source.table())) {
                objectUris.addAll(objectUris(source));
            }
        }
        if (objectUris.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows;
        try {
            rows = duckQuery(accountId, roleSession, GlueTableResolver.parquetSchemaSql(objectUris), null);
        } catch (SpectrumSqlException | SpectrumReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // the read that follows names the same files, so a file that is really unreadable still fails there
            LOG.warnv(exception, "Unable to describe the Parquet files of a read of {0} objects, "
                    + "reading every declared column", objectUris.size());
            return Map.of();
        }
        Map<String, Set<String>> columnsByObject = new HashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("file_name") instanceof String file && row.get("name") instanceof String name) {
                columnsByObject.computeIfAbsent(file, ignored -> new HashSet<>()).add(name.toLowerCase(Locale.ROOT));
            }
        }
        return columnsByObject;
    }

    /** The columns the objects of one source carry; null when they are unknown, so every column is read. */
    private static Set<String> fileColumns(ReadSource source, Map<String, Set<String>> columnsByObject) {
        if (!GlueTableResolver.readsParquet(source.table())) {
            return null;
        }
        Set<String> columns = new HashSet<>();
        for (String uri : objectUris(source)) {
            Set<String> described = columnsByObject.get(uri);
            if (described == null) {
                return null;
            }
            columns.addAll(described);
        }
        return columns.isEmpty() ? null : columns;
    }

    private static List<String> objectUris(ReadSource source) {
        return source.objects().stream()
                .map(object -> "s3://" + source.location().bucket() + "/" + object.getKey()).toList();
    }

    private String readSelect(ReadSource source, Set<String> fileColumns) {
        GlueTableResolver.ReadPlan plan = GlueTableResolver.readPlan(source.table(), objectUris(source));
        String projection;
        if (source.partition() == null) {
            projection = plan.columns().stream().map(column -> dataProjection(column, fileColumns))
                    .collect(Collectors.joining(", "));
        } else {
            List<Column> dataColumns = source.table().getStorageDescriptor().getColumns();
            List<String> parts = new ArrayList<>(dataColumns.stream()
                    .map(column -> dataProjection(column, fileColumns)).toList());
            List<Column> partitionKeys = source.table().getPartitionKeys();
            List<String> values = source.partition().getValues();
            for (int index = 0; index < partitionKeys.size(); index++) {
                Column key = partitionKeys.get(index);
                // a data column wider than its width is NULL, so a partition value is as well
                String value = partitionValue(key, values, index);
                String expression = value == null
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

    private static String dataProjection(Column column, Set<String> fileColumns) {
        if (fileColumns != null && !fileColumns.contains(column.getName().toLowerCase(Locale.ROOT))) {
            return GlueTypeMapper.nullProjection(column.getName());
        }
        return GlueTypeMapper.duckProjection(column.getName(), column.getType());
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
