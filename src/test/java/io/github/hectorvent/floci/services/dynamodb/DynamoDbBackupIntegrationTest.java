package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.CONTENT_TYPE;
import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static io.github.hectorvent.floci.testing.DynamoDbRequests.decimalAt;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbBackupIntegrationTest {

    private static final String TABLE_ARN_PREFIX = "arn:aws:dynamodb:us-east-1:000000000000:table/";
    private static final String BACKUP_ARN_PATTERN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/%s/backup/\\d{14}-[0-9a-f]{8}";
    private static final String OTHER_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260215/eu-west-2/dynamodb/aws4_request, "
            + "SignedHeaders=host, Signature=abc";
    private static final String OTHER_PARTITION_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260215/cn-north-1/dynamodb/aws4_request, "
            + "SignedHeaders=host, Signature=abc";
    private static final String OTHER_ACCOUNT_AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000001/20260215/us-east-1/dynamodb/aws4_request, "
            + "SignedHeaders=host, Signature=abc";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createBackupAnswersCreatingAndDescribeBackupAnswersAvailable() {
        String table = "BackupIT-describe";
        createIndexedTable(table);
        putItem(table, "a", "1");
        ddb("UpdateTimeToLive", """
                {"TableName": "%s", "TimeToLiveSpecification": {"Enabled": true, "AttributeName": "ttl"}}
                """.formatted(table)).statusCode(200);

        String backupArn = ddb("CreateBackup", """
                {"TableName": "%s", "BackupName": "describe_me"}
                """.formatted(table))
                .statusCode(200)
                .body("BackupDetails.BackupName", equalTo("describe_me"))
                .body("BackupDetails.BackupStatus", equalTo("CREATING"))
                .body("BackupDetails.BackupType", equalTo("USER"))
                .body("BackupDetails.BackupSizeBytes", equalTo(0))
                .body("BackupDetails.BackupCreationDateTime", notNullValue())
                .body("BackupDetails", not(hasKey("BackupExpiryDateTime")))
                .extract().path("BackupDetails.BackupArn");
        assertTrue(backupArn.matches(BACKUP_ARN_PATTERN.formatted(table)), backupArn);

        ddb("DescribeBackup", "{\"BackupArn\": \"" + backupArn + "\"}")
                .statusCode(200)
                .body("BackupDescription.BackupDetails.BackupArn", equalTo(backupArn))
                .body("BackupDescription.BackupDetails.BackupStatus", equalTo("AVAILABLE"))
                .body("BackupDescription.SourceTableDetails.TableName", equalTo(table))
                .body("BackupDescription.SourceTableDetails.TableArn", equalTo(TABLE_ARN_PREFIX + table))
                .body("BackupDescription.SourceTableDetails.TableId", notNullValue())
                .body("BackupDescription.SourceTableDetails.TableCreationDateTime", notNullValue())
                .body("BackupDescription.SourceTableDetails.ItemCount", equalTo(1))
                .body("BackupDescription.SourceTableDetails.BillingMode", equalTo("PAY_PER_REQUEST"))
                .body("BackupDescription.SourceTableDetails.KeySchema.AttributeName", contains("pk", "sk"))
                .body("BackupDescription.SourceTableDetails.ProvisionedThroughput.ReadCapacityUnits", equalTo(0))
                .body("BackupDescription.SourceTableDetails.ProvisionedThroughput",
                        not(hasKey("NumberOfDecreasesToday")))
                .body("BackupDescription.SourceTableFeatureDetails.GlobalSecondaryIndexes[0].IndexName",
                        equalTo("gsi1"))
                .body("BackupDescription.SourceTableFeatureDetails.GlobalSecondaryIndexes[0]",
                        not(hasKey("IndexStatus")))
                .body("BackupDescription.SourceTableFeatureDetails.LocalSecondaryIndexes[0].IndexName",
                        equalTo("lsi1"))
                .body("BackupDescription.SourceTableFeatureDetails.LocalSecondaryIndexes[0]",
                        not(hasKey("IndexArn")))
                .body("BackupDescription.SourceTableFeatureDetails.StreamDescription.StreamEnabled", equalTo(true))
                .body("BackupDescription.SourceTableFeatureDetails.StreamDescription.StreamViewType",
                        equalTo("NEW_AND_OLD_IMAGES"))
                .body("BackupDescription.SourceTableFeatureDetails.TimeToLiveDescription.AttributeName",
                        equalTo("ttl"))
                .body("BackupDescription.SourceTableFeatureDetails.TimeToLiveDescription.TimeToLiveStatus",
                        equalTo("ENABLED"))
                .body("BackupDescription.SourceTableFeatureDetails.SSEDescription.SSEType", equalTo("KMS"));
    }

    @Test
    void plainTableBackupHasNoFeatureDetails() {
        String table = "BackupIT-plain";
        createPlainTable(table);
        String backupArn = createBackup(table, "plain");

        ddb("DescribeBackup", "{\"BackupArn\": \"" + backupArn + "\"}")
                .statusCode(200)
                .body("BackupDescription.SourceTableDetails.BillingMode", equalTo("PROVISIONED"))
                .body("BackupDescription.SourceTableDetails.ProvisionedThroughput.ReadCapacityUnits", equalTo(1))
                .body("BackupDescription.SourceTableFeatureDetails.size()", equalTo(0));
    }

    @Test
    void createBackupOfMissingTableIsTableNotFound() {
        ddb("CreateBackup", "{\"TableName\": \"BackupIT-missing\", \"BackupName\": \"abc\"}")
                .statusCode(400)
                .body("__type", containsString("TableNotFoundException"))
                .body("message", equalTo("Table not found: BackupIT-missing"));
    }

    @Test
    void createBackupValidatesItsMembers() {
        ddb("CreateBackup", "{\"BackupName\": \"abc\"}")
                .statusCode(400)
                .body("message", equalTo("The parameter 'TableName' is required but was not present in the request"));
        ddb("CreateBackup", "{\"TableName\": \"BackupIT-missing\"}")
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value null at 'backupName' failed to "
                        + "satisfy constraint: Member must not be null"));
        ddb("CreateBackup", "{\"TableName\": \"BackupIT-missing\", \"BackupName\": \"ab\"}")
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value 'ab' at 'backupName' failed to "
                        + "satisfy constraint: Member must have length greater than or equal to 3"));
        ddb("CreateBackup", "{\"TableName\": \"BackupIT-missing\", \"BackupName\": \"a b\"}")
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value 'a b' at 'backupName' failed to "
                        + "satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+"));
        ddb("CreateBackup", "{\"TableName\": \"a b\", \"BackupName\": \"c d\"}")
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value 'a b' at 'tableName' failed to "
                        + "satisfy constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+"));
    }

    @Test
    void backupArnsAreCheckedInTwoSteps() {
        String base = TABLE_ARN_PREFIX + "BackupIT-none/backup/";
        for (String arn : List.of("x".repeat(40), base, base + "xy", base + "01700000000000-abcdef12/extra")) {
            ddb("DescribeBackup", "{\"BackupArn\": \"" + arn + "\"}")
                    .statusCode(400)
                    .body("__type", containsString("ValidationException"))
                    .body("message", equalTo("Invalid Backup ARN"));
        }
        for (String arn : List.of(base + "xyz", base + "1700000000000-abcdef12", base + "01700000000000-abcdefgh",
                "arn:aws:s3:us-east-1:000000000000:table/BackupIT-none/backup/01700000000000-abcdef12",
                "arn:aws:dynamodb:nowhere-1:000000000000:table/BackupIT-none/backup/01700000000000-abcdef12")) {
            ddb("DescribeBackup", "{\"BackupArn\": \"" + arn + "\"}")
                    .statusCode(400)
                    .body("message", equalTo("Invalid Request: BackupArn is not valid"));
        }
        ddb("DeleteBackup", "{}")
                .statusCode(400)
                .body("message", equalTo("Invalid Backup ARN"));
    }

    @Test
    void backupArnTableNameAndPartitionAreChecked() {
        String backup = "/backup/01700000000000-abcdef12";
        for (String table : List.of("tt", "t".repeat(256))) {
            ddb("DescribeBackup", "{\"BackupArn\": \"" + TABLE_ARN_PREFIX + table + backup + "\"}")
                    .statusCode(400)
                    .body("message", equalTo("Invalid Backup ARN"));
        }
        for (String arn : List.of("arn:aws-cn:dynamodb:us-east-1:000000000000:table/BackupIT-none" + backup,
                "arn:aws:dynamodb:cn-north-1:000000000000:table/BackupIT-none" + backup)) {
            ddb("DescribeBackup", "{\"BackupArn\": \"" + arn + "\"}")
                    .statusCode(400)
                    .body("message", equalTo("Invalid Request: BackupArn is not valid"));
        }
        for (String arn : List.of(TABLE_ARN_PREFIX + "ttt" + backup, TABLE_ARN_PREFIX + "t".repeat(255) + backup,
                "arn:aws-cn:dynamodb:cn-north-1:000000000000:table/BackupIT-none" + backup)) {
            ddb("DescribeBackup", "{\"BackupArn\": \"" + arn + "\"}")
                    .statusCode(400)
                    .body("__type", containsString("BackupNotFoundException"));
        }
    }

    @Test
    void backupArnOfAnotherAccountIsDeniedOnceItSplitsIntoFields() {
        String id = "01700000000000-abcdef12";
        String other = "arn:aws:dynamodb:us-east-1:123456789012:";
        for (String arn : List.of(other + "table/BackupIT-none/backup/xy", other + "table/BackupIT-none",
                "arn:aws:s3:nowhere-1:123456789012:table/BackupIT-none/backup/1700000000000-abcdef12",
                "arn:aws:dynamodb:us-east-1::table/BackupIT-none/backup/" + id)) {
            for (String action : List.of("DescribeBackup", "DeleteBackup")) {
                ddb(action, "{\"BackupArn\": \"" + arn + "\"}")
                        .statusCode(400)
                        .body("__type", containsString("AccessDeniedException"))
                        .body("message", equalTo("Access is denied"));
            }
            ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + arn + "\", \"TargetTableName\": \"a b\"}")
                    .statusCode(400)
                    .body("__type", containsString("AccessDeniedException"));
        }
        for (String arn : List.of("arn:aws:dynamodb:us-east-1:123456789012", other,
                "arn::dynamodb:us-east-1:123456789012:table/BackupIT-none/backup/" + id,
                "arn:aws::us-east-1:123456789012:table/BackupIT-none/backup/" + id,
                "ARN:aws:dynamodb:us-east-1:123456789012:table/BackupIT-none/backup/" + id)) {
            ddb("DescribeBackup", "{\"BackupArn\": \"" + arn + "\"}")
                    .statusCode(400)
                    .body("message", equalTo("Invalid Backup ARN"));
        }
    }

    @Test
    void unknownBackupIsBackupNotFound() {
        String arn = TABLE_ARN_PREFIX + "BackupIT-none/backup/01700000000000-ABCDEF12";
        ddb("DescribeBackup", "{\"BackupArn\": \"" + arn + "\"}")
                .statusCode(400)
                .body("__type", containsString("BackupNotFoundException"))
                .body("message", equalTo("Backup not found: " + arn));
        ddb("DeleteBackup", "{\"BackupArn\": \"" + arn + "\"}")
                .statusCode(400)
                .body("__type", containsString("BackupNotFoundException"));
        ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + arn + "\", \"TargetTableName\": \"BackupIT-describe\"}")
                .statusCode(400)
                .body("__type", containsString("BackupNotFoundException"));
    }

    @Test
    void backupIsScopedToItsRegionAndAccount() {
        String table = "BackupIT-scope";
        createPlainTable(table);
        String backupArn = createBackup(table, "scoped");

        given().header("Authorization", OTHER_REGION_AUTH)
                .header("X-Amz-Target", "DynamoDB_20120810.DescribeBackup")
                .contentType(CONTENT_TYPE)
                .body("{\"BackupArn\": \"" + backupArn + "\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("BackupNotFoundException"));
        given().header("Authorization", OTHER_REGION_AUTH)
                .header("X-Amz-Target", "DynamoDB_20120810.ListBackups")
                .contentType(CONTENT_TYPE)
                .body("{\"TableName\": \"" + table + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("BackupSummaries", empty());
        given().header("Authorization", OTHER_ACCOUNT_AUTH)
                .header("X-Amz-Target", "DynamoDB_20120810.DescribeBackup")
                .contentType(CONTENT_TYPE)
                .body("{\"BackupArn\": \"" + backupArn + "\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("AccessDeniedException"))
                .body("message", equalTo("Access is denied"));
    }

    @Test
    void listBackupsPagesInCreationOrder() {
        String table = "BackupIT-list";
        createPlainTable(table);
        String first = createBackup(table, "same_name");
        String second = createBackup(table, "same_name");
        String third = createBackup(table, "third");

        ddb("ListBackups", "{\"TableName\": \"" + table + "\"}")
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(first, second, third))
                .body("BackupSummaries[0].BackupStatus", equalTo("AVAILABLE"))
                .body("BackupSummaries[0].TableName", equalTo(table))
                .body("BackupSummaries[0].TableArn", equalTo(TABLE_ARN_PREFIX + table))
                .body("BackupSummaries[0].TableId", notNullValue())
                .body("$", not(hasKey("LastEvaluatedBackupArn")));
        ddb("ListBackups", "{\"TableName\": \"" + TABLE_ARN_PREFIX + table + "\", \"Limit\": 2}")
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(first, second))
                .body("LastEvaluatedBackupArn", equalTo(second));
        ddb("ListBackups", """
                {"TableName": "%s", "Limit": 2, "ExclusiveStartBackupArn": "%s"}
                """.formatted(table, second))
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(third))
                .body("$", not(hasKey("LastEvaluatedBackupArn")));
        ddb("ListBackups", """
                {"TableName": "%s", "ExclusiveStartBackupArn": "%s"}
                """.formatted(table, TABLE_ARN_PREFIX + table + "/backup/01700000000000-abcdef12"))
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(first, second, third));
    }

    @Test
    void listBackupsFiltersByTableTypeAndTime() throws Exception {
        String table = "BackupIT-filter";
        createPlainTable(table);
        String backupArn = createBackup(table, "filtered");
        BigDecimal created = decimalAt(ddb("DescribeBackup", "{\"BackupArn\": \"" + backupArn + "\"}"),
                "/BackupDescription/BackupDetails/BackupCreationDateTime");

        ddb("ListBackups", "{\"TableName\": \"BackupIT-nosuch\"}")
                .statusCode(200)
                .body("BackupSummaries", empty());
        ddb("ListBackups", "{\"TableName\": \"" + table + "\", \"BackupType\": \"SYSTEM\"}")
                .statusCode(200)
                .body("BackupSummaries", empty());
        ddb("ListBackups", "{\"TableName\": \"" + table + "\", \"BackupType\": \"ALL\"}")
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(backupArn));
        ddb("ListBackups", """
                {"TableName": "%s", "TimeRangeLowerBound": %s, "TimeRangeUpperBound": %s}
                """.formatted(table, created, created.add(BigDecimal.ONE)))
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(backupArn));
        ddb("ListBackups", """
                {"TableName": "%s", "TimeRangeLowerBound": %s, "TimeRangeUpperBound": %s}
                """.formatted(table, created.subtract(BigDecimal.ONE), created))
                .statusCode(200)
                .body("BackupSummaries", empty());
        ddb("ListBackups", """
                {"TableName": "%s", "TimeRangeLowerBound": %s, "TimeRangeUpperBound": %s}
                """.formatted(table, created, created.subtract(BigDecimal.ONE)))
                .statusCode(400)
                .body("message", equalTo("One or more parameter values were invalid: "
                        + "Time range lower bound must be less than or equal to upper bound"));
    }

    @Test
    void listBackupsChecksTheTimeRangeLimits() {
        String before = "Invalid Request: The supplied %s must not be before 1970-01-01T00:00:00Z";
        String after = "Invalid Request: The supplied %s must not be after 5138-11-16T09:46:39.999Z";

        listBackupsBetween("-0.0005", "99999999999.9994").statusCode(200);
        listBackupsBetween("-0.001", null)
                .statusCode(400)
                .body("message", equalTo(before.formatted("TimeRangeLowerBound")));
        listBackupsBetween(null, "100000000000")
                .statusCode(400)
                .body("message", equalTo(after.formatted("TimeRangeUpperBound")));
        listBackupsBetween("-1e20", "1e20")
                .statusCode(400)
                .body("message", equalTo(before.formatted("TimeRangeLowerBound")));
        listBackupsBetween("0", "1e400")
                .statusCode(400)
                .body("message", equalTo(after.formatted("TimeRangeUpperBound")));
        listBackupsBetween("1e21", "1e20")
                .statusCode(400)
                .body("message", equalTo(after.formatted("TimeRangeLowerBound")));
        listBackupsBetween("100000000000", "-0.001")
                .statusCode(400)
                .body("message", equalTo("One or more parameter values were invalid: "
                        + "Time range lower bound must be less than or equal to upper bound"));
    }

    @Test
    void listBackupsChecksTheExclusiveStartBackupArn() {
        String table = "BackupIT-start";
        String backup = "/backup/01700000000000-abcdef12";
        String start = TABLE_ARN_PREFIX + table + backup;
        String supplied = "Invalid Request: The supplied ExclusiveStartBackupArn ";

        listBackupsFrom(table, TABLE_ARN_PREFIX + "tt" + backup)
                .statusCode(400)
                .body("message", equalTo("Invalid Backup ARN"));
        listBackupsFrom(table, TABLE_ARN_PREFIX + table + "/backup/1700000000000-abcdef12")
                .statusCode(400)
                .body("message", equalTo(supplied + "is not a valid backup arn"));
        listBackupsFrom(table, start.replace("000000000000", ""))
                .statusCode(400)
                .body("message", equalTo(supplied + "is not a valid backup arn"));
        listBackupsFrom(table, start.replace("us-east-1:000000000000", "eu-west-2:123456789012"))
                .statusCode(400)
                .body("message", equalTo(supplied + "does not match the region"));
        listBackupsFrom(table, start.replace("000000000000", "123456789012"))
                .statusCode(400)
                .body("message", equalTo(supplied + "does not match the account"));
        listBackupsFrom(table, TABLE_ARN_PREFIX + "BackupIT-other" + backup)
                .statusCode(400)
                .body("message", equalTo(supplied + "does not match the table name predicate"));
        ddb("ListBackups", "{\"ExclusiveStartBackupArn\": \"" + TABLE_ARN_PREFIX + "BackupIT-other" + backup + "\"}")
                .statusCode(200);

        String reversedRange = "\"TimeRangeLowerBound\": 2, \"TimeRangeUpperBound\": 1";
        ddb("ListBackups", "{\"ExclusiveStartBackupArn\": \"" + TABLE_ARN_PREFIX + "tt" + backup + "\", "
                + reversedRange + "}")
                .statusCode(400)
                .body("message", equalTo("Invalid Backup ARN"));
        ddb("ListBackups", "{\"ExclusiveStartBackupArn\": \"" + start.replace("000000000000", "123456789012")
                + "\", " + reversedRange + "}")
                .statusCode(400)
                .body("message", equalTo("One or more parameter values were invalid: "
                        + "Time range lower bound must be less than or equal to upper bound"));
    }

    @Test
    void listBackupsReportsEveryViolation() {
        ddb("ListBackups", "{\"Limit\": 0, \"BackupType\": \"BAD\"}")
                .statusCode(400)
                .body("message", equalTo("2 validation errors detected: Value '0' at 'limit' failed to satisfy "
                        + "constraint: Member must have value greater than or equal to 1; Value 'BAD' at "
                        + "'backupType' failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[ALL, AWS_BACKUP, USER, SYSTEM]"));
        ddb("ListBackups", "{\"Limit\": 101}")
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '101' at 'limit' failed to satisfy "
                        + "constraint: Member must have value less than or equal to 100"));
        ddb("ListBackups", "{\"ExclusiveStartBackupArn\": \"" + "x".repeat(40) + "\"}")
                .statusCode(400)
                .body("message", equalTo("Invalid Backup ARN"));
        String shortArn = "x".repeat(36);
        ddb("ListBackups", """
                {"Limit": 0, "ExclusiveStartBackupArn": "%s", "BackupType": "BAD"}
                """.formatted(shortArn))
                .statusCode(400)
                .body("message", equalTo("3 validation errors detected: Value '0' at 'limit' failed to satisfy "
                        + "constraint: Member must have value greater than or equal to 1; Value '" + shortArn
                        + "' at 'exclusiveStartBackupArn' failed to satisfy constraint: Member must have length "
                        + "greater than or equal to 37; Value 'BAD' at 'backupType' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [ALL, AWS_BACKUP, USER, SYSTEM]"));
        String longArn = "x".repeat(1025);
        ddb("ListBackups", "{\"ExclusiveStartBackupArn\": \"" + longArn + "\"}")
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '" + longArn + "' at "
                        + "'exclusiveStartBackupArn' failed to satisfy constraint: Member must have length "
                        + "less than or equal to 1024"));
    }

    @Test
    void deleteBackupAnswersDeletedAndTheBackupIsGone() {
        String table = "BackupIT-delete";
        createPlainTable(table);
        String backupArn = createBackup(table, "doomed");

        ddb("DeleteBackup", "{\"BackupArn\": \"" + backupArn + "\"}")
                .statusCode(200)
                .body("BackupDescription.BackupDetails.BackupArn", equalTo(backupArn))
                .body("BackupDescription.BackupDetails.BackupStatus", equalTo("DELETED"))
                .body("BackupDescription.SourceTableDetails.TableName", equalTo(table));
        ddb("DescribeBackup", "{\"BackupArn\": \"" + backupArn + "\"}")
                .statusCode(400)
                .body("__type", containsString("BackupNotFoundException"));
        ddb("ListBackups", "{\"TableName\": \"" + table + "\"}")
                .statusCode(200)
                .body("BackupSummaries", empty());
    }

    @Test
    void backupOutlivesItsTable() {
        String table = "BackupIT-outlive";
        createPlainTable(table);
        putItem(table, "kept");
        String backupArn = createBackup(table, "survivor");
        ddb("DeleteTable", "{\"TableName\": \"" + table + "\"}").statusCode(200);

        ddb("ListBackups", "{\"TableName\": \"" + table + "\"}")
                .statusCode(200)
                .body("BackupSummaries.BackupArn", contains(backupArn));
        ddb("DescribeBackup", "{\"BackupArn\": \"" + backupArn + "\"}")
                .statusCode(200)
                .body("BackupDescription.SourceTableDetails.TableName", equalTo(table));
        ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + backupArn + "\", \"TargetTableName\": \"" + table + "\"}")
                .statusCode(200);
        ddb("GetItem", "{\"TableName\": \"" + table + "\", \"Key\": {\"pk\": {\"S\": \"kept\"}}}")
                .statusCode(200)
                .body("Item.pk.S", equalTo("kept"));
    }

    @Test
    void restoreTableFromBackupCopiesTheBackedUpItemsAndSchema() throws Exception {
        String table = "BackupIT-restore-src";
        String target = "BackupIT-restore-dst";
        createIndexedTable(table);
        ddb("UpdateTimeToLive", """
                {"TableName": "%s", "TimeToLiveSpecification": {"Enabled": true, "AttributeName": "ttl"}}
                """.formatted(table)).statusCode(200);
        ddb("UpdateContinuousBackups", """
                {"TableName": "%s", "PointInTimeRecoverySpecification": {"PointInTimeRecoveryEnabled": true}}
                """.formatted(table)).statusCode(200);
        putItem(table, "a", "1");
        putItem(table, "a", "2");
        String backupArn = createBackup(table, "restore_me");
        putItem(table, "a", "after-backup");
        BigDecimal backupCreated = decimalAt(ddb("DescribeBackup", "{\"BackupArn\": \"" + backupArn + "\"}"),
                "/BackupDescription/BackupDetails/BackupCreationDateTime");

        ValidatableResponse restored = ddb("RestoreTableFromBackup",
                "{\"BackupArn\": \"" + backupArn + "\", \"TargetTableName\": \"" + target + "\"}");
        assertEquals(0, backupCreated.compareTo(decimalAt(restored, "/TableDescription/RestoreSummary/RestoreDateTime")));
        restored.statusCode(200)
                .body("TableDescription.TableName", equalTo(target))
                .body("TableDescription.TableArn", equalTo(TABLE_ARN_PREFIX + target))
                .body("TableDescription.TableStatus", equalTo("CREATING"))
                .body("TableDescription.RestoreSummary.SourceBackupArn", equalTo(backupArn))
                .body("TableDescription.RestoreSummary.SourceTableArn", equalTo(TABLE_ARN_PREFIX + table))
                .body("TableDescription.RestoreSummary.RestoreInProgress", equalTo(true))
                .body("TableDescription.GlobalSecondaryIndexes[0].IndexArn",
                        equalTo(TABLE_ARN_PREFIX + target + "/index/gsi1"))
                .body("TableDescription.LocalSecondaryIndexes[0].IndexName", equalTo("lsi1"))
                .body("TableDescription.SSEDescription.SSEType", equalTo("KMS"));

        ddb("DescribeTable", "{\"TableName\": \"" + target + "\"}")
                .statusCode(200)
                .body("Table.TableStatus", equalTo("ACTIVE"))
                .body("Table", not(hasKey("RestoreSummary")))
                .body("Table", not(hasKey("StreamSpecification")))
                .body("Table.DeletionProtectionEnabled", equalTo(false))
                .body("Table.TableClassSummary.TableClass", equalTo("STANDARD"))
                .body("Table.BillingModeSummary.BillingMode", equalTo("PAY_PER_REQUEST"))
                .body("Table.ItemCount", equalTo(2));
        ddb("DescribeTimeToLive", "{\"TableName\": \"" + target + "\"}")
                .statusCode(200)
                .body("TimeToLiveDescription.TimeToLiveStatus", equalTo("DISABLED"));
        ddb("DescribeContinuousBackups", "{\"TableName\": \"" + target + "\"}")
                .statusCode(200)
                .body("ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus",
                        equalTo("DISABLED"));
        ddb("ListTagsOfResource", "{\"ResourceArn\": \"" + TABLE_ARN_PREFIX + target + "\"}")
                .statusCode(200)
                .body("Tags", empty());
        ddb("Scan", "{\"TableName\": \"" + target + "\"}")
                .statusCode(200)
                .body("Items.sk.S", containsInAnyOrder("1", "2"));
        ddb("Query", """
                {"TableName": "%s", "IndexName": "gsi1", "KeyConditionExpression": "g = :g",
                 "ExpressionAttributeValues": {":g": {"S": "g-1"}}}
                """.formatted(target))
                .statusCode(200)
                .body("Count", equalTo(1));
    }

    @Test
    void restoreTableFromBackupRefusesAnExistingTarget() {
        String table = "BackupIT-exists";
        createPlainTable(table);
        String backupArn = createBackup(table, "exists");

        ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + backupArn + "\", \"TargetTableName\": \"" + table + "\"}")
                .statusCode(400)
                .body("__type", containsString("TableAlreadyExistsException"))
                .body("message", equalTo("Table already exists: " + table));
    }

    @Test
    void restoreTableFromBackupChecksTheArnFirst() {
        ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + "x".repeat(40) + "\", \"TargetTableName\": \"a b\"}")
                .statusCode(400)
                .body("message", equalTo("Invalid Backup ARN"));
        String arn = TABLE_ARN_PREFIX + "BackupIT-none/backup/01700000000000-abcdef12";
        ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + arn + "\"}")
                .statusCode(400)
                .body("message", equalTo("The parameter 'TableName' is required but was not present in the request"));
        ddb("RestoreTableFromBackup", """
                {"BackupArn": "%s", "TargetTableName": "a b", "BillingModeOverride": "BAD"}
                """.formatted(arn))
                .statusCode(400)
                .body("message", equalTo("2 validation errors detected: Value 'BAD' at 'billingModeOverride' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[PROVISIONED, PAY_PER_REQUEST]; Value 'a b' at 'targetTableName' failed to satisfy "
                        + "constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+"));
    }

    @Test
    void restoreTableFromBackupChecksTheBackupIdAfterTheRequest() {
        String arn = TABLE_ARN_PREFIX + "BackupIT-none/backup/1700000000000-abcdef12";
        ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + arn + "\"}")
                .statusCode(400)
                .body("message", equalTo("The parameter 'TableName' is required but was not present in the request"));
        ddb("RestoreTableFromBackup", "{\"BackupArn\": \"" + arn + "\", \"TargetTableName\": \"BackupIT-never\"}")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: BackupArn is not valid"));
    }

    @Test
    void restoreAcrossRegionsNeedsAnSseOverride() {
        String table = "BackupIT-cross-region";
        createPlainTable(table);
        putItem(table, "k1");
        String backupArn = createBackup(table, "cross-region");
        String sse = ", \"SSESpecificationOverride\": {\"Enabled\": true, \"SSEType\": \"KMS\"}";
        String missing = TABLE_ARN_PREFIX + table + "/backup/01700000000000-abcdef12";
        String badId = TABLE_ARN_PREFIX + table + "/backup/1700000000000-abcdef12";

        for (String request : List.of(restoreRequest(backupArn, ""), restoreRequest(missing, ""),
                restoreRequest(badId, ""), restoreRequest(backupArn, ", \"SSESpecificationOverride\": null"))) {
            signedWith(OTHER_REGION_AUTH, "RestoreTableFromBackup", request)
                    .statusCode(400)
                    .body("message", equalTo(
                            "Invalid Request: sseSpecificationOverride must be provided for cross-region restores"));
        }
        signedWith(OTHER_REGION_AUTH, "RestoreTableFromBackup", restoreRequest(badId, sse))
                .statusCode(400)
                .body("message", equalTo("Invalid Request: BackupArn is not valid"));
        signedWith(OTHER_REGION_AUTH, "RestoreTableFromBackup", restoreRequest(missing, sse))
                .statusCode(400)
                .body("__type", containsString("BackupNotFoundException"));

        signedWith(OTHER_REGION_AUTH, "RestoreTableFromBackup", """
                {"BackupArn": "%s", "TargetTableName": "BackupIT-cross-region-copy"%s}
                """.formatted(backupArn, sse))
                .statusCode(200)
                .body("TableDescription.TableArn",
                        equalTo("arn:aws:dynamodb:eu-west-2:000000000000:table/BackupIT-cross-region-copy"))
                .body("TableDescription.RestoreSummary.SourceBackupArn", equalTo(backupArn))
                .body("TableDescription.RestoreSummary.SourceTableArn", equalTo(TABLE_ARN_PREFIX + table))
                .body("TableDescription.SSEDescription.KMSMasterKeyArn", startsWith("arn:aws:kms:eu-west-2:"));
        signedWith(OTHER_REGION_AUTH, "Scan", "{\"TableName\": \"BackupIT-cross-region-copy\"}")
                .statusCode(200)
                .body("Items.pk.S", contains("k1"));
    }

    @Test
    void restoreOfABackupInAnotherPartitionFails() {
        String table = "BackupIT-other-partition";
        signedWith(OTHER_PARTITION_AUTH, "CreateTable", plainTableBody(table)).statusCode(200);
        String backupArn = signedWith(OTHER_PARTITION_AUTH, "CreateBackup",
                "{\"TableName\": \"" + table + "\", \"BackupName\": \"other-partition\"}")
                .statusCode(200)
                .extract().path("BackupDetails.BackupArn");
        String target = "BackupIT-other-partition-target";
        createPlainTable(target);
        String sse = "\"SSESpecificationOverride\": {\"Enabled\": true}";

        ddb("RestoreTableFromBackup", restoreRequest(backupArn, ""))
                .statusCode(400)
                .body("message", equalTo(
                        "Invalid Request: sseSpecificationOverride must be provided for cross-region restores"));
        restore(backupArn, target, sse)
                .statusCode(400)
                .body("__type", containsString("TableAlreadyExistsException"));
        ddb("RestoreTableFromBackup", restoreRequest(backupArn, ", " + sse))
                .statusCode(500)
                .body("__type", containsString("InternalServerError"))
                .body("message", equalTo("Internal server error"));
    }

    @Test
    void restoreReportsEveryViolationBeforeTheBackupLookup() {
        String arn = TABLE_ARN_PREFIX + "BackupIT-none/backup/01700000000000-abcdef12";
        String notNull = "failed to satisfy constraint: Member must not be null";
        String atLeastOne = "failed to satisfy constraint: Member must have value greater than or equal to 1";
        ddb("RestoreTableFromBackup", """
                {"BackupArn": "%s", "TargetTableName": "bad name!", "BillingModeOverride": "BAD",
                 "ProvisionedThroughputOverride": {},
                 "GlobalSecondaryIndexOverride": [%s]}
                """.formatted(arn, gsi1(", \"ProvisionedThroughput\": {}").replace("\"gsi1\"", "\"x!\"")))
                .statusCode(400)
                .body("message", equalTo("8 validation errors detected: "
                        + "Value null at 'provisionedThroughputOverride.writeCapacityUnits' " + notNull + "; "
                        + "Value null at 'provisionedThroughputOverride.readCapacityUnits' " + notNull + "; "
                        + "Value 'BAD' at 'billingModeOverride' failed to satisfy constraint: Member must satisfy "
                        + "enum value set: [PROVISIONED, PAY_PER_REQUEST]; "
                        + "Value 'bad name!' at 'targetTableName' failed to satisfy constraint: Member must satisfy "
                        + "regular expression pattern: [a-zA-Z0-9_.-]+; "
                        + "Value 'x!' at 'globalSecondaryIndexOverride.1.member.indexName' failed to satisfy "
                        + "constraint: Member must have length greater than or equal to 3; "
                        + "Value 'x!' at 'globalSecondaryIndexOverride.1.member.indexName' failed to satisfy "
                        + "constraint: Member must satisfy regular expression pattern: [a-zA-Z0-9_.-]+; "
                        + "Value null at 'globalSecondaryIndexOverride.1.member.provisionedThroughput."
                        + "writeCapacityUnits' " + notNull + "; "
                        + "Value null at 'globalSecondaryIndexOverride.1.member.provisionedThroughput."
                        + "readCapacityUnits' " + notNull));
        ddb("RestoreTableFromBackup", """
                {"BackupArn": "%s", "TargetTableName": "BackupIT-none-x", "BillingModeOverride": "PROVISIONED",
                 "ProvisionedThroughputOverride": {"ReadCapacityUnits": 0}}
                """.formatted(arn))
                .statusCode(400)
                .body("message", equalTo("2 validation errors detected: "
                        + "Value null at 'provisionedThroughputOverride.writeCapacityUnits' " + notNull + "; "
                        + "Value '0' at 'provisionedThroughputOverride.readCapacityUnits' " + atLeastOne));
        ddb("RestoreTableFromBackup", """
                {"BackupArn": "%s", "TargetTableName": "BackupIT-none-x", "BillingModeOverride": "PROVISIONED",
                 "ProvisionedThroughputOverride": {"ReadCapacityUnits": 0, "WriteCapacityUnits": 1},
                 "GlobalSecondaryIndexOverride": [%s]}
                """.formatted(arn, gsi1(", \"ProvisionedThroughput\": {\"ReadCapacityUnits\": 0, "
                        + "\"WriteCapacityUnits\": 0}")))
                .statusCode(400)
                .body("message", equalTo("3 validation errors detected: "
                        + "Value '0' at 'provisionedThroughputOverride.readCapacityUnits' " + atLeastOne + "; "
                        + "Value '0' at 'globalSecondaryIndexOverride.1.member.provisionedThroughput."
                        + "writeCapacityUnits' " + atLeastOne + "; "
                        + "Value '0' at 'globalSecondaryIndexOverride.1.member.provisionedThroughput."
                        + "readCapacityUnits' " + atLeastOne));
    }

    @Test
    void restoreOverridesFollowTheBillingMode() {
        String table = "BackupIT-billing";
        createIndexedTable(table);
        String backupArn = createBackup(table, "billing");

        restore(backupArn, "BackupIT-billing-x", "\"BillingModeOverride\": \"PROVISIONED\"")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Must override ProvisionedThroughput if BillingMode is "
                        + "overridden to PROVISIONED"));
        restore(backupArn, "BackupIT-billing-x", """
                "BillingModeOverride": "PROVISIONED",
                "ProvisionedThroughputOverride": {"ReadCapacityUnits": 2, "WriteCapacityUnits": 3}
                """)
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Must override ProvisionedThroughput if BillingMode is "
                        + "overridden to PROVISIONED for index gsi1"));
        restore(backupArn, "BackupIT-billing-x",
                "\"ProvisionedThroughputOverride\": {\"ReadCapacityUnits\": 2, \"WriteCapacityUnits\": 3}")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override ProvisionedThroughput unless "
                        + "BillingMode is PROVISIONED"));
        restore(backupArn, "BackupIT-billing-x", """
                "BillingModeOverride": "PROVISIONED",
                "ProvisionedThroughputOverride": {"ReadCapacityUnits": 2, "WriteCapacityUnits": 3},
                "OnDemandThroughputOverride": {"MaxReadRequestUnits": 10}
                """)
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override MaxReadRequestUnits for "
                        + "OnDemandThroughput when BillingMode is PROVISIONED"));
        restore(backupArn, "BackupIT-billing-x", "\"GlobalSecondaryIndexOverride\": [" + gsi1(
                ", \"ProvisionedThroughput\": {\"ReadCapacityUnits\": 4, \"WriteCapacityUnits\": 5}") + "]")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override ProvisionedThroughput unless "
                        + "BillingMode is PROVISIONED for index gsi1"));

        restore(backupArn, "BackupIT-billing-provisioned", """
                "BillingModeOverride": "PROVISIONED",
                "ProvisionedThroughputOverride": {"ReadCapacityUnits": 2, "WriteCapacityUnits": 3},
                "GlobalSecondaryIndexOverride": [%s]
                """.formatted(gsi1(", \"ProvisionedThroughput\": {\"ReadCapacityUnits\": 4, \"WriteCapacityUnits\": 5}")))
                .statusCode(200)
                .body("TableDescription", not(hasKey("BillingModeSummary")))
                .body("TableDescription.ProvisionedThroughput.ReadCapacityUnits", equalTo(2))
                .body("TableDescription.ProvisionedThroughput.WriteCapacityUnits", equalTo(3))
                .body("TableDescription.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits",
                        equalTo(4));
        restore(backupArn, "BackupIT-billing-ondemand", "\"OnDemandThroughputOverride\": {\"MaxReadRequestUnits\": 10}")
                .statusCode(200)
                .body("TableDescription.OnDemandThroughput.MaxReadRequestUnits", equalTo(10));
    }

    @Test
    void restoreOnDemandOverridesMergeWithTheSourceAndFollowTheBillingMode() {
        String table = "BackupIT-ondemand";
        ddb("CreateTable", """
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "g", "AttributeType": "S"}],
                    "GlobalSecondaryIndexes": [%s],
                    "BillingMode": "PAY_PER_REQUEST",
                    "OnDemandThroughput": {"MaxReadRequestUnits": 7, "MaxWriteRequestUnits": 8}
                }
                """.formatted(table, gsi1(", \"OnDemandThroughput\": {\"MaxReadRequestUnits\": 5, "
                        + "\"MaxWriteRequestUnits\": 6}"))).statusCode(200);
        String backupArn = createBackup(table, "ondemand");
        String gsiThroughput = ", \"ProvisionedThroughput\": {\"ReadCapacityUnits\": 1, \"WriteCapacityUnits\": 1}";
        String provisioned = """
                "BillingModeOverride": "PROVISIONED",
                "ProvisionedThroughputOverride": {"ReadCapacityUnits": 2, "WriteCapacityUnits": 3},
                """;

        restore(backupArn, "BackupIT-ondemand-x", provisioned + "\"GlobalSecondaryIndexOverride\": [" + gsi1(
                gsiThroughput + ", \"OnDemandThroughput\": {\"MaxWriteRequestUnits\": 5}") + "]")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override MaxWriteRequestUnits for "
                        + "OnDemandThroughput when BillingMode is PROVISIONED for index gsi1"));
        restore(backupArn, "BackupIT-ondemand-x", "\"GlobalSecondaryIndexOverride\": [" + gsi1(
                gsiThroughput + ", \"OnDemandThroughput\": {\"MaxReadRequestUnits\": 5}") + "]")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override MaxReadRequestUnits for "
                        + "OnDemandThroughput when ProvisionedThroughputOverride is present for index gsi1"));
        restore(backupArn, "BackupIT-ondemand-x", """
                "ProvisionedThroughputOverride": {"ReadCapacityUnits": 2, "WriteCapacityUnits": 3},
                "OnDemandThroughputOverride": {"MaxReadRequestUnits": 10}
                """)
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override MaxReadRequestUnits for "
                        + "OnDemandThroughput when ProvisionedThroughputOverride is present"));
        restore(backupArn, "BackupIT-ondemand-x", """
                "BillingModeOverride": "PAY_PER_REQUEST",
                "ProvisionedThroughputOverride": {"ReadCapacityUnits": 2, "WriteCapacityUnits": 3}
                """)
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override ProvisionedThroughput if BillingMode "
                        + "is overridden to PAY_PER_REQUEST"));
        restore(backupArn, "BackupIT-ondemand-x", "\"BillingModeOverride\": \"PAY_PER_REQUEST\", "
                + "\"GlobalSecondaryIndexOverride\": [" + gsi1(gsiThroughput) + "]")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override ProvisionedThroughput if BillingMode "
                        + "is overridden to PAY_PER_REQUEST for index gsi1"));

        restore(backupArn, "BackupIT-ondemand-provisioned",
                provisioned + "\"GlobalSecondaryIndexOverride\": [" + gsi1(gsiThroughput) + "]")
                .statusCode(200)
                .body("TableDescription", not(hasKey("OnDemandThroughput")))
                .body("TableDescription.GlobalSecondaryIndexes[0]", not(hasKey("OnDemandThroughput")));
        restore(backupArn, "BackupIT-ondemand-merged", """
                "OnDemandThroughputOverride": {"MaxReadRequestUnits": 10},
                "GlobalSecondaryIndexOverride": [%s]
                """.formatted(gsi1(", \"OnDemandThroughput\": {\"MaxWriteRequestUnits\": 9}")))
                .statusCode(200)
                .body("TableDescription.OnDemandThroughput.MaxReadRequestUnits", equalTo(10))
                .body("TableDescription.OnDemandThroughput.MaxWriteRequestUnits", equalTo(8))
                .body("TableDescription.GlobalSecondaryIndexes[0].OnDemandThroughput.MaxReadRequestUnits", equalTo(5))
                .body("TableDescription.GlobalSecondaryIndexes[0].OnDemandThroughput.MaxWriteRequestUnits",
                        equalTo(9));
        restore(backupArn, "BackupIT-ondemand-empty", """
                "OnDemandThroughputOverride": {},
                "GlobalSecondaryIndexOverride": [%s]
                """.formatted(gsi1(", \"OnDemandThroughput\": {}")))
                .statusCode(200)
                .body("TableDescription.OnDemandThroughput.MaxReadRequestUnits", equalTo(7))
                .body("TableDescription.GlobalSecondaryIndexes[0].OnDemandThroughput.MaxWriteRequestUnits",
                        equalTo(6));
    }

    @Test
    void restoreOfAProvisionedTableNeedsThroughputForEveryIndexOverride() {
        String table = "BackupIT-provisioned";
        String gsiThroughput = ", \"ProvisionedThroughput\": {\"ReadCapacityUnits\": 1, \"WriteCapacityUnits\": 1}";
        ddb("CreateTable", """
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "g", "AttributeType": "S"}],
                    "GlobalSecondaryIndexes": [%s],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1}
                }
                """.formatted(table, gsi1(gsiThroughput))).statusCode(200);
        String backupArn = createBackup(table, "provisioned");

        restore(backupArn, "BackupIT-provisioned-x", "\"GlobalSecondaryIndexOverride\": [" + gsi1("") + "]")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Must specify provisioned throughput for index gsi1"));
        restore(backupArn, "BackupIT-provisioned-x", "\"GlobalSecondaryIndexOverride\": [" + gsi1(
                ", \"OnDemandThroughput\": {\"MaxReadRequestUnits\": 5}") + "]")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override MaxReadRequestUnits for "
                        + "OnDemandThroughput unless BillingModeOverride is PAY_PER_REQUEST for index gsi1"));
        restore(backupArn, "BackupIT-provisioned-x", "\"OnDemandThroughputOverride\": {\"MaxReadRequestUnits\": 10}")
                .statusCode(400)
                .body("message", equalTo("Invalid Request: Cannot override MaxReadRequestUnits for "
                        + "OnDemandThroughput unless BillingModeOverride is PAY_PER_REQUEST"));

        restore(backupArn, "BackupIT-provisioned-kept", """
                "ProvisionedThroughputOverride": {"ReadCapacityUnits": 2, "WriteCapacityUnits": 3},
                "OnDemandThroughputOverride": {},
                "GlobalSecondaryIndexOverride": [%s]
                """.formatted(gsi1(gsiThroughput + ", \"OnDemandThroughput\": {}")))
                .statusCode(200)
                .body("TableDescription.ProvisionedThroughput.ReadCapacityUnits", equalTo(2))
                .body("TableDescription.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits",
                        equalTo(1))
                .body("TableDescription.GlobalSecondaryIndexes[0]", not(hasKey("OnDemandThroughput")));
        restore(backupArn, "BackupIT-provisioned-ondemand", """
                "BillingModeOverride": "PAY_PER_REQUEST",
                "GlobalSecondaryIndexOverride": [%s]
                """.formatted(gsi1(", \"OnDemandThroughput\": {\"MaxReadRequestUnits\": 5}")))
                .statusCode(200)
                .body("TableDescription.BillingModeSummary.BillingMode", equalTo("PAY_PER_REQUEST"))
                .body("TableDescription.GlobalSecondaryIndexes[0].ProvisionedThroughput.ReadCapacityUnits",
                        equalTo(0))
                .body("TableDescription.GlobalSecondaryIndexes[0].OnDemandThroughput.MaxReadRequestUnits", equalTo(5))
                .body("TableDescription.GlobalSecondaryIndexes[0].OnDemandThroughput",
                        not(hasKey("MaxWriteRequestUnits")));
    }

    @Test
    void restoreIndexOverridesMustMatchTheSourceIndexes() {
        String table = "BackupIT-indexes";
        createIndexedTable(table);
        String backupArn = createBackup(table, "indexes");
        String mismatch = "Invalid Request: Index %s does not match a secondary index that existed in the source "
                + "table and cannot be created during the restore operation";

        restore(backupArn, "BackupIT-indexes-x", """
                "GlobalSecondaryIndexOverride": [{"IndexName": "gsi1",
                    "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}], "Projection": {"ProjectionType": "KEYS_ONLY"}}]
                """)
                .statusCode(400)
                .body("message", equalTo(mismatch.formatted("gsi1")));
        restore(backupArn, "BackupIT-indexes-x", """
                "LocalSecondaryIndexOverride": [{"IndexName": "lsi1",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "l", "KeyType": "RANGE"}],
                    "Projection": {"ProjectionType": "ALL"}}]
                """)
                .statusCode(400)
                .body("message", equalTo(mismatch.formatted("lsi1")));

        restore(backupArn, "BackupIT-indexes-renamed", """
                "LocalSecondaryIndexOverride": [{"IndexName": "renamed",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "l", "KeyType": "RANGE"}],
                    "Projection": {"ProjectionType": "KEYS_ONLY"}}]
                """)
                .statusCode(200)
                .body("TableDescription.LocalSecondaryIndexes.IndexName", contains("renamed"))
                .body("TableDescription.GlobalSecondaryIndexes.IndexName", contains("gsi1"));
        restore(backupArn, "BackupIT-indexes-dropped", """
                "GlobalSecondaryIndexOverride": [], "LocalSecondaryIndexOverride": [],
                "SSESpecificationOverride": {"Enabled": false}
                """)
                .statusCode(200)
                .body("TableDescription", not(hasKey("GlobalSecondaryIndexes")))
                .body("TableDescription", not(hasKey("LocalSecondaryIndexes")))
                .body("TableDescription", not(hasKey("SSEDescription")))
                .body("TableDescription.AttributeDefinitions.AttributeName", containsInAnyOrder("pk", "sk"));
    }

    private void createIndexedTable(String table) {
        ddb("CreateTable", """
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "sk", "KeyType": "RANGE"}],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"},
                        {"AttributeName": "g", "AttributeType": "S"},
                        {"AttributeName": "l", "AttributeType": "S"}
                    ],
                    "GlobalSecondaryIndexes": [%s],
                    "LocalSecondaryIndexes": [{"IndexName": "lsi1",
                        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "l", "KeyType": "RANGE"}],
                        "Projection": {"ProjectionType": "KEYS_ONLY"}}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "StreamSpecification": {"StreamEnabled": true, "StreamViewType": "NEW_AND_OLD_IMAGES"},
                    "SSESpecification": {"Enabled": true, "SSEType": "KMS"},
                    "TableClass": "STANDARD_INFREQUENT_ACCESS",
                    "DeletionProtectionEnabled": true,
                    "Tags": [{"Key": "k", "Value": "v"}]
                }
                """.formatted(table, gsi1(""))).statusCode(200);
    }

    private static String gsi1(String extra) {
        return "{\"IndexName\": \"gsi1\", \"KeySchema\": [{\"AttributeName\": \"g\", \"KeyType\": \"HASH\"}], "
                + "\"Projection\": {\"ProjectionType\": \"ALL\"}" + extra + "}";
    }

    private void createPlainTable(String table) {
        ddb("CreateTable", plainTableBody(table)).statusCode(200);
    }

    private static String plainTableBody(String table) {
        return """
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1}
                }
                """.formatted(table);
    }

    private void putItem(String table, String pk) {
        ddb("PutItem", "{\"TableName\": \"" + table + "\", \"Item\": {\"pk\": {\"S\": \"" + pk + "\"}}}")
                .statusCode(200);
    }

    private void putItem(String table, String pk, String sk) {
        ddb("PutItem", """
                {"TableName": "%s", "Item": {"pk": {"S": "%s"}, "sk": {"S": "%s"}, "g": {"S": "g-%s"},
                 "l": {"S": "l-%s"}, "ttl": {"N": "4102444800"}}}
                """.formatted(table, pk, sk, sk, sk)).statusCode(200);
    }

    private String createBackup(String table, String backupName) {
        return ddb("CreateBackup", "{\"TableName\": \"" + table + "\", \"BackupName\": \"" + backupName + "\"}")
                .statusCode(200)
                .extract().path("BackupDetails.BackupArn");
    }

    private ValidatableResponse restore(String backupArn, String target, String overrides) {
        return ddb("RestoreTableFromBackup", """
                {"BackupArn": "%s", "TargetTableName": "%s", %s}
                """.formatted(backupArn, target, overrides));
    }

    private static String restoreRequest(String backupArn, String overrides) {
        return "{\"BackupArn\": \"" + backupArn + "\", \"TargetTableName\": \"BackupIT-never\"" + overrides + "}";
    }

    private static ValidatableResponse signedWith(String authorization, String action, String body) {
        return given().header("Authorization", authorization)
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then();
    }

    private static ValidatableResponse listBackupsBetween(String lowerBound, String upperBound) {
        return ddb("ListBackups", """
                {"TimeRangeLowerBound": %s, "TimeRangeUpperBound": %s}
                """.formatted(lowerBound, upperBound));
    }

    private static ValidatableResponse listBackupsFrom(String tableName, String exclusiveStartBackupArn) {
        return ddb("ListBackups", """
                {"TableName": "%s", "ExclusiveStartBackupArn": "%s"}
                """.formatted(tableName, exclusiveStartBackupArn));
    }
}
