package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import io.github.hectorvent.floci.services.apigateway.model.EndpointConfiguration;
import io.github.hectorvent.floci.services.apigateway.model.EndpointType;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        when(api.findRootResourceId("us-east-1", "api-1")).thenReturn(Optional.of("root-1"));

        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");
        provisioner.provision(r, props("{\"Name\": \"shop\"}"), ctx());

        assertEquals("api-1", r.getPhysicalId());
        assertEquals(Set.of("RestApiId", "RootResourceId"), r.getAttributes().keySet());
        assertEquals("api-1", r.getAttributes().get("RestApiId"));
        assertEquals("root-1", r.getAttributes().get("RootResourceId"));
        verify(api, never()).putRestApi(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void restApiUpdateKeepsTheApiAndPatchesItInPlace() throws Exception {
        existingApi(restApi("api-1", "shop", "v1", "REGIONAL"));

        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");
        provisioner.provision(r, props("{\"Description\": \"v2\"}"), ctx("api-1"));

        assertEquals("api-1", r.getPhysicalId());
        assertEquals("root-1", r.getAttributes().get("RootResourceId"));
        verify(api, never()).createRestApi(anyString(), anyMap());
        // An undeclared Name keeps the API's own rather than a newly generated one, and an
        // undeclared EndpointConfiguration is left alone.
        verify(api).updateRestApi("us-east-1", "api-1", List.of(
                op("replace", "/name", "shop"), op("replace", "/description", "v2")));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void restApiUpdateAppliesAChangedEndpointConfiguration() throws Exception {
        existingApi(restApi("api-1", "shop", null, "REGIONAL"));

        provisioner.provision(resource("AWS::ApiGateway::RestApi", "Api"), props("""
                {"Name": "shop", "EndpointConfiguration": {"Types": ["PRIVATE"], "VpcEndpointIds": ["vpce-1"]}}
                """), ctx("api-1"));

        // The type's path names the type the API has now.
        verify(api).updateRestApi("us-east-1", "api-1", List.of(
                op("replace", "/name", "shop"), op("replace", "/description", null),
                op("replace", "/endpointConfiguration/types/REGIONAL", "PRIVATE"),
                op("add", "/endpointConfiguration/vpcEndpointIds", "vpce-1")));
    }

    @Test
    void restApiUpdateRejectsMoreThanOneEndpointTypeBeforeChangingAnything() throws Exception {
        existingApi(restApi("api-1", "shop", "v1", "REGIONAL"));
        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");

        // CreateRestApi rejects the same list.
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"Name": "shop", "Description": "v2", "EndpointConfiguration": {"Types": ["REGIONAL", "EDGE"]}}
                """), ctx("api-1")));

        assertEquals("BadRequestException", e.getErrorCode());
        verify(api, never()).updateRestApi(anyString(), anyString(), any());
        assertFalse(r.getAttributes().containsKey(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void restApiRollbackPutsBackWhatTheUpdatePatched() throws Exception {
        existingApi(restApi("api-1", "shop", "v1", "REGIONAL"));
        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");
        provisioner.provision(r, props("""
                {"Name": "shop", "Description": "v2",
                 "EndpointConfiguration": {"Types": ["PRIVATE"], "VpcEndpointIds": ["vpce-1"]}}
                """), ctx("api-1"));
        when(api.getRestApi("us-east-1", "api-1")).thenReturn(restApi("api-1", "shop", "v2", "PRIVATE", "vpce-1"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(api).updateRestApi("us-east-1", "api-1", List.of(
                op("replace", "/name", "shop"), op("replace", "/description", "v1"),
                op("replace", "/endpointConfiguration/types/PRIVATE", "REGIONAL"),
                op("remove", "/endpointConfiguration/vpcEndpointIds", "vpce-1")));
        assertFalse(r.getAttributes().containsKey(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void restApiRollbackOfAnUpdateThatAppliedABodyIsNotImplemented() throws Exception {
        existingApi(restApi("api-1", "shop", "v1", "REGIONAL"));
        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");
        provisioner.provision(r, props("""
                {"Name": "shop", "Body": {"openapi": "3.0.1", "info": {"title": "shop"}, "paths": {}}}
                """), ctx("api-1"));
        verify(api).putRestApi(eq("us-east-1"), eq("api-1"), eq("overwrite"), anyString());

        // PutRestApi replaced the API's resources, methods and models, which the snapshot does not hold.
        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void restApiUpdateWhoseBodyIsRejectedPutsThePatchBack() throws Exception {
        existingApi(restApi("api-1", "shop", "v1", "REGIONAL"));
        when(api.putRestApi(eq("us-east-1"), eq("api-1"), eq("overwrite"), anyString()))
                .thenThrow(new AwsException("BadRequestException", "Invalid OpenAPI input", 400));
        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"Name": "shop", "Description": "v2", "Body": {"openapi": "3.0.1", "paths": {}}}
                """), ctx("api-1")));

        verify(api).updateRestApi("us-east-1", "api-1", List.of(
                op("replace", "/name", "shop"), op("replace", "/description", "v2")));
        verify(api).updateRestApi("us-east-1", "api-1", List.of(
                op("replace", "/name", "shop"), op("replace", "/description", "v1")));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void restApiUpdateRecreatesAnApiRemovedOutOfBandAndItsRollbackDeletesIt() throws Exception {
        when(api.getRestApi("us-east-1", "api-1"))
                .thenThrow(new AwsException("NotFoundException", "Invalid API id specified", 404));
        when(api.createRestApi(eq("us-east-1"), anyMap())).thenReturn(restApi("api-2", "shop", null, "REGIONAL"));
        when(api.findRootResourceId("us-east-1", "api-2")).thenReturn(Optional.of("root-2"));
        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");

        provisioner.provision(r, props("{\"Name\": \"shop\"}"), ctx("api-1"));
        assertEquals("api-2", r.getPhysicalId());
        assertEquals("root-2", r.getAttributes().get("RootResourceId"));

        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals("api-1", r.getPhysicalId());
        verify(api).deleteRestApi("us-east-1", "api-2");
    }

    @Test
    void restApiUpdateDeletesTheRecreatedApiWhenItsBodyIsRejected() throws Exception {
        when(api.getRestApi("us-east-1", "api-1"))
                .thenThrow(new AwsException("NotFoundException", "Invalid API id specified", 404));
        when(api.createRestApi(eq("us-east-1"), anyMap())).thenReturn(restApi("api-2", "shop", null, "REGIONAL"));
        when(api.findRootResourceId("us-east-1", "api-2")).thenReturn(Optional.of("root-2"));
        when(api.putRestApi(eq("us-east-1"), eq("api-2"), eq("overwrite"), anyString()))
                .thenThrow(new AwsException("BadRequestException", "Invalid OpenAPI input", 400));
        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"Name": "shop", "Body": {"openapi": "3.0.1", "paths": {}}}
                """), ctx("api-1")));

        // The failed update leaves the stack with its previous resource, so nothing else would delete it.
        verify(api).deleteRestApi("us-east-1", "api-2");
    }

    @Test
    void restApiUpdateListsARecreatedApiItCannotDeleteAndReportsTheRollbackFailure() throws Exception {
        when(api.getRestApi("us-east-1", "api-1"))
                .thenThrow(new AwsException("NotFoundException", "Invalid API id specified", 404));
        when(api.createRestApi(eq("us-east-1"), anyMap())).thenReturn(restApi("api-2", "shop", null, "REGIONAL"));
        when(api.findRootResourceId("us-east-1", "api-2")).thenReturn(Optional.of("root-2"));
        when(api.putRestApi(eq("us-east-1"), eq("api-2"), eq("overwrite"), anyString()))
                .thenThrow(new AwsException("BadRequestException", "Invalid OpenAPI input", 400));
        doThrow(new AwsException("TooManyRequestsException", "Too Many Requests", 429))
                .when(api).deleteRestApi("us-east-1", "api-2");
        StackResource r = resource("AWS::ApiGateway::RestApi", "Api");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"Name": "shop", "Body": {"openapi": "3.0.1", "paths": {}}}
                """), ctx("api-1")));

        assertEquals("BadRequestException", failure.getErrorCode());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(r.getAttributes().getOrDefault(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, "").contains("api-2"));
        // Listed for the next cleanup, which the resource the engine restores inherits.
        assertTrue(r.getAttributes().getOrDefault(CfnRollback.REPLACEMENT_CLEANUP_ATTR, "").contains("\"api-2\""));
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

    /**
     * A COGNITO_USER_POOLS authorizer with no provider ARNs rejects every token, so dropping the
     * property turns a correct template into a 401 on every request.
     */
    @Test
    void authorizerForwardsProviderArns() throws Exception {
        Authorizer authorizer = new Authorizer();
        authorizer.setId("auth-1");
        when(api.createAuthorizer(eq("us-east-1"), eq("api-1"), anyMap())).thenReturn(authorizer);

        StackResource r = resource("AWS::ApiGateway::Authorizer", "Auth");
        provisioner.provision(r, props("""
                {"RestApiId": "api-1", "Name": "cognito", "Type": "COGNITO_USER_POOLS",
                 "IdentitySource": "method.request.header.Authorization",
                 "ProviderARNs": ["arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_abc"]}
                """), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(api).createAuthorizer(eq("us-east-1"), eq("api-1"), request.capture());
        assertEquals(List.of("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_abc"),
                request.getValue().get("providerARNs"));
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
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            List<String> values = new ArrayList<>();
            ((JsonNode) inv.getArgument(0)).forEach(value -> values.add(value.asText()));
            return values;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    /** Stubs what an in-place update of {@code existing} looks up and patches. */
    private void existingApi(RestApi existing) {
        when(api.getRestApi("us-east-1", existing.getId())).thenReturn(existing);
        when(api.updateRestApi(eq("us-east-1"), eq(existing.getId()), any())).thenReturn(existing);
        when(api.findRootResourceId("us-east-1", existing.getId())).thenReturn(Optional.of("root-1"));
    }

    private static RestApi restApi(String id, String name, String description, String endpointType,
                                   String... vpcEndpointIds) {
        RestApi restApi = new RestApi();
        restApi.setId(id);
        restApi.setName(name);
        restApi.setDescription(description);
        EndpointConfiguration endpoint = new EndpointConfiguration();
        endpoint.setTypes(List.of(EndpointType.valueOf(endpointType)));
        endpoint.setVpcEndpointIds(List.of(vpcEndpointIds));
        restApi.setEndpointConfiguration(endpoint);
        return restApi;
    }

    /** A patch operation as the provisioner builds one; unlike {@code Map.of}, it takes a null value. */
    private static Map<String, String> op(String op, String path, String value) {
        Map<String, String> operation = new HashMap<>();
        operation.put("op", op);
        operation.put("path", path);
        operation.put("value", value);
        return operation;
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
