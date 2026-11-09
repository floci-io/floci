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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void removingDeclaredCollectionsAndDescriptionClearsTheirStackSetValues() {
        String stack = "removed-settings-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "set-" + stack;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = simpleStackSetTemplate(name);
        ObjectNode properties = (ObjectNode) body.path("Resources").path("Set").path("Properties");
        properties.put("TemplateBody", "{\"Parameters\":{\"Value\":{\"Type\":\"String\",\"Default\":\"default\"}},"
                + "\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}");
        properties.set("Parameters", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("ParameterKey", "Value").put("ParameterValue", "explicit")));
        properties.set("Capabilities", mapper.createArrayNode().add("CAPABILITY_NAMED_IAM"));
        properties.set("Tags", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("Key", "declared").put("Value", "value")));
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Description>original</Description>"))
                .body(containsString("<ParameterValue>explicit</ParameterValue>"))
                .body(containsString("<member>CAPABILITY_NAMED_IAM</member>"))
                .body(containsString("<Key>declared</Key>"));

        for (String key : new String[]{"Description", "Parameters", "Capabilities", "Tags"}) {
            properties.remove(key);
        }
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(not(containsString("<Description>original</Description>")))
                .body(not(containsString("<ParameterValue>explicit</ParameterValue>")))
                .body(not(containsString("<member>CAPABILITY_NAMED_IAM</member>")))
                .body(not(containsString("<Key>declared</Key>")));
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
    }

    @Test
    void apiUpdateStillKeepsOmittedSettings() {
        String name = "api-keep-" + UUID.randomUUID().toString().substring(0, 8);
        String templateBody = "{\"Parameters\":{\"Value\":{\"Type\":\"String\"}},"
                + "\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}";
        cloudFormation("CreateStackSet", "StackSetName", name, "PermissionModel", "SELF_MANAGED",
                "TemplateBody", templateBody, "Description", "keep",
                "Parameters.member.1.ParameterKey", "Value", "Parameters.member.1.ParameterValue", "explicit",
                "Capabilities.member.1", "CAPABILITY_NAMED_IAM", "Tags.member.1.Key", "declared",
                "Tags.member.1.Value", "value").then().statusCode(200);
        cloudFormation("UpdateStackSet", "StackSetName", name, "TemplateBody", templateBody)
                .then().statusCode(200);
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Description>keep</Description>"))
                .body(containsString("<ParameterValue>explicit</ParameterValue>"))
                .body(containsString("<member>CAPABILITY_NAMED_IAM</member>"))
                .body(containsString("<Key>declared</Key>"));
        cloudFormation("DeleteStackSet", "StackSetName", name).then().statusCode(200);
    }

    @Test
    void removingDeploymentSettingsRestoresTheirDefaults() {
        organizationsFixture.createWithTrustedAccess(MANAGEMENT);
        String stack = "removed-deployment-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "set-" + stack;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = simpleStackSetTemplate(name);
        ObjectNode properties = (ObjectNode) body.path("Resources").path("Set").path("Properties");
        properties.put("PermissionModel", "SERVICE_MANAGED");
        properties.set("AutoDeployment", mapper.createObjectNode().put("Enabled", true)
                .put("RetainStacksOnAccountRemoval", true));
        properties.set("ManagedExecution", mapper.createObjectNode().put("Active", true));
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Enabled>true</Enabled>"))
                .body(containsString("<RetainStacksOnAccountRemoval>true</RetainStacksOnAccountRemoval>"))
                .body(containsString("<Active>true</Active>"));

        properties.remove("AutoDeployment");
        properties.remove("ManagedExecution");
        properties.remove("Description");
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Enabled>false</Enabled>"))
                .body(containsString("<RetainStacksOnAccountRemoval>false</RetainStacksOnAccountRemoval>"))
                .body(containsString("<Active>false</Active>"));
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
    }

    @Test
    void removingRequiredParameterFailsBeforeChangingTheStackSet() {
        String stack = "removed-required-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "set-" + stack;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = simpleStackSetTemplate(name);
        ObjectNode properties = (ObjectNode) body.path("Resources").path("Set").path("Properties");
        properties.put("TemplateBody", "{\"Parameters\":{\"Value\":{\"Type\":\"String\"}},"
                + "\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}");
        properties.set("Parameters", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("ParameterKey", "Value").put("ParameterValue", "original")));
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));

        properties.remove("Parameters");
        properties.put("Description", "must-not-apply");
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack,
                auth(MANAGEMENT, "cloudformation")).status());
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Description>original</Description>"))
                .body(containsString("<ParameterValue>original</ParameterValue>"));
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
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

    @Test
    void deletingAStaleResourceLeavesTheRecreatedStackSetAlone() {
        String stack = "stale-delete-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "set-" + stack;
        ObjectNode body = simpleStackSetTemplate(name);
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String original = output(stack, "Id");
        String replacement = recreateStackSetOutsideItsParent(name);
        assertNotEquals(original, replacement);

        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));

        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<StackSetId>" + replacement + "</StackSetId>"));
        cloudFormation("DeleteStackSet", "StackSetName", name).then().statusCode(200);
    }

    @Test
    void updatingAStaleResourceDoesNotMutateTheRecreatedStackSet() {
        String stack = "stale-update-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "set-" + stack;
        ObjectNode body = simpleStackSetTemplate(name);
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String original = output(stack, "Id");
        String replacement = recreateStackSetOutsideItsParent(name);
        assertNotEquals(original, replacement);
        ((ObjectNode) body.path("Resources").path("Set").path("Properties")).put("Description", "requested");

        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, auth(MANAGEMENT, "cloudformation"));
        assertNotEquals("UPDATE_COMPLETE", state.status());
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Description>replacement</Description>"))
                .body(containsString("<StackSetId>" + replacement + "</StackSetId>"));
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
        cloudFormation("DeleteStackSet", "StackSetName", name).then().statusCode(200);
    }

    private static ObjectNode simpleStackSetTemplate(String name) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode properties = mapper.createObjectNode().put("StackSetName", name)
                .put("PermissionModel", "SELF_MANAGED").put("Description", "original")
                .put("TemplateBody", "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}");
        ObjectNode resource = mapper.createObjectNode().put("Type", "AWS::CloudFormation::StackSet");
        resource.set("Properties", properties);
        ObjectNode body = mapper.createObjectNode();
        body.set("Resources", mapper.createObjectNode().set("Set", resource));
        body.set("Outputs", mapper.createObjectNode().set("Id",
                mapper.createObjectNode().set("Value", mapper.createObjectNode().put("Ref", "Set"))));
        return body;
    }

    private static String recreateStackSetOutsideItsParent(String name) {
        cloudFormation("DeleteStackSet", "StackSetName", name).then().statusCode(200);
        return cloudFormation("CreateStackSet", "StackSetName", name, "PermissionModel", "SELF_MANAGED",
                "Description", "replacement", "TemplateBody",
                "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}")
                .then().statusCode(200).extract().xmlPath().getString("**.find { it.name() == 'StackSetId' }");
    }

    @Test
    void replacementChangesIdentityWhenNameChanges() {
        replacementRename(false);
    }

    @Test
    void replacementHonorsUpdateReplacePolicyRetain() {
        replacementRename(true);
    }

    private void replacementRename(boolean retain) {
        String stack = "replace-name-" + UUID.randomUUID().toString().substring(0, 8);
        String oldName = "old-" + stack;
        String newName = "new-" + stack;
        ObjectNode body = simpleStackSetTemplate(oldName);
        if (retain) {
            ((ObjectNode) body.path("Resources").path("Set")).put("UpdateReplacePolicy", "Retain");
        }
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String original = output(stack, "Id");
        ((ObjectNode) body.path("Resources").path("Set").path("Properties")).put("StackSetName", newName);
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        assertNotEquals(original, output(stack, "Id"));
        assertThat(output(stack, "Id"), startsWith(newName + ":"));
        cloudFormation("DescribeStackSet", "StackSetName", oldName).then().statusCode(retain ? 200 : 404);
        cloudFormation("DescribeStackSet", "StackSetName", newName).then().statusCode(200);
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
        if (retain) {
            cloudFormation("DeleteStackSet", "StackSetName", oldName).then().statusCode(200);
        }
    }

    @Test
    void replacementChangesPermissionModelForGeneratedName() {
        organizationsFixture.createWithTrustedAccess(MANAGEMENT);
        String stack = "replace-model-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode body = simpleStackSetTemplate("temporary");
        ObjectNode properties = (ObjectNode) body.path("Resources").path("Set").path("Properties");
        properties.remove("StackSetName");
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String original = output(stack, "Id");
        properties.put("PermissionModel", "SERVICE_MANAGED");
        properties.set("AutoDeployment", new ObjectMapper().createObjectNode().put("Enabled", false));
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String replacement = output(stack, "Id");
        assertNotEquals(original, replacement);
        cloudFormation("DescribeStackSet", "StackSetName", replacement.substring(0, replacement.indexOf(':')))
                .then().statusCode(200).body(containsString("<PermissionModel>SERVICE_MANAGED</PermissionModel>"));
        cloudFormation("DescribeStackSet", "StackSetName", original.substring(0, original.indexOf(':')))
                .then().statusCode(404);
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
    }

    @Test
    void replacementRejectsSameCustomNameBeforeMutation() {
        String stack = "replace-custom-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "set-" + stack;
        ObjectNode body = simpleStackSetTemplate(name);
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String original = output(stack, "Id");
        ObjectNode properties = (ObjectNode) body.path("Resources").path("Set").path("Properties");
        properties.put("PermissionModel", "SERVICE_MANAGED").put("Description", "must-not-apply");
        properties.set("AutoDeployment", new ObjectMapper().createObjectNode().put("Enabled", false));
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        assertEquals(original, output(stack, "Id"));
        cloudFormation("DescribeStackSet", "StackSetName", name).then().statusCode(200)
                .body(containsString("<Description>original</Description>"))
                .body(containsString("<PermissionModel>SELF_MANAGED</PermissionModel>"));
        CfnStackWaits.deleteStack(stack, auth(MANAGEMENT, "cloudformation"));
    }

    @Test
    void replacementRollsBackWhenALaterResourceFails() {
        String stack = "replace-rollback-" + UUID.randomUUID().toString().substring(0, 8);
        String oldName = "old-" + stack;
        String newName = "new-" + stack;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = simpleStackSetTemplate(oldName);
        ObjectNode resources = (ObjectNode) body.path("Resources");
        ObjectNode parameter = mapper.createObjectNode().put("Type", "AWS::SSM::Parameter");
        parameter.set("Properties", mapper.createObjectNode().put("Name", stack)
                .put("Type", "String").put("Value", "original"));
        resources.set("Obstacle", parameter);
        cloudFormation("CreateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        String original = output(stack, "Id");
        ((ObjectNode) resources.path("Set").path("Properties")).put("StackSetName", newName);
        ObjectNode broken = parameter.deepCopy();
        broken.set("DependsOn", mapper.createArrayNode().add("Set").add("Obstacle"));
        resources.set("Broken", broken);
        cloudFormation("UpdateStack", "StackName", stack, "TemplateBody", body.toString()).then().statusCode(200);
        CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE", auth(MANAGEMENT, "cloudformation"));
        assertEquals(original, output(stack, "Id"));
        cloudFormation("DescribeStackSet", "StackSetName", oldName).then().statusCode(200);
        cloudFormation("DescribeStackSet", "StackSetName", newName).then().statusCode(404);
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
