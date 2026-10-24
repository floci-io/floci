package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The account access role CreateAccount preconfigures in each member account, end to end as
 * the issue reports it: create the organization, create the account, then assume the new
 * account's OrganizationAccountAccessRole with STS.
 *
 * <p>Runs as its own management account, like {@link OrganizationsCrossAccountIntegrationTest},
 * so it never contends for one with the other Organizations suites.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsAccountAccessRoleIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";

    private static final String MANAGEMENT_ACCOUNT = "666666666666";

    private String memberAccountId;
    private String createAccountRequestId;
    private String customRoleAccountId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private RequestSpecification organizations(String action, String body) {
        return given()
                .header("Authorization", authorization(MANAGEMENT_ACCOUNT, "organizations"))
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }

    private RequestSpecification sts(String roleArn) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization(MANAGEMENT_ACCOUNT, "sts"))
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "management-check");
    }

    private RequestSpecification iamAsMember(String accountId, String action, String roleName) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization(accountId, "iam"))
                .formParam("Action", action)
                .formParam("RoleName", roleName);
    }

    private static String authorization(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20261024/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    @Test
    @Order(1)
    void createOrganizationAsManagementAccount() {
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.MasterAccountId", equalTo(MANAGEMENT_ACCOUNT));
    }

    @Test
    @Order(2)
    void createAccountReportsSuccess() {
        JsonPath response = organizations("CreateAccount",
                "{\"Email\":\"member@example.com\",\"AccountName\":\"Member\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
            .body("CreateAccountStatus.AccountId", matchesPattern("\\d{12}"))
            .extract().jsonPath();

        memberAccountId = response.getString("CreateAccountStatus.AccountId");
        createAccountRequestId = response.getString("CreateAccountStatus.Id");
    }

    @Test
    @Order(3)
    void describeCreateAccountStatusStillReportsSucceeded() {
        organizations("DescribeCreateAccountStatus",
                "{\"CreateAccountRequestId\":\"" + createAccountRequestId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
            .body("CreateAccountStatus.AccountId", equalTo(memberAccountId));
    }

    @Test
    @Order(4)
    void theManagementAccountCanAssumeThePreconfiguredRole() {
        sts("arn:aws:iam::" + memberAccountId + ":role/OrganizationAccountAccessRole")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", notNullValue())
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.SecretAccessKey", notNullValue())
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken", notNullValue())
            .body("AssumeRoleResponse.AssumeRoleResult.Credentials.Expiration", notNullValue())
            .body("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn",
                    equalTo("arn:aws:sts::" + memberAccountId
                            + ":assumed-role/OrganizationAccountAccessRole/management-check"));
    }

    @Test
    @Order(5)
    void theRoleLivesInTheMemberAccountAndTrustsTheManagementAccount() {
        iamAsMember(memberAccountId, "GetRole", "OrganizationAccountAccessRole")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetRoleResponse.GetRoleResult.Role.Arn",
                    equalTo("arn:aws:iam::" + memberAccountId + ":role/OrganizationAccountAccessRole"))
            .body("GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument",
                    containsString("\"Principal\":{\"AWS\":\"arn:aws:iam::" + MANAGEMENT_ACCOUNT + ":root\"}"))
            .body("GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument",
                    containsString("sts:AssumeRole"));

        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization(memberAccountId, "iam"))
                .formParam("Action", "ListAttachedRolePolicies")
                .formParam("RoleName", "OrganizationAccountAccessRole")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult"
                    + ".AttachedPolicies.member.PolicyName", equalTo("AdministratorAccess"))
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult"
                    + ".AttachedPolicies.member.PolicyArn",
                    equalTo("arn:aws:iam::aws:policy/AdministratorAccess"));

        iamAsMember(MANAGEMENT_ACCOUNT, "GetRole", "OrganizationAccountAccessRole")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(6)
    void createAccountWithACustomRoleNameProvisionsThatName() {
        customRoleAccountId = organizations("CreateAccount",
                "{\"Email\":\"custom@example.com\",\"AccountName\":\"Custom\",\"RoleName\":\"TeamAccessRole\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
            .extract().jsonPath().getString("CreateAccountStatus.AccountId");

        iamAsMember(customRoleAccountId, "GetRole", "TeamAccessRole")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetRoleResponse.GetRoleResult.Role.Arn",
                    equalTo("arn:aws:iam::" + customRoleAccountId + ":role/TeamAccessRole"));

        sts("arn:aws:iam::" + customRoleAccountId + ":role/TeamAccessRole")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn",
                    equalTo("arn:aws:sts::" + customRoleAccountId
                            + ":assumed-role/TeamAccessRole/management-check"));
    }

    @Test
    @Order(7)
    void anInvalidRoleNameIsRejected() {
        organizations("CreateAccount",
                "{\"Email\":\"bad@example.com\",\"AccountName\":\"Bad\",\"RoleName\":\"AWSServiceRoleForOrganizations\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(90)
    void tearDownOrganization() {
        organizations("RemoveAccountFromOrganization", "{\"AccountId\":\"" + memberAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("RemoveAccountFromOrganization", "{\"AccountId\":\"" + customRoleAccountId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations("DeleteOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
