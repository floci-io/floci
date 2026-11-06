package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DynamoDB On-Demand Backups")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbBackupTest {

    private static DynamoDbClient ddb;
    private static final String TABLE_NAME = "sdk-backup-test-table";
    private static final String RESTORED_TABLE_NAME = "sdk-backup-test-restored";
    private static String backupArn;

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
        ddb.putItem(PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(Map.of("pk", AttributeValue.fromS("backed-up")))
                .build());
    }

    @AfterAll
    static void cleanup() {
        for (String table : new String[] {TABLE_NAME, RESTORED_TABLE_NAME}) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(table).build());
            } catch (ResourceNotFoundException ignored) {
                // The restore test did not run.
            }
        }
        if (backupArn != null) {
            try {
                ddb.deleteBackup(DeleteBackupRequest.builder().backupArn(backupArn).build());
            } catch (BackupNotFoundException ignored) {
                // The delete test removed it before it failed.
            }
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    @Order(1)
    void createBackup_returnsCreating() {
        BackupDetails details = ddb.createBackup(CreateBackupRequest.builder()
                .tableName(TABLE_NAME).backupName("sdk_backup").build()).backupDetails();

        backupArn = details.backupArn();
        assertThat(backupArn).contains(":table/" + TABLE_NAME + "/backup/");
        assertThat(details.backupStatus()).isEqualTo(BackupStatus.CREATING);
        assertThat(details.backupType()).isEqualTo(BackupType.USER);
        assertThat(details.backupCreationDateTime()).isNotNull();
    }

    @Test
    @Order(2)
    void describeBackup_returnsAvailableWithSourceTable() {
        BackupDescription description = ddb.describeBackup(DescribeBackupRequest.builder()
                .backupArn(backupArn).build()).backupDescription();

        assertThat(description.backupDetails().backupStatus()).isEqualTo(BackupStatus.AVAILABLE);
        assertThat(description.sourceTableDetails().tableName()).isEqualTo(TABLE_NAME);
        assertThat(description.sourceTableDetails().itemCount()).isEqualTo(1L);
        assertThat(description.sourceTableDetails().billingMode()).isEqualTo(BillingMode.PAY_PER_REQUEST);
    }

    @Test
    @Order(3)
    void listBackups_containsTheBackup() {
        ListBackupsResponse response = ddb.listBackups(ListBackupsRequest.builder().tableName(TABLE_NAME).build());

        assertThat(response.backupSummaries()).extracting(BackupSummary::backupArn).containsExactly(backupArn);
        assertThat(response.lastEvaluatedBackupArn()).isNull();
    }

    @Test
    @Order(4)
    void restoreTableFromBackup_restoresTheItems() {
        TableDescription description = ddb.restoreTableFromBackup(RestoreTableFromBackupRequest.builder()
                .backupArn(backupArn).targetTableName(RESTORED_TABLE_NAME).build()).tableDescription();

        assertThat(description.tableName()).isEqualTo(RESTORED_TABLE_NAME);
        assertThat(description.restoreSummary().sourceBackupArn()).isEqualTo(backupArn);
        assertThat(description.restoreSummary().restoreInProgress()).isTrue();

        ddb.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(RESTORED_TABLE_NAME).build());
        GetItemResponse item = ddb.getItem(GetItemRequest.builder()
                .tableName(RESTORED_TABLE_NAME)
                .key(Map.of("pk", AttributeValue.fromS("backed-up")))
                .build());
        assertThat(item.item()).containsKey("pk");
    }

    @Test
    @Order(5)
    void deleteBackup_thenDescribeFails() {
        BackupDescription deleted = ddb.deleteBackup(DeleteBackupRequest.builder()
                .backupArn(backupArn).build()).backupDescription();
        assertThat(deleted.backupDetails().backupStatus()).isEqualTo(BackupStatus.DELETED);

        assertThatThrownBy(() -> ddb.describeBackup(DescribeBackupRequest.builder().backupArn(backupArn).build()))
                .isInstanceOf(BackupNotFoundException.class);
        backupArn = null;
    }
}
