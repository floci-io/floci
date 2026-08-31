package io.github.hectorvent.floci.services.stepfunctions;

import com.dashjoin.jsonata.Functions;
import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Utils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.hectorvent.floci.services.stepfunctions.AslExecutor.FailStateException;

import static com.dashjoin.jsonata.Jsonata.jsonata;

/**
 * Evaluates JSONata expressions for Step Functions.
 * Handles {% expression %} delimiters, $states variable binding,
 * and recursive template resolution for Arguments/Output fields.
 *
 * Only pure expressions are evaluated: "{% $states.input.name %}" → any type.
 * Strings that are not a single {% %} expression pass through unchanged
 * (AWS does not support string interpolation with multiple {% %} blocks).
 */
@ApplicationScoped
public class JsonataEvaluator {

    private static final Set<String> HASH_ALGORITHMS = Set.of("MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512");

    /**
     * The largest magnitude a {@code double} can still hold as an exact integer count of longs.
     * Past it AWS switches from an integer to exponent notation, and so does {@link #toJsonataValue}.
     */
    private static final double LARGEST_EXACT_LONG_AS_DOUBLE = 9.223372036854776E18;

    /**
     * The magnitude at which AWS stops writing a whole number in full and switches to exponent
     * notation, on both signs: {@code $string(1e20)} is {@code "100000000000000000000"} and
     * {@code $string(1e21)} is {@code "1e+21"}.
     */
    private static final double SMALLEST_EXPONENT_NOTATION = 1e21;

    /** What dashjoin prefixes to an error whose code is not in its message catalog. */
    private static final String UNKNOWN_CODE_PREFIX = "JSonataException ";

    /** Stand-ins for the two values dashjoin substitutes; no catalog template contains them. */
    private static final String CURRENT_MARKER = "{{floci-current}}";
    private static final String EXPECTED_MARKER = "{{floci-expected}}";

    /** The name jsonata-js gives each element type a function signature can ask an array for. */
    private static final Map<Object, String> ARRAY_ELEMENT_TYPES = Map.of(
            "a", "arrays", "b", "booleans", "f", "functions",
            "n", "numbers", "o", "objects", "s", "strings");

    /**
     * How long one expression may evaluate before the state fails with
     * {@code States.QueryEvaluationError}. AWS bounds evaluation and Floci did not, so an
     * expression with no base case pinned a core and left the execution {@code RUNNING} for ever.
     * JSONata optimises the tail call, so such an expression loops instead of overflowing the
     * stack: no {@code Error} is thrown and only a clock can end it.
     *
     * <p>Five seconds is the dashjoin library's own default and roughly fifty times the slowest
     * evaluation of a payload AWS itself accepts: AWS refuses a sequence of 900,000 elements on
     * its memory limit, and summing one that size here takes about 100 ms. Deliberately generous,
     * because a bound that fails a working state machine costs more than one that lets a runaway
     * expression burn a core for five seconds.
     */
    static final long EVALUATION_TIMEOUT_MILLIS = 5_000;

    /**
     * How deep evaluation may nest before the state fails, which is the ceiling AWS itself holds:
     * AWS accepts an evaluation at depth 100 and refuses one at 101, whatever the nesting is made
     * of. An addition chain nests one level per term, parentheses, brackets and the chain operator
     * nest one level more than their count, and a non-tail-recursive {@code $f(n)} nests
     * {@code 3n+5}.
     *
     * <p>The bound trips before the JVM stack does: on a 1 MB thread stack it raises a JSONata
     * error, which the state fails on, where 1000 or more overflows the stack instead.
     */
    static final int MAX_EVALUATION_DEPTH = 100;

    /** What one number, boolean or null of a value counts as against {@link #MAX_EXPRESSION_BYTES}. */
    private static final int BYTES_PER_SCALAR = 8;

    /**
     * How much memory one value an expression builds may hold before the state fails, 6,990,256
     * bytes, which is the bound AWS itself holds: AWS accepts {@code [1..873782]} and refuses one
     * element more, and it accepts a string of 2^22 characters and refuses one of 2^23. Both of
     * those refusals land just past this bound when a number counts as {@link #BYTES_PER_SCALAR}
     * bytes and a character as one.
     *
     * <p>What is bounded is the largest single value an evaluation step produces, not the sum of
     * everything alive at once. Checking it walks that value and stops as soon as the walk passes
     * the bound, so one check costs at most 873,783 steps, and a lazy range is counted from its
     * length without being materialised.
     */
    static final long MAX_EXPRESSION_BYTES = 873_782L * BYTES_PER_SCALAR;

