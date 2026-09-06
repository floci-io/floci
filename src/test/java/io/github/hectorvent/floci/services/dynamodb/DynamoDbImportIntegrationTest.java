package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbImportIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String BUCKET_NAME = "import-test-bucket";
    private static final String TABLE_ARN_PREFIX = "arn:aws:dynamodb:us-east-1:000000000000:table/";

    private static boolean setupDone = false;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void ensureSetup() {
        if (setupDone) {
            return;
        }
        given()
            .when().put("/" + BUCKET_NAME)
            .then().statusCode(anyOf(equalTo(200), equalTo(409)));
        setupDone = true;
    }

    @Test
    void importTable_loadsNdjsonIntoNewTable() {
        putObject("plain/data.json",
                "{\"Item\":{\"pk\":{\"S\":\"imported1\"}}}\n{\"Item\":{\"pk\":{\"S\":\"imported2\"}}}\n");

        var importArn = dynamo("ImportTable", importRequest("ImportPlain", BUCKET_NAME, "plain/", "DYNAMODB_JSON", "NONE"))
            .then()
            .statusCode(200)
            .body("ImportTableDescription.ImportArn", containsString("/import/"))
            .body("ImportTableDescription.ImportStatus", equalTo("IN_PROGRESS"))
            .body("ImportTableDescription.TableArn", equalTo(TABLE_ARN_PREFIX + "ImportPlain"))
            .body("ImportTableDescription.TableId", notNullValue())
            .body("ImportTableDescription.S3BucketSource.S3Bucket", equalTo(BUCKET_NAME))
            .body("ImportTableDescription.S3BucketSource.S3KeyPrefix", equalTo("plain/"))
            .body("ImportTableDescription.InputFormat", equalTo("DYNAMODB_JSON"))
            .body("ImportTableDescription.InputCompressionType", equalTo("NONE"))
            .body("ImportTableDescription.TableCreationParameters.TableName", equalTo("ImportPlain"))
            .body("ImportTableDescription.StartTime", notNullValue())
            .extract().jsonPath().getString("ImportTableDescription.ImportArn");

        dynamo("ListImports", "{}")
            .then()
            .statusCode(200)
            .body("ImportSummaryList.ImportArn", hasItem(importArn));

        dynamo("ListImports", "{\"TableArn\": \"" + TABLE_ARN_PREFIX + "ImportPlain\"}")
            .then()
            .statusCode(200)
            .body("ImportSummaryList.ImportArn", hasItem(importArn))
            .body("ImportSummaryList.find { it.ImportArn == '" + importArn + "' }.InputFormat", equalTo("DYNAMODB_JSON"));

        dynamo("ListImports", "{\"TableArn\": \"" + TABLE_ARN_PREFIX + "SomeOtherTable\"}")
            .then()
            .statusCode(200)
            .body("ImportSummaryList.ImportArn", not(hasItem(importArn)));

        assertEquals("COMPLETED", pollUntilDone(importArn));

        describeImport(importArn)
            .then()
            .statusCode(200)
            .body("ImportTableDescription.ImportArn", equalTo(importArn))
            .body("ImportTableDescription.ImportStatus", equalTo("COMPLETED"))
            .body("ImportTableDescription.ProcessedItemCount", equalTo(2))
            .body("ImportTableDescription.ImportedItemCount", equalTo(2))
            .body("ImportTableDescription.ErrorCount", equalTo(0))
            .body("ImportTableDescription.ProcessedSizeBytes", greaterThan(0))
            .body("ImportTableDescription.EndTime", notNullValue());

        dynamo("DescribeTable", "{\"TableName\": \"ImportPlain\"}")
            .then()
            .statusCode(200)
            .body("Table.TableStatus", equalTo("ACTIVE"))
            .body("Table.ItemCount", equalTo(2));

        dynamo("GetItem", "{\"TableName\": \"ImportPlain\", \"Key\": {\"pk\": {\"S\": \"imported1\"}}}")
            .then()
            .statusCode(200)
            .body("Item.pk.S", equalTo("imported1"));
    }

    @Test
    void importTable_gzip_roundTripsAnExport() {
        createCompositeTable("RoundTripSource");
        for (var i = 1; i <= 3; i++) {
            dynamo("PutItem", "{\"TableName\": \"RoundTripSource\", \"Item\": {\"pk\": {\"S\": \"user-" + i
                    + "\"}, \"sk\": {\"S\": \"order-" + i + "\"}, \"total\": {\"N\": \"" + (i * 10) + "\"}}}")
                .then().statusCode(200);
        }

        var exportArn = dynamo("ExportTableToPointInTime", """
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "S3Prefix": "roundtrip"
                }
                """.formatted(TABLE_ARN_PREFIX + "RoundTripSource", BUCKET_NAME))
            .then().statusCode(200)
            .extract().jsonPath().getString("ExportDescription.ExportArn");
        assertEquals("COMPLETED", pollExport(exportArn));

        var exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        var dataPrefix = "roundtrip/AWSDynamoDB/" + exportId + "/data/";

        var importArn = dynamo("ImportTable", """
                {
                    "S3BucketSource": {"S3Bucket": "%s", "S3KeyPrefix": "%s"},
                    "InputFormat": "DYNAMODB_JSON",
                    "InputCompressionType": "GZIP",
                    "TableCreationParameters": {
                        "TableName": "RoundTripTarget",
                        "AttributeDefinitions": [
                            {"AttributeName": "pk", "AttributeType": "S"},
                            {"AttributeName": "sk", "AttributeType": "S"}
                        ],
                        "KeySchema": [
                            {"AttributeName": "pk", "KeyType": "HASH"},
                            {"AttributeName": "sk", "KeyType": "RANGE"}
                        ],
                        "BillingMode": "PAY_PER_REQUEST"
                    }
                }
                """.formatted(BUCKET_NAME, dataPrefix))
            .then().statusCode(200)
            .body("ImportTableDescription.ImportStatus", equalTo("IN_PROGRESS"))
            .extract().jsonPath().getString("ImportTableDescription.ImportArn");

        assertEquals("COMPLETED", pollUntilDone(importArn));

        describeImport(importArn)
            .then()
            .statusCode(200)
            .body("ImportTableDescription.ImportedItemCount", equalTo(3))
            .body("ImportTableDescription.ErrorCount", equalTo(0));

        dynamo("Scan", "{\"TableName\": \"RoundTripTarget\"}")
            .then()
            .statusCode(200)
            .body("Count", equalTo(3))
            .body("Items.total.N", containsInAnyOrder("10", "20", "30"));
    }

    @Test
    void importTable_skipsMalformedLinesAndCountsThem() {
        putObject("bad/data.json",
                "{\"Item\":{\"pk\":{\"S\":\"good\"}}}\nnot json at all\n{\"NoItem\":1}\n\n{\"Item\":{\"other\":{\"S\":\"missing key\"}}}\n");

        var importArn = dynamo("ImportTable", importRequest("ImportBadLines", BUCKET_NAME, "bad/", "DYNAMODB_JSON", "NONE"))
            .then().statusCode(200)
            .extract().jsonPath().getString("ImportTableDescription.ImportArn");

        assertEquals("COMPLETED", pollUntilDone(importArn));

        describeImport(importArn)
            .then()
            .statusCode(200)
            .body("ImportTableDescription.ProcessedItemCount", equalTo(4))
            .body("ImportTableDescription.ImportedItemCount", equalTo(1))
            .body("ImportTableDescription.ErrorCount", equalTo(3));
    }

    @Test
    void importTable_missingBucket_failsWithS3NoSuchBucket() {
        var importArn = dynamo("ImportTable", importRequest("ImportNoBucket", "no-such-import-bucket", "imp/", "DYNAMODB_JSON", "NONE"))
            .then().statusCode(200)
            .body("ImportTableDescription.ImportStatus", equalTo("IN_PROGRESS"))
            .extract().jsonPath().getString("ImportTableDescription.ImportArn");

        assertEquals("FAILED", pollUntilDone(importArn));

        describeImport(importArn)
            .then()
            .statusCode(200)
            .body("ImportTableDescription.FailureCode", equalTo("S3NoSuchBucket"))
            .body("ImportTableDescription.FailureMessage", containsString("no-such-import-bucket"))
            .body("ImportTableDescription.EndTime", notNullValue());

        dynamo("DescribeTable", "{\"TableName\": \"ImportNoBucket\"}")
            .then()
            .statusCode(200)
            .body("Table.TableStatus", equalTo("ACTIVE"))
            .body("Table.ItemCount", equalTo(0));
    }

    @Test
    void importTable_emptyPrefix_fails() {
        var importArn = dynamo("ImportTable", importRequest("ImportEmptyPrefix", BUCKET_NAME, "nothing-here/", "DYNAMODB_JSON", "NONE"))
            .then().statusCode(200)
            .extract().jsonPath().getString("ImportTableDescription.ImportArn");

        assertEquals("FAILED", pollUntilDone(importArn));

        describeImport(importArn)
            .then()
            .statusCode(200)
            .body("ImportTableDescription.FailureCode", equalTo("S3NoSuchKey"));
    }

    @Test
    void importTable_sameClientToken_returnsTheSameImport() {
        putObject("token/data.json", "{\"Item\":{\"pk\":{\"S\":\"one\"}}}\n");
        var body = """
                {
                    "ClientToken": "import-token-1",
                    "S3BucketSource": {"S3Bucket": "%s", "S3KeyPrefix": "token/"},
                    "InputFormat": "DYNAMODB_JSON",
                    "TableCreationParameters": {
                        "TableName": "ImportToken",
                        "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                        "BillingMode": "PAY_PER_REQUEST"
                    }
                }
                """.formatted(BUCKET_NAME);

        var first = dynamo("ImportTable", body)
            .then().statusCode(200)
            .body("ImportTableDescription.ClientToken", equalTo("import-token-1"))
            .extract().jsonPath().getString("ImportTableDescription.ImportArn");

        var second = dynamo("ImportTable", body)
            .then().statusCode(200)
            .extract().jsonPath().getString("ImportTableDescription.ImportArn");

        assertEquals(first, second);
    }

    @Test
    void importTable_existingTable_returnsResourceInUseException() {
        createCompositeTable("ImportExisting");

        dynamo("ImportTable", importRequest("ImportExisting", BUCKET_NAME, "plain/", "DYNAMODB_JSON", "NONE"))
            .then()
            .statusCode(400)
            .body("__type", containsString("ResourceInUseException"))
            .body("message", containsString("ImportExisting"));
    }

    @Test
    void importTable_csvFormat_returnsValidationException() {
        dynamo("ImportTable", importRequest("ImportCsv", BUCKET_NAME, "plain/", "CSV", "NONE"))
            .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"))
            .body("message", containsString("CSV"));

        dynamo("DescribeTable", "{\"TableName\": \"ImportCsv\"}")
            .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void importTable_zstdCompression_returnsValidationException() {
        dynamo("ImportTable", importRequest("ImportZstd", BUCKET_NAME, "plain/", "DYNAMODB_JSON", "ZSTD"))
            .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"))
            .body("message", containsString("ZSTD"));
    }

    @Test
    void importTable_missingBucketName_returnsValidationException() {
        dynamo("ImportTable", "{\"InputFormat\": \"DYNAMODB_JSON\", \"TableCreationParameters\": {\"TableName\": \"ImportNoSource\"}}")
            .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"));
    }

    @Test
    void describeImport_notFound_returnsImportNotFoundException() {
        describeImport(TABLE_ARN_PREFIX + "T/import/doesnotexist")
            .then()
            .statusCode(400)
            .body("__type", containsString("ImportNotFoundException"));
    }

    // --- Helpers ---

    private io.restassured.response.Response dynamo(String action, String body) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810." + action)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body(body)
            .when().post("/");
    }

    private void putObject(String key, String body) {
        given()
            .header("Content-Type", "application/octet-stream")
            .body(body.getBytes(StandardCharsets.UTF_8))
            .when().put("/" + BUCKET_NAME + "/" + key)
            .then().statusCode(200);
    }

    private void createCompositeTable(String tableName) {
        dynamo("CreateTable", """
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(tableName))
            .then().statusCode(200);
    }

    private String importRequest(String tableName, String bucket, String prefix, String format, String compression) {
        return """
                {
                    "S3BucketSource": {"S3Bucket": "%s", "S3KeyPrefix": "%s"},
                    "InputFormat": "%s",
                    "InputCompressionType": "%s",
                    "TableCreationParameters": {
                        "TableName": "%s",
                        "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                        "BillingMode": "PAY_PER_REQUEST"
                    }
                }
                """.formatted(bucket, prefix, format, compression, tableName);
    }

    private io.restassured.response.Response describeImport(String importArn) {
        return dynamo("DescribeImport", "{\"ImportArn\": \"" + importArn + "\"}");
    }

    private String pollUntilDone(String importArn) {
        return pollStatus(() -> describeImport(importArn), "ImportTableDescription.ImportStatus");
    }

    private String pollExport(String exportArn) {
        return pollStatus(() -> dynamo("DescribeExport", "{\"ExportArn\": \"" + exportArn + "\"}"),
                "ExportDescription.ExportStatus");
    }

    private String pollStatus(Supplier<io.restassured.response.Response> call, String statusPath) {
        var deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var status = call.get().then().statusCode(200).extract().jsonPath().getString(statusPath);
            if (!"IN_PROGRESS".equals(status)) {
                return status;
            }
            sleep(100);
        }
        return "TIMEOUT";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
