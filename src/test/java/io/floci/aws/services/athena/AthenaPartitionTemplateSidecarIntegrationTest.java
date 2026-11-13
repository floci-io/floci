package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Database;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Reads projecting tables whose partitions follow a {@code storage.location.template} through the
 * real floci-duck sidecar, using the views Athena would build for them.
 *
 * <p>Each table reads the same one-row Parquet file, which, like any partitioned dataset, holds no
 * partition columns: {@code region} and {@code dt} exist only in the object paths. Athena derives
 * them from the path through the template, so every table here returns {@code r1, eu, 2026-03-14};
 * the default {@code column=value} layout is the control.
 *
 * <p>The views come from {@link GlueViewDdlBuilder} over these tables alone rather than from Athena
 * end to end: the sidecar application is shared with other test classes, and Athena builds views
 * for every Glue table it holds, so another class's tables would be in every query.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaPartitionTemplateSidecarIntegrationTest {

    /** One row, {@code id = 'r1', status = 'ok'}, written by DuckDB. */
    private static final byte[] PARQUET = Base64.getDecoder().decode(
            "UEFSMRUAFQwVECwVAhUAFQYVBgAABhQCAAAAcjEVABUMFRAsFQIVABUGFQYAAAYUAgAAAG9rFQIZPDUAGA1kdWNrZGJfc2No"
            + "ZW1hFQQAFQwlABgCaWQlAAAVDCUAGAZzdGF0dXMlAAAWAhkcGSwmABwVDBkVABkYAmlkFQIWAhYuFjImCDwYAnIxGAJyMRYA"
            + "KAJyMRgCcjEREQAZHBUAFQAVAgAAACYAHBUMGRUAGRgGc3RhdHVzFQIWAhYuFjImOjwYAm9rGAJvaxYAKAJvaxgCb2sREQAZ"
            + "HBUAFQAVAgAAABZcFgImCBZkACgoRHVja0RCIHZlcnNpb24gdjIuMC4wIChidWlsZCBkNTRjYmJlM2RmKRksHAAAHAAAAOgA"
            + "AABQQVIx");

    private static final List<Map<String, String>> EXPECTED_ROW =
            List.of(Map.of("id", "r1", "region", "eu", "dt", "2026-03-14"));

    @Inject
    FlociDuckClient duck;

    @Inject
    S3Service s3Service;

    private String bucket;
    private String schema;
    private final List<String> keys = new ArrayList<>();
    private final List<Table> tables = new ArrayList<>();

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    @BeforeEach
    void createBucket() {
        bucket = "athena-template-" + System.nanoTime();
        schema = "template_" + System.nanoTime();
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
    void defaultLayoutReadsPartitionValuesFromColumnEqualsValueFolders() {
        table("sales_hive", "hive", null, "hive/region=eu/dt=2026-03-14/part.parquet");

        assertEquals(EXPECTED_ROW, queryEuInMarch("sales_hive"));
    }

    @Test
    void templateWithBareValuesMapsEachFolderToItsKey() {
        table("sales_bare", "bare", "bare/${region}/${dt}/", "bare/eu/2026-03-14/part.parquet");

        assertEquals(EXPECTED_ROW, queryEuInMarch("sales_bare"));
    }

    @Test
    void templateWithLiteralPrefixesAndStaticFolders() {
        table("sales_mixed", "mixed", "mixed/region=${region}/static/${dt}/",
                "mixed/region=eu/static/2026-03-14/part.parquet");

        assertEquals(EXPECTED_ROW, queryEuInMarch("sales_mixed"));
    }

    /**
     * Athena builds a path from one value per key, so an object whose repeated placeholder holds two
     * different values is at a path no query addresses, and is never read.
     */
    @Test
    void templateRepeatingAPlaceholderReadsOnlyPathsWhereItsValuesAgree() {
        table("sales_repeat", "repeat", "repeat/${region}/${dt}/${region}/",
                "repeat/eu/2026-03-14/eu/part.parquet", "repeat/eu/2026-03-14/us/part.parquet");

        assertEquals(EXPECTED_ROW, queryEuInMarch("sales_repeat"));
    }

    /**
     * Each row carries the values of its own path, and an object at a path whose value the key's
     * type cannot hold (no date is {@code latest}) is one projection never generates, so it is not
     * read rather than failing the query.
     */
    @Test
    void eachObjectCarriesItsOwnPathsValuesAndUnparsableValuesAreNotRead() {
        table("sales_bare", "bare", "bare/${region}/${dt}/",
                "bare/eu/2026-03-14/part.parquet", "bare/us/2026-03-15/part.parquet",
                "bare/eu/latest/part.parquet");

        List<Map<String, String>> rows = query("SELECT region, CAST(dt AS VARCHAR) AS dt "
                + "FROM " + view("sales_bare") + " ORDER BY region");

        assertEquals(List.of(Map.of("region", "eu", "dt", "2026-03-14"),
                Map.of("region", "us", "dt", "2026-03-15")), rows);
        assertEquals(EXPECTED_ROW, queryEuInMarch("sales_bare"));
    }

    /**
     * The Firehose layout AWS documents: one string key projected as a date whose format spans four
     * folders, behind a single placeholder. Athena (engine v3) reports the whole
     * {@code 2026/11/10/05} as its value.
     */
    @Test
    void placeholderWhoseDateFormatSpansFoldersTakesEveryFolderAsItsValue() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("classification", "parquet");
        parameters.put("projection.enabled", "true");
        parameters.put("projection.datehour.type", "date");
        parameters.put("projection.datehour.format", "yyyy/MM/dd/HH");
        parameters.put("projection.datehour.range", "2026/01/01/00,NOW");
        parameters.put("projection.datehour.interval", "1");
        parameters.put("projection.datehour.interval.unit", "HOURS");
        parameters.put("storage.location.template", "s3://" + bucket + "/firehose/${datehour}/");
        register("sales_firehose", "firehose", parameters, List.of(column("datehour", "string")),
                "firehose/2026/11/10/05/part.parquet");

        assertEquals(List.of(Map.of("id", "r1", "datehour", "2026/11/10/05")),
                query("SELECT id, datehour FROM " + view("sales_firehose")
                        + " WHERE datehour = '2026/11/10/05'"));
    }

    /**
     * A multi-folder date with more of the template after it. Reading it with one wildcard for the
     * date matched no object, which fails the view and with it every other view in the batch.
     */
    @Test
    void dateSpanningFoldersFollowedByMoreOfTheTemplate() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("classification", "parquet");
        parameters.put("projection.enabled", "true");
        parameters.put("projection.dt.type", "date");
        parameters.put("projection.dt.format", "yyyy/MM/dd");
        parameters.put("projection.dt.range", "2026/01/01,NOW");
        parameters.put("projection.region.type", "injected");
        parameters.put("storage.location.template", "s3://" + bucket + "/nested/${dt}/region=${region}/");
        register("sales_nested", "nested", parameters,
                List.of(column("dt", "string"), column("region", "string")),
                "nested/2026/03/14/region=eu/part.parquet");

        assertEquals(List.of(Map.of("id", "r1", "region", "eu", "dt", "2026/03/14")),
                query("SELECT id, region, dt FROM " + view("sales_nested") + " WHERE region = 'eu'"));
    }

    /**
     * Ten placeholders put {@code dt} at group 10, past what DuckDB's scalar {@code regexp_extract}
     * accepts. The keys before it need no projection type for the view, so they set none.
     */
    @Test
    void templateWithTenPlaceholdersReadsTheTenth() {
        List<String> hierarchy = List.of("account", "team", "service", "env", "cluster", "zone", "rack", "host");
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("classification", "parquet");
        parameters.put("projection.enabled", "true");
        parameters.put("projection.region.type", "injected");
        parameters.put("projection.dt.type", "date");
        parameters.put("projection.dt.format", "yyyy-MM-dd");
        parameters.put("projection.dt.range", "2026-01-01,NOW");
        StringBuilder template = new StringBuilder("s3://" + bucket + "/deep/");
        List<Column> partitionKeys = new ArrayList<>();
        for (String key : hierarchy) {
            template.append("${").append(key).append("}/");
            partitionKeys.add(column(key, "string"));
        }
        parameters.put("storage.location.template", template + "${region}/${dt}/");
        partitionKeys.add(column("region", "string"));
        partitionKeys.add(column("dt", "date"));
        register("sales_deep", "deep", parameters, partitionKeys,
                "deep/a1/t1/s1/prod/c1/z1/r1/h1/eu/2026-03-14/part.parquet");

        assertEquals(EXPECTED_ROW, queryEuInMarch("sales_deep"));
    }

    // ---- helpers ----

    /**
     * Uploads the Parquet file to each of {@code objectKeys} and declares a projecting table over
     * {@code root}, laid out by {@code template} (relative to the bucket) or by the default layout.
     */
    private void table(String name, String root, String template, String... objectKeys) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("classification", "parquet");
        parameters.put("projection.enabled", "true");
        parameters.put("projection.region.type", "injected");
        parameters.put("projection.dt.type", "date");
        parameters.put("projection.dt.format", "yyyy-MM-dd");
        parameters.put("projection.dt.range", "2026-01-01,NOW");
        if (template != null) {
            parameters.put("storage.location.template", "s3://" + bucket + "/" + template);
        }
        register(name, root, parameters, List.of(column("region", "string"), column("dt", "date")), objectKeys);
    }

    /** Uploads the Parquet file to each of {@code objectKeys} and declares a table over {@code root}. */
    private void register(String name, String root, Map<String, String> parameters, List<Column> partitionKeys,
                          String... objectKeys) {
        for (String key : objectKeys) {
            s3Service.putObject(bucket, key, PARQUET, "application/octet-stream", null);
            keys.add(key);
        }
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://" + bucket + "/" + root + "/");
        descriptor.setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe");
        descriptor.setSerdeInfo(serde);
        descriptor.setColumns(List.of(column("id", "string"), column("status", "string")));
        Table table = new Table();
        table.setName(name);
        table.setStorageDescriptor(descriptor);
        table.setPartitionKeys(partitionKeys);
        table.setParameters(parameters);
        tables.add(table);
    }

    /** A typical projected-partition query, with the equality filter an injected key requires. */
    private List<Map<String, String>> queryEuInMarch(String table) {
        return query("SELECT id, region, CAST(dt AS VARCHAR) AS dt FROM " + view(table)
                + " WHERE region = 'eu' AND dt BETWEEN DATE '2026-03-01' AND DATE '2026-03-31'");
    }

    private List<Map<String, String>> query(String sql) {
        GlueService glue = Mockito.mock(GlueService.class);
        Database database = new Database();
        database.setName(schema);
        when(glue.getDatabases()).thenReturn(List.of(database));
        when(glue.getTables(schema)).thenReturn(tables);
        String views = new GlueViewDdlBuilder(glue).build(null);

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
