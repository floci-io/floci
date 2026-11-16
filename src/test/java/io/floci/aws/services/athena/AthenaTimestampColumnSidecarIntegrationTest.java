package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Reads columns declared {@code timestamp} through the real floci-duck sidecar, with the views Athena builds, and
 * checks them against what Athena (engine v3) returns for the same values: a Hive table's {@code timestamp} is
 * Trino's {@code timestamp(3)}, the UTC wall-clock time truncated to milliseconds towards the past, whatever the file
 * stores.
 *
 * <p>The Parquet file is written by DuckDB, as writers commonly store a timestamp: {@code ts} UTC-adjusted
 * ({@code isAdjustedToUTC}, MICROS), which DuckDB reads as {@code TIMESTAMP WITH TIME ZONE}, and {@code ts_ns} with
 * nanoseconds (pandas' unit), which it reads as {@code TIMESTAMP_NS}.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaTimestampColumnSidecarIntegrationTest {

    /** The rows of every table here, by id: whole seconds, a half millisecond, before the epoch, and NULL. */
    private static final String ROWS = """
            SELECT * FROM (VALUES
              (1, TIMESTAMPTZ '2026-03-14 09:30:00+00',       TIMESTAMP_NS '2026-03-14 09:30:00'),
              (2, TIMESTAMPTZ '2026-03-14 09:30:00.1235+00',  TIMESTAMP_NS '2026-03-14 09:30:00.123999999'),
              (3, TIMESTAMPTZ '1969-12-31 23:59:59.9995+00',  TIMESTAMP_NS '1969-12-31 23:59:59.9995'),
              (4, CAST(NULL AS TIMESTAMPTZ),                  CAST(NULL AS TIMESTAMP_NS))
            ) AS t (id, ts, ts_ns)""";

    /**
     * The values Athena returns for {@link #ROWS}, read here with DuckDB's {@code CAST(... AS VARCHAR)}, which drops a
     * zero fraction that {@code GetQueryResults} pads ({@code 09:30:00.000} on Athena), and each column's type.
     */
    private static final List<Map<String, String>> EXPECTED = List.of(
            row("1", "2026-03-14 09:30:00", "2026-03-14 09:30:00"),
            row("2", "2026-03-14 09:30:00.123", "2026-03-14 09:30:00.123"),
            row("3", "1969-12-31 23:59:59.999", "1969-12-31 23:59:59.999"),
            row("4", "null", "null"));

    private static final String SELECT = "SELECT id, CAST(ts AS VARCHAR) AS ts, CAST(ts_ns AS VARCHAR) AS ts_ns,"
            + " typeof(ts) AS ts_type, typeof(ts_ns) AS ts_ns_type FROM ";

    @Inject
    FlociDuckClient duck;

    @Inject
    S3Service s3Service;

    private String bucket;
    private String schema;
    private final List<String> keys = new ArrayList<>();
    private final List<Table> tables = new ArrayList<>();
    private final Map<String, List<Partition>> partitions = new HashMap<>();

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    @BeforeEach
    void createBucket() {
        bucket = "athena-timestamp-" + System.nanoTime();
        schema = "timestamps_" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
    }

    @AfterEach
    void deleteBucket() {
        for (String key : keys) {
            s3Service.deleteObject(bucket, key);
        }
        s3Service.deleteBucket(bucket);
    }

    @Test
    void parquetColumnsDeclaredTimestampReadAsAthenasTimestamp() {
        writeParquet("events/part-0.parquet");
        tables.add(parquetTable("events", List.of()));

        assertEquals(EXPECTED, query(SELECT + view("events") + " ORDER BY id"));
    }

    /** The views pin the session to UTC, so a session that starts in another zone still reads UTC. */
    @Test
    void theUtcWallClockTimeDoesNotDependOnTheSessionsZone() {
        writeParquet("events/part-0.parquet");
        tables.add(parquetTable("events", List.of()));
        String sql = "SELECT CAST(ts AS VARCHAR) AS ts FROM " + view("events") + " WHERE id = 1";

        List<Map<String, Object>> rows = duck.query(sql, "SET TimeZone = 'America/Toronto';\n"
                + builder().build(null, sql));

        assertEquals("2026-03-14 09:30:00", rows.getFirst().get("ts"));
    }

    /** A partitioned table, read through its registered partitions, types its data columns the same way. */
    @Test
    void aPartitionedTableReadsItsTimestampsTheSameWay() {
        writeParquet("events/dt=2026-03-14/part-0.parquet");
        tables.add(parquetTable("events", List.of(column("dt", "string"))));
        partitions.put("events", List.of(GluePartitions.partition(null, "2026-03-14")));

        assertEquals(EXPECTED, query(SELECT + view("events") + " ORDER BY id"));
    }

    /** An empty table, which reads no files, gives its columns the type a full one does. */
    @Test
    void anEmptyTableTypesItsTimestampsAsAFullOneDoes() {
        tables.add(parquetTable("events", List.of()));

        assertEquals(List.of(Map.of("ts_type", "TIMESTAMP", "ts_ns_type", "TIMESTAMP")), query(
                "SELECT typeof(any_value(ts)) AS ts_type, typeof(any_value(ts_ns)) AS ts_ns_type FROM "
                        + view("events")));
    }

    /** Delimited text has the same precision: LazySimpleSerDe reads {@code .1235} as {@code .123} on Athena too. */
    @Test
    void delimitedTextTimestampsReadAtMillisecondPrecision() {
        put("text/part-0.tsv", "1\t2026-03-14 09:30:00.1235\n2\t1969-12-31 23:59:59.9995\n3\t\\N\n");
        StorageDescriptor descriptor = descriptor("text");
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        serde.setParameters(Map.of("field.delim", "\t"));
        descriptor.setSerdeInfo(serde);
        descriptor.setColumns(List.of(column("id", "int"), column("ts", "timestamp")));
        tables.add(table("text", descriptor, List.of()));

        assertEquals(List.of(
                Map.of("id", "1", "ts", "2026-03-14 09:30:00.123", "ts_type", "TIMESTAMP"),
                Map.of("id", "2", "ts", "1969-12-31 23:59:59.999", "ts_type", "TIMESTAMP"),
                Map.of("id", "3", "ts", "null", "ts_type", "TIMESTAMP")),
                query("SELECT id, CAST(ts AS VARCHAR) AS ts, typeof(ts) AS ts_type FROM " + view("text")
                        + " ORDER BY id"));
    }

    /**
     * A file whose column declared {@code timestamp} holds something else reads as on Athena (engine v3), which goes
     * by the file's physical type: an INT64 without a logical type is epoch milliseconds, and a string, even an ISO
     * one, binary or a date fails the query that selects it, and only that query.
     */
    @Test
    void aColumnStoredAsAnotherTypeReadsAsOnAthena() {
        writeParquet("mismatch/part-0.parquet", "SELECT 1 AS id, CAST(1773480600000 AS BIGINT) AS epoch,"
                + " '2026-03-14 09:30:00' AS iso, DATE '2026-03-14' AS day, CAST('raw' AS BLOB) AS raw");
        Table table = parquetTable("mismatch", List.of());
        table.getStorageDescriptor().setColumns(List.of(column("id", "int"), column("epoch", "timestamp"),
                column("iso", "timestamp"), column("day", "timestamp"), column("raw", "timestamp")));
        tables.add(table);

        assertEquals(List.of(Map.of("id", "1")), query("SELECT id FROM " + view("mismatch")));
        assertEquals(List.of(Map.of("epoch", "2026-03-14 09:30:00")),
                query("SELECT CAST(epoch AS VARCHAR) AS epoch FROM " + view("mismatch")));
        RuntimeException iso = assertThrows(RuntimeException.class,
                () -> query("SELECT iso FROM " + view("mismatch")));
        assertTrue(iso.getMessage().contains("HIVE_BAD_DATA: Malformed Parquet file. Field iso's type BINARY in parquet"
                + " file is incompatible with type timestamp(3) defined in table schema"), iso.getMessage());
        RuntimeException raw = assertThrows(RuntimeException.class,
                () -> query("SELECT raw FROM " + view("mismatch")));
        assertTrue(raw.getMessage().contains("HIVE_BAD_DATA: Malformed Parquet file. Field raw's type BINARY"),
                raw.getMessage());
        RuntimeException day = assertThrows(RuntimeException.class,
                () -> query("SELECT day FROM " + view("mismatch")));
        assertTrue(day.getMessage().contains(
                "SERIALIZATION_ERROR: Could not serialize column 'day' of type 'timestamp(3)'"), day.getMessage());
    }

    // ---- helpers ----

    /** Writes {@link #ROWS} to {@code key} as Parquet, through the sidecar. */
    private void writeParquet(String key) {
        writeParquet(key, ROWS);
    }

    /** Writes the rows {@code select} gives to {@code key} as Parquet, through the sidecar. */
    private void writeParquet(String key, String select) {
        duck.query("COPY (" + select + ") TO 's3://" + bucket + "/" + key + "' (FORMAT PARQUET)", null);
        keys.add(key);
    }

    private void put(String key, String content) {
        s3Service.putObject(bucket, key, content.getBytes(StandardCharsets.UTF_8), "application/octet-stream", null);
        keys.add(key);
    }

    private Table parquetTable(String name, List<Column> partitionKeys) {
        StorageDescriptor descriptor = descriptor(name);
        descriptor.setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe");
        descriptor.setSerdeInfo(serde);
        descriptor.setColumns(List.of(column("id", "int"), column("ts", "timestamp"), column("ts_ns", "timestamp")));
        return table(name, descriptor, partitionKeys);
    }

    private StorageDescriptor descriptor(String root) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://" + bucket + "/" + root + "/");
        return descriptor;
    }

    private static Table table(String name, StorageDescriptor descriptor, List<Column> partitionKeys) {
        Table table = new Table();
        table.setName(name);
        table.setStorageDescriptor(descriptor);
        table.setPartitionKeys(partitionKeys);
        return table;
    }

    private GlueViewDdlBuilder builder() {
        GlueService glue = Mockito.mock(GlueService.class);
        Database database = new Database();
        database.setName(schema);
        when(glue.getDatabases()).thenReturn(List.of(database));
        when(glue.getTables(schema)).thenReturn(tables);
        when(glue.getPartitions(eq(schema), anyString()))
                .thenAnswer(call -> partitions.getOrDefault(call.getArgument(1), List.of()));
        return new GlueViewDdlBuilder(glue, s3Service);
    }

    private List<Map<String, String>> query(String sql) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> row : duck.query(sql, builder().build(null, sql))) {
            Map<String, String> values = new LinkedHashMap<>();
            row.forEach((column, value) -> values.put(column, String.valueOf(value)));
            rows.add(values);
        }
        return rows;
    }

    private String view(String table) {
        return "\"" + schema + "\".\"" + table + "\"";
    }

    private static Map<String, String> row(String id, String ts, String tsNs) {
        return Map.of("id", id, "ts", ts, "ts_ns", tsNs, "ts_type", "TIMESTAMP", "ts_ns_type", "TIMESTAMP");
    }

    private static Column column(String name, String type) {
        Column column = new Column();
        column.setName(name);
        column.setType(type);
        return column;
    }
}
