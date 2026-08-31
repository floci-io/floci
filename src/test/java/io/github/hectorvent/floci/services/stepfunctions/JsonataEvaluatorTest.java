package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class JsonataEvaluatorTest {

    private JsonataEvaluator evaluator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evaluator = new JsonataEvaluator(objectMapper);
    }

    @Test
    void isExpression_valid() {
        assertTrue(JsonataEvaluator.isExpression("{% $states.input.x %}"));
        assertTrue(JsonataEvaluator.isExpression("{%$states.input%}"));
        assertTrue(JsonataEvaluator.isExpression("{% $states.input %}"));
    }

    @Test
    void isExpression_invalid() {
        assertFalse(JsonataEvaluator.isExpression(null));
        assertFalse(JsonataEvaluator.isExpression("hello"));
        assertFalse(JsonataEvaluator.isExpression("{% incomplete"));
        assertFalse(JsonataEvaluator.isExpression("incomplete %}"));
        // Not a pure expression — AWS does not support string interpolation
        assertFalse(JsonataEvaluator.isExpression("Hello {% name %} welcome"));
    }

    @Test
    void unwrap_stripsDelimitersAndTrims() {
        assertEquals("$states.input.x", JsonataEvaluator.unwrap("{% $states.input.x %}"));
        assertEquals("1 + 2", JsonataEvaluator.unwrap("{%1 + 2%}"));
    }

    @Test
    void evaluate_simpleArithmetic() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% 1 + 2 %}", statesVar);
        assertEquals(3, result.asInt());
    }

    @Test
    void evaluate_stringConcatenation() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% 'hello' & ' ' & 'world' %}", statesVar);
        assertEquals("hello world", result.asText());
    }

    @Test
    void evaluate_statesInputAccess() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice", "age": 30}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.input.name %}", statesVar);
        assertEquals("Alice", result.asText());
    }

    @Test
    void evaluate_statesResultAccess() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"x": 1}, "result": {"value": 42}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.result.value %}", statesVar);
        assertEquals(42, result.asInt());
    }

    @Test
    void evaluate_booleanExpression() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"score": 85}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.input.score > 50 %}", statesVar);
        assertTrue(result.asBoolean());
    }

    @Test
    void evaluate_returnsNothingForMissingField() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice"}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.input.missing %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void resolveTemplate_nonExpressionStringPassesThrough() throws Exception {
        JsonNode template = objectMapper.readTree("\"plain text\"");
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.resolveTemplate(template, "Output", statesVar);
        assertEquals("plain text", result.asText());
    }

    @Test
    void resolveTemplate_evaluatesExpressionInString() throws Exception {
        JsonNode template = objectMapper.readTree("\"{% 1 + 1 %}\"");
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.resolveTemplate(template, "Output", statesVar);
        assertEquals(2, result.asInt());
    }

    @Test
    void resolveTemplate_walksObjectAndEvaluatesExpressions() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {
                    "greeting": "{% 'Hello ' & $states.input.name %}",
                    "static": "unchanged",
                    "count": 42
                }
                """);
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Bob"}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, "Output", statesVar);
        assertTrue(result.isObject());
        assertEquals("Hello Bob", result.get("greeting").asText());
        assertEquals("unchanged", result.get("static").asText());
        assertEquals(42, result.get("count").asInt());
    }

    @Test
    void resolveTemplate_walksArrayAndEvaluatesExpressions() throws Exception {
        JsonNode template = objectMapper.readTree("""
                ["{% $states.input.a %}", "static", "{% $states.input.b %}"]
                """);
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"a": 1, "b": 2}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, "Output", statesVar);
        assertTrue(result.isArray());
        assertEquals(1, result.get(0).asInt());
        assertEquals("static", result.get(1).asText());
        assertEquals(2, result.get(2).asInt());
    }

    @Test
    void resolveTemplate_nonPureExpressionPassesThrough() throws Exception {
        // AWS does not support string interpolation — non-pure expressions pass through as-is
        JsonNode template = objectMapper.readTree("\"Hello {% $states.input.name %}, you are {% $states.input.age %} years old\"");
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice", "age": 30}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, "Output", statesVar);
        assertTrue(result.isTextual());
        assertEquals("Hello {% $states.input.name %}, you are {% $states.input.age %} years old", result.asText());
    }

    @Test
    void resolveTemplate_pureExpressionReturnsObject() throws Exception {
        JsonNode template = objectMapper.readTree("\"{% $states.input %}\"");
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice", "age": 30}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, "Output", statesVar);
        assertTrue(result.isObject());
        assertEquals("Alice", result.get("name").asText());
    }

    @Test
    void resolveTemplate_primitivesPassThrough() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals(42, evaluator.resolveTemplate(objectMapper.readTree("42"), "Output", statesVar).asInt());
        assertTrue(evaluator.resolveTemplate(objectMapper.readTree("true"), "Output", statesVar).asBoolean());
        assertTrue(evaluator.resolveTemplate(NullNode.getInstance(), "Output", statesVar).isNull());
    }

    @Test
    void parse_deserializesJsonObject() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $parse('{\"a\":1,\"b\":[1,2,3]}') %}", statesVar);
        assertTrue(result.isObject());
        assertEquals(1, result.get("a").asInt());
        assertEquals(3, result.get("b").size());
    }

    @Test
    void parse_navigatesFieldOffResult() throws Exception {
        // The dominant real-world shape: $parse(x.Output).someField.
        JsonNode statesVar = objectMapper.readTree("""
                {"eligibilityRaw": {"Output": "{\\"joinable\\": true}"}}
                """);
        JsonNode result = evaluator.evaluate("{% $parse($states.eligibilityRaw.Output).joinable %}", statesVar);
        assertTrue(result.asBoolean());
    }

    @Test
    void parse_chainedWithArithmeticOnNestedField() throws Exception {
        // Real acceptance shape: $round($parse($states.input[0].body).detail.amount.value * 100)
        JsonNode statesVar = objectMapper.readTree("""
                {"input": [{"body": "{\\"detail\\": {\\"amount\\": {\\"value\\": 12.345}}}"}]}
                """);
        JsonNode result = evaluator.evaluate(
                "{% $round($parse($states.input[0].body).detail.amount.value * 100) %}", statesVar);
        assertEquals(1234, result.asInt());
    }

    @Test
    void parse_nullLiteralIsRealNullNotUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $exists($parse('null')) %}", statesVar);
        assertTrue(result.asBoolean());
    }

    @Test
    void parse_noArgumentReturnsUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $parse() %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void parse_nullArgumentRaisesSignatureError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $parse(null) %}", statesVar));
        assertTrue(ex.cause.contains("T0410"));
    }

    @Test
    void parse_invalidJsonRaisesError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $parse('not valid json') %}", statesVar));
        assertTrue(ex.cause.contains("D3137"));
    }

    @Test
    void partition_splitsIntoChunksWithShorterLastChunk() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $partition([1,2,3,4,5], 2) %}", statesVar);
        assertEquals("[[1,2],[3,4],[5]]", result.toString());
    }

    @Test
    void partition_missingChunkSizeReturnsWholeArrayAsOneChunk() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $partition([1,2,3]) %}", statesVar);
        assertEquals("[[1,2,3]]", result.toString());
    }

    @Test
    void partition_nonIntegerChunkSizeIsRoundedTowardsZero() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $partition([1,2,3,4,5], 2.9) %}", statesVar);
        assertEquals("[[1,2],[3,4],[5]]", result.toString());
    }

    @Test
    void partition_emptyArrayReturnsUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $partition([], 2) %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void partition_chunkSizeZeroReturnsUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $partition([1,2,3], 0) %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void partition_negativeChunkSizeRaisesError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $partition([1,2,3], -1) %}", statesVar));
        assertTrue(ex.cause.contains("D3137: Second argument must be zero or greater"), ex.cause);
    }

    @Test
    void partition_nullArrayRaisesError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $partition(null, 2) %}", statesVar));
        assertTrue(ex.cause.contains("T0412"));
    }

    @Test
    void range_generatesInclusiveAscendingArray() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $range(0, 10, 2) %}", statesVar);
        assertEquals("[0,2,4,6,8,10]", result.toString());
    }

    @Test
    void range_negativeStepGeneratesDescendingArray() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $range(10, 0, -2) %}", statesVar);
        assertEquals("[10,8,6,4,2,0]", result.toString());
    }

    @Test
    void range_nonIntegerStartIsRoundedTowardsZero() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $range(1.7, 5, 1) %}", statesVar);
        assertEquals("[1,2,3,4,5]", result.toString());
    }

    @Test
    void range_singleElementRangeCollapsesToScalar() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $range(3, 3, 1) %}", statesVar);
        assertTrue(result.isNumber());
        assertEquals(3, result.asInt());
    }

    @Test
    void range_wrongSignStepReturnsUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $range(5, 1, 1) %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void range_stepZeroReturnsUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $range(1, 5, 0) %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void range_missingStepReturnsUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $range(1, 5) %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void range_missingEndRaisesSignatureError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $range(1) %}", statesVar));
        assertTrue(ex.cause.contains("T0410"));
    }

    /**
     * The AWS bound on how many elements a single {@code $range} call may produce, measured
     * directly (us-east-1, test-state, 2026-08-30): AWS accepts {@code $range(1, 360145, 1)},
     * returning all 360,145 elements.
     */
    @Test
    void rangeAtTheAwsElementBoundBuildsTheFullArray() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $count($range(1, 360145, 1)) %}", statesVar);
        assertEquals(360145, result.asInt());
    }

    /**
     * One element past the bound, AWS fails the state with {@code States.QueryEvaluationError}
     * and this cause byte for byte (us-east-1, test-state, 2026-08-30).
     */
    @Test
    void rangeOneElementPastTheAwsElementBoundFailsWithTheMemoryRefusal() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% $range(1, 360146, 1) %}", "Output/v", statesVar, null));
        assertEquals("States.QueryEvaluationError", failure.error);
        assertEquals("The JSONata expression '$range(1, 360146, 1)' specified for the field 'Output/v' threw "
                + "an error during evaluation: Expression evaluation memory limit exceeded: Check for excessive "
                + "memory usage", failure.cause);
    }

    /**
     * Far past the bound, the refusal has to come from the pre-allocation cap in {@code range()}
     * rather than from the memory bound on the built value: an unbounded call would spend
     * gigabytes building the array before that check ever runs.
     */
    @Test
    @Timeout(60)
    void rangeFarPastTheBoundFailsWithoutAllocating() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% $range(1, 1000000000, 1) %}", "Output/v", statesVar, null));
        assertEquals("States.QueryEvaluationError", failure.error);
        assertEquals("The JSONata expression '$range(1, 1000000000, 1)' specified for the field 'Output/v' threw "
                + "an error during evaluation: Expression evaluation memory limit exceeded: Check for excessive "
                + "memory usage", failure.cause);
    }

    @Test
    void hash_computesKnownDigestsForEachAlgorithm() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("8b1a9953c4611296a827abf8c47804d7",
                evaluator.evaluate("{% $hash('Hello', 'MD5') %}", statesVar).asText());
        assertEquals("f7ff9e8b7bb2e09b70935a5d785e0cc5d9d0abf0",
                evaluator.evaluate("{% $hash('Hello', 'SHA-1') %}", statesVar).asText());
        assertEquals("185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969",
                evaluator.evaluate("{% $hash('Hello', 'SHA-256') %}", statesVar).asText());
    }

    @Test
    void hash_algorithmNameIsCaseSensitive() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $hash('Hello', 'md5') %}", statesVar));
        assertTrue(ex.cause.contains("D3137"));
    }

    @Test
    void hash_unsupportedAlgorithmRaisesError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $hash('Hello', 'SHA3-256') %}", statesVar));
        assertTrue(ex.cause.contains("D3137"));
    }

    @Test
    void hash_missingAlgorithmReturnsUndefined() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $hash('Hello') %}", statesVar);
        assertTrue(result.isMissingNode());
    }

    @Test
    void hash_nonStringValueRaisesSignatureError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $hash(42, 'MD5') %}", statesVar));
        assertTrue(ex.cause.contains("T0410"));
    }

    @Test
    void uuid_generatesCanonicalV4Uuid() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $uuid() %}", statesVar);
        assertTrue(result.asText()
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"));
    }

    @Test
    void uuid_successiveCallsAreDistinct() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $uuid() = $uuid() %}", statesVar);
        assertFalse(result.asBoolean());
    }

    @Test
    void uuid_anyArgumentRaisesSignatureError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $uuid('seed') %}", statesVar));
        assertTrue(ex.cause.contains("T0410"));
    }

    @Test
    void random_unseededCallsDiffer() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $random() = $random() %}", statesVar);
        assertFalse(result.asBoolean());
    }

    @Test
    void random_seedMakesTheDrawReproducible() {
        // JSONata's own $random takes no arguments; the Step Functions one takes a seed and draws
        // from java.util.Random, so these are the values AWS returns for the same seeds.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals(0.7275636800328681, evaluator.evaluate("{% $random(42) %}", statesVar).asDouble());
        assertEquals(0.730967787376657, evaluator.evaluate("{% $random(0) %}", statesVar).asDouble());
        assertEquals(0.7306990420600421, evaluator.evaluate("{% $random(7) %}", statesVar).asDouble());
    }

    @Test
    void random_seedRoundsTowardsZero() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals(evaluator.evaluate("{% $random(0) %}", statesVar).asDouble(),
                evaluator.evaluate("{% $random(0.9) %}", statesVar).asDouble());
    }

    @Test
    void random_surplusArgumentRaisesSignatureError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $random(1, 2) %}", statesVar));
        assertTrue(ex.cause.contains("T0410"));
    }

    @Test
    void roundingGoesTowardsZeroNotDownwards() {
        // -1.7 becomes -1 on AWS, not -2. Flooring shifts every negative argument by one, which
        // is a whole extra element at the head of the range.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("[-1,0,1,2]", evaluator.evaluate("{% $range(-1.7, 2, 1) %}", statesVar).toString());
        assertEquals("[-2,-1,0]", evaluator.evaluate("{% $range(-2.9, 0, 1) %}", statesVar).toString());
        assertEquals("[5,4,3,2,1]", evaluator.evaluate("{% $range(5, 1, -1.5) %}", statesVar).toString());
        // -0.5 rounds to 0, which is undefined, while -2 stays negative and is an error.
        assertTrue(evaluator.evaluate("{% $partition([1,2,3,4], -0.5) %}", statesVar).isMissingNode());
    }

    @Test
    void range_stepNearLongMaxTerminates() {
        // Walking with `v += step` and testing `v <= end` never terminates here: the addition
        // wraps and lands back below the end on every pass.
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate(
                "{% $count($range(-9223372036854775808, 9223372036854775807, 9223372036854775807)) %}", statesVar);
        assertEquals(3, result.asInt());
    }

    @Test
    void hash_computesTheRemainingAlgorithmsAndHashesUtf8Bytes() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("3519fe5ad2c596efe3e276a6f351b8fc0b03db861782490d45f7598ebd0ab5fd"
                        + "5520ed102f38c4a5ec834e98668035fc",
                evaluator.evaluate("{% $hash('Hello', 'SHA-384') %}", statesVar).asText());
        assertEquals("3615f80c9d293ed7402687f94b22d58e529b8cc7916f8fac7fddf7fbd5af4cf7"
                        + "77d3d795a7a00a16bf7e7f3fb9561ee9baae480da9fe7a18769e71886b03f315",
                evaluator.evaluate("{% $hash('Hello', 'SHA-512') %}", statesVar).asText());
        // A non-ASCII character is hashed as UTF-8, not as the platform charset.
        assertEquals("94e9342ecbb1458b6043ecd3bfcbc192",
                evaluator.evaluate("{% $hash('ñ', 'MD5') %}", statesVar).asText());
    }

    @Test
    void hash_missingAlgorithmIsUndefinedButNonStringOneIsASignatureError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertTrue(evaluator.evaluate("{% $hash('a') %}", statesVar).isMissingNode());
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $hash('a', 5) %}", statesVar));
        assertTrue(ex.cause.contains("T0410"));
    }

    @Test
    void parse_keepsFieldOrder() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $parse('{\"b\":1,\"a\":2}') %}", statesVar);
        assertEquals("{\"b\":1,\"a\":2}", result.toString());
    }

    @Test
    void parse_keepsAnIntegerExactWhileItFitsInALong() {
        // Reading every integer as a long regardless of width is what turns a number larger than
        // a long negative. AWS switches to exponent notation at the same boundary.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("9223372036854775807",
                evaluator.evaluate("{% $parse('{\"n\":9223372036854775807}').n %}", statesVar).toString());
        assertEquals(9.223372036854776E18,
                evaluator.evaluate("{% $parse('{\"n\":9223372036854775808}').n %}", statesVar).asDouble());
        assertEquals(1.2345678901234568E29,
                evaluator.evaluate("{% $parse('123456789012345678901234567890') %}", statesVar).asDouble());
    }

    @Test
    void parse_dropsATrailingZeroOnAnIntegerValuedNumber() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("1", evaluator.evaluate("{% $parse('1.0') %}", statesVar).toString());
        assertEquals("100", evaluator.evaluate("{% $parse('1e2') %}", statesVar).toString());
        assertEquals("1.5", evaluator.evaluate("{% $parse('1.5') %}", statesVar).toString());
    }

    /**
     * The execution input reaching {@code $states.input} follows the same number model as
     * {@code $parse} (see {@link #parse_dropsATrailingZeroOnAnIntegerValuedNumber}): both route
     * through {@code toJsonataValue}/{@code toJsonataNumber}. Measured against real AWS
     * (us-east-1, test-state, 2026-08-30): a Pass state with
     * {@code Output: {"v": "{% $states.input.amount %}"}} and input {@code {"amount": 1.0}}
     * answers {@code {"v": 1}}.
     */
    @Test
    void statesInputDropsATrailingZeroOnAnIntegerValuedNumber() throws Exception {
        JsonNode statesVar = objectMapper.readTree("{\"input\": {\"amount\": 1.0}}");
        assertEquals("1", evaluator.evaluate("{% $states.input.amount %}", statesVar).toString());
    }

    /**
     * Same model, the other side of the long boundary (see
     * {@link #parse_keepsAnIntegerExactWhileItFitsInALong}). Measured against real AWS
     * (us-east-1, test-state, 2026-08-30): input {@code {"amount": 9223372036854775808}} answers
     * {@code {"v": 9.223372036854776E18}}.
     */
    @Test
    void statesInputSwitchesToADoublePastTheLongBoundary() throws Exception {
        JsonNode statesVar = objectMapper.readTree("{\"input\": {\"amount\": 9223372036854775808}}");
        assertEquals("9.223372036854776E18",
                evaluator.evaluate("{% $states.input.amount %}", statesVar).toString());
    }

    @Test
    void parse_rejectsJsonAwsRejects() {
        JsonNode statesVar = objectMapper.createObjectNode();
        // An empty body, a second document after the first, a repeated key and a number too large
        // for a double are all D3137 on AWS. A lenient parser turns the first into "" and the
        // second into a silent success on a truncated payload.
        for (String json : new String[]{"''", "'  '", "'{\"a\":1}{\"b\":2}'", "'[1,2] [3,4]'",
                "'{\"a\":1,\"a\":2}'", "'1e400'"}) {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> evaluator.evaluate("{% $parse(" + json + ") %}", statesVar),
                    "expected $parse(" + json + ") to fail");
            assertTrue(ex.cause.contains("D3137: Invalid JSON"),
                    "wrong cause for $parse(" + json + "): " + ex.cause);
        }
    }

    @Test
    void surplusArgumentsRaiseASignatureError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        for (String expression : new String[]{"$parse('{}', 'x')", "$partition([1,2,3], 2, 'x')",
                "$range(1, 5, 1, 'x')", "$hash('a', 'MD5', 'x')"}) {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> evaluator.evaluate("{% " + expression + " %}", statesVar),
                    "expected " + expression + " to fail");
            assertTrue(ex.cause.contains("T0410"), "wrong code for " + expression + ": " + ex.cause);
        }
    }

    @Test
    void functionsAreUsableAsAValueInAHigherOrderCall() {
        // dashjoin dereferences the signature of a bound function unconditionally in $map and
        // friends, so a function bound without one throws a raw NullPointerException here.
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% $map(['{\"a\":1}', '{\"a\":2}'], $parse).a %}", statesVar);
        assertEquals("[1,2]", result.toString());
    }

    /**
     * The expression from issue #2667. JSONata optimises the tail call, so it loops instead of
     * overflowing the stack: nothing is thrown, and before the time bound the execution stayed
     * RUNNING for ever with a core pinned. The evaluator is built with a 250 ms bound rather than
     * the shipped {@link JsonataEvaluator#EVALUATION_TIMEOUT_MILLIS} so the suite stays fast, and
     * the method timeout makes an unbounded evaluation fail the test instead of blocking the run.
     */
    @Test
    @Timeout(30)
    void recursionWithNoBaseCaseFailsOnTheTimeBound() {
        JsonataEvaluator shortlyBounded =
                new JsonataEvaluator(objectMapper, 250, JsonataEvaluator.MAX_EVALUATION_DEPTH);
        JsonNode statesVar = objectMapper.createObjectNode();

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> shortlyBounded.evaluate("{% ($f := function($x){ $f($x+1) }; $f(1)) %}", statesVar));

        assertEquals("States.QueryEvaluationError", ex.error);
        assertEquals("Expression evaluation timeout: Check for infinite loop", ex.cause);
    }

    /**
     * Recursion the library cannot optimise grows the evaluation nesting instead of looping, and
     * without a depth bound it ends in a StackOverflowError, which is an Error and so escapes the
     * evaluator's catch. The shipped bound is what turns it into a state failure.
     *
     * <p>The cause is the library's own string for this failure, with its trailing {@code Depth=N
     * max=M} suffix stripped: AWS never emits it. The library writes two spaces after the full
     * stop where AWS writes one; that divergence is left as is.
     */
    @Test
    @Timeout(30)
    void nonTailRecursionFailsOnTheDepthBound() {
        JsonNode statesVar = objectMapper.createObjectNode();

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate(
                        "{% ($f := function($x){ $x <= 0 ? 0 : 1 + $f($x-1) }; $f(1000000)) %}", statesVar));

        assertEquals("States.QueryEvaluationError", ex.error);
        assertEquals("Stack overflow error: Check for non-terminating recursive"
                + " function.  Consider rewriting as tail-recursive", ex.cause);
    }

    /**
     * The boundary from below: the risk of a bound is that it fails a working state machine, so
     * these must all still evaluate. AWS accepts every one of them and refuses the next value on
     * each axis: the recursion at n=32, the sequence at 873,783 elements.
     */
    @Test
    @Timeout(60)
    void expensiveButLegitimateExpressionsStayWithinTheShippedBounds() {
        JsonNode statesVar = objectMapper.createObjectNode();
        String recursionHead = "{% ($f := function($x){ $x <= 0 ? 0 : 1 + $f($x-1) }; $f(";

        assertEquals(30, evaluator.evaluate(recursionHead + "30)) %}", statesVar).asInt());
        assertEquals(31, evaluator.evaluate(recursionHead + "31)) %}", statesVar).asInt());
        assertEquals(800000, evaluator.evaluate("{% [1..800000] ~> $count() %}", statesVar).asInt());
        assertEquals(40000200000L,
                evaluator.evaluate("{% $sum($map([1..200000], function($v){$v * 2})) %}", statesVar).asLong());
    }

    /**
     * The four nesting shapes of issue #2737, at the last {@code n} AWS accepts and at the first
     * it refuses. An addition chain nests one level per term, so AWS accepts 100 terms;
     * parentheses, brackets and the chain operator nest one level more than their count, so AWS
     * accepts 99 of each. Both put AWS's ceiling at
     * nesting depth 100, which is what {@link JsonataEvaluator#MAX_EVALUATION_DEPTH} holds.
     */
    @Test
    @Timeout(30)
    void everyNestingShapeFailsAtTheDepthAwsFailsAt() {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals(100, evaluator.evaluate("{% 1" + "+1".repeat(99) + " %}", statesVar).asInt());
        assertEquals(1, evaluator.evaluate("{% " + "(".repeat(99) + "1" + ")".repeat(99) + " %}", statesVar).asInt());
        assertTrue(evaluator.evaluate("{% " + "[".repeat(99) + "1" + "]".repeat(99) + " %}",
                statesVar).isArray());
        assertEquals("1", evaluator.evaluate("{% 1" + "~>$string".repeat(99) + " %}", statesVar).asText());

        for (String tooDeep : new String[]{
                "1" + "+1".repeat(100),
                "(".repeat(100) + "1" + ")".repeat(100),
                "[".repeat(100) + "1" + "]".repeat(100),
                "1" + "~>$string".repeat(100)}) {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> evaluator.evaluate("{% " + tooDeep + " %}", statesVar),
                    "expected the depth bound to refuse " + tooDeep.substring(0, 20) + "...");
            assertEquals("States.QueryEvaluationError", ex.error);
            assertTrue(ex.cause.endsWith("Consider rewriting as tail-recursive"), ex.cause);
        }
    }

    /**
     * The non-tail-recursive shape of issue #2737, which nests three levels per call: AWS accepts
     * {@code $f(31)} and refuses {@code $f(32)}, and the depth bound reproduces both.
     */
    @Test
    @Timeout(30)
    void nonTailRecursionFailsAtTheCallDepthAwsFailsAt() {
        JsonNode statesVar = objectMapper.createObjectNode();
        String recursionHead = "{% ($f := function($x){ $x <= 0 ? 0 : 1 + $f($x-1) }; $f(";

        assertEquals(31, evaluator.evaluate(recursionHead + "31)) %}", statesVar).asInt());

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate(recursionHead + "32)) %}", statesVar));
        assertEquals("States.QueryEvaluationError", ex.error);
        assertTrue(ex.cause.endsWith("Consider rewriting as tail-recursive"), ex.cause);
    }

    /**
     * The memory bound of issue #2737 on the sequence AWS draws it on: AWS accepts
     * {@code [1..873782]} and refuses one element more, and counting a number as eight bytes puts
     * those two on either side of {@link JsonataEvaluator#MAX_EXPRESSION_BYTES} here as well.
     */
    @Test
    @Timeout(60)
    void theLargestSequenceAwsAcceptsEvaluatesAndTheNextOneFailsOnTheMemoryBound() {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals(873782, evaluator.evaluate("{% [1..873782] ~> $count() %}", statesVar).asInt());

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% [1..873783] ~> $count() %}", statesVar));
        assertEquals("States.QueryEvaluationError", ex.error);
        assertEquals("Expression evaluation memory limit exceeded: Check for excessive memory usage", ex.cause);
    }

    /**
     * The same memory bound on a string: a string that doubles 22 times evaluates on AWS and one
     * that doubles 23 times does not. A character counts as one byte, which leaves 2^22 characters
     * inside the bound and 2^23 outside it, as on AWS.
     */
    @Test
    @Timeout(60)
    void aStringDoublingStopsAtTheSameBoundAwsStopsAt() {
        JsonNode statesVar = objectMapper.createObjectNode();
        String doublingHead = "{% ($f := function($s, $n){ $n = 0 ? $s : $f($s & $s, $n - 1) };"
                + " $length($f('x', ";

        assertEquals(4194304, evaluator.evaluate(doublingHead + "22))) %}", statesVar).asInt());

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate(doublingHead + "23))) %}", statesVar));
        assertEquals("States.QueryEvaluationError", ex.error);
        assertEquals("Expression evaluation memory limit exceeded: Check for excessive memory usage", ex.cause);
    }

    /**
     * The two expressions of issue #2737 that AWS refuses on its memory limit. They fail the state
     * here too, so a Catch fires on them rather than the state succeeding with a value.
     */
    @Test
    @Timeout(60)
    void theSequencesAwsRefusesOnItsMemoryLimitFailTheStateHere() {
        JsonNode statesVar = objectMapper.createObjectNode();

        for (String expression : new String[]{"[1..900000] ~> $count()", "$sum([1..900000])"}) {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> evaluator.evaluate("{% " + expression + " %}", statesVar),
                    "expected the memory bound to refuse " + expression);
            assertEquals("States.QueryEvaluationError", ex.error);
            assertEquals("Expression evaluation memory limit exceeded: Check for excessive memory usage", ex.cause);
        }
    }

    /**
     * AWS disables $eval and answers T1006 for a call to it. The library ships $eval, so the name
     * is left unbound here and a call to it answers the same code; $parse is the replacement AWS
     * documents.
     */
    @Test
    void evalIsNotBoundBecauseAwsDisablesIt() {
        JsonNode statesVar = objectMapper.createObjectNode();

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $eval('1+1') %}", statesVar));

        assertEquals("States.QueryEvaluationError", ex.error);
        assertEquals("T1006: Attempted to invoke a non-function", ex.cause);
    }

    /**
     * JSON has no literal for a non-finite number, so AWS writes each one as the string JavaScript
     * names it by. The library refuses to carry the value at all and raises D1001 instead, and the
     * value that refusal carries is what these answer.
     */
    @Test
    void aNonFiniteNumberIsTheStringAwsWritesForIt() {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals("Infinity", evaluator.evaluate("{% 1/0 %}", statesVar).asText());
        assertEquals("Infinity", evaluator.evaluate("{% 1e308 * 10 %}", statesVar).asText());
        assertEquals("-Infinity", evaluator.evaluate("{% -1/0 %}", statesVar).asText());
    }

    /**
     * The shapes AWS answers a string for and Floci does not. A non-finite number the library's own
     * arithmetic produces reaches Floci only as the exception the library raises instead of carrying
     * the value, and by then the exception has unwound whatever value was being built around it, so
     * only an expression whose own result is non-finite can be answered. AWS answers
     * {@code ["Infinity"]} for the first, {@code {"k":"Infinity"}} for the second and
     * {@code "NaN"} for {@code 0/0}.
     *
     * <p>Failing the state is the half of the divergence a Catch fires on, which is why the value is
     * not answered at the top of an expression that was building something else with it.
     */
    @Test
    void aNonFiniteNumberTheArithmeticBuildsInsideAValueFailsTheStateInstead() {
        JsonNode statesVar = objectMapper.createObjectNode();

        for (String expression : new String[]{"[1/0]", "{'k': 1/0}", "[1/0, 2]", "$string(1/0)"}) {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> evaluator.evaluate("{% " + expression + " %}", statesVar),
                    "expected " + expression + " to fail the state");
            assertEquals("States.QueryEvaluationError", ex.error);
            assertEquals("D1001: Number out of range: \"Infinity\"", ex.cause);
        }

        // NaN raises nothing at all: the library reads it as not a number and drops it, so the
        // expression returns nothing and the field it was written in is what fails.
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% 0/0 %}", "Output/r", statesVar, null));
        assertEquals("The JSONata expression '0/0' specified for the field 'Output/r' "
                + "returned nothing (undefined).", ex.cause);
    }

    /**
     * $parseInteger answers NaN for a value its picture cannot read, where the library answers
     * nothing and an ASL field that evaluates to nothing fails the state.
     */
    @Test
    void parseIntegerAnswersNaNForAValueThePictureCannotRead() {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals("NaN", evaluator.evaluateField("{% $parseInteger('abc', '0') %}",
                "Output/r", statesVar, null).asText());
        // A non-finite number a function answers is carried like any other value, so it reaches the
        // output as AWS writes it wherever it lands, which is what arithmetic cannot do.
        assertEquals("[\"NaN\"]",
                evaluator.evaluate("{% [$parseInteger('abc', '0')] %}", statesVar).toString());
        assertEquals("{\"k\":\"NaN\"}",
                evaluator.evaluate("{% {'k': $parseInteger('abc', '0')} %}", statesVar).toString());
        // A value the picture does read stays the integer it was, not a double.
        assertEquals("123", evaluator.evaluate("{% $parseInteger('123', '0') %}", statesVar).toString());
    }

    /**
     * A picture string with no digit in it describes no number, and AWS fails the state on it
     * where the library formats through DecimalFormat and answers "x1".
     */
    @Test
    void formatNumberRefusesAPictureThatDescribesNoNumber() {
        JsonNode statesVar = objectMapper.createObjectNode();

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $formatNumber(1, 'x') %}", statesVar));

        assertEquals("States.QueryEvaluationError", ex.error);
        assertEquals("D3086: The sub-picture must not contain a passive character that is preceded"
                + " by an active character and that is followed by another active character", ex.cause);
    }

    @Test
    void formatNumberNamesTheRuleThePictureBreaks() {
        JsonNode statesVar = objectMapper.createObjectNode();
        Map<String, String> expectedCodes = Map.ofEntries(
                Map.entry("0.0;0.0;0.0", "D3080"), Map.entry("0.0.0", "D3081"),
                Map.entry("0%%", "D3082"), Map.entry("0‰‰", "D3083"),
                Map.entry("0%‰", "D3084"), Map.entry("0,.0", "D3087"),
                Map.entry("0,", "D3088"), Map.entry("0,,0", "D3089"),
                Map.entry("0#", "D3090"), Map.entry("##0.0#0", "D3091"),
                Map.entry("0e0%", "D3092"), Map.entry("#0e", "D3093"));

        expectedCodes.forEach((picture, code) -> {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> evaluator.evaluate("{% $formatNumber(1, '" + picture + "') %}", statesVar),
                    "expected " + picture + " to fail");
            assertTrue(ex.cause.startsWith(code + ":"), picture + " gave " + ex.cause);
        });
    }

    @Test
    void formatNumberStillFormatsThePicturesTheRulesAllow() {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals("1,234.57", evaluator.evaluate("{% $formatNumber(1234.5678, '#,##0.00') %}",
                statesVar).asText());
        assertEquals("12.3%", evaluator.evaluate("{% $formatNumber(0.123, '#0.0%') %}", statesVar).asText());
        assertEquals("$1,234.57", evaluator.evaluate("{% $formatNumber(1234.5678, '$#,##0.00') %}",
                statesVar).asText());
        // A passive character before the first active one (a currency prefix, or the parenthesis
        // AWS's own negative sub-picture opens with) shifted the exponent separator's index without
        // shifting it back onto the active part, so any of these with an exponent was refused with a
        // spurious D3093.
        assertEquals("-$12e2", evaluator.evaluate("{% $formatNumber(-1200, '$#0e0') %}", statesVar).asText());
        assertEquals("-12e2", evaluator.evaluate("{% $formatNumber(-1200, '#0e0;-#0e0') %}", statesVar).asText());
        assertEquals("(12e2)", evaluator.evaluate("{% $formatNumber(-1200, '#0e0;(#0e0)') %}", statesVar).asText());
    }

    @Test
    void string_writesAWholeNumberInFullBelowTheExponentBoundary() {
        // dashjoin stops writing digits at Long.MAX_VALUE, 9.223372036854776E18, and prints
        // exponent notation from there. AWS keeps writing them until 1e21.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("10000000000000000000", evaluator.evaluate("{% $string(1e19) %}", statesVar).asText());
        assertEquals("100000000000000000000", evaluator.evaluate("{% $string(1e20) %}", statesVar).asText());
        assertEquals("150000000000000000000", evaluator.evaluate("{% $string(1.5e20) %}", statesVar).asText());
        // The largest double under the boundary.
        assertEquals("999999999999999900000",
                evaluator.evaluate("{% $string(999999999999999868928) %}", statesVar).asText());
    }

    @Test
    void string_switchesToExponentNotationAt1e21() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("1e+21", evaluator.evaluate("{% $string(1e21) %}", statesVar).asText());
        // 1e21 - 1 is not a double, so it is the same number as 1e21 and prints the same way.
        assertEquals("1e+21", evaluator.evaluate("{% $string(1e21 - 1) %}", statesVar).asText());
        assertEquals("1e+22", evaluator.evaluate("{% $string(1e22) %}", statesVar).asText());
        assertEquals("1e+100", evaluator.evaluate("{% $string(1e100) %}", statesVar).asText());
    }

    @Test
    void string_flipsAtTheSameBoundaryOnANegativeNumber() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("-100000000000000000000", evaluator.evaluate("{% $string(-1e20) %}", statesVar).asText());
        assertEquals("-150000000000000000000", evaluator.evaluate("{% $string(-1.5e20) %}", statesVar).asText());
        assertEquals("-1e+21", evaluator.evaluate("{% $string(-1e21) %}", statesVar).asText());
    }

    @Test
    void string_writesTheShortestDigitsThatReadBackAsTheSameDouble() {
        // The two sides of $parse's number model print differently across the long boundary: the
        // long prints its exact value, the double prints the shortest decimal that reads back as
        // itself, so 2^63 is 9223372036854776000 and not its exact 9223372036854775808.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("9223372036854775807",
                evaluator.evaluate("{% $string($parse('{\"n\":9223372036854775807}').n) %}", statesVar).asText());
        assertEquals("9223372036854776000",
                evaluator.evaluate("{% $string($parse('{\"n\":9223372036854775808}').n) %}", statesVar).asText());
    }

    @Test
    void string_writesANumberInsideAnObjectOrAnArrayTheSameWay() {
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("{\"n\":100000000000000000000}",
                evaluator.evaluate("{% $string({'n': 1e20}) %}", statesVar).asText());
        assertEquals("[100000000000000000000,2]",
                evaluator.evaluate("{% $string([1e20, 2]) %}", statesVar).asText());
    }

    @Test
    void string_leavesEveryOtherValueToTheBuiltIn() {
        JsonNode statesVar = objectMapper.createObjectNode();
        // AWS switches to exponent notation on the small side too, under 1e-6, and keeps fifteen
        // significant digits on a fractional number. Both already match.
        assertEquals("0.000001", evaluator.evaluate("{% $string(1e-6) %}", statesVar).asText());
        assertEquals("1e-7", evaluator.evaluate("{% $string(1e-7) %}", statesVar).asText());
        assertEquals("0.333333333333333", evaluator.evaluate("{% $string(1/3) %}", statesVar).asText());
        assertEquals("true", evaluator.evaluate("{% $string(true) %}", statesVar).asText());
        assertEquals("abc", evaluator.evaluate("{% $string('abc') %}", statesVar).asText());
        assertEquals("null", evaluator.evaluate("{% $string(null) %}", statesVar).asText());
        assertEquals("{\n  \"a\": 1\n}", evaluator.evaluate("{% $string({'a': 1}, true) %}", statesVar).asText());
        // $string() with no argument returns nothing, which this change stops reporting as an
        // explicit null. AWS fails the state on it, which is #2665's subject and not this one's.
        assertTrue(evaluator.evaluate("{% $string() %}", statesVar).isMissingNode());
        assertEquals("[\"100000000000000000000\",\"1e+21\"]",
                evaluator.evaluate("{% $map([1e20, 1e21], $string) %}", statesVar).toString());
    }

    // ---- the cause of a States.QueryEvaluationError (#2668) ----
    // Every expected string below was captured from real AWS through test-state, minus the
    // "The JSONata expression '...' specified for the field '...' threw an error during
    // evaluation." prefix AWS puts in front of it, which needs the field path #2665 threads.

    @Test
    void theCauseCarriesTheJsonataErrorCode() {
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $split('a','b',-1) %}", statesVar));
        assertEquals("D3020: Third argument of split function must evaluate to a positive number",
                ex.cause);
    }

    @Test
    void theErrorNameStaysQueryEvaluationErrorSoACatchStillMatchesIt() {
        JsonNode statesVar = objectMapper.createObjectNode();
        for (String expression : new String[]{"$sum(['a'])", "1 < 'a'", "$sqrt(-1)",
                "$each('x', function($v){$v})", "$parse(null)"}) {
            AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                    () -> evaluator.evaluate("{% " + expression + " %}", statesVar),
                    "expected " + expression + " to fail");
            assertEquals("States.QueryEvaluationError", ex.error,
                    "wrong error name for " + expression);
        }
    }

    @Test
    void aFunctionNameIsNamedAsAFunctionAndNotAsAnObject() {
        // dashjoin ships jsonata-js's message catalog with the word "function" replaced by
        // "Object" throughout, which is 116 of the 117 malformed causes in #2668.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("T0410: Argument 1 of function \"each\" does not match function signature",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $each('x', function($v){$v}) %}", statesVar)).cause);
        assertEquals("D3050: The second argument of reduce function must be a function"
                        + " with at least two arguments",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $reduce([1,2], function($a){$a}) %}", statesVar)).cause);
        assertEquals("D3138: The $single() function expected exactly 1 matching result. "
                        + " Instead it matched more.",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $single([1,2], function($x){true}) %}", statesVar)).cause);
    }

    @Test
    void anArrayElementTypeIsNamedInsteadOfLeftAsATypePlaceholder() {
        // T0412 is the one code in the catalog carrying both defects: the "Object" word and a
        // third placeholder that dashjoin's two-slot substituter never reaches. It also carries
        // neither the argument index nor the function name AWS names, so the cause states what
        // the library does hand over: the offending argument and the element type it wanted.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("T0412: Argument [\"a\"] must be an array of \"numbers\"",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $sum(['a']) %}", statesVar)).cause);
        assertEquals("T0412: Argument [\"a\",\"b\"] must be an array of \"numbers\"",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $average(['a','b']) %}", statesVar)).cause);
    }

    @Test
    void bothComparedValuesAreNamedInsteadOfLeftAsATokenPlaceholder() {
        // T2009 is the other three-placeholder code, and the only one of the 117 whose leftover
        // is {{token}}. The operator is not carried either, so the cause names the two values.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("T2009: The values 1 and \"a\" either side of the operator"
                        + " must be of the same data type",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% 1 < 'a' %}", statesVar)).cause);
        assertEquals("T2009: The values \"a\" and 1 either side of the operator"
                        + " must be of the same data type",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% 'a' < 1 %}", statesVar)).cause);
    }

    @Test
    void aMessageThrownInPlaceOfACodeIsNotPrefixedWithJSonataException() {
        // dashjoin's own catalog lookup falls back to "JSonataException " + code, and both this
        // class's six functions and a handful of library call sites throw with the whole message
        // in the code slot. AWS emits neither that prefix nor the class name.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("T0410: Argument 1 of function \"parse\" does not match function signature",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $parse(null) %}", statesVar)).cause);
        assertEquals("Second argument of replace function cannot be an empty string",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $replace('a','','b') %}", statesVar)).cause);
    }

    @Test
    void aValueSubstitutedIntoTheMessageIsRenderedAsJson() {
        // dashjoin quotes every value it substitutes; jsonata-js and AWS JSON-render it, so a
        // number stays bare and an array keeps its brackets.
        JsonNode statesVar = objectMapper.createObjectNode();
        assertEquals("D3060: The sqrt function cannot be applied to a negative number: -1",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% $sqrt(-1) %}", statesVar)).cause);
        assertEquals("T2002: The right side of the \"+\" operator must evaluate to a number",
                assertThrows(AslExecutor.FailStateException.class,
                        () -> evaluator.evaluate("{% 1 + {} %}", statesVar)).cause);
    }

    @Test
    void aNonJsonataFailureKeepsTheMessageItCameWith() {
        // dashjoin lets a plain Java exception out of a few functions. There is no code and no
        // template to render, and swallowing the message would lose the only diagnosis there is.
        JsonNode statesVar = objectMapper.createObjectNode();
        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% $toMillis('nope') %}", statesVar));
        assertEquals("States.QueryEvaluationError", ex.error);
        assertTrue(ex.cause.contains("nope"), ex.cause);
    }

    @Test
    void anExplicitNullKeepsItsKey() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"bar": null}}
                """);
        JsonNode template = objectMapper.readTree("""
                {"fromInput": "{% $lookup($states.input, 'bar') %}",
                 "kept": 1}
                """);

        assertEquals("{\"fromInput\":null,\"kept\":1}",
                evaluator.resolveTemplate(template, "Output", statesVar).toString());
    }

    @Test
    void anExplicitNullSurvivesInsideANestedObject() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode template = objectMapper.readTree("""
                {"outer": {"fromExpression": "{% null %}", "kept": 1}}
                """);

        assertEquals("{\"outer\":{\"fromExpression\":null,\"kept\":1}}",
                evaluator.resolveTemplate(template, "Output", statesVar).toString());
    }

    @Test
    void aWholeTemplateEvaluatingToNullIsANull() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertTrue(evaluator.resolveTemplate(objectMapper.readTree("\"{% null %}\""), "Output", statesVar).isNull());
    }

    @Test
    void anExplicitNullIsAnArrayElement() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals("{\"values\":[null,1]}", evaluator.resolveTemplate(objectMapper.readTree("""
                {"values": ["{% null %}", 1]}
                """), "Output", statesVar).toString());
    }

    @Test
    void aLiteralNullInTheTemplateKeepsItsKey() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals("{\"literal\":null,\"kept\":1}", evaluator.resolveTemplate(objectMapper.readTree("""
                {"literal": null, "kept": 1}
                """), "Output", statesVar).toString());
    }

    @Test
    void anInputNullIsAValueForExistsAndType() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"bar": null}}
                """);

        assertTrue(evaluator.evaluate("{% $exists($states.input.bar) %}", statesVar).asBoolean());
        assertEquals("null", evaluator.evaluate("{% $type($states.input.bar) %}", statesVar).asText());
    }

    @Test
    void anAssignedNullReadsBackAsANull() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode variables = objectMapper.readTree("""
                {"nullVariable": null}
                """);

        assertTrue(evaluator.evaluate("{% $nullVariable %}", statesVar, variables).isNull());
        assertTrue(evaluator.evaluate("{% $exists($nullVariable) %}", statesVar, variables).asBoolean());
    }

    /**
     * The field path AWS names in the cause: the ASL field the template came from, a {@code /}
     * before every object key below it and {@code [i]} appended for every array index. Measured on
     * TestState, one Pass or one Assign per row.
     */
    static Stream<Arguments> templatesWhoseExpressionReturnsNothing() {
        return Stream.of(
                Arguments.of("{\"v\": \"{% $states.input.missing %}\"}", "Output", "Output/v"),
                Arguments.of("{\"a\": {\"b\": \"{% $states.input.missing %}\"}}", "Output", "Output/a/b"),
                Arguments.of("\"{% $states.input.missing %}\"", "Output", "Output"),
                Arguments.of("{\"values\": [\"{% $states.input.missing %}\", 1]}", "Output", "Output/values[0]"),
                Arguments.of("{\"a\": {\"b\": [[1, \"{% $states.input.missing %}\"]]}}", "Output", "Output/a/b[0][1]"),
                Arguments.of("{\"x\": {\"y\": \"{% $states.input.missing %}\"}}", "Assign", "Assign/x/y"));
    }

    @ParameterizedTest
    @MethodSource("templatesWhoseExpressionReturnsNothing")
    void anExpressionReturningNothingFailsTheStateNamingTheFieldPath(String template, String field,
                                                                     String expectedFieldPath) throws Exception {
        JsonNode statesVar = objectMapper.readTree("{\"input\": {}}");

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.resolveTemplate(objectMapper.readTree(template), field, statesVar));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertEquals("The JSONata expression '$states.input.missing' specified for the field '"
                + expectedFieldPath + "' returned nothing (undefined).", failure.cause);
    }

    /**
     * #2738: {@code evaluate} caught {@code Exception}, not {@code Error}, so a
     * {@code java.lang.Error} raised while parsing or evaluating an expression escaped straight
     * past this method to {@code AslExecutor}'s last-resort {@code catch (Error e)}, failing the
     * whole execution as {@code States.Runtime} before the state's own {@code Retry}/{@code Catch}
     * ever ran. This pins the {@code StackOverflowError} arm of the catch, with an expression
     * nested 200,000 parentheses deep that overflows the JVM call stack during parsing in a few
     * milliseconds and never grows a string past a few hundred KB. The other arm,
     * {@code OutOfMemoryError}, remains a guard with no cheap trigger through this library: the
     * memory bound now trips first on every expression measured against it, including the one the
     * issue reproduced with (see
     * {@code StepFunctionsJsonataIntegrationTest.parallelBranchErrorIsCatchableByStatesAll}).
     */
    @Test
    void aStackOverflowErrorDuringParsingBecomesAQueryEvaluationError() {
        JsonNode statesVar = objectMapper.createObjectNode();
        String deeplyNested = "(".repeat(200_000) + "1" + ")".repeat(200_000);

        AslExecutor.FailStateException ex = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluate("{% " + deeplyNested + " %}", statesVar));

        assertEquals("States.QueryEvaluationError", ex.error);
        assertTrue(ex.cause.contains("StackOverflowError"), ex.cause);
    }

    /**
     * The {@code StackOverflowError}/{@code OutOfMemoryError} arm used to throw a bare
     * {@code FailStateException}, which {@link JsonataEvaluator#evaluateField} does not catch as a
     * {@code QueryEvaluationFailure}, so the field's expression and AWS's sentence never reached the
     * cause: a Catch on this failure saw the bare {@code java.lang.StackOverflowError} instead of
     * the sentence every other evaluation failure carries.
     */
    @Test
    void evaluateFieldWrapsAStackOverflowErrorWithTheSentenceAndAColonSeparator() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();
        String deeplyNested = "(".repeat(200_000) + "1" + ")".repeat(200_000);

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% " + deeplyNested + " %}", "Output/v", statesVar, null));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertTrue(failure.cause.contains("threw an error during evaluation: "), failure.cause);
        assertTrue(failure.cause.contains("StackOverflowError"), failure.cause);
    }

    @Test
    void evaluateFieldNamesTheFieldOfAPositionThatHoldsASingleExpression() throws Exception {
        JsonNode statesVar = objectMapper.readTree("{\"input\": {}}");

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% $states.input.missing %}", "Seconds", statesVar, null));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertEquals("The JSONata expression '$states.input.missing' specified for the field 'Seconds' "
                + "returned nothing (undefined).", failure.cause);
    }

    @Test
    void evaluateFieldWrapsAThrownJsonataErrorWithTheSentenceAndACodedSeparator() throws Exception {
        // A coded JSONata failure carries AWS's sentence in front of the code, joined by ". ".
        JsonNode statesVar = objectMapper.createObjectNode();

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% $sum(['a']) %}", "Output/v", statesVar, null));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertEquals("The JSONata expression '$sum(['a'])' specified for the field 'Output/v' threw an error "
                + "during evaluation. T0412: Argument [\"a\"] must be an array of \"numbers\"", failure.cause);
    }

    @Test
    void evaluateFieldWrapsABoundFailureWithTheSentenceAndAColonSeparator() throws Exception {
        // A failure with no JSONata code and no template to render (see
        // aNonJsonataFailureKeepsTheMessageItCameWith) carries AWS's sentence in front of the bare
        // message it came with, joined by ": " where a coded one joins with ". ".
        JsonNode statesVar = objectMapper.createObjectNode();

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% $toMillis('nope') %}", "Output/v", statesVar, null));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertTrue(failure.cause.startsWith("The JSONata expression '$toMillis('nope')' specified for the field "
                + "'Output/v' threw an error during evaluation: "), failure.cause);
        assertTrue(failure.cause.contains("nope"), failure.cause);
    }

    /**
     * The memory bound's message carries no JSONata code slot, so it is a bare bound message like
     * {@code $toMillis('nope')}'s and joins AWS's sentence with {@code ": "}, not the {@code ". "}
     * a coded message joins with. Measured against real AWS (us-east-1, test-state): this is its
     * cause byte for byte.
     */
    @Test
    @Timeout(60)
    void evaluateFieldWrapsTheMemoryBoundFailureWithTheSentenceAndAColonSeparator() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% [1..900000] ~> $count() %}", "Output/r", statesVar, null));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertEquals("The JSONata expression '[1..900000] ~> $count()' specified for the field 'Output/r' threw "
                + "an error during evaluation: Expression evaluation memory limit exceeded: Check for excessive "
                + "memory usage", failure.cause);
    }

    /**
     * The depth bound's message is the same shape: no JSONata code slot, so {@code ": "} joins it
     * to AWS's sentence too. AWS's own cause for this same 101-term chain, measured the same way,
     * ends {@code "...evaluation: Stack overflow error: Check for non-terminating recursive
     * function. Consider rewriting as tail-recursive"}; the library's own double space after
     * "function." is the one divergence {@link JsonataEvaluator#withoutDepthBoundSuffix} leaves in
     * place, so this checks the sentence and the join rather than the byte-exact tail.
     */
    @Test
    @Timeout(30)
    void evaluateFieldWrapsTheDepthBoundFailureWithTheSentenceAndAColonSeparator() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();
        String chain = "1" + "+1".repeat(100);

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> evaluator.evaluateField("{% " + chain + " %}", "Output/r", statesVar, null));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertTrue(failure.cause.startsWith("The JSONata expression '" + chain + "' specified for the field "
                + "'Output/r' threw an error during evaluation: Stack overflow error: Check for non-terminating"),
                failure.cause);
        assertTrue(failure.cause.endsWith("Consider rewriting as tail-recursive"), failure.cause);
    }

    @Test
    void anExplicitNullIsAValueAndKeepsEveryPositionEvaluating() throws Exception {
        JsonNode statesVar = objectMapper.readTree("{\"input\": {\"bar\": null}}");

        assertTrue(evaluator.evaluateField("{% null %}", "Seconds", statesVar, null).isNull());
        assertEquals("{\"v\":null}", evaluator.resolveTemplate(
                objectMapper.readTree("{\"v\": \"{% $states.input.bar %}\"}"), "Output", statesVar).toString());
    }
}
