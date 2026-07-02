package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit coverage for the JSONPath/intrinsic features DPS's provisioning state machine relies on:
 * the {@code $.X.*.Y} wildcard projection, {@code States.ArrayContains}, and {@code ResultSelector}
 * evaluation (which reuses the Parameters resolver).
 */
class AslExecutorPathIntrinsicsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(io.github.hectorvent.floci.services.s3.S3Service.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                mapper,
                new JsonataEvaluator(mapper),
                mock(Instance.class));
    }

    @Test
    void wildcardProjectionCollectsFieldFromEachArrayElement() throws Exception {
        JsonNode root = mapper.readTree(
                "{\"Regions\":[{\"RegionName\":\"us-east-1\"},{\"RegionName\":\"eu-west-1\"}]}");
        JsonNode result = executor.resolvePath("$.Regions.*.RegionName", root);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("us-east-1", result.get(0).asText());
        assertEquals("eu-west-1", result.get(1).asText());
    }

    @Test
    void wildcardProjectionOnNonArrayIsNull() throws Exception {
        JsonNode root = mapper.readTree("{\"Regions\":\"not-an-array\"}");
        assertTrue(executor.resolvePath("$.Regions.*.RegionName", root).isNull());
    }

    @Test
    void bracketWildcardProjectionMatchesDotForm() throws Exception {
        JsonNode root = mapper.readTree(
                "{\"Regions\":[{\"RegionName\":\"us-east-1\"},{\"RegionName\":\"eu-west-1\"}]}");
        // The AWS bracket form must behave exactly like the dot form.
        JsonNode result = executor.resolvePath("$.Regions[*].RegionName", root);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("us-east-1", result.get(0).asText());
        assertEquals("eu-west-1", result.get(1).asText());
    }

    @Test
    void rootBracketDoubleWildcardFlattensArrayOfArrays() throws Exception {
        JsonNode root = mapper.readTree("[[1,2],[3,4]]");
        JsonNode result = executor.resolvePath("$[*][*]", root);
        assertTrue(result.isArray());
        assertEquals(4, result.size());
        assertEquals(1, result.get(0).asInt());
        assertEquals(4, result.get(3).asInt());
    }

    @Test
    void numericBracketSegmentIndexesIntoArray() throws Exception {
        JsonNode root = mapper.readTree("{\"items\":[\"a\",\"b\",\"c\"]}");
        assertEquals("b", executor.resolvePath("$.items[1]", root).asText());
    }

    @Test
    void resolvePathNodeDistinguishesExplicitNullFromMissing() throws Exception {
        JsonNode root = mapper.readTree("{\"x\":null}");
        // An explicit null exists, so it is present (not a MissingNode)...
        assertFalse(executor.resolvePathNode("$.x", root).isMissingNode());
        assertTrue(executor.resolvePathNode("$.x", root).isNull());
        // ...while an absent field resolves to a MissingNode.
        assertTrue(executor.resolvePathNode("$.y", root).isMissingNode());
    }

    @Test
    void arrayContainsTrueAndFalse() throws Exception {
        JsonNode root = mapper.readTree("{\"list\":[\"a\",\"b\",\"c\"],\"hit\":\"b\",\"miss\":\"z\"}");
        assertTrue(executor.resolvePath("States.ArrayContains($.list, $.hit)", root).asBoolean());
        assertFalse(executor.resolvePath("States.ArrayContains($.list, $.miss)", root).asBoolean());
    }

    @Test
    void arrayContainsThrowsWhenFirstArgIsNotAnArray() throws Exception {
        JsonNode root = mapper.readTree("{\"notList\":\"oops\",\"hit\":\"b\"}");
        // AWS raises States.Runtime instead of silently returning false on a non-array argument.
        assertThrows(AslExecutor.FailStateException.class,
                () -> executor.resolvePath("States.ArrayContains($.notList, $.hit)", root));
    }

    @Test
    void wildcardProjectionKeepsExplicitNullsButOmitsMissing() throws Exception {
        JsonNode root = mapper.readTree("[{\"field\":null},{\"field\":\"x\"},{\"other\":1}]");
        JsonNode result = executor.resolvePath("$[*].field", root);
        assertTrue(result.isArray());
        // The explicit null is kept; the element missing "field" is omitted.
        assertEquals(2, result.size());
        assertTrue(result.get(0).isNull());
        assertEquals("x", result.get(1).asText());
    }

    @Test
    void resultSelectorAppliesProjectionAndIntrinsics() throws Exception {
        JsonNode result = mapper.readTree(
                "{\"Regions\":[{\"RegionName\":\"us-east-1\"},{\"RegionName\":\"us-west-2\"}]}");
        JsonNode selector = mapper.readTree("{\"list.$\":\"$.Regions.*.RegionName\"}");
        JsonNode out = executor.resolveParameters(selector, result, mapper.createObjectNode());
        assertTrue(out.path("list").isArray());
        assertEquals(2, out.path("list").size());
        assertEquals("us-east-1", out.path("list").get(0).asText());
    }

    @Test
    void rootArrayIndexDotBracketIsResolved() throws Exception {
        // EventBridge Pipes delivers a batch as a JSON array; an SFN unwraps it via InputPath "$.[0]".
        JsonNode root = mapper.readTree("[{\"systemId\":\"DFSLOCAL\",\"solutions\":[1,2]}]");
        JsonNode out = executor.resolvePath("$.[0]", root);
        assertTrue(out.isObject());
        assertEquals("DFSLOCAL", out.path("systemId").asText());
        assertEquals(2, out.path("solutions").size());
    }

    @Test
    void rootArrayIndexNoDotIsResolved() throws Exception {
        JsonNode root = mapper.readTree("[{\"k\":\"v0\"},{\"k\":\"v1\"}]");
        assertEquals("v1", executor.resolvePath("$[1]", root).path("k").asText());
    }

    @Test
    void rootArrayIndexThenFieldIsResolved() throws Exception {
        JsonNode root = mapper.readTree("[{\"systemId\":\"S1\"}]");
        assertEquals("S1", executor.resolvePath("$.[0].systemId", root).asText());
    }
}
