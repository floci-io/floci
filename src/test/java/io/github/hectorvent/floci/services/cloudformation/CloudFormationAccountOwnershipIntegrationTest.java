package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CloudFormationAccountOwnershipIntegrationTest {

    private static final String ACCOUNT_1 = "000000000001";
    private static final String ACCOUNT_2 = "000000000002";
    private static final String US_EAST_1 = "us-east-1";
    private static final String EU_WEST_1 = "eu-west-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sameStackNameIsIsolatedByAccount() {
        createStack(ACCOUNT_1, US_EAST_1, "cfn-account-isolated-stack");
        createStack(ACCOUNT_2, US_EAST_1, "cfn-account-isolated-stack");

        describeStack(ACCOUNT_1, US_EAST_1, "cfn-account-isolated-stack")
                .statusCode(200).body(containsString(ACCOUNT_1));
        describeStack(ACCOUNT_2, US_EAST_1, "cfn-account-isolated-stack")
                .statusCode(200).body(containsString(ACCOUNT_2));
    }

    @Test
    void sameStackNameIsIsolatedByRegion() {
        createStack(ACCOUNT_1, US_EAST_1, "cfn-region-isolated-stack");
        createStack(ACCOUNT_1, EU_WEST_1, "cfn-region-isolated-stack");

        describeStack(ACCOUNT_1, US_EAST_1, "cfn-region-isolated-stack")
                .statusCode(200).body(containsString(US_EAST_1));
        describeStack(ACCOUNT_1, EU_WEST_1, "cfn-region-isolated-stack")
                .statusCode(200).body(containsString(EU_WEST_1));
    }

    @Test
    void sameExportNameIsIsolatedByAccount() {
        createExportingStack(ACCOUNT_1, US_EAST_1, "cfn-export-account-one");
        createExportingStack(ACCOUNT_2, US_EAST_1, "cfn-export-account-two");

        listExports(ACCOUNT_1, US_EAST_1).statusCode(200)
                .body(containsString("cfn-shared-export"))
                .body(containsString(ACCOUNT_1))
                .body(not(containsString(ACCOUNT_2)));
        listExports(ACCOUNT_2, US_EAST_1).statusCode(200)
                .body(containsString("cfn-shared-export"))
                .body(containsString(ACCOUNT_2))
                .body(not(containsString(ACCOUNT_1)));
    }

    @Test
    void crossAccountReadAndUpdateDoNotChangeOwnerStack() {
        String ownerArn = createStack(ACCOUNT_1, US_EAST_1, "cfn-cross-account-mutation",
                "{\"Resources\":{\"Original\":{\"Type\":\"AWS::S3::Bucket\"}}}");

        describeStack(ACCOUNT_2, US_EAST_1, "cfn-cross-account-mutation")
                .statusCode(400)
                .body(containsString("does not exist"));
        given().header("Authorization", auth(ACCOUNT_2, US_EAST_1))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UpdateStack")
                .formParam("StackName", "cfn-cross-account-mutation")
                .formParam("TemplateBody", "{\"Resources\":{\"Changed\":{\"Type\":\"AWS::S3::Bucket\"}}}")
                .when().post("/").then().statusCode(400);
        given().header("Authorization", auth(ACCOUNT_2, US_EAST_1))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", "cfn-cross-account-mutation")
                .when().post("/").then().statusCode(200);
        given().header("Authorization", auth(ACCOUNT_2, US_EAST_1))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", ownerArn)
                .when().post("/").then().statusCode(400);
        describeStackResources(ACCOUNT_1, US_EAST_1, "cfn-cross-account-mutation")
                .statusCode(200)
                .body(containsString("Original"))
                .body(not(containsString("Changed")));
    }

    @Test
    void listStacksIsScopedToCallerAccount() {
        createStack(ACCOUNT_1, US_EAST_1, "cfn-list-scope-account-1");
        createStack(ACCOUNT_2, US_EAST_1, "cfn-list-scope-account-2");

        listStacks(ACCOUNT_1, US_EAST_1)
                .statusCode(200)
                .body(containsString("<StackName>cfn-list-scope-account-1</StackName>"))
                .body(not(containsString("<StackName>cfn-list-scope-account-2</StackName>")));
        listStacks(ACCOUNT_2, US_EAST_1)
                .statusCode(200)
                .body(containsString("<StackName>cfn-list-scope-account-2</StackName>"))
                .body(not(containsString("<StackName>cfn-list-scope-account-1</StackName>")));
    }

    @Test
    void assumedRoleCredentialsScopeListStacksAndDescribeStackResources() {
        createStack(ACCOUNT_1, US_EAST_1, "cfn-management-stack",
                "{\"Resources\":{\"ManagementBucket\":{\"Type\":\"AWS::S3::Bucket\"}}}");

        String memberAccessKeyId = given()
                .header("Authorization", auth(ACCOUNT_1, US_EAST_1, "sts"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT_2 + ":role/cfn-member-deploy-role")
                .formParam("RoleSessionName", "cfn-member-session")
                .when().post("/").then()
                .statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .extract().path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");

        createStack(memberAccessKeyId, US_EAST_1, "cfn-member-stack",
                "{\"Resources\":{\"MemberBucket\":{\"Type\":\"AWS::S3::Bucket\"}}}");

        listStacks(memberAccessKeyId, US_EAST_1)
                .statusCode(200)
                .body(containsString("<StackName>cfn-member-stack</StackName>"))
                .body(not(containsString("<StackName>cfn-management-stack</StackName>")));
        listStacks(ACCOUNT_1, US_EAST_1)
                .statusCode(200)
                .body(containsString("<StackName>cfn-management-stack</StackName>"))
                .body(not(containsString("<StackName>cfn-member-stack</StackName>")));

        describeStackResources(memberAccessKeyId, US_EAST_1, "cfn-member-stack")
                .statusCode(200)
                .body(containsString("MemberBucket"));
        describeStackResources(memberAccessKeyId, US_EAST_1, "cfn-management-stack")
                .statusCode(400)
                .body(containsString("does not exist"));
        describeStackResources(ACCOUNT_1, US_EAST_1, "cfn-member-stack")
                .statusCode(400)
                .body(containsString("does not exist"));
    }

    private static String createStack(String account, String region, String name) {
        return createStack(account, region, name, "{\"Resources\":{}}");
    }

    private static String createStack(String account, String region, String name, String template) {
        String body = given().header("Authorization", auth(account, region))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200).extract().body().asString();
        return body.replaceFirst("(?s).*<StackId>([^<]+)</StackId>.*", "$1");
    }

    private static void createExportingStack(String account, String region, String name) {
        String template = "{\"Outputs\":{\"Exported\":{\"Value\":\"" + account
                + "\",\"Export\":{\"Name\":\"cfn-shared-export\"}}}}";
        given().header("Authorization", auth(account, region))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse describeStack(
            String account, String region, String name) {
        return given().header("Authorization", auth(account, region))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", name)
                .when().post("/").then();
    }

    private static io.restassured.response.ValidatableResponse describeStackResources(
            String account, String region, String name) {
        return given().header("Authorization", auth(account, region))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", name)
                .when().post("/").then();
    }

    private static io.restassured.response.ValidatableResponse listStacks(String accessKeyId, String region) {
        return given().header("Authorization", auth(accessKeyId, region))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListStacks")
                .when().post("/").then();
    }

    private static io.restassured.response.ValidatableResponse listExports(String account, String region) {
        return given().header("Authorization", auth(account, region))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListExports")
                .when().post("/").then();
    }

    private static String auth(String accessKeyId, String region) {
        return auth(accessKeyId, region, "cloudformation");
    }

    private static String auth(String accessKeyId, String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                + "/20260907/" + region + "/" + service + "/aws4_request,"
                + " SignedHeaders=host, Signature=abc";
    }
}
