package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression + functionality coverage for the {@code States.JsonMerge} intrinsic. AWS Step Functions
 * supports only the shallow merge form {@code States.JsonMerge($.a, $.b, false)} where the second
 * object's top-level fields win on a key conflict; deep-merge ({@code true}) and non-object arguments
 * are rejected. A state machine using this intrinsic previously failed with
 * "Unsupported intrinsic function: States.JsonMerge".
 */
class AslExecutorJsonMergeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = AslExecutorFixture.builder()
                .objectMapper(mapper)
                .customResourceLiveness(null)
                .build();
    }

    @Test
    void shallowMergeSecondObjectWins() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1,\"y\":2},\"b\":{\"y\":9,\"z\":3}}");
        JsonNode out = executor.resolvePath("States.JsonMerge($.a, $.b, false)", root);
        assertTrue(out.isObject());
        assertEquals(1, out.path("x").asInt());
        assertEquals(9, out.path("y").asInt(), "second object's value should win on key conflict");
        assertEquals(3, out.path("z").asInt());
    }

    @Test
    void deepMergeTrueIsRejected() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1},\"b\":{\"y\":2}}");
        assertThrows(RuntimeException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b, true)", root));
    }

    @Test
    void nonObjectArgumentsRejected() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":[1,2],\"b\":{\"y\":2}}");
        assertThrows(RuntimeException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b, false)", root));
    }

    @Test
    void nonObjectArgumentsAreRejectedBeforeTheDeepMergeFlag() throws Exception {
        // Two non-objects passed with deep=true must report the object-type error, not the
        // "shallow merge only" error — AWS validates argument types before the deep-merge flag.
        JsonNode root = mapper.readTree("{\"a\":[1,2],\"b\":[3,4]}");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b, true)", root));
        assertTrue(ex.getMessage().contains("requires two JSON objects"),
                "expected the object-type error to take precedence, got: " + ex.getMessage());
    }

    @Test
    void wrongArgumentCountUsesIntrinsicFailure() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1},\"b\":{\"y\":2}}");
        AslExecutor.FailStateException exception = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b)", root));
        assertEquals("States.IntrinsicFailure", exception.error);
    }

    @Test
    void trailingCommaUsesIntrinsicFailure() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1},\"b\":{\"y\":2}}");
        AslExecutor.FailStateException exception = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b, false,)", root));
        assertEquals("States.IntrinsicFailure", exception.error);
    }

    @Test
    void nonBooleanThirdArgumentRejected() throws Exception {
        // A non-boolean third argument must be rejected, not silently coerced to false (shallow).
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1},\"b\":{\"y\":2},\"n\":3}");
        assertThrows(RuntimeException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b, $.n)", root));
    }
}
