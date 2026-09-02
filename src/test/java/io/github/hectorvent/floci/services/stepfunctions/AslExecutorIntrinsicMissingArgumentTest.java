package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * A {@code $.} argument of a {@code States.*} intrinsic that matches nothing fails with
 * {@code States.Runtime}. Every cause here was captured from real AWS (us-east-1), and Step
 * Functions Local 2.0.0 writes the same text. The cause names the whole expression, and an
 * out-of-range array index that a plain {@code "field.$"} reference resolves to null is a miss
 * here.
 */
class AslExecutorIntrinsicMissingArgumentTest {

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
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper,
                new JsonataEvaluator(mapper),
                mock(Instance.class),
                mock(EmulatorConfig.class),
                null,
                null);
    }

    @Test
    void unresolvableArgumentFailsWithTheCauseAwsWrites() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{}', $.nope)\"}", "{\"other\":1}"));

        assertEquals("States.Runtime", failure.error);
        assertEquals("The function 'States.Format('{}', $.nope)' had the following error: "
                + "The JsonPath argument for the field '$.nope' could not be found in the input "
                + "'{\"other\":1}'", failure.cause);
    }

    /** The expression is echoed as written, spacing included. */
    @Test
    void theCauseEchoesTheExpressionVerbatim() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{}',$.nope)\"}", "{\"other\":1}"));

        assertEquals("The function 'States.Format('{}',$.nope)' had the following error: "
                + "The JsonPath argument for the field '$.nope' could not be found in the input "
                + "'{\"other\":1}'", failure.cause);
    }

    /** A later argument fails alike, and the cause names that argument rather than the first. */
    @Test
    void aLaterArgumentNamesItself() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{} {}', $.other, $.a.b)\"}", "{\"other\":1}"));

        assertEquals("The function 'States.Format('{} {}', $.other, $.a.b)' had the following "
                + "error: The JsonPath argument for the field '$.a.b' could not be found in the "
                + "input '{\"other\":1}'", failure.cause);
    }

    /**
     * A reference path in the format template position is left as written rather than resolved, so
     * it never reaches the argument check. Real AWS and Step Functions Local resolve it and fail
     * here. That is a separate gap from issue #2870 and is tracked by issue #2927.
     */
    @Test
    void aReferencePathAsTheFormatTemplateIsStillTakenLiterally() throws Exception {
        var resolved = resolve("{\"v.$\":\"States.Format($.nope, 1)\"}", "{\"other\":1}");

        assertEquals("\"$.nope\"", resolved.path("v").toString());
    }

    /** The whole expression is named, not the inner function that took the failing argument. */
    @Test
    void nestedIntrinsicNamesTheWholeExpression() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{}', States.Format('{}', $.nope))\"}",
                        "{\"other\":1}"));

        assertEquals("The function 'States.Format('{}', States.Format('{}', $.nope))' had the "
                + "following error: The JsonPath argument for the field '$.nope' could not be "
                + "found in the input '{\"other\":1}'", failure.cause);
    }

    /** Every intrinsic resolves its arguments through the same path, so every one of them fails. */
    @Test
    void otherIntrinsicsFailOnAnUnresolvableArgument() {
        assertIntrinsicFails("States.ArrayLength($.nope)", "$.nope");
        assertIntrinsicFails("States.Array(1, $.nope)", "$.nope");
        assertIntrinsicFails("States.JsonToString($.nope)", "$.nope");
        assertIntrinsicFails("States.StringToJson($.nope)", "$.nope");
        assertIntrinsicFails("States.MathAdd($.nope, 1)", "$.nope");
        assertIntrinsicFails("States.ArrayContains($.nope, 1)", "$.nope");
        assertIntrinsicFails("States.JsonMerge($.nope, $.other, false)", "$.nope");
    }

    /** Only absence fails. A present but explicitly null argument still formats as null. */
    @Test
    void explicitNullArgumentStillFormats() throws Exception {
        var resolved = resolve("{\"v.$\":\"States.Format('{}', $.nul)\"}", "{\"nul\":null}");

        assertEquals("\"null\"", resolved.path("v").toString());
    }

    /** A wildcard projection that matches nothing is an empty array, not an absent argument. */
    @Test
    void wildcardMatchingNothingIsAnEmptyArray() throws Exception {
        var resolved = resolve("{\"v.$\":\"States.ArrayLength($.items[*].nope)\"}", "{\"items\":[1,2]}");

        assertEquals(0, resolved.path("v").asInt());
    }

    /**
     * The two payload template forms really do differ here. A plain {@code "idx.$": "$.items[5]"}
     * resolves to null on AWS and the execution succeeds, while the same path as an intrinsic
     * argument is a miss. Both were run against real AWS in us-east-1.
     */
    @Test
    void indexPastTheEndOfAnArrayFails() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{}', $.items[5])\"}", "{\"items\":[1,2]}"));

        assertEquals("The function 'States.Format('{}', $.items[5])' had the following error: "
                + "The JsonPath argument for the field '$.items[5]' could not be found in the "
                + "input '{\"items\":[1,2]}'", failure.cause);
    }

    /** Reading a field off the absent element fails alike. */
    @Test
    void navigatingPastAnOutOfRangeIndexFails() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{}', $.items[5].x)\"}", "{\"items\":[1]}"));

        assertEquals("The function 'States.Format('{}', $.items[5].x)' had the following error: "
                + "The JsonPath argument for the field '$.items[5].x' could not be found in the "
                + "input '{\"items\":[1]}'", failure.cause);
    }

    /**
     * Indexing something that is not an array fails too. AWS leaks its JSONPath library here and
     * writes {@code Filter: [0] can only be applied to arrays. Current context is: 1} in place of
     * the sentence below. Floci keeps its own wording, so only the failure itself matches.
     */
    @Test
    void indexingANonArrayFails() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{}', $.other[0])\"}", "{\"other\":1}"));

        assertEquals("States.Runtime", failure.error);
        assertEquals("The function 'States.Format('{}', $.other[0])' had the following error: "
                + "The JsonPath argument for the field '$.other[0]' could not be found in the "
                + "input '{\"other\":1}'", failure.cause);
    }

    /**
     * A {@code $$.} argument reports the path rewritten against the Context Object and names the
     * state input, not the Context Object it searched. Step Functions Local words it this way, and
     * it is the one place the two payload template forms differ: a plain {@code "ctx.$"} reference
     * reports the Context Object.
     */
    @Test
    void unresolvableContextArgumentNamesTheRewrittenPathAndTheStateInput() throws Exception {
        var context = mapper.readTree("{\"State\":{\"Name\":\"P\"}}");
        var template = mapper.readTree("{\"v.$\":\"States.Format('{}', $$.Nope.Deep)\"}");

        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.resolveParameters(template, mapper.readTree("{\"other\":1}"), context));

        assertEquals("The function 'States.Format('{}', $$.Nope.Deep)' had the following error: "
                + "The JsonPath argument for the field '$.Nope.Deep' could not be found in the "
                + "input '{\"other\":1}'", failure.cause);
    }

    /** A bare $ argument is the whole input, so it never misses. */
    @Test
    void wholeInputArgumentResolves() throws Exception {
        var resolved = resolve("{\"v.$\":\"States.JsonToString($)\"}", "{\"other\":1}");

        assertEquals("{\"other\":1}", resolved.path("v").asText());
    }

    /** ResultSelector and ItemSelector run through the same resolver, so they fail the same way. */
    @Test
    void resultSelectorFailsOnAnUnresolvableArgument() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"States.Format('{}', $[0].nope)\"}", "[{\"other\":1}]"));

        assertEquals("The function 'States.Format('{}', $[0].nope)' had the following error: "
                + "The JsonPath argument for the field '$[0].nope' could not be found in the "
                + "input '[{\"other\":1}]'", failure.cause);
    }

    private void assertIntrinsicFails(String expression, String argument) {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"" + expression + "\"}", "{\"other\":1}"),
                expression);

        assertEquals("States.Runtime", failure.error);
        assertEquals("The function '" + expression + "' had the following error: The JsonPath "
                + "argument for the field '" + argument + "' could not be found in the input "
                + "'{\"other\":1}'", failure.cause);
    }

    private JsonNode resolve(String template, String input) throws Exception {
        return executor.resolveParameters(
                mapper.readTree(template), mapper.readTree(input), mapper.createObjectNode());
    }
}
