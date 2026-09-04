package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** AWS Organizations semantics required by service-managed CloudFormation StackSets. */
@QuarkusTest
class CloudFormationServiceManagedStackSetsIntegrationTest {
    private static final String MANAGEMENT = "555555555555";
    private static final String REGION = "us-east-1";
    private static final String ORG_TARGET = "AWSOrganizationsV20161128.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void serviceManagedStackSetUsesTrustedAccessAndRecursesIntoChildOus() {
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        String rootId = organizations("ListRoots", "{}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");

        String parentOu = organizations("CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"StackSetsParent\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
        String childOu = organizations("CreateOrganizationalUnit",
                "{\"ParentId\":\"" + parentOu + "\",\"Name\":\"StackSetsChild\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String directAccount = createAccount("stacksets-direct-" + suffix + "@example.com", "StackSetsDirect");
        String nestedAccount = createAccount("stacksets-nested-" + suffix + "@example.com", "StackSetsNested");
        moveAccount(directAccount, rootId, parentOu);
        moveAccount(nestedAccount, rootId, childOu);

        cloudFormation("ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);

        organizations("ListAWSServiceAccessForOrganization", "{}")
                .post("/").then().statusCode(200)
                .body("EnabledServicePrincipals.ServicePrincipal",
                        hasItem("stacksets.cloudformation.amazonaws.com"));

        cloudFormation("DescribeOrganizationsAccess")
                .post("/").then().statusCode(200)
                .body(containsString("<Status>ENABLED</Status>"));

        String stackSet = "nested-ou-" + suffix;
        String queue = "nested-ou-q-" + suffix;
        cloudFormation("CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .formParam("AutoDeployment.Enabled", "true")
                .post("/").then().statusCode(200);

        cloudFormation("CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", parentOu)
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(200)
                .body(containsString("<OperationId>"));

        assertQueueVisible(directAccount, queue);
        assertQueueVisible(nestedAccount, queue);

        cloudFormation("ListStackSetAutoDeploymentTargets")
                .formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200)
                .body(containsString("<OrganizationalUnitId>" + parentOu + "</OrganizationalUnitId>"))
                .body(containsString("<member>" + REGION + "</member>"));

        cloudFormation("ListStackInstances")
                .formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200)
                .body(containsString("<Account>" + directAccount + "</Account>"))
                .body(containsString("<Account>" + nestedAccount + "</Account>"))
                .body(containsString("<OrganizationalUnitId>" + parentOu + "</OrganizationalUnitId>"));
    }

    @Test
    void activateOrganizationsAccessRequiresAllFeatures() {
        String management = "666666666666";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"CONSOLIDATED_BILLING\"}")
                .post("/").then().statusCode(200);

        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(400)
                .body(containsString("InvalidOperationException"))
                .body(containsString("all features"));
    }

    private String createAccount(String email, String name) {
        JsonPath response = organizations("CreateAccount",
                "{\"Email\":\"" + email + "\",\"AccountName\":\"" + name + "\"}")
                .post("/").then().statusCode(200)
                .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
                .extract().jsonPath();
        return response.getString("CreateAccountStatus.AccountId");
    }

    private void moveAccount(String accountId, String sourceParent, String destinationParent) {
        organizations("MoveAccount",
                "{\"AccountId\":\"" + accountId + "\",\"SourceParentId\":\"" + sourceParent
                        + "\",\"DestinationParentId\":\"" + destinationParent + "\"}")
                .post("/").then().statusCode(200);
    }

    private RequestSpecification organizations(String action, String body) {
        return organizations(MANAGEMENT, action, body);
    }

    private RequestSpecification organizations(String account, String action, String body) {
        return given()
                .header("Authorization", auth(account, "organizations"))
                .header("X-Amz-Target", ORG_TARGET + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
    }

    private RequestSpecification cloudFormation(String action) {
        return cloudFormation(MANAGEMENT, action);
    }

    private RequestSpecification cloudFormation(String account, String action) {
        return given()
                .header("Authorization", auth(account, "cloudformation"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
    }

    private void assertQueueVisible(String account, String queueName) {
        given()
                .header("Authorization", auth(account, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueName)
                .post("/").then().statusCode(200)
                .body(containsString("/" + account + "/" + queueName));
    }

    private static String queueTemplate(String queueName) {
        return "{\"Resources\":{\"Q\":{\"Type\":\"AWS::SQS::Queue\",\"Properties\":{\"QueueName\":\""
                + queueName + "\"}}}}";
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260904/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
