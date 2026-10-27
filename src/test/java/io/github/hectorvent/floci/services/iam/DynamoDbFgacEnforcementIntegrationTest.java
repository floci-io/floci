package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end proof of the scenario in issue #2926: one session scoped by
 * ForAllValues:StringLike on dynamodb:LeadingKeys reads its own partition and is denied
 * another tenant's, through the real filter, resolver and evaluator.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbFgacEnforcementIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @CsvSource({"Query,false", "Scan,false", "Query,true", "Scan,true"})
    void defaultSelectionCannotBypassSpecificAttributesPolicy(String action, boolean index) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-select-" + suffix;
        String roleName = "FgacSelect" + suffix;
        createTableWithGsi(tableName, "by-value");
        putItemWithGsiKey(tableName, "USER_alice", "group");
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);
        String accessKey = assumeRoleWithSessionPolicy(roleName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["dynamodb:Query","dynamodb:Scan"],"Resource":"*",
                   "Condition":{"StringEqualsIfExists":{"dynamodb:Select":"SPECIFIC_ATTRIBUTES"}}}]}
                """);
        String key = index ? "GSI_PK" : "PK";
        String value = index ? "group" : "USER_alice";
        String indexMember = index ? ",\"IndexName\":\"by-value\"" : "";
        String keyMember = "Query".equals(action) ? """
                ,"KeyConditions":{"%s":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"%s"}]}}
                """.formatted(key, value) : "";
        String body = "{\"TableName\":\"" + tableName + "\"" + indexMember + keyMember;
        given().header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + action).contentType(DYNAMODB_CONTENT_TYPE)
                .body(body + "}").post("/").then().statusCode(400).body(containsString("AccessDeniedException"));
        given().header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + action).contentType(DYNAMODB_CONTENT_TYPE)
                .body(body + ",\"ProjectionExpression\":\"PK\"}").post("/").then().statusCode(200)
                .body("Count", equalTo(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "cbor", "legacy"})
    void defaultSelectionIsAvailableToExplicitAllowPoliciesAcrossProtocols(String protocol) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-select-wire-" + suffix;
        String roleName = "FgacSelectWire" + suffix;
        given().header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable").contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                        {"TableName":"%s","KeySchema":[{"AttributeName":"PK","KeyType":"HASH"},
                          {"AttributeName":"SK","KeyType":"RANGE"}],
                         "AttributeDefinitions":[{"AttributeName":"PK","AttributeType":"S"},
                          {"AttributeName":"SK","AttributeType":"S"}],"BillingMode":"PAY_PER_REQUEST"}
                        """.formatted(tableName)).post("/").then().statusCode(200);
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);
        String accessKey = assumeRoleWithSessionPolicy(roleName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:Query","Resource":"*",
                   "Condition":{"StringEquals":{"dynamodb:Select":"ALL_ATTRIBUTES"}}}]}
                """);
        String body = "legacy".equals(protocol)
                ? """
                  {"TableName":"%s","HashKeyValue":{"S":"USER_alice"}}
                  """.formatted(tableName)
                : """
                  {"TableName":"%s","KeyConditions":{"PK":{
                    "ComparisonOperator":"EQ","AttributeValueList":[{"S":"USER_alice"}]}}}
                  """.formatted(tableName);
        if ("cbor".equals(protocol)) {
            ObjectMapper cbor = new ObjectMapper(new CBORFactory());
            JsonNode request = new ObjectMapper().readTree(body);
            byte[] result = given().header("Authorization", auth(accessKey, "dynamodb"))
                    .contentType("application/cbor").accept("application/cbor")
                    .body(cbor.writeValueAsBytes(request)).post("/service/DynamoDB_20120810/operation/Query")
                    .then().statusCode(200).extract().asByteArray();
            assertEquals(0, cbor.readTree(result).path("Count").asInt());
        } else {
            given().header("Authorization", auth(accessKey, "dynamodb"))
                    .header("X-Amz-Target", "legacy".equals(protocol)
                            ? "DynamoDB_20111205.Query" : "DynamoDB_20120810.Query")
                    .contentType(DYNAMODB_CONTENT_TYPE).body(body).post("/").then().statusCode(200)
                    .body("Count", equalTo(0));
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Query", "BatchWriteItem"})
    void legacyKeysCannotBypassLeadingKeyPolicyWithoutNullGuard(String action) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-legacy-" + suffix;
        String roleName = "FgacLegacyRole" + suffix;
        given().header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable").contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                        {"TableName":"%s","KeySchema":[{"AttributeName":"PK","KeyType":"HASH"},
                          {"AttributeName":"SK","KeyType":"RANGE"}],
                         "AttributeDefinitions":[{"AttributeName":"PK","AttributeType":"S"},
                          {"AttributeName":"SK","AttributeType":"S"}],"BillingMode":"PAY_PER_REQUEST"}
                        """.formatted(tableName)).post("/").then().statusCode(200);
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);
        String accessKey = assumeRoleWithLeadingKeysSessionPolicy(roleName, false);
        for (String user : new String[]{"USER_bob", "USER_alice"}) {
            String body = "Query".equals(action)
                    ? """
                      {"TableName":"%s","HashKeyValue":{"S":"%s"}}
                      """.formatted(tableName, user)
                    : """
                      {"RequestItems":{"%s":[{"DeleteRequest":{"Key":{
                        "HashKeyElement":{"S":"%s"},"RangeKeyElement":{"S":"profile"}}}}]}}
                      """.formatted(tableName, user);
            if ("USER_bob".equals(user)) {
                given().header("Authorization", auth(accessKey, "dynamodb"))
                        .header("X-Amz-Target", "DynamoDB_20111205." + action)
                        .contentType(DYNAMODB_CONTENT_TYPE).body(body).post("/").then()
                        .statusCode(400).body(containsString("AccessDeniedException"));
            } else {
                given().header("Authorization", auth(accessKey, "dynamodb"))
                        .header("X-Amz-Target", "DynamoDB_20111205." + action)
                        .contentType(DYNAMODB_CONTENT_TYPE).body(body).post("/").then().statusCode(200);
            }
        }
    }

    @Test
    void legacyQueryHonorsProjectedAttributesAndExplicitPartitionDeny() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-legacy-attributes-" + suffix;
        String roleName = "FgacLegacyAttributes" + suffix;
        given().header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable").contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                        {"TableName":"%s","KeySchema":[{"AttributeName":"PK","KeyType":"HASH"},
                          {"AttributeName":"SK","KeyType":"RANGE"}],
                         "AttributeDefinitions":[{"AttributeName":"PK","AttributeType":"S"},
                          {"AttributeName":"SK","AttributeType":"S"}],"BillingMode":"PAY_PER_REQUEST"}
                        """.formatted(tableName)).post("/").then().statusCode(200);
        given().header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem").contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                        {"TableName":"%s","Item":{"PK":{"S":"USER_alice"},"SK":{"S":"one"},
                        "public":{"S":"visible"},"secret":{"S":"fixture-private"}}}
                        """.formatted(tableName)).post("/").then().statusCode(200);
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);
        String accessKey = assumeRoleWithSessionPolicy(roleName, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:Query","Resource":"*",
                   "Condition":{"ForAllValues:StringEquals":{"dynamodb:Attributes":["PK","SK","public"]}}},
                  {"Effect":"Deny","Action":"dynamodb:Query","Resource":"*",
                   "Condition":{"ForAnyValue:StringEquals":{"dynamodb:LeadingKeys":["USER_denied"]}}}]}
                """);
        for (String[] query : new String[][]{
                {"USER_alice", "public", "200"}, {"USER_alice", "secret", "400"}, {"USER_denied", "public", "400"}}) {
            if ("200".equals(query[2])) {
                given().header("Authorization", auth(accessKey, "dynamodb"))
                        .header("X-Amz-Target", "DynamoDB_20111205.Query").contentType(DYNAMODB_CONTENT_TYPE)
                        .body("""
                                {"TableName":"%s","HashKeyValue":{"S":"%s"},"AttributesToGet":["%s"]}
                                """.formatted(tableName, query[0], query[1]))
                        .post("/").then().statusCode(200);
            } else {
                given().header("Authorization", auth(accessKey, "dynamodb"))
                        .header("X-Amz-Target", "DynamoDB_20111205.Query").contentType(DYNAMODB_CONTENT_TYPE)
                        .body("""
                                {"TableName":"%s","HashKeyValue":{"S":"%s"},"AttributesToGet":["%s"]}
                                """.formatted(tableName, query[0], query[1]))
                        .post("/").then().statusCode(400).body(containsString("AccessDeniedException"));
            }
        }
        given().header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20111205.Query").contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                        {"TableName":"%s","HashKeyValue":{"S":"USER_alice"}}
                        """.formatted(tableName)).post("/").then().statusCode(200)
                .body("Items[0].secret.S", equalTo("fixture-private"));
    }

    @Test
    void leadingKeysScopedSessionReadsItsOwnPartitionAndIsDeniedAnotherTenants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-" + suffix;
        String roleName = "FgacRole" + suffix;

        createTable(tableName);
        putItem(tableName, "USER_alice");
        putItem(tableName, "USER_bob");
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);

        String accessKeyId = assumeRoleWithLeadingKeysSessionPolicy(roleName);

        // In scope: the session policy's ForAllValues:StringLike matches USER_alice*.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"PK":{"S":"USER_alice"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Item.PK.S", equalTo("USER_alice"));

        // Out of scope: same session, same table, different partition.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"PK":{"S":"USER_bob"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("AccessDeniedException"));
    }

    @Test
    void requestThatCannotProveItsLeadingKeyIsDenied() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-noprove-" + suffix;
        String roleName = "FgacNoProveRole" + suffix;

        createTable(tableName);
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);

        String accessKeyId = assumeRoleWithLeadingKeysSessionPolicy(roleName);

        // The Key omits the partition attribute, so the leading key cannot be resolved.
        // The session policy's Null:false guard denies it before DynamoDB handles the request.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"SK":{"S":"profile"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("AccessDeniedException"));
    }

    @Test
    void leadingKeysScopedSessionQueriesGsiByItsOwnPartitionKeyAndIsDeniedAnother() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-gsi-" + suffix;
        String roleName = "FgacGsiRole" + suffix;

        createTableWithGsi(tableName, "TenantGSI");
        putItemWithGsiKey(tableName, "item-1", "USER_alice");
        putItemWithGsiKey(tableName, "item-2", "USER_bob");
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);

        String accessKeyId = assumeRoleWithLeadingKeysSessionPolicy(roleName);

        // In scope: query targets GSI partition key matching USER_alice*.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.Query")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s",
                     "IndexName":"TenantGSI",
                     "KeyConditionExpression":"GSI_PK = :gsi",
                     "ExpressionAttributeValues":{":gsi":{"S":"USER_alice"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Count", equalTo(1))
                .body("Items[0].PK.S", equalTo("item-1"));

        // Out of scope: query targets GSI partition key of another tenant.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.Query")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s",
                     "IndexName":"TenantGSI",
                     "KeyConditionExpression":"GSI_PK = :gsi",
                     "ExpressionAttributeValues":{":gsi":{"S":"USER_bob"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("AccessDeniedException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {":uid = PK", ":uid = #pk"})
    void reversedQueryRequiresTheRequestedPartitionWithoutNullGuard(String expression) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String table = "fgac-reversed-" + suffix;
        String role = "FgacReversed" + suffix;
        createTable(table);
        putItem(table, "USER_alice");
        putItem(table, "USER_bob");
        createRole(role);
        putBroadDynamoDbRolePolicy(role);
        String accessKey = assumeRoleWithLeadingKeysSessionPolicy(role, false);
        for (String user : new String[]{"USER_alice", "USER_bob"}) {
            given().header("Authorization", auth(accessKey, "dynamodb"))
                    .header("X-Amz-Target", "DynamoDB_20120810.Query")
                    .contentType(DYNAMODB_CONTENT_TYPE)
                    .body("""
                        {"TableName":"%s","KeyConditionExpression":"%s",%s
                         "ExpressionAttributeValues":{":uid":{"S":"%s"}}}
                        """.formatted(table, expression, expression.contains("#")
                            ? "\"ExpressionAttributeNames\":{\"#pk\":\"PK\"}," : "", user))
                    .post("/").then().statusCode(user.equals("USER_alice") ? 200 : 400)
                    .body(containsString(user.equals("USER_alice") ? "USER_alice" : "AccessDeniedException"));
        }
    }

    @ParameterizedTest
    @CsvSource({"BatchGetItem,false", "BatchGetItem,true", "BatchWriteItem,false", "BatchWriteItem,true"})
    void everyBatchEntryContributesLeadingKeysWithoutNullGuard(String action, boolean alias) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String table = "fgac-batch-" + suffix;
        String other = alias ? "arn:aws:dynamodb:" + REGION + ":" + ROLE_ACCOUNT_ID + ":table/" + table
                : "fgac-other-" + suffix;
        String role = "FgacBatch" + suffix;
        createTable(table);
        if (!alias) {
            createTable(other);
        }
        putItem(table, "USER_alice");
        putItem(other, "USER_bob");
        createRole(role);
        putBroadDynamoDbRolePolicy(role);
        String accessKey = assumeRoleWithLeadingKeysSessionPolicy(role, false);
        for (String user : new String[]{"USER_alice_2", "USER_bob"}) {
            String first = batchEntry(action, "USER_alice");
            String second = batchEntry(action, user);
            given().header("Authorization", auth(accessKey, "dynamodb"))
                    .header("X-Amz-Target", "DynamoDB_20120810." + action)
                    .contentType(DYNAMODB_CONTENT_TYPE)
                    .body("{\"RequestItems\":{\"" + table + "\":" + first + ",\"" + other + "\":" + second + "}}")
                    .post("/").then().statusCode(user.equals("USER_bob") ? 400 : 200)
                    .body(containsString(user.equals("USER_bob") ? "AccessDeniedException"
                            : action.equals("BatchGetItem") ? "Responses" : "UnprocessedItems"));
        }
        given().header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"TableName\":\"" + other + "\",\"Key\":{\"PK\":{\"S\":\"USER_bob\"}}}")
                .post("/").then().statusCode(200).body("Item.PK.S", equalTo("USER_bob"));
    }

    @Test
    void batchPoliciesEvaluateEachTableWithOnlyItsOwnKeys() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String first = "fgac-first-" + suffix;
        String second = "fgac-second-" + suffix;
        String role = "FgacSeparate" + suffix;
        createTable(first);
        createTable(second);
        putItem(first, "USER_alice");
        putItem(second, "USER_bob");
        createRole(role);
        String statements = """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":"dynamodb:BatchGetItem","Resource":"%s",
                  "Condition":{"ForAllValues:StringEquals":{"dynamodb:LeadingKeys":["USER_alice"]}}},
                 {"Effect":"Allow","Action":"dynamodb:BatchGetItem","Resource":"%s",
                  "Condition":{"ForAllValues:StringEquals":{"dynamodb:LeadingKeys":["USER_bob"]}}}]}
                """.formatted("arn:aws:dynamodb:" + REGION + ":" + ROLE_ACCOUNT_ID + ":table/" + first,
                        "arn:aws:dynamodb:" + REGION + ":" + ROLE_ACCOUNT_ID + ":table/" + second);
        given().header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
                .formParam("Action", "PutRolePolicy").formParam("RoleName", role)
                .formParam("PolicyName", "Tables").formParam("PolicyDocument", statements)
                .post("/").then().statusCode(200);
        String accessKey = given().header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + role)
                .formParam("RoleSessionName", "separate-tables")
                .post("/").then().statusCode(200).extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
        for (boolean swapped : new boolean[]{false, true}) {
            given().header("Authorization", auth(accessKey, "dynamodb"))
                    .header("X-Amz-Target", "DynamoDB_20120810.BatchGetItem")
                    .contentType(DYNAMODB_CONTENT_TYPE)
                    .body("{\"RequestItems\":{\"" + first + "\":"
                            + batchEntry("BatchGetItem", swapped ? "USER_bob" : "USER_alice")
                            + ",\"" + second + "\":"
                            + batchEntry("BatchGetItem", swapped ? "USER_alice" : "USER_bob") + "}}")
                    .post("/").then().statusCode(swapped ? 400 : 200)
                    .body(containsString(swapped ? "AccessDeniedException" : "USER_bob"));
        }
    }

    private static String batchEntry(String action, String user) {
        String key = "{\"PK\":{\"S\":\"" + user + "\"}}";
        return action.equals("BatchGetItem") ? "{\"Keys\":[" + key + "]}"
                : "[{\"DeleteRequest\":{\"Key\":" + key + "}}]";
    }

    private static void createTable(String tableName) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s",
                     "KeySchema":[{"AttributeName":"PK","KeyType":"HASH"}],
                     "AttributeDefinitions":[{"AttributeName":"PK","AttributeType":"S"}],
                     "BillingMode":"PAY_PER_REQUEST"}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putItem(String tableName, String partitionKey) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Item":{"PK":{"S":"%s"},"email":{"S":"x@y.z"}}}"""
                        .formatted(tableName, partitionKey))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void createTableWithGsi(String tableName, String indexName) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s",
                     "KeySchema":[{"AttributeName":"PK","KeyType":"HASH"}],
                     "AttributeDefinitions":[
                       {"AttributeName":"PK","AttributeType":"S"},
                       {"AttributeName":"GSI_PK","AttributeType":"S"}
                     ],
                     "GlobalSecondaryIndexes":[
                       {
                         "IndexName":"%s",
                         "KeySchema":[{"AttributeName":"GSI_PK","KeyType":"HASH"}],
                         "Projection":{"ProjectionType":"ALL"}
                       }
                     ],
                     "BillingMode":"PAY_PER_REQUEST"}"""
                        .formatted(tableName, indexName))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putItemWithGsiKey(String tableName, String partitionKey, String gsiKey) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Item":{"PK":{"S":"%s"},"GSI_PK":{"S":"%s"},"email":{"S":"x@y.z"}}}"""
                        .formatted(tableName, partitionKey, gsiKey))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void createRole(String roleName) {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": { "AWS": "*" },
                          "Action": "sts:AssumeRole"
                        }
                      ]
                    }
                    """)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putBroadDynamoDbRolePolicy(String roleName) {
        given()
                .formParam("Action", "PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "AllowDynamoDb")
                .formParam("PolicyDocument", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"dynamodb:*","Resource":"*"}
                    ]}""")
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    /**
     * The session policy is the only thing that discriminates: Resource is "*", so an
     * allow or a deny can only come from the LeadingKeys condition.
     */
    private static String assumeRoleWithLeadingKeysSessionPolicy(String roleName) {
        return assumeRoleWithLeadingKeysSessionPolicy(roleName, true);
    }

    private static String assumeRoleWithLeadingKeysSessionPolicy(String roleName, boolean nullGuard) {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:*","Resource":"*",
                   "Condition":{"ForAllValues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}%s}}
                ]}
                """.formatted(nullGuard ? ",\"Null\":{\"dynamodb:LeadingKeys\":\"false\"}" : "");
        return assumeRoleWithSessionPolicy(roleName, policy);
    }

    private static String assumeRoleWithSessionPolicy(String roleName, String policy) {
        return given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "fgac-leading-keys-test")
                .formParam("Policy", policy)
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260904/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
