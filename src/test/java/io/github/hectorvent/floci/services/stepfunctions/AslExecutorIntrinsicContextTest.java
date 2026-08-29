package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for Context Object ($$) resolution inside JSONPath-mode intrinsic functions.
 *
 * <p>A whole-value {@code "x.$": "$$.Map.Item.Value"} Parameters field already resolved against
 * the Context Object, but a {@code $$.} reference nested inside an intrinsic argument — e.g.
 * {@code States.Format('.../{}', $$.Map.Item.Value.solutionId)} — used to fall through to an
 * input-only lookup and resolve to null, because the context was never threaded from
 * {@code resolveParameters} into {@code evaluateIntrinsic}/{@code resolveIntrinsicArg}. These
 * tests pin the fix and guard the no-context (input-only) path against regression.
 */
class AslExecutorIntrinsicContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Only ObjectMapper is exercised by the path/intrinsic resolution methods under test. */
    private AslExecutor newExecutor() {
        return new AslExecutor(null, null, null, null, null, null, null, null,
                null, null, null, null, null, mapper, null, null, null, null);
    }

    @Test
    void statesFormat_resolvesContextArgInsideParameters() throws Exception {
        AslExecutor executor = newExecutor();
        // Shaped like a Map ItemSelector: $. args come from the Map state's effective input,
        // $$.Map.Item.Value.* comes from the per-iteration Context Object.
        JsonNode parameters = mapper.readTree("""
                {
                    "provisionerFolder.$": "States.Format('/mnt/efs/{}/{}/{}/provisioner', $.systemId, $.systemConfigVersion, $$.Map.Item.Value.solutionId)"
                }
                """);
        JsonNode input = mapper.readTree("""
                { "systemId": "SYS1", "systemConfigVersion": "v3" }
                """);
        JsonNode context = mapper.readTree("""
                { "Map": { "Item": { "Index": 0, "Value": { "solutionId": "SOLUTION_A" } } } }
                """);

        JsonNode resolved = executor.resolveParameters(parameters, input, context);

        assertEquals("/mnt/efs/SYS1/v3/SOLUTION_A/provisioner",
                resolved.path("provisionerFolder").asText());
    }

    @Test
    void statesFormat_resolvesContextArgViaResolvePath() {
        AslExecutor executor = newExecutor();
        JsonNode input = mapper.createObjectNode();
        JsonNode context = mapper.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) context).putObject("Map")
                .putObject("Item").putObject("Value").put("solutionId", "SOLUTION_B");

        JsonNode result = executor.resolvePath(
                "States.Format('id={}', $$.Map.Item.Value.solutionId)", input, context);

        assertEquals("id=SOLUTION_B", result.asText());
    }

    @Test
    void wholeContext_resolvesInsideIntrinsic() {
        AslExecutor executor = newExecutor();
        JsonNode input = mapper.createObjectNode();
        JsonNode context = mapper.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) context).put("token", "abc");

        // States.JsonToString($$) serializes the whole Context Object.
        JsonNode result = executor.resolvePath("States.JsonToString($$)", input, context);

        assertEquals("{\"token\":\"abc\"}", result.asText());
    }

    @Test
    void inputArgsStillResolveWithContextPresent() {
        AslExecutor executor = newExecutor();
        JsonNode input = mapper.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).put("a", "X").put("b", "Y");
        JsonNode context = mapper.createObjectNode();

        JsonNode result = executor.resolvePath("States.Format('{}-{}', $.a, $.b)", input, context);

        assertEquals("X-Y", result.asText());
    }

    @Test
    void noContext_intrinsicResolutionUnchanged() {
        AslExecutor executor = newExecutor();
        JsonNode input = mapper.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).put("name", "widget");

        // The 2-arg form (context == null) must behave exactly as before: $. args resolve,
        // and a stray $$. arg — which has no context to resolve against — yields "null".
        // The "null" here pins Floci-internal transitional behavior, NOT AWS semantics (on real
        // AWS the Context Object always exists); see resolveIntrinsicArg. It only holds until
        // context is threaded into the remaining resolvePath callers, at which point this
        // assertion should be updated rather than treated as a contract.
        assertEquals("widget", executor.resolvePath("States.Format('{}', $.name)", input).asText());
        assertEquals("null", executor.resolvePath(
                "States.Format('{}', $$.Map.Item.Value.solutionId)", input).asText());
    }
}
