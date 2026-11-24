package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What {@link QueryConstraintCheck} and {@link RequestConstraintCheck} both read off a vendored
 * shape: the patterns the shapes declare, how a string breaks its shape, the values an enum admits
 * and how a message names a member. The two walk a request differently because the protocols carry
 * one differently, but a string is held to its shape and worded the same way on either, so that
 * reading lives here once.
 */
final class VendoredShapes {

    private VendoredShapes() {
    }

    /** Each shape's pattern, compiled as the model means it, keyed by the shape's name. */
    static Map<String, Pattern> patterns(JsonNode shapes) {
        Map<String, Pattern> patterns = new HashMap<>();
        for (Map.Entry<String, JsonNode> shape : shapes.properties()) {
            if (shape.getValue().has("pattern")) {
                patterns.put(shape.getKey(), VendoredPatterns.compile(shape.getValue().get("pattern").asText()));
            }
        }
        return patterns;
    }

    /**
     * Every constraint {@code text} breaks against its string {@code shape}, in the order AWS lists
     * them: its length, its {@code pattern} (none for a shape without one) and its enum, each in
     * the form {@code messages} writes.
     *
     * <p>The pattern is quoted as the model declares it, which is what AWS answers and what the
     * service docs carry, rather than as it was compiled: a wide escape is rewritten to match on.
     * One member is quoted from a capture instead, where AWS gives a pattern of its own for a shape
     * botocore shares, and {@link VendoredPatterns#quoted} decides which text that is.
     */
    static List<String> stringViolations(JsonNode shape, Pattern pattern, String text, String path,
                                         SmithyValidationErrors.Violations messages) {
        List<String> violations = new ArrayList<>();
        String length = messages.lengthOutOfRange(path, text,
                shape.path("min").asInt(0), shape.path("max").asInt(Integer.MAX_VALUE));
        if (length != null) {
            violations.add(length);
        }
        if (pattern != null && !pattern.matcher(text).matches()) {
            violations.add(messages.pattern(path, text, VendoredPatterns.quoted(shape)));
        }
        List<String> allowed = enumValues(shape);
        if (!allowed.isEmpty() && !allowed.contains(text)) {
            violations.add(messages.notInEnum(text, path, allowed));
        }
        return violations;
    }

    /** The values a shape's enum admits, in the model's order, or none for a shape without one. */
    static List<String> enumValues(JsonNode shape) {
        List<String> values = new ArrayList<>();
        shape.path("enum").forEach(choice -> values.add(choice.asText()));
        return values;
    }

    /** A member's name as a validation message spells it, which is the model's with a lower first letter. */
    static String memberName(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
