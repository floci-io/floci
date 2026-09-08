package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@code ".$"} payload template field (Parameters, ResultSelector, ItemSelector) whose JSONPath
 * does not resolve against the effective input fails the state with {@code States.Runtime},
 * matching real AWS and Step Functions Local 2.0.0, instead of silently resolving to null.
 * Fixes issue #2521.
 *
 * <p>AWS keeps one narrow leniency here: an out-of-range array index still resolves to null and
 * the execution keeps running (see {@link AslExecutorIntrinsicMissingArgumentTest}, which pins
 * the same path failing when passed to a {@code States.*} intrinsic instead).
 */
class AslExecutorUnresolvableJsonPathTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AslExecutor newExecutor() {
        return new AslExecutor(null, null, null, null, null, null, null, null,
                null, null, null, null, null, mapper, null, null, null, null, null);
    }

    @Test
    void unresolvableNestedPathFailsWithTheCauseAwsWrites() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"missing.$\":\"$.nope.deep\"}", "{\"other\":1}"));

        assertEquals("States.Runtime", failure.error);
        assertEquals("The JSONPath '$.nope.deep' specified for the field 'missing.$' could not "
                + "be found in the input '{\"other\":1}'", failure.cause);
    }

    @Test
    void unresolvableTopLevelPathFails() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"missing.$\":\"$.nope\"}", "{\"other\":1}"));

        assertEquals("The JSONPath '$.nope' specified for the field 'missing.$' could not be "
                + "found in the input '{\"other\":1}'", failure.cause);
    }

    /** A field that resolves fine must be unaffected. */
    @Test
    void resolvablePathStillSucceeds() throws Exception {
        var resolved = resolve("{\"picked.$\":\"$.other\"}", "{\"other\":1}");

        assertEquals(1, resolved.path("picked").asInt());
    }

    /**
     * An out-of-range array index is the one place AWS keeps the old null-and-succeed behavior;
     * verified against real AWS in us-east-1 (see the sibling comment in
     * {@link AslExecutorIntrinsicMissingArgumentTest#indexPastTheEndOfAnArrayFails}).
     */
    @Test
    void outOfRangeArrayIndexStillResolvesToNull() throws Exception {
        var resolved = resolve("{\"idx.$\":\"$.items[5]\"}", "{\"items\":[1,2]}");

        assertEquals("null", resolved.path("idx").toString());
    }

    /**
     * An all-digit index too large to fit in an {@code int} is still an out-of-range array access,
     * not a parse failure, so it must resolve to null the same as an ordinary out-of-range index.
     */
    @Test
    void indexBeyondIntRangeStillResolvesToNull() throws Exception {
        var resolved = resolve("{\"idx.$\":\"$.items[99999999999999999999]\"}", "{\"items\":[1,2]}");

        assertEquals("null", resolved.path("idx").toString());
    }

    /** An explicit null value is present, not missing, so it must not fail. */
    @Test
    void explicitNullValueStillSucceeds() throws Exception {
        var resolved = resolve("{\"v.$\":\"$.nul\"}", "{\"nul\":null}");

        assertEquals("null", resolved.path("v").toString());
    }

    /** A miss at any nesting depth inside the template fails, not only at the top level. */
    @Test
    void unresolvablePathFailsAtAnyNestingDepth() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"outer\":{\"missing.$\":\"$.a.b.c\"}}", "{\"a\":{\"x\":1}}"));

        assertEquals("The JSONPath '$.a.b.c' specified for the field 'missing.$' could not be "
                + "found in the input '{\"a\":{\"x\":1}}'", failure.cause);
    }

    /** ResultSelector and ItemSelector run through the same resolver, so they fail the same way. */
    @Test
    void resultSelectorFailsOnAnUnresolvablePath() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"missing.$\":\"$.nope\"}", "[{\"other\":1}]"));

        assertEquals("The JSONPath '$.nope' specified for the field 'missing.$' could not be "
                + "found in the input '[{\"other\":1}]'", failure.cause);
    }

    /** Indexing through a value that is not an array or object fails, matching AWS. */
    @Test
    void navigatingThroughANonContainerValueFails() throws Exception {
        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> resolve("{\"v.$\":\"$.other.x\"}", "{\"other\":1}"));

        assertEquals("States.Runtime", failure.error);
        assertEquals("The JSONPath '$.other.x' specified for the field 'v.$' could not be found "
                + "in the input '{\"other\":1}'", failure.cause);
    }

    /** A wildcard projection matching nothing is an empty array, not a failure. */
    @Test
    void wildcardMatchingNothingStaysAnEmptyArray() throws Exception {
        var resolved = resolve("{\"names.$\":\"$.items[*].name\"}", "{\"items\":[1,2]}");

        assertEquals(0, resolved.path("names").size());
    }

    /**
     * An unresolvable {@code $$.} Context Object reference fails too, and names the Context
     * Object as the input, since that is what the path was actually resolved against.
     */
    @Test
    void unresolvableContextReferenceFailsAndNamesTheContextObject() throws Exception {
        var context = mapper.readTree("{\"State\":{\"Name\":\"P\"}}");
        var template = mapper.readTree("{\"missing.$\":\"$$.Nope.Deep\"}");

        var failure = assertThrows(AslExecutor.FailStateException.class,
                () -> newExecutor().resolveParameters(template, mapper.readTree("{\"other\":1}"), context));

        assertEquals("States.Runtime", failure.error);
        assertEquals("The JSONPath '$.Nope.Deep' specified for the field 'missing.$' could not "
                + "be found in the input '{\"State\":{\"Name\":\"P\"}}'", failure.cause);
    }

    /** A resolvable Context Object reference is unaffected. */
    @Test
    void resolvableContextReferenceStillSucceeds() throws Exception {
        var context = mapper.readTree("{\"Execution\":{\"Name\":\"exec1\"}}");
        var template = mapper.readTree("{\"name.$\":\"$$.Execution.Name\"}");

        var resolved = newExecutor().resolveParameters(template, mapper.readTree("{}"), context);

        assertEquals("exec1", resolved.path("name").asText());
    }

    private JsonNode resolve(String template, String input) throws Exception {
        return newExecutor().resolveParameters(
                mapper.readTree(template), mapper.readTree(input), mapper.createObjectNode());
    }
}
