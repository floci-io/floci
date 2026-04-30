package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbExportIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE_NAME = "ExportTestTable";
    private static final String BUCKET_NAME = "export-test-bucket";
    private static final String TABLE_ARN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE_NAME;

    private static boolean setupDone = false;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void ensureSetup() {
        if (setupDone) return;

        // Create S3 bucket
        given()
            .when().put("/" + BUCKET_NAME)
            .then().statusCode(anyOf(equalTo(200), equalTo(409)));

        // Create table
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"}
                    ]
                }
                """.formatted(TABLE_NAME))
            .when().post("/").then().statusCode(200);

        // Insert 3 items
        putItem("{\"pk\": {\"S\": \"user-1\"}, \"sk\": {\"S\": \"order-001\"}, \"total\": {\"N\": \"99\"}}");
        putItem("{\"pk\": {\"S\": \"user-2\"}, \"sk\": {\"S\": \"order-002\"}, \"total\": {\"N\": \"55\"}}");
        putItem("{\"pk\": {\"S\": \"user-3\"}, \"sk\": {\"S\": \"order-003\"}, \"total\": {\"N\": \"150\"}}");

        setupDone = true;
    }

    @Test
    void exportTableToPointInTime_returnsInProgressOrCompleted() {
        String exportArn = given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "S3Prefix": "exports",
                    "ExportFormat": "DYNAMODB_JSON"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(200)
            .body("ExportDescription.ExportArn", notNullValue())
            .body("ExportDescription.ExportStatus", oneOf("IN_PROGRESS", "COMPLETED"))
            .body("ExportDescription.TableArn", equalTo(TABLE_ARN))
            .body("ExportDescription.S3Bucket", equalTo(BUCKET_NAME))
            .body("ExportDescription.ExportFormat", equalTo("DYNAMODB_JSON"))
            .body("ExportDescription.ExportType", equalTo("FULL_EXPORT"))
            .extract().path("ExportDescription.ExportArn");

        assertNotNull(exportArn);
        assertTrue(exportArn.contains("/export/"), "ExportArn should contain /export/ segment");

        // Poll DescribeExport until COMPLETED (max 10s)
        String status = pollUntilCompleted(exportArn, 10_000);
        assertEquals("COMPLETED", status, "Export should complete within 10 seconds");
    }

    @Test
    void describeExport_returnsCompletedExportWithS3Manifest() throws Exception {
        String exportArn = startExport("exports-describe");
        String status = pollUntilCompleted(exportArn, 10_000);
        assertEquals("COMPLETED", status);

        // Verify ExportManifest is set
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"ExportArn\": \"" + exportArn + "\"}")
            .when().post("/")
            .then()
            .statusCode(200)
            .body("ExportDescription.ExportStatus", equalTo("COMPLETED"))
            .body("ExportDescription.ItemCount", equalTo(3))
            .body("ExportDescription.BilledSizeBytes", greaterThan(0))
            .body("ExportDescription.ExportManifest", notNullValue());
    }

    @Test
    void listExports_returnsCompletedExport() throws Exception {
        String exportArn = startExport("exports-list");
        pollUntilCompleted(exportArn, 10_000);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ListExports")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableArn\": \"" + TABLE_ARN + "\"}")
            .when().post("/")
            .then()
            .statusCode(200)
            .body("ExportSummaries", hasSize(greaterThanOrEqualTo(1)))
            .body("ExportSummaries[0].ExportArn", notNullValue())
            .body("ExportSummaries[0].ExportStatus", notNullValue())
            .body("ExportSummaries[0].ExportType", equalTo("FULL_EXPORT"));
    }

    @Test
    void exportTableToPointInTime_unsupportedExportType_returnsValidationException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "ExportType": "INCREMENTAL_EXPORT"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"));
    }

    @Test
    void exportTableToPointInTime_ionFormat_returnsValidationException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "ExportFormat": "ION"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"));
    }

    @Test
    void exportTableToPointInTime_tableNotFound_returnsResourceNotFoundException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "arn:aws:dynamodb:us-east-1:000000000000:table/NonExistentTable",
                    "S3Bucket": "%s"
                }
                """.formatted(BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void describeExport_notFound_returnsExportNotFoundException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"ExportArn\": \"arn:aws:dynamodb:us-east-1:000000000000:table/T/export/doesnotexist\"}")
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("ExportNotFoundException"));
    }

    @Test
    void exportData_s3ObjectsExist_andNdjsonIsValid() throws Exception {
        String exportArn = startExport("exports-data");
        pollUntilCompleted(exportArn, 10_000);

        String manifestKey = given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"ExportArn\": \"" + exportArn + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .extract().path("ExportDescription.ExportManifest");

        assertNotNull(manifestKey, "ExportManifest key should be set");

        // Extract bucket prefix from the manifest key
        // manifestKey is like: exports-data/AWSDynamoDB/<exportId>/manifest-summary.json
        String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        String manifestFilesKey = "exports-data/AWSDynamoDB/" + exportId + "/manifest-files.json";

        // Download manifest-files.json and find the data key
        byte[] manifestFiles = given()
            .when().get("/" + BUCKET_NAME + "/" + manifestFilesKey)
            .then().statusCode(200)
            .extract().asByteArray();

        String dataKey = new String(manifestFiles, StandardCharsets.UTF_8).trim();
        assertFalse(dataKey.isEmpty(), "manifest-files.json should contain the data file key");
        assertTrue(dataKey.endsWith(".json.gz"), "Data file should be a .json.gz file");

        // Download and decompress the data file
        byte[] gzipData = given()
            .when().get("/" + BUCKET_NAME + "/" + dataKey)
            .then().statusCode(200)
            .extract().asByteArray();

        String ndjson = decompressGzip(gzipData);
        String[] lines = ndjson.split("\n");
        assertEquals(3, lines.length, "Should have 3 items in the export");

        for (String line : lines) {
            assertTrue(line.contains("\"Item\""), "Each line should have Item wrapper");
            assertTrue(line.contains("\"pk\""), "Each line should have pk attribute");
        }
    }

    // --- Helpers ---

    private void putItem(String itemJson) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"" + TABLE_NAME + "\", \"Item\": " + itemJson + "}")
            .when().post("/").then().statusCode(200);
    }

    private String startExport(String prefix) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "S3Prefix": "%s"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME, prefix))
            .when().post("/")
            .then().statusCode(200)
            .extract().path("ExportDescription.ExportArn");
    }

    private String pollUntilCompleted(String exportArn, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = given()
                .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"ExportArn\": \"" + exportArn + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("ExportDescription.ExportStatus");

            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
        }
        return "TIMEOUT";
    }

    private String decompressGzip(byte[] data) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
