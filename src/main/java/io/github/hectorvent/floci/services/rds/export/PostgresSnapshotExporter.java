package io.github.hectorvent.floci.services.rds.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.ExportTask;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a PostgreSQL snapshot the way RDS does: the snapshot is restored into a database of its
 * own, here a container started from the snapshot's image and removed afterwards, and each table
 * is then read out and written as Parquet by the floci-duck sidecar. PostgreSQL renders every value
 * the export stores as a string, so the strings are PostgreSQL's own text output.
 *
 * <p>As in {@code FirehoseParquetConverter}, a table's rows and its Parquet are staged in a bucket
 * of Floci's own, and only a finished Parquet object is written to the export's bucket, so nothing
 * partial or temporary ever appears there. A table's rows are streamed from psql to a temporary
 * file and staged in bounded chunks, so its size never has to fit in memory.
 */
@ApplicationScoped
public class PostgresSnapshotExporter implements SnapshotExporter {

    private static final Logger LOG = Logger.getLogger(PostgresSnapshotExporter.class);

    private static final String DATABASES_SQL = "SELECT to_json(datname) FROM pg_database "
            + "WHERE datallowconn AND NOT datistemplate ORDER BY datname";

    // Ordinary and partitioned tables, but not a partition on its own, since reading the parent
    // reads its partitions. Temporary and unlogged tables are skipped, as RDS skips them.
    private static final String COLUMNS_SQL = "SELECT json_build_object('schema', n.nspname, "
            + "'table', c.relname, 'kind', c.relkind, 'column', a.attname, 'type', t.typname) "
            + "FROM pg_class c "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped "
            + "JOIN pg_type t ON t.oid = a.atttypid "
            + "WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relpersistence = 'p' "
            + "AND n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg\\_%' "
            + "ORDER BY n.nspname, c.relname, a.attnum";

    private static final String NDJSON = "application/x-ndjson";
    private static final String JSON = "application/json";
    private static final String PARQUET = "application/octet-stream";

    // How long one table may take to read out of the restored database.
    static final int TABLE_READ_TIMEOUT_SECONDS = 3600;
    static final int STAGING_CHUNK_BYTES = 32 * 1024 * 1024;
    static final int DUCKDB_DEFAULT_MAXIMUM_OBJECT_SIZE = 16 * 1024 * 1024;

    private final RdsContainerManager containerManager;
    private final FlociDuckClient duckClient;
    private final S3Service s3Service;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String stagingBucket;

    @Inject
    public PostgresSnapshotExporter(RdsContainerManager containerManager, FlociDuckClient duckClient,
                                    S3Service s3Service, ObjectMapper mapper, Clock clock,
                                    EmulatorConfig config) {
        this.containerManager = containerManager;
        this.duckClient = duckClient;
        this.s3Service = s3Service;
        this.mapper = mapper;
        this.clock = clock;
        this.stagingBucket = config.services().rds().exportStagingBucket();
    }

    record Table(String database, String schema, String name, boolean partitioned, List<ExportColumn> columns) {

        String target() {
            return database + "." + schema + "." + name;
        }
    }

    @Override
    public Outcome export(ExportTask task, SnapshotExportSource source, Progress progress) {
        RdsContainerHandle handle = null;
        try {
            handle = containerManager.startForExport(task.getRuntimeArn(), task.getExportTaskIdentifier(),
                    task.getRuntimeVolumeId(), source.image(), source.masterUsername(), source.masterPassword());
            containerManager.restorePostgresSnapshot(handle.getContainerId(), source.masterUsername(),
                    source.sqlDump());
            progress.extracting(0);
            return exportTables(task, source, handle.getContainerId(), progress);
        } finally {
            removeContainer(handle, task);
        }
    }

    private Outcome exportTables(ExportTask task, SnapshotExportSource source, String containerId,
                                 Progress progress) {
        ExportOnlySelection selection = ExportOnlySelection.of(task.getExportOnly());
        ExportLayout layout = new ExportLayout(task.getS3Prefix(), task.getExportTaskIdentifier());
        List<String> warnings = new ArrayList<>();
        List<Table> tables = new ArrayList<>();
        for (String database : jsonLines(query(containerId, source, "postgres", DATABASES_SQL))) {
            if (!selection.includesDatabase(database)) {
                continue;
            }
            for (Table table : tables(containerId, source, database)) {
                if (!selection.includes(database, table.schema(), table.name())) {
                    continue;
                }
                if (table.target().contains("/")
                        || !table.columns().stream().allMatch(ExportColumn::hasExportableName)) {
                    warnings.add("The table " + table.target() + " was skipped because its database, schema or"
                            + " table name, or a column name, has characters RDS cannot export.");
                    continue;
                }
                tables.add(table);
            }
        }

        if (!tables.isEmpty()) {
            ensureStagingBucket(task.getRegion());
        }
        long bytes = 0;
        ArrayNode perTableStatus = mapper.createArrayNode();
        for (int i = 0; i < tables.size(); i++) {
            if (progress.canceled()) {
                break;
            }
            Table table = tables.get(i);
            long written = exportTable(task, source, containerId, layout, table, i + 1, progress);
            bytes += written;
            perTableStatus.add(tableStatus(table, written));
            progress.extracting((i + 1) * 100 / tables.size());
        }
        Instant finishedAt = Instant.now(clock);
        if (!progress.canceled()) {
            writeInfo(task, layout, perTableStatus, tables.size(), bytes, finishedAt);
        }
        return new Outcome(bytes, warnings, finishedAt);
    }

