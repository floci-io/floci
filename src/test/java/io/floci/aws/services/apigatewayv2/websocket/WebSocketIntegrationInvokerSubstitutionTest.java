package io.floci.aws.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.apigateway.AwsServiceRouter;
import io.floci.aws.services.apigateway.VtlTemplateEngine;
import io.floci.aws.services.lambda.LambdaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for stage variable substitution in WebSocketIntegrationInvoker.
 */
class WebSocketIntegrationInvokerSubstitutionTest {

    private WebSocketIntegrationInvoker invoker;

    @BeforeEach
    void setUp() {
        invoker = new WebSocketIntegrationInvoker(
                mock(LambdaService.class),
                mock(AwsServiceRouter.class),
                new ObjectMapper(),
                mock(VtlTemplateEngine.class));
    }

    @Test
    void substituteStageVariables_singleVariable() {
        // substitute stage variable reference with corresponding value
        String uri = "arn:aws:lambda:us-east-1:123456789:function:${stageVariables.functionName}/invocations";
        Map<String, String> vars = Map.of("functionName", "myHandler");

        String result = invoker.substituteStageVariables(uri, vars);

        assertEquals("arn:aws:lambda:us-east-1:123456789:function:myHandler/invocations", result);
    }

    @Test
    void substituteStageVariables_undefinedVariableReplacedWithEmpty() {
        // undefined variable references replaced with empty string
        String uri = "arn:aws:lambda:us-east-1:123456789:function:${stageVariables.missingVar}/invocations";
        Map<String, String> vars = Map.of("otherVar", "value");

        String result = invoker.substituteStageVariables(uri, vars);

        assertEquals("arn:aws:lambda:us-east-1:123456789:function:/invocations", result);
    }

    @Test
    void substituteStageVariables_multipleReferences() {
        // multiple stage variable references in a single URI
        String uri = "arn:aws:lambda:${stageVariables.region}:${stageVariables.account}:function:${stageVariables.functionName}/invocations";
        Map<String, String> vars = Map.of(
                "region", "us-west-2",
                "account", "987654321",
                "functionName", "myFunc");

        String result = invoker.substituteStageVariables(uri, vars);

        assertEquals("arn:aws:lambda:us-west-2:987654321:function:myFunc/invocations", result);
    }

    @Test
    void substituteStageVariables_noReferences() {
        // URI without any stage variable references should be returned unchanged
        String uri = "arn:aws:lambda:us-east-1:123456789:function:myHandler/invocations";
        Map<String, String> vars = Map.of("functionName", "otherHandler");

        String result = invoker.substituteStageVariables(uri, vars);

        assertEquals("arn:aws:lambda:us-east-1:123456789:function:myHandler/invocations", result);
    }

    @Test
    void substituteStageVariables_nullUri() {
        String result = invoker.substituteStageVariables(null, Map.of("key", "value"));
        assertNull(result);
    }

    @Test
    void substituteStageVariables_nullStageVariables() {
        // Null stage variables map should treat all references as undefined (empty string)
        String uri = "arn:aws:lambda:us-east-1:123456789:function:${stageVariables.fn}/invocations";

        String result = invoker.substituteStageVariables(uri, null);

        assertEquals("arn:aws:lambda:us-east-1:123456789:function:/invocations", result);
    }

    @Test
    void substituteStageVariables_emptyStageVariables() {
        // Empty stage variables map should treat all references as undefined (empty string)
        String uri = "arn:aws:lambda:us-east-1:123456789:function:${stageVariables.fn}/invocations";

        String result = invoker.substituteStageVariables(uri, Collections.emptyMap());

        assertEquals("arn:aws:lambda:us-east-1:123456789:function:/invocations", result);
    }

    @Test
    void substituteStageVariables_mixedDefinedAndUndefined() {
        // Mix of defined and undefined variables
        String uri = "${stageVariables.prefix}-${stageVariables.missing}-${stageVariables.suffix}";
        Map<String, String> vars = Map.of("prefix", "hello", "suffix", "world");

        String result = invoker.substituteStageVariables(uri, vars);

        assertEquals("hello--world", result);
    }
}
