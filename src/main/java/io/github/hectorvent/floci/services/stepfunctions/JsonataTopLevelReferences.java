package io.github.hectorvent.floci.services.stepfunctions;

import com.dashjoin.jsonata.Parser;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Names every reference a Step Functions JSONata expression makes against the top-level context.
 *
 * <p>A Step Functions expression is evaluated with no context item: the input arrives as
 * {@code $states.input} and workflow variables as {@code $name}. A path that starts from neither
 * reads a context item that does not exist, and real AWS refuses the whole definition at
 * {@code CreateStateMachine} time with
 * {@code UNSUPPORTED_JSONATA_EXPRESSION: Reference to 'phone' at the top level is not supported.}
 *
 * <p>Only the first step of a path is evaluated against the top-level context. Every later step,
 * every predicate, every sort term and every grouping expression runs against the value the step
 * before it produced, so a name there is legal and this walk never descends into one. A lambda
 * body, on the other hand, keeps the context of the expression that defines it, so
 * {@code $map($states.input.a, function($x){ b })} does name {@code b} at the top level. Both
 * halves are measured against AWS in {@code JsonataTopLevelReferencesTest}.
 *
 * <p>{@link Parser#parse(String)} is public and returns a public {@link Parser.Symbol}, but every
 * field carrying the tree is package-private, and Quarkus loads application classes and dependency
 * jars with different class loaders, so sharing the package raises {@code IllegalAccessError}
 * instead. The fields are therefore read reflectively, and the cost is paid up front: all of them
 * are resolved once in the static initializer, so a {@code com.dashjoin:jsonata} upgrade that
 * renames or removes one fails loudly on the first call instead of quietly naming no reference.
 */
@RegisterForReflection(targets = Parser.Symbol.class, fields = true)
final class JsonataTopLevelReferences {

    private static final Logger LOG = Logger.getLogger(JsonataTopLevelReferences.class);

    /** The name AWS prints for {@code $}, the context item itself. */
    private static final String CONTEXT_ITEM = "$";

    private static final Map<String, Field> TREE_FIELDS = resolveTreeFields(
            "type", "value", "steps", "lhs", "rhs", "expression", "expressions",
            "procedure", "arguments", "body", "condition", "then", "_else");

    private JsonataTopLevelReferences() {
    }

    /**
     * The distinct top-level references in {@code expression}, in the order they are written.
     * An expression that does not parse names none: a syntax error is AWS's separate
     * {@code INVALID_JSONATA_EXPRESSION} rule, and naming nothing leaves the definition accepted.
     */
    static List<String> in(String expression) {
        Parser.Symbol root;
        try {
            root = new Parser().parse(expression);
        } catch (Exception e) {
            LOG.debugf(e, "JSONata expression not parsed, so no top-level reference is named: %s",
                    expression);
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        collect(root, names);
        return new ArrayList<>(names);
    }

    private static void collect(Parser.Symbol node, Set<String> names) {
        String type = node == null ? null : (String) read(node, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "path" -> collect(first(node, "steps"), names);
            case "name" -> names.add(String.valueOf(read(node, "value")));
            case "variable" -> collectContextItem(node, names);
            case "unary" -> collectUnary(node, names);
            case "binary", "apply" -> {
                collect(child(node, "lhs"), names);
                collect(child(node, "rhs"), names);
            }
            case "function", "partial" -> {
                collect(child(node, "procedure"), names);
                collectEach(children(node, "arguments"), names);
            }
            case "lambda" -> collect(child(node, "body"), names);
            case "condition" -> {
                collect(child(node, "condition"), names);
                collect(child(node, "then"), names);
                collect(child(node, "_else"), names);
            }
            case "block" -> collectEach(children(node, "expressions"), names);
            case "bind" -> collect(child(node, "rhs"), names);
            default -> {
                // literal, number, string, regex, descendant, transform, sort, filter and group
                // never read the top-level context.
            }
        }
    }

    /**
     * {@code $} carries the empty name and is the context item AWS refuses. {@code $$} carries the
     * name {@code "$"} and AWS refuses it under a different message, which is a separate rule.
     * Every other variable is a workflow variable or a function, and AWS accepts an undefined one.
     */
    private static void collectContextItem(Parser.Symbol variable, Set<String> names) {
        if ("".equals(read(variable, "value"))) {
            names.add(CONTEXT_ITEM);
        }
    }

    /**
     * The array constructor carries its bracket as a {@link Character} and every other unary
     * operator carries it as a {@link String}, so the operator is read through
     * {@link String#valueOf(Object)} rather than compared against the field directly. The object
     * constructor keeps its key/value pairs in the public {@code lhsObject}, and AWS reads a key
     * at the top level too: {@code {aaa: 1}} names {@code aaa}.
     */
    private static void collectUnary(Parser.Symbol unary, Set<String> names) {
        switch (String.valueOf(read(unary, "value"))) {
            case "[" -> collectEach(children(unary, "expressions"), names);
            case "{" -> collectEachPair(unary.lhsObject, names);
            default -> collect(child(unary, "expression"), names);
        }
    }

    private static void collectEach(List<Parser.Symbol> nodes, Set<String> names) {
        if (nodes == null) {
            return;
        }
        for (Parser.Symbol node : nodes) {
            collect(node, names);
        }
    }

    private static void collectEachPair(List<Parser.Symbol[]> pairs, Set<String> names) {
        if (pairs == null) {
            return;
        }
        for (Parser.Symbol[] pair : pairs) {
            collect(pair[0], names);
            collect(pair[1], names);
        }
    }

    private static Parser.Symbol child(Parser.Symbol node, String field) {
        return (Parser.Symbol) read(node, field);
    }

    @SuppressWarnings("unchecked")
    private static List<Parser.Symbol> children(Parser.Symbol node, String field) {
        return (List<Parser.Symbol>) read(node, field);
    }

    private static Parser.Symbol first(Parser.Symbol node, String field) {
        List<Parser.Symbol> nodes = children(node, field);
        return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
    }

    private static Object read(Parser.Symbol node, String field) {
        try {
            return Objects.requireNonNull(TREE_FIELDS.get(field), field).get(node);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "com.dashjoin:jsonata no longer allows reading Parser.Symbol." + field, e);
        }
    }

    private static Map<String, Field> resolveTreeFields(String... names) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (String name : names) {
            try {
                Field field = Parser.Symbol.class.getDeclaredField(name);
                field.setAccessible(true);
                fields.put(name, field);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("com.dashjoin:jsonata no longer declares "
                        + "Parser.Symbol." + name + ", so the top-level reference check has to be "
                        + "rewritten against the new parse tree", e);
            }
        }
        return Map.copyOf(fields);
    }
}
