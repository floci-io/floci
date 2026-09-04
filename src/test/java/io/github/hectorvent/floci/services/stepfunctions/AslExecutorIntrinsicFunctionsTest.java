package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Functionality + regression coverage for the five JSONPath-mode intrinsic functions added
 * alongside the existing nine: {@code States.Base64Encode}, {@code States.Base64Decode},
 * {@code States.StringSplit}, {@code States.ArrayGetItem}, and {@code States.Hash}. Before this
 * change each aborted execution with "Unsupported intrinsic function: States.&lt;Name&gt;"
 * ({@code States.Runtime}).
 *
 * <p>AWS-documented examples are used as fixtures where they exist (StringSplit and ArrayGetItem
 * doc examples; a computed SHA-256/SHA-1/MD5 of "input data"). AWS's published SHA-1 example digest
 * is a 39-character typo, so the true 40-character digest is asserted instead. Every failure case
 * asserts the exact {@code States.IntrinsicFailure} error name (catchable), never a bare
 * RuntimeException. A reverted implementation would instead throw the uncatchable
 * {@code States.Runtime}, so a bare-throw assertion would silently pass against broken code.
 */
class AslExecutorIntrinsicFunctionsTest {

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

    // ---------- A. States.Base64Encode ----------

    @Test
    void base64EncodeMatchesAwsDocExample() throws Exception {
        JsonNode root = mapper.readTree("{\"data\":\"Data to encode\"}");
        assertEquals("RGF0YSB0byBlbmNvZGU=",
                executor.resolvePath("States.Base64Encode($.data)", root).asText());
    }

    @Test
    void base64EncodeUsesBasicAlphabetNotUrlSafe() {
        assertEquals("Pj4+", executor.resolvePath("States.Base64Encode('>>>')", obj()).asText());
        assertEquals("Pz8/", executor.resolvePath("States.Base64Encode('???')", obj()).asText());
    }

    @Test
    void base64EncodeDoesNotMimeWrapLongOutput() {
        ObjectNode root = mapper.createObjectNode();
        root.put("s", "a".repeat(100));
        String out = executor.resolvePath("States.Base64Encode($.s)", root).asText();
        assertEquals(136, out.length());
        assertFalse(out.contains("\n"), "Basic encoder must not insert line breaks");
        assertFalse(out.contains("\r"), "Basic encoder must not insert carriage returns");
    }

    @Test
    void base64EncodeOfEmptyStringIsEmptyString() {
        assertEquals("", executor.resolvePath("States.Base64Encode('')", obj()).asText());
    }

    @Test
    void base64EncodeAtExactly10000CharsSucceeds() {
        ObjectNode root = mapper.createObjectNode();
        root.put("s", "a".repeat(10_000));
        assertEquals(13_336, executor.resolvePath("States.Base64Encode($.s)", root).asText().length());
    }

    @Test
    void base64EncodeOver10000CharsIsIntrinsicFailure() {
        ObjectNode root = mapper.createObjectNode();
        root.put("s", "a".repeat(10_001));
        assertIntrinsicFailure(root, "States.Base64Encode($.s)");
    }

    @Test
    void base64EncodeCapCountsCodePointsNotUtf16Units() {
        // 5,001 astral characters = 5,001 code points but 10,002 UTF-16 units. A String.length()
        // based cap would wrongly reject this; the code-point cap accepts it.
        ObjectNode root = mapper.createObjectNode();
        root.put("s", "😀".repeat(5_001));
        assertFalse(executor.resolvePath("States.Base64Encode($.s)", root).asText().isEmpty());
    }

