package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * End-to-end proof of the scenario in issue #2926: one session scoped by
 * ForAllValues:StringLike on dynamodb:LeadingKeys reads its own partition and is denied
 * another tenant's, through the real filter, resolver and evaluator.
 */
@QuarkusTest
@TestProfile(DynamoDbFgacEnforcementIntegrationTest.IamEnforcementProfile.class)
class DynamoDbFgacEnforcementIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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
                .statusCode(403)
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

        // The Key omits the partition attribute, so the leading key cannot be resolved. With
        // access scoped purely through dynamodb:LeadingKeys this is denied rather than passed
        // to DynamoDB, which is the correct direction for a security boundary.
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
                .statusCode(403)
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
                .statusCode(403)
                .body(containsString("AccessDeniedException"));
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
        return given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "fgac-leading-keys-test")
                .formParam("Policy", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"dynamodb:*","Resource":"*",
                       "Condition":{"ForAllValues:StringLike":
                         {"dynamodb:LeadingKeys":["USER_alice*"]}}}
                    ]}""")
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

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
