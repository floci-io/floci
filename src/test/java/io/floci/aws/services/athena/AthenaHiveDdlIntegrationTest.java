package io.floci.aws.services.athena;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.QueryExecution;
import software.amazon.awssdk.services.athena.model.QueryExecutionState;
import software.amazon.awssdk.services.athena.model.Row;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.Partition;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hive DDL submitted through Athena changes the Glue Data Catalog the way Athena does, checked
 * through the Glue and Athena APIs with the AWS SDK.
 */
@QuarkusTest
class AthenaHiveDdlIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(741680700000L);

    private AthenaClient athena;
    private GlueClient glue;
    private S3Client s3;
    private String bucket;
    private String results;

    @BeforeEach
    void openClients() {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        athena = AwsTestClients.create(AthenaClient.builder(), account, Region.US_EAST_1);
        glue = AwsTestClients.create(GlueClient.builder(), account, Region.US_EAST_1);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), account, Region.US_EAST_1);
        bucket = "hive-ddl-" + account;
        results = "hive-ddl-results-" + account;
        s3.createBucket(r -> r.bucket(bucket));
        assertEquals(QueryExecutionState.SUCCEEDED, run("CREATE DATABASE lake").status().state());
    }

    @AfterEach
    void closeClients() {
        athena.close();
        glue.close();
        s3.close();
    }

    private QueryExecution run(String sql) {
        String id = athena.startQueryExecution(r -> r.queryString(sql)
                .queryExecutionContext(c -> c.database("lake"))
                .resultConfiguration(c -> c.outputLocation("s3://" + results + "/"))).queryExecutionId();
        return athena.getQueryExecution(r -> r.queryExecutionId(id)).queryExecution();
    }

    private void succeeds(String sql) {
        QueryExecution execution = run(sql);
        assertEquals(QueryExecutionState.SUCCEEDED, execution.status().state(), execution.status().stateChangeReason());
        assertEquals("DDL", execution.statementTypeAsString());
    }

    private String fails(String sql) {
        QueryExecution execution = run(sql);
        assertEquals(QueryExecutionState.FAILED, execution.status().state(), sql);
        return execution.status().stateChangeReason();
    }

    private void createEventsTable() {
        succeeds("""
                CREATE EXTERNAL TABLE events (id bigint, payload struct<a:int,b:array<string>>)
                PARTITIONED BY (dt string, region string)
                ROW FORMAT DELIMITED FIELDS TERMINATED BY '\\t'
                STORED AS TEXTFILE
                LOCATION 's3://%s/events/'
                TBLPROPERTIES ('classification'='csv')
                """.formatted(bucket));
    }

    private Set<String> partitionLocations() {
        return glue.getPartitions(r -> r.databaseName("lake").tableName("events")).partitions().stream()
                .map(p -> String.join("/", p.values()) + "@" + p.storageDescriptor().location())
                .collect(Collectors.toSet());
    }

    @Test
    void createExternalTableRegistersTheGlueTable() {
        createEventsTable();

        Table table = glue.getTable(r -> r.databaseName("lake").name("events")).table();
        assertEquals("EXTERNAL_TABLE", table.tableType());
        assertEquals(Map.of("classification", "csv", "EXTERNAL", "TRUE"), table.parameters());
        assertEquals(List.of("bigint", "struct<a:int,b:array<string>>"),
                table.storageDescriptor().columns().stream().map(c -> c.type()).toList());
        assertEquals(List.of("dt", "region"), table.partitionKeys().stream().map(c -> c.name()).toList());
        assertEquals("s3://" + bucket + "/events/", table.storageDescriptor().location());
        assertEquals("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
                table.storageDescriptor().serdeInfo().serializationLibrary());
        assertEquals("\t", table.storageDescriptor().serdeInfo().parameters().get("field.delim"));

        assertTrue(fails(String.format("CREATE EXTERNAL TABLE events (id bigint) LOCATION 's3://%s/x/'", bucket))
                .contains("exists"));
        succeeds(String.format("CREATE EXTERNAL TABLE IF NOT EXISTS events (id bigint) LOCATION 's3://%s/x/'", bucket));
        assertTrue(fails("CREATE TABLE plain (a int) STORED AS PARQUET LOCATION 's3://b/plain/'").contains("EXTERNAL"));
    }

    @Test
    void addPartitionDefaultsToTheHiveLayoutAndHonoursIfNotExists() {
        createEventsTable();

        succeeds("""
                ALTER TABLE events ADD
                  PARTITION (dt = '2026-11-14', region = 'eu')
                  PARTITION (region = 'us', dt = '2026-11-15') LOCATION 's3://%s/custom/'
                """.formatted(bucket));

        assertEquals(Set.of("2026-11-14/eu@s3://" + bucket + "/events/dt=2026-11-14/region=eu/",
                "2026-11-15/us@s3://" + bucket + "/custom/"), partitionLocations());
        fails("ALTER TABLE events ADD PARTITION (dt = '2026-11-14', region = 'eu')");
        succeeds("ALTER TABLE events ADD IF NOT EXISTS PARTITION (dt = '2026-11-14', region = 'eu')");
        assertTrue(fails("ALTER TABLE events ADD PARTITION (dt = '2026-11-16')").contains("partition keys"));
    }

    @Test
    void msckRepairAddsTheHiveFoldersItFindsAndShowPartitionsListsThem() {
        createEventsTable();
        succeeds("ALTER TABLE events ADD PARTITION (dt = '2026-11-14', region = 'eu')");
        for (String key : List.of("events/dt=2026-11-14/region=eu/part-0.tsv", "events/dt=2026-11-16/region=ap/part-0.tsv",
                "events/dt=2026-11-17/region=sa%2Fwest/part-0.tsv", "events/dt=2026-11-18/part-0.tsv",
                "events/other/part-0.tsv")) {
            s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString("1\t{}\n"));
        }

        succeeds("MSCK REPAIR TABLE events");

        assertEquals(Set.of("2026-11-14/eu", "2026-11-16/ap", "2026-11-17/sa/west"),
                glue.getPartitions(r -> r.databaseName("lake").tableName("events")).partitions().stream()
                        .map(p -> String.join("/", p.values())).collect(Collectors.toSet()));
        QueryExecution show = run("SHOW PARTITIONS events");
        assertEquals(QueryExecutionState.SUCCEEDED, show.status().state(), show.status().stateChangeReason());
        List<Row> rows = athena.getQueryResults(r -> r.queryExecutionId(show.queryExecutionId())).resultSet().rows();
        assertEquals("partition", rows.getFirst().data().getFirst().varCharValue());
        assertEquals(Set.of("dt=2026-11-14/region=eu", "dt=2026-11-16/region=ap", "dt=2026-11-17/region=sa%2Fwest"),
                rows.stream().skip(1).map(row -> row.data().getFirst().varCharValue()).collect(Collectors.toSet()));
    }

    @Test
    void msckKeepsEachFolderAsSpelledAndSkipsOnesHiveCouldNotHaveWritten() {
        createEventsTable();
        for (String key : List.of("events/dt=2026-11-14/region=sa%2fwest/part-0.tsv",
                "events/dt=2026-11-14/region=bad%ZZ/part-0.tsv")) {
            s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString("1\t{}\n"));
        }

        succeeds("MSCK REPAIR TABLE events");

        assertEquals(Set.of("2026-11-14/sa/west@s3://" + bucket + "/events/dt=2026-11-14/region=sa%2fwest/"),
                partitionLocations());
    }

    @Test
    void anAddedPartitionInheritsTheTablesSortColumns() {
        succeeds("""
                CREATE EXTERNAL TABLE sorted_events (id bigint) PARTITIONED BY (dt string)
                CLUSTERED BY (id) SORTED BY (id DESC) INTO 2 BUCKETS STORED AS PARQUET LOCATION 's3://%s/sorted/'
                """.formatted(bucket));

        succeeds("ALTER TABLE sorted_events ADD PARTITION (dt = '2026-11-14')");

        Partition partition = glue.getPartitions(r -> r.databaseName("lake").tableName("sorted_events")).partitions().getFirst();
        assertEquals("id", partition.storageDescriptor().sortColumns().getFirst().column());
        assertEquals(0, partition.storageDescriptor().sortColumns().getFirst().sortOrder());
    }

    @Test
    void droppingAnIcebergTableDeletesItsDataToo() {
        glue.createTable(r -> r.databaseName("lake").tableInput(t -> t.name("ice")
                .tableType("EXTERNAL_TABLE")
                .parameters(Map.of("table_type", "ICEBERG", "metadata_location", "s3://" + bucket + "/ice/metadata/v1.json"))
                .storageDescriptor(sd -> sd.location("s3://" + bucket + "/ice"))));
        for (String key : List.of("ice/metadata/v1.json", "ice/data/part-0.parquet", "iceberg-other/keep.txt")) {
            s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString("x"));
        }

        succeeds("DROP TABLE ice");

        assertEquals(List.of("iceberg-other/keep.txt"), s3.listObjectsV2(r -> r.bucket(bucket)).contents().stream()
                .map(o -> o.key()).toList());
    }

    @Test
    void dropPartitionAndDropTableRemoveMetadataOnly() {
        createEventsTable();
        succeeds("ALTER TABLE events ADD PARTITION (dt = '2026-11-14', region = 'eu') PARTITION (dt = '2026-11-15', region = 'us')");
        s3.putObject(r -> r.bucket(bucket).key("events/dt=2026-11-14/region=eu/part-0.tsv"), RequestBody.fromString("1\n"));

        succeeds("ALTER TABLE events DROP PARTITION (dt = '2026-11-15', region = 'us')");
        assertEquals(List.of("2026-11-14"), glue.getPartitions(r -> r.databaseName("lake").tableName("events"))
                .partitions().stream().map(Partition::values).map(List::getFirst).toList());
        fails("ALTER TABLE events DROP PARTITION (dt = '2026-11-15', region = 'us')");
        succeeds("ALTER TABLE events DROP IF EXISTS PARTITION (dt = '2026-11-15', region = 'us')");

        succeeds("DROP TABLE lake.events");
        assertThrows(EntityNotFoundException.class, () -> glue.getTable(r -> r.databaseName("lake").name("events")));
        assertEquals(1, s3.listObjectsV2(r -> r.bucket(bucket).prefix("events/")).keyCount(),
                "the table's data stays where it was");
        assertTrue(fails("DROP TABLE events").contains("not found"));
        succeeds("DROP TABLE IF EXISTS events");
    }
}