    private List<Table> tables(String containerId, SnapshotExportSource source, String database) {
        Map<String, Table> tables = new LinkedHashMap<>();
        for (String line : query(containerId, source, database, COLUMNS_SQL).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = readTree(line);
            String schema = row.get("schema").asText();
            String name = row.get("table").asText();
            boolean partitioned = "p".equals(row.path("kind").asText());
            tables.computeIfAbsent(schema + "\u0000" + name,
                            key -> new Table(database, schema, name, partitioned, new ArrayList<>()))
                    .columns()
                    .add(new ExportColumn(row.get("column").asText(), row.get("type").asText()));
        }
        return List.copyOf(tables.values());
    }

    /**
     * Writes one table and returns the size of its rows. A table with no rows writes no file, as
     * RDS writes none for an empty partition.
     */
    private long exportTable(ExportTask task, SnapshotExportSource source, String containerId,
                             ExportLayout layout, Table table, int tableNumber, Progress progress) {
        List<String> extract = new ArrayList<>();
        List<String> write = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (ExportColumn column : table.columns()) {
            extract.add(column.extractExpression() + " AS " + ExportColumn.quoteIdentifier(column.name()));
            write.add(column.writeExpression());
            types.add("'" + escapeLiteral(column.name()) + "': '" + column.stagedType() + "'");
        }
        String select = "SELECT row_to_json(r) FROM (SELECT " + String.join(", ", extract)
                + (table.partitioned() ? " FROM " : " FROM ONLY ") + ExportColumn.quoteIdentifier(table.schema())
                + "." + ExportColumn.quoteIdentifier(table.name()) + ") r";

        Path rows = null;
        List<String> staged = new ArrayList<>();
        try {
            rows = Files.createTempFile("floci-rds-export-", ".ndjson");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(rows))) {
                containerManager.queryPostgres(containerId, source.masterUsername(), table.database(), select,
                        out, TABLE_READ_TIMEOUT_SECONDS);
            }
            long size = Files.size(rows);
            if (size == 0) {
                return 0;
            }
            String stagingRoot = "rds-export/" + task.getRuntimeVolumeId() + "/" + tableNumber + "/";
            int longestRow = stageChunksReturningTheLongestRow(rows, stagingRoot, staged);
            String stagedParquet = stagingRoot + "data.gz.parquet";
            staged.add(stagedParquet);
            // floci-duck's /execute wraps its sql field in a CSV export, so the Parquet COPY goes
            // in setup_sql and no output path is given, as FirehoseParquetConverter does.
            String copy = "COPY (SELECT " + String.join(", ", write) + " FROM read_json('"
                    + escapeLiteral("s3://" + stagingBucket + "/" + stagingRoot + "part-*.ndjson")
                    + "', format='newline_delimited', maximum_object_size="
                    + Math.max(DUCKDB_DEFAULT_MAXIMUM_OBJECT_SIZE, longestRow)
                    + ", columns={" + String.join(", ", types) + "})) TO '"
                    + escapeLiteral("s3://" + stagingBucket + "/" + stagedParquet)
                    + "' (FORMAT PARQUET, COMPRESSION GZIP)";
            duckClient.execute("SELECT 1 AS ok", copy, null, task.getAccountId());
            byte[] parquet = s3Service.getObject(stagingBucket, stagedParquet).getData();
            if (progress.abandonedByReset()) {
                return size;
            }
            s3Service.putObject(task.getS3Bucket(), layout.dataFileKey(table.database(), table.schema(), table.name()),
                    parquet, PARQUET, Map.of());
            return size;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (String key : staged) {
                deleteQuietly(stagingBucket, key);
            }
            deleteQuietly(rows);
        }
    }

    /** Stages the rows as NDJSON objects of about {@link #STAGING_CHUNK_BYTES}, split between rows. */
    private int stageChunksReturningTheLongestRow(Path rows, String stagingRoot, List<String> keys)
            throws IOException {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        int longestRow = 0;
        try (BufferedReader reader = Files.newBufferedReader(rows, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                byte[] row = line.getBytes(StandardCharsets.UTF_8);
                longestRow = Math.max(longestRow, row.length + 1);
                chunk.write(row);
                chunk.write('\n');
                if (chunk.size() >= STAGING_CHUNK_BYTES) {
                    putChunk(stagingRoot, keys, chunk);
                }
            }
        }
        if (chunk.size() > 0) {
            putChunk(stagingRoot, keys, chunk);
        }
        return longestRow;
    }

    private void putChunk(String stagingRoot, List<String> keys, ByteArrayOutputStream chunk) {
        String key = stagingRoot + "part-" + keys.size() + ".ndjson";
        keys.add(key);
        s3Service.putObject(stagingBucket, key, chunk.toByteArray(), NDJSON, Map.of());
        chunk.reset();
    }

    /**
     * Two workers can reach an empty region together, and outside us-east-1 the second
     * {@code createBucket} answers {@code BucketAlreadyOwnedByYou}.
     */
    private void ensureStagingBucket(String region) {
        if (s3Service.bucketExists(stagingBucket)) {
            return;
        }
        try {
            s3Service.createBucket(stagingBucket, region);
        } catch (AwsException e) {
            if (!"BucketAlreadyOwnedByYou".equals(e.getErrorCode())
                    && !"BucketAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    private ObjectNode tableStatus(Table table, long bytes) {
        ObjectNode status = mapper.createObjectNode();
        status.put("target", table.target());
        status.put("status", ExportTask.STATUS_COMPLETE);
        status.put("sizeGB", (double) bytes / RdsExportTaskService.GIB);
        ArrayNode mappings = status.putObject("schemaMetadata").putArray("originalTypeMappings");
        for (ExportColumn column : table.columns()) {
            mappings.addObject()
                    .put("columnName", column.name())
                    .put("originalType", column.pgType())
                    .put("expectedExportedType", column.parquetTypeName());
        }
        return status;
    }

    /**
     * The task's own record next to its data: its description, and the status of every table it
     * wrote. Readers that follow the RDS advice to read everything under a table's prefix never
     * see these, as they sit above the tables.
     */
    private void writeInfo(ExportTask task, ExportLayout layout, ArrayNode perTableStatus, int tableCount,
                           long bytes, Instant finishedAt) {
        ObjectNode info = mapper.createObjectNode();
        info.put("exportTaskIdentifier", task.getExportTaskIdentifier());
        info.put("sourceArn", task.getSourceArn());
        info.set("exportOnly", mapper.valueToTree(task.getExportOnly()));
        info.put("snapshotTime", String.valueOf(task.getSnapshotTime()));
        info.put("taskStartTime", String.valueOf(task.getTaskStartTime()));
        info.put("taskEndTime", finishedAt.toString());
        info.put("s3Bucket", task.getS3Bucket());
        info.put("s3Prefix", task.getS3Prefix() == null ? "" : task.getS3Prefix());
        info.put("exportedFilesPath", layout.taskRoot());
        info.put("iamRoleArn", task.getIamRoleArn());
        info.put("kmsKeyId", task.getKmsKeyId());
        info.put("status", ExportTask.STATUS_COMPLETE);
        info.put("percentProgress", 100);
        info.put("totalExportedDataInGB", (double) bytes / RdsExportTaskService.GIB);

        ObjectNode tablesInfo = mapper.createObjectNode();
        tablesInfo.set("perTableStatus", perTableStatus);
        putJson(task.getS3Bucket(), layout.tablesInfoKey(Math.max(tableCount, 1)), tablesInfo);
        putJson(task.getS3Bucket(), layout.exportInfoKey(), info);
    }

    private void putJson(String bucket, String key, JsonNode body) {
        try {
            s3Service.putObject(bucket, key, mapper.writeValueAsBytes(body), JSON, Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String query(String containerId, SnapshotExportSource source, String database, String sql) {
        return containerManager.queryPostgres(containerId, source.masterUsername(), database, sql);
    }

    private List<String> jsonLines(String output) {
        List<String> values = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isBlank()) {
                values.add(readTree(line).asText());
            }
        }
        return values;
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void discard(ExportTask task) {
        if (task.getRuntimeVolumeId() != null) {
            containerManager.discardExport(task.getRuntimeVolumeId());
        }
    }

    /**
     * Stops the container, then removes it and its volume whether or not the stop succeeded: a
     * volume left behind holds a whole copy of the database.
     */
    private void removeContainer(RdsContainerHandle handle, ExportTask task) {
        String volumeId = task.getRuntimeVolumeId();
        if (handle != null) {
            try {
                containerManager.stop(handle);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not stop the export container {0}", volumeId);
            }
        }
        try {
            containerManager.discardExport(volumeId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the export container and volume {0}", volumeId);
        }
        try {
            containerManager.stopByRuntimeId(task.getRuntimeArn());
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not release the export runtime {0}", task.getRuntimeArn());
        }
    }

    private void deleteQuietly(String bucket, String key) {
        try {
            s3Service.deleteObject(bucket, key);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the staged export object s3://{0}/{1}", bucket, key);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warnv(e, "Could not remove the temporary export file {0}", file);
        }
    }

    private static String escapeLiteral(String raw) {
        return raw.replace("'", "''");
    }
}
