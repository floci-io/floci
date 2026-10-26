package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static io.github.hectorvent.floci.testing.DynamoDbRequests.decimalAt;
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
    private static final String TABLE_ARN_PREFIX = "arn:aws:dynamodb:us-east-1:000000000000:table/";

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
        enablePointInTimeRecovery(TABLE_NAME);

        // Insert 3 items
        putItem("{\"pk\": {\"S\": \"user-1\"}, \"sk\": {\"S\": \"order-001\"}, \"total\": {\"N\": \"99\"}}");
        putItem("{\"pk\": {\"S\": \"user-2\"}, \"sk\": {\"S\": \"order-002\"}, \"total\": {\"N\": \"55\"}}");
        putItem("{\"pk\": {\"S\": \"user-3\"}, \"sk\": {\"S\": \"order-003\"}, \"total\": {\"N\": \"150\"}}");

        setupDone = true;
    }

    @Test
    void exportTableToPointInTime_returnsInProgress() {
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
            .body("ExportDescription.ExportArn", matchesPattern(TABLE_ARN + "/export/\\d{14}-[0-9a-f]{8}"))
            .body("ExportDescription.ExportStatus", equalTo("IN_PROGRESS"))
            .body("ExportDescription.TableArn", equalTo(TABLE_ARN))
            .body("ExportDescription.S3Bucket", equalTo(BUCKET_NAME))
            .body("ExportDescription.ExportFormat", equalTo("DYNAMODB_JSON"))
            .body("ExportDescription", not(hasKey("ExportType")))
            .body("ExportDescription.DestinationType", equalTo("S3"))
            .body("ExportDescription.S3SseAlgorithm", equalTo("AES256"))
            .body("ExportDescription.TableId", equalTo(tableId(TABLE_NAME)))
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
            .body("ExportSummaries[0].ExportType", equalTo("FULL_EXPORT"))
            .body("ExportSummaries[0].DestinationType", equalTo("S3"));
    }

    @Test
    void incrementalExportRequestIsCheckedAsAwsChecksIt() {
        String over1024 = "a".repeat(1025);
        String enumSet = "' failed to satisfy constraint: Member must satisfy enum value set: ";
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3SseAlgorithm": "BAD", "S3Prefix": "%s", "ExportType": "BAD",
             "IncrementalExportSpecification": {"ExportViewType": "BAD"}, "ExportFormat": "BAD"}
            """.formatted(TABLE_ARN_PREFIX + "IncrementalMissing", BUCKET_NAME, over1024)), "ValidationException",
                "5 validation errors detected: Value 'BAD' at 's3SseAlgorithm" + enumSet + "[AES256, KMS]; Value '"
                + over1024 + "' at 's3Prefix' failed to satisfy constraint: Member must have length less than or equal to 1024; "
                + "Value 'BAD' at 'exportType" + enumSet + "[FULL_EXPORT, INCREMENTAL_EXPORT]; "
                + "Value 'BAD' at 'incrementalExportSpecification.exportViewType" + enumSet + "[NEW_AND_OLD_IMAGES, NEW_IMAGE]; "
                + "Value 'BAD' at 'exportFormat" + enumSet + "[ION, DYNAMODB_JSON]");
        expectError(incrementalExport("IncrementalMissing", "inc", null), "TableNotFoundException",
                "Table not found: " + TABLE_ARN_PREFIX + "IncrementalMissing");

        String tooLong = "a".repeat(949);
        expectError(incrementalExport(TABLE_NAME, tooLong, null), "ValidationException",
                "Invalid Request: Incremental Export Specification expected when export type is set as Incremental Export");
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "%s", "ExportType": "FULL_EXPORT",
             "IncrementalExportSpecification": {"ExportFromTime": 1}}
            """.formatted(TABLE_ARN, BUCKET_NAME, tooLong)), "ValidationException",
                "Invalid Request: When Export Type is Full Export , Incremental Export Specification should not be provided");
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "%s", "IncrementalExportSpecification": {"ExportFromTime": 1}}
            """.formatted(TABLE_ARN, BUCKET_NAME, tooLong)), "ValidationException",
                "Invalid Request: Export Type expected to be Incremental Export when incremental export specification is provided");
        expectError(incrementalExport(TABLE_NAME, tooLong, "{\"ExportToTime\": 1}"), "ValidationException",
                "Invalid Request: ExportFromTime must be provided in IncrementalExportSpecification");
        expectError(incrementalExport(TABLE_NAME, tooLong, "{\"ExportFromTime\": 1}"), "ValidationException",
                "Invalid Request: S3 Prefix is too long");
        expectError(incrementalExport(TABLE_NAME, "inc", "{\"ExportFromTime\": 1000000000.5, \"ExportToTime\": 1}"),
                "InvalidExportTimeException",
                "Incremental export period from time cannot be less than the table creation time : 2001-09-09T01:46:40.500Z");
        long now = System.currentTimeMillis() / 1000;
        expectError(incrementalExport(TABLE_NAME, "inc", "{\"ExportFromTime\": %d, \"ExportToTime\": %d}"
                .formatted(now, now + 3600)), "InvalidExportTimeException",
                "Incremental export period to time cannot be greater than the current time : "
                + Instant.ofEpochSecond(now + 3600));
        expectError(incrementalExport(TABLE_NAME, "inc", "{\"ExportFromTime\": %d}".formatted(now + 60)),
                "InvalidExportTimeException",
                "Incremental export period from time should not be greater or equal to incremental export period to time");
    }

    @Test
    void exportFormatIsCheckedAsAwsChecksIt() {
        String enumSet = "' at 'exportFormat' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [ION, DYNAMODB_JSON]";
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "ExportFormat": "ion", "S3Prefix": "x"}
            """.formatted(TABLE_ARN_PREFIX + "ExportFormatMissing", BUCKET_NAME)), "ValidationException",
                "1 validation error detected: Value 'ion" + enumSet);
        String over1024 = "a".repeat(1025);
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "ExportFormat": "", "S3Prefix": "%s"}
            """.formatted(TABLE_ARN, BUCKET_NAME, over1024)), "ValidationException",
                "2 validation errors detected: Value '" + over1024 + "' at 's3Prefix' failed to satisfy constraint: "
                + "Member must have length less than or equal to 1024; Value '" + enumSet);
        ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "exports-ion", "ExportFormat": "ION"}
            """.formatted(TABLE_ARN, BUCKET_NAME)).statusCode(200)
            .body("ExportDescription.ExportFormat", equalTo("ION"));
    }

    @Test
    void exportTableToPointInTime_tableNotFound_returnsTableNotFoundException() {
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
            .body("__type", containsString("TableNotFoundException"))
            .body("message", equalTo(
                "Table not found: arn:aws:dynamodb:us-east-1:000000000000:table/NonExistentTable"));
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

        ValidatableResponse describe = ddb("DescribeExport", "{\"ExportArn\": \"" + exportArn + "\"}").statusCode(200);
        JsonPath described = describe.extract().jsonPath();
        String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        String dir = "exports-data/AWSDynamoDB/" + exportId + "/";
        assertEquals(dir + "manifest-summary.json", described.getString("ExportDescription.ExportManifest"));

        assertEquals(0, s3Object(dir + "_started").length);
        byte[] manifestFiles = s3Object(dir + "manifest-files.json");
        assertEquals(md5Base64(manifestFiles), new String(s3Object(dir + "manifest-files.md5"), StandardCharsets.UTF_8));
        String manifestLine = new String(manifestFiles, StandardCharsets.UTF_8);
        assertTrue(manifestLine.endsWith("}\n"), manifestLine);
        JsonPath dataFile = JsonPath.from(manifestLine);
        String dataKey = dataFile.getString("dataFileS3Key");
        assertTrue(dataKey.matches(dir + "data/[a-z2-7]{26}\\.json\\.gz"), dataKey);
        byte[] gzipData = s3Object(dataKey);
        assertEquals(3, dataFile.getInt("itemCount"));
        assertEquals(md5Base64(gzipData), dataFile.getString("md5Checksum"));
        given().when().head("/" + BUCKET_NAME + "/" + dataKey)
            .then().statusCode(200)
            .header("ETag", equalTo("\"" + dataFile.getString("etag") + "\""))
            .header("ETag", endsWith("-1\""))
            .header("Content-Type", equalTo("application/octet-stream"));

        byte[] summaryBytes = s3Object(dir + "manifest-summary.json");
        assertEquals(md5Base64(summaryBytes), new String(s3Object(dir + "manifest-summary.md5"), StandardCharsets.UTF_8));
        JsonNode summary = new ObjectMapper().readTree(summaryBytes);
        List<String> fields = new ArrayList<>();
        summary.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("version", "exportArn", "startTime", "endTime", "tableArn", "tableId", "exportTime",
                "s3Bucket", "s3Prefix", "s3SseAlgorithm", "s3SseKmsKeyId", "manifestFilesS3Key", "billedSizeBytes",
                "itemCount", "outputFormat"), fields);
        assertEquals("2020-06-30", summary.get("version").asText());
        assertEquals(manifestTime(decimalAt(describe, "/ExportDescription/StartTime")), summary.get("startTime").asText());
        assertEquals(manifestTime(decimalAt(describe, "/ExportDescription/EndTime")), summary.get("endTime").asText());
        assertEquals(tableId(TABLE_NAME), summary.get("tableId").asText());
        assertEquals("exports-data", summary.get("s3Prefix").asText());
        assertTrue(summary.get("s3SseKmsKeyId").isNull());
        assertEquals(dir + "manifest-files.json", summary.get("manifestFilesS3Key").asText());
        assertEquals(3, summary.get("itemCount").asInt());
        assertEquals("DYNAMODB_JSON", summary.get("outputFormat").asText());

        String ndjson = decompressGzip(gzipData);
        String[] lines = ndjson.split("\n");
        assertEquals(3, lines.length, "Should have 3 items in the export");

        for (String line : lines) {
            assertTrue(line.contains("\"Item\""), "Each line should have Item wrapper");
            assertTrue(line.contains("\"pk\""), "Each line should have pk attribute");
        }
    }

    @Test
    void exportTimeExportsTheItemsOfThatTime() throws Exception {
        String table = "ExportTimeItems";
        createHashTable(table);
        BigDecimal earliest = decimalAt(enablePointInTimeRecovery(table),
                "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/EarliestRestorableDateTime");
        putHashItem(table, "k1", "v1");
        BigDecimal t1 = pause();
        putHashItem(table, "k1", "v2");
        putHashItem(table, "k2", "v1");

        exportAt(table, "export-time-earliest", earliest).statusCode(200);
        ValidatableResponse atT1 = exportAt(table, "export-time-t1", t1).statusCode(200);
        assertEquals(0, t1.compareTo(decimalAt(atT1, "/ExportDescription/ExportTime")));
        long before = System.currentTimeMillis();
        ValidatableResponse noTime = ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "export-time-now"}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME)).statusCode(200);
        long after = System.currentTimeMillis();
        BigDecimal startTime = decimalAt(noTime, "/ExportDescription/StartTime");
        assertEquals(0, startTime.compareTo(decimalAt(noTime, "/ExportDescription/ExportTime")));
        long startMillis = startTime.movePointRight(3).longValueExact();
        assertTrue(before <= startMillis && startMillis <= after, before + " <= " + startTime + " <= " + after);

        String atT1Arn = atT1.extract().path("ExportDescription.ExportArn");
        assertEquals("COMPLETED", pollUntilCompleted(atT1Arn, 10_000));
        assertEquals(List.of("k1=v1"), exportedItems(atT1Arn, "export-time-t1"));
        String noTimeArn = noTime.extract().path("ExportDescription.ExportArn");
        assertTrue(noTimeArn.contains("/export/" + String.format("%014d", startMillis) + "-"), noTimeArn);
        assertEquals("COMPLETED", pollUntilCompleted(noTimeArn, 10_000));
        assertEquals(List.of("k1=v2", "k2=v1"), exportedItems(noTimeArn, "export-time-now"));
    }

    @Test
    void exportTableToPointInTimeChecksInTheAwsOrder() throws Exception {
        String table = "ExportTimeChecks";
        long beforeCreate = System.currentTimeMillis();
        createHashTable(table);
        long afterCreate = System.currentTimeMillis();
        BigDecimal future = BigDecimal.valueOf(System.currentTimeMillis() + 60_000, 3);

        expectError(exportAt("ExportTimeMissing", "export-checks", future),
                "TableNotFoundException", "Table not found: " + TABLE_ARN_PREFIX + "ExportTimeMissing");
        expectError(exportAt(table, "export-checks", future), "InvalidExportTimeException", "Export Time is invalid");
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s"}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME)), "PointInTimeRecoveryUnavailableException",
                "Point in time recovery is not enabled for table '" + table + "'");

        // EarliestRestorableDateTime is in whole seconds. Enabling in a later second puts it after the creation.
        while (System.currentTimeMillis() / 1000 <= afterCreate / 1000) {
            Thread.sleep(10);
        }
        BigDecimal earliest = decimalAt(enablePointInTimeRecovery(table),
                "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/EarliestRestorableDateTime");
        expectError(exportAt(table, "export-checks", BigDecimal.valueOf(beforeCreate - 1000, 3)),
                "InvalidExportTimeException", "Export Time is invalid");
        expectError(exportAt(table, "export-checks", earliest.subtract(new BigDecimal("0.001"))),
                "InvalidExportTimeException", "Point in Time Recovery is not available for specified export time");
        expectError(exportAt(table, "export-checks", future), "InvalidExportTimeException", "Export Time is invalid");
        exportAt(table, "export-checks", earliest).statusCode(200);
    }

    @Test
    void exportTimeBeyondTheMillisecondRangeIsInvalid() {
        String table = "ExportTimeRange";
        createHashTable(table);
        enablePointInTimeRecovery(table);
        BigDecimal nowPlusTwoToThe64Millis = BigDecimal.valueOf(System.currentTimeMillis(), 3)
                .add(new BigDecimal(BigInteger.TWO.pow(64), 3));

        expectError(exportAt(table, "export-range", nowPlusTwoToThe64Millis),
                "InvalidExportTimeException", "Export Time is invalid");
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "ExportTime": 1e400}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME)),
                "InvalidExportTimeException", "Export Time is invalid");
    }

    @Test
    void s3PrefixIsCheckedAsAwsChecksIt() {
        String atLeast3 = "' at 's3Prefix' failed to satisfy constraint: Member must have length greater than or equal to 3";
        expectError(exportWithPrefix("ExportPrefixMissing", ""), "ValidationException",
                "Invalid Request: 1 validation error detected: Value '" + atLeast3);
        expectError(exportWithPrefix("ExportPrefixMissing", "éé"), "ValidationException",
                "Invalid Request: 1 validation error detected: Value 'éé" + atLeast3);
        String over1024 = "a".repeat(1025);
        expectError(exportWithPrefix(TABLE_NAME, over1024), "ValidationException", "1 validation error detected: Value '"
                + over1024 + "' at 's3Prefix' failed to satisfy constraint: Member must have length less than or equal to 1024");
        expectError(exportWithPrefix("ExportPrefixMissing", "a".repeat(949)), "TableNotFoundException",
                "Table not found: " + TABLE_ARN_PREFIX + "ExportPrefixMissing");
        expectError(exportWithPrefix(TABLE_NAME, "é".repeat(475)), "ValidationException",
                "Invalid Request: S3 Prefix is too long");
        expectError(exportWithPrefix(TABLE_NAME, "a".repeat(949)), "ValidationException",
                "Invalid Request: S3 Prefix is too long");
        exportWithPrefix(TABLE_NAME, "ends-with/").statusCode(200)
            .body("ExportDescription.S3Prefix", equalTo("ends-with/"));
    }

    @Test
    void s3PrefixLengthCountsSupplementaryCharactersOnce() {
        String scalar = "\uD83D\uDE00";
        String shortPrefix = scalar.repeat(2);
        expectError(exportWithPrefix("ExportPrefixMissing", shortPrefix), "ValidationException",
                "Invalid Request: 1 validation error detected: Value '" + shortPrefix
                        + "' at 's3Prefix' failed to satisfy constraint: Member must have length greater than or equal to 3");
        expectError(exportWithPrefix("ExportPrefixMissing", scalar.repeat(1024)), "TableNotFoundException",
                "Table not found: " + TABLE_ARN_PREFIX + "ExportPrefixMissing");
        String longPrefix = scalar.repeat(1025);
        expectError(exportWithPrefix("ExportPrefixMissing", longPrefix), "ValidationException",
                "1 validation error detected: Value '" + longPrefix
                        + "' at 's3Prefix' failed to satisfy constraint: Member must have length less than or equal to 1024");
    }

    // --- Helpers ---

    private static ValidatableResponse incrementalExport(String table, String prefix, String specification) {
        return ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "%s", "ExportType": "INCREMENTAL_EXPORT"%s}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME, prefix,
                specification != null ? ", \"IncrementalExportSpecification\": " + specification : ""));
    }

    private static ValidatableResponse exportWithPrefix(String table, String prefix) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "%s"}
                """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME, prefix).getBytes(StandardCharsets.UTF_8))
            .when().post("/")
            .then();
    }

    /** Returns a time between the writes before and after the call, in epoch seconds with milliseconds. */
    private static BigDecimal pause() {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() <= now) {
            Thread.onSpinWait();
        }
        return BigDecimal.valueOf(now, 3);
    }

    private static void createHashTable(String table) {
        ddb("CreateTable", """
            {"TableName": "%s", "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
             "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
             "BillingMode": "PAY_PER_REQUEST"}
            """.formatted(table)).statusCode(200);
    }

    private static void putHashItem(String table, String pk, String value) {
        ddb("PutItem", """
            {"TableName": "%s", "Item": {"pk": {"S": "%s"}, "v": {"S": "%s"}}}
            """.formatted(table, pk, value)).statusCode(200);
    }

    private static ValidatableResponse exportAt(String table, String prefix, BigDecimal exportTime) {
        return ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "%s", "ExportTime": %s}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME, prefix, exportTime.toPlainString()));
    }

    private List<String> exportedItems(String exportArn, String prefix) throws Exception {
        String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        String dataKey = JsonPath.from(new String(s3Object(prefix + "/AWSDynamoDB/" + exportId + "/manifest-files.json"),
                StandardCharsets.UTF_8)).getString("dataFileS3Key");
        byte[] gzipData = s3Object(dataKey);
        List<String> items = new ArrayList<>();
        for (String line : decompressGzip(gzipData).split("\n")) {
            JsonPath item = JsonPath.from(line);
            items.add(item.getString("Item.pk.S") + "=" + item.getString("Item.v.S"));
        }
        return items.stream().sorted().toList();
    }

    private static byte[] s3Object(String key) {
        return given().when().get("/" + BUCKET_NAME + "/" + key)
            .then().statusCode(200)
            .extract().asByteArray();
    }

    private static String md5Base64(byte[] data) {
        return Base64.getEncoder().encodeToString(Digests.md5(data));
    }

    /** A DescribeExport time in epoch seconds, as the manifest writes it. */
    private static String manifestTime(BigDecimal epochSeconds) {
        return Instant.ofEpochMilli(epochSeconds.movePointRight(3).longValueExact()).toString()
                .replaceFirst("(:\\d{2})Z$", "$1.000Z");
    }

    private static String tableId(String table) {
        return ddb("DescribeTable", "{\"TableName\": \"" + table + "\"}")
            .statusCode(200)
            .extract().path("Table.TableId");
    }

    private static void expectError(ValidatableResponse response, String type, String message) {
        response.statusCode(400)
            .body("__type", containsString(type))
            .body("message", equalTo(message));
    }

    private static ValidatableResponse enablePointInTimeRecovery(String table) {
        return ddb("UpdateContinuousBackups", """
            {"TableName": "%s", "PointInTimeRecoverySpecification": {"PointInTimeRecoveryEnabled": true}}
            """.formatted(table)).statusCode(200);
    }

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
