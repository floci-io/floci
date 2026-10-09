package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Lambda function CFN provisioner in isolation, for the {@code Tags} property: the create
 * request carries the template's tags, and an in-place update applies the template's set and removes
 * only the keys the previous template had, keeping tags added outside the template.
 */
class LambdaCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:";
    private static final String TEMPLATE_TAG_KEYS_ATTR = "FlociLambdaTemplateTagKeys";

    private final LambdaService lambda = mock(LambdaService.class);
    private final LambdaCfnProvisioner provisioner = new LambdaCfnProvisioner(
            lambda, mock(LambdaLayerService.class), mock(S3Service.class), null);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalar properties and flat tag lists only, so a passthrough engine keeps this isolated.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorPhysicalId);
    }

    private StackResource function(String physicalId, String templateTagKeys) {
        StackResource r = function(physicalId);
        r.getAttributes().put(TEMPLATE_TAG_KEYS_ATTR, templateTagKeys);
        return r;
    }

    private StackResource function(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("MyFunction");
        r.setResourceType("AWS::Lambda::Function");
        r.setAttributes(new HashMap<>());
        if (physicalId != null) {
            r.setPhysicalId(physicalId);
            r.getAttributes().put("FlociLambdaFunctionNameMode", "explicit");
            r.getAttributes().put("FlociLambdaPackageType", "Zip");
        }
        return r;
    }

    private ObjectNode props(String functionName, String... keyValues) {
        ObjectNode props = mapper.createObjectNode().put("FunctionName", functionName);
        if (keyValues.length > 0) {
            ArrayNode tags = props.putArray("Tags");
            for (int i = 0; i < keyValues.length; i += 2) {
                tags.add(mapper.createObjectNode().put("Key", keyValues[i]).put("Value", keyValues[i + 1]));
            }
        }
        return props;
    }

    private static LambdaFunction lambdaFunction(String name) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(name);
        fn.setFunctionArn(FUNCTION_ARN + name);
        fn.setPackageType("Zip");
        return fn;
    }

    private void stubInPlaceUpdate(String name, Map<String, String> currentTags) {
        LambdaFunction existing = lambdaFunction(name);
        when(lambda.getFunction(REGION, name)).thenReturn(existing);
        when(lambda.updateFunctionConfiguration(anyString(), anyString(), anyMap())).thenReturn(existing);
        when(lambda.updateFunctionCode(anyString(), anyString(), anyMap())).thenReturn(existing);
        when(lambda.listTags(FUNCTION_ARN + name)).thenReturn(new HashMap<>(currentTags));
    }

    @Test
    void functionTagsFromTheTemplateReachCreateFunction() {
        when(lambda.createFunction(eq(REGION), anyMap())).thenReturn(lambdaFunction("my-fn"));
        StackResource r = function(null);

        provisioner.provision(r, props("my-fn", "team", "a", "env", "dev"), ctx());

        assertEquals(Map.of("team", "a", "env", "dev"), capturedCreateRequest().get("Tags"));
        assertEquals("env,team", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
        verify(lambda, never()).tagResource(anyString(), anyMap());
        verify(lambda, never()).untagResource(anyString(), any());
    }

    @Test
    void adoptedFunctionKeepsItsOwnTags() {
        when(lambda.createFunction(eq(REGION), anyMap())).thenThrow(
                new AwsException("ResourceConflictException", "Function already exist: my-fn", 409));
        when(lambda.getFunction(REGION, "my-fn")).thenReturn(lambdaFunction("my-fn"));
        StackResource r = function(null);

        provisioner.provision(r, props("my-fn", "team", "a", "env", "dev"), ctx());

        verify(lambda, never()).tagResource(any(), any());
        assertFalse(r.getAttributes().containsKey(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void replacementCarriesTheTemplateTagsOnTheNewFunction() {
        when(lambda.getFunction(REGION, "old-fn")).thenReturn(lambdaFunction("old-fn"));
        when(lambda.createFunction(eq(REGION), anyMap())).thenReturn(lambdaFunction("new-fn"));

        provisioner.provision(function("old-fn"), props("new-fn", "team", "b"), updateCtx("old-fn"));

        assertEquals(Map.of("team", "b"), capturedCreateRequest().get("Tags"));
        verify(lambda).deleteFunction(REGION, "old-fn");
        verify(lambda, never()).tagResource(anyString(), anyMap());
    }

    @Test
    void inPlaceUpdateUntagsOnlyTheKeyTheTemplateDropped() {
        stubInPlaceUpdate("my-fn", Map.of("team", "a", "env", "dev", "oob", "x"));
        StackResource r = function("my-fn", "env,team");

        provisioner.provision(r, props("my-fn", "team", "b"), updateCtx("my-fn"));

        verify(lambda).untagResource(FUNCTION_ARN + "my-fn", List.of("env"));
        verify(lambda).tagResource(FUNCTION_ARN + "my-fn", Map.of("team", "b"));
        verify(lambda, never()).createFunction(anyString(), anyMap());
        assertEquals("team", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void inPlaceUpdateWithTheTagsPropertyGoneUntagsOnlyTheTemplateKeys() {
        stubInPlaceUpdate("my-fn", Map.of("team", "a", "env", "dev", "oob", "x"));
        StackResource r = function("my-fn", "env,team");

        provisioner.provision(r, props("my-fn"), updateCtx("my-fn"));

        verify(lambda).untagResource(eq(FUNCTION_ARN + "my-fn"),
                argThat(keys -> keys.size() == 2 && keys.containsAll(List.of("team", "env"))));
        verify(lambda, never()).tagResource(anyString(), anyMap());
        assertEquals("", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void inPlaceUpdateWithoutRecordedTemplateKeysUntagsNothing() {
        stubInPlaceUpdate("my-fn", Map.of("legacy", "x"));

        provisioner.provision(function("my-fn"), props("my-fn", "team", "b"), updateCtx("my-fn"));

        verify(lambda, never()).untagResource(anyString(), any());
        verify(lambda).tagResource(FUNCTION_ARN + "my-fn", Map.of("team", "b"));
    }

    @Test
    void addingDurableConfigToANamedFunctionCannotReplaceIt() {
        stubInPlaceUpdate("my-fn", Map.of());
        ObjectNode props = props("my-fn");
        props.putObject("DurableConfig").put("ExecutionTimeout", 60);

        AwsException rejected = assertThrows(AwsException.class,
                () -> provisioner.provision(function("my-fn"), props, updateCtx("my-fn")));

        assertEquals("Cannot replace Lambda function my-fn without a new FunctionName", rejected.getMessage());
        verify(lambda, never()).updateFunctionConfiguration(anyString(), anyString(), anyMap());
    }

    @Test
    void aDurableConfigThatIsNotAnObjectLeavesTheFunctionAlone() {
        // A generated name, so treating the value as removed would replace and delete the function.
        String generated = "my-stack-MyFunction-ABCDEFGHIJKL";
        LambdaFunction existing = lambdaFunction(generated);
        existing.setDurableExecutionTimeout(60);
        when(lambda.getFunction(REGION, generated)).thenReturn(existing);
        when(lambda.createFunction(eq(REGION), anyMap())).thenReturn(lambdaFunction("replacement"));
        for (JsonNode malformed : List.of(mapper.getNodeFactory().textNode("abc"), mapper.getNodeFactory().textNode(""),
                mapper.getNodeFactory().numberNode(5), mapper.createArrayNode())) {
            StackResource r = function(generated);
            r.getAttributes().put("FlociLambdaFunctionNameMode", "generated");
            ObjectNode props = mapper.createObjectNode();
            props.set("DurableConfig", malformed);

            AwsException rejected = assertThrows(AwsException.class,
                    () -> provisioner.provision(r, props, updateCtx(generated)));

            assertEquals("ValidationError", rejected.getErrorCode());
        }
        verify(lambda, never()).createFunction(anyString(), anyMap());
        verify(lambda, never()).updateFunctionConfiguration(anyString(), anyString(), anyMap());
        verify(lambda, never()).deleteFunction(anyString(), anyString());
    }

    @Test
    void aDurableConfigWithoutExecutionTimeoutOrWithAnUnknownMemberIsRejectedOnUpdate() {
        LambdaFunction existing = lambdaFunction("my-fn");
        existing.setDurableExecutionTimeout(60);
        when(lambda.getFunction(REGION, "my-fn")).thenReturn(existing);
        ObjectNode withoutTimeout = props("my-fn");
        withoutTimeout.putObject("DurableConfig").put("RetentionPeriodInDays", 5);
        ObjectNode unknownMember = props("my-fn");
        unknownMember.putObject("DurableConfig").put("ExecutionTimeout", 90).put("executionTimeout", 1);

        AwsException missing = assertThrows(AwsException.class,
                () -> provisioner.provision(function("my-fn"), withoutTimeout, updateCtx("my-fn")));
        AwsException unknown = assertThrows(AwsException.class,
                () -> provisioner.provision(function("my-fn"), unknownMember, updateCtx("my-fn")));

        assertEquals("AWS::Lambda::Function DurableConfig requires ExecutionTimeout", missing.getMessage());
        assertEquals("AWS::Lambda::Function DurableConfig does not allow the member executionTimeout",
                unknown.getMessage());
        verify(lambda, never()).updateFunctionConfiguration(anyString(), anyString(), anyMap());
    }

    @Test
    void anExecutionTimeoutBeyondAnIntegerReachesLambdaUnchanged() {
        when(lambda.createFunction(eq(REGION), anyMap())).thenReturn(lambdaFunction("my-fn"));
        ObjectNode props = props("my-fn");
        props.putObject("DurableConfig").put("ExecutionTimeout", 4_294_967_356L);

        provisioner.provision(function(null), props, ctx());

        assertEquals(4_294_967_356L, ((Map<?, ?>) capturedCreateRequest().get("DurableConfig")).get("ExecutionTimeout"));
    }

    @Test
    void aKmsKeyArnTheTemplateDropsIsClearedOnUpdate() {
        LambdaFunction existing = lambdaFunction("my-fn");
        existing.setDurableExecutionTimeout(60);
        existing.setDurableRetentionPeriodInDays(14);
        existing.setDurableKmsKeyArn("arn:aws:kms:us-east-1:000000000000:key/k1");
        when(lambda.getFunction(REGION, "my-fn")).thenReturn(existing);
        when(lambda.updateFunctionConfiguration(anyString(), anyString(), anyMap())).thenReturn(existing);
        when(lambda.updateFunctionCode(anyString(), anyString(), anyMap())).thenReturn(existing);
        when(lambda.listTags(FUNCTION_ARN + "my-fn")).thenReturn(new HashMap<>());
        ObjectNode props = props("my-fn");
        props.putObject("DurableConfig").put("ExecutionTimeout", 60);

        provisioner.provision(function("my-fn"), props, updateCtx("my-fn"));

        verify(lambda).updateFunctionConfiguration(eq(REGION), eq("my-fn"),
                argThat(request -> "".equals(((Map<?, ?>) request.get("DurableConfig")).get("KMSKeyArn"))));
        verify(lambda, times(1)).getFunction(REGION, "my-fn");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedCreateRequest() {
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(lambda).createFunction(eq(REGION), request.capture());
        return request.getValue();
    }
}