    /**
     * The largest number of elements a single {@code $range} call may produce before floci
     * refuses it, the bound AWS itself holds specifically for {@code $range}: AWS accepts
     * {@code $range(1, 360145, 1)}, returning all 360,145 elements, and refuses one element more
     * with the same memory refusal as {@link #MAX_EXPRESSION_BYTES}. That general bound is looser
     * (873,782 scalars) and is checked only on the value an evaluation step has already built; a
     * lazy literal range such as {@code [1..873782]} is exempt from it on AWS, and stays exempt
     * here. {@code $range} gets a bound of its own because it is the one construct that actually
     * allocates a list of that size, so the general check would see the memory only after the
     * allocation already happened.
     */
    static final int MAX_RANGE_ELEMENTS = 360_145;

    /**
     * AWS's own sentence for an expression that exceeded its evaluation memory limit, shared by
     * the bound on a built value ({@link #boundHeldMemory}) and the pre-allocation cap on
     * {@code $range} ({@link #range}).
     */
    private static final String MEMORY_LIMIT_EXCEEDED =
            "Expression evaluation memory limit exceeded: Check for excessive memory usage";

    private final ObjectMapper objectMapper;
    private final ObjectReader strictJsonReader;
    private final Map<String, Jsonata.JFunction> stepFunctionsExtensions;
    private final long evaluationTimeoutMillis;
    private final int maxEvaluationDepth;

    @Inject
    public JsonataEvaluator(ObjectMapper objectMapper) {
        this(objectMapper, EVALUATION_TIMEOUT_MILLIS, MAX_EVALUATION_DEPTH);
    }

    /**
     * Bounds evaluation at values other than the shipped ones, so a test can trip the time bound
     * without waiting {@link #EVALUATION_TIMEOUT_MILLIS} for it.
     */
    JsonataEvaluator(ObjectMapper objectMapper, long evaluationTimeoutMillis, int maxEvaluationDepth) {
        this.evaluationTimeoutMillis = evaluationTimeoutMillis;
        this.maxEvaluationDepth = maxEvaluationDepth;
        this.objectMapper = objectMapper;
        // AWS rejects what a lenient parser accepts: a second document after the first, a repeated
        // key, an empty string. All three come back as D3137 there, so $parse reads through this
        // reader rather than the shared mapper, whose settings belong to the wire protocol.
        this.strictJsonReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.stepFunctionsExtensions = buildStepFunctionsExtensions();
    }

    /**
     * Check if the string is a JSONata expression (starts with {% and ends with %}).
     */
    static boolean isExpression(String value) {
        return value != null && value.startsWith("{%") && value.endsWith("%}");
    }

    /**
     * Strip {% %} delimiters and return the inner expression, trimmed.
     */
    static String unwrap(String value) {
        return value.substring(2, value.length() - 2).trim();
    }

    /**
     * Evaluate a single JSONata expression string with $states bound.
     * The expression may or may not have {% %} delimiters.
     *
     * <p><b>Singleton sequence reduction:</b>
     * Both real AWS Step Functions and the JSONata spec apply singleton sequence reduction:
     * a 1-element sequence produced by an object-mapping expression (e.g.
     * {@code $states.result.Items.{"id": id}}) is reduced to the single object rather than
     * remaining a 1-element array. Floci's behavior matches AWS.
     *
     * <p>To force an array regardless of element count, wrap in {@code [...]}, e.g.
     * {@code [$states.result.Items.{"id": id}]}.
     */
    JsonNode evaluate(String expression, JsonNode statesVar) {
        return evaluate(expression, statesVar, null);
    }

