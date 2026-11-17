package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.iceberg.GlueIcebergCatalog;
import io.floci.aws.services.glue.iceberg.GlueIcebergTokens;
import io.floci.aws.services.glue.iceberg.IcebergRestException;
import io.floci.aws.services.glue.iceberg.IcebergSchemas;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Athena's statements on Iceberg tables, run against AWS Glue's Iceberg REST catalog the way Athena manages Iceberg
 * tables: {@code CREATE TABLE ... TBLPROPERTIES ('table_type'='ICEBERG')} creates one, {@code INSERT INTO},
 * {@code UPDATE}, {@code DELETE FROM} and {@code MERGE INTO} write one through the query engine attached to that
 * catalog, and {@code VACUUM} expires its old snapshots and removes the files nothing references any more.
 */
@ApplicationScoped
public class AthenaIcebergStatements {

    /** The name the query engine attaches Glue's Iceberg REST catalog under. */
    static final String CATALOG_ALIAS = "glue_iceberg";

    /** The table properties Athena allows on an Iceberg table, {@code table_type} aside. */
    private static final Set<String> TABLE_PROPERTIES = Set.of("format", "write_compression",
            "optimize_rewrite_data_file_threshold", "optimize_rewrite_delete_file_threshold",
            "vacuum_min_snapshots_to_keep", "vacuum_max_snapshot_age_seconds", "vacuum_max_metadata_files_to_keep",
            "write_data_path_enabled");
    private static final String MIN_SNAPSHOTS_TO_KEEP = "vacuum_min_snapshots_to_keep";
    private static final String MAX_SNAPSHOT_AGE_SECONDS = "vacuum_max_snapshot_age_seconds";
    private static final String MAX_METADATA_FILES_TO_KEEP = "vacuum_max_metadata_files_to_keep";
    private static final long DEFAULT_MIN_SNAPSHOTS_TO_KEEP = 1;
    private static final long DEFAULT_MAX_SNAPSHOT_AGE_SECONDS = 432_000;
    private static final long DEFAULT_MAX_METADATA_FILES_TO_KEEP = 100;
    /** Athena creates Iceberg v2 tables. */
    private static final String FORMAT_VERSION = "2";
    private static final String DEFAULT_CATALOG = ":";
    private static final String DEFAULT_DATA_CATALOG = "awsdatacatalog";
    /** How long the query engine's catalog token lives at most; it is revoked as soon as the statement ends. */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);
    private static final Pattern DML = Pattern.compile("^(?:INSERT\\s+INTO|MERGE\\s+INTO|DELETE\\s+FROM|UPDATE)\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern S3_URI = Pattern.compile("^s3[an]?://([^/]+)/(.*)$");
    private static final String S3_TABLES_CATALOG_NAME = "s3tablescatalog";
    private static final Pattern S3_TABLES_CATALOG = Pattern.compile("(?i)^s3tablescatalog/([a-z0-9][a-z0-9-]*)$");

    private final GlueIcebergCatalog catalog;
    private final GlueService glue;
    private final S3Service s3;
    private final GlueIcebergTokens tokens;
    private final ContainerReachableEndpoint containerEndpoint;
    private final FlociDuckClient duck;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, ReentrantReadWriteLock> tableLocks = new ConcurrentHashMap<>();

    @Inject
    public AthenaIcebergStatements(GlueIcebergCatalog catalog, GlueService glue, S3Service s3,
                                   GlueIcebergTokens tokens, ContainerReachableEndpoint containerEndpoint,
                                   FlociDuckClient duck, RegionResolver regionResolver, ObjectMapper mapper) {
        this(catalog, glue, s3, tokens, containerEndpoint, duck, regionResolver, mapper, Clock.systemUTC());
    }

    AthenaIcebergStatements(GlueIcebergCatalog catalog, GlueService glue, S3Service s3, GlueIcebergTokens tokens,
                            ContainerReachableEndpoint containerEndpoint, FlociDuckClient duck,
                            RegionResolver regionResolver, ObjectMapper mapper, Clock clock) {
        this.catalog = catalog;
        this.glue = glue;
        this.s3 = s3;
        this.tokens = tokens;
        this.containerEndpoint = containerEndpoint;
        this.duck = duck;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ─── CREATE TABLE ─────────────────────────────────────────────────────────────

    /** Creates the Iceberg table {@code create} describes in {@code database} of the default catalog, with no data. */
    void createTable(String database, AthenaHiveDdl.CreateIcebergTable create) {
        createTable(DEFAULT_CATALOG, database, create);
    }

    /**
     * Creates the Iceberg table {@code create} describes in {@code database} of {@code restCatalog}, the catalog as
     * Glue's Iceberg REST endpoint names it: the default catalog, or a table bucket of the federated S3 Tables catalog
     * ({@code s3tablescatalog:<bucket>}), where S3 Tables decides the table's location.
     */
    void createTable(String restCatalog, String database, AthenaHiveDdl.CreateIcebergTable create) {
        if (!DEFAULT_CATALOG.equals(restCatalog) && create.location() != null) {
            throw invalid("S3 Tables manage their own storage locations: a CREATE TABLE in an S3 Tables catalog names "
                    + "no LOCATION");
        }
        String table = create.ref().table();
        if (DEFAULT_CATALOG.equals(restCatalog) && create.ifNotExists() && findTable(database, table) != null) {
            return;
        }
        Map<String, String> properties = new HashMap<>();
        create.properties().forEach((key, value) -> {
            String name = key.toLowerCase(Locale.ROOT);
            if (!TABLE_PROPERTIES.contains(name)) {
                throw invalid("Unsupported table property key: " + key + ". Athena allows only a predefined list "
                        + "of table properties on an Iceberg table");
            }
            properties.put(name, value);
        });
        String format = properties.get("format");
        if (format != null && !format.equalsIgnoreCase("parquet")) {
            throw invalid("Floci writes Iceberg data files as Parquet only, not " + format);
        }
        properties.put("format-version", FORMAT_VERSION);
        ObjectNode request;
        try {
            request = IcebergSchemas.createTableRequest(table, create.columns(), create.partitioning(),
                    create.location(), properties);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        try {
            catalog.createTable(restCatalog, database, request);
        } catch (IcebergRestException e) {
            if (create.ifNotExists() && "AlreadyExistsException".equals(e.type())) {
                return;
            }
            throw invalid(e.getMessage());
        }
    }

    // ─── DROP TABLE ───────────────────────────────────────────────────────────────

    /**
     * Deletes a dropped Iceberg table's files, everything under its location, as Athena does for a table it treats
     * as managed; only from a bucket of the calling account.
     */
    void purge(Table table) {
        TableLocation location = TableLocation.of(table.getStorageDescriptor() != null
                ? table.getStorageDescriptor().getLocation() : null);
        if (location != null && location.ownedBy(s3, regionResolver.getAccountId())) {
            location.forEachObject(s3, object -> s3.deleteObject(location.bucket(), object.getKey()));
        }
    }

    // ─── S3 Tables catalogs ───────────────────────────────────────────────────────

    /**
     * The table bucket an Athena catalog name ({@code s3tablescatalog/<bucket>}) names, or null for any other catalog:
     * Athena queries a table bucket integrated with the Glue Data Catalog under that name.
     */
    static String s3TablesBucket(String catalogName) {
        if (catalogName == null) {
            return null;
        }
        Matcher matcher = S3_TABLES_CATALOG.matcher(catalogName);
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** The catalog Glue's Iceberg REST endpoint answers for a table bucket of the caller's federated S3 Tables catalog. */
    static String restCatalog(String bucket) {
        return S3_TABLES_CATALOG_NAME + ":" + bucket;
    }

    /** {@code CREATE DATABASE} in an S3 Tables catalog: a namespace of its table bucket. */
    void createNamespace(String bucket, String name, boolean ifNotExists) {
        try {
            catalog.createNamespace(restCatalog(bucket), mapper.createObjectNode().set("namespace",
                    mapper.createArrayNode().add(name)));
        } catch (IcebergRestException e) {
            if (ifNotExists && "AlreadyExistsException".equals(e.type())) {
                return;
            }
            throw invalid(e.getMessage());
        }
    }

    /** {@code DROP TABLE} in an S3 Tables catalog: S3 Tables deletes the table, data and all. */
    void dropS3Table(String bucket, String namespace, String table, boolean ifExists) {
        try {
            catalog.dropTable(restCatalog(bucket), namespace, table);
        } catch (IcebergRestException e) {
            if (ifExists && ("NoSuchTableException".equals(e.type()) || "NoSuchNamespaceException".equals(e.type()))) {
                return;
            }
            throw invalid(e.getMessage());
        }
    }

    /**
     * The query engine's attachments of the S3 Tables catalogs a query reads or writes: its context catalog, when that
     * is one, and every {@code "s3tablescatalog/<bucket>"} it names. Each is attached under its own name, through Glue's
     * Iceberg REST endpoint with the warehouse {@code <account>:s3tablescatalog/<bucket>}, so the query runs as written;
     * with a context catalog whose namespace exists, the search path resolves unqualified names in it. The token goes
     * once the query ends.
     */
    record S3TablesAttachment(String attachSql, String searchPathSql, String token) {
    }

    /** The attachments {@code query} needs, or null when it names no S3 Tables catalog. */
    S3TablesAttachment s3TablesAttachment(String query, String contextCatalog, String contextDatabase) {
        Set<String> buckets = new LinkedHashSet<>();
        String contextBucket = s3TablesBucket(contextCatalog);
        if (contextBucket != null) {
            buckets.add(contextBucket);
        }
        // A catalog only a comment or a string literal holds is no reference.
        for (List<String> name : GlueViewDdlBuilder.references(query)) {
            String bucket = name.size() > 1 ? s3TablesBucket(name.getFirst()) : null;
            if (bucket != null) {
                buckets.add(bucket);
            }
        }
        if (buckets.isEmpty()) {
            return null;
        }
        String accountId = regionResolver.getAccountId();
        String token = tokens.mint(accountId, regionResolver.getRegion(), TOKEN_LIFETIME);
        StringBuilder attachSql = new StringBuilder("INSTALL iceberg; LOAD iceberg;\n");
        for (String bucket : buckets) {
            attachSql.append(attach(accountId + ":" + S3_TABLES_CATALOG_NAME + "/" + bucket,
                    GlueViewDdlBuilder.quote(S3_TABLES_CATALOG_NAME + "/" + bucket), token));
        }
        String searchPath = contextBucket == null || !hasNamespace(contextBucket, contextDatabase) ? ""
                : "SET search_path = " + literal(GlueViewDdlBuilder.quote(S3_TABLES_CATALOG_NAME + "/" + contextBucket)
                + "." + GlueViewDdlBuilder.quote(contextDatabase) + ",memory.main") + ";\n";
        return new S3TablesAttachment(attachSql.toString(), searchPath, token);
    }

    /**
     * Whether the table bucket has the namespace: the engine refuses a search path naming one it does not, and a query
     * whose context database is the {@code default} Athena gives one naming none still runs on the names it qualifies.
     */
    private boolean hasNamespace(String bucket, String namespace) {
        try {
            catalog.loadNamespace(restCatalog(bucket), namespace);
            return true;
        } catch (IcebergRestException e) {
            if ("NoSuchNamespaceException".equals(e.type())) {
                return false;
            }
            throw e;
        }
    }

    /**
     * The query engine's attachment of Glue's Iceberg REST endpoint, as {@code alias}, for the catalog
     * {@code warehouse} names, with a catalog token, its S3 reads and writes going through the engine's own credentials.
     */
    private String attach(String warehouse, String alias, String token) {
        return "ATTACH " + literal(warehouse) + " AS " + alias + " (TYPE iceberg, ENDPOINT "
                + literal(containerEndpoint.baseUrl() + "/iceberg") + ", AUTHORIZATION_TYPE 'oauth2', TOKEN "
                + literal(token) + ", ACCESS_DELEGATION_MODE 'none');\n";
    }

    /** Gives up the catalog token of an S3 Tables attachment once its query has ended. */
    void finished(S3TablesAttachment attachment) {
        tokens.revoke(attachment.token());
    }

    // ─── INSERT / UPDATE / DELETE / MERGE ─────────────────────────────────────────

    /**
     * A data-changing statement whose target is an Iceberg table, rewritten to write that table through the query
     * engine's attachment of Glue's Iceberg REST catalog: the setup SQL that attaches it with a catalog token for the
     * calling account and Region, and the table's write lock it holds, both given up once the statement ends.
     */
    record IcebergWrite(String query, String setupSql, String token, Lock lock) {
    }

    /**
     * {@code query} rewritten to write its Iceberg target through the attached catalog, or null when it is not an
     * {@code INSERT INTO}, {@code UPDATE}, {@code DELETE FROM} or {@code MERGE INTO} of an Iceberg table of the
     * default catalog; a target named without a database is in {@code contextDatabase}.
     */
    IcebergWrite write(String query, String contextDatabase) {
        return write(query, AthenaService.DEFAULT_CATALOG, contextDatabase);
    }

    /**
     * As {@link #write(String, String)}, in the context catalog {@code contextCatalog}: in an S3 Tables catalog, only a
     * target naming the default catalog ({@code awsdatacatalog.db.t}) is one, a name giving none being the context's.
     */
    IcebergWrite write(String query, String contextCatalog, String contextDatabase) {
        Target target = target(query);
        if (target == null || s3TablesBucket(contextCatalog) != null && !target.catalogNamed()) {
            return null;
        }
        String database = target.database() != null ? target.database() : contextDatabase;
        Table table = findTable(database, target.table());
        if (!GlueTableResolver.isIcebergTable(table)) {
            return null;
        }
        String accountId = regionResolver.getAccountId();
        // Held, shared with other writes, until the statement ends, so VACUUM leaves the files it stages alone.
        Lock lock = tableLock(database, target.table()).readLock();
        lock.lock();
        String token = tokens.mint(accountId, regionResolver.getRegion(), TOKEN_LIFETIME);
        String setup = "INSTALL iceberg; LOAD iceberg;\n" + attach(accountId, CATALOG_ALIAS, token);
        String rewritten = query.substring(0, target.start()) + CATALOG_ALIAS + "."
                + GlueViewDdlBuilder.quote(database) + "." + GlueViewDdlBuilder.quote(target.table())
                + query.substring(target.end());
        return new IcebergWrite(rewritten, setup, token, lock);
    }

    /** Gives up the catalog token and the table lock of a write once its statement has ended, on its thread. */
    void finished(IcebergWrite write) {
        try {
            tokens.revoke(write.token());
        } finally {
            write.lock().unlock();
        }
    }

    /**
     * The lock between Floci's own writes to a table, which share it, and {@code VACUUM}'s file sweep, which takes
     * it alone: a file a running write staged is not yet referenced by any snapshot, and a short
     * {@code vacuum_max_snapshot_age_seconds} would otherwise let the sweep delete it.
     */
    private ReentrantReadWriteLock tableLock(String database, String table) {
        return tableLocks.computeIfAbsent(regionResolver.getAccountId() + "/" + regionResolver.getRegion() + "/"
                + database + "/" + table, key -> new ReentrantReadWriteLock());
    }

    /** The table a statement writes, whether its name gives its catalog, and where its name sits in the statement. */
    record Target(String database, String table, boolean catalogNamed, int start, int end) {
    }

    /**
     * The table an {@code INSERT INTO}, {@code UPDATE}, {@code DELETE FROM} or {@code MERGE INTO} writes:
     * {@code t}, {@code db.t} or {@code awsdatacatalog.db.t}, each part bare or double-quoted. Null for any other
     * statement, or one writing another catalog's table.
     */
    static Target target(String query) {
        int start = statementStart(query);
        Matcher dml = DML.matcher(query).region(start, query.length());
        if (!dml.lookingAt()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int i = dml.end();
        int nameStart = i;
        int nameEnd;
        while (true) {
            int partEnd = identifierEnd(query, i);
            if (partEnd == i) {
                return null;
            }
            String part = query.charAt(i) == '"'
                    ? query.substring(i + 1, partEnd - 1).replace("\"\"", "\"")
                    : query.substring(i, partEnd);
            parts.add(part.toLowerCase(Locale.ROOT));
            nameEnd = partEnd;
            int next = skipSpaces(query, partEnd);
            if (next < query.length() && query.charAt(next) == '.') {
                i = skipSpaces(query, next + 1);
            } else {
                break;
            }
        }
        return switch (parts.size()) {
            case 1 -> new Target(null, parts.get(0), false, nameStart, nameEnd);
            case 2 -> new Target(parts.get(0), parts.get(1), false, nameStart, nameEnd);
            case 3 -> DEFAULT_DATA_CATALOG.equals(parts.get(0))
                    ? new Target(parts.get(1), parts.get(2), true, nameStart, nameEnd) : null;
            default -> null;
        };
    }

    /** Where the statement starts, past leading whitespace and comments. */
    private static int statementStart(String query) {
        int i = 0;
        while (true) {
            i = skipSpaces(query, i);
            if (query.startsWith("--", i)) {
                int lineEnd = query.indexOf('\n', i);
                i = lineEnd < 0 ? query.length() : lineEnd + 1;
            } else if (query.startsWith("/*", i)) {
                int commentEnd = query.indexOf("*/", i + 2);
                i = commentEnd < 0 ? query.length() : commentEnd + 2;
            } else {
                return i;
            }
        }
    }

    private static int skipSpaces(String query, int i) {
        while (i < query.length() && Character.isWhitespace(query.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The index just past the identifier at {@code i}, or {@code i} when none starts there. */
    private static int identifierEnd(String query, int i) {
        if (i >= query.length()) {
            return i;
        }
        if (query.charAt(i) == '"') {
            int j = i + 1;
            while (j < query.length()) {
                if (query.charAt(j) == '"') {
                    if (j + 1 < query.length() && query.charAt(j + 1) == '"') {
                        j += 2;
                        continue;
                    }
                    return j + 1;
                }
                j++;
            }
            return i;
        }
        int j = i;
        while (j < query.length() && (Character.isLetterOrDigit(query.charAt(j)) || query.charAt(j) == '_')) {
            j++;
        }
        return j;
    }

    // ─── VACUUM ───────────────────────────────────────────────────────────────────

    /**
     * Runs {@code VACUUM} on the Iceberg table {@code ref} names, a table without a database being in
     * {@code contextDatabase}: expires the snapshots older than {@code vacuum_max_snapshot_age_seconds} beyond the
     * newest {@code vacuum_min_snapshots_to_keep} of each branch, then removes the table's metadata files beyond the
     * newest {@code vacuum_max_metadata_files_to_keep}, and every file under its location that no snapshot left
     * references and that is older than that age, orphans included. Files are deleted only from a bucket of the
     * calling account; elsewhere they stay, as Athena's VACUUM succeeds without deleting files it may not delete.
     * While one of Floci's own writes to the table runs, the files stay for the next {@code VACUUM}.
     */
    void vacuum(AthenaHiveDdl.TableRef ref, String contextDatabase) {
        String database = ref.database() != null ? ref.database() : contextDatabase;
        Table table = findTable(database, ref.table());
        if (table == null) {
            throw new AwsException("InvalidRequestException", "Table " + database + "." + ref.table()
                    + " does not exist", 400);
        }
        if (!GlueTableResolver.isIcebergTable(table)) {
            throw invalid("VACUUM is supported only for Apache Iceberg tables");
        }
        ObjectNode loaded = loadTable(database, ref.table());
        JsonNode metadata = loaded.path("metadata");
        JsonNode properties = metadata.path("properties");
        long now = clock.millis();
        long cutoff = now - Duration.ofSeconds(longProperty(properties, MAX_SNAPSHOT_AGE_SECONDS,
                DEFAULT_MAX_SNAPSHOT_AGE_SECONDS)).toMillis();
        long minSnapshots = longProperty(properties, MIN_SNAPSHOTS_TO_KEEP, DEFAULT_MIN_SNAPSHOTS_TO_KEEP);
        long metadataFilesToKeep = longProperty(properties, MAX_METADATA_FILES_TO_KEEP,
                DEFAULT_MAX_METADATA_FILES_TO_KEEP);

        Set<Long> retained = retainedSnapshots(metadata, cutoff, minSnapshots);
        List<Long> expired = new ArrayList<>();
        for (JsonNode snapshot : metadata.path("snapshots")) {
            long id = snapshot.path("snapshot-id").asLong();
            if (!retained.contains(id)) {
                expired.add(id);
            }
        }
        if (!expired.isEmpty()) {
            loaded = commitExpiry(database, ref.table(), metadata, expired);
            metadata = loaded.path("metadata");
        }
        Lock sweep = tableLock(database, ref.table()).writeLock();
        if (!sweep.tryLock()) {
            // A write to the table is running; its staged files stay, and the next VACUUM sweeps.
            return;
        }
        try {
            removeUnreferencedFiles(loaded.path("metadata-location").asText(), metadata, cutoff, metadataFilesToKeep);
        } finally {
            sweep.unlock();
        }
    }

    /**
     * The snapshots {@code VACUUM} keeps, as Iceberg's snapshot expiration keeps them: each tag's snapshot; on each
     * branch, the ancestors of its head while they are among its newest {@code minSnapshots} or newer than
     * {@code cutoff}; and any other snapshot newer than {@code cutoff}.
     */
    static Set<Long> retainedSnapshots(JsonNode metadata, long cutoff, long minSnapshots) {
        Map<Long, JsonNode> snapshots = new HashMap<>();
        for (JsonNode snapshot : metadata.path("snapshots")) {
            snapshots.put(snapshot.path("snapshot-id").asLong(), snapshot);
        }
        Map<String, JsonNode> refs = new HashMap<>();
        metadata.path("refs").properties().forEach(entry -> refs.put(entry.getKey(), entry.getValue()));
        if (refs.isEmpty() && metadata.hasNonNull("current-snapshot-id")
                && metadata.path("current-snapshot-id").asLong() != -1) {
            refs.put("main", JsonNodeFactory.instance.objectNode()
                    .put("snapshot-id", metadata.path("current-snapshot-id").asLong()).put("type", "branch"));
        }
        Set<Long> retained = new HashSet<>();
        for (JsonNode ref : refs.values()) {
            long head = ref.path("snapshot-id").asLong();
            if ("tag".equals(ref.path("type").asText())) {
                retained.add(head);
                continue;
            }
            JsonNode snapshot = snapshots.get(head);
            for (long kept = 0; snapshot != null; kept++) {
                if (kept >= minSnapshots && snapshot.path("timestamp-ms").asLong() < cutoff) {
                    break;
                }
                retained.add(snapshot.path("snapshot-id").asLong());
                snapshot = snapshot.hasNonNull("parent-snapshot-id")
                        ? snapshots.get(snapshot.path("parent-snapshot-id").asLong()) : null;
            }
        }
        snapshots.forEach((id, snapshot) -> {
            if (snapshot.path("timestamp-ms").asLong() >= cutoff) {
                retained.add(id);
            }
        });
        return retained;
    }

    private ObjectNode commitExpiry(String database, String table, JsonNode metadata, List<Long> expired) {
        ObjectNode commit = mapper.createObjectNode();
        ArrayNode requirements = commit.putArray("requirements");
        requirements.addObject().put("type", "assert-table-uuid").put("uuid", metadata.path("table-uuid").asText());
        ObjectNode main = requirements.addObject().put("type", "assert-ref-snapshot-id").put("ref", "main");
        JsonNode mainRef = metadata.path("refs").path("main");
        if (mainRef.hasNonNull("snapshot-id")) {
            main.put("snapshot-id", mainRef.path("snapshot-id").asLong());
        } else {
            main.putNull("snapshot-id");
        }
        ArrayNode ids = commit.putArray("updates").addObject().put("action", "remove-snapshots")
                .putArray("snapshot-ids");
        expired.forEach(ids::add);
        try {
            return catalog.commitTable(DEFAULT_CATALOG, database, table, commit);
        } catch (IcebergRestException e) {
            throw invalid(e.getMessage());
        }
    }

    /**
     * Deletes the files under the table's location nothing references any more: metadata files beyond the newest
     * {@code metadataFilesToKeep} before the current one, and any other file neither the metadata (its manifest
     * lists and statistics files) nor a retained snapshot reaches, once it is older than {@code cutoff}, which spares
     * the files of another engine's write still in flight, as on Athena; Floci's own writes hold the table's lock.
     */
    private void removeUnreferencedFiles(String metadataLocation, JsonNode metadata, long cutoff,
                                         long metadataFilesToKeep) {
        TableLocation location = TableLocation.of(metadata.path("location").asText());
        if (location == null || location.prefix().isEmpty() || !location.ownedBy(s3, regionResolver.getAccountId())) {
            // Athena's VACUUM expects a table in a folder, not at a bucket's root, and deletes as the caller.
            return;
        }
        Set<String> referenced = new HashSet<>();
        addS3Uri(referenced, metadataLocation);
        List<String> previousMetadata = new ArrayList<>();
        metadata.path("metadata-log").forEach(entry -> previousMetadata.add(entry.path("metadata-file").asText()));
        Set<String> expiredMetadata = new HashSet<>();
        for (int i = 0; i < previousMetadata.size(); i++) {
            addS3Uri(i >= previousMetadata.size() - metadataFilesToKeep ? referenced : expiredMetadata,
                    previousMetadata.get(i));
        }
        List<Long> snapshotIds = new ArrayList<>();
        for (JsonNode snapshot : metadata.path("snapshots")) {
            snapshotIds.add(snapshot.path("snapshot-id").asLong());
            addS3Uri(referenced, snapshot.path("manifest-list").asText(null));
        }
        // Table and partition statistics files (Puffin and the like) the metadata still lists.
        for (String statistics : List.of("statistics", "partition-statistics")) {
            metadata.path(statistics).forEach(file -> addS3Uri(referenced, file.path("statistics-path").asText(null)));
        }
        referenced.addAll(filesOf(metadataLocation, snapshotIds));
        Instant before = Instant.ofEpochMilli(cutoff);
        location.forEachObject(s3, object -> {
            String uri = "s3://" + location.bucket() + "/" + object.getKey();
            boolean expiredVersion = expiredMetadata.contains(uri);
            boolean old = object.getLastModified() != null && object.getLastModified().isBefore(before);
            if (!referenced.contains(uri) && (expiredVersion || old)) {
                s3.deleteObject(location.bucket(), object.getKey());
            }
        });
    }

    /** The manifests and data and delete files each snapshot reaches, read by the query engine. */
    private Set<String> filesOf(String metadataLocation, List<Long> snapshotIds) {
        Set<String> files = new HashSet<>();
        if (snapshotIds.isEmpty()) {
            return files;
        }
        StringBuilder sql = new StringBuilder();
        for (Long id : snapshotIds) {
            if (!sql.isEmpty()) {
                sql.append("\nUNION\n");
            }
            sql.append("SELECT manifest_path, file_path FROM iceberg_metadata(").append(literal(metadataLocation))
                    .append(", snapshot_from_id => ").append(id).append(")");
        }
        for (Map<String, Object> row : duck.query(sql.toString(), "INSTALL iceberg; LOAD iceberg;\n",
                regionResolver.getAccountId())) {
            addS3Uri(files, row.get("manifest_path"));
            addS3Uri(files, row.get("file_path"));
        }
        return files;
    }

    private static void addS3Uri(Set<String> files, Object path) {
        if (path == null) {
            return;
        }
        Matcher uri = S3_URI.matcher(path.toString());
        // s3a:// and s3n:// name the same object as s3://.
        files.add(uri.matches() ? "s3://" + uri.group(1) + "/" + uri.group(2) : path.toString());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private ObjectNode loadTable(String database, String table) {
        try {
            return catalog.loadTable(DEFAULT_CATALOG, database, table, null);
        } catch (IcebergRestException e) {
            throw invalid(e.getMessage());
        }
    }

    private Table findTable(String database, String table) {
        try {
            return glue.getTable(database, table);
        } catch (AwsException e) {
            if ("EntityNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private static long longProperty(JsonNode properties, String name, long fallback) {
        JsonNode value = properties.get(name);
        if (value == null || value.asText().isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.asText().trim());
            if (parsed <= 0) {
                throw invalid("Table property " + name + " must be a positive number: " + value.asText());
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw invalid("Table property " + name + " must be a positive number: " + value.asText());
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
