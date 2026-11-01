package io.github.hectorvent.floci.testing;

import io.restassured.response.ValidatableResponse;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.createUser;
import static io.restassured.RestAssured.given;

/** Shared wire requests and cleanup for DynamoDB IAM enforcement tests. */
public final class DynamoDbAuthorizationFixtures {
    private final PartitionCleanup cleanup;
    private final int port;
    private final String prefix;
    private final String policyName;

    public DynamoDbAuthorizationFixtures(PartitionCleanup cleanup, int port, String prefix, String policyName) {
        this.cleanup = cleanup;
        this.port = port;
        this.prefix = prefix;
        this.policyName = policyName;
    }

    public static String itemBody(String operation, String table) {
        if ("Put".equals(operation)) {
            return "{\"TableName\":\"" + table + "\",\"Item\":{\"PK\":{\"S\":\"item\"},"
                    + "\"value\":{\"S\":\"changed\"}}}";
        }
        String base = keyBody(table);
        if ("Update".equals(operation)) {
            return base.substring(0, base.length() - 1) + ",\"UpdateExpression\":\"SET #v = :v\","
                    + "\"ExpressionAttributeNames\":{\"#v\":\"value\"},"
                    + "\"ExpressionAttributeValues\":{\":v\":{\"S\":\"changed\"}}}";
        }
        if ("ConditionCheck".equals(operation)) {
            return base.substring(0, base.length() - 1) + ",\"ConditionExpression\":\"attribute_exists(PK)\"}";
        }
        return base;
    }

    public static String keyBody(String table) {
        return "{\"TableName\":\"" + table + "\",\"Key\":{\"PK\":{\"S\":\"item\"}}}";
    }

    public String table() {
        return table("PK");
    }

    public String table(String partitionKey) {
        String table = prefix + UUID.randomUUID();
        cleanup.register(() -> given().port(port).header("Authorization", auth("test", "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType("application/x-amz-json-1.0").body("{\"TableName\":\"" + table + "\"}")
                .post("/").then().statusCode(200));
        request("test", "CreateTable", """
                {"TableName":"%s","KeySchema":[{"AttributeName":"%s","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"%s","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table, partitionKey, partitionKey)).statusCode(200);
        request("test", "PutItem", itemBody("Put", table).replace("changed", "original")
                .replace("\"PK\"", "\"" + partitionKey + "\"")).statusCode(200);
        return table;
    }

    public String user(String policy) {
        // Eight characters of a UUID rather than a whole one: a user name is held to the 64
        // characters its shape declares, which a prefix plus a whole UUID can break.
        return userNamed(prefix + UUID.randomUUID().toString().substring(0, 8), policy);
    }

    /** A user whose name the caller chooses, for a policy that has to name it. */
    public String userNamed(String name, String policy) {
        String key = createUser(cleanup, port, auth("test", "iam"), name).accessKeyId();
        given().port(port).header("Authorization", auth("test", "iam")).formParam("Action", "PutUserPolicy")
                .formParam("UserName", name).formParam("PolicyName", policyName)
                .formParam("PolicyDocument", policy).post("/").then().statusCode(200);
        return key;
    }

    public static String policy(String action, String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"*\"" + condition + "}]}";
    }

    public ValidatableResponse request(String key, String action, String body) {
        return given().port(port).header("Authorization", auth(key, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }
}
