package io.floci.aws.services.rds.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.container.RdsContainerManager;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.MappedColumn;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.SourceColumn;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.services.rds.model.Integration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The PostgreSQL source of an RDS zero-ETL integration: its running database, its catalog, and the logical
 * replication that carries its changes to the target.
 *
 * <p>Each destination replicates through a publication of the tables it copied and a {@code pgoutput} replication
 * slot of the same name, both in the source database. The publication names its tables rather than all of them,
 * since a publication of a table without a primary key makes the source refuse to update or delete its rows.
 */
@ApplicationScoped
public class RdsIntegrationSource {

    // Ordinary and partitioned tables, but not a partition on its own, since reading the parent reads
    // its partitions. Temporary and unlogged tables are skipped, as for a snapshot export. "key" is the
    // column's position in the primary key, 0 when it is not part of one.
    static final String CATALOG_SQL = "SELECT json_build_object('schema', n.nspname, 'table', c.relname, "
            + "'column', a.attname, 'type', t.typname, 'typtype', t.typtype, 'category', t.typcategory, "
            + "'builtin', t.typnamespace = 'pg_catalog'::regnamespace, "
            + "'element', e.typname, 'elementtype', e.typtype, "
            + "'elementbuiltin', COALESCE(e.typnamespace = 'pg_catalog'::regnamespace, false), "
            + "'formatted', format_type(a.atttypid, a.atttypmod), "
            // indkey is an int2vector, whose subscripts start at 0, so the position is counted rather than read
            // off the subscript. Limit to indnkeyatts so INCLUDE columns are not treated as primary-key columns.
            + "'key', COALESCE((SELECT k.n FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(att, n) "
            + "WHERE k.att = a.attnum AND k.n <= i.indnkeyatts), 0)) "
            + "FROM pg_class c "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped "
            + "JOIN pg_type t ON t.oid = a.atttypid "
            + "LEFT JOIN pg_type e ON e.oid = t.typelem AND t.typcategory = 'A' "
            + "LEFT JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary "
            + "WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relpersistence = 'p' "
            + "AND n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg\\_%' "
            + "ORDER BY n.nspname, c.relname, a.attnum";

    private static final Logger LOG = Logger.getLogger(RdsIntegrationSource.class);

    /** How long reading one batch of changes may take. */
    static final int CHANGES_READ_TIMEOUT_SECONDS = 600;

    private final RdsService rdsService;
    private final RdsContainerManager containerManager;
    private final ObjectMapper objectMapper;

    @Inject
    public RdsIntegrationSource(RdsService rdsService, RdsContainerManager containerManager,
                                ObjectMapper objectMapper) {
        this.rdsService = rdsService;
        this.containerManager = containerManager;
        this.objectMapper = objectMapper;
    }

    record Source(String containerId, String masterUsername) {}

    record SourceTable(String schema, String table, List<SourceColumn> columns, List<String> primaryKey) {

        String qualifiedName() {
            return PostgresWire.quoteIdentifier(schema) + "." + PostgresWire.quoteIdentifier(table);
        }
    }

    /** One row of a slot's changes: where the message is in the WAL, and the {@code pgoutput} message. */
    record Change(String lsn, byte[] data) {}

