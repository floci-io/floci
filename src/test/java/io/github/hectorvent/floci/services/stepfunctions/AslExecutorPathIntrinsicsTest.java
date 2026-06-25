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
    void arrayContainsTrueAndFalse() throws Exception {
        JsonNode root = mapper.readTree("{\"list\":[\"a\",\"b\",\"c\"],\"hit\":\"b\",\"miss\":\"z\"}");
        assertTrue(executor.resolvePath("States.ArrayContains($.list, $.hit)", root).asBoolean());
        assertFalse(executor.resolvePath("States.ArrayContains($.list, $.miss)", root).asBoolean());
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
}
