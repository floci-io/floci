package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.Get;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DynamoDB transaction item permissions with IAM enforcement")
class DynamoDbTransactionAuthorizationTest {

    private IamClient iam;
    private DynamoDbClient admin;
    private DynamoDbClient caller;
    private String user;
    private String table;
    private String tableArn;
    private AccessKey accessKey;
    private boolean policyCreated;

    @BeforeEach
    void setUp() {
        iam = TestFixtures.iamClient();
        admin = TestFixtures.dynamoDbClient();
        String name = TestFixtures.uniqueName("tx-auth");
        iam.createUser(r -> r.userName(name));
        user = name;
        accessKey = iam.createAccessKey(r -> r.userName(user)).accessKey();
        caller = DynamoDbClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        accessKey.accessKeyId(), accessKey.secretAccessKey()))).build();
        boolean enforced = false;
        try {
            caller.listTables();
        } catch (DynamoDbException error) {
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException");
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");
        tableArn = admin.createTable(r -> r.tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(k -> k.attributeName("PK").keyType(KeyType.HASH))
                .attributeDefinitions(a -> a.attributeName("PK").attributeType(ScalarAttributeType.S)))
                .tableDescription().tableArn();
        table = name;
        admin.putItem(r -> r.tableName(table).item(item("item", "original")));
    }

    @AfterEach
    void cleanUp() {
        try {
            if (table != null) {
                admin.deleteTable(r -> r.tableName(table));
            }
        } finally {
            try {
                if (policyCreated) {
                    iam.deleteUserPolicy(r -> r.userName(user).policyName("transaction"));
                }
            } finally {
                try {
                    if (accessKey != null) {
                        iam.deleteAccessKey(r -> r.userName(user).accessKeyId(accessKey.accessKeyId()));
                    }
                } finally {
                    try {
                        if (user != null) {
                            iam.deleteUser(r -> r.userName(user));
                        }
                    } finally {
                        if (caller != null) {
                            caller.close();
                        }
                        if (admin != null) {
                            admin.close();
                        }
                        if (iam != null) {
                            iam.close();
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "Get", "ConditionCheck"})
    void itemPermissionAllowsTransactionButTransactionVerbDoesNot(String operation) {
        allow("dynamodb:" + enclosing(operation), "");
        assertThatThrownBy(() -> transaction(operation)).isInstanceOfSatisfying(DynamoDbException.class,
                error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException"));
        assertThat(read().get("value").s()).isEqualTo("original");
        allow("dynamodb:" + operation + "Item", "");
        transaction(operation);
        if ("Delete".equals(operation)) {
            assertThat(read()).isEmpty();
        } else {
            assertThat(read().get("value").s()).isEqualTo(
                    "Put".equals(operation) || "Update".equals(operation) ? "changed" : "original");
        }
    }

    @Test
    void enclosingOperationAllowsTransactionAndDeniesStandaloneRead() {
        allow("dynamodb:GetItem", ",\"Condition\":{\"ForAnyValue:StringEquals\":{"
                + "\"dynamodb:EnclosingOperation\":[\"TransactGetItems\"]}}");
        transaction("Get");
        assertThatThrownBy(() -> caller.getItem(r -> r.tableName(table).key(key("item"))))
                .isInstanceOfSatisfying(DynamoDbException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException"));
    }

    @Test
    void laterForbiddenKeyPreventsEarlierWriteWithoutNullGuard() {
        allow("dynamodb:PutItem", ",\"Condition\":{\"ForAllValues:StringEquals\":{"
                + "\"dynamodb:LeadingKeys\":[\"item\"]}}");
        assertThatThrownBy(() -> caller.transactWriteItems(r -> r.transactItems(
                TransactWriteItem.builder().put(Put.builder().tableName(table).item(item("item", "changed")).build()).build(),
                TransactWriteItem.builder().put(Put.builder().tableName(table).item(item("forbidden", "changed"))
                        .build()).build())))
                .isInstanceOfSatisfying(DynamoDbException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException"));
        assertThat(read().get("value").s()).isEqualTo("original");
        assertThat(admin.getItem(r -> r.tableName(table).key(key("forbidden"))).item()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete"})
    void standaloneReturnValuesConditionsRejectBeforeMutation(String operation) {
        allow("dynamodb:" + operation + "Item", ",\"Condition\":{\"StringEquals\":{"
                + "\"dynamodb:ReturnValues\":\"NONE\"}}");
        assertThatThrownBy(() -> standaloneWrite(operation, "ALL_OLD"))
                .isInstanceOfSatisfying(DynamoDbException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException"));
        assertThat(read().get("value").s()).isEqualTo("original");
        assertThat(standaloneWrite(operation, null)).isEmpty();
        if ("Delete".equals(operation)) {
            assertThat(read()).isEmpty();
        } else {
            assertThat(read().get("value").s()).isEqualTo("changed");
        }
        admin.putItem(r -> r.tableName(table).item(item("item", "original")));
        allow("dynamodb:" + operation + "Item", ",\"Condition\":{\"StringEquals\":{"
                + "\"dynamodb:ReturnValues\":\"ALL_OLD\"}}");
        assertThat(standaloneWrite(operation, "ALL_OLD").get("value").s()).isEqualTo("original");
    }

    private Map<String, AttributeValue> standaloneWrite(String operation, String returnValues) {
        return switch (operation) {
            case "Put" -> caller.putItem(r -> r.tableName(table).item(item("item", "changed"))
                    .returnValues(returnValues)).attributes();
            case "Update" -> caller.updateItem(r -> r.tableName(table).key(key("item"))
                    .updateExpression("SET #v = :v").expressionAttributeNames(Map.of("#v", "value"))
                    .expressionAttributeValues(Map.of(":v", AttributeValue.fromS("changed")))
                    .returnValues(returnValues)).attributes();
            case "Delete" -> caller.deleteItem(r -> r.tableName(table).key(key("item"))
                    .returnValues(returnValues)).attributes();
            default -> throw new IllegalArgumentException(operation);
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void differingStandaloneReturnModesRequireBothGrants(boolean succeeds) {
        for (String grant : new String[]{"UPDATED_NEW", "ALL_OLD"}) {
            allow("dynamodb:UpdateItem", ",\"Condition\":{\"StringEquals\":{"
                    + "\"dynamodb:ReturnValues\":\"" + grant + "\"}}");
            assertThatThrownBy(() -> mixedReturnUpdate(succeeds))
                    .isInstanceOfSatisfying(DynamoDbException.class,
                            error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException"));
            assertThat(read().get("value").s()).isEqualTo("original");
        }
        allow("dynamodb:UpdateItem", ",\"Condition\":{\"StringEquals\":{"
                + "\"dynamodb:ReturnValues\":[\"UPDATED_NEW\",\"ALL_OLD\"]}}");
        if (succeeds) {
            assertThat(mixedReturnUpdate(true).get("value").s()).isEqualTo("changed");
            assertThat(read().get("value").s()).isEqualTo("changed");
        } else {
            assertThatThrownBy(() -> mixedReturnUpdate(false))
                    .isInstanceOfSatisfying(ConditionalCheckFailedException.class,
                            error -> assertThat(error.item().get("value").s()).isEqualTo("original"));
            assertThat(read().get("value").s()).isEqualTo("original");
        }
    }

    private Map<String, AttributeValue> mixedReturnUpdate(boolean succeeds) {
        return caller.updateItem(r -> r.tableName(table).key(key("item"))
                .updateExpression("SET #v = :v").expressionAttributeNames(Map.of("#v", "value"))
                .expressionAttributeValues(Map.of(":v", AttributeValue.fromS("changed")))
                .conditionExpression(succeeds ? "attribute_exists(PK)" : "attribute_not_exists(PK)")
                .returnValues("UPDATED_NEW").returnValuesOnConditionCheckFailure("ALL_OLD")).attributes();
    }

    private void transaction(String operation) {
        if ("Get".equals(operation)) {
            assertThat(caller.transactGetItems(r -> r.transactItems(TransactGetItem.builder()
                    .get(Get.builder().tableName(table).key(key("item")).build()).build()))
                    .responses().get(0).item().get("value").s()).isEqualTo("original");
            return;
        }
        TransactWriteItem request = switch (operation) {
            case "Put" -> TransactWriteItem.builder().put(Put.builder()
                    .tableName(table).item(item("item", "changed")).build()).build();
            case "Update" -> TransactWriteItem.builder().update(Update.builder()
                    .tableName(table).key(key("item")).updateExpression("SET #v = :v")
                    .expressionAttributeNames(Map.of("#v", "value"))
                    .expressionAttributeValues(Map.of(":v", AttributeValue.fromS("changed"))).build()).build();
            case "Delete" -> TransactWriteItem.builder().delete(Delete.builder()
                    .tableName(table).key(key("item")).build()).build();
            case "ConditionCheck" -> TransactWriteItem.builder().conditionCheck(ConditionCheck.builder()
                    .tableName(table).key(key("item")).conditionExpression("attribute_exists(PK)").build()).build();
            default -> throw new IllegalArgumentException(operation);
        };
        caller.transactWriteItems(r -> r.transactItems(request));
    }

    private void allow(String action, String condition) {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"" + tableArn + "\"" + condition + "}]}";
        iam.putUserPolicy(r -> r.userName(user).policyName("transaction").policyDocument(document));
        policyCreated = true;
    }

    private Map<String, AttributeValue> read() {
        return admin.getItem(r -> r.tableName(table).key(key("item"))).item();
    }

    private static String enclosing(String operation) {
        return "Get".equals(operation) ? "TransactGetItems" : "TransactWriteItems";
    }

    private static Map<String, AttributeValue> key(String value) {
        return Map.of("PK", AttributeValue.fromS(value));
    }

    private static Map<String, AttributeValue> item(String key, String value) {
        return Map.of("PK", AttributeValue.fromS(key), "value", AttributeValue.fromS(value));
    }
}
