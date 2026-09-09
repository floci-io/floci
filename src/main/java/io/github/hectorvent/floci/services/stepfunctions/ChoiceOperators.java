package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Choice-state comparison operators: the single source of truth for the ASL data-test
 * operator set, their <em>type-strict</em> evaluation, and the structural validation of
 * {@code Choice} rules.
 *
 * <p>Extracted from {@link AslExecutor} so the exact same operator inventory and semantics
 * are shared between the runtime evaluator and the {@code CreateStateMachine}/
 * {@code UpdateStateMachine} validator (they can no longer drift), and so the logic is
 * unit-testable without constructing the full Quarkus/Vert.x runtime that {@code AslExecutor}
 * requires.
 *
 * <p>Type strictness matches AWS: a comparator only matches when the resolved value and the
 * operand share the expected JSON type; a mismatched type yields {@code false} rather than a
 * coerced comparison. A {@code Variable} (or {@code *Path} operand) that resolves to a missing
 * path raises {@link ChoiceEvaluationException} for every operator except {@code IsPresent}
 * (which exists precisely to test presence); the caller maps that to a {@code States.Runtime}
 * failure. An unsupported field or a rule that does not carry exactly one operator is likewise a
 * runtime error, not a silently-false fallthrough to the {@code Default} branch.
 *
 * @see <a href="https://docs.aws.amazon.com/step-functions/latest/dg/amazon-states-language-choice-state.html">ASL Choice state</a>
 */
public final class ChoiceOperators {

    private ChoiceOperators() {
    }

    public static final Set<String> TYPE_TEST_OPERATORS = Set.of(
            "IsNull", "IsPresent", "IsNumeric", "IsString", "IsBoolean", "IsTimestamp");

    public static final Set<String> STRING_OPERATORS = Set.of(
            "StringEquals", "StringEqualsPath",
            "StringLessThan", "StringLessThanPath",
            "StringGreaterThan", "StringGreaterThanPath",
            "StringLessThanEquals", "StringLessThanEqualsPath",
            "StringGreaterThanEquals", "StringGreaterThanEqualsPath",
            "StringMatches");

    public static final Set<String> NUMERIC_OPERATORS = Set.of(
            "NumericEquals", "NumericEqualsPath",
            "NumericLessThan", "NumericLessThanPath",
            "NumericGreaterThan", "NumericGreaterThanPath",
            "NumericLessThanEquals", "NumericLessThanEqualsPath",
            "NumericGreaterThanEquals", "NumericGreaterThanEqualsPath");

    public static final Set<String> BOOLEAN_OPERATORS = Set.of(
            "BooleanEquals", "BooleanEqualsPath");

    public static final Set<String> TIMESTAMP_OPERATORS = Set.of(
            "TimestampEquals", "TimestampEqualsPath",
            "TimestampLessThan", "TimestampLessThanPath",
            "TimestampGreaterThan", "TimestampGreaterThanPath",
            "TimestampLessThanEquals", "TimestampLessThanEqualsPath",
            "TimestampGreaterThanEquals", "TimestampGreaterThanEqualsPath");

    /** The 39 documented data-test comparison operators (excludes the And/Or/Not logical operators). */
    public static final Set<String> DATA_TEST_OPERATORS = buildDataTestOperators();

    public static final Set<String> LOGICAL_OPERATORS = Set.of("And", "Or", "Not");

    /** Non-operator fields an individual Choice rule may carry. */
    public static final Set<String> RULE_STRUCTURAL_FIELDS = Set.of("Variable", "Comment", "Next", "Assign");

