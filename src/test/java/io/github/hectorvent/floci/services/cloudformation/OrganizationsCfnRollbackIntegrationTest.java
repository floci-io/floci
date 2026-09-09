package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rollback of an {@code AWS::Organizations::Account} that fails <em>after</em> it was created.
 *
 * <p>{@code CreateAccount} succeeds and makes the account a real member of the organization, then
 * {@code MoveAccount} raises {@code DestinationParentNotFoundException} because {@code ParentIds}
 * names an OU that does not exist. Recovering from that needs two things from the provisioner, and
 * missing either one strands the account: a physical id, and the
 * {@code CfnRollback.ROLLBACK_OWNED_ATTR} marker. {@code CloudFormationService} only deletes a
 * CREATE_FAILED resource when it has both, so without them rollback silently walks past the
 * account, then fails to delete the organization it still belongs to and reports ROLLBACK_FAILED.
 *
 * <p>Runs as its own management account so it never contends for an organization with the other
 * Organizations suites.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsCfnRollbackIntegrationTest {

    private static final String MANAGEMENT_ACCOUNT = "676767676767";
    private static final String STACK_NAME = "organizations-rollback-stack";
    private static final String ORGANIZATIONS_TARGET = "AWSOrganizationsV20161128.";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    /** ParentIds names a well-formed OU id that was never created. */
    private static final String TEMPLATE = """
        {
          "Resources": {
            "Org": {
              "Type": "AWS::Organizations::Organization",
              "Properties": { "FeatureSet": "ALL" }
            },
            "Stranded": {
              "Type": "AWS::Organizations::Account",
              "DependsOn": "Org",
              "Properties": {
                "AccountName": "stranded",
                "Email": "stranded@example.com",
                "ParentIds": [ "ou-zzzz-nosuchou1" ]
              }
            }
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response cloudFormation(String action, String... formParams) {
        var request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + MANAGEMENT_ACCOUNT
                        + "/20260823/us-east-1/cloudformation/aws4_request, SignedHeaders=host, Signature=abc")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/");
    }

    private static Response organizations(String action, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + MANAGEMENT_ACCOUNT
                        + "/20260823/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", ORGANIZATIONS_TARGET + action)
                .contentType(JSON_1_1)
                .body(body)
                .when().post("/");
    }

    private static String stackStatus() {
        return cloudFormation("DescribeStacks", "StackName", STACK_NAME).xmlPath().getString(
                "DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus");
    }

    @Test
    void anAccountThatCannotBeMovedIsDeletedByRollbackRatherThanStranded() {
        cloudFormation("CreateStack", "StackName", STACK_NAME, "TemplateBody", TEMPLATE)
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(30)).pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    String status = stackStatus();
                    return status != null && (status.endsWith("_COMPLETE") || status.endsWith("_FAILED"))
                            && !status.endsWith("IN_PROGRESS");
                });

        // ROLLBACK_FAILED here means rollback could not unwind the attempt — the account survived
        // and kept the organization alive with it.
        assertEquals("ROLLBACK_COMPLETE", stackStatus());

        Response events = cloudFormation("DescribeStackEvents", "StackName", STACK_NAME);
        List<String> strandedStatuses = events.xmlPath().getList(
                "DescribeStackEventsResponse.DescribeStackEventsResult.StackEvents.member"
                        + ".findAll { it.LogicalResourceId == 'Stranded' }.ResourceStatus");
        List<String> strandedIds = events.xmlPath().getList(
                "DescribeStackEventsResponse.DescribeStackEventsResult.StackEvents.member"
                        + ".findAll { it.LogicalResourceId == 'Stranded' }.PhysicalResourceId");

        // The account id has to reach the stack's own records, or rollback has nothing to delete.
        assertTrue(strandedIds.stream().anyMatch(id -> id != null && id.matches("\\d{12}")),
                "no account id was recorded for the failed resource: " + strandedIds);
        // And rollback has to actually act on it rather than walk past a CREATE_FAILED resource.
        assertTrue(strandedStatuses.contains("DELETE_COMPLETE"),
                "rollback never deleted the created account; events were " + strandedStatuses);

        // Nothing survives the unwind: no member account, and so the organization deletes cleanly.
        assertEquals("AWSOrganizationsNotInUseException",
                organizations("DescribeOrganization", "{}").jsonPath().getString("__type"));
        assertNull(organizations("ListAccounts", "{}").jsonPath().getString("Accounts"));
    }
}
