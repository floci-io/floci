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
import static org.hamcrest.Matchers.startsWith;

/**
 * Proves that {@code AssumeRole} session tags become {@code aws:PrincipalTag} keys on the
 * session's requests, survive a role chain when they are transitive, and expose the session's
 * source identity as {@code aws:SourceIdentity}.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsSessionTagEnforcementIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";
    private static final String TAG_TRUST_POLICY = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {"Effect": "Allow", "Principal": {"AWS": "*"},
                 "Action": ["sts:AssumeRole", "sts:TagSession"]}
              ]
            }
            """;
    private static final String ASSUME_ONLY_TRUST_POLICY = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {"Effect": "Allow", "Principal": {"AWS": "*"}, "Action": "sts:AssumeRole"}
              ]
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aSessionTagAllowsTheDataActionAndAnAbsentOrDifferentTagDeniesIt() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String table = createTable("session-tags-" + suffix);
        String role = createRoleWithPolicy("SessionTagRole" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem"],"Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalTag/team":"blue"}}}]}
                """);

        String blue = assumeRole(role, "team", "blue", null);
        putItem(blue, table, "item-1", 200);
        getItem(blue, table, 200);

        String untagged = assumeRole(role, null, null, null);
        getItem(untagged, table, 400);

        String red = assumeRole(role, "team", "red", null);
        getItem(red, table, 400);
    }

    @Test
    void sourceIdentityIsVisibleAsAConditionKey() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String table = createTable("session-source-" + suffix);
        String role = createRoleWithPolicy("SourceIdentityRole" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"StringEquals":{"aws:SourceIdentity":"alice"}}}]}
                """);

        String named = assumeRole(role, null, null, "alice");
        putItemAsAccount(ROLE_ACCOUNT_ID, table, "item-1");
        getItem(named, table, 200);

        String unnamed = assumeRole(role, null, null, null);
        getItem(unnamed, table, 400);
    }

    @Test
    void tagsRequireTagSessionInTheTrustPolicy() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = createRoleWithTrustAndPolicy("AssumeOnlyRole" + suffix, ASSUME_ONLY_TRUST_POLICY, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*"}]}
                """);

        given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + role)
                .formParam("RoleSessionName", "session-tag-test")
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "blue")
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("AccessDenied"))
                .body(containsString("sts:TagSession"));

        assumeRole(role, null, null, null);
    }

    @Test
    void theTagSessionTrustCheckSeesTheRequestTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trust = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
                  {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:TagSession",
                   "Condition":{"StringEquals":{"aws:RequestTag/team":"blue"}}}]}
                """;
        String role = createRoleWithTrustAndPolicy("TagSessionConditionRole" + suffix, trust, """
                {"Version":"2012-10-17","Statement":[]}
                """);

        assumeRole(role, "team", "blue", null);
        given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + role)
                .formParam("RoleSessionName", "session-tag-test")
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "red")
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("AccessDenied"));
    }

    @Test
    void aChainedSessionCannotReuseAnInheritedTransitiveTagKey() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String firstRole = createRoleWithPolicy("ReuseFirstRole" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["sts:AssumeRole","sts:TagSession"],"Resource":"*"}]}
                """);
        String secondRole = createRoleWithPolicy("ReuseSecondRole" + suffix, """
                {"Version":"2012-10-17","Statement":[]}
                """);
        String firstSession = assumeRole(firstRole, "team", "blue", null, "team");

        for (String key : new String[]{"team", "TEAM"}) {
            given()
                    .formParam("Action", "AssumeRole")
                    .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + secondRole)
                    .formParam("RoleSessionName", "session-tag-test")
                    .formParam("Tags.member.1.Key", key)
                    .formParam("Tags.member.1.Value", "red")
                    .header("Authorization", auth(firstSession, "sts"))
            .when()
                    .post("/")
            .then()
                    .statusCode(400)
                    .body(containsString("ValidationError"))
                    .body(containsString("transitive session tag"));
        }
    }

    @Test
    void aTransitiveSessionTagSurvivesARoleChain() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String table = createTable("session-chain-" + suffix);
        putItemAsAccount(ROLE_ACCOUNT_ID, table, "item-1");
        String firstRole = createRoleWithPolicy("ChainFirstRole" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalTag/team":"blue"}}}]}
                """);
        String secondRole = createRoleWithPolicy("ChainSecondRole" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalTag/team":"blue"}}}]}
                """);

        String firstSession = assumeRole(firstRole, "team", "blue", null, "team");
        String secondSession = assumeRoleAs(firstSession, secondRole, null, null, null);

        getItem(secondSession, table, 200);
    }

    @Test
    void aNonTransitiveSessionTagDoesNotSurviveARoleChain() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String table = createTable("session-chain-private-" + suffix);
        putItemAsAccount(ROLE_ACCOUNT_ID, table, "item-1");
        String firstRole = createRoleWithPolicy("PrivateFirstRole" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalTag/team":"blue"}}}]}
                """);
        String secondRole = createRoleWithPolicy("PrivateSecondRole" + suffix, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalTag/team":"blue"}}}]}
                """);

        String firstSession = assumeRole(firstRole, "team", "blue", null);
        String secondSession = assumeRoleAs(firstSession, secondRole, null, null, null);

        getItem(secondSession, table, 400);
    }

    private static String createTable(String tableName) {
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
        return tableName;
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

    private static void getItem(String accessKey, String tableName, int status) {
        ValidatableResponse response = given()
                .header("Authorization", auth(accessKey, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"PK":{"S":"item-1"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("AccessDeniedException"));
        }
    }

    private static String createRoleWithPolicy(String roleName, String policyDocument) {
        return createRoleWithTrustAndPolicy(roleName, TAG_TRUST_POLICY, policyDocument);
    }

    private static String createRoleWithTrustAndPolicy(String roleName, String trustPolicy,
                                                       String policyDocument) {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", trustPolicy)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
        given()
                .formParam("Action", "PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "SessionTagPolicy")
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
        return roleName;
    }

    private static String assumeRole(String roleName, String tagKey, String tagValue, String sourceIdentity) {
        return assumeRole(roleName, tagKey, tagValue, sourceIdentity, null);
    }

    private static String assumeRole(String roleName, String tagKey, String tagValue,
                                     String sourceIdentity, String transitiveTagKey) {
        return assumeRoleAs(CALLER_ACCOUNT_ID, roleName, tagKey, tagValue, sourceIdentity, transitiveTagKey);
    }

    private static String assumeRoleAs(String callerAccessKey, String roleName, String tagKey, String tagValue,
                                       String sourceIdentity) {
        return assumeRoleAs(callerAccessKey, roleName, tagKey, tagValue, sourceIdentity, null);
    }

    private static String assumeRoleAs(String callerAccessKey, String roleName, String tagKey, String tagValue,
                                       String sourceIdentity, String transitiveTagKey) {
        RequestSpecification request = given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "session-tag-test")
                .header("Authorization", auth(callerAccessKey, "sts"));
        if (tagKey != null) {
            request.formParam("Tags.member.1.Key", tagKey);
            request.formParam("Tags.member.1.Value", tagValue);
        }
        if (transitiveTagKey != null) {
            request.formParam("TransitiveTagKeys.member.1", transitiveTagKey);
        }
        if (sourceIdentity != null) {
            request.formParam("SourceIdentity", sourceIdentity);
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
