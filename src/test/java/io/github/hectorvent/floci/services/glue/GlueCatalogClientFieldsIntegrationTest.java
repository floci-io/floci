package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BatchUpdatePartitionRequestEntry;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.DatabaseInput;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GlueException;
import software.amazon.awssdk.services.glue.model.Order;
import software.amazon.awssdk.services.glue.model.Partition;
import software.amazon.awssdk.services.glue.model.PartitionInput;
import software.amazon.awssdk.services.glue.model.PartitionValueList;
import software.amazon.awssdk.services.glue.model.Segment;
import software.amazon.awssdk.services.glue.model.SerDeInfo;
import software.amazon.awssdk.services.glue.model.SkewedInfo;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.glue.model.TableInput;
import software.amazon.awssdk.services.glue.model.TableVersion;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fields the Glue Data Catalog client for Hive and Spark writes and unboxes on read, through every catalog
 * action that returns a table or partition, and the partition segments it reads a table's partitions in.
 */
@QuarkusTest
class GlueCatalogClientFieldsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private static final StorageDescriptor HIVE_DESCRIPTOR = StorageDescriptor.builder()
            .columns(Column.builder().name("id").type("int").build(),
                    Column.builder().name("name").type("string").build())
            .location("s3://warehouse/bucketed/")
            .additionalLocations("s3://warehouse/more/")
            .inputFormat("org.apache.hadoop.mapred.TextInputFormat")
            .outputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat")
            .compressed(true)
            .numberOfBuckets(4)
            .serdeInfo(SerDeInfo.builder()
                    .serializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe").build())
            .bucketColumns("id")
            .sortColumns(Order.builder().column("id").sortOrder(1).build())
            .skewedInfo(SkewedInfo.builder().skewedColumnNames("name").skewedColumnValues("a")
                    .skewedColumnValueLocationMaps(Map.of("a", "s3://warehouse/bucketed/name=a/")).build())
            .storedAsSubDirectories(true)
            .build();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void everyCatalogReadReturnsTheStorageFieldsTheHiveClientWrites() {
        String database = "hive_" + UUID.randomUUID().toString().replace("-", "");
        try (GlueClient client = client()) {
            client.createDatabase(request -> request.databaseInput(DatabaseInput.builder().name(database).build()));
            try {
                TableInput input = TableInput.builder().name("bucketed").retention(7).storageDescriptor(HIVE_DESCRIPTOR)
                        .partitionKeys(Column.builder().name("day").type("string").build()).build();
                Instant accessed = Instant.ofEpochSecond(1_700_000_000L);
                write(client, database, input, PartitionInput.builder().values("one").storageDescriptor(HIVE_DESCRIPTOR)
                        .parameters(Map.of("transient_lastDdlTime", "1700000000")).lastAccessTime(accessed).build());

                for (Table table : tables(client, database, "bucketed")) {
                    assertEquals(7, table.retention());
                    assertEquals(HIVE_DESCRIPTOR, table.storageDescriptor());
                }
                for (Partition partition : partitions(client, database, "bucketed")) {
                    assertEquals(HIVE_DESCRIPTOR, partition.storageDescriptor());
                    assertEquals(List.of("one"), partition.values());
                    assertEquals(database, partition.databaseName());
                    assertEquals("bucketed", partition.tableName());
                    assertEquals(Map.of("transient_lastDdlTime", "1700000000"), partition.parameters());
                    assertEquals(accessed, partition.lastAccessTime());
                    assertNotNull(partition.creationTime());
                }
            } finally {
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }

    @Test
    void aTableAndPartitionWrittenWithoutThemReadBackGluesDefaults() {
        String database = "plain_" + UUID.randomUUID().toString().replace("-", "");
        try (GlueClient client = client()) {
            client.createDatabase(request -> request.databaseInput(DatabaseInput.builder().name(database).build()));
            try {
                StorageDescriptor descriptor = StorageDescriptor.builder()
                        .columns(Column.builder().name("id").type("int").build())
                        .location("s3://warehouse/plain/").build();
                TableInput input = TableInput.builder().name("plain").storageDescriptor(descriptor)
                        .partitionKeys(Column.builder().name("day").type("string").build()).build();
                write(client, database, input, PartitionInput.builder().values("one").storageDescriptor(
                        StorageDescriptor.builder().location("s3://warehouse/plain/day=one/").build()).build());

                for (Table table : tables(client, database, "plain")) {
                    assertEquals(0, table.retention());
                    assertFalse(table.isRegisteredWithLakeFormation());
                    assertGluesDefaults(table.storageDescriptor());
                }
                for (Partition partition : partitions(client, database, "plain")) {
                    assertGluesDefaults(partition.storageDescriptor());
                }
            } finally {
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }

    @Test
    void theHiveClientsFiveSegmentsSplitATablesPartitionsWithoutOverlap() {
        String database = "segments_" + UUID.randomUUID().toString().replace("-", "");
        try (GlueClient client = client()) {
            client.createDatabase(request -> request.databaseInput(DatabaseInput.builder().name(database).build()));
            try {
                TableInput input = TableInput.builder().name("days")
                        .partitionKeys(Column.builder().name("day").type("string").build()).build();
                client.createTable(request -> request.databaseName(database).tableInput(input));
                List<String> days = IntStream.range(0, 12).mapToObj(day -> "day" + day).toList();
                List<PartitionInput> partitions = days.stream()
                        .map(day -> PartitionInput.builder().values(day).build()).toList();
                client.batchCreatePartition(request -> request.databaseName(database).tableName("days")
                        .partitionInputList(partitions));

                List<String> segmented = new ArrayList<>();
                for (int number = 0; number < 5; number++) {
                    segmented.addAll(segment(client, database, number, 5));
                }
                assertEquals(new TreeSet<>(days), new TreeSet<>(segmented));
                assertEquals(days.size(), segmented.size());
                assertEquals(new TreeSet<>(days), new TreeSet<>(segment(client, database, 0, 1)));
                assertEquals(List.of(), segment(client, database, 5, 5));

                assertSegmentRefused(client, database, Segment.builder().segmentNumber(0).totalSegments(11).build(),
                        "Value '11' at 'segment.totalSegments' failed to satisfy constraint: "
                                + "Member must have value less than or equal to 10");
                assertSegmentRefused(client, database, Segment.builder().segmentNumber(0).totalSegments(0).build(),
                        "Value '0' at 'segment.totalSegments' failed to satisfy constraint: "
                                + "Member must have value greater than or equal to 1");
                assertSegmentRefused(client, database, Segment.builder().segmentNumber(-1).totalSegments(5).build(),
                        "Value '-1' at 'segment.segmentNumber' failed to satisfy constraint: "
                                + "Member must have value greater than or equal to 0");
                assertSegmentRefused(client, database, Segment.builder().totalSegments(5).build(),
                        "Value null at 'segment.segmentNumber' failed to satisfy constraint: Member must not be null");
                assertSegmentRefused(client, database, Segment.builder().segmentNumber(0).build(),
                        "Value null at 'segment.totalSegments' failed to satisfy constraint: Member must not be null");

                // A member of the wrong type is malformed, refused before any constraint.
                for (String malformed : List.of("\"0 of 5\"", "{\"SegmentNumber\": \"bad\", \"TotalSegments\": 5}",
                        "{\"SegmentNumber\": 1.5, \"TotalSegments\": 5}",
                        "{\"SegmentNumber\": 0, \"TotalSegments\": true}",
                        "{\"SegmentNumber\": 4294967296, \"TotalSegments\": 5}")) {
                    post("GetPartitions", "{\"DatabaseName\": \"" + database + "\", \"TableName\": \"days\", "
                            + "\"Segment\": " + malformed + "}")
                            .statusCode(400).body("__type", equalTo("SerializationException"));
                }
            } finally {
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }

    @Test
    void aSortColumnMissingAMemberOrOutOfRangeIsRefusedBeforeAnythingIsStored() {
        String database = "sorted_" + UUID.randomUUID().toString().replace("-", "");
        Order noSortOrder = Order.builder().column("id").build();
        String missingSortOrder = ".storageDescriptor.sortColumns.1.member.sortOrder' failed to satisfy constraint: "
                + "Member must not be null";
        try (GlueClient client = client()) {
            client.createDatabase(request -> request.databaseInput(DatabaseInput.builder().name(database).build()));
            try {
                assertRefused(() -> client.createTable(request -> request.databaseName(database).tableInput(
                        sorted("plain", noSortOrder, Order.builder().sortOrder(0).build(),
                                Order.builder().column("id").sortOrder(2).build()))),
                        "3 validation errors detected: Value null at 'tableInput" + missingSortOrder + "; "
                                + "Value null at 'tableInput.storageDescriptor.sortColumns.2.member.column' failed to "
                                + "satisfy constraint: Member must not be null; Value '2' at "
                                + "'tableInput.storageDescriptor.sortColumns.3.member.sortOrder' failed to satisfy "
                                + "constraint: Member must have value less than or equal to 1");
                assertThrows(EntityNotFoundException.class,
                        () -> client.getTable(request -> request.databaseName(database).name("plain")));
                post("CreateTable", "{\"DatabaseName\": \"" + database + "\", \"TableInput\": {\"Name\": \"plain\", "
                        + "\"StorageDescriptor\": {\"SortColumns\": [null]}}}")
                        .statusCode(400).body("__type", equalTo("ValidationException"))
                        .body("message", equalTo("1 validation error detected: Value null at "
                                + "'tableInput.storageDescriptor.sortColumns.1.member' failed to satisfy constraint: "
                                + "Member must not be null"));

                client.createTable(request -> request.databaseName(database).tableInput(sorted("days")));
                assertRefused(() -> client.updateTable(request -> request.databaseName(database)
                        .tableInput(sorted("days", noSortOrder))),
                        "1 validation error detected: Value null at 'tableInput" + missingSortOrder);
                assertEquals("0", client.getTable(request -> request.databaseName(database).name("days")).table()
                        .versionId());

                PartitionInput bad = PartitionInput.builder().values("two")
                        .storageDescriptor(StorageDescriptor.builder().sortColumns(noSortOrder).build()).build();
                assertRefused(() -> client.createPartition(request -> request.databaseName(database).tableName("days")
                        .partitionInput(bad)),
                        "1 validation error detected: Value null at 'partitionInput" + missingSortOrder);
                assertRefused(() -> client.batchCreatePartition(request -> request.databaseName(database)
                        .tableName("days").partitionInputList(PartitionInput.builder().values("one").build(), bad)),
                        "1 validation error detected: Value null at 'partitionInputList.2.member" + missingSortOrder);
                assertEquals(List.of(), client.getPartitions(request -> request.databaseName(database)
                        .tableName("days")).partitions());

                client.createPartition(request -> request.databaseName(database).tableName("days")
                        .partitionInput(PartitionInput.builder().values("one").build()));
                assertRefused(() -> client.updatePartition(request -> request.databaseName(database).tableName("days")
                        .partitionValueList("one").partitionInput(bad)),
                        "1 validation error detected: Value null at 'partitionInput" + missingSortOrder);
                assertRefused(() -> client.batchUpdatePartition(request -> request.databaseName(database)
                        .tableName("days").entries(BatchUpdatePartitionRequestEntry.builder()
                                .partitionValueList("one").partitionInput(bad).build())),
                        "1 validation error detected: Value null at 'entries.1.member.partitionInput"
                                + missingSortOrder);
                assertEquals(List.of(List.of("one")), client.getPartitions(request -> request.databaseName(database)
                        .tableName("days")).partitions().stream().map(Partition::values).toList());
            } finally {
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }

    private static TableInput sorted(String name, Order... orders) {
        return TableInput.builder().name(name).partitionKeys(Column.builder().name("day").type("string").build())
                .storageDescriptor(StorageDescriptor.builder().columns(Column.builder().name("id").type("int").build())
                        .sortColumns(orders).build())
                .build();
    }

    private static void assertSegmentRefused(GlueClient client, String database, Segment segment, String violation) {
        assertRefused(() -> client.getPartitions(request -> request.databaseName(database).tableName("days")
                .segment(segment)), "1 validation error detected: " + violation);
    }

    private static void assertRefused(Executable request, String message) {
        GlueException error = assertThrows(GlueException.class, request);
        assertEquals(400, error.statusCode());
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertEquals(message, error.awsErrorDetails().errorMessage());
    }

    private static ValidatableResponse post(String target, String body) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", "AWSGlue." + target).body(body)
                .when().post("/").then();
    }

    private static List<String> segment(GlueClient client, String database, int number, int total) {
        return client.getPartitions(request -> request.databaseName(database).tableName("days")
                        .segment(Segment.builder().segmentNumber(number).totalSegments(total).build()))
                .partitions().stream().map(partition -> partition.values().get(0)).toList();
    }

    private static void assertGluesDefaults(StorageDescriptor descriptor) {
        assertFalse(descriptor.compressed());
        assertEquals(0, descriptor.numberOfBuckets());
        assertTrue(descriptor.hasSortColumns());
        assertEquals(List.of(), descriptor.sortColumns());
        assertFalse(descriptor.storedAsSubDirectories());
        assertFalse(descriptor.hasBucketColumns());
        assertNull(descriptor.skewedInfo());
    }

    /** Creates the table, then updates it with the same input, so that version 0 is archived and 1 is current. */
    private static void write(GlueClient client, String database, TableInput input, PartitionInput partition) {
        client.createTable(request -> request.databaseName(database).tableInput(input));
        client.updateTable(request -> request.databaseName(database).tableInput(input));
        client.createPartition(request -> request.databaseName(database).tableName(input.name())
                .partitionInput(partition));
    }

    private static List<Table> tables(GlueClient client, String database, String name) {
        List<Table> tables = new ArrayList<>();
        tables.add(client.getTable(request -> request.databaseName(database).name(name)).table());
        tables.addAll(client.getTables(request -> request.databaseName(database)).tableList());
        tables.addAll(client.searchTables(request -> request.searchText(database)).tableList().stream()
                .filter(table -> database.equals(table.databaseName())).toList());
        client.getTableVersions(request -> request.databaseName(database).tableName(name)).tableVersions().stream()
                .map(TableVersion::table).forEach(tables::add);
        tables.add(client.getTableVersion(request -> request.databaseName(database).tableName(name).versionId("0"))
                .tableVersion().table());
        assertEquals(6, tables.size());
        return tables;
    }

    private static List<Partition> partitions(GlueClient client, String database, String table) {
        List<Partition> partitions = new ArrayList<>();
        partitions.add(client.getPartition(request -> request.databaseName(database).tableName(table)
                .partitionValues("one")).partition());
        partitions.addAll(client.getPartitions(request -> request.databaseName(database).tableName(table))
                .partitions());
        partitions.addAll(client.batchGetPartition(request -> request.databaseName(database).tableName(table)
                .partitionsToGet(PartitionValueList.builder().values("one").build())).partitions());
        assertEquals(3, partitions.size());
        return partitions;
    }

    private static GlueClient client() {
        return GlueClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("741579900001", "test")))
                .build();
    }
}
