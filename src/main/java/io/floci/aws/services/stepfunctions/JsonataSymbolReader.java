package io.floci.aws.services.stepfunctions;

import com.dashjoin.jsonata.Parser;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the nonpublic JSONata parse-tree fields shared by validation and evaluation.
 * Quarkus loads application classes and dependency jars with different class loaders, so sharing
 * the parser's package would raise IllegalAccessError. Resolve fields once so a dependency upgrade
 * that changes the tree fails loudly, and register their native-image reflection in one place.
 */
@RegisterForReflection(targets = Parser.Symbol.class, fields = true)
final class JsonataSymbolReader {

    private static final Map<String, Field> TREE_FIELDS = resolveTreeFields(
            "type", "value", "steps", "lhs", "rhs", "expression", "expressions",
            "procedure", "arguments", "body", "condition", "then", "_else", "keepArray");

    private JsonataSymbolReader() {
    }

    static Object read(Parser.Symbol node, String field) {
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
                        + "Parser.Symbol." + name + ", so its parse-tree readers must be updated", e);
            }
        }
        return Map.copyOf(fields);
    }
}
