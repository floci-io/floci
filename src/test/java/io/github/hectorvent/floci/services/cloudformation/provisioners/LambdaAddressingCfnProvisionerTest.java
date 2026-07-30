package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * The permission-replacement path: a stack update removes the previous statement before adding
 * its replacement, so a rejected replacement must not leave the function with neither.
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
        when(lambda.getPolicy(REGION, "app-fn"))
                .thenReturn(Map.of("policy", Map.of("Statement", List.of(existing))));
        doThrow(new AwsException("InvalidParameterValueException", "bad principal", 400))
                .when(lambda).addPermission(eq(REGION), eq("app-fn"), anyMap());

        StackResource r = resource();
        r.setPhysicalId("app-fn|InvokePermission");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("app-fn"), ctx()));

        ArgumentCaptor<Map<String, Object>> restored = ArgumentCaptor.captor();
        verify(lambda).removePermission(REGION, "app-fn", "InvokePermission");
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
        when(lambda.getPolicy(REGION, "app-fn"))
                .thenReturn(Map.of("policy", Map.of("Statement", List.of(existing))));

        StackResource r = resource();
        r.setPhysicalId("app-fn|InvokePermission");

        provisioner.provision(r, props("app-fn"), ctx());

        verify(lambda).removePermission(REGION, "app-fn", "InvokePermission");
        verify(lambda, never()).restorePermissionStatement(anyString(), anyString(), anyMap());
        assertEquals("app-fn|InvokePermission", r.getPhysicalId());
    }

    @Test
    void aFirstCreateHasNothingToRemoveOrRestore() {
        StackResource r = resource();

        provisioner.provision(r, props("app-fn"), ctx());

        verify(lambda, never()).removePermission(anyString(), anyString(), anyString());
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
}
