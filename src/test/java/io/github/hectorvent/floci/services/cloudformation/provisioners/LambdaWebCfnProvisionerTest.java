package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaWebCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String FN_ARN = "arn:aws:lambda:us-east-1:000000000000:web-function/app";
    private static final String OTHER_FN_ARN = "arn:aws:lambda:us-east-1:000000000000:web-function/other";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final Set<String> FUNCTION_ATTRIBUTES = Set.of("FunctionArn", "State", "StateReason", "CreatedAt", "UpdatedAt");
    private static final Set<String> REVISION_ATTRIBUTES =
            Set.of("FunctionArn", "RevisionArn", "RevisionId", "State", "StateReason", "CreatedAt");
    private static final Set<String> ENDPOINT_ATTRIBUTES =
            Set.of("FunctionArn", "EndpointArn", "DomainName", "RegionalEndpoints", "State", "StateReason", "CreatedAt",
                    "UpdatedAt");

    private final LambdaWebService service = mock(LambdaWebService.class);
    private final LambdaWebCfnProvisioner provisioner = new LambdaWebCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);

    private ProvisionContext ctx(String priorPhysicalId) {
        return ctx(priorPhysicalId, null);
    }

    private ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, ACCOUNT, "my-stack", priorPhysicalId, event -> {}, priorProperties);
    }

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Res");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static StackResource updateOf(StackResource prior) {
        StackResource r = resource(prior.getResourceType());
        r.setPhysicalId(prior.getPhysicalId());
        r.setAttributes(new HashMap<>(prior.getAttributes()));
        return r;
    }

    private static WebFunction function(String arn) {
        WebFunction fn = new WebFunction();
        fn.functionName = arn.substring(arn.lastIndexOf('/') + 1);
        fn.functionArn = arn;
        fn.createdAt = Instant.parse("2026-10-14T20:37:19.533870Z");
        fn.updatedAt = fn.createdAt;
        return fn;
    }

    private static WebFunctionRevision revision(String id) {
        WebFunctionRevision revision = new WebFunctionRevision();
        revision.revisionId = id;
        revision.revisionArn = FN_ARN + "/revision/" + id;
        revision.functionArn = FN_ARN;
        revision.createdAt = Instant.parse("2026-10-14T20:37:19.533870Z");
        return revision;
    }

    private static WebFunctionEndpoint endpoint(String name, String type, List<String> regions) {
        WebFunctionEndpoint endpoint = new WebFunctionEndpoint();
        endpoint.endpointName = name;
        endpoint.endpointArn = FN_ARN + "/endpoint/" + name;
        endpoint.functionArn = FN_ARN;
        endpoint.endpointType = type;
        endpoint.regions = regions;
        endpoint.domainName = "abc.001.lambda-web.us-east-1.localhost:4566";
        endpoint.createdAt = Instant.parse("2026-10-14T20:37:19.533870Z");
        endpoint.updatedAt = endpoint.createdAt;
        return endpoint;
    }

    private ObjectNode revisionProps() {
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app").put("Description", "first");
        ObjectNode build = props.putObject("BuildConfig");
        build.putObject("CodeConfig").putObject("S3Object").put("Bucket", "code").put("Key", "app.zip");
        build.putObject("RuntimeConfig").put("Runtime", "nodejs24.x");
        ObjectNode service = props.putObject("ServiceConfig").put("ExecutionRoleArn", ROLE).put("TimeoutSeconds", 45);
        service.putObject("EnvironmentVariables").put("APP_VERSION", "2");
        return props;
    }

    private ObjectNode endpointProps(String type) {
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app").put("EndpointName", "prod")
                .put("EndpointType", type).put("AuthType", "IamAuth");
        props.putArray("RevisionWeights").addObject().put("RevisionId", "rev-aaaaa").put("Weight", 100);
        if (!"HomeRegion".equals(type)) {
            props.putArray("Regions").add("us-west-2");
        }
        return props;
    }

    @Test
    void functionIsCreatedBareAndIdentifiedByArn() {
        WebFunction fn = function(FN_ARN);
        fn.tags.put("env", "prod");
        when(service.createFunctionShell(REGION, "app", Map.of("env", "prod"))).thenReturn(fn);
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "prod");
        StackResource r = resource(LambdaWebCfnProvisioner.FUNCTION);

        provisioner.provision(r, props, ctx(null));

        assertEquals(FN_ARN, r.getPhysicalId());
        assertEquals(FUNCTION_ATTRIBUTES, r.getAttributes().keySet());
        assertEquals(FN_ARN, r.getAttributes().get("FunctionArn"));
        assertEquals("Active", r.getAttributes().get("State"));
        assertEquals("The function is active.", r.getAttributes().get("StateReason"));
        assertEquals("2026-10-14T20:37:19.533870Z", r.getAttributes().get("CreatedAt"));
        assertEquals("2026-10-14T20:37:19.533870Z", r.getAttributes().get("UpdatedAt"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void functionUpdateWithTheSameNameReconcilesTags() {
        WebFunction fn = function(FN_ARN);
        fn.tags.put("env", "prod");
        when(service.getFunction(REGION, "app")).thenReturn(fn);
        WebFunction retagged = function(FN_ARN);
        retagged.tags.put("team", "web");
        retagged.updatedAt = fn.updatedAt.plusSeconds(5);
        when(service.replaceTags(REGION, "app", Map.of("team", "web"))).thenReturn(retagged);
        StackResource prior = resource(LambdaWebCfnProvisioner.FUNCTION);
        prior.setPhysicalId(FN_ARN);
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "web");

        StackResource r = updateOf(prior);
        provisioner.provision(r, props, ctx(FN_ARN));

        verify(service).replaceTags(REGION, "app", Map.of("team", "web"));
        verify(service, never()).createFunctionShell(anyString(), anyString(), any());
        assertEquals(FN_ARN, r.getPhysicalId());
        assertEquals(LambdaWebService.formatTimestamp(retagged.updatedAt), r.getAttributes().get("UpdatedAt"));
        assertFalse(provisioner.hasReplacementUpdate(r));
        // A failed stack update puts the prior tags back.
        assertTrue(r.getAttributes().containsKey(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR));
        assertTrue(provisioner.rollbackUpdate(r));
        verify(service).replaceTags(REGION, FN_ARN, Map.of("env", "prod"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void functionUpdateWithTheSameTagsWritesNothing() {
        WebFunction fn = function(FN_ARN);
        fn.tags.put("env", "prod");
        when(service.getFunction(REGION, "app")).thenReturn(fn);
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "prod");
        StackResource r = resource(LambdaWebCfnProvisioner.FUNCTION);
        r.setPhysicalId(FN_ARN);

        provisioner.provision(r, props, ctx(FN_ARN));

        verify(service, never()).replaceTags(anyString(), anyString(), any());
        assertEquals(FN_ARN, r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR));
        assertFalse(provisioner.rollbackUpdate(r), "nothing changed in place, so there is nothing to put back");
    }

    @Test
    void functionUpdateWithANewNameCreatesAndDisplacesThePriorFunction() {
        when(service.getFunction(REGION, "app2"))
                .thenThrow(new AwsException("ResourceNotFoundException", "The function does not exist.", 404));
        when(service.createFunctionShell(REGION, "app2", Map.of()))
                .thenReturn(function("arn:aws:lambda:us-east-1:000000000000:web-function/app2"));
        StackResource r = resource(LambdaWebCfnProvisioner.FUNCTION);
        r.setPhysicalId(FN_ARN);

        provisioner.provision(r, mapper.createObjectNode().put("FunctionName", "app2"), ctx(FN_ARN));

        assertEquals("arn:aws:lambda:us-east-1:000000000000:web-function/app2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(FN_ARN, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void integerPropertiesGivenAsTextArriveAsNumbers() {
        when(service.createRevision(eq(REGION), eq("app"), any())).thenReturn(revision("rev-aaaaa"));
        when(service.createEndpoint(eq(REGION), eq("app"), any()))
                .thenReturn(endpoint("prod", "HomeRegion", List.of("us-east-1")));
        ObjectNode revisionProps = revisionProps();
        ((ObjectNode) revisionProps.get("ServiceConfig")).put("TimeoutSeconds", "45").put("MaxConcurrencyPerEnvironment", "8");
        ObjectNode endpointProps = endpointProps("HomeRegion");
        ((ObjectNode) endpointProps.get("RevisionWeights").get(0)).put("Weight", "100");
        endpointProps.putObject("ScalingConfig").put("MaxEnvironments", "4");
        endpointProps.putObject("ThrottleConfig").put("RateLimit", "x");

        provisioner.provision(resource(LambdaWebCfnProvisioner.REVISION), revisionProps, ctx(null));
        provisioner.provision(resource(LambdaWebCfnProvisioner.ENDPOINT), endpointProps, ctx(null));

        ArgumentCaptor<JsonNode> revision = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createRevision(eq(REGION), eq("app"), revision.capture());
        assertTrue(revision.getValue().path("serviceConfig").path("timeoutSeconds").isIntegralNumber());
        assertEquals(45, revision.getValue().path("serviceConfig").path("timeoutSeconds").intValue());
        assertEquals(8, revision.getValue().path("serviceConfig").path("maxConcurrencyPerEnvironment").intValue());
        ArgumentCaptor<JsonNode> endpoint = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createEndpoint(eq(REGION), eq("app"), endpoint.capture());
        assertEquals(100, endpoint.getValue().path("revisionWeights").get(0).path("weight").intValue());
        assertEquals(4, endpoint.getValue().path("scalingConfig").path("maxEnvironments").intValue());
        assertTrue(endpoint.getValue().path("throttleConfig").path("rateLimit").isTextual(),
                "text that is not an integer is left for the service to reject");
    }

    @Test
    void revisionPropertiesReachTheServiceInCamelCase() {
        when(service.createRevision(eq(REGION), eq("app"), any())).thenReturn(revision("rev-aaaaa"));
        StackResource r = resource(LambdaWebCfnProvisioner.REVISION);
        ObjectNode props = revisionProps();

        provisioner.provision(r, props, ctx(null));

        verify(engine).resolveNodeOmittingNoValue(props);
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createRevision(eq(REGION), eq("app"), request.capture());
        JsonNode sent = request.getValue();
        assertEquals("code", sent.path("buildConfig").path("codeConfig").path("s3Object").path("bucket").asText());
        assertEquals("nodejs24.x", sent.path("buildConfig").path("runtimeConfig").path("runtime").asText());
        assertEquals(45, sent.path("serviceConfig").path("timeoutSeconds").asInt());
        assertEquals("2", sent.path("serviceConfig").path("environmentVariables").path("APP_VERSION").asText(),
                "environment variable keys are the user's own and are not lowered");
        assertEquals("first", sent.path("description").asText());
        assertFalse(sent.has("functionName"));
        assertEquals(FN_ARN + "/revision/rev-aaaaa", r.getPhysicalId());
        assertEquals(REVISION_ATTRIBUTES, r.getAttributes().keySet());
        assertEquals("rev-aaaaa", r.getAttributes().get("RevisionId"));
        assertEquals(FN_ARN + "/revision/rev-aaaaa", r.getAttributes().get("RevisionArn"));
        assertEquals(FN_ARN, r.getAttributes().get("FunctionArn"));
        assertEquals("Active", r.getAttributes().get("State"));
        assertEquals("The revision is active.", r.getAttributes().get("StateReason"));
    }

    @Test
    void aFailedRevisionFailsTheResourceAndIsDeleted() {
        WebFunctionRevision failed = revision("rev-aaaaa");
        failed.failureReason = "Failed to read zip file: zip: not a valid zip file";
        when(service.createRevision(eq(REGION), eq("app"), any())).thenReturn(failed);
        StackResource r = resource(LambdaWebCfnProvisioner.REVISION);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, revisionProps(), ctx(null)));

        assertEquals("Failed to read zip file: zip: not a valid zip file", e.getMessage());
        verify(service).deleteRevision(REGION, FN_ARN, failed.revisionArn);
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void unchangedRevisionIsKeptOnUpdate() {
        WebFunctionRevision existing = revision("rev-aaaaa");
        when(service.getFunction(REGION, "app")).thenReturn(function(FN_ARN));
        when(service.getRevision(REGION, FN_ARN, existing.revisionArn)).thenReturn(existing);
        when(service.revisionMatches(eq(existing), any())).thenReturn(true);
        StackResource prior = resource(LambdaWebCfnProvisioner.REVISION);
        prior.setPhysicalId(existing.revisionArn);

        StackResource r = updateOf(prior);
        provisioner.provision(r, revisionProps(), ctx(existing.revisionArn));

        verify(service, never()).createRevision(anyString(), anyString(), any());
        assertEquals(existing.revisionArn, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void changedRevisionIsReplacedAndTheOldOneOwedADelete() {
        WebFunctionRevision existing = revision("rev-aaaaa");
        when(service.getFunction(REGION, "app")).thenReturn(function(FN_ARN));
        when(service.getRevision(REGION, FN_ARN, existing.revisionArn)).thenReturn(existing);
        when(service.revisionMatches(eq(existing), any())).thenReturn(false);
        when(service.createRevision(eq(REGION), eq("app"), any())).thenReturn(revision("rev-bbbbb"));
        StackResource prior = resource(LambdaWebCfnProvisioner.REVISION);
        prior.setPhysicalId(existing.revisionArn);
        prior.getAttributes().put("RevisionId", "rev-aaaaa");

        StackResource r = updateOf(prior);
        provisioner.provision(r, revisionProps(), ctx(existing.revisionArn));

        assertEquals(FN_ARN + "/revision/rev-bbbbb", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(existing.revisionArn, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void revisionOfAnotherFunctionIsNotReused() {
        WebFunctionRevision existing = revision("rev-aaaaa");
        when(service.getFunction(REGION, "other")).thenReturn(function(OTHER_FN_ARN));
        when(service.getRevision(REGION, FN_ARN, existing.revisionArn)).thenReturn(existing);
        when(service.revisionMatches(eq(existing), any())).thenReturn(true);
        when(service.createRevision(eq(REGION), eq("other"), any())).thenReturn(revision("rev-bbbbb"));
        StackResource r = resource(LambdaWebCfnProvisioner.REVISION);
        r.setPhysicalId(existing.revisionArn);

        provisioner.provision(r, revisionProps().put("FunctionName", "other"), ctx(existing.revisionArn));

        verify(service).createRevision(eq(REGION), eq("other"), any());
        assertTrue(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void autoDeploymentModeIsNotACloudFormationProperty() {
        ObjectNode props = endpointProps("HomeRegion").put("AutoDeploymentMode", "LatestRevision");
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provisioner.provision(r, props, ctx(null)));
        assertTrue(e.getMessage().contains("AutoDeploymentMode"), e.getMessage());
        verify(service, never()).createEndpoint(anyString(), anyString(), any());
    }

    @Test
    void endpointPropertiesReachTheServiceInCamelCase() {
        when(service.createEndpoint(eq(REGION), eq("app"), any()))
                .thenReturn(endpoint("prod", "MultiRegion", List.of("us-east-1", "us-west-2")));
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        ObjectNode props = endpointProps("MultiRegion");

        provisioner.provision(r, props, ctx(null));

        verify(engine).resolveNodeOmittingNoValue(props);
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createEndpoint(eq(REGION), eq("app"), request.capture());
        JsonNode sent = request.getValue();
        assertEquals("prod", sent.path("endpointName").asText());
        assertEquals("IamAuth", sent.path("authType").asText());
        assertEquals("rev-aaaaa", sent.path("revisionWeights").get(0).path("revisionId").asText());
        assertEquals(100, sent.path("revisionWeights").get(0).path("weight").asInt());
        assertEquals("us-west-2", sent.path("regions").get(0).asText());
        assertEquals(FN_ARN + "/endpoint/prod", r.getPhysicalId());
        assertEquals(ENDPOINT_ATTRIBUTES, r.getAttributes().keySet());
        assertEquals("abc.001.lambda-web.us-east-1.localhost:4566", r.getAttributes().get("DomainName"));
        assertEquals("The endpoint is active and invokable.", r.getAttributes().get("StateReason"));
    }

    @Test
    void regionalEndpointsIsTheApiBlockInPascalCase() {
        WebFunctionEndpoint created = endpoint("prod", "PerRegion", List.of("us-east-1", "us-west-2"));
        created.authType = "ApplicationManaged";
        WebFunctionEndpoint.RevisionWeight weight = new WebFunctionEndpoint.RevisionWeight();
        weight.revisionId = "rev-aaaaa";
        weight.weight = 100;
        created.revisionWeights.add(weight);
        created.scalingConfig = new WebFunctionEndpoint.ScalingConfig();
        created.scalingConfig.maxEnvironments = 3;
        created.regionalDomainNames.put("us-east-1", "abc.001.lambda-web.us-east-1.localhost:4566");
        created.regionalDomainNames.put("us-west-2", "abc.001.lambda-web.us-west-2.localhost:4566");
        when(service.createEndpoint(eq(REGION), eq("app"), any())).thenReturn(created);
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);

        provisioner.provision(r, endpointProps("PerRegion"), ctx(null));

        String entry = "{\"AuthType\":\"ApplicationManaged\",\"DomainName\":\"abc.001.lambda-web.%s.localhost:4566\","
                + "\"RevisionWeights\":[{\"RevisionId\":\"rev-aaaaa\",\"Weight\":100}],"
                + "\"ScalingConfig\":{\"MaxEnvironments\":3},\"State\":\"Active\","
                + "\"StateReason\":\"The endpoint is active in the region.\"}";
        assertEquals("{\"us-east-1\":" + entry.formatted("us-east-1") + ",\"us-west-2\":" + entry.formatted("us-west-2")
                + "}", provisioner.attributeValue(LambdaWebCfnProvisioner.ENDPOINT, "RegionalEndpoints",
                r.getAttributes().get("RegionalEndpoints")).toString());
        assertEquals("Active", provisioner.attributeValue(LambdaWebCfnProvisioner.ENDPOINT, "State",
                r.getAttributes().get("State")).textValue());
    }

    @Test
    void endpointWithTheSameIdentityIsUpdatedInPlace() {
        WebFunctionEndpoint existing = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        existing.authType = "ApplicationManaged";
        existing.autoDeploymentMode = "LatestRevision";
        when(service.getFunction(REGION, "app")).thenReturn(function(FN_ARN));
        when(service.getEndpoint(REGION, FN_ARN, existing.endpointArn)).thenReturn(existing);
        WebFunctionEndpoint updated = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        updated.updateStatus = "Successful";
        when(service.updateEndpoint(eq(REGION), eq("app"), eq("prod"), any()))
                .thenReturn(new LambdaWebService.EndpointUpdate(existing, updated));
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        r.setPhysicalId(existing.endpointArn);

        provisioner.provision(r, endpointProps("HomeRegion"), ctx(existing.endpointArn));

        ArgumentCaptor<JsonNode> patch = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updateEndpoint(eq(REGION), eq("app"), eq("prod"), patch.capture());
        assertEquals("IamAuth", patch.getValue().path("authType").asText());
        assertFalse(patch.getValue().has("endpointType"));
        assertFalse(patch.getValue().has("scalingConfig"), "a config the stack never declared is left alone");
        assertFalse(patch.getValue().has("throttleConfig"));
        assertFalse(patch.getValue().has("description"));
        verify(service, never()).createEndpoint(anyString(), anyString(), any());
        assertEquals(existing.endpointArn, r.getPhysicalId());
        Set<String> expected = new HashSet<>(ENDPOINT_ATTRIBUTES);
        expected.add("UpdateStatus");
        expected.add("UpdateStatusReason");
        expected.add(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR);
        assertEquals(expected, r.getAttributes().keySet());
        assertEquals("Successful", r.getAttributes().get("UpdateStatus"));
        assertEquals("The endpoint is updated.", r.getAttributes().get("UpdateStatusReason"));
        assertFalse(provisioner.hasReplacementUpdate(r));
        // A failed stack update puts the prior configuration back.
        WebFunctionEndpoint restoredEndpoint = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        restoredEndpoint.authType = "ApplicationManaged";
        when(service.updateEndpoint(eq(REGION), eq(FN_ARN), eq("prod"), any()))
                .thenReturn(new LambdaWebService.EndpointUpdate(updated, restoredEndpoint));
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals("ApplicationManaged", provisioner.attributeValue(LambdaWebCfnProvisioner.ENDPOINT,
                "RegionalEndpoints", r.getAttributes().get("RegionalEndpoints")).path("us-east-1").path("AuthType")
                .textValue(), "the restored endpoint's regional block");
        ArgumentCaptor<JsonNode> restored = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updateEndpoint(eq(REGION), eq(FN_ARN), eq("prod"), restored.capture());
        assertEquals("ApplicationManaged", restored.getValue().path("authType").asText());
        assertEquals("LatestRevision", restored.getValue().path("autoDeploymentMode").asText());
        assertFalse(restored.getValue().has("revisionWeights"));
        assertEquals("", restored.getValue().path("description").asText());
        assertTrue(restored.getValue().path("scalingConfig").isEmpty());
        assertTrue(restored.getValue().path("throttleConfig").isEmpty());
        assertFalse(r.getAttributes().containsKey(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void propertiesThePreviousTemplateDeclaredAreClearedWhenDropped() {
        WebFunctionEndpoint existing = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        when(service.getFunction(REGION, "app")).thenReturn(function(FN_ARN));
        when(service.getEndpoint(REGION, FN_ARN, existing.endpointArn)).thenReturn(existing);
        when(service.updateEndpoint(eq(REGION), eq("app"), eq("prod"), any()))
                .thenReturn(new LambdaWebService.EndpointUpdate(existing, existing));
        ObjectNode priorProps = endpointProps("HomeRegion").put("Description", "blue");
        priorProps.putObject("ScalingConfig").put("MaxEnvironments", 4);
        priorProps.putObject("ThrottleConfig").put("RateLimit", 100);
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        r.setPhysicalId(existing.endpointArn);

        provisioner.provision(r, endpointProps("HomeRegion"), ctx(existing.endpointArn, priorProps));

        ArgumentCaptor<JsonNode> patch = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updateEndpoint(eq(REGION), eq("app"), eq("prod"), patch.capture());
        assertTrue(patch.getValue().path("scalingConfig").isObject() && patch.getValue().path("scalingConfig").isEmpty());
        assertTrue(patch.getValue().path("throttleConfig").isObject() && patch.getValue().path("throttleConfig").isEmpty());
        assertEquals("", patch.getValue().path("description").asText());
    }

    @Test
    void endpointWithAChangedTypeIsRefusedAsACustomNamedReplacement() {
        WebFunctionEndpoint existing = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        when(service.getFunction(REGION, "app")).thenReturn(function(FN_ARN));
        when(service.getEndpoint(REGION, FN_ARN, existing.endpointArn)).thenReturn(existing);
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        r.setPhysicalId(existing.endpointArn);

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, endpointProps("PerRegion"), ctx(existing.endpointArn)));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("CloudFormation cannot update a stack when a custom-named resource requires replacing."
                + " Rename prod and update the stack again.", e.getMessage());
        verify(service, never()).createEndpoint(anyString(), anyString(), any());
        verify(service, never()).updateEndpoint(anyString(), anyString(), anyString(), any());
    }

    @Test
    void renamedEndpointIsReplacedAndTheOldOneOwedADelete() {
        WebFunctionEndpoint existing = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        when(service.getFunction(REGION, "app")).thenReturn(function(FN_ARN));
        when(service.getEndpoint(REGION, FN_ARN, existing.endpointArn)).thenReturn(existing);
        when(service.createEndpoint(eq(REGION), eq("app"), any()))
                .thenReturn(endpoint("prod2", "HomeRegion", List.of("us-east-1")));
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        r.setPhysicalId(existing.endpointArn);
        r.getAttributes().put("UpdateStatus", "Successful");
        r.getAttributes().put("UpdateStatusReason", "The endpoint is updated.");

        provisioner.provision(r, endpointProps("HomeRegion").put("EndpointName", "prod2"), ctx(existing.endpointArn));

        assertEquals(FN_ARN + "/endpoint/prod2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(existing.endpointArn, provisioner.updateCleanupPhysicalId(r));
        Set<String> published = new HashSet<>(r.getAttributes().keySet());
        published.removeIf(key -> key.startsWith("__Floci"));
        assertEquals(ENDPOINT_ATTRIBUTES, published, "a replacement does not inherit UpdateStatus");
    }

    @Test
    void endpointMovedToAnotherFunctionIsCreatedThere() {
        WebFunctionEndpoint existing = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        when(service.getFunction(REGION, "other")).thenReturn(function(OTHER_FN_ARN));
        when(service.getEndpoint(REGION, FN_ARN, existing.endpointArn)).thenReturn(existing);
        WebFunctionEndpoint created = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        created.endpointArn = OTHER_FN_ARN + "/endpoint/prod";
        created.functionArn = OTHER_FN_ARN;
        when(service.createEndpoint(eq(REGION), eq("other"), any())).thenReturn(created);
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        r.setPhysicalId(existing.endpointArn);

        provisioner.provision(r, endpointProps("HomeRegion").put("FunctionName", "other"), ctx(existing.endpointArn));

        verify(service, never()).updateEndpoint(anyString(), anyString(), anyString(), any());
        assertEquals(OTHER_FN_ARN + "/endpoint/prod", r.getPhysicalId());
        assertEquals(existing.endpointArn, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void aPriorLookupFailureOtherThanNotFoundPropagates() {
        when(service.getFunction(REGION, "app")).thenReturn(function(FN_ARN));
        when(service.getEndpoint(REGION, FN_ARN, FN_ARN + "/endpoint/prod"))
                .thenThrow(new AwsException("ValidationException", "Invalid endpoint name or ARN", 400));
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);
        r.setPhysicalId(FN_ARN + "/endpoint/prod");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, endpointProps("HomeRegion"), ctx(FN_ARN + "/endpoint/prod")));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void deletesTolerateAnAbsentResourceButNotARevisionStillInUse() {
        doThrow(new AwsException("ResourceNotFoundException", "The function " + FN_ARN + " does not exist.", 404))
                .when(service).deleteEndpoint(REGION, FN_ARN, FN_ARN + "/endpoint/prod");
        provisioner.delete(LambdaWebCfnProvisioner.ENDPOINT, FN_ARN + "/endpoint/prod", REGION);

        doThrow(new AwsException("ConflictException", "in use", 409))
                .when(service).deleteRevision(REGION, FN_ARN, FN_ARN + "/revision/rev-aaaaa");
        assertThrows(AwsException.class,
                () -> provisioner.delete(LambdaWebCfnProvisioner.REVISION, FN_ARN + "/revision/rev-aaaaa", REGION));

        provisioner.delete(LambdaWebCfnProvisioner.FUNCTION, FN_ARN, REGION);
        verify(service).deleteFunction(REGION, FN_ARN);
    }
}