    private static Set<String> buildDataTestOperators() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(STRING_OPERATORS);
        all.addAll(NUMERIC_OPERATORS);
        all.addAll(BOOLEAN_OPERATORS);
        all.addAll(TIMESTAMP_OPERATORS);
        all.addAll(TYPE_TEST_OPERATORS);
        return Set.copyOf(all);
    }

    // RFC3339 profile AWS accepts: uppercase 'T'; seconds required; optional fraction of 1-9 digits
    // (java.time's nanosecond ceiling); 'Z' or a colonized numeric offset. Leap seconds (:60) and
    // fractions beyond 9 digits are not supported and are treated as invalid, a stated limitation.
    private static final Pattern RFC3339 = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$");

    /** Resolves an ASL reference path against the current input, preserving the missing/null distinction. */
    @FunctionalInterface
    public interface PathResolver {
        JsonNode resolve(String path) throws Exception;
    }

    /**
     * Raised for an undefined reference path, an unsupported/absent comparator, a malformed rule, or an
     * invalid {@code StringMatches} pattern. The runtime caller ({@link AslExecutor}) maps this to a
     * {@code States.Runtime} failure; the validator surfaces the same class of problem at Create/Update time.
     */
    public static final class ChoiceEvaluationException extends RuntimeException {
        public ChoiceEvaluationException(String message) {
            super(message);
        }
    }

    // ──────────────────────────── Evaluation ────────────────────────────

    /**
     * Evaluates a single Choice rule (including {@code And}/{@code Or}/{@code Not} nesting) against the
     * input reachable through {@code resolver}. Returns whether the rule matches.
     *
     * @throws ChoiceEvaluationException if the rule is malformed (unsupported field, or not exactly one
     *                                   operator), a referenced path is undefined (non-{@code IsPresent}),
     *                                   or a {@code StringMatches} pattern is invalid.
     */
    public static boolean evaluate(JsonNode rule, PathResolver resolver) throws Exception {
        // Runtime backstop: a malformed rule is a runtime error, not a silent one-branch pick. This also
        // catches state machines persisted before Create/Update validation rejected such rules.
        assertRuntimeWellFormed(rule);

        if (rule.has("And")) {
            for (JsonNode sub : rule.get("And")) {
                if (!evaluate(sub, resolver)) {
                    return false;
                }
            }
            return true;
        }
        if (rule.has("Or")) {
            for (JsonNode sub : rule.get("Or")) {
                if (evaluate(sub, resolver)) {
                    return true;
                }
            }
            return false;
        }
        if (rule.has("Not")) {
            return !evaluate(rule.get("Not"), resolver);
        }

        String operator = dataTestOperatorOf(rule);
        String variable = rule.path("Variable").asText(null);
        if (variable == null || variable.isEmpty()) {
            throw new ChoiceEvaluationException(
                    "Choice rule with operator '" + operator + "' is missing the required 'Variable' field");
        }
        JsonNode value = resolver.resolve(variable);
        if (!"IsPresent".equals(operator) && (value == null || value.isMissingNode())) {
            throw new ChoiceEvaluationException(
                    "Invalid path '" + variable + "': the Choice rule references an undefined value");
        }
        return evaluateLeaf(operator, value, rule.get(operator), resolver);
    }

    /** Rejects rules with an unsupported field or without exactly one comparison/logical operator. */
    private static void assertRuntimeWellFormed(JsonNode rule) {
        int logical = 0;
        int dataTest = 0;
        var it = rule.fieldNames();
        while (it.hasNext()) {
            String field = it.next();
            if (LOGICAL_OPERATORS.contains(field)) {
                logical++;
            } else if (DATA_TEST_OPERATORS.contains(field)) {
                dataTest++;
            } else if (!RULE_STRUCTURAL_FIELDS.contains(field)) {
                throw new ChoiceEvaluationException("Unsupported field '" + field + "' in Choice rule");
            }
        }
        if (logical + dataTest != 1) {
            throw new ChoiceEvaluationException(
                    "Choice rule must contain exactly one comparison operator or one of And/Or/Not");
        }
    }

    private static boolean evaluateLeaf(String op, JsonNode value, JsonNode operand, PathResolver resolver)
            throws Exception {
        // Presence / type predicates (operand is the expected boolean).
        switch (op) {
            case "IsPresent":
                return (value != null && !value.isMissingNode()) == operand.asBoolean();
            case "IsNull":
                return value.isNull() == operand.asBoolean();
            case "IsString":
                return value.isTextual() == operand.asBoolean();
            case "IsNumeric":
                return value.isNumber() == operand.asBoolean();
            case "IsBoolean":
                return value.isBoolean() == operand.asBoolean();
            case "IsTimestamp":
                return (value.isTextual() && isRfc3339(value.asText())) == operand.asBoolean();
            case "StringMatches":
                return value.isTextual() && Pattern.matches(stringMatchesToRegex(operand.asText()), value.asText());
            default:
                break;
        }

        boolean isPath = op.endsWith("Path");
        String core = isPath ? op.substring(0, op.length() - "Path".length()) : op;
        JsonNode rhs = isPath ? resolveRequired(resolver, operand.asText()) : operand;

        if (core.startsWith("String")) {
            if (!value.isTextual() || !rhs.isTextual()) {
                return false;
            }
            return applyComparison(value.asText().compareTo(rhs.asText()), core.substring("String".length()));
        }
        if (core.startsWith("Numeric")) {
            if (!value.isNumber() || !rhs.isNumber()) {
                return false;
            }
            BigDecimal a = value.decimalValue();
            BigDecimal b = rhs.decimalValue();
            return applyComparison(a.compareTo(b), core.substring("Numeric".length()));
        }
        if (core.startsWith("Boolean")) {
            // Only BooleanEquals[Path] exists.
            if (!value.isBoolean() || !rhs.isBoolean()) {
                return false;
            }
            return value.asBoolean() == rhs.asBoolean();
        }
        if (core.startsWith("Timestamp")) {
            if (!value.isTextual() || !rhs.isTextual()) {
                return false;
            }
            Instant a = parseRfc3339(value.asText());
            Instant b = parseRfc3339(rhs.asText());
            if (a == null || b == null) {
                return false;
            }
            return applyComparison(a.compareTo(b), core.substring("Timestamp".length()));
        }
        // Unreachable given assertRuntimeWellFormed + dataTestOperatorOf, but fail loud rather than silent.
        throw new ChoiceEvaluationException("Unsupported comparison operator: " + op);
    }

    private static boolean applyComparison(int cmp, String kind) {
        switch (kind) {
            case "Equals":
                return cmp == 0;
            case "LessThan":
                return cmp < 0;
            case "GreaterThan":
                return cmp > 0;
            case "LessThanEquals":
                return cmp <= 0;
            case "GreaterThanEquals":
                return cmp >= 0;
            default:
                throw new ChoiceEvaluationException("Unsupported comparison kind: " + kind);
        }
    }

    private static JsonNode resolveRequired(PathResolver resolver, String path) throws Exception {
        JsonNode node = resolver.resolve(path);
        if (node == null || node.isMissingNode()) {
            throw new ChoiceEvaluationException(
                    "Invalid path '" + path + "': the Choice rule references an undefined value");
        }
        return node;
    }

    private static String dataTestOperatorOf(JsonNode rule) {
        var it = rule.fieldNames();
        while (it.hasNext()) {
            String field = it.next();
            if (DATA_TEST_OPERATORS.contains(field)) {
                return field;
            }
        }
        return null;
    }

    static boolean isRfc3339(String text) {
        return parseRfc3339(text) != null;
    }

    private static Instant parseRfc3339(String text) {
        if (text == null || !RFC3339.matcher(text).matches()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Compiles an ASL {@code StringMatches} pattern to an anchored regex. The only wildcard is {@code *};
     * {@code \\*} is a literal asterisk, {@code \\\\} a literal backslash, and a backslash followed by
     * anything else (or a dangling backslash) is an error, per the ASL specification.
     */
    static String stringMatchesToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '\\') {
                if (i + 1 >= glob.length()) {
                    throw new ChoiceEvaluationException(
                            "Invalid StringMatches pattern (dangling escape): '" + glob + "'");
                }
                char next = glob.charAt(++i);
                if (next != '*' && next != '\\') {
                    throw new ChoiceEvaluationException(
                            "Invalid StringMatches pattern (illegal escape '\\" + next + "'): '" + glob + "'");
                }
                literal.append(next);
            } else if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    // ──────────────────────────── Validation ────────────────────────────

    /**
     * Structurally validates a single Choice rule (recursing through {@code And}/{@code Or}/{@code Not}),
     * appending human-readable messages to {@code errors}. Enforces the field allowlist, exactly one
     * comparison-or-logical operator per rule, reference-path syntax for {@code Variable}/{@code *Path}
     * operands, per-family operand JSON types, valid {@code StringMatches} patterns, {@code Assign} being an
     * object, logical operand shapes, and {@code Next} placement (required at the top level of a Choices
     * entry, forbidden on nested operands).
     */
    public static void validateChoiceRule(String path, JsonNode rule, boolean topLevel, List<String> errors) {
        if (rule == null || !rule.isObject()) {
            errors.add("Choice rule must be a JSON object at " + path);
            return;
        }

        int logicalCount = 0;
        int dataTestCount = 0;
        var names = rule.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (LOGICAL_OPERATORS.contains(field)) {
                logicalCount++;
            } else if (DATA_TEST_OPERATORS.contains(field)) {
                dataTestCount++;
            } else if (!RULE_STRUCTURAL_FIELDS.contains(field)) {
                errors.add("Choice rule has unsupported field '" + field + "' at " + path);
            }
        }

        if (logicalCount + dataTestCount != 1) {
            errors.add("Choice rule must contain exactly one comparison operator or one of And/Or/Not at " + path);
        }

        if (rule.has("Assign") && !rule.get("Assign").isObject()) {
            errors.add("'Assign' must be a JSON object at " + path);
        }

        // Next placement: required at the top level of a Choices entry, forbidden on nested operands.
        boolean hasNext = rule.path("Next").isTextual();
        if (topLevel && !hasNext) {
            errors.add("Choice rule must declare a string 'Next' at " + path);
        } else if (!topLevel && rule.has("Next")) {
            errors.add("Nested Choice rule (inside And/Or/Not) must not declare 'Next' at " + path);
        }

        if (logicalCount == 1) {
            if (rule.has("Variable")) {
                errors.add("Logical Choice rule (And/Or/Not) must not declare 'Variable' at " + path);
            }
            if (rule.has("And") || rule.has("Or")) {
                String key = rule.has("And") ? "And" : "Or";
                JsonNode arr = rule.get(key);
                if (!arr.isArray() || arr.isEmpty()) {
                    errors.add("'" + key + "' must be a non-empty array at " + path);
                } else {
                    for (int i = 0; i < arr.size(); i++) {
                        validateChoiceRule(path + "/" + key + "/" + i, arr.get(i), false, errors);
                    }
                }
            } else { // Not
                JsonNode not = rule.get("Not");
                if (!not.isObject()) {
                    errors.add("'Not' must be a single object at " + path);
                } else {
                    validateChoiceRule(path + "/Not", not, false, errors);
                }
            }
            return;
        }

        if (dataTestCount == 1) {
            JsonNode variable = rule.path("Variable");
            if (!variable.isTextual() || !isReferencePath(variable.asText())) {
                errors.add("Choice comparison rule must declare a valid reference-path 'Variable' at " + path);
            }
            String op = dataTestOperatorOf(rule);
            if (op != null) {
                validateOperandType(path, op, rule.get(op), errors);
            }
        }
    }

    private static void validateOperandType(String path, String op, JsonNode operand, List<String> errors) {
        if (TYPE_TEST_OPERATORS.contains(op)) {
            if (!operand.isBoolean()) {
                errors.add("Operator '" + op + "' requires a boolean operand at " + path);
            }
            return;
        }
        if (op.endsWith("Path")) {
            if (!operand.isTextual() || !isReferencePath(operand.asText())) {
                errors.add("Operator '" + op + "' requires a reference-path string operand at " + path);
            }
            return;
        }
        if ("StringMatches".equals(op)) {
            if (!operand.isTextual()) {
                errors.add("Operator 'StringMatches' requires a string operand at " + path);
            } else {
                try {
                    stringMatchesToRegex(operand.asText());
                } catch (ChoiceEvaluationException e) {
                    errors.add(e.getMessage() + " at " + path);
                }
            }
            return;
        }
        if (STRING_OPERATORS.contains(op)) {
            if (!operand.isTextual()) {
                errors.add("Operator '" + op + "' requires a string operand at " + path);
            }
        } else if (TIMESTAMP_OPERATORS.contains(op)) {
            if (!operand.isTextual()) {
                errors.add("Operator '" + op + "' requires a string operand at " + path);
            } else if (!isRfc3339(operand.asText())) {
                errors.add("Operator '" + op + "' requires an RFC3339 timestamp operand at " + path);
            }
        } else if (NUMERIC_OPERATORS.contains(op)) {
            if (!operand.isNumber()) {
                errors.add("Operator '" + op + "' requires a numeric operand at " + path);
            }
        } else if (BOOLEAN_OPERATORS.contains(op)) {
            if (!operand.isBoolean()) {
                errors.add("Operator '" + op + "' requires a boolean operand at " + path);
            }
        }
    }

    /**
     * Pragmatic ASL reference-path syntax check: must start with {@code $}; {@code $} alone is valid; a
     * longer path must be a sequence of non-empty {@code .segment} steps and balanced non-empty
     * {@code [..]} steps. Rejects the common malformations ({@code not-a-path}, {@code $.}, {@code $[}).
     */
    static boolean isReferencePath(String p) {
        if (p == null || p.isEmpty() || p.charAt(0) != '$') {
            return false;
        }
        if (p.equals("$")) {
            return true;
        }
        if (!p.startsWith("$.") && !p.startsWith("$[")) {
            return false;
        }
        int i = 1;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                int j = i + 1;
                while (j < p.length() && p.charAt(j) != '.' && p.charAt(j) != '[') {
                    j++;
                }
                if (j == i + 1) {
                    return false; // empty segment: "$." or "$.."
                }
                i = j;
            } else if (c == '[') {
                int close = p.indexOf(']', i);
                if (close < 0 || close == i + 1) {
                    return false; // unclosed or empty "[]"
                }
                i = close + 1;
            } else {
                return false;
            }
        }
        return true;
    }
}
