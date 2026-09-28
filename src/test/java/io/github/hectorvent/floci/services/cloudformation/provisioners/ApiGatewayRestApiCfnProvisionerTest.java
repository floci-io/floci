package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REST API Gateway core types in isolation: the physical id and the exact Fn::GetAtt keys each
 * publishes, the inline-stage shortcut on a Deployment, and that only the RestApi deletes.
 */
class ApiGatewayRestApiCfnProvisionerTest {

    private final ApiGatewayService api = mock(ApiGatewayService.class);
    private final S3Service s3 = mock(S3Service.class);
    private final ApiGatewayRestApiCfnProvisioner provisioner =
            new ApiGatewayRestApiCfnProvisioner(api, s3, new ObjectMapper());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void restApiPublishesIdAndRootResourceId() throws Exception {
        RestApi created = new RestApi();
        created.setId("api-1");
        when(api.createRestApi(eq("us-east-1"), anyMap())).thenReturn(created);
        ApiGatewayResource root = new ApiGatewayResource();
        root.setId("root-1");
        when(api.getResources("us-east-1", "api-1")).thenReturn(List.of(root));

        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");
        provisioner.provision(r, props("{\"Name\": \"shop\"}"), ctx());

        assertEquals("api-1", r.getPhysicalId());
        assertEquals(Set.of("RestApiId", "RootResourceId"), r.getAttributes().keySet());
        assertEquals("api-1", r.getAttributes().get("RestApiId"));
        assertEquals("root-1", r.getAttributes().get("RootResourceId"));
        verify(api, never()).putRestApi(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void resourcePublishesResourceId() throws Exception {
        ApiGatewayResource res = new ApiGatewayResource();
        res.setId("res-1");
        when(api.createResource(eq("us-east-1"), eq("api-1"), eq("root-1"), anyMap())).thenReturn(res);

        StackResource r = resource("AWS::ApiGateway::Resource", "Res");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "ParentId": "root-1", "PathPart": "orders"}
                """), ctx());

        assertEquals("res-1", r.getPhysicalId());
        assertEquals("res-1", r.getAttributes().get("ResourceId"));
    }

    @Test
    void authorizerPublishesAuthorizerId() throws Exception {
        Authorizer authorizer = new Authorizer();
        authorizer.setId("auth-1");
        when(api.createAuthorizer(eq("us-east-1"), eq("api-1"), anyMap())).thenReturn(authorizer);

        StackResource r = resource("AWS::ApiGateway::Authorizer", "Auth");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "Name": "jwt", "Type": "TOKEN"}
                """), ctx());

        assertEquals("auth-1", r.getPhysicalId());
        assertEquals("auth-1", r.getAttributes().get("AuthorizerId"));
    }

    @Test
    void methodUsesTheCompositePhysicalIdAndProvisionsItsIntegration() throws Exception {
        StackResource r = resource("AWS::ApiGateway::Method", "Get");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "ResourceId": "res-1", "HttpMethod": "GET",
                 "Integration": {"Type": "AWS_PROXY", "IntegrationHttpMethod": "POST", "Uri": "arn:..."}}
                """), ctx());

        assertEquals("api-1-res-1-GET", r.getPhysicalId());
        verify(api).putMethod(eq("us-east-1"), eq("api-1"), eq("res-1"), eq("GET"), anyMap());
        verify(api).putIntegration(eq("us-east-1"), eq("api-1"), eq("res-1"), eq("GET"), anyMap());
    }

    @Test
    void methodProvisionsMockTemplatesAndCorsResponses() throws Exception {
        StackResource r = resource("AWS::ApiGateway::Method", "Options");
        provisioner.provision(r, props("""
                {"RestApiId":"api-1","ResourceId":"res-1","HttpMethod":"OPTIONS",
                 "MethodResponses":[{"StatusCode":"200","ResponseParameters":{
                   "method.response.header.Access-Control-Allow-Origin":true}}],
                 "Integration":{"Type":"MOCK","RequestTemplates":{
                   "application/json":"{\\\"statusCode\\\":200}"},
                   "IntegrationResponses":[{"StatusCode":"200","ResponseParameters":{
                     "method.response.header.Access-Control-Allow-Origin":"'*'"},
                     "ResponseTemplates":{"application/json":"{}"}}]}}
                """), ctx());

        verify(api).putMethodResponse("us-east-1", "api-1", "res-1", "OPTIONS", "200",
                Map.of("responseParameters", Map.of(
                        "method.response.header.Access-Control-Allow-Origin", true)));
        verify(api).putIntegration(eq("us-east-1"), eq("api-1"), eq("res-1"), eq("OPTIONS"),
                org.mockito.ArgumentMatchers.argThat(request -> Map.of(
                        "application/json", "{\"statusCode\":200}")
                        .equals(request.get("requestTemplates"))));
        verify(api).putIntegrationResponse(eq("us-east-1"), eq("api-1"), eq("res-1"), eq("OPTIONS"), eq("200"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        Map.of("method.response.header.Access-Control-Allow-Origin", "'*'")
                                .equals(request.get("responseParameters"))
                        && Map.of("application/json", "{}").equals(request.get("responseTemplates"))));
    }

    @Test
    void deploymentPublishesDeploymentIdAndCreatesTheInlineStage() throws Exception {
        when(api.createDeployment(eq("us-east-1"), eq("api-1"), anyMap()))
                .thenReturn(new Deployment("dep-1", null, 0L));

        StackResource r = resource("AWS::ApiGateway::Deployment", "Dep");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "StageName": "prod"}
                """), ctx());

        assertEquals("dep-1", r.getPhysicalId());
        assertEquals("dep-1", r.getAttributes().get("DeploymentId"));
        verify(api).createStage(eq("us-east-1"), eq("api-1"), anyMap());
    }

    @Test
    void stageUsesTheStageNameAsPhysicalId() throws Exception {
        StackResource r = resource("AWS::ApiGateway::Stage", "Stage");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "StageName": "prod", "DeploymentId": "dep-1"}
                """), ctx());

        assertEquals("prod", r.getPhysicalId());
        verify(api).createStage(eq("us-east-1"), eq("api-1"), anyMap());
    }

    @Test
    void deleteRemovesOnlyTheRestApi() {
        provisioner.delete("AWS::ApiGateway::RestApi", "api-1", "us-east-1");
        verify(api).deleteRestApi("us-east-1", "api-1");

        provisioner.delete("AWS::ApiGateway::Method", "api-1-res-1-GET", "us-east-1");
        verify(api, never()).deleteRestApi("us-east-1", "api-1-res-1-GET");
    }

    @Test
    void deleteToleratesARestApiAlreadyGone() {
        doThrow(new AwsException("NotFoundException", "Invalid API id specified", 404))
                .when(api).deleteRestApi("us-east-1", "api-1");

        assertDoesNotThrow(() -> provisioner.delete("AWS::ApiGateway::RestApi", "api-1", "us-east-1"));
    }

    @Test
    void deletePropagatesAnUnexpectedRestApiError() {
        doThrow(new AwsException("TooManyRequestsException", "rate exceeded", 429))
                .when(api).deleteRestApi("us-east-1", "api-1");

        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::ApiGateway::RestApi", "api-1", "us-east-1"));
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
