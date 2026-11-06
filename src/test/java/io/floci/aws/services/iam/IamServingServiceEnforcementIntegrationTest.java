package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamServingServiceEnforcementIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static final String ACCOUNT_ID = "000000000000";

    @Test
    void queryRequestCannotBorrowPermissionsFromItsSigningScope() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String caller = "query-scope-" + suffix;
        String attempted = "created-through-query-" + suffix;
        String accessKeyId = createUserWithPolicy(caller, "LambdaOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:*","Resource":"*"}
                ]}""");

        iamCall(accessKeyId, "lambda", "CreateUser", Map.of("UserName", attempted))
                .statusCode(403)
                .body(containsString("iam:CreateUser"));

        adminIam("GetUser", Map.of("UserName", attempted)).statusCode(404);
    }

    @Test
    void operationOnlyQueryRequestCannotBypassIamEnforcement() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String caller = "operation-scope-" + suffix;
        String attempted = "created-through-operation-" + suffix;
        String accessKeyId = createUserWithPolicy(caller, "LambdaOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:*","Resource":"*"}
                ]}""");

        iamOperationCall(accessKeyId, "lambda", "CreateUser", Map.of("UserName", attempted))
                .statusCode(403)
                .body(containsString("iam:CreateUser"));

        adminIam("GetUser", Map.of("UserName", attempted)).statusCode(404);
    }

    @Test
    void queryRequestCannotBorrowGetUserPermissionFromUrlAction() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String caller = "query-url-action-" + suffix;
        String attempted = "created-through-url-action-" + suffix;
        String accessKeyId = createUserWithPolicy(caller, "GetUserOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:GetUser","Resource":"*"}
                ]}""");

        given()
                .header("Authorization", auth(accessKeyId, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .queryParam("Action", "GetUser")
                .formParam("Action", "CreateUser")
                .formParam("Version", "2010-05-08")
                .formParam("UserName", attempted)
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("iam:CreateUser"));

        adminIam("GetUser", Map.of("UserName", attempted)).statusCode(404);
    }

    @Test
    void queryVersionMovesAuthorizationWithTheServiceItSelects() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String attempted = "version-denied-" + suffix;
        String accessKeyId = createUserWithPolicy("version-iam-only-" + suffix, "IamCreateUserOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:CreateUser","Resource":"*"}
                ]}""");

        elastiCacheCreateUser(accessKeyId, attempted)
                .statusCode(403)
                .body(containsString("elasticache:CreateUser"));

        adminIam("GetUser", Map.of("UserName", attempted)).statusCode(404);
        adminElastiCache("DescribeUsers", Map.of("UserId", attempted)).statusCode(404);
    }

    @Test
    void queryVersionReachesTheServiceItsPolicyAllows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String attempted = "version-allowed-" + suffix;
        String accessKeyId = createUserWithPolicy("version-cache-only-" + suffix, "CacheCreateUserOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"elasticache:CreateUser","Resource":"*"}
                ]}""");

        cleanup.register(() -> adminElastiCache("DeleteUser", Map.of("UserId", attempted)).statusCode(200));
        elastiCacheCreateUser(accessKeyId, attempted)
                .statusCode(200)
                .body("CreateUserResponse.CreateUserResult.UserId", equalTo(attempted));

        adminIam("GetUser", Map.of("UserName", attempted)).statusCode(404);
    }

    @Test
    void restRequestCannotBypassApiGatewayPolicyWithIamScope() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String caller = "rest-scope-" + suffix;
        String apiName = "blocked-api-" + suffix;
        String accessKeyId = createUserWithPolicy(caller, "IamOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"}
                ]}""");

        given()
                .header("Authorization", auth(accessKeyId, "iam"))
                .contentType("application/json")
                .body(Map.of("name", apiName))
        .when()
                .post("/restapis")
        .then()
                .statusCode(403)
                .body(containsString("apigateway:POST"));

        given()
                .header("Authorization", auth(ACCOUNT_ID, "apigateway"))
        .when()
                .get("/restapis")
        .then()
                .statusCode(200)
                .body(not(containsString(apiName)));
    }

    @Test
    void restPutCannotBorrowListBucketPermissionThroughAction() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "action-bypass-" + suffix;
        String accessKeyId = createUserWithPolicy("action-bypass-user-" + suffix,
                "ListBucketOnly", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"*"}
                ]}""");

        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                auth(ACCOUNT_ID, "s3"), bucket));
        given().header("Authorization", auth(ACCOUNT_ID, "s3"))
                .when().put("/" + bucket).then().statusCode(200);

        given().header("Authorization", auth(accessKeyId, "s3"))
                .queryParam("Action", "ListBucket")
                .body("query-spoof")
                .when().put("/" + bucket + "/from-query")
                .then().statusCode(403).body(containsString("s3:PutObject"));

        given().header("Authorization", auth(accessKeyId, "s3"))
                .contentType("application/x-www-form-urlencoded")
                .body("Action=ListBucket")
                .when().put("/" + bucket + "/from-form")
                .then().statusCode(403).body(containsString("s3:PutObject"));

        given().header("Authorization", auth(ACCOUNT_ID, "s3"))
                .when().get("/" + bucket + "/from-query")
                .then().statusCode(404);
        given().header("Authorization", auth(ACCOUNT_ID, "s3"))
                .when().get("/" + bucket + "/from-form")
                .then().statusCode(404);
    }

    private String createUserWithPolicy(String userName, String policyName, String policyDocument) {
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        adminIam("PutUserPolicy", Map.of(
                "UserName", userName,
                "PolicyName", policyName,
                "PolicyDocument", policyDocument)).statusCode(200);
        return accessKeyId;
    }

    private static ValidatableResponse adminIam(String action, Map<String, String> params) {
        return iamCall(ACCOUNT_ID, "iam", action, params);
    }

    private static ValidatableResponse iamCall(String accessKeyId, String scope, String action,
                                               Map<String, String> params) {
        return iamCall(accessKeyId, scope, "Action", action, params);
    }

    /**
     * An ElastiCache {@code CreateUser}, a name IAM also defines and takes by name. Signed under a
     * scope that is not a Query service, so the scope cannot decide and the {@code Version} has to.
     */
    private static ValidatableResponse elastiCacheCreateUser(String accessKeyId, String userId) {
        return given()
                .header("Authorization", auth(accessKeyId, "lambda"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateUser")
                .formParam("Version", "2015-02-02")
                .formParam("UserId", userId)
                .formParam("UserName", userId)
                .formParam("Engine", "redis")
                .formParam("AccessString", "on ~* +@all")
                .formParam("AuthenticationMode.Type", "no-password-required")
                .when().post("/").then();
    }

    private static ValidatableResponse adminElastiCache(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", auth(ACCOUNT_ID, "elasticache"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2015-02-02");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static ValidatableResponse iamOperationCall(String accessKeyId, String scope, String operation,
                                                        Map<String, String> params) {
        return iamCall(accessKeyId, scope, "Operation", operation, params);
    }

    private static ValidatableResponse iamCall(String accessKeyId, String scope, String operationName,
                                               String operation, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", auth(accessKeyId, scope))
                .contentType("application/x-www-form-urlencoded")
                .formParam(operationName, operation)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

}