    JsonNode evaluate(String expression, JsonNode statesVar, JsonNode variables) {
        String expr = isExpression(expression) ? unwrap(expression) : expression;
        ExpressionNesting nesting = new ExpressionNesting();
        try {
            Jsonata jsonataExpr = jsonata(expr);
            // Off, the library keeps JSONata's null marker in the result instead of flattening it
            // into a Java null. On, an expression that evaluated to JSON null and one that returned
            // nothing both arrive here as a Java null, and AWS keeps the first while dropping the
            // second.
            jsonataExpr.setOutputConvertNulls(false);
            Jsonata.Frame frame = jsonataExpr.createFrame();
            // Without this the library evaluates unbounded: a recursive expression with no base
            // case loops for ever and the execution never leaves RUNNING. The bounds raise a
            // JSONata error, which the catch below turns into States.QueryEvaluationError.
            frame.setRuntimeBounds(evaluationTimeoutMillis, maxEvaluationDepth);
            nesting.trackOn(frame);
            boundHeldMemory(frame);
            stepFunctionsExtensions.forEach(frame::bind);
            // AWS disables $eval, and the library ships it. An unbound name called as a function is
            // the library's own T1006, which is the code AWS answers. $parse is the replacement.
            frame.bind("eval", (Object) null);
            // Workflow variables (the Assign field) are referenced as top-level $name in AWS's
            // JSONata dialect, e.g. $CheckpointCount. They are bound alongside $states, never
            // inside it: AWS reserves $states for input/result/errorOutput/context only. $states is
            // bound last so that reservation holds even if a definition assigns a variable named
            // "states", which AWS rejects but Floci does not yet validate.
            if (variables != null && variables.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = variables.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    frame.bind(entry.getKey(), toObject(entry.getValue()));
                }
            }
            frame.bind("states", toObject(statesVar));
            Object result = jsonataExpr.evaluate(null, frame);
            return toJsonNode(result);
        } catch (Exception e) {
            JsonNode nonFinite = nesting.isTheWholeExpression() ? nonFiniteRefusal(e) : null;
            if (nonFinite != null) {
                return nonFinite;
            }
            throw queryEvaluationFailure(e);
        } catch (StackOverflowError | OutOfMemoryError e) {
            // These two are the resource an expression itself spends: nesting deep enough to
            // exhaust the parser's stack, and asking for a value larger than the JVM can hold.
            // AWS bounds both and fails the state with States.QueryEvaluationError, so the state's
            // own Retry and Catch (and a Catch on States.ALL) still see it. Every other Error says
            // the runtime is broken rather than this expression, and it keeps going to
            // AslExecutor's terminal catch (Error e), which fails the execution as States.Runtime
            // and rethrows. The message is often null here, so toString() carries the diagnosis,
            // the same way that terminal catch already renders it. QueryEvaluationFailure (rather
            // than a bare FailStateException) is what lets evaluateField prepend AWS's sentence,
            // colon-separated since this cause carries no JSONata code.
            throw new QueryEvaluationFailure(e.toString(), ": ");
        }
    }

    /**
     * How deeply nested the evaluation step running is, the expression's own root node being 1.
     * A step that fails never leaves, so once an exception has unwound the count is the depth of
     * the step that raised it.
     */
    private static final class ExpressionNesting {

        private int depth;

        /**
         * Counts on the same pair of callbacks the library installs its bounds through, keeping
         * the library's own and running after it.
         */
        void trackOn(Jsonata.Frame frame) {
            Jsonata.EntryCallback boundsOnEntry = (Jsonata.EntryCallback) frame.lookup("__evaluate_entry");
            frame.setEvaluateEntryCallback((expression, input, environment) -> {
                boundsOnEntry.callback(expression, input, environment);
                depth++;
            });
            afterEachEvaluationStep(frame, (expression, input, environment, result) -> depth--);
        }

        /** Whether the step that failed is the expression itself rather than a step inside it. */
        boolean isTheWholeExpression() {
            return depth == 1;
        }
    }

    /**
     * The value behind a D1001, which the library raises rather than carrying a non-finite number:
     * {@code 1/0} and {@code 1e308*10} both fail there where AWS answers {@code "Infinity"}. The
     * exception is the only place the library leaves the value, and it carries no way back to where
     * the arithmetic ran, so this recovers the value only when the step that raised it is the whole
     * expression. {@code [1/0]} fails the state instead of answering AWS's {@code ["Infinity"]},
     * which is the conservative half of the divergence: a state that fails is one a Catch fires on.
     */
    private JsonNode nonFiniteRefusal(Exception e) {
        if (e instanceof JException jsonataError && "D1001".equals(jsonataError.getError())
                && jsonataError.getCurrent() instanceof Number value) {
            return fromJsonataValue(value.doubleValue());
        }
        return null;
    }

    /**
     * Adds the memory bound to the time and depth bounds the library installs. The exit callback
     * carries the value every evaluation step produces, which is what the bound is on.
     */
    private static void boundHeldMemory(Jsonata.Frame frame) {
        afterEachEvaluationStep(frame, (expression, input, environment, result) -> {
            if (heldBytes(result, 0) > MAX_EXPRESSION_BYTES) {
                throw new JException(MEMORY_LIMIT_EXCEEDED, -1);
            }
        });
    }

    /**
     * Runs {@code addition} after every evaluation step, keeping whatever the frame already runs
     * there. The library installs its time and depth bounds through that same callback, so an
     * addition extends them rather than replacing them.
     */
    private static void afterEachEvaluationStep(Jsonata.Frame frame, Jsonata.ExitCallback addition) {
        Jsonata.ExitCallback installed = (Jsonata.ExitCallback) frame.lookup("__evaluate_exit");
        frame.setEvaluateExitCallback((expression, input, environment, result) -> {
            installed.callback(expression, input, environment, result);
            addition.callback(expression, input, environment, result);
        });
    }

    /**
     * What {@code value} adds to the {@code held} bytes already counted, stopping as soon as the
     * total passes {@link #MAX_EXPRESSION_BYTES} so a value far over the bound costs no more to
     * refuse than one just over it. A range is counted from its length: the library materialises it
     * lazily, and AWS refuses it on the elements it stands for rather than on what it has built.
     */
    private static long heldBytes(Object value, long held) {
        if (held > MAX_EXPRESSION_BYTES) {
            return held;
        }
        if (value instanceof String text) {
            return held + text.length();
        }
        if (value instanceof Utils.RangeList range) {
            return held + (long) range.size() * BYTES_PER_SCALAR;
        }
        if (value instanceof List<?> list) {
            return elementsHeldBytes(list, held);
        }
        if (value instanceof Map<?, ?> map) {
            return elementsHeldBytes(map.entrySet(), held);
        }
        if (value instanceof Map.Entry<?, ?> entry) {
            return heldBytes(entry.getValue(), heldBytes(entry.getKey(), held));
        }
        return held + BYTES_PER_SCALAR;
    }

    private static long elementsHeldBytes(Iterable<?> elements, long held) {
        long total = held;
        for (Object element : elements) {
            total = heldBytes(element, total);
            if (total > MAX_EXPRESSION_BYTES) {
                return total;
            }
        }
        return total;
    }

    /**
     * The state failure AWS reports for an expression that threw. Its cause is the error code and
     * the message jsonata-js renders for it, as in {@code T0412: Argument 1 of function "sum" must
     * be an array of "numbers"}; for the handful of failures with no JSONata code of their own (a
     * timeout, a stack-depth bound) it is the bare message the exception came with.
     *
     * <p>The dashjoin port renders the coded message itself, and three things go wrong on the way.
     * Its copy of the catalog has the word "function" replaced by "Object" throughout; its
     * substituter fills the first two placeholders of a template and leaves a third one standing;
     * and it quotes every value it inserts, where jsonata-js JSON-renders it. So the message is
     * composed here from the code and the values {@link JException} carries instead.
     */
    private QueryEvaluationFailure queryEvaluationFailure(Exception e) {
        if (!(e instanceof JException jsonataError) || jsonataError.getError() == null) {
            return new QueryEvaluationFailure(e.getMessage(), ": ");
        }
        String code = jsonataError.getError();
        Object current = jsonataError.getCurrent();
        Object expected = jsonataError.getExpected();
        // Both this class's own functions and a few library call sites throw with the whole
        // message in the code slot. The catalog lookup then misses and dashjoin prefixes its own
        // class name, which AWS never emits; the message is the code slot itself. Most of these
        // already open with a real code ("D3137: Invalid JSON"), a sentence of its own that joins
        // AWS's with ". "; the two bound messages (a timeout, a stack-depth or memory ceiling) open
        // with English instead and are bare, joining with ": " like any other uncoded message.
        if ((UNKNOWN_CODE_PREFIX + code).equals(jsonataError.getMessage())) {
            String bound = withoutDepthBoundSuffix(code);
            String separator = bound.matches("[A-Z]\\d{4}:.*") ? ". " : ": ";
            return new QueryEvaluationFailure(bound, separator);
        }
        String tail = switch (code) {
            // The only two templates in the catalog with a third placeholder. Neither third value
            // reaches the exception: T0412 carries the offending argument and the element type it
            // wanted but not the argument index nor the function name, and T2009 carries the two
            // compared values but not the operator. Both sentences state what is carried.
            case "T0412" -> "T0412: Argument " + json(current) + " must be an array of "
                    + json(ARRAY_ELEMENT_TYPES.getOrDefault(expected, String.valueOf(expected)));
            case "T2009" -> "T2009: The values " + json(current) + " and " + json(expected)
                    + " either side of the operator must be of the same data type";
            default -> code + ": " + renderTemplate(code, current, expected);
        };
        return new QueryEvaluationFailure(tail, ". ");
    }

    /**
     * The library's stack-depth bound names the depth and the ceiling it hit,
     * {@code ". Depth=101 max=100"}, which AWS's own sentence for the same failure never carries.
     * Every other bare message this method renders has no such suffix, so stripping it here is
     * specific to that one failure without needing to name it.
     */
    private static String withoutDepthBoundSuffix(String message) {
        return message.replaceFirst("\\. Depth=\\d+ max=\\d+$", "");
    }

    /**
     * A States.QueryEvaluationError the expression itself raised, carrying the punctuation that
     * joins its cause to the sentence {@link #evaluateField} puts in front: {@code ". "} after a
     * coded JSONata message, which is a sentence of its own, and {@code ": "} before a bare one.
     */
    private static final class QueryEvaluationFailure extends FailStateException {

        final String sentenceSeparator;

        QueryEvaluationFailure(String cause, String sentenceSeparator) {
            super("States.QueryEvaluationError", cause);
            this.sentenceSeparator = sentenceSeparator;
        }
    }

    /**
     * The catalog template for a code with its values substituted. Rendering it once with markers
     * in the value slots is what keeps the template's own words apart from a value that happens to
     * contain them, so restoring "function" cannot reach into a value. A marker comes back quoted
     * where jsonata-js JSON-renders the value and bare where it inserts it raw.
     */
    private String renderTemplate(String code, Object current, Object expected) {
        return JException.msg(code, -1, CURRENT_MARKER, EXPECTED_MARKER)
                .replace("Object", "function")
                .replace('"' + CURRENT_MARKER + '"', json(current))
                .replace('"' + EXPECTED_MARKER + '"', json(expected))
                .replace(CURRENT_MARKER, String.valueOf(current))
                .replace(EXPECTED_MARKER, String.valueOf(expected));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    /**
     * Evaluate the single expression an ASL field holds, failing the state when it returns nothing
     * or when the expression itself throws. AWS names the expression and the field in the cause
     * either way and a {@code Catch} on States.QueryEvaluationError fires: a Wait whose Seconds
     * returned nothing does not wait zero seconds, it fails, and {@code $abs("x")} in a field fails
     * it with AWS's "threw an error during evaluation" sentence in front of the JSONata message.
     *
     * <p>{@code field} is the field's path relative to the state, as AWS writes it: {@code Seconds},
     * {@code Choices[1]/Condition}, {@code Output/a/b}.
     */
    JsonNode evaluateField(String expression, String field, JsonNode statesVar, JsonNode variables) {
        String expr = isExpression(expression) ? unwrap(expression) : expression;
        JsonNode value;
        try {
            value = evaluate(expression, statesVar, variables);
        } catch (QueryEvaluationFailure failure) {
            throw new FailStateException(failure.error,
                    "The JSONata expression '" + expr + "' specified for the field '" + field
                            + "' threw an error during evaluation" + failure.sentenceSeparator + failure.cause);
        }
        if (value.isMissingNode()) {
            throw new FailStateException("States.QueryEvaluationError",
                    "The JSONata expression '" + expr + "' specified for the field '" + field
                            + "' returned nothing (undefined).");
        }
        return value;
    }

    /**
     * Walk a JSON template (Arguments, Output or Assign), evaluating any {% %} strings found.
     * Non-expression values pass through unchanged.
     *
     * Only pure {% expression %} strings are evaluated (can return any JSON type).
     * All other strings pass through unchanged.
     *
     * <p>{@code field} is the template's own field name, which the walk extends with {@code /key}
     * per object key and {@code [i]} per array index so a failing expression is named the way AWS
     * names it: {@code Output/a/b[0][1]}.
     */
    JsonNode resolveTemplate(JsonNode template, String field, JsonNode statesVar) {
        return resolveTemplate(template, field, statesVar, null);
    }

    JsonNode resolveTemplate(JsonNode template, String field, JsonNode statesVar, JsonNode variables) {
        if (template == null || template.isMissingNode()) {
            return template;
        }
        if (template.isTextual()) {
            String text = template.asText();
            if (isExpression(text)) {
                return evaluateField(text, field, statesVar, variables);
            }
            return template;
        }
        if (template.isObject()) {
            ObjectNode resolved = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                resolved.set(entry.getKey(),
                        resolveTemplate(entry.getValue(), field + "/" + entry.getKey(), statesVar, variables));
            }
            return resolved;
        }
        if (template.isArray()) {
            ArrayNode resolved = objectMapper.createArrayNode();
            for (int i = 0; i < template.size(); i++) {
                resolved.add(resolveTemplate(template.get(i), field + "[" + i + "]", statesVar, variables));
            }
            return resolved;
        }
        // Primitives (number, boolean, null) pass through
        return template;
    }

    /**
     * Binds $states and the workflow variables as JSONata values, so a JSON null inside them stays
     * a null the expression can see: $exists() on it is true and $type() on it is "null", as on
     * AWS. Only an absent node is undefined.
     */
    private static Object toObject(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        return toJsonataValue(node);
    }

    /**
     * The evaluation result. A Java null is the expression returning nothing, which
     * {@link #evaluateField} fails the state on; the JSONata null marker is a JSON null, which is a
     * value like any other. Nested inside an object or an array there is no undefined, so every
     * null there is a JSON null.
     */
    private JsonNode toJsonNode(Object value) {
        return value == null ? MissingNode.getInstance() : fromJsonataValue(value);
    }

    private JsonNode fromJsonataValue(Object value) {
        if (value == null || value == Jsonata.NULL_VALUE) {
            return NullNode.getInstance();
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = objectMapper.createObjectNode();
            map.forEach((key, element) -> object.set(String.valueOf(key), fromJsonataValue(element)));
            return object;
        }
        if (value instanceof List<?> list) {
            ArrayNode array = objectMapper.createArrayNode();
            list.forEach(element -> array.add(fromJsonataValue(element)));
            return array;
        }
        // JSON has no literal for a non-finite number, so AWS writes each one as the string
        // JavaScript names it by: 1/0 comes back as "Infinity" and $parseInteger("abc","0") as
        // "NaN".
        if (value instanceof Double number && !Double.isFinite(number)) {
            return TextNode.valueOf(nonFiniteName(number));
        }
        return objectMapper.valueToTree(value);
    }

    private static String nonFiniteName(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        return value > 0 ? "Infinity" : "-Infinity";
    }

    /**
     * The JSONata functions Floci binds on the evaluation frame. Six of them are what the Step
     * Functions dialect adds on top of the JSONata language, per
     * https://docs.aws.amazon.com/step-functions/latest/dg/transforming-data.html: five the
     * dashjoin library does not have at all, and $random it has with the wrong arity, so the
     * binding shadows it to accept AWS's optional seed. The other three are JSONata functions the
     * library has and answers differently from AWS: $string writes a number in another notation,
     * $parseInteger answers nothing where AWS answers NaN, and $formatNumber accepts picture
     * strings AWS refuses.
     *
     * <p>Each of the six declares one optional slot more than the arity AWS documents. The library
     * pads a short call with Java nulls up to the declared arity and refuses a call longer than
     * it, so the extra slot is what lets an over-long call reach this code and fail with AWS's own
     * message instead of the library's. $string keeps the library's own signature instead, so
     * every arity and type error it already raised stays word for word what it was.
     *
     * <p>A signature is also what makes a function usable as a value: dashjoin dereferences it
     * unconditionally in $map, $filter, $sift, $each, $single and $reduce, and a null one throws a
     * raw NullPointerException there.
     */
    private Map<String, Jsonata.JFunction> buildStepFunctionsExtensions() {
        return Map.of(
                "parse", new Jsonata.JFunction((input, arguments) -> parse(arguments), "<x?x?:x>"),
                "partition", new Jsonata.JFunction((input, arguments) -> partition(arguments), "<x?x?x?:x>"),
                "range", new Jsonata.JFunction((input, arguments) -> range(arguments), "<x?x?x?x?:x>"),
                "hash", new Jsonata.JFunction((input, arguments) -> hash(arguments), "<x?x?x?:x>"),
                "random", new Jsonata.JFunction((input, arguments) -> random(arguments), "<x?x?:x>"),
                "uuid", new Jsonata.JFunction((input, arguments) -> uuid(arguments), "<x?:x>"),
                "string", new Jsonata.JFunction((input, arguments) -> string(arguments), "<x-b?:s>"),
                "parseInteger", new Jsonata.JFunction((input, arguments) -> parseInteger(arguments), "<s-s:n>"),
                "formatNumber", new Jsonata.JFunction((input, arguments) -> formatNumber(arguments), "<n-so?:s>"));
    }

    /**
     * $parseInteger(value, picture): the JSONata function, answering NaN where the picture cannot
     * read the value, which is what AWS answers. The library answers nothing there, and an ASL
     * field that evaluates to nothing fails the state.
     */
    private static Object parseInteger(List<Object> arguments) {
        Object value = argument(arguments, 0);
        if (value == null) {
            return null;
        }
        try {
            Number parsed = Functions.parseInteger((String) value, (String) argument(arguments, 1));
            return parsed == null ? Double.NaN : parsed;
        } catch (ParseException e) {
            throw new JException(e.getMessage(), -1);
        }
    }

    /**
     * $formatNumber(value, picture, options): the JSONata function, with the picture string checked
     * against the rules AWS applies to it first. See {@link FormatNumberPicture}.
     */
    private static Object formatNumber(List<Object> arguments) {
        Object value = argument(arguments, 0);
        if (value == null) {
            return null;
        }
        Map<?, ?> options = (Map<?, ?>) argument(arguments, 2);
        String picture = (String) argument(arguments, 1);
        FormatNumberPicture.validate(picture, options);
        return Functions.formatNumber((Number) value, picture, options);
    }

    /**
     * The argument in a slot, or null when the caller left it out. A higher-order call such as
     * {@code $map(items, $parse)} passes only the arguments it has, so the list can be shorter
     * than the declared arity.
     */
    private static Object argument(List<Object> arguments, int index) {
        return index < arguments.size() ? arguments.get(index) : null;
    }

    /**
     * AWS refuses a call with more arguments than the function takes, naming the first surplus
     * one. The signatures declare that slot so the surplus argument arrives here to be named.
     */
    private static void rejectSurplusArgument(List<Object> arguments, String functionName, int firstSurplusIndex) {
        if (argument(arguments, firstSurplusIndex) != null) {
            throw signatureError(functionName, firstSurplusIndex + 1);
        }
    }

    private static JException signatureError(String functionName, int argumentNumber) {
        return new JException("T0410: Argument " + argumentNumber + " of function \"" + functionName
                + "\" does not match function signature", -1);
    }

    /**
     * AWS rounds a non-integer argument towards zero, so -1.7 becomes -1 and 2.9 becomes 2, which
     * is what a Java cast already does. Flooring instead shifts every negative argument by one.
     */
    private static long towardsZero(Object argument, String functionName, int argumentNumber) {
        if (!(argument instanceof Number number)) {
            throw signatureError(functionName, argumentNumber);
        }
        return (long) number.doubleValue();
    }

    /**
     * $parse(jsonString): deserializes a JSON string, replacing AWS's disabled $eval. A missing
     * argument evaluates to undefined (Java null); a non-string argument or JSON that AWS's
     * parser refuses raises a JSONata error.
     */
    private Object parse(List<Object> arguments) {
        rejectSurplusArgument(arguments, "parse", 1);
        Object jsonArgument = argument(arguments, 0);
        if (jsonArgument == null) {
            return null;
        }
        if (!(jsonArgument instanceof String jsonString)) {
            throw signatureError("parse", 1);
        }
        JsonNode parsed;
        try {
            parsed = strictJsonReader.readTree(jsonString);
        } catch (JsonProcessingException e) {
            throw new JException("D3137: Invalid JSON", -1);
        }
        // An empty or blank string parses to a missing node rather than raising. AWS calls it
        // invalid JSON, and letting it through would turn an empty upstream body into "".
        if (parsed == null || parsed.isMissingNode()) {
            throw new JException("D3137: Invalid JSON", -1);
        }
        return toJsonataValue(parsed);
    }

    /**
     * Converts a parsed JSON tree into plain Java values that JSONata can navigate as a path,
     * using the JSONata null marker (not Java null, which JSONata reads as undefined) for JSON
     * null so $exists() on a parsed null stays true, as it does on AWS.
     */
    private static Object toJsonataValue(JsonNode node) {
        if (node.isNull()) {
            return Jsonata.NULL_VALUE;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), toJsonataValue(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(toJsonataValue(element)));
            return list;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return toJsonataNumber(node);
        }
        return node.asText();
    }

    /**
     * AWS keeps a JSON integer exact while it fits in a long and switches to a double past that,
     * so 9223372036854775807 stays itself and 9223372036854775808 comes back as
     * 9.223372036854776E18. It also drops a trailing zero, writing 1.0 as 1 and 1e2 as 100.
     *
     * <p>Reading every integer as a long regardless of width is what makes a number larger than a
     * long come back negative.
     */
    private static Object toJsonataNumber(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value)) {
            throw new JException("D3137: Invalid JSON", -1);
        }
        if (value == Math.rint(value) && Math.abs(value) < LARGEST_EXACT_LONG_AS_DOUBLE) {
            return (long) value;
        }
        return value;
    }

    /**
     * $string(value, prettify): the JSONata function, with AWS's number notation. dashjoin writes
     * a whole number in full only while it fits in a long and prints exponent notation from there,
     * so $string(1e20) is "1e+20" where AWS writes the twenty-one digits. AWS's boundary is 1e21.
     *
     * <p>Nothing else about the function changes: the call reaches the library with the numbers
     * AWS writes in full already replaced by their digits, so the delimiters, the escaping, the
     * prettify layout and every error message stay the library's.
     */
    private static Object string(List<Object> arguments) {
        return Functions.string(withWholeNumbersWrittenInFull(argument(arguments, 0)),
                (Boolean) argument(arguments, 1));
    }

    /**
     * Replaces every double AWS writes in full with the exact integer of the shortest decimal that
     * reads back as that double, which is the digit string AWS prints: a double holding 2^63 is
     * "9223372036854776000" there and not its exact value 9223372036854775808. A BigInteger is
     * printed verbatim, so the notation stops being the library's decision.
     */
    private static Object withWholeNumbersWrittenInFull(Object value) {
        if (value instanceof Double number) {
            boolean writtenInFull = Double.isFinite(number) && number % 1 == 0
                    && Math.abs(number) < SMALLEST_EXPONENT_NOTATION;
            return writtenInFull ? BigDecimal.valueOf(number).toBigInteger() : number;
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> written = new LinkedHashMap<>();
            object.forEach((key, field) -> written.put(String.valueOf(key), withWholeNumbersWrittenInFull(field)));
            return written;
        }
        if (value instanceof List<?> array) {
            List<Object> written = new ArrayList<>();
            array.forEach(element -> written.add(withWholeNumbersWrittenInFull(element)));
            return written;
        }
        return value;
    }

    /**
     * $partition(array, chunkSize): splits array into chunks of chunkSize elements, the last chunk
     * holding the remainder. A missing chunk size returns the whole array as one chunk; a missing
     * array, an empty array and a chunk size that rounds to zero evaluate to undefined (Java
     * null); a chunk size that rounds below zero raises D3137, and a non-array first argument or a
     * non-numeric chunk size raises a JSONata signature error. Rounding towards zero is what makes
     * -0.5 undefined and -2 an error.
     */
    private static Object partition(List<Object> arguments) {
        rejectSurplusArgument(arguments, "partition", 2);
        Object arrayArgument = argument(arguments, 0);
        if (arrayArgument == null) {
            return null;
        }
        if (!(arrayArgument instanceof List<?> array)) {
            throw new JException("T0412: Argument 1 of function \"partition\" must be an array of undefined", -1);
        }
        if (array.isEmpty()) {
            return null;
        }
        Object chunkSizeArgument = argument(arguments, 1);
        if (chunkSizeArgument == null) {
            return List.of(array);
        }
        long chunkSize = towardsZero(chunkSizeArgument, "partition", 2);
        if (chunkSize < 0) {
            throw new JException("D3137: Second argument must be zero or greater", -1);
        }
        if (chunkSize == 0) {
            return null;
        }
        List<Object> chunks = new ArrayList<>();
        int size = (int) Math.min(chunkSize, array.size());
        for (int i = 0; i < array.size(); i += size) {
            chunks.add(new ArrayList<>(array.subList(i, Math.min(i + size, array.size()))));
        }
        return chunks;
    }

    /**
     * $range(start, end, step): generates an array from start to end (inclusive, when the step
     * lands on it). A single-element range collapses to the bare scalar, not a one-element array;
     * a missing or zero step, or a step whose sign disagrees with the start/end direction,
     * evaluates to undefined (Java null); a missing start or end raises a JSONata error.
     */
    private static Object range(List<Object> arguments) {
        rejectSurplusArgument(arguments, "range", 3);
        long start = towardsZero(argument(arguments, 0), "range", 1);
        long end = towardsZero(argument(arguments, 1), "range", 2);
        Object stepArgument = argument(arguments, 2);
        if (stepArgument == null) {
            return null;
        }
        long step = towardsZero(stepArgument, "range", 3);
        if (step == 0 || (step > 0 && start > end) || (step < 0 && start < end)) {
            return null;
        }
        if (start == end) {
            return start;
        }
        // Counted in BigInteger, then iterated a fixed number of times. Walking the range with
        // `v += step` and testing `v <= end` never terminates once the addition wraps: with a step
        // near Long.MAX_VALUE the sum flips sign and lands back below the end on every pass.
        BigInteger elements = BigInteger.valueOf(end)
                .subtract(BigInteger.valueOf(start))
                .divide(BigInteger.valueOf(step))
                .add(BigInteger.ONE);
        if (elements.compareTo(BigInteger.valueOf(MAX_RANGE_ELEMENTS)) > 0) {
            throw new JException(MEMORY_LIMIT_EXCEEDED, -1);
        }
        List<Object> values = new ArrayList<>();
        long value = start;
        for (BigInteger emitted = BigInteger.ZERO;
                emitted.compareTo(elements) < 0;
                emitted = emitted.add(BigInteger.ONE)) {
            values.add(value);
            value += step;
        }
        return values;
    }

    /**
     * $hash(str, algorithm): hex-encoded digest of str using algorithm, one of MD5, SHA-1,
     * SHA-256, SHA-384 or SHA-512 (case-sensitive). A missing or non-string str raises a JSONata
     * signature error, a missing algorithm evaluates to undefined (Java null), and an algorithm
     * name that is a string but not one of the five raises D3137: that is the split AWS makes
     * between a wrong type and a wrong value.
     */
    private static Object hash(List<Object> arguments) {
        rejectSurplusArgument(arguments, "hash", 2);
        if (!(argument(arguments, 0) instanceof String value)) {
            throw signatureError("hash", 1);
        }
        Object algorithmArgument = argument(arguments, 1);
        if (algorithmArgument == null) {
            return null;
        }
        if (!(algorithmArgument instanceof String algorithm)) {
            throw signatureError("hash", 2);
        }
        if (!HASH_ALGORITHMS.contains(algorithm)) {
            throw new JException("D3137: Hash algorithm '" + algorithm
                    + "' must be one of SHA-1, SHA-384, SHA-256, SHA-512, MD5", -1);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new JException("Hash algorithm '" + algorithm + "' is not available", -1);
        }
    }

    /**
     * $random(seed): a number in [0, 1). JSONata's own $random takes no arguments; the Step
     * Functions one takes an optional integer seed and is reproducible under it. AWS draws from
     * java.util.Random, so seeding one with the same value returns the same sequence: $random(42)
     * is 0.7275636800328681 on both sides.
     */
    private static Object random(List<Object> arguments) {
        rejectSurplusArgument(arguments, "random", 1);
        Object seedArgument = argument(arguments, 0);
        if (seedArgument == null) {
            return ThreadLocalRandom.current().nextDouble();
        }
        return new Random(towardsZero(seedArgument, "random", 1)).nextDouble();
    }

    /**
     * $uuid(): a random v4 UUID. Strictly zero-arity; any argument raises a JSONata error.
     */
    private static Object uuid(List<Object> arguments) {
        rejectSurplusArgument(arguments, "uuid", 0);
        return UUID.randomUUID().toString();
    }
}
