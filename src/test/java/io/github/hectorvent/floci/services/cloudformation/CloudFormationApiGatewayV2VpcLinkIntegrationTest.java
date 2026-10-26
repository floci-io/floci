package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.VpcLink;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.FAILING_RESOURCE;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.REGION;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.integration;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.stack;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.stackAction;
import static io.github.hectorvent.floci.services.cloudformation.ApiGatewayV2StackTestSupport.waitForStackStatus;
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

    private static final String STACK_NAME = "vpclink-stack";
    private static final String VPC_LINK_CONNECTION =
            ",\"ConnectionType\":\"VPC_LINK\",\"ConnectionId\":{\"Ref\":\"VpcLink\"}";

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
        waitForStackStatus(cloudFormationService, STACK_NAME, "CREATE_COMPLETE");

        Stack created = stack(cloudFormationService, STACK_NAME);
        String vpcLinkId = created.getOutputs().get("VpcLinkRef");
        assertEquals(vpcLinkId, created.getOutputs().get("VpcLinkIdAttr"));
        VpcLink link = apiGatewayV2Service.getVpcLink(REGION, vpcLinkId);
        assertEquals("first-name", link.getName());
        assertEquals(List.of("subnet-aaaa1111"), link.getSubnetIds());
        assertEquals(Map.of("Env", "test"), link.getTags());
        Integration integration = integration(apiGatewayV2Service, created);
        assertEquals("VPC_LINK", integration.getConnectionType());
        assertEquals(vpcLinkId, integration.getConnectionId());

        stackAction("UpdateStack", STACK_NAME, template("second-name", "subnet-aaaa1111"));
        waitForStackStatus(cloudFormationService, STACK_NAME, "UPDATE_COMPLETE");
        assertEquals(vpcLinkId, stack(cloudFormationService, STACK_NAME).getOutputs().get("VpcLinkRef"), "a Name change keeps the same link");
        assertEquals("second-name", apiGatewayV2Service.getVpcLink(REGION, vpcLinkId).getName());

        stackAction("UpdateStack", STACK_NAME, template("second-name", "subnet-bbbb2222"));
        waitForStackStatus(cloudFormationService, STACK_NAME, "UPDATE_COMPLETE");
        Stack replaced = stack(cloudFormationService, STACK_NAME);
        String newVpcLinkId = replaced.getOutputs().get("VpcLinkRef");
        assertNotEquals(vpcLinkId, newVpcLinkId, "a SubnetIds change replaces the link");
        assertEquals(List.of("subnet-bbbb2222"), apiGatewayV2Service.getVpcLink(REGION, newVpcLinkId).getSubnetIds());
        assertNotFound(vpcLinkId);
        assertEquals(newVpcLinkId, integration(apiGatewayV2Service, replaced).getConnectionId());

        stackAction("DeleteStack", STACK_NAME, null);
        waitForStackStatus(cloudFormationService, STACK_NAME, "DELETE_COMPLETE");
        assertNotFound(newVpcLinkId);
    }

    @Test
    void failedUpdateRestoresTheIntegrationConnectionAndDeletesTheNewLink() {
        String stackName = "vpclink-rollback-stack";
        stackAction("CreateStack", stackName,
                template("rollback-link", "subnet-aaaa1111", "{}", VPC_LINK_CONNECTION, ""));
        waitForStackStatus(cloudFormationService, stackName, "CREATE_COMPLETE");
        String vpcLinkId = stack(cloudFormationService, stackName).getOutputs().get("VpcLinkRef");

        stackAction("UpdateStack", stackName,
                template("rollback-link", "subnet-bbbb2222", "{}", VPC_LINK_CONNECTION, FAILING_RESOURCE));
        waitForStackStatus(cloudFormationService, stackName, "UPDATE_ROLLBACK_COMPLETE");

        Stack rolledBack = stack(cloudFormationService, stackName);
        assertEquals(vpcLinkId, rolledBack.getOutputs().get("VpcLinkRef"));
        assertEquals(List.of("subnet-aaaa1111"), apiGatewayV2Service.getVpcLink(REGION, vpcLinkId).getSubnetIds());
        Integration integration = integration(apiGatewayV2Service, rolledBack);
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
        waitForStackStatus(cloudFormationService, stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, template("connection-link", "subnet-aaaa1111", "{}", "", ""));
        waitForStackStatus(cloudFormationService, stackName, "UPDATE_COMPLETE");

        Integration integration = integration(apiGatewayV2Service, stack(cloudFormationService, stackName));
        assertEquals("INTERNET", integration.getConnectionType());
        assertNull(integration.getConnectionId());
    }

    @Test
    void renamingALinkKeepsAReservedTagSetAtCreation() {
        String stackName = "vpclink-reserved-tag-stack";
        String tags = "{\"floci:note\":\"kept\",\"Env\":\"test\"}";
        stackAction("CreateStack", stackName, template("reserved-v1", "subnet-aaaa1111", tags, "", ""));
        waitForStackStatus(cloudFormationService, stackName, "CREATE_COMPLETE");
        String vpcLinkId = stack(cloudFormationService, stackName).getOutputs().get("VpcLinkRef");

        stackAction("UpdateStack", stackName, template("reserved-v2", "subnet-aaaa1111", tags, "", ""));
        waitForStackStatus(cloudFormationService, stackName, "UPDATE_COMPLETE");

        VpcLink link = apiGatewayV2Service.getVpcLink(REGION, vpcLinkId);
        assertEquals("reserved-v2", link.getName());
        assertEquals(Map.of("floci:note", "kept", "Env", "test"), link.getTags());
    }

    private void assertNotFound(String vpcLinkId) {
        AwsException e = assertThrows(AwsException.class, () -> apiGatewayV2Service.getVpcLink(REGION, vpcLinkId));
        assertEquals("NotFoundException", e.getErrorCode());
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