    @Test
    void base64EncodeNonStringArgumentIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"n\":5}"), "States.Base64Encode($.n)");
    }

    @Test
    void base64EncodeWrongArgumentCountIsIntrinsicFailure() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":\"x\",\"b\":\"y\"}");
        assertIntrinsicFailure(root, "States.Base64Encode($.a, $.b)");
    }

    // ---------- B. States.Base64Decode ----------

    @Test
    void base64DecodeMatchesAwsDocExample() {
        assertEquals("Data to encode",
                executor.resolvePath("States.Base64Decode('RGF0YSB0byBlbmNvZGU=')", obj()).asText());
    }

    @Test
    void base64RoundTripsThroughNestedIntrinsics() throws Exception {
        JsonNode root = mapper.readTree("{\"s\":\"round trip value\"}");
        assertEquals("round trip value",
                executor.resolvePath("States.Base64Decode(States.Base64Encode($.s))", root).asText());
    }

    @Test
    void base64DecodeInvalidInputIsIntrinsicFailure() {
        assertIntrinsicFailure(obj(), "States.Base64Decode('not base64!!!')");
    }

    @Test
    void base64DecodeRejectsEmbeddedWhitespace() throws Exception {
        // The Basic decoder rejects embedded line breaks (the MIME decoder would silently ignore
        // them). Pinned so the deliberate Basic-vs-MIME choice cannot regress unnoticed.
        JsonNode root = mapper.readTree("{\"b\":\"RGF0\\nYQ==\"}");
        assertIntrinsicFailure(root, "States.Base64Decode($.b)");
    }

    @Test
    void base64DecodeAtExactly10000CharsSucceeds() {
        ObjectNode root = mapper.createObjectNode();
        root.put("b", "A".repeat(10_000)); // valid base64 (len % 4 == 0), decodes fine
        assertFalse(executor.resolvePath("States.Base64Decode($.b)", root).isNull());
    }

    @Test
    void base64DecodeOver10000CharsIsIntrinsicFailure() {
        ObjectNode root = mapper.createObjectNode();
        root.put("b", "A".repeat(10_004)); // valid alphabet + length, so the cap (not invalidity) fires
        assertIntrinsicFailure(root, "States.Base64Decode($.b)");
    }

    @Test
    void base64DecodeWrongArgumentCountIsIntrinsicFailure() {
        assertIntrinsicFailure(obj(), "States.Base64Decode()");
    }

    // ---------- C. States.StringSplit ----------

    @Test
    void stringSplitMatchesAwsDocExample() throws Exception {
        JsonNode root = mapper.readTree("{\"inputString\":\"This.is+a,test=string\",\"splitter\":\".+,=\"}");
        JsonNode out = executor.resolvePath("States.StringSplit($.inputString, $.splitter)", root);
        assertTrue(out.isArray());
        assertEquals(5, out.size());
        assertEquals("This", out.get(0).asText());
        assertEquals("is", out.get(1).asText());
        assertEquals("a", out.get(2).asText());
        assertEquals("test", out.get(3).asText());
        assertEquals("string", out.get(4).asText());
    }

    @Test
    void stringSplitWithQuotedLiteralArguments() {
        // The comma inside the '.+,=' literal is inside quotes, so the quote-aware splitter does not
        // treat it as an argument separator.
        JsonNode out = executor.resolvePath(
                "States.StringSplit('This.is+a,test=string', '.+,=')", obj());
        assertEquals(5, out.size());
        assertEquals("string", out.get(4).asText());
    }

    @Test
    void stringSplitOmitsEmptySegments() throws Exception {
        JsonNode root = mapper.readTree("{\"s\":\",,a,,b,,\"}");
        JsonNode out = executor.resolvePath("States.StringSplit($.s, ',')", root);
        assertEquals(2, out.size());
        assertEquals("a", out.get(0).asText());
        assertEquals("b", out.get(1).asText());
    }

    @Test
    void stringSplitOfOnlyDelimitersYieldsEmptyArray() throws Exception {
        JsonNode root = mapper.readTree("{\"s\":\"...\"}");
        JsonNode out = executor.resolvePath("States.StringSplit($.s, '.')", root);
        assertTrue(out.isArray());
        assertEquals(0, out.size());
    }

    @Test
    void stringSplitOnSupplementaryCharacterDelimiter() throws Exception {
        // Delimiter is a single astral character (😀 U+1F600).
        JsonNode root = mapper.readTree("{\"s\":\"a😀b😀c\",\"d\":\"😀\"}");
        JsonNode out = executor.resolvePath("States.StringSplit($.s, $.d)", root);
        assertEquals(3, out.size());
        assertEquals("a", out.get(0).asText());
        assertEquals("b", out.get(1).asText());
        assertEquals("c", out.get(2).asText());
    }

    @Test
    void stringSplitDoesNotMatchSharedSurrogateHalf() throws Exception {
        // Input 😁 (U+1F601) and delimiter 😀 (U+1F600) share the high surrogate \uD83D. A naive
        // UTF-16 char scan would split on that shared half and corrupt the input; the code-point
        // scan must leave 😁 intact.
        JsonNode root = mapper.readTree("{\"s\":\"😁\",\"d\":\"😀\"}");
        JsonNode out = executor.resolvePath("States.StringSplit($.s, $.d)", root);
        assertEquals(1, out.size());
        assertEquals("😁", out.get(0).asText());
    }

    @Test
    void stringSplitEmptyDelimiterIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"s\":\"abc\"}"), "States.StringSplit($.s, '')");
    }

    @Test
    void stringSplitNonStringArgumentIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"n\":7}"), "States.StringSplit($.n, ',')");
    }

    @Test
    void stringSplitWrongArgumentCountIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"s\":\"a,b\"}"), "States.StringSplit($.s)");
    }

    // ---------- D. States.ArrayGetItem ----------

    @Test
    void arrayGetItemReturnsZeroBasedElement() throws Exception {
        JsonNode root = mapper.readTree("{\"arr\":[1,2,3,4,5,6,7,8,9],\"index\":5}");
        assertEquals(6, executor.resolvePath("States.ArrayGetItem($.arr, $.index)", root).asInt());
    }

    @Test
    void arrayGetItemReturnsComplexElementUnchanged() throws Exception {
        JsonNode root = mapper.readTree("{\"arr\":[{\"k\":1},{\"k\":2}]}");
        JsonNode out = executor.resolvePath("States.ArrayGetItem($.arr, 1)", root);
        assertTrue(out.isObject());
        assertEquals(2, out.path("k").asInt());
    }

    @Test
    void arrayGetItemIndexEqualToLengthIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"arr\":[1,2,3]}"), "States.ArrayGetItem($.arr, 3)");
    }

    @Test
    void arrayGetItemNegativeIndexIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"arr\":[1,2,3]}"), "States.ArrayGetItem($.arr, -1)");
    }

    @Test
    void arrayGetItemNonIntegerIndexIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"arr\":[1,2,3]}"), "States.ArrayGetItem($.arr, 1.5)");
    }

    @Test
    void arrayGetItemHugeIntegerIndexIsIntrinsicFailure() throws Exception {
        // 18446744073709551616 is a BigIntegerNode: isIntegralNumber() is true but canConvertToInt()
        // is false, and asInt() would silently wrap to 0 and return element 1. Must fail instead.
        JsonNode root = mapper.readTree("{\"arr\":[1,2,3],\"i\":18446744073709551616}");
        assertIntrinsicFailure(root, "States.ArrayGetItem($.arr, $.i)");
    }

    @Test
    void arrayGetItemNonArrayFirstArgumentIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"s\":\"notarray\"}"), "States.ArrayGetItem($.s, 0)");
    }

    @Test
    void arrayGetItemWrongArgumentCountIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"arr\":[1,2,3]}"), "States.ArrayGetItem($.arr)");
    }

    // ---------- E. States.Hash ----------

    @Test
    void hashSha256ProducesLowercaseHexDigest() throws Exception {
        JsonNode root = mapper.readTree("{\"data\":\"input data\"}");
        assertEquals("b4a697a057313163aee33cd8d40c66e9f0f177e00cac2de32475ffff6169c3e3",
                executor.resolvePath("States.Hash($.data, 'SHA-256')", root).asText());
    }

    @Test
    void hashSha1Digest() throws Exception {
        // AWS's doc example prints a 39-char typo (missing a digit); this is the true SHA-1 digest.
        JsonNode root = mapper.readTree("{\"data\":\"input data\"}");
        assertEquals("aaff4a450a104cd177d28d18d74485e8cae074b7",
                executor.resolvePath("States.Hash($.data, 'SHA-1')", root).asText());
    }

    @Test
    void hashMd5Sha384Sha512Digests() throws Exception {
        JsonNode root = mapper.readTree("{\"data\":\"input data\"}");
        assertEquals("812f45842bc6d66ee14572ce20db8e86",
                executor.resolvePath("States.Hash($.data, 'MD5')", root).asText());
        assertEquals("d28a7d5cf25a74f11a50a18452b75e04bb3d70c9dd0510d6123aa008c756511b87525bdc835ebb27e1fb9e9374a15562",
                executor.resolvePath("States.Hash($.data, 'SHA-384')", root).asText());
        assertEquals("6ce4adb348546d4f449c4d25aad9a7c9cb711d9e91982d3f0b29ca2f3f47d4ce2deba23bf2954f0f1d593fc50283731a533d30d425402d4f91316d871303aac4",
                executor.resolvePath("States.Hash($.data, 'SHA-512')", root).asText());
    }

    @Test
    void hashUnknownAlgorithmWithoutHyphenIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"data\":\"input data\"}"),
                "States.Hash($.data, 'SHA256')");
    }

    @Test
    void hashLowercaseAlgorithmIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"data\":\"input data\"}"),
                "States.Hash($.data, 'sha-256')");
    }

    @Test
    void hashOver10000CharsIsIntrinsicFailure() {
        ObjectNode root = mapper.createObjectNode();
        root.put("data", "a".repeat(10_001));
        assertIntrinsicFailure(root, "States.Hash($.data, 'SHA-256')");
    }

    @Test
    void hashNonStringDataIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"n\":5}"), "States.Hash($.n, 'SHA-256')");
    }

    @Test
    void hashWrongArgumentCountIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"data\":\"x\"}"), "States.Hash($.data)");
    }

    // ---------- I. Code-review hardening: leading-zero hex, plan-promised omissions, trailing comma ----------

    @Test
    void hashPreservesLeadingZeroNibbles() throws Exception {
        // MD5("v787") begins with two zero nibbles; a new BigInteger(1, digest).toString(16)
        // implementation would drop them and return 30 hex chars instead of the fixed-width 32.
        JsonNode root = mapper.readTree("{\"data\":\"v787\"}");
        assertEquals("0003ec0d0c76934ba8a8b39809c825b3",
                executor.resolvePath("States.Hash($.data, 'MD5')", root).asText());
    }

    @Test
    void base64DecodeNonStringArgumentIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"n\":5}"), "States.Base64Decode($.n)");
    }

    @Test
    void stringSplitOfEmptyStringYieldsEmptyArray() throws Exception {
        JsonNode out = executor.resolvePath("States.StringSplit($.s, ',')", mapper.readTree("{\"s\":\"\"}"));
        assertTrue(out.isArray());
        assertEquals(0, out.size());
    }

    @Test
    void arrayGetItemStringIndexIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"arr\":[1,2,3],\"i\":\"1\"}"),
                "States.ArrayGetItem($.arr, $.i)");
    }

    @Test
    void hashAtExactly10000CharsSucceeds() {
        ObjectNode root = mapper.createObjectNode();
        root.put("data", "a".repeat(10_000));
        assertEquals(64, executor.resolvePath("States.Hash($.data, 'SHA-256')", root).asText().length());
    }

    @Test
    void hashNonStringAlgorithmIsIntrinsicFailure() throws Exception {
        assertIntrinsicFailure(mapper.readTree("{\"data\":\"x\",\"algo\":5}"),
                "States.Hash($.data, $.algo)");
    }

    @Test
    void trailingCommaIsIntrinsicFailure() throws Exception {
        // The trailing-comma guard is load-bearing: splitIntrinsicArgs drops a trailing empty token,
        // so an arity check alone would not reject a stray comma.
        assertIntrinsicFailure(mapper.readTree("{\"a\":\"x\"}"), "States.Base64Encode($.a,)");
        assertIntrinsicFailure(mapper.readTree("{\"s\":\"a,b\"}"), "States.StringSplit($.s, ',',)");
    }

    // ---------- F. Parameters-path wiring (resolveParameters -> resolvePath -> evaluateIntrinsic) ----------

    @Test
    void intrinsicResolvesInsidePassStateParameters() throws Exception {
        JsonNode params = mapper.readTree("{\"encoded.$\":\"States.Base64Encode($.data)\"}");
        JsonNode input = mapper.readTree("{\"data\":\"Data to encode\"}");
        JsonNode out = executor.resolveParameters(params, input, null);
        assertEquals("RGF0YSB0byBlbmNvZGU=", out.path("encoded").asText());
    }

    @Test
    void newAndExistingIntrinsicsResolveTogetherInParameters() throws Exception {
        JsonNode params = mapper.readTree(
                "{\"item.$\":\"States.ArrayGetItem($.arr, 1)\",\"len.$\":\"States.ArrayLength($.arr)\"}");
        JsonNode input = mapper.readTree("{\"arr\":[10,20,30]}");
        JsonNode out = executor.resolveParameters(params, input, null);
        assertEquals(20, out.path("item").asInt());
        assertEquals(3, out.path("len").asInt());
    }

    // ---------- G. Execution-level catchability (full doExecute loop via executeSync) ----------

    @Test
    void intrinsicFailureIsCatchableEndToEnd() {
        // A States.IntrinsicFailure raised inside a Pass state's Parameters must be catchable; a
        // reverted implementation raises the uncatchable States.Runtime and this state machine FAILs.
        Execution exec = run("""
                {
                  "StartAt": "Try",
                  "States": {
                    "Try": {
                      "Type": "Pass",
                      "Parameters": {"decoded.$": "States.Base64Decode($.bad)"},
                      "Next": "Unexpected",
                      "Catch": [{"ErrorEquals": ["States.IntrinsicFailure"], "Next": "Recover"}]
                    },
                    "Unexpected": {"Type": "Fail", "Cause": "intrinsic unexpectedly succeeded"},
                    "Recover": {"Type": "Pass", "End": true}
                  }
                }
                """, "{\"bad\":\"not base64!!!\"}");
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
                      "Parameters": {"decoded.$": "States.Base64Decode($.bad)"},
                      "End": true
                    }
                  }
                }
                """, "{\"bad\":\"not base64!!!\"}");
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

    // ---------- H. Regression: the nine pre-existing intrinsics still resolve ----------

    @Test
    void existingIntrinsicsStillResolve() throws Exception {
        assertEquals(1, executor.resolvePath("States.StringToJson($.s)",
                mapper.readTree("{\"s\":\"{\\\"a\\\":1}\"}")).path("a").asInt());
        assertEquals("{\"a\":1}", executor.resolvePath("States.JsonToString($.o)",
                mapper.readTree("{\"o\":{\"a\":1}}")).asText());
        assertEquals("Hello world", executor.resolvePath("States.Format('Hello {}', $.n)",
                mapper.readTree("{\"n\":\"world\"}")).asText());
        JsonNode arr = executor.resolvePath("States.Array(1, $.n)", mapper.readTree("{\"n\":2}"));
        assertEquals(2, arr.size());
        assertEquals(1, arr.get(0).asInt());
        assertEquals(2, arr.get(1).asInt());
        assertEquals(3, executor.resolvePath("States.ArrayLength($.arr)",
                mapper.readTree("{\"arr\":[1,2,3]}")).asInt());
        assertEquals(5, executor.resolvePath("States.MathAdd($.a, $.b)",
                mapper.readTree("{\"a\":2,\"b\":3}")).asLong());
        assertTrue(executor.resolvePath("States.ArrayContains($.arr, $.v)",
                mapper.readTree("{\"arr\":[1,2,3],\"v\":2}")).asBoolean());
        assertTrue(executor.resolvePath("States.UUID()", obj()).asText()
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
        JsonNode merged = executor.resolvePath("States.JsonMerge($.a, $.b, false)",
                mapper.readTree("{\"a\":{\"x\":1},\"b\":{\"y\":2}}"));
        assertEquals(1, merged.path("x").asInt());
        assertEquals(2, merged.path("y").asInt());
    }
}
