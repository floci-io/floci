package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Lists and lookups of resources stored for every region return only the request region's. Each
 * case creates in {@code eu-west-1} and reads from there and from {@code us-west-2}.
 */
@QuarkusTest
class RegionScopedListsIntegrationTest {

    private static final String HOME = "eu-west-1";
    private static final String OTHER = "us-west-2";
    private static final String DYNAMODB_JSON = "application/x-amz-json-1.0";
    private static final String COGNITO_JSON = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    @Test
    void lambdaEventSourceMappingsStayInTheirRegion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String queue = "region-esm-q-" + suffix;
        String function = "region-esm-fn-" + suffix;

        given().header("Authorization", auth(HOME, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue").formParam("QueueName", queue)
                .formParam("Version", "2012-11-05")
            .when().post("/").then().statusCode(200);
        given().header("Authorization", auth(HOME, "lambda"))
                .contentType("application/json")
                .body("""
                    {"FunctionName": "%s", "Runtime": "nodejs20.x",
                     "Role": "arn:aws:iam::000000000000:role/lambda-role", "Handler": "index.handler"}
                    """.formatted(function))
            .when().post("/2015-03-31/functions").then().statusCode(201);
        String uuid = given().header("Authorization", auth(HOME, "lambda"))
                .contentType("application/json")
                .body("""
                    {"FunctionName": "%s", "EventSourceArn": "arn:aws:sqs:%s:000000000000:%s"}
                    """.formatted(function, HOME, queue))
            .when().post("/2015-03-31/event-source-mappings")
            .then().statusCode(202).extract().path("UUID");

        given().header("Authorization", auth(HOME, "lambda"))
            .when().get("/2015-03-31/event-source-mappings?FunctionName=" + function)
            .then().statusCode(200).body("EventSourceMappings.UUID", hasItem(uuid));
        given().header("Authorization", auth(OTHER, "lambda"))
            .when().get("/2015-03-31/event-source-mappings?FunctionName=" + function)
            .then().statusCode(200).body("EventSourceMappings.UUID", not(hasItem(uuid)));
        given().header("Authorization", auth(OTHER, "lambda"))
            .when().get("/2015-03-31/event-source-mappings")
            .then().statusCode(200).body("EventSourceMappings.UUID", not(hasItem(uuid)));
        given().header("Authorization", auth(OTHER, "lambda"))
            .when().get("/2015-03-31/event-source-mappings/" + uuid)
            .then().statusCode(404);

        given().header("Authorization", auth(HOME, "lambda"))
            .when().delete("/2015-03-31/event-source-mappings/" + uuid).then().statusCode(202);
        given().header("Authorization", auth(HOME, "lambda"))
            .when().delete("/2015-03-31/functions/" + function).then().statusCode(204);
        given().header("Authorization", auth(HOME, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", "http://localhost:4566/000000000000/" + queue)
                .formParam("Version", "2012-11-05")
            .when().post("/").then().statusCode(200);
    }

    @Test
    void dynamoDbExportsStayInTheirRegion() {
        String table = "region-export-" + UUID.randomUUID().toString().substring(0, 8);
        String tableArn = given().header("Authorization", auth(HOME, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DYNAMODB_JSON)
                .body("""
                    {"TableName": "%s", "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                     "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                     "BillingMode": "PAY_PER_REQUEST"}
                    """.formatted(table))
            .when().post("/").then().statusCode(200)
            .extract().path("TableDescription.TableArn");
        // The bucket is never created: the export job is recorded before it writes anything, and a
        // missing bucket only fails the job later. Export jobs have no delete API.
        String exportArn = given().header("Authorization", auth(HOME, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
                .contentType(DYNAMODB_JSON)
                .body("""
                    {"TableArn": "%s", "S3Bucket": "region-export-no-bucket", "ExportFormat": "DYNAMODB_JSON"}
                    """.formatted(tableArn))
            .when().post("/").then().statusCode(200)
            .extract().path("ExportDescription.ExportArn");

        given().header("Authorization", auth(HOME, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.ListExports")
                .contentType(DYNAMODB_JSON).body("{}")
            .when().post("/").then().statusCode(200)
            .body("ExportSummaries.ExportArn", hasItem(exportArn));
        given().header("Authorization", auth(OTHER, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.ListExports")
                .contentType(DYNAMODB_JSON).body("{}")
            .when().post("/").then().statusCode(200)
            .body("ExportSummaries.ExportArn", not(hasItem(exportArn)));
        given().header("Authorization", auth(OTHER, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
                .contentType(DYNAMODB_JSON).body("{\"ExportArn\": \"" + exportArn + "\"}")
            .when().post("/").then().statusCode(400)
            .body("__type", endsWith("ExportNotFoundException"));

        given().header("Authorization", auth(HOME, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(DYNAMODB_JSON).body("{\"TableName\": \"" + table + "\"}")
            .when().post("/").then().statusCode(200);
    }

    @Test
    void dynamoDbImportClientTokensStayInTheirRegion() {
        String table = "region-import-" + UUID.randomUUID().toString().substring(0, 8);
        // The same request and ClientToken in two regions: each region starts its own import. The bucket is
        // never created, so each job fails later without touching S3.
        String request = """
            {"S3BucketSource": {"S3Bucket": "region-import-no-bucket"}, "InputFormat": "DYNAMODB_JSON",
             "ClientToken": "%s",
             "TableCreationParameters": {"TableName": "%s",
               "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
               "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
               "BillingMode": "PAY_PER_REQUEST"}}
            """.formatted(UUID.randomUUID(), table);
        String homeImportArn = importTable(HOME, request);
        String otherImportArn = importTable(OTHER, request);

        assertThat(otherImportArn, not(equalTo(homeImportArn)));
        given().header("Authorization", auth(OTHER, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.DescribeImport")
                .contentType(DYNAMODB_JSON).body("{\"ImportArn\": \"" + otherImportArn + "\"}")
            .when().post("/").then().statusCode(200)
            .body("ImportTableDescription.ImportArn", equalTo(otherImportArn));
        given().header("Authorization", auth(OTHER, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.ListImports")
                .contentType(DYNAMODB_JSON).body("{}")
            .when().post("/").then().statusCode(200)
            .body("ImportSummaryList.ImportArn", not(hasItem(homeImportArn)));

        for (String region : new String[] {HOME, OTHER}) {
            given().header("Authorization", auth(region, "dynamodb"))
                    .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                    .contentType(DYNAMODB_JSON).body("{\"TableName\": \"" + table + "\"}")
                .when().post("/");
        }
    }

    private static String importTable(String region, String request) {
        return given().header("Authorization", auth(region, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.ImportTable")
                .contentType(DYNAMODB_JSON).body(request)
            .when().post("/").then().statusCode(200)
            .extract().path("ImportTableDescription.ImportArn");
    }

    @Test
    void cognitoUserPoolsStayInTheirRegion() {
        String poolId = given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.CreateUserPool")
                .contentType(COGNITO_JSON)
                .body("{\"PoolName\": \"region-pool-" + UUID.randomUUID().toString().substring(0, 8) + "\"}")
            .when().post("/").then().statusCode(200)
            .extract().path("UserPool.Id");

        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.ListUserPools")
                .contentType(COGNITO_JSON).body("{\"MaxResults\": 60}")
            .when().post("/").then().statusCode(200)
            .body("UserPools.Id", hasItem(poolId));
        given().header("Authorization", auth(OTHER, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.ListUserPools")
                .contentType(COGNITO_JSON).body("{\"MaxResults\": 60}")
            .when().post("/").then().statusCode(200)
            .body("UserPools.Id", not(hasItem(poolId)));
        // A pool lives in the region its id names: another region cannot read or delete it.
        for (String action : new String[] {"DescribeUserPool", "AdminGetUser", "DeleteUserPool"}) {
            given().header("Authorization", auth(OTHER, "cognito-idp"))
                    .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + action)
                    .contentType(COGNITO_JSON)
                    .body("{\"UserPoolId\": \"" + poolId + "\", \"Username\": \"nobody\"}")
                .when().post("/").then().statusCode(400)
                .body("__type", endsWith("ResourceNotFoundException"));
        }
        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPool")
                .contentType(COGNITO_JSON).body("{\"UserPoolId\": \"" + poolId + "\"}")
            .when().post("/").then().statusCode(200)
            .body("UserPool.Id", equalTo(poolId));

        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DeleteUserPool")
                .contentType(COGNITO_JSON).body("{\"UserPoolId\": \"" + poolId + "\"}")
            .when().post("/").then().statusCode(200);
    }

    @Test
    void cognitoTagsAndDomainsStayInTheirPoolsRegion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonPath pool = given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.CreateUserPool")
                .contentType(COGNITO_JSON)
                .body("{\"PoolName\": \"region-tags-" + suffix + "\", \"UserPoolTags\": {\"team\": \"home\"}}")
            .when().post("/").then().statusCode(200)
            .extract().jsonPath();
        String poolId = pool.getString("UserPool.Id");
        String poolArn = pool.getString("UserPool.Arn");
        String domain = "region-domain-" + suffix;
        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.CreateUserPoolDomain")
                .contentType(COGNITO_JSON)
                .body("{\"UserPoolId\": \"" + poolId + "\", \"Domain\": \"" + domain + "\"}")
            .when().post("/").then().statusCode(200);

        // Another region cannot read or change the pool's tags, or describe its domain.
        given().header("Authorization", auth(OTHER, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.TagResource")
                .contentType(COGNITO_JSON)
                .body("{\"ResourceArn\": \"" + poolArn + "\", \"Tags\": {\"team\": \"other\"}}")
            .when().post("/").then().statusCode(400)
            .body("__type", endsWith("ResourceNotFoundException"));
        given().header("Authorization", auth(OTHER, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.UntagResource")
                .contentType(COGNITO_JSON)
                .body("{\"ResourceArn\": \"" + poolArn + "\", \"TagKeys\": [\"team\"]}")
            .when().post("/").then().statusCode(400)
            .body("__type", endsWith("ResourceNotFoundException"));
        given().header("Authorization", auth(OTHER, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.ListTagsForResource")
                .contentType(COGNITO_JSON).body("{\"ResourceArn\": \"" + poolArn + "\"}")
            .when().post("/").then().statusCode(400)
            .body("__type", endsWith("ResourceNotFoundException"));
        given().header("Authorization", auth(OTHER, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPoolDomain")
                .contentType(COGNITO_JSON).body("{\"Domain\": \"" + domain + "\"}")
            .when().post("/").then().statusCode(400)
            .body("__type", endsWith("ResourceNotFoundException"));

        // An ARN naming the pool's id under another region or account names another pool.
        String[] arn = poolArn.split(":", 6);
        String otherRegionArn = String.join(":", arn[0], arn[1], arn[2], OTHER, arn[4], arn[5]);
        String otherAccountArn = String.join(":", arn[0], arn[1], arn[2], arn[3], "111122223333", arn[5]);
        for (String foreignArn : List.of(otherRegionArn, otherAccountArn)) {
            given().header("Authorization", auth(HOME, "cognito-idp"))
                    .header("X-Amz-Target", "AWSCognitoIdentityProviderService.TagResource")
                    .contentType(COGNITO_JSON)
                    .body("{\"ResourceArn\": \"" + foreignArn + "\", \"Tags\": {\"team\": \"other\"}}")
                .when().post("/").then().statusCode(400)
                .body("__type", endsWith("ResourceNotFoundException"));
        }

        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.ListTagsForResource")
                .contentType(COGNITO_JSON).body("{\"ResourceArn\": \"" + poolArn + "\"}")
            .when().post("/").then().statusCode(200)
            .body("Tags.team", equalTo("home"));
        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPoolDomain")
                .contentType(COGNITO_JSON).body("{\"Domain\": \"" + domain + "\"}")
            .when().post("/").then().statusCode(200)
            .body("DomainDescription.UserPoolId", equalTo(poolId));

        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DeleteUserPoolDomain")
                .contentType(COGNITO_JSON)
                .body("{\"UserPoolId\": \"" + poolId + "\", \"Domain\": \"" + domain + "\"}")
            .when().post("/").then().statusCode(200);
        given().header("Authorization", auth(HOME, "cognito-idp"))
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DeleteUserPool")
                .contentType(COGNITO_JSON).body("{\"UserPoolId\": \"" + poolId + "\"}")
            .when().post("/").then().statusCode(200);
    }
}
