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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * publishes, the inline-stage shortcut on a Deployment, what an update keeps or replaces, and what
 * each type deletes.
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
    void resourceUpdateKeepsAnUnchangedResource() throws Exception {
        when(api.getResource("us-east-1", "api-1", "res-1")).thenReturn(apiResource("res-1", "root-1", "orders"));

        StackResource r = resource("AWS::ApiGateway::Resource", "Res");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "ParentId": "root-1", "PathPart": "orders"}
                """), ctx("res-1"));

        assertEquals("res-1", r.getPhysicalId());
        assertEquals("res-1", r.getAttributes().get("ResourceId"));
        verify(api, never()).createResource(anyString(), anyString(), anyString(), anyMap());
        verify(api, never()).deleteResource(anyString(), anyString(), anyString());
    }

    @Test
    void resourceReplacementDeletesTheDisplacedResourceOnlyOnceTheUpdateCommits() throws Exception {
        StackResource r = replacedResource();

        assertEquals("res-2", r.getPhysicalId());
        verify(api, never()).deleteResource(anyString(), anyString(), anyString());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("res-1", provisioner.updateCleanupPhysicalId(r));

        assertTrue(provisioner.completeUpdate(r).complete());
        verify(api).deleteResource("us-east-1", "api-1", "res-1");
    }

    @Test
    void resourceReplacementRollsBackToTheDisplacedResource() throws Exception {
        StackResource r = replacedResource();

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals("res-1", r.getPhysicalId());
        assertEquals("res-1", r.getAttributes().get("ResourceId"));
        verify(api).deleteResource("us-east-1", "api-1", "res-2");
        verify(api, never()).deleteResource("us-east-1", "api-1", "res-1");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollbackOfAKeptResourceHasNothingToUndo() throws Exception {
        when(api.getResource("us-east-1", "api-1", "res-1")).thenReturn(apiResource("res-1", "root-1", "orders"));
        StackResource r = resource("AWS::ApiGateway::Resource", "Res");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "ParentId": "root-1", "PathPart": "orders"}
                """), ctx("res-1"));

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals("res-1", r.getPhysicalId());
        verify(api, never()).createResource(anyString(), anyString(), anyString(), anyMap());
        verify(api, never()).deleteResource(anyString(), anyString(), anyString());
    }

    @Test
    void rollbackOfAKeptMethodIsNotImplemented() throws Exception {
        StackResource r = resource("AWS::ApiGateway::Method", "Get");
        provisioner.provision(r, props("""
                {"RestApiId": "a1b2c3", "ResourceId": "d4e5f6", "HttpMethod": "GET"}
                """), ctx("a1b2c3-d4e5f6-GET"));

        // putMethod rewrote the method in place, and nothing kept what it was before.
        assertFalse(provisioner.rollbackUpdate(r));
        verify(api, never()).deleteMethod(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void resourceReplacementKeepsTheDisplacedResourceUnderRetain() throws Exception {
        StackResource r = replacedResource();
        r.setUpdateReplacePolicy("Retain");

        provisioner.completeUpdate(r);

        verify(api, never()).deleteResource(anyString(), anyString(), anyString());
    }

    @Test
    void resourceDeleteRemovesItFromTheApiItWasCreatedOn() throws Exception {
        when(api.createResource(eq("us-east-1"), eq("api-1"), eq("root-1"), anyMap()))
                .thenReturn(apiResource("res-1", "root-1", "orders"));
        StackResource r = resource("AWS::ApiGateway::Resource", "Res");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "ParentId": "root-1", "PathPart": "orders"}
                """), ctx());

        provisioner.delete(r, "us-east-1");

        verify(api).deleteResource("us-east-1", "api-1", "res-1");
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
    void methodReplacementDeletesTheDisplacedMethodAtItsRecordedLocation() throws Exception {
        // Neither the API id nor the HTTP method can be told apart from the joining hyphens of
        // "my-api-d4e5f6-X-OLD", so only the recorded location names the method to delete.
        StackResource r = resource("AWS::ApiGateway::Method", "Custom");
        provisioner.provision(r, props("""
                {"RestApiId": "my-api", "ResourceId": "d4e5f6", "HttpMethod": "X-OLD"}
                """), ctx());
        provisioner.provision(r, props("""
                {"RestApiId": "my-api", "ResourceId": "d4e5f6", "HttpMethod": "POST"}
                """), ctx("my-api-d4e5f6-X-OLD"));

        assertEquals("my-api-d4e5f6-POST", r.getPhysicalId());
        verify(api, never()).deleteMethod(anyString(), anyString(), anyString(), anyString());

        provisioner.completeUpdate(r);
        verify(api).deleteMethod("us-east-1", "my-api", "d4e5f6", "X-OLD");
    }

    @Test
    void methodUpdateOfTheSameMethodDeletesNothing() throws Exception {
        StackResource r = resource("AWS::ApiGateway::Method", "Get");
        provisioner.provision(r, props("""
                {"RestApiId": "a1b2c3", "ResourceId": "d4e5f6", "HttpMethod": "GET"}
                """), ctx("a1b2c3-d4e5f6-get"));

        assertEquals("a1b2c3-d4e5f6-get", r.getPhysicalId());
        verify(api).putMethod(eq("us-east-1"), eq("a1b2c3"), eq("d4e5f6"), eq("GET"), anyMap());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void methodDeleteUsesTheRecordedLocationOfAHyphenatedMethod() throws Exception {
        StackResource r = resource("AWS::ApiGateway::Method", "Custom");
        provisioner.provision(r, props("""
                {"RestApiId": "my-api", "ResourceId": "d4e5f6", "HttpMethod": "X-CUSTOM"}
                """), ctx());

        provisioner.delete(r, "us-east-1");

        verify(api).deleteMethod("us-east-1", "my-api", "d4e5f6", "X-CUSTOM");
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
    void deleteRemovesTheRestApi() {
        provisioner.delete("AWS::ApiGateway::RestApi", "api-1", "us-east-1");
        verify(api).deleteRestApi("us-east-1", "api-1");
    }

    @Test
    void deleteOfAMethodWithNoRecordedLocationSplitsItsId() {
        // A method provisioned before locations were recorded has only its id. A custom API id can
        // contain hyphens, a resource id cannot.
        StackResource r = resource("AWS::ApiGateway::Method", "Get");
        r.setPhysicalId("my-api-d4e5f6-GET");

        provisioner.delete(r, "us-east-1");

        verify(api).deleteMethod("us-east-1", "my-api", "d4e5f6", "GET");
        verify(api, never()).deleteRestApi(anyString(), anyString());
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
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    /** A Resource created as res-1 at /orders, then replaced by res-2 at /items. */
    private StackResource replacedResource() throws Exception {
        when(api.createResource(eq("us-east-1"), eq("api-1"), eq("root-1"), anyMap()))
                .thenReturn(apiResource("res-1", "root-1", "orders"), apiResource("res-2", "root-1", "items"));
        when(api.getResource("us-east-1", "api-1", "res-1")).thenReturn(apiResource("res-1", "root-1", "orders"));
        StackResource r = resource("AWS::ApiGateway::Resource", "Res");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "ParentId": "root-1", "PathPart": "orders"}
                """), ctx());
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "ParentId": "root-1", "PathPart": "items"}
                """), ctx("res-1"));
        return r;
    }

    private static ApiGatewayResource apiResource(String id, String parentId, String pathPart) {
        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setId(id);
        resource.setParentId(parentId);
        resource.setPathPart(pathPart);
        return resource;
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
