package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Functionality + regression coverage for the last four JSONPath-mode intrinsic functions:
 * {@code States.ArrayPartition}, {@code States.ArrayRange}, {@code States.ArrayUnique} and
 * {@code States.MathRandom}. Before this change each aborted the execution with
 * "Unsupported intrinsic function: States.&lt;Name&gt;" ({@code States.Runtime}).
 *
 * <p>AWS-documented examples are used as fixtures where they exist (the ArrayPartition,
 * ArrayRange and ArrayUnique doc examples). Every failure case asserts the exact
 * {@code States.IntrinsicFailure} error name (catchable), never a bare RuntimeException: a
 * reverted implementation throws the uncatchable {@code States.Runtime} instead.
 */
class AslExecutorArrayAndMathIntrinsicsTest {

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
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper,
                new JsonataEvaluator(mapper),
                mock(Instance.class),
                mock(EmulatorConfig.class),
                null, null);
    }

    private void assertIntrinsicFailure(JsonNode root, String expr) {
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.resolvePath(expr, root));
        assertEquals("States.IntrinsicFailure", ex.error,
                "expected catchable States.IntrinsicFailure for: " + expr);
    }

    private JsonNode obj() {
        return mapper.createObjectNode();
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    private void assertJson(String expected, JsonNode actual) throws Exception {
        assertEquals(mapper.writeValueAsString(json(expected)), mapper.writeValueAsString(actual));
    }

    // ---------- A. States.ArrayPartition ----------

    @Test
    void arrayPartitionMatchesAwsDocExample() throws Exception {
        JsonNode root = json("{\"inputArray\":[1,2,3,4,5,6,7,8,9]}");
        assertJson("[[1,2,3,4],[5,6,7,8],[9]]",
                executor.resolvePath("States.ArrayPartition($.inputArray, 4)", root));
    }

    @Test
    void arrayPartitionChunkSizeFromPath() throws Exception {
        JsonNode root = json("{\"arr\":[\"a\",\"b\",\"c\"],\"n\":2}");
        assertJson("[[\"a\",\"b\"],[\"c\"]]", executor.resolvePath("States.ArrayPartition($.arr, $.n)", root));
    }

    @Test
    void arrayPartitionChunkLargerThanArrayIsSingleChunk() throws Exception {
        JsonNode root = json("{\"arr\":[1,2]}");
        assertJson("[[1,2]]", executor.resolvePath("States.ArrayPartition($.arr, 10)", root));
    }

    @Test
    void arrayPartitionOfEmptyArrayIsEmpty() throws Exception {
        JsonNode root = json("{\"arr\":[]}");
        assertJson("[]", executor.resolvePath("States.ArrayPartition($.arr, 3)", root));
    }

    @Test
    void arrayPartitionRoundsNonIntegerChunkSize() throws Exception {
        JsonNode root = json("{\"arr\":[1,2,3,4,5],\"n\":2.6}");
        assertJson("[[1,2,3],[4,5]]", executor.resolvePath("States.ArrayPartition($.arr, $.n)", root));
    }

    @Test
    void arrayPartitionKeepsNestedElementsIntact() throws Exception {
        JsonNode root = json("{\"arr\":[{\"a\":1},[2],\"s\",null]}");
        assertJson("[[{\"a\":1},[2]],[\"s\",null]]",
                executor.resolvePath("States.ArrayPartition($.arr, 2)", root));
    }

    @Test
    void arrayPartitionNonArrayIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(json("{\"arr\":\"abc\"}"), "States.ArrayPartition($.arr, 2)");
    }

    @Test
    void arrayPartitionNonPositiveChunkSizeIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(json("{\"arr\":[1,2]}"), "States.ArrayPartition($.arr, 0)");
        assertIntrinsicFailure(json("{\"arr\":[1,2]}"), "States.ArrayPartition($.arr, -1)");
        assertIntrinsicFailure(json("{\"arr\":[1,2],\"n\":0.4}"), "States.ArrayPartition($.arr, $.n)");
    }

    @Test
    void arrayPartitionNonNumericChunkSizeIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(json("{\"arr\":[1,2]}"), "States.ArrayPartition($.arr, '2')");
        assertIntrinsicFailure(json("{\"arr\":[1,2]}"), "States.ArrayPartition($.arr, true)");
    }

    @Test
    void arrayPartitionWrongArityIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(json("{\"arr\":[1,2]}"), "States.ArrayPartition($.arr)");
        assertIntrinsicFailure(json("{\"arr\":[1,2]}"), "States.ArrayPartition($.arr, 1, 2)");
        assertIntrinsicFailure(json("{\"arr\":[1,2]}"), "States.ArrayPartition($.arr, 1,)");
    }

    // ---------- B. States.ArrayRange ----------

    @Test
    void arrayRangeMatchesAwsDocExample() throws Exception {
        assertJson("[1,3,5,7,9]", executor.resolvePath("States.ArrayRange(1, 9, 2)", obj()));
    }

    @Test
    void arrayRangeStopsBeforeEndWhenStepDoesNotLand() throws Exception {
        assertJson("[1,3,5,7]", executor.resolvePath("States.ArrayRange(1, 8, 2)", obj()));
    }

    @Test
    void arrayRangeSupportsNegativeStep() throws Exception {
        assertJson("[9,7,5,3,1]", executor.resolvePath("States.ArrayRange(9, 1, -2)", obj()));
    }

    @Test
    void arrayRangeWithEqualBoundsIsSingleElement() throws Exception {
        assertJson("[5]", executor.resolvePath("States.ArrayRange(5, 5, 1)", obj()));
        assertJson("[5]", executor.resolvePath("States.ArrayRange(5, 5, -3)", obj()));
    }

    @Test
    void arrayRangeWithStepPointingAwayFromEndIsEmpty() throws Exception {
        assertJson("[]", executor.resolvePath("States.ArrayRange(9, 1, 2)", obj()));
        assertJson("[]", executor.resolvePath("States.ArrayRange(1, 9, -2)", obj()));
    }

    @Test
    void arrayRangeArgumentsFromPathsAndRounding() throws Exception {
        JsonNode root = json("{\"s\":0.6,\"e\":4.4,\"st\":1.5}");
        // 0.6 -> 1, 4.4 -> 4, 1.5 -> 2
        assertJson("[1,3]", executor.resolvePath("States.ArrayRange($.s, $.e, $.st)", root));
    }

    @Test
    void arrayRangeAtExactly1000ElementsSucceeds() throws Exception {
        JsonNode out = executor.resolvePath("States.ArrayRange(1, 1000, 1)", obj());
        assertEquals(1000, out.size());
        assertEquals(1, out.get(0).asInt());
        assertEquals(1000, out.get(999).asInt());
    }

    @Test
    void arrayRangeOver1000ElementsIsIntrinsicFailure() {
        assertIntrinsicFailure(obj(), "States.ArrayRange(1, 1001, 1)");
        assertIntrinsicFailure(obj(), "States.ArrayRange(0, 9223372036854775807, 1)");
    }

    @Test
    void arrayRangeNearLongBoundsDoesNotOverflow() throws Exception {
        assertJson("[9223372036854775806,9223372036854775807]",
                executor.resolvePath("States.ArrayRange(9223372036854775806, 9223372036854775807, 1)", obj()));
        // (MAX - MIN) / MAX = 2 whole steps, so three elements; a naive `v <= end` walk would
        // wrap past MAX and never terminate.
        assertJson("[-9223372036854775808,-1,9223372036854775806]",
                executor.resolvePath(
                        "States.ArrayRange(-9223372036854775808, 9223372036854775807, 9223372036854775807)",
                        obj()));
    }

    @Test
    void arrayRangeZeroStepIsIntrinsicFailure() {
        assertIntrinsicFailure(obj(), "States.ArrayRange(1, 9, 0)");
    }

    @Test
    void arrayRangeNonNumericArgumentIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(obj(), "States.ArrayRange('1', 9, 2)");
        assertIntrinsicFailure(json("{\"e\":null}"), "States.ArrayRange(1, $.e, 2)");
        assertIntrinsicFailure(json("{\"st\":[2]}"), "States.ArrayRange(1, 9, $.st)");
    }

    @Test
    void arrayRangeWrongArityIsIntrinsicFailure() {
        assertIntrinsicFailure(obj(), "States.ArrayRange(1, 9)");
        assertIntrinsicFailure(obj(), "States.ArrayRange(1, 9, 2, 3)");
        assertIntrinsicFailure(obj(), "States.ArrayRange(1, 9, 2,)");
    }

    // ---------- C. States.ArrayUnique ----------

    @Test
    void arrayUniqueMatchesAwsDocExample() throws Exception {
        JsonNode root = json("{\"inputArray\":[1,2,3,3,3,3,3,3,4]}");
        assertJson("[1,2,3,4]", executor.resolvePath("States.ArrayUnique($.inputArray)", root));
    }

    @Test
    void arrayUniqueKeepsFirstOccurrenceOrder() throws Exception {
        JsonNode root = json("{\"arr\":[\"b\",\"a\",\"b\",\"c\",\"a\"]}");
        assertJson("[\"b\",\"a\",\"c\"]", executor.resolvePath("States.ArrayUnique($.arr)", root));
    }

    @Test
    void arrayUniqueComparesStructurally() throws Exception {
        JsonNode root = json(
                "{\"arr\":[{\"a\":1,\"b\":[1,2]},{\"b\":[1,2],\"a\":1},{\"a\":1,\"b\":[2,1]},[1,2],[1,2],null,null]}");
        assertJson("[{\"a\":1,\"b\":[1,2]},{\"a\":1,\"b\":[2,1]},[1,2],null]",
                executor.resolvePath("States.ArrayUnique($.arr)", root));
    }

    @Test
    void arrayUniqueDistinguishesTypes() throws Exception {
        JsonNode root = json("{\"arr\":[1,\"1\",true,\"true\",null,\"null\"]}");
        assertEquals(6, executor.resolvePath("States.ArrayUnique($.arr)", root).size());
    }

    @Test
    void arrayUniqueTreatsLiteralAndPathNumbersAsEqual() throws Exception {
        // A literal 1 is parsed as a LongNode and a path-resolved 1 as an IntNode; Jackson's
        // equals tells them apart, AWS does not.
        JsonNode root = json("{\"n\":1,\"d\":1.0}");
        assertEquals(1, executor.resolvePath("States.ArrayUnique(States.Array(1, $.n, $.d))", root).size());
    }

    @Test
    void arrayUniqueOfEmptyArrayIsEmpty() throws Exception {
        assertJson("[]", executor.resolvePath("States.ArrayUnique($.arr)", json("{\"arr\":[]}")));
    }

    @Test
    void arrayUniqueNonArrayIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(json("{\"arr\":\"abc\"}"), "States.ArrayUnique($.arr)");
        assertIntrinsicFailure(json("{\"arr\":{\"a\":1}}"), "States.ArrayUnique($.arr)");
    }

    @Test
    void arrayUniqueWrongArityIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(json("{\"arr\":[1]}"), "States.ArrayUnique($.arr, $.arr)");
        assertIntrinsicFailure(json("{\"arr\":[1]}"), "States.ArrayUnique($.arr,)");
    }

    // ---------- D. States.MathRandom ----------

    @Test
    void mathRandomStaysWithinHalfOpenRange() throws Exception {
        // AWS documents the start as inclusive and the end as exclusive.
        JsonNode root = json("{\"start\":1,\"end\":999}");
        for (int i = 0; i < 200; i++) {
            JsonNode out = executor.resolvePath("States.MathRandom($.start, $.end)", root);
            assertTrue(out.isIntegralNumber(), "MathRandom must yield an integer");
            long v = out.asLong();
            assertTrue(v >= 1 && v < 999, "out of range: " + v);
        }
    }

    @Test
    void mathRandomNeverReturnsTheExclusiveEnd() {
        // A range of width one has exactly one admissible value; returning the end would be a
        // closed-range draw, which AWS never makes.
        for (int i = 0; i < 500; i++) {
            assertEquals(3, executor.resolvePath("States.MathRandom(3, 4)", obj()).asLong());
        }
    }

    @Test
    void mathRandomCoversBothAdmissibleValuesOfATinyRange() {
        boolean sawLow = false;
        boolean sawHigh = false;
        for (int i = 0; i < 500 && !(sawLow && sawHigh); i++) {
            long v = executor.resolvePath("States.MathRandom(3, 5)", obj()).asLong();
            assertTrue(v == 3 || v == 4, "out of range: " + v);
            sawLow |= v == 3;
            sawHigh |= v == 4;
        }
        assertTrue(sawLow && sawHigh, "both values of the half-open range [3, 5) must be reachable");
    }

    @Test
    void mathRandomIsReproducibleWithSeed() {
        long first = executor.resolvePath("States.MathRandom(1, 1000000, 42)", obj()).asLong();
        long second = executor.resolvePath("States.MathRandom(1, 1000000, 42)", obj()).asLong();
        assertEquals(first, second);
        // AWS draws from java.util.Random, so the draw must be the one that generator produces.
        assertEquals(new Random(42).nextLong(1, 1_000_000), first);
        long other = executor.resolvePath("States.MathRandom(1, 1000000, 43)", obj()).asLong();
        assertNotEquals(first, other);
    }

    @Test
    void mathRandomRoundsNonIntegerBounds() throws Exception {
        JsonNode root = json("{\"s\":2.4,\"e\":3.6}");
        // 2.4 -> 2, 3.6 -> 4, so the draw is from [2, 4)
        for (int i = 0; i < 50; i++) {
            long v = executor.resolvePath("States.MathRandom($.s, $.e)", root).asLong();
            assertTrue(v == 2 || v == 3, "out of range: " + v);
        }
    }

    @Test
    void mathRandomEmptyRangeIsIntrinsicFailure() {
        // The end is exclusive, so equal bounds define no value at all, and neither does a start
        // above the end.
        assertIntrinsicFailure(obj(), "States.MathRandom(7, 7)");
        assertIntrinsicFailure(obj(), "States.MathRandom(10, 1)");
    }

    @Test
    void mathRandomNonNumericArgumentIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(obj(), "States.MathRandom('1', 9)");
        assertIntrinsicFailure(json("{\"e\":\"9\"}"), "States.MathRandom(1, $.e)");
        assertIntrinsicFailure(obj(), "States.MathRandom(1, 9, 'seed')");
    }

    @Test
    void mathRandomWrongArityIsIntrinsicFailure() {
        assertIntrinsicFailure(obj(), "States.MathRandom(1)");
        assertIntrinsicFailure(obj(), "States.MathRandom(1, 9, 2, 3)");
        assertIntrinsicFailure(obj(), "States.MathRandom(1, 9,)");
    }

    // ---------- E. Missing-path arguments still fail with States.Runtime ----------

    @Test
    void missingPathArgumentIsStatesRuntime() throws Exception {
        JsonNode root = json("{\"arr\":[1]}");
        for (String expr : List.of("States.ArrayPartition($.nope, 2)", "States.ArrayRange($.nope, 9, 1)",
                "States.ArrayUnique($.nope)", "States.MathRandom(1, $.nope)")) {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> executor.resolvePath(expr, root), expr);
            assertEquals("States.Runtime", ex.error, expr);
        }
    }

    // ---------- F. Parameters-path wiring and nesting ----------

    @Test
    void intrinsicsResolveInsidePassStateParameters() throws Exception {
        JsonNode params = json("{\"chunks.$\":\"States.ArrayPartition($.arr, 2)\","
                + "\"range.$\":\"States.ArrayRange(1, 3, 1)\","
                + "\"unique.$\":\"States.ArrayUnique($.arr)\","
                + "\"rand.$\":\"States.MathRandom(5, 6)\"}");
        JsonNode input = json("{\"arr\":[1,1,2]}");
        JsonNode out = executor.resolveParameters(params, input, null);
        assertJson("[[1,1],[2]]", out.path("chunks"));
        assertJson("[1,2,3]", out.path("range"));
        assertJson("[1,2]", out.path("unique"));
        assertEquals(5, out.path("rand").asLong());
    }

    @Test
    void intrinsicsComposeWithEachOther() throws Exception {
        assertJson("[[1,2],[3,4],[5]]",
                executor.resolvePath("States.ArrayPartition(States.ArrayRange(1, 5, 1), 2)", obj()));
        assertEquals(3, executor.resolvePath("States.ArrayLength(States.ArrayUnique(States.Array(1, 1, 2, 3)))",
                obj()).asInt());
        assertEquals(1, executor.resolvePath("States.ArrayGetItem(States.ArrayRange(0, 4, 1), 1)", obj()).asInt());
    }

    // ---------- G. Execution-level behaviour (full doExecute loop via executeSync) ----------

    @Test
    void issueReproductionSucceedsEndToEnd() throws Exception {
        Execution exec = run("""
                {
                  "StartAt": "Calc",
                  "States": {
                    "Calc": {
                      "Type": "Pass",
                      "Parameters": {
                        "first.$": "States.ArrayGetItem($.items, 0)",
                        "chunks.$": "States.ArrayPartition($.items, 2)",
                        "range.$": "States.ArrayRange(1, 3, 1)",
                        "unique.$": "States.ArrayUnique($.dupes)",
                        "rand.$": "States.MathRandom(1, 4, 7)"
                      },
                      "End": true
                    }
                  }
                }
                """, "{\"items\": [1, 2, 3], \"dupes\": [1, 1, 2]}");
        assertEquals("SUCCEEDED", exec.getStatus());
        JsonNode out = json(exec.getOutput());
        assertEquals(1, out.path("first").asInt());
        assertJson("[[1,2],[3]]", out.path("chunks"));
        assertJson("[1,2,3]", out.path("range"));
        assertJson("[1,2]", out.path("unique"));
        long rand = out.path("rand").asLong();
        assertTrue(rand >= 1 && rand < 4, "out of range: " + rand);
    }

    @Test
    void intrinsicFailureIsCatchableEndToEnd() {
        Execution exec = run("""
                {
                  "StartAt": "Try",
                  "States": {
                    "Try": {
                      "Type": "Pass",
                      "Parameters": {"range.$": "States.ArrayRange(1, 9, 0)"},
                      "Next": "Unexpected",
                      "Catch": [{"ErrorEquals": ["States.IntrinsicFailure"], "Next": "Recover"}]
                    },
                    "Unexpected": {"Type": "Fail", "Cause": "intrinsic unexpectedly succeeded"},
                    "Recover": {"Type": "Pass", "End": true}
                  }
                }
                """, "{}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void intrinsicFailureWithoutCatchFailsExecutionWithIntrinsicFailure() {
        Execution exec = run("""
                {
                  "StartAt": "Try",
                  "States": {
                    "Try": {
                      "Type": "Pass",
                      "Parameters": {"u.$": "States.ArrayUnique($.notAnArray)"},
                      "End": true
                    }
                  }
                }
                """, "{\"notAnArray\":\"x\"}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.IntrinsicFailure", exec.getError());
    }

    private Execution run(String definition, String input) {
        StateMachine sm = new StateMachine();
        sm.setName("intrinsic-test");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:intrinsic-test");
        sm.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        sm.setDefinition(definition);

        Execution exec = new Execution();
        exec.setName("intrinsic-test-execution");
        exec.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:intrinsic-test:intrinsic-test-execution");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(sm, exec, history, (updated, events) -> {
        });
        return exec;
    }
}
