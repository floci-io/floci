package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/** {@code AWS::CloudFormation::StackSet} deploys its instances through StackSets, end to end. */
@QuarkusTest
class StackSetCfnIntegrationTest {

    private static final String MANAGEMENT = "676767676767";
    private static final String REGION = "us-east-1";
    private static final String ORG_TARGET = "AWSOrganizationsV20161128.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void serviceManagedStackSetResourceDeploysToItsTargetsAndFollowsUpdatesAndDeletion() throws Exception {
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}").then().statusCode(200);
        String rootId = organizations("ListRoots", "{}").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
        String targetOu = createOu(rootId, "CfnStackSetTarget");
        String otherOu = createOu(rootId, "CfnStackSetOther");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String targetAccount = createAccount("cfn-stackset-target-" + suffix + "@example.com", targetOu, rootId);
        String otherAccount = createAccount("cfn-stackset-other-" + suffix + "@example.com", otherOu, rootId);
        cloudFormation("ActivateOrganizationsAccess").then().statusCode(200);

        String stack = "stackset-resource-" + suffix;
        String stackSet = "cfn-stackset-" + suffix;
        String queue = "cfn-stackset-q-" + suffix;
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", template(stackSet, queue),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", targetOu)
                .then().statusCode(200);
        awaitStackStatus(stack, "CREATE_COMPLETE");

        String ref = output(stack, "StackSetRef");
        assertThat(ref, startsWith(stackSet + ":"));
        assertThat(output(stack, "StackSetId"), equalTo(ref));
        assertQueue(targetAccount, queue, true);
        assertQueue(otherAccount, queue, false);
        cloudFormation("DescribeStackSet", "StackSetName", stackSet).then().statusCode(200)
                .body(containsString("<PermissionModel>SERVICE_MANAGED</PermissionModel>"))
                .body(containsString("<Enabled>true</Enabled>"))
                .body(containsString("<ParameterValue>" + queue + "</ParameterValue>"))
                .body(containsString("<member>" + targetOu + "</member>"));

        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", template(stackSet, queue),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", otherOu)
                .then().statusCode(200);
        awaitStackStatus(stack, "UPDATE_COMPLETE");
        assertQueue(targetAccount, queue, false);
        assertQueue(otherAccount, queue, true);

        cloudFormation("DeleteStack", "StackName", stack).then().statusCode(200);
        await().atMost(Duration.ofSeconds(30)).pollInterval(200, TimeUnit.MILLISECONDS).until(() ->
                cloudFormation("DescribeStackSet", "StackSetName", stackSet).statusCode() == 404);
        assertQueue(otherAccount, queue, false);
    }

    @AfterEach
    void deleteOrganization() {
        if (organizations("DescribeOrganization", "{}").statusCode() != 200) {
            return;
        }
        JsonPath accounts = organizations("ListAccounts", "{}").jsonPath();
        for (String accountId : accounts.getList("Accounts.Id", String.class)) {
            if (!MANAGEMENT.equals(accountId)) {
                organizations("RemoveAccountFromOrganization", "{\"AccountId\":\"" + accountId + "\"}")
                        .then().statusCode(200);
            }
        }
        String rootId = organizations("ListRoots", "{}").jsonPath().getString("Roots[0].Id");
        List<String> units = organizations("ListOrganizationalUnitsForParent", "{\"ParentId\":\"" + rootId + "\"}")
                .jsonPath().getList("OrganizationalUnits.Id", String.class);
        for (String unit : units) {
            organizations("DeleteOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + unit + "\"}")
                    .then().statusCode(200);
        }
        organizations("DeleteOrganization", "{}").then().statusCode(200);
    }

    private static String template(String stackSet, String queue) throws JsonProcessingException {
        String instanceTemplate = "{\"Parameters\":{\"QueueName\":{\"Type\":\"String\"}},"
                + "\"Resources\":{\"Q\":{\"Type\":\"AWS::SQS::Queue\",\"Properties\":{\"QueueName\":{\"Ref\":\"QueueName\"}}}}}";
        return """
            {
              "Parameters": { "Targets": { "Type": "String" } },
              "Resources": {
                "StackSet": {
                  "Type": "AWS::CloudFormation::StackSet",
                  "Properties": {
                    "StackSetName": "%s",
                    "PermissionModel": "SERVICE_MANAGED",
                    "AutoDeployment": { "Enabled": true, "RetainStacksOnAccountRemoval": false },
                    "ManagedExecution": { "Active": true },
                    "Parameters": [ { "ParameterKey": "QueueName", "ParameterValue": "%s" } ],
                    "StackInstancesGroup": [ {
                      "DeploymentTargets": { "OrganizationalUnitIds": { "Fn::Split": [" ", { "Ref": "Targets" }] } },
                      "Regions": [ { "Ref": "AWS::Region" } ]
                    } ],
                    "TemplateBody": %s
                  }
                }
              },
              "Outputs": {
                "StackSetRef": { "Value": { "Ref": "StackSet" } },
                "StackSetId": { "Value": { "Fn::GetAtt": ["StackSet", "StackSetId"] } }
              }
            }
            """.formatted(stackSet, queue, new ObjectMapper().writeValueAsString(instanceTemplate));
    }

    private static String createOu(String parentId, String name) {
        return organizations("CreateOrganizationalUnit", "{\"ParentId\":\"" + parentId + "\",\"Name\":\"" + name + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("OrganizationalUnit.Id");
    }

    private static String createAccount(String email, String parentId, String rootId) {
        String accountId = organizations("CreateAccount", "{\"Email\":\"" + email + "\",\"AccountName\":\"CfnStackSet\"}")
                .then().statusCode(200).extract().jsonPath().getString("CreateAccountStatus.AccountId");
        organizations("MoveAccount", "{\"AccountId\":\"" + accountId + "\",\"SourceParentId\":\"" + rootId
                + "\",\"DestinationParentId\":\"" + parentId + "\"}").then().statusCode(200);
        return accountId;
    }

    private static void awaitStackStatus(String stack, String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(200, TimeUnit.MILLISECONDS).until(() ->
                expected.equals(cloudFormation("DescribeStacks", "StackName", stack).xmlPath().getString(
                        "DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus")));
    }

    private static String output(String stack, String key) {
        return cloudFormation("DescribeStacks", "StackName", stack).xmlPath().getString(
                "DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                        + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static void assertQueue(String account, String queue, boolean present) {
        given()
                .header("Authorization", auth(account, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queue)
                .post("/").then().statusCode(present ? 200 : 400);
    }

    private static Response cloudFormation(String action, String... formParams) {
        RequestSpecification request = given()
                .header("Authorization", auth(MANAGEMENT, "cloudformation"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/");
    }

    private static Response organizations(String action, String body) {
        return given()
                .header("Authorization", auth(MANAGEMENT, "organizations"))
                .header("X-Amz-Target", ORG_TARGET + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261101/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
