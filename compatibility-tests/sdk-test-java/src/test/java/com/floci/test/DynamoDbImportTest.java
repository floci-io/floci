package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB Import from S3")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbImportTest {

    private static DynamoDbClient ddb;
    private static S3Client s3;
    private static final String TABLE_NAME = "sdk-import-test-table";
    private static final String BUCKET_NAME = "sdk-import-test-bucket";
    private static String importArn;

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
        s3 = TestFixtures.s3Client();

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET_NAME).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {}

        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_NAME).key("imp/data.json").build(),
                RequestBody.fromString("{\"Item\":{\"pk\":{\"S\":\"imported1\"}}}\n{\"Item\":{\"pk\":{\"S\":\"imported2\"}}}\n"));
    }

    @AfterAll
    static void cleanup() {
        try {
            ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
        } catch (Exception ignored) {}
        try {
            var objects = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET_NAME).build());
            for (var obj : objects.contents()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(BUCKET_NAME).key(obj.key()).build());
            }
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET_NAME).build());
        } catch (Exception ignored) {}
        if (ddb != null) {
            ddb.close();
        }
        if (s3 != null) {
            s3.close();
        }
    }

    private static ImportTableDescription describeImport() {
        return ddb.describeImport(DescribeImportRequest.builder().importArn(importArn).build())
                .importTableDescription();
    }

    private static ImportTableRequest importRequest() {
        return ImportTableRequest.builder()
                .s3BucketSource(S3BucketSource.builder().s3Bucket(BUCKET_NAME).s3KeyPrefix("imp/").build())
                .inputFormat(InputFormat.DYNAMODB_JSON)
                .inputCompressionType(InputCompressionType.NONE)
                .tableCreationParameters(TableCreationParameters.builder()
                        .tableName(TABLE_NAME)
                        .attributeDefinitions(AttributeDefinition.builder()
                                .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                        .keySchema(KeySchemaElement.builder()
                                .attributeName("pk").keyType(KeyType.HASH).build())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .build())
                .build();
    }

    @Test
    @Order(1)
    void importTable_returnsInProgress() {
        var resp = ddb.importTable(importRequest());

        var desc = resp.importTableDescription();
        assertThat(desc.importArn()).contains("/import/");
        assertThat(desc.importStatus()).isEqualTo(ImportStatus.IN_PROGRESS);
        assertThat(desc.tableArn()).endsWith(":table/" + TABLE_NAME);
        assertThat(desc.s3BucketSource().s3Bucket()).isEqualTo(BUCKET_NAME);
        assertThat(desc.s3BucketSource().s3KeyPrefix()).isEqualTo("imp/");
        assertThat(desc.inputFormat()).isEqualTo(InputFormat.DYNAMODB_JSON);
        assertThat(desc.inputCompressionType()).isEqualTo(InputCompressionType.NONE);
        assertThat(desc.tableCreationParameters().tableName()).isEqualTo(TABLE_NAME);
        assertThat(desc.startTime()).isNotNull();

        importArn = desc.importArn();
    }

    @Test
    @Order(2)
    void listImports_containsImport() {
        assertThat(importArn).isNotNull();

        var resp = ddb.listImports(ListImportsRequest.builder().build());

        assertThat(resp.importSummaryList())
                .extracting(ImportSummary::importArn)
                .contains(importArn);
    }

    @Test
    @Order(3)
    void describeImport_reachesCompleted() throws Exception {
        assertThat(importArn).isNotNull();

        var desc = describeImport();
        var deadline = System.currentTimeMillis() + 10_000;
        while (desc.importStatus() == ImportStatus.IN_PROGRESS && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            desc = describeImport();
        }

        assertThat(desc.importStatus())
                .as("%s: %s", desc.failureCode(), desc.failureMessage())
                .isEqualTo(ImportStatus.COMPLETED);
        assertThat(desc.processedItemCount()).isEqualTo(2L);
        assertThat(desc.importedItemCount()).isEqualTo(2L);
        assertThat(desc.errorCount()).isZero();
        assertThat(desc.endTime()).isNotNull();
    }

    @Test
    @Order(4)
    void importedTable_isActiveAndHoldsTheItems() {
        var table = ddb.describeTable(DescribeTableRequest.builder().tableName(TABLE_NAME).build());
        assertThat(table.table().tableStatus()).isEqualTo(TableStatus.ACTIVE);

        var scan = ddb.scan(ScanRequest.builder().tableName(TABLE_NAME).build());
        assertThat(scan.count()).isEqualTo(2);
        assertThat(scan.items())
                .extracting(item -> item.get("pk").s())
                .containsExactlyInAnyOrder("imported1", "imported2");
    }

    @Test
    @Order(5)
    void importTable_existingTable_throwsResourceInUseException() {
        assertThatThrownBy(() -> ddb.importTable(importRequest()))
                .isInstanceOf(ResourceInUseException.class);
    }

    @Test
    @Order(6)
    void describeImport_notFound_throwsImportNotFoundException() {
        assertThatThrownBy(() -> ddb.describeImport(DescribeImportRequest.builder()
                        .importArn("arn:aws:dynamodb:us-east-1:000000000000:table/T/import/doesnotexist")
                        .build()))
                .isInstanceOf(ImportNotFoundException.class);
    }
}
