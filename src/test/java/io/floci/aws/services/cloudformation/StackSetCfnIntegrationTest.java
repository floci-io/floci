package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.OrganizationsFixture;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code AWS::CloudFormation::StackSet} deploys its instances through StackSets, end to end. */
@QuarkusTest
class StackSetCfnIntegrationTest {

    private static final String MANAGEMENT = "676767676767";
    private static final String REGION = "us-east-1";
    private final OrganizationsFixture organizationsFixture = new OrganizationsFixture();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void serviceManagedStackSetResourceDeploysToItsTargetsAndFollowsUpdatesAndDeletion() throws Exception {
        String rootId = organizationsFixture.createWithTrustedAccess(MANAGEMENT);
        String targetOu = createOu(rootId, "CfnStackSetTarget");
        String otherOu = createOu(rootId, "CfnStackSetOther");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String targetAccount = createAccount("cfn-stackset-target-" + suffix + "@example.com", targetOu, rootId);
        String otherAccount = createAccount("cfn-stackset-other-" + suffix + "@example.com", otherOu, rootId);

        String stack = "stackset-resource-" + suffix;
        String stackSet = "cfn-stackset-" + suffix;
        String queue = "cfn-stackset-q-" + suffix;
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", template(stackSet, queue),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", targetOu)
                .then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));

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
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        assertQueue(targetAccount, queue, false);
        assertQueue(otherAccount, queue, true);

        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DescribeStackSet", "StackSetName", stackSet).then().statusCode(404);
        assertQueue(otherAccount, queue, false);
    }

    @Test
    void anUpdateRemovesTheInstancesOfARegionDroppedFromAKeptTarget() throws Exception {
        String rootId = organizationsFixture.createWithTrustedAccess(MANAGEMENT);
        String targetOu = createOu(rootId, "CfnStackSetRegions");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String account = createAccount("cfn-stackset-regions-" + suffix + "@example.com", targetOu, rootId);

        String stack = "stackset-regions-" + suffix;
        String stackSet = "cfn-stackset-regions-" + suffix;
        String queue = "cfn-stackset-regions-q-" + suffix;
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", template(stackSet, queue),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", targetOu,
                "Parameters.member.2.ParameterKey", "Regions", "Parameters.member.2.ParameterValue", REGION + ",us-west-2")
                .then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        assertQueue(account, queue, REGION, true);
        assertQueue(account, queue, "us-west-2", true);

        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", template(stackSet, queue),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", targetOu,
                "Parameters.member.2.ParameterKey", "Regions", "Parameters.member.2.ParameterValue", REGION)
                .then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        assertQueue(account, queue, REGION, true);
        assertQueue(account, queue, "us-west-2", false);
        cloudFormation("ListStackInstances", "StackSetName", stackSet).then().statusCode(200)
                .body(containsString("<Region>" + REGION + "</Region>"))
                .body(not(containsString("<Region>us-west-2</Region>")));
    }

    @Test
    void anUpdateDroppingATargetWithNoInstancesStopsDeployingToIt() throws Exception {
        String rootId = organizationsFixture.createWithTrustedAccess(MANAGEMENT);
        String emptyOu = createOu(rootId, "CfnStackSetEmpty");
        String otherOu = createOu(rootId, "CfnStackSetOtherEmpty");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stack = "stackset-empty-" + suffix;
        String stackSet = "cfn-stackset-empty-" + suffix;
        String queue = "cfn-stackset-empty-q-" + suffix;
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", template(stackSet, queue),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", emptyOu)
                .then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", template(stackSet, queue),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", otherOu)
                .then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));

        String account = createAccount("cfn-stackset-empty-" + suffix + "@example.com", emptyOu, rootId);
        assertQueue(account, queue, false);
        cloudFormation("DescribeStackSet", "StackSetName", stackSet).then().statusCode(200)
                .body(not(containsString("<member>" + emptyOu + "</member>")))
                .body(containsString("<member>" + otherOu + "</member>"));
    }

    @Test
    void missingPermissionModelFailsStackCreation() {
        String stack = "missing-permission-model-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectMapper mapper = new ObjectMapper();
        String body = mapper.createObjectNode().set("Resources", mapper.createObjectNode().set("StackSet",
                mapper.createObjectNode().put("Type", "AWS::CloudFormation::StackSet")
                        .set("Properties", mapper.createObjectNode().put("StackSetName", stack).put("TemplateBody",
                                "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}"))))
                .toString();
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body).then().statusCode(200);

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, auth(MANAGEMENT, "cloudformation"));
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertTrue(CfnStackWaits.failedResourceReason(stack, auth(MANAGEMENT, "cloudformation"))
                .contains("PermissionModel"));
    }

    @Test
    void unnamedStackSetKeepsItsGeneratedIdentityOnUpdate() {
        String stack = "unnamed-stackset-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode properties = mapper.createObjectNode().put("PermissionModel", "SELF_MANAGED")
                .put("TemplateBody", "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}");
        ObjectNode resource = mapper.createObjectNode().put("Type", "AWS::CloudFormation::StackSet");
        resource.set("Properties", properties);
        ObjectNode body = mapper.createObjectNode();
        body.set("Resources", mapper.createObjectNode().set("Set", resource));
        body.set("Outputs", mapper.createObjectNode().set("Id",
                mapper.createObjectNode().set("Value", mapper.createObjectNode().put("Ref", "Set"))));
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String identity = output(stack, "Id");
        assertThat(identity, startsWith(stack + "-Set-"));

        properties.put("Description", "updated");
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        assertEquals(identity, output(stack, "Id"));
        String name = identity.substring(0, identity.indexOf(':'));
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Description>updated</Description>"));
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(404);
    }

    @Test
    void conditionalDeploymentSettingsResolveTheirSelectedObjects() throws Exception {
        String root = organizationsFixture.createWithTrustedAccess(MANAGEMENT);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stack = "conditional-stackset-" + suffix;
        String stackSet = "conditional-set-" + suffix;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = (ObjectNode) mapper.readTree(template(stackSet, "conditional-q-" + suffix));
        body.set("Conditions", mapper.createObjectNode().set("Enabled", mapper.createObjectNode()
                .set("Fn::Equals", mapper.createArrayNode().add("yes").add("yes"))));
        ObjectNode properties = (ObjectNode) body.path("Resources").path("StackSet").path("Properties");
        for (String key : new String[]{"AutoDeployment", "ManagedExecution"}) {
            properties.set(key, mapper.createObjectNode().set("Fn::If", mapper.createArrayNode()
                    .add("Enabled").add(properties.get(key))
                    .add(mapper.createObjectNode().put("AutoDeployment".equals(key) ? "Enabled" : "Active", false))));
        }
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString(),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", root)
                .then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));

        cloudFormation("DescribeStackSet", "StackSetName", stackSet).then().statusCode(200)
                .body(containsString("<Enabled>true</Enabled>"))
                .body(containsString("<Active>true</Active>"));
        body.set("Conditions", mapper.createObjectNode().set("Enabled", mapper.createObjectNode()
                .set("Fn::Equals", mapper.createArrayNode().add("no").add("yes"))));
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString(),
                "Parameters.member.1.ParameterKey", "Targets", "Parameters.member.1.ParameterValue", root)
                .then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DescribeStackSet", "StackSetName", stackSet).then().statusCode(200)
                .body(containsString("<Enabled>false</Enabled>"))
                .body(containsString("<Active>false</Active>"));
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
    }

    @AfterEach
    void deleteOrganization() {
        organizationsFixture.cleanup();
    }

    private static String template(String stackSet, String queue) throws JsonProcessingException {
        String instanceTemplate = "{\"Parameters\":{\"QueueName\":{\"Type\":\"String\"}},"
                + "\"Resources\":{\"Q\":{\"Type\":\"AWS::SQS::Queue\",\"Properties\":{\"QueueName\":{\"Ref\":\"QueueName\"}}}}}";
        return """
            {
              "Parameters": {
                "Targets": { "Type": "String" },
                "Regions": { "Type": "CommaDelimitedList", "Default": "us-east-1" }
              },
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
                      "Regions": { "Ref": "Regions" }
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

    private String createOu(String parentId, String name) {
        return organizations("CreateOrganizationalUnit", "{\"ParentId\":\"" + parentId + "\",\"Name\":\"" + name + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("OrganizationalUnit.Id");
    }

    private String createAccount(String email, String parentId, String rootId) {
        String accountId = organizations("CreateAccount", "{\"Email\":\"" + email + "\",\"AccountName\":\"CfnStackSet\"}")
                .then().statusCode(200).extract().jsonPath().getString("CreateAccountStatus.AccountId");
        organizations("MoveAccount", "{\"AccountId\":\"" + accountId + "\",\"SourceParentId\":\"" + rootId
                + "\",\"DestinationParentId\":\"" + parentId + "\"}").then().statusCode(200);
        return accountId;
    }

    private static String output(String stack, String key) {
        return cloudFormation("DescribeStacks", "StackName", stack).xmlPath().getString(
                "DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                        + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static void assertQueue(String account, String queue, boolean present) {
        assertQueue(account, queue, REGION, present);
    }

    private static void assertQueue(String account, String queue, String region, boolean present) {
        given()
                .header("Authorization", auth(account, region, "sqs"))
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

    private Response organizations(String action, String body) {
        return organizationsFixture.request(MANAGEMENT, action, body).post("/");
    }
}
