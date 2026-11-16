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
import java.util.Base64;
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
 * Headerless delimited text, the layout Spark writes with {@code partitionBy} and a ZSTD codec, read
 * through the real floci-duck sidecar with the views Athena builds: rows come back under the Glue
 * column names and types, and a table whose view cannot be built leaves queries on other tables
 * alone.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaDelimitedTextSidecarIntegrationTest {

    /** {@code 3<TAB>carol<LF>}, ZSTD-compressed. */
    private static final byte[] ZSTD_ROW = Base64.getDecoder().decode("KLUv/QRoQQAAMwljYXJvbAo4rOon");

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
        bucket = "athena-tsv-" + System.nanoTime();
        schema = "tsv_" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        put("events/dt=2026-11-14/part-00000.tsv", "1\talice\n2\tbob\n".getBytes(StandardCharsets.UTF_8));
        put("events/dt=2026-11-15/part-00001.tsv.zst", ZSTD_ROW);
        tables.add(tsvTable("events", "events"));
        register("events", "2026-11-14", null);
        register("events", "2026-11-15", null);
    }

    @AfterEach
    void deleteBucket() {
        for (String key : keys) {
            s3Service.deleteObject(bucket, key);
        }
        s3Service.deleteBucket(bucket);
    }

    @Test
    void headerlessTsvReturnsRowsUnderTheGlueColumnNamesAndTypes() {
        List<Map<String, String>> rows = query("SELECT id, name, dt, typeof(id) AS id_type FROM " + view("events")
                + " ORDER BY id");

        assertEquals(List.of(
                Map.of("id", "1", "name", "alice", "dt", "2026-11-14", "id_type", "BIGINT"),
                Map.of("id", "2", "name", "bob", "dt", "2026-11-14", "id_type", "BIGINT"),
                Map.of("id", "3", "name", "carol", "dt", "2026-11-15", "id_type", "BIGINT")), rows);
    }

    /**
     * A table declared as Parquet over the same text files cannot have its view built, since DuckDB
     * reads a Parquet footer when it binds the view; a query on another table must still run.
     */
    @Test
    void aTableWhoseViewCannotBeBuiltFailsOnlyQueriesThatReadIt() {
        Table broken = tsvTable("not_parquet", "events");
        broken.getStorageDescriptor().setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        broken.getStorageDescriptor().getSerdeInfo()
                .setSerializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe");
        tables.add(broken);
        register("not_parquet", "2026-11-14", null);
        String sql = "SELECT count(*) AS n FROM " + view("events");

        assertThrows(RuntimeException.class, () -> duck.query(sql, builder().build(null)),
                "with every table's view in the batch, the broken one fails the query");
        assertEquals(List.of(Map.of("n", "3")), query(sql));
    }

    /**
     * Rows read as Hive's LazySimpleSerDe reads them: a row written before a column was added gets NULL for it, a
     * field past the declared columns is dropped, {@code \\N} is NULL and an empty field is an empty string.
     */
    @Test
    void delimitedTextRowsReadAsLazySimpleSerDeReadsThem() {
        put("evolved/dt=2026-11-16/part-00000.tsv",
                "1\talice\n2\t\\N\tx\n3\t\tx\n4\tdave\tn\textra\n".getBytes(StandardCharsets.UTF_8));
        Table evolved = tsvTable("evolved", "evolved");
        evolved.getStorageDescriptor().setColumns(List.of(column("id", "bigint"), column("name", "string"),
                column("note", "string")));
        tables.add(evolved);
        register("evolved", "2026-11-16", null);

        List<Map<String, String>> rows = query("SELECT id, name, name IS NULL AS name_null, note FROM "
                + view("evolved") + " ORDER BY id");

        assertEquals(List.of(
                Map.of("id", "1", "name", "alice", "name_null", "false", "note", "null"),
                Map.of("id", "2", "name", "null", "name_null", "true", "note", "x"),
                Map.of("id", "3", "name", "", "name_null", "false", "note", "x"),
                Map.of("id", "4", "name", "dave", "name_null", "false", "note", "n")), rows);
    }

    /**
     * A table whose location holds no data, or only a folder marker, answers no rows with its declared columns as
     * their types, as Athena does, where reading the empty location fails to bind.
     */
    @Test
    void aTableWhoseLocationHoldsNoDataAnswersNoRows() {
        put("marked/", new byte[0]);
        for (String name : List.of("nothing", "marked")) {
            Table table = tsvTable(name, name);
            table.setPartitionKeys(List.of());
            tables.add(table);
        }

        for (String table : List.of("nothing", "marked")) {
            assertEquals(List.of(Map.of("n", "0", "id_type", "BIGINT", "name_type", "VARCHAR")), query(
                    "SELECT count(*) AS n, typeof(max(id)) AS id_type, typeof(max(name)) AS name_type FROM "
                            + view(table)), table);
        }
        Table nested = tsvTable("nested", "nested");
        nested.setPartitionKeys(List.of());
        nested.getStorageDescriptor().setColumns(List.of(column("tags", "array<string>"),
                column("who", "struct<name:string,age:int>")));
        tables.add(nested);
        assertEquals(List.of(Map.of("tags_type", "VARCHAR[]", "age_type", "INTEGER")),
                query("SELECT typeof(any_value(tags)) AS tags_type, typeof(any_value(who).age) AS age_type FROM "
                        + view("nested")));
    }

    /**
     * A partitioned table is read through its registered partitions, as Athena reads it: a partition at its default
     * folder and one whose location lies outside the table's are read, with the partition's value as the key, and a
     * folder under the table's location that no partition names is not.
     */
    @Test
    void aPartitionedTableIsReadThroughItsRegisteredPartitions() {
        put("loaded/dt=2026-11-14/part-0.tsv", "1\talice\n".getBytes(StandardCharsets.UTF_8));
        put("loaded/dt=2026-11-15/part-0.tsv", "2\tbob\n".getBytes(StandardCharsets.UTF_8));
        put("archive/2026/11/16/part-0.tsv", "3\tcarol\n".getBytes(StandardCharsets.UTF_8));
        tables.add(tsvTable("loaded", "loaded"));
        register("loaded", "2026-11-14", null);
        register("loaded", "2026-11-16", "s3://" + bucket + "/archive/2026/11/16/");

        assertEquals(List.of(Map.of("id", "1", "name", "alice", "dt", "2026-11-14"),
                Map.of("id", "3", "name", "carol", "dt", "2026-11-16")),
                query("SELECT id, name, dt FROM " + view("loaded") + " ORDER BY id"));
        tables.add(tsvTable("unloaded", "loaded"));
        assertEquals(List.of(Map.of("n", "0")), query("SELECT count(*) AS n FROM " + view("unloaded")));

        // A value its key's type cannot hold fails the query as Athena's does.
        Table typed = tsvTable("typed", "loaded");
        typed.setPartitionKeys(List.of(column("dt", "date")));
        tables.add(typed);
        register("typed", "soon", "s3://" + bucket + "/archive/2026/11/16/");
        RuntimeException invalid = assertThrows(RuntimeException.class,
                () -> query("SELECT count(*) AS n FROM " + view("typed")));
        assertTrue(invalid.getMessage().contains(
                "HIVE_INVALID_PARTITION_VALUE: Invalid partition value 'soon' for DATE partition key: dt=soon"),
                invalid.getMessage());
    }

    // ---- helpers ----

    /** Registers a partition of {@code table} for {@code dt}, at {@code location} or, when null, its default folder. */
    private void register(String table, String dt, String location) {
        partitions.computeIfAbsent(table, ignored -> new ArrayList<>()).add(GluePartitions.partition(location, dt));
    }

    private void put(String key, byte[] content) {
        s3Service.putObject(bucket, key, content, "application/octet-stream", null);
        keys.add(key);
    }

    private Table tsvTable(String name, String root) {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://" + bucket + "/" + root + "/");
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        serde.setParameters(Map.of("field.delim", "\t"));
        descriptor.setSerdeInfo(serde);
        descriptor.setColumns(List.of(column("id", "bigint"), column("name", "string")));
        Table table = new Table();
        table.setName(name);
        table.setStorageDescriptor(descriptor);
        table.setPartitionKeys(List.of(column("dt", "string")));
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
        String views = builder().build(null, sql);

        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> row : duck.query(sql, views)) {
            Map<String, String> values = new LinkedHashMap<>();
            row.forEach((column, value) -> values.put(column, String.valueOf(value)));
            rows.add(values);
        }
        return rows;
    }

    private String view(String table) {
        return "\"" + schema + "\".\"" + table + "\"";
    }

    private static Column column(String name, String type) {
        Column column = new Column();
        column.setName(name);
        column.setType(type);
        return column;
    }
}
