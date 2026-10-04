package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.VpcLink;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AWS::ApiGatewayV2::VpcLink through a real stack: Ref and Fn::GetAtt VpcLinkId resolve to the
 * real link, an integration can use it through ConnectionId, a Name change updates the link in
 * place, a SubnetIds change replaces it, and DeleteStack removes it. A failed update puts the link
 * and the integration connection back.
 */
@QuarkusTest
class CloudFormationApiGatewayV2VpcLinkIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String STACK_NAME = "vpclink-stack";
    private static final String VPC_LINK_CONNECTION =
            ",\"ConnectionType\":\"VPC_LINK\",\"ConnectionId\":{\"Ref\":\"VpcLink\"}";
    private static final String FAILING_RESOURCE =
            ",\"Broken\":{\"Type\":\"AWS::ApiGatewayV2::VpcLink\",\"DependsOn\":\"Integration\","
                    + "\"Properties\":{\"Name\":\"broken\"}}";

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void vpcLinkLifecycleFollowsTheCloudFormationUpdateRules() {
        stackAction("CreateStack", STACK_NAME, template("first-name", "subnet-aaaa1111"));
        waitForStackStatus(STACK_NAME, "CREATE_COMPLETE");

        Stack created = stack(STACK_NAME);
        String vpcLinkId = created.getOutputs().get("VpcLinkRef");
        assertEquals(vpcLinkId, created.getOutputs().get("VpcLinkIdAttr"));
        VpcLink link = apiGatewayV2Service.getVpcLink(REGION, vpcLinkId);
        assertEquals("first-name", link.getName());
        assertEquals(List.of("subnet-aaaa1111"), link.getSubnetIds());
        assertEquals(Map.of("Env", "test"), link.getTags());
        Integration integration = integration(created);
        assertEquals("VPC_LINK", integration.getConnectionType());
        assertEquals(vpcLinkId, integration.getConnectionId());

        stackAction("UpdateStack", STACK_NAME, template("second-name", "subnet-aaaa1111"));
        waitForStackStatus(STACK_NAME, "UPDATE_COMPLETE");
        assertEquals(vpcLinkId, stack(STACK_NAME).getOutputs().get("VpcLinkRef"), "a Name change keeps the same link");
        assertEquals("second-name", apiGatewayV2Service.getVpcLink(REGION, vpcLinkId).getName());

        stackAction("UpdateStack", STACK_NAME, template("second-name", "subnet-bbbb2222"));
        waitForStackStatus(STACK_NAME, "UPDATE_COMPLETE");
        Stack replaced = stack(STACK_NAME);
        String newVpcLinkId = replaced.getOutputs().get("VpcLinkRef");
        assertNotEquals(vpcLinkId, newVpcLinkId, "a SubnetIds change replaces the link");
        assertEquals(List.of("subnet-bbbb2222"), apiGatewayV2Service.getVpcLink(REGION, newVpcLinkId).getSubnetIds());
        assertNotFound(vpcLinkId);
        assertEquals(newVpcLinkId, integration(replaced).getConnectionId());

        stackAction("DeleteStack", STACK_NAME, null);
        waitForStackStatus(STACK_NAME, "DELETE_COMPLETE");
        assertNotFound(newVpcLinkId);
    }

    @Test
    void failedUpdateRestoresTheIntegrationConnectionAndDeletesTheNewLink() {
        String stackName = "vpclink-rollback-stack";
        stackAction("CreateStack", stackName,
                template("rollback-link", "subnet-aaaa1111", "{}", VPC_LINK_CONNECTION, ""));
        waitForStackStatus(stackName, "CREATE_COMPLETE");
        String vpcLinkId = stack(stackName).getOutputs().get("VpcLinkRef");

        stackAction("UpdateStack", stackName,
                template("rollback-link", "subnet-bbbb2222", "{}", VPC_LINK_CONNECTION, FAILING_RESOURCE));
        waitForStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        Stack rolledBack = stack(stackName);
        assertEquals(vpcLinkId, rolledBack.getOutputs().get("VpcLinkRef"));
        assertEquals(List.of("subnet-aaaa1111"), apiGatewayV2Service.getVpcLink(REGION, vpcLinkId).getSubnetIds());
        Integration integration = integration(rolledBack);
        assertEquals("VPC_LINK", integration.getConnectionType());
        assertEquals(vpcLinkId, integration.getConnectionId());
        assertEquals(1, apiGatewayV2Service.getVpcLinks(REGION).stream()
                .filter(link -> "rollback-link".equals(link.getName()))
                .count(), "the link created by the failed update is deleted");
    }

    @Test
    void removingTheConnectionFromTheTemplateResetsTheIntegrationToInternet() {
        String stackName = "vpclink-connection-stack";
        stackAction("CreateStack", stackName,
                template("connection-link", "subnet-aaaa1111", "{}", VPC_LINK_CONNECTION, ""));
        waitForStackStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, template("connection-link", "subnet-aaaa1111", "{}", "", ""));
        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        Integration integration = integration(stack(stackName));
        assertEquals("INTERNET", integration.getConnectionType());
        assertNull(integration.getConnectionId());
    }

    @Test
    void renamingALinkKeepsAReservedTagSetAtCreation() {
        String stackName = "vpclink-reserved-tag-stack";
        String tags = "{\"floci:note\":\"kept\",\"Env\":\"test\"}";
        stackAction("CreateStack", stackName, template("reserved-v1", "subnet-aaaa1111", tags, "", ""));
        waitForStackStatus(stackName, "CREATE_COMPLETE");
        String vpcLinkId = stack(stackName).getOutputs().get("VpcLinkRef");

        stackAction("UpdateStack", stackName, template("reserved-v2", "subnet-aaaa1111", tags, "", ""));
        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        VpcLink link = apiGatewayV2Service.getVpcLink(REGION, vpcLinkId);
        assertEquals("reserved-v2", link.getName());
        assertEquals(Map.of("floci:note", "kept", "Env", "test"), link.getTags());
    }

    private void assertNotFound(String vpcLinkId) {
        AwsException e = assertThrows(AwsException.class, () -> apiGatewayV2Service.getVpcLink(REGION, vpcLinkId));
        assertEquals("NotFoundException", e.getErrorCode());
    }

    private Integration integration(Stack stack) {
        StackResource api = stack.getResources().get("HttpApi");
        StackResource integration = stack.getResources().get("Integration");
        return apiGatewayV2Service.getIntegration(REGION, api.getPhysicalId(), integration.getPhysicalId());
    }

    private Stack stack(String stackName) {
        return cloudFormationService.describeStacks(stackName, REGION).getFirst();
    }

    private static void stackAction(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private void waitForStackStatus(String stackName, String expected) {
        String status = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try {
                status = cloudFormationService.describeStacks(stackName, REGION).getFirst().getStatus();
            } catch (AwsException e) {
                // A deleted stack can no longer be described by name.
                status = "DELETE_COMPLETE";
            }
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack status", e);
            }
        }
        throw new AssertionError("Stack did not reach " + expected + ", last status " + status);
    }

    private static String template(String linkName, String subnetId) {
        return """
                {"Resources":{
                  "VpcLink":{"Type":"AWS::ApiGatewayV2::VpcLink","Properties":{
                    "Name":"%s","SubnetIds":["%s"],"SecurityGroupIds":["sg-cccc3333"],"Tags":{"Env":"test"}}},
                  "HttpApi":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"vpclink-api","ProtocolType":"HTTP"}},
                  "Integration":{"Type":"AWS::ApiGatewayV2::Integration","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"IntegrationType":"HTTP_PROXY","IntegrationMethod":"ANY",
                    "IntegrationUri":"arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/alb/1/2",
                    "ConnectionType":"VPC_LINK","ConnectionId":{"Ref":"VpcLink"},
                    "PayloadFormatVersion":"1.0"}}},
                 "Outputs":{
                  "VpcLinkRef":{"Value":{"Ref":"VpcLink"}},
                  "VpcLinkIdAttr":{"Value":{"Fn::GetAtt":["VpcLink","VpcLinkId"]}}}}
                """.formatted(linkName, subnetId);
    }

    /**
     * A link, an HTTP API and an integration. {@code connection} adds integration properties and
     * {@code extraResources} adds resources, both as JSON fragments that start with a comma.
     */
    private static String template(String linkName, String subnetId, String tags, String connection,
                                   String extraResources) {
        return """
                {"Resources":{
                  "VpcLink":{"Type":"AWS::ApiGatewayV2::VpcLink","Properties":{
                    "Name":"%s","SubnetIds":["%s"],"Tags":%s}},
                  "HttpApi":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"vpclink-api","ProtocolType":"HTTP"}},
                  "Integration":{"Type":"AWS::ApiGatewayV2::Integration","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"IntegrationType":"HTTP_PROXY","IntegrationMethod":"ANY",
                    "IntegrationUri":"http://backend.example","PayloadFormatVersion":"1.0"%s}}%s},
                 "Outputs":{"VpcLinkRef":{"Value":{"Ref":"VpcLink"}}}}
                """.formatted(linkName, subnetId, tags, connection, extraResources);
    }
}
