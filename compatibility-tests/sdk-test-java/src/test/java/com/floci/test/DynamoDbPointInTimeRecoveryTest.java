package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB Point-in-Time Recovery")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPointInTimeRecoveryTest {

    private static DynamoDbClient ddb;
    private static final String TABLE_NAME = "sdk-pitr-test-table";
    private static final String RESTORED_TABLE_NAME = "sdk-pitr-test-restored";
    private static final String SYSTEM_RESTORED_TABLE_NAME = "sdk-pitr-test-system-restored";
    private static Instant beforeSecondWrite;

    @BeforeAll
    static void setup() {
        ddb = TestFixtures.dynamoDbClient();
        ddb.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    @AfterAll
    static void cleanup() {
        for (String table : new String[] {TABLE_NAME, RESTORED_TABLE_NAME, SYSTEM_RESTORED_TABLE_NAME}) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(table).build());
            } catch (ResourceNotFoundException ignored) {
                // The test that creates or deletes it did not run.
            }
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    @Order(1)
    void updateContinuousBackups_opensTheRestoreWindow() {
        PointInTimeRecoveryDescription pitr = ddb.updateContinuousBackups(UpdateContinuousBackupsRequest.builder()
                .tableName(TABLE_NAME)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true).build())
                .build()).continuousBackupsDescription().pointInTimeRecoveryDescription();

        assertThat(pitr.pointInTimeRecoveryStatus()).isEqualTo(PointInTimeRecoveryStatus.ENABLED);
        assertThat(pitr.recoveryPeriodInDays()).isEqualTo(35);
        assertThat(pitr.earliestRestorableDateTime()).isNotNull();
        assertThat(pitr.latestRestorableDateTime()).isAfterOrEqualTo(pitr.earliestRestorableDateTime());
    }

    @Test
    @Order(2)
    void restoreTableToPointInTime_restoresTheItemsOfThatTime() throws InterruptedException {
        putItem("first");
        Thread.sleep(50);
        beforeSecondWrite = Instant.now();
        Thread.sleep(50);
        putItem("second");

        TableDescription description = ddb.restoreTableToPointInTime(RestoreTableToPointInTimeRequest.builder()
                .sourceTableName(TABLE_NAME)
                .targetTableName(RESTORED_TABLE_NAME)
                .restoreDateTime(beforeSecondWrite)
                .build()).tableDescription();

        assertThat(description.restoreSummary().restoreInProgress()).isTrue();
        assertThat(description.restoreSummary().sourceTableArn()).endsWith(":table/" + TABLE_NAME);
        ddb.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(RESTORED_TABLE_NAME).build());
        assertThat(keys(RESTORED_TABLE_NAME)).containsExactly("first");
    }

    @Test
    @Order(3)
    void restoreTableToPointInTime_refusesAFutureTime() {
        assertThatThrownBy(() -> ddb.restoreTableToPointInTime(RestoreTableToPointInTimeRequest.builder()
                .sourceTableName(TABLE_NAME)
                .targetTableName("sdk-pitr-test-never")
                .restoreDateTime(Instant.now().plusSeconds(3600))
                .build()))
                .isInstanceOf(InvalidRestoreTimeException.class);
    }

    @Test
    @Order(4)
    void deleteTable_leavesASystemBackup() {
        ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());

        List<BackupSummary> backups = ddb.listBackups(ListBackupsRequest.builder()
                .tableName(TABLE_NAME).backupType(BackupTypeFilter.SYSTEM).build()).backupSummaries();
        assertThat(backups).isNotEmpty();
        BackupSummary backup = backups.get(backups.size() - 1);
        assertThat(backup.backupName()).isEqualTo(TABLE_NAME + "$DeletedTableBackup");
        assertThat(backup.backupType()).isEqualTo(BackupType.SYSTEM);
        assertThat(backup.backupExpiryDateTime()).isAfter(backup.backupCreationDateTime());
        assertThatThrownBy(() -> ddb.deleteBackup(DeleteBackupRequest.builder()
                .backupArn(backup.backupArn()).build()))
                .isInstanceOf(DynamoDbException.class)
                .hasMessageContaining("not allowed to delete the system backup");

        ddb.restoreTableFromBackup(RestoreTableFromBackupRequest.builder()
                .backupArn(backup.backupArn()).targetTableName(SYSTEM_RESTORED_TABLE_NAME).build());
        ddb.waiter().waitUntilTableExists(DescribeTableRequest.builder()
                .tableName(SYSTEM_RESTORED_TABLE_NAME).build());
        assertThat(keys(SYSTEM_RESTORED_TABLE_NAME)).containsExactlyInAnyOrder("first", "second");
    }

    private static void putItem(String pk) {
        ddb.putItem(PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(Map.of("pk", AttributeValue.fromS(pk)))
                .build());
    }

    private static List<String> keys(String table) {
        return ddb.scan(ScanRequest.builder().tableName(table).build()).items().stream()
                .map(item -> item.get("pk").s())
                .toList();
    }
}