    /** The source's running database, started first when it was recorded without one. */
    Source source(Integration integration) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(integration.getSourceArn());
        String resource = arn.resource();
        String identifier = resource.substring(resource.indexOf(':') + 1);
        if (resource.startsWith("cluster:")) {
            DbCluster cluster = rdsService.ensureClusterBackend(identifier, arn.region());
            return requireRunning(cluster.getContainerId(), cluster.getMasterUsername(), integration);
        }
        DbInstance instance = rdsService.ensureInstanceBackend(identifier, arn.region());
        return requireRunning(instance.getContainerId(), instance.getMasterUsername(), integration);
    }

    private static Source requireRunning(String containerId, String masterUsername, Integration integration) {
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalStateException("The source " + integration.getSourceArn()
                    + " has no running database to copy from.");
        }
        return new Source(containerId, masterUsername);
    }

    /** The source's tables, in schema and table order, each with its columns and its primary key in key order. */
    List<SourceTable> catalog(Source source, String sourceDatabase) {
        String output = query(source, sourceDatabase, CATALOG_SQL);
        Map<String, SourceTable> tables = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, String>> keys = new LinkedHashMap<>();
        for (String line : output.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = parse(line);
            String schema = row.path("schema").asText();
            String name = row.path("table").asText();
            String tableKey = schema + "\u0000" + name;
            SourceTable table = tables.computeIfAbsent(tableKey,
                    ignored -> new SourceTable(schema, name, new ArrayList<>(), new ArrayList<>()));
            String column = row.path("column").asText();
            table.columns().add(new SourceColumn(column, row.path("type").asText(), row.path("typtype").asText(),
                    row.path("category").asText(), row.path("builtin").asBoolean(),
                    row.path("element").isNull() ? null : row.path("element").asText(null),
                    row.path("elementtype").asText(null), row.path("elementbuiltin").asBoolean(),
                    row.path("formatted").asText()));
            int keyPosition = row.path("key").asInt();
            if (keyPosition > 0) {
                keys.computeIfAbsent(tableKey, ignored -> new TreeMap<>()).put(keyPosition, column);
            }
        }
        keys.forEach((tableKey, positions) -> tables.get(tableKey).primaryKey().addAll(positions.values()));
        return new ArrayList<>(tables.values());
    }

    /** The table's columns as Redshift takes them, or nothing when a column has a type zero-ETL does not replicate. */
    static Optional<List<MappedColumn>> mappedColumns(SourceTable table) {
        List<MappedColumn> mapped = new ArrayList<>();
        for (SourceColumn column : table.columns()) {
            Optional<MappedColumn> one = PostgresRedshiftTypes.map(column);
            if (one.isEmpty()) {
                return Optional.empty();
            }
            mapped.add(one.get());
        }
        return Optional.of(mapped);
    }

    /** Runs a query on the source database, writing its rows to {@code rows} as psql prints them. */
    void query(Source source, String sourceDatabase, String sql, OutputStream rows, int timeoutSeconds) {
        containerManager.queryPostgres(source.containerId(), source.masterUsername(), sourceDatabase, sql, rows,
                timeoutSeconds);
    }

    private String query(Source source, String sourceDatabase, String sql) {
        return containerManager.queryPostgres(source.containerId(), source.masterUsername(), sourceDatabase, sql);
    }

    /**
     * The name of the publication and the replication slot a destination replicates through: {@code zetl_}, the
     * integration id and a digest of the source database, within the 63 characters PostgreSQL allows a name.
     */
    static String replicationName(Integration integration, String sourceDatabase) {
        String id = integration.integrationId().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        if (id.length() > 40) {
            id = id.substring(0, 40);
        }
        return "zetl_" + id + "_" + Digests.sha256Hex(sourceDatabase).substring(0, 8);
    }

    /** Whether the source runs with {@code wal_level=logical}, which {@code rds.logical_replication=1} turns on. */
    boolean logicalReplicationEnabled(Source source, String sourceDatabase) {
        return "logical".equals(query(source, sourceDatabase, "SHOW wal_level").trim());
    }

    /**
     * Sets up replication of {@code tables} afresh, before they are copied: any slot and publication left by an
     * earlier load are dropped, and the publication is created before the slot, so that the slot decodes every
     * change it holds against it. Each statement runs on its own, since PostgreSQL creates a logical slot only
     * outside a transaction that has written.
     */
    void startReplication(Source source, String sourceDatabase, String name, List<SourceTable> tables) {
        dropReplication(source, sourceDatabase, name);
        StringBuilder publication = new StringBuilder("CREATE PUBLICATION ").append(name);
        for (int i = 0; i < tables.size(); i++) {
            publication.append(i == 0 ? " FOR TABLE " : ", ").append(tables.get(i).qualifiedName());
        }
        // A partitioned table's changes are published under its own name rather than its partitions'.
        publication.append(" WITH (publish_via_partition_root = true)");
        query(source, sourceDatabase, publication.toString());
        query(source, sourceDatabase, "SELECT pg_create_logical_replication_slot('" + name + "', 'pgoutput')");
    }

    boolean slotExists(Source source, String sourceDatabase, String name) {
        return !query(source, sourceDatabase,
                "SELECT 1 FROM pg_replication_slots WHERE slot_name = '" + name + "'").isBlank();
    }

    /**
     * Up to about {@code limit} of the slot's changes, in whole transactions, without consuming them: the slot
     * gives them again until {@link #advance} moves past them. Output is staged in a temporary file so a large
     * source transaction does not sit in heap; closing the returned {@link StagedChanges} removes the file.
     */
    StagedChanges peekChanges(Source source, String sourceDatabase, String name, int limit) {
        Path staged = null;
        try {
            staged = Files.createTempFile("floci-rds-integration-changes-", ".wal");
            try (OutputStream out = Files.newOutputStream(staged)) {
                query(source, sourceDatabase,
                        "SELECT lsn, encode(data, 'hex') "
                                + "FROM pg_logical_slot_peek_binary_changes('" + name + "', NULL, " + limit
                                + ", 'proto_version', '1', 'publication_names', '" + name + "')",
                        out, CHANGES_READ_TIMEOUT_SECONDS);
            }
            return StagedChanges.fromFile(staged);
        } catch (IOException e) {
            deleteStaged(staged);
            throw new UncheckedIOException("Could not stage the changes of slot " + name, e);
        } catch (RuntimeException e) {
            deleteStaged(staged);
            throw e;
        }
    }

    /**
     * One peek of a slot's changes, streamed one line at a time from its staged file so heap stays bounded
     * regardless of transaction size. Closing releases the reader and deletes the file.
     */
    static final class StagedChanges implements AutoCloseable {
        private final Path staged;
        private final BufferedReader reader;
        private final Iterator<Change> inMemory;
        private Change buffered;
        private boolean hasBuffered;

        private StagedChanges(Path staged, BufferedReader reader, Iterator<Change> inMemory) {
            this.staged = staged;
            this.reader = reader;
            this.inMemory = inMemory;
        }

        static StagedChanges fromFile(Path staged) throws IOException {
            return new StagedChanges(staged, Files.newBufferedReader(staged, StandardCharsets.UTF_8), null);
        }

        static StagedChanges of(List<Change> changes) {
            return new StagedChanges(null, null, changes.iterator());
        }

        Path stagedFile() {
            return staged;
        }

        boolean isEmpty() {
            if (!hasBuffered) {
                buffered = readNext();
                hasBuffered = true;
            }
            return buffered == null;
        }

        Change next() {
            if (hasBuffered) {
                Change value = buffered;
                buffered = null;
                hasBuffered = false;
                return value;
            }
            return readNext();
        }

        private Change readNext() {
            if (inMemory != null) {
                return inMemory.hasNext() ? inMemory.next() : null;
            }
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int separator = line.indexOf('|');
                    return new Change(line.substring(0, separator),
                            HexFormat.of().parseHex(line, separator + 1, line.length()));
                }
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read staged replication changes", e);
            }
        }

        @Override
        public void close() {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                LOG.warnv(e, "Could not close staged replication changes {0}", staged);
            } finally {
                deleteStaged(staged);
            }
        }
    }

    static void deleteStaged(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            LOG.warnv(e, "Could not delete staged file {0}", staged);
        }
    }

    /** Marks the slot's changes up to {@code lsn} as applied, so that they are neither given again nor kept. */
    void advance(Source source, String sourceDatabase, String name, String lsn) {
        query(source, sourceDatabase, "SELECT pg_replication_slot_advance('" + name + "', '" + lsn + "'::pg_lsn)");
    }

    /** Drops the slot and the publication, when they exist, so that the source no longer keeps WAL for them. */
    void dropReplication(Source source, String sourceDatabase, String name) {
        query(source, sourceDatabase, "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots "
                + "WHERE slot_name = '" + name + "'");
        query(source, sourceDatabase, "DROP PUBLICATION IF EXISTS " + name);
    }

    private JsonNode parse(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the source's catalog: " + e.getOriginalMessage(), e);
        }
    }
}
