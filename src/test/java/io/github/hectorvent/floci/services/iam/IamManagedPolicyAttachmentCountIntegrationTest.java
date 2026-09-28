package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class IamManagedPolicyAttachmentCountIntegrationTest {

    private static final String FIRST_ACCOUNT = "730000000421";
    private static final String SECOND_ACCOUNT = "730000000422";
    private static final String POLICY_NAME = "AmazonS3ReadOnlyAccess";
    private static final String POLICY_ARN = "arn:aws:iam::aws:policy/" + POLICY_NAME;
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;

    @Test
    void managedPolicyCountsFollowAttachmentsWithinEachAccount() {
        String name = "attachment-count-" + Long.toString(System.nanoTime(), 36);
        try {
            for (String accountId : List.of(FIRST_ACCOUNT, SECOND_ACCOUNT)) {
                iam(accountId, "CreateUser", Map.of("UserName", name)).then().statusCode(200)
                        .body("CreateUserResponse.CreateUserResult.User.Arn",
                                equalTo("arn:aws:iam::" + accountId + ":user/" + name));
                iam(accountId, "CreateGroup", Map.of("GroupName", name)).then().statusCode(200);
                iam(accountId, "CreateRole", Map.of("RoleName", name,
                        "AssumeRolePolicyDocument", TRUST_POLICY)).then().statusCode(200);
            }
            assertCounts(0, 0);

            changeAttachment(FIRST_ACCOUNT, "Attach", "User", name);
            changeAttachment(FIRST_ACCOUNT, "Attach", "User", name);
            assertCounts(1, 0);
            changeAttachment(FIRST_ACCOUNT, "Attach", "Group", name);
            assertCounts(2, 0);
            changeAttachment(FIRST_ACCOUNT, "Attach", "Role", name);
            assertCounts(3, 0);

            changeAttachment(SECOND_ACCOUNT, "Attach", "User", name);
            assertCounts(3, 1);
            for (String entityType : List.of("User", "Group", "Role")) {
                assertAttachedPolicy(FIRST_ACCOUNT, entityType, name);
            }
            assertAttachedPolicy(SECOND_ACCOUNT, "User", name);

            changeAttachment(FIRST_ACCOUNT, "Detach", "User", name);
            assertCounts(2, 1);
            changeAttachment(FIRST_ACCOUNT, "Detach", "Group", name);
            assertCounts(1, 1);
            changeAttachment(FIRST_ACCOUNT, "Detach", "Role", name);
            assertCounts(0, 1);
            changeAttachment(SECOND_ACCOUNT, "Detach", "User", name);
            assertCounts(0, 0);
        } finally {
            for (String accountId : List.of(FIRST_ACCOUNT, SECOND_ACCOUNT)) {
                for (String entityType : List.of("User", "Group", "Role")) {
                    cleanup(accountId, "Detach" + entityType + "Policy",
                            Map.of(entityType + "Name", name, "PolicyArn", POLICY_ARN));
                    cleanup(accountId, "Delete" + entityType, Map.of(entityType + "Name", name));
                }
            }
        }
    }

    private void assertCounts(int firstAccountCount, int secondAccountCount) {
        assertCount(FIRST_ACCOUNT, firstAccountCount);
        assertCount(SECOND_ACCOUNT, secondAccountCount);
    }

    private void assertCount(String accountId, int expectedCount) {
        iam(accountId, "GetPolicy", Map.of("PolicyArn", POLICY_ARN)).then().statusCode(200)
                .body("GetPolicyResponse.GetPolicyResult.Policy.AttachmentCount",
                        equalTo(Integer.toString(expectedCount)));
        iam(accountId, "ListPolicies", Map.of("Scope", "AWS")).then().statusCode(200)
                .body("ListPoliciesResponse.ListPoliciesResult.Policies.member.find { it.Arn == '"
                                + POLICY_ARN + "' }.AttachmentCount",
                        equalTo(Integer.toString(expectedCount)));
    }

    private void changeAttachment(String accountId, String action, String entityType, String name) {
        iam(accountId, action + entityType + "Policy",
                Map.of(entityType + "Name", name, "PolicyArn", POLICY_ARN)).then().statusCode(200);
    }

    private void assertAttachedPolicy(String accountId, String entityType, String name) {
        String action = "ListAttached" + entityType + "Policies";
        String result = action + "Response." + action + "Result.AttachedPolicies.member";
        String response = iam(accountId, action, Map.of(entityType + "Name", name)).then().statusCode(200)
                .body(result + ".size()", equalTo(1))
                .body(result + ".PolicyName", equalTo(POLICY_NAME))
                .body(result + ".PolicyArn", equalTo(POLICY_ARN))
                .extract().asString();
        assertEquals(List.of("PolicyName", "PolicyArn"), XmlParser.childElementNames(response, "member"));
    }

    private void cleanup(String accountId, String action, Map<String, String> parameters) {
        Response response = iam(accountId, action, parameters);
        if (response.statusCode() == 404) {
            // Creation may have failed before this principal existed.
            response.then().body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
        } else {
            response.then().statusCode(200);
        }
    }

    private Response iam(String accountId, String action, Map<String, String> parameters) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accountId
                        + "/20260923/us-east-1/iam/aws4_request")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08")
                .formParams(parameters)
                .when().post("/");
    }
}
