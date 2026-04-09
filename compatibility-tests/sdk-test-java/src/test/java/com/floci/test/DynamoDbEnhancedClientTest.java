package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the DynamoDB Enhanced Client against Floci.
 *
 */
@DisplayName("DynamoDB Enhanced Client")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbEnhancedClientTest {

    private static DynamoDbClient dynamoDbClient;
    private static DynamoDbEnhancedClient enhancedClient;
    private static DynamoDbTable<UserData> userTable;
    private static final String TABLE_NAME = "Users";

    @BeforeAll
    static void setUp() {
        dynamoDbClient = TestFixtures.dynamoDbClient();
        enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();

        userTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(UserData.class));
        userTable.createTable();
    }

    @AfterAll
    static void cleanUp() {
        if (dynamoDbClient != null) {
            try {
                dynamoDbClient.deleteTable(DeleteTableRequest.builder().tableName(TABLE_NAME).build());
            } catch (Exception ignored) {}
            dynamoDbClient.close();
        }
    }

    /**
     * Test updating a nullable boolean from null to true.
     * This mimics the Kotlin scenario for optional, nullable, variables in a class like: var useLoyaltyPoints: Boolean? = null
     *
     * The Enhanced Client generates: SET field1 = :val1, ..., boolField = :boolVal REMOVE nullField1, ...
     * The bug was that boolField would not be set because the parser incorrectly
     * included "REMOVE nullField1" in the value lookup.
     */
    @Test
    @Order(1)
    void testUpdateNullableBooleanFromNullToTrue() {
        String userId = "user-" + System.currentTimeMillis();

        // Step 1: Create user WITHOUT the boolean field set
        UserData user = new UserData();
        user.setUserId(userId);
        user.setEntries("initial entries");
        long createdTimestampInMillis = 1234567890L;
        user.setCreated(createdTimestampInMillis);
        // isActive is null (not set)
        user.setTempField("temporary data");

        userTable.putItem(user);

        // Verify initial state
        UserData initial = userTable.getItem(r -> r.key(k -> k.partitionValue(userId)));
        assertThat(initial).isNotNull();
        assertThat(initial.getIsActive()).isNull();
        assertThat(initial.getTempField()).isEqualTo("temporary data");
        assertThat(initial.getCreated()).isEqualTo(createdTimestampInMillis);

        // Step 2: Update user - set boolean to true and other fields
        // The Enhanced Client will generate a SET clause with multiple fields
        // followed by a REMOVE clause for null fields
        user.setIsActive(true);  // Set boolean to true
        user.setEntries("updated entries");
        user.setTempField(null);  // This will be REMOVED
        user.setCreated(null);    // This will be REMOVED

        userTable.updateItem(user);

        // Step 3: Get the item and verify the boolean was set correctly
        UserData updated = userTable.getItem(r -> r.key(k -> k.partitionValue(userId)));

        assertThat(updated).isNotNull();
        assertThat(updated.getIsActive())
                .as("isActive should be true after update")
                .isTrue();
        assertThat(updated.getEntries()).isEqualTo("updated entries");
        assertThat(updated.getTempField()).isNull();
        assertThat(updated.getCreated()).isNull();
    }

    /**
     * Test updating a boolean from false to true with Enhanced Client.
     */
    @Test
    @Order(2)
    void testUpdateBooleanFromFalseToTrue() {
        String userId = "user-" + System.currentTimeMillis();

        // Step 1: Create user WITH boolean = false
        UserData user = new UserData();
        user.setUserId(userId);
        user.setEntries("initial");
        user.setIsActive(false);
        user.setTempField("temp");

        userTable.putItem(user);

        // Verify initial state
        UserData initial = userTable.getItem(r -> r.key(k -> k.partitionValue(userId)));
        assertThat(initial.getIsActive()).isFalse();

        // Step 2: Update boolean to true
        user.setIsActive(true);
        user.setEntries("updated");
        user.setTempField(null);  // Will be REMOVED

        userTable.updateItem(user);

        // Step 3: Verify the boolean changed to true
        UserData updated = userTable.getItem(r -> r.key(k -> k.partitionValue(userId)));

        assertThat(updated).isNotNull();
        assertThat(updated.getIsActive())
                .as("isActive should be true after update from false")
                .isTrue();
        assertThat(updated.getEntries()).isEqualTo("updated");
        assertThat(updated.getTempField()).isNull();
    }

    @DynamoDbBean
    public static class UserData {
        private String userId;
        private String entries;
        private Long created;
        private Boolean isActive;
        private String tempField;

        @DynamoDbPartitionKey
        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getEntries() {
            return entries;
        }

        public void setEntries(String entries) {
            this.entries = entries;
        }

        public Long getCreated() {
            return created;
        }

        public void setCreated(Long created) {
            this.created = created;
        }

        public Boolean getIsActive() {
            return isActive;
        }

        public void setIsActive(Boolean isActive) {
            this.isActive = isActive;
        }

        public String getTempField() {
            return tempField;
        }

        public void setTempField(String tempField) {
            this.tempField = tempField;
        }
    }
}

