package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** AWS Organizations semantics required by service-managed CloudFormation StackSets. */
@QuarkusTest
class CloudFormationServiceManagedStackSetsIntegrationTest {
    private static final String MANAGEMENT = "555555555555";
    private static final String REGION = "us-east-1";
    private static final String ORG_TARGET = "AWSOrganizationsV20161128.";

    private final Set<String> organizationOwners = new LinkedHashSet<>();

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
    void serviceManagedStackSetRejectsTopLevelAccounts() {
        String management = "777777777777";
        String targetAccount = "999999999999";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackSet = "reject-accounts-" + suffix;
        String queue = "reject-accounts-q-" + suffix;
        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .post("/").then().statusCode(200);

        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("Accounts.member.1", targetAccount)
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(400)
                .body(containsString("InvalidOperationException"));

        assertQueueAbsent(targetAccount, queue);
    }

    @Test
    void createServiceManagedStackSetWithoutTrustedAccessUsesValidationError() {
        String management = "888888888888";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);

        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", "no-access-" + UUID.randomUUID().toString().substring(0, 8))
                .formParam("TemplateBody", queueTemplate("unused"))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .post("/").then().statusCode(400)
                .body(containsString("ValidationError"))
                .body(not(containsString("InvalidOperationException")));
    }

    @Test
    void describeSelfManagedStackSetOmitsAutoDeployment() {
        String stackSet = "self-managed-" + UUID.randomUUID().toString().substring(0, 8);
        cloudFormation("CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate("unused"))
                .post("/").then().statusCode(200);

        cloudFormation("DescribeStackSet")
                .formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200)
                .body(containsString("<PermissionModel>SELF_MANAGED</PermissionModel>"))
                .body(not(containsString("<AutoDeployment>")));
    }

    @Test
    void serviceManagedDeleteRequiresDeploymentTargets() {
        String management = "909090909090";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        String rootId = organizations(management, "ListRoots", "{}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
        String targetOu = organizations(management, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"DeleteTarget\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String targetAccount = organizations(management, "CreateAccount",
                "{\"Email\":\"delete-target-" + suffix + "@example.com\",\"AccountName\":\"DeleteTarget\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");
        organizations(management, "MoveAccount",
                "{\"AccountId\":\"" + targetAccount + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + targetOu + "\"}")
                .post("/").then().statusCode(200);
        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);

        String stackSet = "delete-targets-" + suffix;
        String queue = "delete-targets-q-" + suffix;
        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .formParam("AutoDeployment.Enabled", "true")
                .post("/").then().statusCode(200);
        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", targetOu)
                .formParam("Regions.member.1", REGION)
                .formParam("Regions.member.2", "us-west-2")
                .post("/").then().statusCode(200);
        assertQueueVisible(targetAccount, queue);

        cloudFormation(management, "DeleteStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("Accounts.member.1", targetAccount)
                .formParam("Regions.member.1", REGION)
                .formParam("RetainStacks", "false")
                .post("/").then().statusCode(400)
                .body(containsString("InvalidOperationException"));
        assertQueueVisible(targetAccount, queue);

        cloudFormation(management, "DeleteStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", targetOu)
                .formParam("Regions.member.1", REGION)
                .formParam("RetainStacks", "false")
                .post("/").then().statusCode(200);
        assertQueueAbsent(targetAccount, queue);
        cloudFormation(management, "ListStackSetAutoDeploymentTargets")
                .formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200)
                .body(containsString("<OrganizationalUnitId>" + targetOu + "</OrganizationalUnitId>"))
                .body(not(containsString("<member>" + REGION + "</member>")))
                .body(containsString("<member>us-west-2</member>"));

        cloudFormation(management, "DeleteStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", targetOu)
                .formParam("Regions.member.1", "us-west-2")
                .formParam("RetainStacks", "false")
                .post("/").then().statusCode(200);
        cloudFormation(management, "ListStackSetAutoDeploymentTargets")
                .formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200)
                .body(not(containsString("<OrganizationalUnitId>" + targetOu + "</OrganizationalUnitId>")));
    }

    @Test
    void serviceManagedDeleteUsesPersistedOuAssociationAfterAccountMoves() {
        String management = "919191919191";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        String rootId = organizations(management, "ListRoots", "{}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
        String firstOu = organizations(management, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"PersistedTargetA\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
        String secondOu = organizations(management, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"PersistedTargetB\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String firstAccount = organizations(management, "CreateAccount",
                "{\"Email\":\"persisted-a-" + suffix + "@example.com\",\"AccountName\":\"PersistedA\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");
        String secondAccount = organizations(management, "CreateAccount",
                "{\"Email\":\"persisted-b-" + suffix + "@example.com\",\"AccountName\":\"PersistedB\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");
        organizations(management, "MoveAccount",
                "{\"AccountId\":\"" + firstAccount + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + firstOu + "\"}")
                .post("/").then().statusCode(200);
        organizations(management, "MoveAccount",
                "{\"AccountId\":\"" + secondAccount + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + secondOu + "\"}")
                .post("/").then().statusCode(200);
        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);

        String stackSet = "persisted-ou-" + suffix;
        String queue = "persisted-ou-q-" + suffix;
        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .post("/").then().statusCode(200);
        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", firstOu)
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(200);
        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", secondOu)
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(200);
        assertQueueVisible(firstAccount, queue);
        assertQueueVisible(secondAccount, queue);

        organizations(management, "MoveAccount",
                "{\"AccountId\":\"" + firstAccount + "\",\"SourceParentId\":\"" + firstOu
                        + "\",\"DestinationParentId\":\"" + secondOu + "\"}")
                .post("/").then().statusCode(200);
        organizations(management, "MoveAccount",
                "{\"AccountId\":\"" + secondAccount + "\",\"SourceParentId\":\"" + secondOu
                        + "\",\"DestinationParentId\":\"" + firstOu + "\"}")
                .post("/").then().statusCode(200);

        cloudFormation(management, "DeleteStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", firstOu)
                .formParam("Regions.member.1", REGION)
                .formParam("RetainStacks", "false")
                .post("/").then().statusCode(200);
        assertQueueAbsent(firstAccount, queue);
        assertQueueVisible(secondAccount, queue);

        cloudFormation(management, "DeleteStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", secondOu)
                .formParam("Regions.member.1", REGION)
                .formParam("RetainStacks", "false")
                .post("/").then().statusCode(200);
        assertQueueAbsent(secondAccount, queue);
    }

    @Test
    void overlappingOuTargetsKeepInstanceUntilLastAssociationIsDeleted() {
        String management = "929292929292";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        String rootId = organizations(management, "ListRoots", "{}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
        String parentOu = organizations(management, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"OverlapParent\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
        String childOu = organizations(management, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + parentOu + "\",\"Name\":\"OverlapChild\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String account = organizations(management, "CreateAccount",
                "{\"Email\":\"overlap-" + suffix + "@example.com\",\"AccountName\":\"Overlap\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");
        organizations(management, "MoveAccount",
                "{\"AccountId\":\"" + account + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + childOu + "\"}")
                .post("/").then().statusCode(200);
        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);

        String stackSet = "overlap-ou-" + suffix;
        String queue = "overlap-ou-q-" + suffix;
        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .post("/").then().statusCode(200);
        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", parentOu)
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(200);
        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", childOu)
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(200);
        assertQueueVisible(account, queue);

        cloudFormation(management, "DeleteStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", parentOu)
                .formParam("Regions.member.1", REGION)
                .formParam("RetainStacks", "false")
                .post("/").then().statusCode(200);
        assertQueueVisible(account, queue);

        cloudFormation(management, "DeleteStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", childOu)
                .formParam("Regions.member.1", REGION)
                .formParam("RetainStacks", "false")
                .post("/").then().statusCode(200);
        assertQueueAbsent(account, queue);
    }

    @Test
    void anOrganizationalUnitThatCannotBeResolvedFailsTheOperation() {
        // Resolving the targets is part of the operation: a target that fails ends it FAILED rather
        // than leave it RUNNING, which would refuse every later operation on the stack set.
        String management = "646464646464";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        String rootId = organizations(management, "ListRoots", "{}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackSet = "missing-ou-" + suffix;
        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate("missing-ou-q-" + suffix))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .post("/").then().statusCode(200);

        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", "ou-zzzz-zzzzzzzz")
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(400)
                .body(containsString("OrganizationalUnitNotFoundException"));
        cloudFormation(management, "ListStackSetOperations")
                .formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200)
                .body(containsString("<Status>FAILED</Status>"))
                .body(not(containsString("<Status>RUNNING</Status>")));

        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", rootId)
                .formParam("Regions.member.1", REGION)
                .post("/").then().statusCode(200);
    }

    @Test
    void activateOrganizationsAccessRequiresAllFeatures() {
        String management = "939393939393";
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"CONSOLIDATED_BILLING\"}")
                .post("/").then().statusCode(200);

        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(400)
                .body(containsString("InvalidOperationException"))
                .body(containsString("all features"));
    }

    @Test
    void autoDeploymentFollowsAnAccountIntoAndOutOfATargetedOrganizationalUnit() {
        String management = "626262626262";
        String rootId = createOrganizationWithTrustedAccess(management);
        String targetOu = createOu(management, rootId, "AutoTarget");
        String childOu = createOu(management, targetOu, "AutoTargetChild");
        String otherOu = createOu(management, rootId, "AutoOther");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackSet = "auto-ou-" + suffix;
        String queue = "auto-ou-q-" + suffix;
        createAutoDeployingStackSet(management, stackSet, queue, "false");
        createStackInstances(management, stackSet, targetOu, REGION);
        createStackInstances(management, stackSet, targetOu, "us-west-2");

        cloudFormation(management, "ListStackSetAutoDeploymentTargets")
                .formParam("StackSetName", stackSet)
                .post("/").then().statusCode(200)
                .body(containsString("<member>" + REGION + "</member>"))
                .body(containsString("<member>us-west-2</member>"));

        String account = createAccount(management, "auto-ou-" + suffix + "@example.com", "AutoOu");
        assertQueueAbsent(account, queue);
        assertNoInstance(management, stackSet, account);

        moveAccount(management, account, rootId, childOu);
        assertQueueVisible(account, queue);
        cloudFormation(management, "ListStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("StackInstanceAccount", account)
                .post("/").then().statusCode(200)
                .body(containsString("<Region>" + REGION + "</Region>"))
                .body(containsString("<Region>us-west-2</Region>"))
                .body(containsString("<OrganizationalUnitId>" + targetOu + "</OrganizationalUnitId>"))
                .body(containsString("<DetailedStatus>SUCCEEDED</DetailedStatus>"));

        moveAccount(management, account, childOu, targetOu);
        assertQueueVisible(account, queue);

        moveAccount(management, account, targetOu, otherOu);
        assertQueueAbsent(account, queue);
        assertNoInstance(management, stackSet, account);
    }

    @Test
    void autoDeploymentToTheRootCoversNewAccountsAndRemovesThoseThatLeave() {
        String management = "636363636363";
        String rootId = createOrganizationWithTrustedAccess(management);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackSet = "auto-root-" + suffix;
        String queue = "auto-root-q-" + suffix;
        createAutoDeployingStackSet(management, stackSet, queue, "false");
        createStackInstances(management, stackSet, rootId, REGION);

        String account = createAccount(management, "auto-root-" + suffix + "@example.com", "AutoRoot");
        assertQueueVisible(account, queue);
        assertQueueAbsent(management, queue);

        organizations(management, "RemoveAccountFromOrganization", "{\"AccountId\":\"" + account + "\"}")
                .post("/").then().statusCode(200);
        assertQueueAbsent(account, queue);
        assertNoInstance(management, stackSet, account);
    }

    @Test
    void autoDeploymentRetainsTheStackOfAnAccountThatLeavesItsTargets() {
        String management = "646464646464";
        String rootId = createOrganizationWithTrustedAccess(management);
        String targetOu = createOu(management, rootId, "RetainTarget");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackSet = "auto-retain-" + suffix;
        String queue = "auto-retain-q-" + suffix;
        createAutoDeployingStackSet(management, stackSet, queue, "true");
        createStackInstances(management, stackSet, targetOu, REGION);

        String account = createAccount(management, "auto-retain-" + suffix + "@example.com", "AutoRetain");
        moveAccount(management, account, rootId, targetOu);
        assertQueueVisible(account, queue);

        moveAccount(management, account, targetOu, rootId);
        assertNoInstance(management, stackSet, account);
        assertQueueVisible(account, queue);
    }

    @Test
    void stackSetWithoutAutoDeploymentIgnoresAccountsMovedIntoItsTargets() {
        String management = "656565656565";
        String rootId = createOrganizationWithTrustedAccess(management);
        String targetOu = createOu(management, rootId, "ManualTarget");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackSet = "manual-" + suffix;
        String queue = "manual-q-" + suffix;
        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .formParam("AutoDeployment.Enabled", "false")
                .post("/").then().statusCode(200);
        createStackInstances(management, stackSet, targetOu, REGION);

        String account = createAccount(management, "manual-" + suffix + "@example.com", "Manual");
        moveAccount(management, account, rootId, targetOu);
        assertQueueAbsent(account, queue);
        assertNoInstance(management, stackSet, account);
    }

    private String createOrganizationWithTrustedAccess(String management) {
        organizations(management, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        cloudFormation(management, "ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);
        return organizations(management, "ListRoots", "{}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
    }

    private String createOu(String management, String parentId, String name) {
        return organizations(management, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + parentId + "\",\"Name\":\"" + name + "\"}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getString("OrganizationalUnit.Id");
    }

    private void createAutoDeployingStackSet(String management, String stackSet, String queue, String retainStacks) {
        cloudFormation(management, "CreateStackSet")
                .formParam("StackSetName", stackSet)
                .formParam("TemplateBody", queueTemplate(queue))
                .formParam("PermissionModel", "SERVICE_MANAGED")
                .formParam("AutoDeployment.Enabled", "true")
                .formParam("AutoDeployment.RetainStacksOnAccountRemoval", retainStacks)
                .post("/").then().statusCode(200);
    }

    private void createStackInstances(String management, String stackSet, String target, String region) {
        cloudFormation(management, "CreateStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("DeploymentTargets.OrganizationalUnitIds.member.1", target)
                .formParam("Regions.member.1", region)
                .post("/").then().statusCode(200);
    }

    private String createAccount(String management, String email, String name) {
        return organizations(management, "CreateAccount",
                "{\"Email\":\"" + email + "\",\"AccountName\":\"" + name + "\"}")
                .post("/").then().statusCode(200)
                .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
                .extract().jsonPath().getString("CreateAccountStatus.AccountId");
    }

    private void moveAccount(String management, String accountId, String sourceParent, String destinationParent) {
        organizations(management, "MoveAccount",
                "{\"AccountId\":\"" + accountId + "\",\"SourceParentId\":\"" + sourceParent
                        + "\",\"DestinationParentId\":\"" + destinationParent + "\"}")
                .post("/").then().statusCode(200);
    }

    private void assertNoInstance(String management, String stackSet, String account) {
        cloudFormation(management, "ListStackInstances")
                .formParam("StackSetName", stackSet)
                .formParam("StackInstanceAccount", account)
                .post("/").then().statusCode(200)
                .body(not(containsString("<Account>" + account + "</Account>")));
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
        if ("CreateOrganization".equals(action)) {
            organizationOwners.add(account);
        }
        return given()
                .header("Authorization", auth(account, "organizations"))
                .header("X-Amz-Target", ORG_TARGET + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
    }

    /**
     * Organizations state is shared by every test in the JVM and keyed by the management account,
     * so an organization left behind here makes the next class that creates one under the same
     * account fail with AlreadyInOrganizationException. Dismantle each one this test created:
     * member accounts out, organizational units bottom-up, then the organization.
     */
    @AfterEach
    void deleteTheOrganizationsThisTestCreated() {
        for (String owner : organizationOwners) {
            if (organizations(owner, "DescribeOrganization", "{}").post("/").statusCode() != 200) {
                continue;
            }
            JsonPath accounts = organizations(owner, "ListAccounts", "{}").post("/").jsonPath();
            for (String accountId : accounts.getList("Accounts.Id", String.class)) {
                if (!owner.equals(accountId)) {
                    organizations(owner, "RemoveAccountFromOrganization", "{\"AccountId\":\"" + accountId + "\"}")
                            .post("/").then().statusCode(200);
                }
            }
            JsonPath roots = organizations(owner, "ListRoots", "{}").post("/").jsonPath();
            for (String rootId : roots.getList("Roots.Id", String.class)) {
                deleteOrganizationalUnitsUnder(owner, rootId);
            }
            organizations(owner, "DeleteOrganization", "{}").post("/").then().statusCode(200);
        }
        organizationOwners.clear();
    }

    private void deleteOrganizationalUnitsUnder(String owner, String parentId) {
        JsonPath units = organizations(owner, "ListOrganizationalUnitsForParent", "{\"ParentId\":\"" + parentId + "\"}")
                .post("/").jsonPath();
        List<String> ouIds = units.getList("OrganizationalUnits.Id", String.class);
        for (String ouId : ouIds) {
            deleteOrganizationalUnitsUnder(owner, ouId);
            organizations(owner, "DeleteOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
                    .post("/").then().statusCode(200);
        }
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

    private void assertQueueAbsent(String account, String queueName) {
        given()
                .header("Authorization", auth(account, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueName)
                .post("/").then().statusCode(400)
                .body(containsString("AWS.SimpleQueueService.NonExistentQueue"));
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
