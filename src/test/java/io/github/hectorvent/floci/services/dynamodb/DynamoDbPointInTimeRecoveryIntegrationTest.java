package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.CONTENT_TYPE;
import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static io.github.hectorvent.floci.testing.DynamoDbRequests.decimalAt;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbPointInTimeRecoveryIntegrationTest {

    private static final String TABLE_ARN_PREFIX = "arn:aws:dynamodb:us-east-1:000000000000:table/";
    private static final String PITR = "ContinuousBackupsDescription.PointInTimeRecoveryDescription";
    private static final String OTHER_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260215/eu-west-2/dynamodb/aws4_request, "
            + "SignedHeaders=host, Signature=abc";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void enablingStartsTheRestoreWindowAndDisablingEndsIt() throws Exception {
        String table = "PitrIT-window";
        createTable(table);
        ddb("DescribeContinuousBackups", "{\"TableName\": \"" + table + "\"}")
                .statusCode(200)
                .body("ContinuousBackupsDescription.ContinuousBackupsStatus", equalTo("ENABLED"))
                .body(PITR + ".PointInTimeRecoveryStatus", equalTo("DISABLED"))
                .body(PITR + ".keySet()", contains("PointInTimeRecoveryStatus"));

        long before = System.currentTimeMillis();
        ValidatableResponse enabled = updateContinuousBackups(table, "true, \"RecoveryPeriodInDays\": 10")
                .statusCode(200)
                .body(PITR + ".PointInTimeRecoveryStatus", equalTo("ENABLED"))
                .body(PITR + ".RecoveryPeriodInDays", equalTo(10))
                .body(PITR + ".PointInTimeRecoveryWindowDays", equalTo(10));
        BigDecimal earliest = decimalAt(enabled, "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/EarliestRestorableDateTime");
        assertEquals(0, earliest.remainder(BigDecimal.ONE).signum(), "whole seconds: " + earliest);
        assertTrue(before / 1000 <= earliest.longValue() && earliest.longValue() <= System.currentTimeMillis() / 1000);
        BigDecimal latest = decimalAt(enabled, "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/LatestRestorableDateTime");
        assertTrue(earliest.compareTo(latest) <= 0, earliest + " <= " + latest);

        ValidatableResponse again = updateContinuousBackups(table, "true")
                .statusCode(200)
                .body(PITR + ".RecoveryPeriodInDays", equalTo(10));
        assertEquals(earliest, decimalAt(again,
                "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/EarliestRestorableDateTime"));

        updateContinuousBackups(table, "false")
                .statusCode(200)
                .body(PITR + ".keySet()", contains("PointInTimeRecoveryStatus"))
                .body(PITR + ".PointInTimeRecoveryStatus", equalTo("DISABLED"));
        updateContinuousBackups(table, "true")
                .statusCode(200)
                .body(PITR + ".RecoveryPeriodInDays", equalTo(35))
                .body(PITR + ".PointInTimeRecoveryWindowDays", equalTo(35));
    }

    @Test
    void updateContinuousBackupsChecksInTheAwsOrder() {
        String table = "PitrIT-update-checks";
        createTable(table);
        String spec = "\"PointInTimeRecoverySpecification\": ";
        expectError(ddb("UpdateContinuousBackups", "{" + spec + "{\"PointInTimeRecoveryEnabled\": true}}"),
                "ValidationException", "The parameter 'TableName' is required but was not present in the request");
        expectError(ddb("UpdateContinuousBackups", "{\"TableName\": \"a b\"}"), "ValidationException",
                "1 validation error detected: Value 'a b' at 'tableName' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+");
        expectError(ddb("UpdateContinuousBackups", "{\"TableName\": \"" + table + "\"}"), "ValidationException",
                "1 validation error detected: Value null at 'pointInTimeRecoverySpecification' failed to satisfy "
                + "constraint: Member must not be null");
        expectError(ddb("UpdateContinuousBackups", "{\"TableName\": \"" + table + "\", " + spec
                + "{\"RecoveryPeriodInDays\": 0}}"), "ValidationException",
                "2 validation errors detected: Value '0' at 'pointInTimeRecoverySpecification.recoveryPeriodInDays' "
                + "failed to satisfy constraint: Member must have value greater than or equal to 1; Value null at "
                + "'pointInTimeRecoverySpecification.pointInTimeRecoveryEnabled' failed to satisfy constraint: "
                + "Member must not be null");
        expectError(updateContinuousBackups(table, "true, \"RecoveryPeriodInDays\": 36"), "ValidationException",
                "1 validation error detected: Value '36' at 'pointInTimeRecoverySpecification.recoveryPeriodInDays' "
                + "failed to satisfy constraint: Member must have value less than or equal to 35");
        expectError(updateContinuousBackups("PitrIT-missing", "false, \"RecoveryPeriodInDays\": 7"),
                "ValidationException",
                "Invalid Request: Cannot specify RecoveryPeriodInDays when disabling point-in-time recovery.");
        expectError(updateContinuousBackups("PitrIT-missing", "true"), "TableNotFoundException",
                "Table not found: PitrIT-missing");
        expectError(ddb("DescribeContinuousBackups", "{}"), "ValidationException",
                "The parameter 'TableName' is required but was not present in the request");
        expectError(ddb("DescribeContinuousBackups", "{\"TableName\": \"ab\"}"), "ValidationException",
                "1 validation error detected: Value 'ab' at 'tableName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 3");
        expectError(ddb("DescribeContinuousBackups", "{\"TableName\": \"PitrIT-missing\"}"), "TableNotFoundException",
                "Table not found: PitrIT-missing");
    }

    @Test
    void restoreTableToPointInTimeRestoresTheItemsOfThatTime() {
        String table = "PitrIT-items";
        createTable(table);
        updateContinuousBackups(table, "true").statusCode(200);
        putItem(table, "k1", "v1");
        BigDecimal t1 = pause();
        ddb("UpdateItem", """
                {"TableName": "%s", "Key": {"pk": {"S": "k1"}}, "UpdateExpression": "SET v = :v",
                 "ExpressionAttributeValues": {":v": {"S": "v2"}}}
                """.formatted(table)).statusCode(200);
        ddb("BatchWriteItem", """
                {"RequestItems": {"%s": [{"PutRequest": {"Item": {"pk": {"S": "k2"}, "v": {"S": "v1"}}}}]}}
                """.formatted(table)).statusCode(200);
        BigDecimal t2 = pause();
        ddb("TransactWriteItems", """
                {"TransactItems": [
                    {"Delete": {"TableName": "%1$s", "Key": {"pk": {"S": "k1"}}}},
                    {"Put": {"TableName": "%1$s", "Item": {"pk": {"S": "k3"}, "v": {"S": "v1"}}}}
                ]}
                """.formatted(table)).statusCode(200);
        ddb("ExecuteStatement", "{\"Statement\": \"UPDATE \\\"" + table + "\\\" SET v = 'v2' WHERE pk = 'k3'\"}")
                .statusCode(200);

        restoreToTime(table, "PitrIT-items-t1", t1)
                .statusCode(200)
                .body("TableDescription.TableName", equalTo("PitrIT-items-t1"))
                .body("TableDescription.TableStatus", equalTo("CREATING"))
                .body("TableDescription.RestoreSummary.SourceTableArn", equalTo(TABLE_ARN_PREFIX + table))
                .body("TableDescription.RestoreSummary.RestoreInProgress", equalTo(true))
                .body("TableDescription.RestoreSummary", not(hasKey("SourceBackupArn")));
        assertEquals(List.of("k1=v1"), items("PitrIT-items-t1"));
        restoreToTime(table, "PitrIT-items-t2", t2).statusCode(200);
        assertEquals(List.of("k1=v2", "k2=v1"), items("PitrIT-items-t2"));
        ddb("RestoreTableToPointInTime", """
                {"SourceTableArn": "%s", "TargetTableName": "PitrIT-items-latest", "UseLatestRestorableTime": true}
                """.formatted(TABLE_ARN_PREFIX + table)).statusCode(200);
        assertEquals(List.of("k2=v1", "k3=v2"), items("PitrIT-items-latest"));

        ddb("DescribeTable", "{\"TableName\": \"PitrIT-items-t1\"}")
                .statusCode(200)
                .body("Table.TableStatus", equalTo("ACTIVE"))
                .body("Table", not(hasKey("RestoreSummary")));
        ddb("DescribeContinuousBackups", "{\"TableName\": \"PitrIT-items-t1\"}")
                .statusCode(200)
                .body(PITR + ".PointInTimeRecoveryStatus", equalTo("DISABLED"));
    }

    @Test
    void restoreSummaryNamesTheRequestedTime() throws Exception {
        String table = "PitrIT-summary";
        createTable(table);
        updateContinuousBackups(table, "true").statusCode(200);
        BigDecimal t1 = pause();

        ValidatableResponse restored = restoreToTime(table, "PitrIT-summary-t1", t1).statusCode(200);
        assertEquals(0, t1.compareTo(decimalAt(restored, "/TableDescription/RestoreSummary/RestoreDateTime")));
    }

    @Test
    void restoreRefusesATimeOutsideTheWindow() throws Exception {
        String table = "PitrIT-window-checks";
        createTable(table);
        BigDecimal earliest = decimalAt(updateContinuousBackups(table, "true").statusCode(200),
                "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/EarliestRestorableDateTime");

        String message = restoreToTime(table, "PitrIT-never", earliest.subtract(BigDecimal.ONE))
                .statusCode(400)
                .body("__type", containsString("InvalidRestoreTimeException"))
                .extract().path("message");
        String earliestText = Instant.ofEpochSecond(earliest.longValue()).toString();
        String requested = Instant.ofEpochSecond(earliest.longValue() - 1).toString();
        assertTrue(message.matches("RestoreDateTime '" + requested + "' must be within EarliestRestorableDateTime '"
                + earliestText + "' and LatestRestorableDateTime '[0-9T:.\\-]+Z' for table '" + table + "'"), message);
        restoreToTime(table, "PitrIT-never", earliest.add(BigDecimal.valueOf(Duration.ofHours(1).toSeconds())))
                .statusCode(400)
                .body("__type", containsString("InvalidRestoreTimeException"));
        restoreToTime(table, "PitrIT-window-checks-earliest", earliest).statusCode(200);
    }

    @Test
    void restoreDateTimeIsRoundedToTheMillisecondAsADouble() {
        String table = "PitrIT-rounding";
        createTable(table);
        updateContinuousBackups(table, "true").statusCode(200);
        String nowPlusTwoToThe64Millis = BigDecimal.valueOf(System.currentTimeMillis(), 3)
                .add(new BigDecimal(BigInteger.TWO.pow(64), 3)).toPlainString();

        assertEquals("+292278994-08-17T07:12:55.807Z", refusedRestoreTime(table, nowPlusTwoToThe64Millis));
        assertEquals("+292278994-08-17T07:12:55.807Z", refusedRestoreTime(table, "1e400"));
        assertEquals("-292275055-05-16T16:47:04.192Z", refusedRestoreTime(table, "-1e20"));
        assertEquals("1970-01-01T00:00:00.001Z", refusedRestoreTime(table, "0.0005"));
        assertEquals("1970-01-01T00:00:00Z", refusedRestoreTime(table, "-0.0005"));
        assertEquals("2026-10-22T00:42:24.001Z", refusedRestoreTime(table, "1792629744.0004999"));
    }

    @Test
    void restoreTableToPointInTimeChecksInTheAwsOrder() {
        String table = "PitrIT-checks";
        String off = "PitrIT-checks-off";
        createTable(table);
        createTable(off);
        updateContinuousBackups(table, "true").statusCode(200);
        String latest = "\"UseLatestRestorableTime\": true";

        expectError(restore("\"SourceTableName\": \"" + table + "\", " + latest), "ValidationException",
                "The parameter 'TableName' is required but was not present in the request");
        expectError(restore("\"TargetTableName\": \"c d\", " + latest), "ValidationException",
                "The parameter 'TableName' is required but was not present in the request");
        expectError(restore("\"SourceTableName\": \"ab\", \"TargetTableName\": \"c d\", " + latest),
                "ValidationException", "TableName must be at least 3 characters long and at most 255 characters long");
        expectError(restore("""
                "SourceTableName": "a b", "TargetTableName": "c d", "BillingModeOverride": "BAD",
                "ProvisionedThroughputOverride": {},
                "GlobalSecondaryIndexOverride": [{"IndexName": "a", "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                    "Projection": {"ProjectionType": "ALL"}, "ProvisionedThroughput": {"ReadCapacityUnits": 0}}],
                """ + latest), "ValidationException",
                "8 validation errors detected: "
                + "Value 'a b' at 'sourceTableName' failed to satisfy constraint: Member must satisfy regular "
                + "expression pattern: [a-zA-Z0-9_.-]+; "
                + "Value null at 'provisionedThroughputOverride.writeCapacityUnits' failed to satisfy constraint: "
                + "Member must not be null; "
                + "Value null at 'provisionedThroughputOverride.readCapacityUnits' failed to satisfy constraint: "
                + "Member must not be null; "
                + "Value 'BAD' at 'billingModeOverride' failed to satisfy constraint: Member must satisfy enum value "
                + "set: [PROVISIONED, PAY_PER_REQUEST]; "
                + "Value 'c d' at 'targetTableName' failed to satisfy constraint: Member must satisfy regular "
                + "expression pattern: [a-zA-Z0-9_.-]+; "
                + "Value 'a' at 'globalSecondaryIndexOverride.1.member.indexName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 3; "
                + "Value null at 'globalSecondaryIndexOverride.1.member.provisionedThroughput.writeCapacityUnits' "
                + "failed to satisfy constraint: Member must not be null; "
                + "Value '0' at 'globalSecondaryIndexOverride.1.member.provisionedThroughput.readCapacityUnits' "
                + "failed to satisfy constraint: Member must have value greater than or equal to 1");
        String both = "\"SourceTableName\": \"%s\", \"SourceTableArn\": \"%s\", ".formatted(table, TABLE_ARN_PREFIX + table);
        expectError(restore(both + "\"TargetTableName\": \"c d\", " + latest), "ValidationException",
                "1 validation error detected: Value 'c d' at 'targetTableName' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+");
        expectError(restore(both + "\"TargetTableName\": \"PitrIT-never\", " + latest), "ValidationException",
                "Invalid Request: Must provide exactly one of: sourceTableArn, sourceTableName");
        expectError(restore("\"SourceTableName\": \"PitrIT-missing\", \"TargetTableName\": \"PitrIT-never\", "
                + latest + ", \"RestoreDateTime\": 1"), "ValidationException",
                "Invalid Request: Both RestoreDateTime and UseLatestRestorableTime cannot be set for point-in-time "
                + "restore request. Please specify either RestoreDateTime time or UseLatestRestorableTime");
        expectError(restore("\"SourceTableName\": \"PitrIT-missing\", \"TargetTableName\": \"PitrIT-never\""),
                "TableNotFoundException", "Table not found: PitrIT-missing");
        expectError(restore("\"SourceTableName\": \"" + off + "\", \"TargetTableName\": \"PitrIT-never\", "
                + "\"UseLatestRestorableTime\": false"), "ValidationException",
                "Invalid Request: Either one of RestoreDateTime or UseLatestRestorableTime must be specified, but not both");
        expectError(restore("\"SourceTableName\": \"" + off + "\", \"TargetTableName\": \"PitrIT-never\", "
                + "\"BillingModeOverride\": \"PROVISIONED\", " + latest), "PointInTimeRecoveryUnavailableException",
                "Point in time recovery is not enabled for table '" + off + "'");
        expectError(restore("\"SourceTableName\": \"" + table + "\", \"TargetTableName\": \"" + off + "\", "
                + "\"BillingModeOverride\": \"PROVISIONED\", " + latest), "ValidationException",
                "Invalid Request: Must override ProvisionedThroughput if BillingMode is overridden to PROVISIONED");
        expectError(restore("\"SourceTableName\": \"" + table + "\", \"TargetTableName\": \"" + table + "\", " + latest),
                "TableAlreadyExistsException", "Table already exists: " + table);
    }

    @Test
    void sourceTableArnIsCheckedBeforeTheValidationList() {
        String table = "PitrIT-arn";
        createTable(table);
        updateContinuousBackups(table, "true").statusCode(200);
        String rest = ", \"TargetTableName\": \"c d\", \"UseLatestRestorableTime\": true";

        expectError(restore("\"SourceTableArn\": \"abc\"" + rest), "ValidationException",
                "One or more parameter values were invalid: ARNs must start with 'arn:': abc");
        expectError(restore("\"SourceTableArn\": \"arn:aws:s3:::bucket\"" + rest), "ValidationException",
                "One or more parameter values were invalid: Invalid resource Arn: arn:aws:s3:::bucket");
        String emptyPartition = "arn::dynamodb:us-east-1:000000000000:table/" + table;
        expectError(restore("\"SourceTableArn\": \"" + emptyPartition + "\"" + rest), "ValidationException",
                "One or more parameter values were invalid: Partition must be non-empty: " + emptyPartition);
        String otherPartition = "arn:aws-cn:dynamodb:cn-north-1:000000000000:table/" + table;
        expectError(restore("\"SourceTableArn\": \"" + otherPartition + "\"" + rest), "ValidationException",
                "One or more parameter values were invalid: Invalid resource Arn: " + otherPartition);
        expectError(restore("\"SourceTableArn\": \"arn:aws:dynamodb:us-east-1:000000000000:foo/bar\"" + rest),
                "ValidationException", "One or more parameter values were invalid: sourceTableArn is not a valid ARN");
        expectError(restore("\"SourceTableArn\": \"" + TABLE_ARN_PREFIX + "ab\"" + rest), "ValidationException",
                "Invalid Request: Invalid TableArn: Invalid Table ARN");
        expectError(restore("\"SourceTableArn\": \"arn:aws:dynamodb:us-east-1:123456789012:table/" + table + "\""
                + rest), "ValidationException",
                "This action is only supported by accounts that match the resource owner’s account.");
        expectError(restore("\"SourceTableArn\": \"arn:aws:dynamodb:eu-west-2:000000000000:table/" + table + "\""
                + rest), "ValidationException",
                "Invalid Request: sseSpecificationOverride must be provided for cross-region restores");
        expectError(restore("\"SourceTableArn\": \"" + TABLE_ARN_PREFIX + "PitrIT-missing\", "
                + "\"TargetTableName\": \"PitrIT-never\", \"UseLatestRestorableTime\": true"),
                "TableNotFoundException", "Table not found: PitrIT-missing");
        expectError(restore("\"SourceTableArn\": \"arn:aws:dynamodb:eu-west-2:000000000000:table/PitrIT-missing\", "
                + "\"TargetTableName\": \"PitrIT-never\", \"UseLatestRestorableTime\": true, "
                + "\"SSESpecificationOverride\": {\"Enabled\": true, \"SSEType\": \"KMS\"}"),
                "TableNotFoundException", "Table not found");
        expectError(restore("\"SourceTableArn\": \"arn:aws:dynamodb:cn-north-1:000000000000:table/PitrIT-missing\", "
                + "\"TargetTableName\": \"PitrIT-never\", \"UseLatestRestorableTime\": true, "
                + "\"SSESpecificationOverride\": {\"Enabled\": true}"),
                "ValidationException", "Invalid Request: Table ARN is invalid.");
    }

    @Test
    void restoreAcrossRegionsNeedsAnSseOverride() {
        String table = "PitrIT-cross-region";
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .header("Authorization", OTHER_REGION_AUTH)
                .contentType(CONTENT_TYPE)
                .body(createTableBody(table))
                .when().post("/")
                .then().statusCode(200);
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.UpdateContinuousBackups")
                .header("Authorization", OTHER_REGION_AUTH)
                .contentType(CONTENT_TYPE)
                .body("{\"TableName\": \"" + table + "\", \"PointInTimeRecoverySpecification\": "
                        + "{\"PointInTimeRecoveryEnabled\": true}}")
                .when().post("/")
                .then().statusCode(200);
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .header("Authorization", OTHER_REGION_AUTH)
                .contentType(CONTENT_TYPE)
                .body("{\"TableName\": \"" + table + "\", \"Item\": {\"pk\": {\"S\": \"k1\"}, \"v\": {\"S\": \"v1\"}}}")
                .when().post("/")
                .then().statusCode(200);

        String sourceArn = "arn:aws:dynamodb:eu-west-2:000000000000:table/" + table;
        restore("""
                "SourceTableArn": "%s", "TargetTableName": "PitrIT-cross-region-copy", "UseLatestRestorableTime": true,
                "SSESpecificationOverride": {"Enabled": true, "SSEType": "KMS"}
                """.formatted(sourceArn))
                .statusCode(200)
                .body("TableDescription.TableArn", equalTo(TABLE_ARN_PREFIX + "PitrIT-cross-region-copy"))
                .body("TableDescription.RestoreSummary.SourceTableArn", equalTo(sourceArn))
                .body("TableDescription.SSEDescription.SSEType", equalTo("KMS"));
        assertEquals(List.of("k1=v1"), items("PitrIT-cross-region-copy"));
    }

    @Test
    void deletingATableWithPointInTimeRecoveryLeavesASystemBackup() throws Exception {
        String table = "PitrIT-deleted";
        createTable(table);
        updateContinuousBackups(table, "true, \"RecoveryPeriodInDays\": 7").statusCode(200);
        putItem(table, "k1", "v1");
        ddb("DeleteTable", "{\"TableName\": \"" + table + "\"}").statusCode(200);

        ddb("ListBackups", "{\"TableName\": \"" + table + "\"}")
                .statusCode(200)
                .body("BackupSummaries", empty());
        ValidatableResponse listed = ddb("ListBackups", "{\"TableName\": \"" + table + "\", \"BackupType\": \"SYSTEM\"}")
                .statusCode(200)
                .body("BackupSummaries", hasSize(1))
                .body("BackupSummaries[0].BackupName", equalTo(table + "$DeletedTableBackup"))
                .body("BackupSummaries[0].BackupType", equalTo("SYSTEM"))
                .body("BackupSummaries[0].BackupStatus", equalTo("AVAILABLE"))
                .body("BackupSummaries[0].TableArn", equalTo(TABLE_ARN_PREFIX + table));
        String backupArn = listed.extract().path("BackupSummaries[0].BackupArn");
        assertTrue(backupArn.matches(TABLE_ARN_PREFIX + table + "/backup/\\d{14}-00000000"), backupArn);
        BigDecimal created = decimalAt(listed, "/BackupSummaries/0/BackupCreationDateTime");
        BigDecimal expires = decimalAt(listed, "/BackupSummaries/0/BackupExpiryDateTime");
        assertEquals(Duration.ofDays(35).toMillis(), expires.subtract(created).movePointRight(3).longValueExact());
        ddb("ListBackups", "{\"TableName\": \"" + table + "\", \"BackupType\": \"ALL\"}")
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(backupArn));

        ddb("DescribeBackup", "{\"BackupArn\": \"" + backupArn + "\"}")
                .statusCode(200)
                .body("BackupDescription.BackupDetails.BackupType", equalTo("SYSTEM"))
                .body("BackupDescription.SourceTableDetails.ItemCount", equalTo(1));
        String expiry = Instant.ofEpochMilli(expires.movePointRight(3).longValue()).toString();
        expectError(ddb("DeleteBackup", "{\"BackupArn\": \"" + backupArn + "\"}"), "ValidationException",
                "Invalid Request: User is not allowed to delete the system backup with arn " + backupArn
                + ". It will automatically expire on " + expiry);

        ddb("RestoreTableFromBackup", """
                {"BackupArn": "%s", "TargetTableName": "PitrIT-deleted-back"}
                """.formatted(backupArn)).statusCode(200);
        assertEquals(List.of("k1=v1"), items("PitrIT-deleted-back"));
        expectError(ddb("DescribeContinuousBackups", "{\"TableName\": \"" + table + "\"}"), "TableNotFoundException",
                "Table not found: " + table);
    }

    @Test
    void deletingATableWithoutPointInTimeRecoveryLeavesNoBackup() {
        String table = "PitrIT-deleted-plain";
        createTable(table);
        ddb("DeleteTable", "{\"TableName\": \"" + table + "\"}").statusCode(200);

        ddb("ListBackups", "{\"TableName\": \"" + table + "\", \"BackupType\": \"ALL\"}")
                .statusCode(200)
                .body("BackupSummaries", empty());
    }

    /** Returns a time between the writes before and after the call, in epoch seconds with milliseconds. */
    private static BigDecimal pause() {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() <= now) {
            Thread.onSpinWait();
        }
        return BigDecimal.valueOf(now, 3);
    }

    private void createTable(String table) {
        ddb("CreateTable", createTableBody(table)).statusCode(200);
    }

    private static String createTableBody(String table) {
        return """
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(table);
    }

    private void putItem(String table, String pk, String value) {
        ddb("PutItem", """
                {"TableName": "%s", "Item": {"pk": {"S": "%s"}, "v": {"S": "%s"}}}
                """.formatted(table, pk, value)).statusCode(200);
    }

    private List<String> items(String table) {
        List<String> pks = ddb("Scan", "{\"TableName\": \"" + table + "\"}")
                .statusCode(200)
                .extract().path("Items.collect { it.pk.S + '=' + it.v.S }");
        return pks.stream().sorted().toList();
    }

    private ValidatableResponse updateContinuousBackups(String table, String spec) {
        return ddb("UpdateContinuousBackups", """
                {"TableName": "%s", "PointInTimeRecoverySpecification": {"PointInTimeRecoveryEnabled": %s}}
                """.formatted(table, spec));
    }

    private ValidatableResponse restoreToTime(String source, String target, BigDecimal time) {
        return restore("""
                "SourceTableName": "%s", "TargetTableName": "%s", "RestoreDateTime": %s
                """.formatted(source, target, time.toPlainString()));
    }

    private ValidatableResponse restore(String members) {
        return ddb("RestoreTableToPointInTime", "{" + members + "}");
    }

    private String refusedRestoreTime(String source, String restoreDateTime) {
        String message = restore("""
                "SourceTableName": "%s", "TargetTableName": "PitrIT-never", "RestoreDateTime": %s
                """.formatted(source, restoreDateTime))
                .statusCode(400)
                .body("__type", containsString("InvalidRestoreTimeException"))
                .extract().path("message");
        return message.substring("RestoreDateTime '".length(), message.indexOf("' must be within"));
    }

    private static void expectError(ValidatableResponse response, String type, String message) {
        response.statusCode(400)
                .body("__type", containsString(type))
                .body("message", equalTo(message));
    }
}
