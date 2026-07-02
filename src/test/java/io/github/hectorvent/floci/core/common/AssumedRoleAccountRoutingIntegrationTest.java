package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * End-to-end proof that assumed-role temporary credentials are routed to the role's account.
 *
 * <p>Scenario: account A ({@code 111122223333}) assumes a role in account B
 * ({@code 222233334444}); the returned temporary credentials are then used to create a DynamoDB
 * table. The table must materialize in account B's namespace — visible to B, invisible to A —
 * rather than collapsing to the default account.
 */
@QuarkusTest
class AssumedRoleAccountRoutingIntegrationTest {

    private static final String ACCOUNT_A = "111122223333";
    private static final String ACCOUNT_B = "222233334444";
    private static final String REGION = "us-east-1";
    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void assumedRoleCredentialsRouteResourcesToTargetAccount() {
        String tableName = "routing-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Account A assumes a role in account B and receives temporary credentials.
        String tempAccessKeyId = given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_B + ":role/CrossAccountAccess")
                .formParam("RoleSessionName", "routing-session")
                .header("Authorization", auth(ACCOUNT_A, "sts"))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");

        // 2. GetCallerIdentity with the temporary credentials resolves to account B.
        given()
                .formParam("Action", "GetCallerIdentity")
                .header("Authorization", auth(tempAccessKeyId, "sts"))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo(ACCOUNT_B));

        // 3. Create a DynamoDB table using the temporary credentials. Its ARN must carry account B.
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .header("Authorization", auth(tempAccessKeyId, "dynamodb"))
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {
                        "TableName": "%s",
                        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                        "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                        "BillingMode": "PAY_PER_REQUEST"
                    }
                    """.formatted(tableName))
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("TableDescription.TableArn",
                        equalTo("arn:aws:dynamodb:" + REGION + ":" + ACCOUNT_B + ":table/" + tableName));

        // 4. Account B sees the table.
        listTables(ACCOUNT_B).body("TableNames", hasItem(tableName));

        // 5. Account A does not — the resource is isolated to account B.
        listTables(ACCOUNT_A).body("TableNames", not(hasItem(tableName)));
    }

    private static io.restassured.response.ValidatableResponse listTables(String account) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                .header("Authorization", auth(account, "dynamodb"))
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260215/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
