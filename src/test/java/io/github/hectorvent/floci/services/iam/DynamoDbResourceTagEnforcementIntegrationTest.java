package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Proves that DynamoDB data-plane calls see the target table's {@code aws:ResourceTag} keys, and
 * that a request naming several tables is evaluated once per table with only that table's tags.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbResourceTagEnforcementIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void taggedTableAllowsTheDataActionAndAnUntaggedTableDeniesIt() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tagged = "tags-tagged-" + suffix;
        String untagged = "tags-untagged-" + suffix;
        String role = "ResourceTagRole" + suffix;

        createTable(tagged);
        createTable(untagged);
        tagTable(tagged, "team", "blue");
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem"],"Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role, null);

        putItem(accessKey, tagged, "item-1", 200);
        getItem(accessKey, tagged, "item-1", 200);
        getItem(accessKey, untagged, "item-1", 400);
        putItem(accessKey, untagged, "item-2", 400);
    }

    @Test
    void tagValueAndTagAbsenceBothFailThePositiveCondition() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String otherTeam = "tags-red-" + suffix;
        String untagged = "tags-none-" + suffix;
        String role = "ResourceTagValues" + suffix;

        createTable(otherTeam);
        createTable(untagged);
        tagTable(otherTeam, "team", "red");
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"StringLike":{"aws:ResourceTag/team":"bl*"}}}]}
                """);
        String accessKey = assumeRole(role, null);

        getItem(accessKey, otherTeam, "item-1", 400);
        getItem(accessKey, untagged, "item-1", 400);
    }

    @Test
    void everyTableInABatchIsEvaluatedWithItsOwnTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tagged = "tags-batch-tagged-" + suffix;
        String untagged = "tags-batch-untagged-" + suffix;
        String role = "ResourceTagBatch" + suffix;

        createTable(tagged);
        createTable(untagged);
        tagTable(tagged, "team", "blue");
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:BatchGetItem","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role, null);
        putItemAsAccount(ROLE_ACCOUNT_ID, tagged, "item-1");
        putItemAsAccount(ROLE_ACCOUNT_ID, untagged, "item-1");

        given()
                .header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.BatchGetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(batchGet(tagged, untagged))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("AccessDeniedException"));

        given()
                .header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.BatchGetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(batchGet(tagged))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Responses.'" + tagged + "'[0].PK.S", equalTo("item-1"));
    }

    @Test
    void everyTransactionItemIsEvaluatedWithItsTablesTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tagged = "tags-transact-tagged-" + suffix;
        String untagged = "tags-transact-untagged-" + suffix;
        String role = "ResourceTagTransact" + suffix;

        createTable(tagged);
        createTable(untagged);
        tagTable(tagged, "team", "blue");
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role, null);
        putItemAsAccount(ROLE_ACCOUNT_ID, tagged, "item-1");
        putItemAsAccount(ROLE_ACCOUNT_ID, untagged, "item-1");

        given()
                .header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.TransactGetItems")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(transactGet(tagged, untagged))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("AccessDeniedException"));

        given()
                .header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.TransactGetItems")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(transactGet(tagged))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Responses[0].Item.PK.S", equalTo("item-1"));
    }

    private static String transactGet(String... tables) {
        StringBuilder body = new StringBuilder("{\"TransactItems\":[");
        for (int i = 0; i < tables.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"Get\":{\"TableName\":\"").append(tables[i])
                    .append("\",\"Key\":{\"PK\":{\"S\":\"item-1\"}}}}");
        }
        return body.append("]}").toString();
    }

    private static String batchGet(String... tables) {
        StringBuilder body = new StringBuilder("{\"RequestItems\":{");
        for (int i = 0; i < tables.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('"').append(tables[i]).append("\":{\"Keys\":[{\"PK\":{\"S\":\"item-1\"}}]}");
        }
        return body.append("}}").toString();
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

    private static void tagTable(String tableName, String key, String value) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.TagResource")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"ResourceArn":"arn:aws:dynamodb:%s:%s:table/%s",
                     "Tags":[{"Key":"%s","Value":"%s"}]}"""
                        .formatted(REGION, ROLE_ACCOUNT_ID, tableName, key, value))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putItemAsAccount(String accountId, String tableName, String partitionKey) {
        given()
                .header("Authorization", auth(accountId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Item":{"PK":{"S":"%s"}}}"""
                        .formatted(tableName, partitionKey))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putItem(String accessKey, String tableName, String partitionKey, int status) {
        ValidatableResponse response = given()
                .header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Item":{"PK":{"S":"%s"}}}"""
                        .formatted(tableName, partitionKey))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("AccessDeniedException"));
        }
    }

    private static void getItem(String accessKey, String tableName, String partitionKey, int status) {
        ValidatableResponse response = given()
                .header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"PK":{"S":"%s"}}}"""
                        .formatted(tableName, partitionKey))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("AccessDeniedException"));
        }
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
                        {"Effect": "Allow", "Principal": {"AWS": "*"}, "Action": "sts:AssumeRole"}
                      ]
                    }
                    """)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putRolePolicy(String roleName, String policyDocument) {
        given()
                .formParam("Action", "PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "ResourceTagPolicy")
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static String assumeRole(String roleName, String sessionPolicy) {
        RequestSpecification request = given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "resource-tag-test")
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"));
        if (sessionPolicy != null) {
            request.formParam("Policy", sessionPolicy);
        }
        return request.when()
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
