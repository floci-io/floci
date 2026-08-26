package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code AWS::Organizations::*} CloudFormation resource types end to end.
 *
 * <p>Every assertion goes through a stack {@code Output} backed by {@code Fn::GetAtt}. That is
 * deliberate: an unmapped attribute does not fail provisioning, it silently resolves to the
 * literal string {@code "LogicalId.Attr"}. Asserting {@code CREATE_COMPLETE}, or even that the
 * underlying resource exists, would pass just as happily with every attribute unwired.
 *
 * <p>Runs as its own management account so it never contends with the Organizations service
 * tests for the default account's organization.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsCfnIntegrationTest {

    private static final String MANAGEMENT_ACCOUNT = "666666666666";
    private static final String STACK_NAME = "organizations-stack";
    private static final String ORGANIZATIONS_TARGET = "AWSOrganizationsV20161128.";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    private static final String TEMPLATE = """
        {
          "Resources": {
            "Org": {
              "Type": "AWS::Organizations::Organization",
              "Properties": { "FeatureSet": "ALL" }
            },
            "Workloads": {
              "Type": "AWS::Organizations::OrganizationalUnit",
              "Properties": {
                "Name": "Workloads",
                "ParentId": { "Fn::GetAtt": ["Org", "RootId"] },
                "Tags": [ { "Key": "env", "Value": "test" } ]
              }
            },
            "Dev": {
              "Type": "AWS::Organizations::Account",
              "Properties": {
                "AccountName": "CfnDev",
                "Email": "cfn-dev@example.com",
                "ParentIds": [ { "Ref": "Workloads" } ]
              }
            },
            "Scp": {
              "Type": "AWS::Organizations::Policy",
              "Properties": {
                "Name": "CfnDenyEc2",
                "Type": "SERVICE_CONTROL_POLICY",
                "Description": "Deny EC2",
                "Content": {
                  "Version": "2012-10-17",
                  "Statement": [ { "Effect": "Deny", "Action": "ec2:*", "Resource": "*" } ]
                },
                "TargetIds": [ { "Ref": "Workloads" } ]
              }
            },
            "Rp": {
              "Type": "AWS::Organizations::ResourcePolicy",
              "Properties": {
                "Content": {
                  "Version": "2012-10-17",
                  "Statement": [ {
                    "Effect": "Allow",
                    "Principal": { "AWS": "*" },
                    "Action": "organizations:Describe*",
                    "Resource": "*"
                  } ]
                }
              }
            }
          },
          "Outputs": {
            "OrgId":          { "Value": { "Fn::GetAtt": ["Org", "Id"] } },
            "OrgArn":         { "Value": { "Fn::GetAtt": ["Org", "Arn"] } },
            "MgmtAccountId":  { "Value": { "Fn::GetAtt": ["Org", "ManagementAccountId"] } },
            "MgmtAccountArn": { "Value": { "Fn::GetAtt": ["Org", "ManagementAccountArn"] } },
            "MgmtEmail":      { "Value": { "Fn::GetAtt": ["Org", "ManagementAccountEmail"] } },
            "RootId":         { "Value": { "Fn::GetAtt": ["Org", "RootId"] } },
            "OrgRef":         { "Value": { "Ref": "Org" } },
            "OuId":           { "Value": { "Fn::GetAtt": ["Workloads", "Id"] } },
            "OuArn":          { "Value": { "Fn::GetAtt": ["Workloads", "Arn"] } },
            "OuPath":         { "Value": { "Fn::GetAtt": ["Workloads", "Path"] } },
            "OuRef":          { "Value": { "Ref": "Workloads" } },
            "AccountId":      { "Value": { "Fn::GetAtt": ["Dev", "AccountId"] } },
            "AccountArn":     { "Value": { "Fn::GetAtt": ["Dev", "Arn"] } },
            "AccountStatus":  { "Value": { "Fn::GetAtt": ["Dev", "Status"] } },
            "AccountState":   { "Value": { "Fn::GetAtt": ["Dev", "State"] } },
            "AccountPaths":   { "Value": { "Fn::GetAtt": ["Dev", "Paths"] } },
            "AccountPath0":   { "Value": { "Fn::Select": [0, { "Fn::GetAtt": ["Dev", "Paths"] }] } },
            "AccountJoined":  { "Value": { "Fn::GetAtt": ["Dev", "JoinedMethod"] } },
            "AccountJoinedAt":{ "Value": { "Fn::GetAtt": ["Dev", "JoinedTimestamp"] } },
            "PolicyId":       { "Value": { "Fn::GetAtt": ["Scp", "Id"] } },
            "PolicyArn":      { "Value": { "Fn::GetAtt": ["Scp", "Arn"] } },
            "PolicyAwsManaged": { "Value": { "Fn::GetAtt": ["Scp", "AwsManaged"] } },
            "ResourcePolicyId":  { "Value": { "Fn::GetAtt": ["Rp", "Id"] } },
            "ResourcePolicyArn": { "Value": { "Fn::GetAtt": ["Rp", "Arn"] } }
          }
        }
        """;

    private String organizationId;
    private String rootId;
    private String ouId;
    private String memberAccountId;
    private String policyId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String authorization() {
        return "AWS4-HMAC-SHA256 Credential=" + MANAGEMENT_ACCOUNT
                + "/20260823/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc";
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
                .header("Authorization", authorization())
                .header("X-Amz-Target", ORGANIZATIONS_TARGET + action)
                .contentType(JSON_1_1)
                .body(body)
                .when().post("/");
    }

    private static Response describeStacks() {
        return cloudFormation("DescribeStacks", "StackName", STACK_NAME);
    }

    /** Pulls a single {@code OutputValue} out of the DescribeStacks XML by its key. */
    private static String output(Response response, String key) {
        return response.xmlPath().getString(
                "DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                        + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static void awaitStackStatus(String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(200, TimeUnit.MILLISECONDS).until(() ->
                expected.equals(describeStacks().xmlPath().getString(
                        "DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus")));
    }

    @Test
    @Order(1)
    void createStackProvisionsEveryOrganizationsResourceType() {
        cloudFormation("CreateStack", "StackName", STACK_NAME, "TemplateBody", TEMPLATE)
                .then().statusCode(200);
        awaitStackStatus("CREATE_COMPLETE");
    }

    @Test
    @Order(2)
    void organizationAttributesResolve() {
        Response stacks = describeStacks();

        organizationId = output(stacks, "OrgId");
        rootId = output(stacks, "RootId");

        assertThat(organizationId, matchesRegex("o-[a-z0-9]{10}"));
        assertThat(rootId, matchesRegex("r-[a-z0-9]{4}"));
        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":organization/" + organizationId,
                output(stacks, "OrgArn"));
        assertEquals(MANAGEMENT_ACCOUNT, output(stacks, "MgmtAccountId"));
        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":account/" + organizationId
                + "/" + MANAGEMENT_ACCOUNT, output(stacks, "MgmtAccountArn"));
        assertThat(output(stacks, "MgmtEmail"), startsWith("master@"));
        // Organization is the one AWS type where Ref returns the management account id rather than
        // the resource's own id; Fn::GetAtt Org.Id above is what returns the organization id.
        assertEquals(MANAGEMENT_ACCOUNT, output(stacks, "OrgRef"));
    }

    @Test
    @Order(3)
    void organizationalUnitAttributesResolveAndRefIsTheOuId() {
        Response stacks = describeStacks();
        ouId = output(stacks, "OuId");

        assertThat(ouId, matchesRegex("ou-[a-z0-9]{4}-[a-z0-9]{8}"));
        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":ou/" + organizationId + "/" + ouId,
                output(stacks, "OuArn"));
        // Path runs from the organization down to and including the OU, trailing slash and all.
        assertEquals(organizationId + "/" + rootId + "/" + ouId + "/", output(stacks, "OuPath"));
        // Ref resolves to the physical id, which is what ParentIds/TargetIds referenced.
        assertEquals(ouId, output(stacks, "OuRef"));
    }

    @Test
    @Order(4)
    void accountAttributesResolve() {
        Response stacks = describeStacks();
        memberAccountId = output(stacks, "AccountId");

        assertThat(memberAccountId, matchesRegex("\\d{12}"));
        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":account/" + organizationId
                + "/" + memberAccountId, output(stacks, "AccountArn"));
        assertEquals("ACTIVE", output(stacks, "AccountStatus"));
        // State is the successor AWS is retiring Status in favour of; both are mapped.
        assertEquals("ACTIVE", output(stacks, "AccountState"));
        assertEquals("CREATED", output(stacks, "AccountJoined"));
        assertThat(output(stacks, "AccountJoinedAt"), matchesRegex("\\d{4}-\\d{2}-\\d{2}T.*Z"));

        // Paths names the OU that ParentIds moved the account into, so it is read after the move
        // rather than off the freshly created account still sitting under the root.
        String path = organizationId + "/" + rootId + "/" + ouId + "/" + memberAccountId + "/";
        assertEquals(path, output(stacks, "AccountPaths"));
        // Paths is list-typed in the registry schema, and Fn::Select is how a template reads one.
        assertEquals(path, output(stacks, "AccountPath0"));
    }

    @Test
    @Order(5)
    void policyAndResourcePolicyAttributesResolve() {
        Response stacks = describeStacks();
        policyId = output(stacks, "PolicyId");

        assertThat(policyId, matchesRegex("p-[a-z0-9]{8}"));
        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":policy/" + organizationId
                + "/service_control_policy/" + policyId, output(stacks, "PolicyArn"));
        assertEquals("false", output(stacks, "PolicyAwsManaged"));

        assertThat(output(stacks, "ResourcePolicyId"), startsWith("rp-"));
        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":resourcepolicy/" + organizationId
                        + "/" + output(stacks, "ResourcePolicyId"),
                output(stacks, "ResourcePolicyArn"));
    }

    @Test
    @Order(6)
    void theProvisionedResourcesAreRealAndWiredTogether() {
        organizations("DescribeOrganization", "{}")
                .then().statusCode(200)
                .body("Organization.Id", equalTo(organizationId))
                .body("Organization.FeatureSet", equalTo("ALL"));

        // ParentIds moved the account out of the root and into the OU.
        organizations("ListAccountsForParent", "{\"ParentId\":\"" + ouId + "\"}")
                .then().statusCode(200)
                .body("Accounts.Id", hasItem(memberAccountId));

        // TargetIds attached the SCP to the OU.
        organizations("ListPoliciesForTarget",
                "{\"TargetId\":\"" + ouId + "\",\"Filter\":\"SERVICE_CONTROL_POLICY\"}")
                .then().statusCode(200)
                .body("Policies.Id", hasItem(policyId));

        // Tags on the OU came through the template.
        organizations("ListTagsForResource", "{\"ResourceId\":\"" + ouId + "\"}")
                .then().statusCode(200)
                .body("Tags[0].Key", equalTo("env"))
                .body("Tags[0].Value", equalTo("test"));

        organizations("DescribeResourcePolicy", "{}")
                .then().statusCode(200)
                .body("ResourcePolicy.Content", org.hamcrest.Matchers.containsString("organizations:Describe*"));
    }

    @Test
    @Order(7)
    void updateStackRenamesInPlaceRatherThanRecreating() {
        String updated = TEMPLATE.replace("\"Name\": \"Workloads\"", "\"Name\": \"Renamed\"");

        cloudFormation("UpdateStack", "StackName", STACK_NAME, "TemplateBody", updated)
                .then().statusCode(200);
        awaitStackStatus("UPDATE_COMPLETE");

        // The physical id must survive the update — a recreated OU would orphan the old one and
        // break every Ref that pointed at it.
        assertEquals(ouId, output(describeStacks(), "OuId"));
        organizations("DescribeOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
                .then().statusCode(200)
                .body("OrganizationalUnit.Name", equalTo("Renamed"));

        organizations("ListAccounts", "{}")
                .then().statusCode(200)
                .body("Accounts.Id", hasItem(memberAccountId));
    }

    @Test
    @Order(8)
    void deleteStackTearsTheOrganizationDown() {
        cloudFormation("DeleteStack", "StackName", STACK_NAME).then().statusCode(200);

        await().atMost(Duration.ofSeconds(30)).pollInterval(200, TimeUnit.MILLISECONDS).until(() ->
                organizations("DescribeOrganization", "{}").statusCode() == 400);

        organizations("DescribeOrganization", "{}")
                .then().statusCode(400)
                .body("__type", equalTo("AWSOrganizationsNotInUseException"));
    }

    @Test
    @Order(9)
    void theManagementAccountIsFreeToStartOver() {
        // The teardown must leave no organization behind for this account, otherwise a second
        // stack — or a re-run of this test class — would hit AlreadyInOrganizationException.
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .then().statusCode(200)
                .body("Organization.Id", not(equalTo(organizationId)));

        organizations("DeleteOrganization", "{}").then().statusCode(200);
    }
}
