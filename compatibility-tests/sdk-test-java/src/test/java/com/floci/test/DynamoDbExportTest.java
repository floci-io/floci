package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB Export to S3")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbExportTest {

    private static DynamoDbClient ddb;
    private static S3Client s3;
    private static final String TABLE_NAME = "sdk-export-test-table";
    private static final String BUCKET_NAME = "sdk-export-test-bucket";
    private static String tableArn;
    private static String exportArn;

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
        s3 = TestFixtures.s3Client();

        // Create S3 bucket
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET_NAME).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {}

        // Create DynamoDB table
        CreateTableResponse tableResp = ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk")
                                .attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk")
                                .attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        tableArn = tableResp.tableDescription().tableArn();
        ddb.updateContinuousBackups(UpdateContinuousBackupsRequest.builder()
                .tableName(TABLE_NAME)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true).build())
                .build());

        // Insert 3 items
        ddb.putItem(PutItemRequest.builder().tableName(TABLE_NAME)
                .item(Map.of(
                        "pk", AttributeValue.fromS("user-1"),
                        "sk", AttributeValue.fromS("order-001"),
                        "total", AttributeValue.fromN("99")))
                .build());
        ddb.putItem(PutItemRequest.builder().tableName(TABLE_NAME)
                .item(Map.of(
                        "pk", AttributeValue.fromS("user-2"),
                        "sk", AttributeValue.fromS("order-002"),
                        "total", AttributeValue.fromN("55")))
                .build());
        ddb.putItem(PutItemRequest.builder().tableName(TABLE_NAME)
                .item(Map.of(
                        "pk", AttributeValue.fromS("user-3"),
                        "sk", AttributeValue.fromS("order-003"),
                        "total", AttributeValue.fromN("150")))
                .build());
    }

    @AfterAll
    static void cleanup() {
        try {
            ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
        } catch (Exception ignored) {}
        try {
            ListObjectsV2Response objects = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(BUCKET_NAME).build());
            for (S3Object obj : objects.contents()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(BUCKET_NAME).key(obj.key()).build());
            }
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET_NAME).build());
        } catch (Exception ignored) {}
        if (ddb != null) ddb.close();
        if (s3 != null) s3.close();
    }

    @Test
    @Order(1)
    void exportTableToPointInTime_returnsInProgress() {
        ExportTableToPointInTimeResponse resp = ddb.exportTableToPointInTime(
                ExportTableToPointInTimeRequest.builder()
                        .tableArn(tableArn)
                        .s3Bucket(BUCKET_NAME)
                        .s3Prefix("exports")
                        .exportFormat(ExportFormat.DYNAMODB_JSON)
                        .build());

        ExportDescription desc = resp.exportDescription();
        assertThat(desc.exportArn()).isNotBlank();
        assertThat(desc.exportStatus()).isEqualTo(ExportStatus.IN_PROGRESS);
        assertThat(desc.tableArn()).isEqualTo(tableArn);
        assertThat(desc.s3Bucket()).isEqualTo(BUCKET_NAME);
        assertThat(desc.exportFormat()).isEqualTo(ExportFormat.DYNAMODB_JSON);
        assertThat(desc.exportType()).isNull();

        exportArn = desc.exportArn();
    }

    @Test
    @Order(2)
    void waitUntilExportCompleted_completesSuccessfully() {
        assertThat(exportArn).isNotNull();

        try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(ddb).build()) {
            waiter.waitUntilExportCompleted(r -> r.exportArn(exportArn));
        }
    }

    @Test
    @Order(3)
    void describeExport_returnsCompletedExportWithAllFields() {
        assertThat(exportArn).isNotNull();

        DescribeExportResponse resp = ddb.describeExport(
                DescribeExportRequest.builder().exportArn(exportArn).build());

        ExportDescription desc = resp.exportDescription();
        assertThat(desc.exportStatus()).isEqualTo(ExportStatus.COMPLETED);
        assertThat(desc.tableArn()).isEqualTo(tableArn);
        assertThat(desc.s3Bucket()).isEqualTo(BUCKET_NAME);
        assertThat(desc.itemCount()).isEqualTo(3L);
        assertThat(desc.billedSizeBytes()).isGreaterThan(0L);
        assertThat(desc.exportManifest()).isNotBlank();
        assertThat(desc.startTime()).isNotNull();
        assertThat(desc.endTime()).isNotNull();
    }

    @Test
    @Order(4)
    void listExports_byTableArn_returnsExport() {
        assertThat(exportArn).isNotNull();

        ListExportsResponse resp = ddb.listExports(
                ListExportsRequest.builder().tableArn(tableArn).build());

        assertThat(resp.exportSummaries()).isNotEmpty();
        assertThat(resp.exportSummaries().stream()
                .anyMatch(s -> exportArn.equals(s.exportArn())))
                .isTrue();

        ExportSummary summary = resp.exportSummaries().stream()
                .filter(s -> exportArn.equals(s.exportArn()))
                .findFirst().orElseThrow();
        assertThat(summary.exportStatus()).isEqualTo(ExportStatus.COMPLETED);
        assertThat(summary.exportType()).isEqualTo(ExportType.FULL_EXPORT);
    }

    @Test
    @Order(5)
    void s3Objects_manifestAndDataExist() throws Exception {
        assertThat(exportArn).isNotNull();

        ExportFiles files = readExportFiles(exportArn);
        assertThat(files.manifestSummary().get("outputFormat").asText()).isEqualTo("DYNAMODB_JSON");
        String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        assertThat(files.manifestSummary().get("manifestFilesS3Key").asText())
                .isEqualTo("exports/AWSDynamoDB/" + exportId + "/manifest-files.json");

        String dataKey = files.dataFileEntry().get("dataFileS3Key").asText();
        assertThat(dataKey).endsWith(".json.gz");
        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(BUCKET_NAME).key(dataKey).build());
        assertThat(head.eTag()).isEqualTo("\"" + files.dataFileEntry().get("etag").asText() + "\"").endsWith("-1\"");

        String[] lines = files.data().split("\n");
        assertThat(lines).hasSize(3);

        for (String line : lines) {
            assertThat(line).contains("\"Item\"");
            assertThat(line).contains("\"pk\"");
        }
    }

    @Test
    @Order(6)
    void exportTableToPointInTime_invalidExportType_throwsValidationException() {
        assertThatThrownBy(() -> ddb.exportTableToPointInTime(
                ExportTableToPointInTimeRequest.builder()
                        .tableArn(tableArn)
                        .s3Bucket(BUCKET_NAME)
                        .exportType(ExportType.INCREMENTAL_EXPORT)
                        .build()))
                .isInstanceOf(DynamoDbException.class)
                .hasMessageContaining("Incremental Export Specification expected when export type is set as Incremental Export");
    }

    @Test
    @Order(7)
    void describeExport_notFound_throwsExportNotFoundException() {
        assertThatThrownBy(() -> ddb.describeExport(
                DescribeExportRequest.builder()
                        .exportArn("arn:aws:dynamodb:us-east-1:000000000000:table/T/export/doesnotexist")
                        .build()))
                .isInstanceOf(DynamoDbException.class);
    }

    @Test
    @Order(8)
    void exportTableToPointInTime_exportTime_exportsTheItemsOfThatTime() throws InterruptedException {
        Thread.sleep(50);
        Instant beforeFourthItem = Instant.now();
        Thread.sleep(50);
        ddb.putItem(PutItemRequest.builder().tableName(TABLE_NAME)
                .item(Map.of(
                        "pk", AttributeValue.fromS("user-4"),
                        "sk", AttributeValue.fromS("order-004"),
                        "total", AttributeValue.fromN("10")))
                .build());

        ExportDescription desc = ddb.exportTableToPointInTime(ExportTableToPointInTimeRequest.builder()
                .tableArn(tableArn)
                .s3Bucket(BUCKET_NAME)
                .s3Prefix("exports-time")
                .exportTime(beforeFourthItem)
                .build()).exportDescription();
        assertThat(desc.exportTime()).isEqualTo(beforeFourthItem.truncatedTo(ChronoUnit.MILLIS));

        try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(ddb).build()) {
            waiter.waitUntilExportCompleted(r -> r.exportArn(desc.exportArn()));
        }
        assertThat(ddb.describeExport(DescribeExportRequest.builder().exportArn(desc.exportArn()).build())
                .exportDescription().itemCount()).isEqualTo(3L);
    }

    @Test
    @Order(9)
    void exportTableToPointInTime_futureExportTime_throwsInvalidExportTimeException() {
        assertThatThrownBy(() -> ddb.exportTableToPointInTime(ExportTableToPointInTimeRequest.builder()
                .tableArn(tableArn)
                .s3Bucket(BUCKET_NAME)
                .exportTime(Instant.now().plusSeconds(3600))
                .build()))
                .isInstanceOf(InvalidExportTimeException.class)
                .hasMessageContaining("Export Time is invalid");
    }

    @Test
    @Order(10)
    void exportTableToPointInTime_ionFormat_writesIonLines() throws Exception {
        ExportDescription desc = ddb.exportTableToPointInTime(ExportTableToPointInTimeRequest.builder()
                .tableArn(tableArn)
                .s3Bucket(BUCKET_NAME)
                .s3Prefix("exports-ion")
                .exportFormat(ExportFormat.ION)
                .build()).exportDescription();
        assertThat(desc.exportFormat()).isEqualTo(ExportFormat.ION);

        try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(ddb).build()) {
            waiter.waitUntilExportCompleted(r -> r.exportArn(desc.exportArn()));
        }
        ExportFiles files = readExportFiles(desc.exportArn());
        assertThat(files.manifestSummary().get("outputFormat").asText()).isEqualTo("ION");
        assertThat(files.dataFileEntry().get("dataFileS3Key").asText()).endsWith(".ion.gz");
        assertThat(files.data().split("\n")).hasSize(4)
                .allSatisfy(line -> assertThat(line).startsWith("$ion_1_0 {Item:{").endsWith("}}"))
                .anySatisfy(line -> assertThat(line).contains("pk:\"user-3\"", "sk:\"order-003\"", "total:15d1"));
    }

    @Test
    @Order(11)
    void exportTableToPointInTime_incrementalUnder15Minutes_throwsInvalidExportTimeException() throws InterruptedException {
        Instant created = ddb.describeTable(r -> r.tableName(TABLE_NAME)).table().creationDateTime();
        Instant from = created.truncatedTo(ChronoUnit.SECONDS);
        Instant to = from.plusSeconds(1);
        TestFixtures.await(() -> Instant.now().isAfter(to), TABLE_NAME, "one-second export period elapsed",
                Duration.ofSeconds(5).toNanos(), 25);

        assertThatThrownBy(() -> ddb.exportTableToPointInTime(ExportTableToPointInTimeRequest.builder()
                .tableArn(tableArn)
                .s3Bucket(BUCKET_NAME)
                .exportType(ExportType.INCREMENTAL_EXPORT)
                .incrementalExportSpecification(IncrementalExportSpecification.builder()
                        .exportFromTime(from)
                        .exportToTime(to)
                        .exportViewType(ExportViewType.NEW_IMAGE)
                        .build())
                .build()))
                .isInstanceOf(InvalidExportTimeException.class)
                .hasMessageContaining("Difference between export period from time and export period to time is less than 15 minutes");
    }

    /** The manifests and the data of an export that wrote one data file. */
    private record ExportFiles(JsonNode manifestSummary, JsonNode dataFileEntry, String data) {}

    private ExportFiles readExportFiles(String arn) throws Exception {
        JsonNode manifestSummary = readJson(ddb.describeExport(DescribeExportRequest.builder().exportArn(arn).build())
                .exportDescription().exportManifest());
        JsonNode dataFileEntry = readJson(manifestSummary.get("manifestFilesS3Key").asText());
        String data = decompressGzip(getObject(dataFileEntry.get("dataFileS3Key").asText()));
        return new ExportFiles(manifestSummary, dataFileEntry, data);
    }

    private JsonNode readJson(String key) throws Exception {
        return new ObjectMapper().readTree(getObject(key));
    }

    private byte[] getObject(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET_NAME).key(key).build()).asByteArray();
    }

    private String decompressGzip(byte[] data) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
