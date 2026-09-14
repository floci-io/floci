package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The permission-replacement path: a stack update removes the previous statement before adding
 * its replacement, so a rejected replacement must not leave the function with neither. Plus
 * {@code AWS::Lambda::Url}, whose {@code FunctionUrl} attribute is the one a template exports.
 */
class LambdaAddressingCfnProvisionerTest {

    private static final String REGION = "us-east-1";

    private final LambdaService lambda = mock(LambdaService.class);
    private final LambdaAddressingCfnProvisioner provisioner = new LambdaAddressingCfnProvisioner(lambda);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aRejectedPermissionReplacementRestoresThePreviousStatement() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("Sid", "InvokePermission");
        existing.put("Effect", "Allow");
        existing.put("Principal", Map.of("Service", "s3.amazonaws.com"));
        existing.put("Action", "lambda:InvokeFunction");
        when(lambda.getPolicy(REGION, "app-fn", null))
                .thenReturn(Map.of("policy", Map.of("Statement", List.of(existing))));
        doThrow(new AwsException("InvalidParameterValueException", "bad principal", 400))
                .when(lambda).addPermission(eq(REGION), eq("app-fn"), isNull(), anyMap());

        StackResource r = resource();
        r.setPhysicalId("app-fn|InvokePermission");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("app-fn"), ctx()));

        ArgumentCaptor<Map<String, Object>> restored = ArgumentCaptor.captor();
        verify(lambda).removePermission(REGION, "app-fn", null, "InvokePermission");
        verify(lambda).restorePermissionStatement(eq(REGION), eq("app-fn"), restored.capture());
        assertEquals("InvokePermission", restored.getValue().get("Sid"));
        assertEquals("lambda:InvokeFunction", restored.getValue().get("Action"));
        // The physical id still points at the statement that is actually there.
        assertEquals("app-fn|InvokePermission", r.getPhysicalId());
    }

    @Test
    void aSuccessfulReplacementDoesNotRestoreAnything() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("Sid", "InvokePermission");
        when(lambda.getPolicy(REGION, "app-fn", null))
                .thenReturn(Map.of("policy", Map.of("Statement", List.of(existing))));

        StackResource r = resource();
        r.setPhysicalId("app-fn|InvokePermission");

        provisioner.provision(r, props("app-fn"), ctx());

        verify(lambda).removePermission(REGION, "app-fn", null, "InvokePermission");
        verify(lambda, never()).restorePermissionStatement(anyString(), anyString(), anyMap());
        assertEquals("app-fn|InvokePermission", r.getPhysicalId());
    }

    @Test
    void aFirstCreateHasNothingToRemoveOrRestore() {
        StackResource r = resource();

        provisioner.provision(r, props("app-fn"), ctx());

        verify(lambda, never()).removePermission(anyString(), anyString(), any(), anyString());
        verify(lambda, never()).restorePermissionStatement(anyString(), anyString(), anyMap());
        assertEquals("app-fn|InvokePermission", r.getPhysicalId());
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, REGION, "000000000000", "test-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("InvokePermission");
        r.setResourceType("AWS::Lambda::Permission");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode props(String functionName) {
        return mapper.createObjectNode()
                .put("FunctionName", functionName)
                .put("Action", "lambda:InvokeFunction")
                .put("Principal", "s3.amazonaws.com");
    }

    // ── AWS::Lambda::Url ─────────────────────────────────────────────────────

    private static final String FN_ARN = "arn:aws:lambda:us-east-1:000000000000:function:app-fn";

    private StackResource urlResource() {
        StackResource r = new StackResource();
        r.setLogicalId("AppFnLambdaFunctionUrl");
        r.setResourceType("AWS::Lambda::Url");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private LambdaUrlConfig urlConfig(String functionArn) {
        LambdaUrlConfig config = new LambdaUrlConfig();
        config.setFunctionArn(functionArn);
        config.setFunctionUrl("http://abc123.lambda-url.us-east-1.localhost:4566/");
        return config;
    }

    private void noExistingUrlConfig() {
        doThrow(new AwsException("ResourceNotFoundException", "Function URL config not found", 404))
                .when(lambda).getFunctionUrlConfig(anyString(), anyString(), any());
    }

    @Test
    void urlPublishesTheFunctionUrlAttribute() {
        noExistingUrlConfig();
        when(lambda.createFunctionUrlConfig(eq(REGION), eq(FN_ARN), isNull(), anyMap()))
                .thenReturn(urlConfig(FN_ARN));
        StackResource r = urlResource();

        provisioner.provision(r, mapper.createObjectNode()
                .put("TargetFunctionArn", FN_ARN)
                .put("AuthType", "NONE"), ctx());

        // Without the attribute, Fn::GetAtt resolves to the literal "LogicalId.FunctionUrl" and
        // that string is what a stack export hands out in place of a URL.
        assertEquals("http://abc123.lambda-url.us-east-1.localhost:4566/",
                r.getAttributes().get("FunctionUrl"));
        assertEquals(FN_ARN, r.getAttributes().get("FunctionArn"));
        assertEquals(FN_ARN, r.getPhysicalId());
    }

    @Test
    void urlUpdatesTheConfigTheTargetAlreadyHas() {
        when(lambda.getFunctionUrlConfig(REGION, FN_ARN, null)).thenReturn(urlConfig(FN_ARN));
        when(lambda.updateFunctionUrlConfig(eq(REGION), eq(FN_ARN), isNull(), anyMap()))
                .thenReturn(urlConfig(FN_ARN));
        StackResource r = urlResource();
        r.setPhysicalId(FN_ARN);

        provisioner.provision(r, mapper.createObjectNode()
                .put("TargetFunctionArn", FN_ARN)
                .put("AuthType", "AWS_IAM"), ctx());

        // CreateFunctionUrlConfig answers 409 for a function that already has one, so a re-deploy
        // has to update rather than create.
        verify(lambda).updateFunctionUrlConfig(eq(REGION), eq(FN_ARN), isNull(), anyMap());
        verify(lambda, never()).createFunctionUrlConfig(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void urlMovedToAnotherFunctionDeletesTheDisplacedOne() {
        String newArn = "arn:aws:lambda:us-east-1:000000000000:function:other-fn";
        noExistingUrlConfig();
        when(lambda.createFunctionUrlConfig(eq(REGION), eq(newArn), isNull(), anyMap()))
                .thenReturn(urlConfig(newArn));
        StackResource r = urlResource();
        r.setPhysicalId(FN_ARN);
        ProvisionContext update = new ProvisionContext(ctx().engine(), REGION, "000000000000",
                "test-stack", FN_ARN);

        provisioner.provision(r, mapper.createObjectNode()
                .put("TargetFunctionArn", newArn)
                .put("AuthType", "NONE"), update);

        // TargetFunctionArn is create-only. The URL on the previous function is not addressable
        // from any resource any more, so leaving it would keep it invokable forever.
        verify(lambda).deleteFunctionUrlConfig(REGION, FN_ARN, null);
        assertEquals(newArn, r.getPhysicalId());
    }

    @Test
    void urlCorsArrivesTypedNotStringified() {
        noExistingUrlConfig();
        when(lambda.createFunctionUrlConfig(eq(REGION), eq(FN_ARN), isNull(), anyMap()))
                .thenReturn(urlConfig(FN_ARN));
        CloudFormationTemplateEngine engine = ctx().engine();
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> values = new java.util.ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(element -> values.add(element.asText()));
            }
            return values;
        });
        ProvisionContext ctx = new ProvisionContext(engine, REGION, "000000000000", "test-stack");
        ObjectNode props = mapper.createObjectNode()
                .put("TargetFunctionArn", FN_ARN)
                .put("AuthType", "NONE");
        ObjectNode cors = props.putObject("Cors");
        cors.put("AllowCredentials", true).put("MaxAge", 600);
        cors.putArray("AllowOrigins").add("https://app.example.com");
        cors.putArray("AllowMethods").add("GET").add("POST");

        provisioner.provision(urlResource(), props, ctx);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
        verify(lambda).createFunctionUrlConfig(eq(REGION), eq(FN_ARN), isNull(), captor.capture());
        Map<String, Object> cfg = (Map<String, Object>) captor.getValue().get("Cors");
        // The service reads these with Boolean.TRUE.equals and an int coercion: the resolved
        // strings a scalar resolve hands back would read as false and 0.
        assertEquals(Boolean.TRUE, cfg.get("AllowCredentials"));
        assertEquals(600, cfg.get("MaxAge"));
        assertEquals(List.of("https://app.example.com"), cfg.get("AllowOrigins"));
        assertEquals(List.of("GET", "POST"), cfg.get("AllowMethods"));
    }

    @Test
    void anUpdateThatDroppedCorsClearsIt() {
        when(lambda.getFunctionUrlConfig(REGION, FN_ARN, null)).thenReturn(urlConfig(FN_ARN));
        when(lambda.updateFunctionUrlConfig(eq(REGION), eq(FN_ARN), isNull(), anyMap()))
                .thenReturn(urlConfig(FN_ARN));
        StackResource r = urlResource();
        r.setPhysicalId(FN_ARN);

        provisioner.provision(r, mapper.createObjectNode()
                .put("TargetFunctionArn", FN_ARN)
                .put("AuthType", "NONE"), ctx());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
        verify(lambda).updateFunctionUrlConfig(eq(REGION), eq(FN_ARN), isNull(), captor.capture());
        // UpdateFunctionUrlConfig clears the policy only for a Cors member that is present and
        // null; omitting it left the rules the template no longer declares in force.
        assertTrue(captor.getValue().containsKey("Cors"));
        assertNull(captor.getValue().get("Cors"));
    }

    @Test
    void urlWithoutATargetIsRefused() {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(urlResource(),
                mapper.createObjectNode().put("AuthType", "NONE"), ctx()));

        assertTrue(e.getMessage().contains("TargetFunctionArn"), e.getMessage());
        verify(lambda, never()).createFunctionUrlConfig(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void urlDeleteRemovesTheConfigTheArnPointsAt() {
        provisioner.delete("AWS::Lambda::Url", FN_ARN, REGION);

        // A qualified ARN parses back into function and qualifier, so the physical id is enough.
        verify(lambda).deleteFunctionUrlConfig(REGION, FN_ARN, null);
    }

    @Test
    void urlDeleteToleratesAnAlreadyGoneConfig() {
        doThrow(new AwsException("ResourceNotFoundException", "not found", 404))
                .when(lambda).deleteFunctionUrlConfig(REGION, FN_ARN, null);

        provisioner.delete("AWS::Lambda::Url", FN_ARN, REGION);
    }

    @Test
    void urlDeletePropagatesARealFailure() {
        doThrow(new AwsException("ResourceConflictException", "update in progress", 409))
                .when(lambda).deleteFunctionUrlConfig(REGION, FN_ARN, null);

        assertThrows(AwsException.class, () -> provisioner.delete("AWS::Lambda::Url", FN_ARN, REGION));
    }
}
