package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    void exportTableToPointInTime_returnsInProgressOrCompleted() {
        ExportTableToPointInTimeResponse resp = ddb.exportTableToPointInTime(
                ExportTableToPointInTimeRequest.builder()
                        .tableArn(tableArn)
                        .s3Bucket(BUCKET_NAME)
                        .s3Prefix("exports")
                        .exportFormat(ExportFormat.DYNAMODB_JSON)
                        .build());

        ExportDescription desc = resp.exportDescription();
        assertThat(desc.exportArn()).isNotBlank();
        assertThat(desc.exportStatus()).isIn(ExportStatus.IN_PROGRESS, ExportStatus.COMPLETED);
        assertThat(desc.tableArn()).isEqualTo(tableArn);
        assertThat(desc.s3Bucket()).isEqualTo(BUCKET_NAME);
        assertThat(desc.exportFormat()).isEqualTo(ExportFormat.DYNAMODB_JSON);
        assertThat(desc.exportType()).isEqualTo(ExportType.FULL_EXPORT);

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

        DescribeExportResponse descResp = ddb.describeExport(
                DescribeExportRequest.builder().exportArn(exportArn).build());
        String manifestSummaryKey = descResp.exportDescription().exportManifest();
        assertThat(manifestSummaryKey).isNotBlank();

        // Verify manifest-summary.json exists
        ResponseBytes<GetObjectResponse> manifestSummary = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET_NAME).key(manifestSummaryKey).build());
        assertThat(manifestSummary.asByteArray().length).isGreaterThan(0);

        String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        String manifestFilesKey = "exports/AWSDynamoDB/" + exportId + "/manifest-files.json";

        ResponseBytes<GetObjectResponse> manifestFiles = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET_NAME).key(manifestFilesKey).build());
        String dataKey = new String(manifestFiles.asByteArray(), StandardCharsets.UTF_8).trim();
        assertThat(dataKey).endsWith(".json.gz");

        // Download and decompress the data file
        ResponseBytes<GetObjectResponse> dataFile = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET_NAME).key(dataKey).build());

        String ndjson = decompressGzip(dataFile.asByteArray());
        String[] lines = ndjson.split("\n");
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
                .isInstanceOf(software.amazon.awssdk.services.dynamodb.model.DynamoDbException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @Order(7)
    void describeExport_notFound_throwsExportNotFoundException() {
        assertThatThrownBy(() -> ddb.describeExport(
                DescribeExportRequest.builder()
                        .exportArn("arn:aws:dynamodb:us-east-1:000000000000:table/T/export/doesnotexist")
                        .build()))
                .isInstanceOf(software.amazon.awssdk.services.dynamodb.model.DynamoDbException.class);
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
