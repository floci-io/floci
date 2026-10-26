package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks an AWS Query request against the constraints a {@code tools/aws/regen_*_shapes.py}
 * generator vendors from botocore through {@code request_shapes.build_constraints}: the members a
 * structure requires, a string's length, pattern and enum, and a number's range. It reads the same
 * vendored shape {@link RequestConstraintCheck} reads for the JSON protocols, and walks it
 * differently because the requests differ: a JSON body arrives as a tree, where a Query request
 * arrives as flat form parameters named after the members that carry them.
 *
 * <p>Every violation is collected rather than the first one thrown, which is what AWS's validation
 * layer answers: "N validation errors detected" with the violations in the order the model lists
 * the members, and a member that breaks two constraints reported twice. That is the reading #6402
 * merged for a `ServiceSpecificCredentialId` breaking both its length and its pattern, and
 * `docs/services/iam.md` carries it.
 *
 * <p>The offending value is quoted, which is what {@link SmithyValidationErrors#lengthOutOfRange}
 * and this package's `validateRoleName` already answer. Whether current IAM quotes it at all is
 * what DRIFT-13 asks, and the point of reading the constraints here is that one capture would
 * change one helper rather than every message in the package.
 *
 * <p>A member the request leaves out is absent, and only a required member is reported for one. A
 * member present and empty is a value: the protocol carries it, so it breaks its minimum length
 * rather than reading as absent.
 *
 * <p>Scalars only for now. An operation whose input shape carries a list or a map is refused when
 * the file loads rather than passed over quietly, since reading one means reading
 * {@code Name.member.N} and {@code Name.entry.N.key} and naming each index as the request spelled
 * it.
 */
public final class QueryConstraintCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shape types a Query parameter can carry on its own, without an index or an entry. */
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "long", "boolean");

    private final JsonNode operations;
    private final JsonNode shapes;
    private final Map<String, Pattern> patterns = new HashMap<>();

    private QueryConstraintCheck(JsonNode model) {
        this.operations = model.path("operations");
        this.shapes = model.path("shapes");
        for (Map.Entry<String, JsonNode> shape : shapes.properties()) {
            if (shape.getValue().has("pattern")) {
                patterns.put(shape.getKey(), Pattern.compile(shape.getValue().get("pattern").asText()));
            }
        }
        for (Map.Entry<String, JsonNode> operation : operations.properties()) {
            requireScalarMembers(operation.getKey(), operation.getValue().asText());
        }
    }

    /** The vendored resource {@code name}; {@code description} names it in a failure. */
    public static QueryConstraintCheck load(String name, String description) {
        return VendoredResources.read(name, description,
                input -> new QueryConstraintCheck(MAPPER.readTree(input)));
    }

    public static QueryConstraintCheck of(JsonNode model) {
        return new QueryConstraintCheck(model);
    }

    private void requireScalarMembers(String operation, String inputShape) {
        for (Map.Entry<String, JsonNode> member : shapes.path(inputShape).path("members").properties()) {
            String type = shapes.path(member.getValue().asText()).path("type").asText();
            if (!SCALAR_TYPES.contains(type)) {
                throw new IllegalStateException(operation + "'s " + member.getKey() + " is a " + type
                        + ", which this check does not read yet");
            }
        }
    }

    /**
     * Throws the Query protocol's {@code ValidationError} when {@code params} breaks a constraint
     * the model declares for {@code operation}. An operation the vendored file does not carry
     * passes, which is how the file grows one wave at a time.
     */
    public void check(String operation, MultivaluedMap<String, String> params) {
        List<String> violations = violations(operation, params);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
    }

    /** The violations {@code params} carries for {@code operation}, in the model's member order. */
    public List<String> violations(String operation, MultivaluedMap<String, String> params) {
        JsonNode shape = shapes.path(operations.path(operation).asText());
        if (!shape.hasNonNull("members")) {
            return List.of();
        }
        Set<String> required = new LinkedHashSet<>();
        shape.path("required").forEach(name -> required.add(name.asText()));
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : shape.path("members").properties()) {
            String name = member.getKey();
            String value = params == null ? null : params.getFirst(name);
            String path = memberPath(name);
            if (value == null) {
                if (required.contains(name)) {
                    violations.add(SmithyValidationErrors.notNull(path));
                }
                continue;
            }
            violations.addAll(violations(member.getValue().asText(), value, path));
        }
        return violations;
    }

    /** The member's name as a validation message spells it, which is the request's with a lower
     * first letter. */
    private static String memberPath(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Every constraint {@code value} breaks, in the order a shape declares them. */
    private List<String> violations(String shapeName, String value, String path) {
        JsonNode shape = shapes.path(shapeName);
        String type = shape.path("type").asText();
        if ("integer".equals(type) || "long".equals(type)) {
            return single(numberViolation(shape, value, path));
        }
        if (!"string".equals(type)) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        String length = SmithyValidationErrors.lengthOutOfRange(path, value,
                shape.path("min").asInt(0), shape.path("max").asInt(Integer.MAX_VALUE));
        if (length != null) {
            violations.add(length);
        }
        Pattern pattern = patterns.get(shapeName);
        if (pattern != null && !pattern.matcher(value).matches()) {
            violations.add(SmithyValidationErrors.pattern(path, value, pattern.pattern()));
        }
        violations.addAll(single(enumViolation(shape, value, path)));
        return violations;
    }

    private static List<String> single(String violation) {
        return violation == null ? List.of() : List.of(violation);
    }

    /**
     * A value that is no number is left to the operation: this package answers one with
     * {@code InvalidParameterValue} rather than with a constraint violation, and which member
     * reports it that way is the operation's own reading.
     */
    private static String numberViolation(JsonNode shape, String value, String path) {
        long number;
        try {
            number = Long.parseLong(value.trim());
        } catch (NumberFormatException expected) {
            // Not a number at all, so no range of the model's is what it breaks.
            return null;
        }
        return SmithyValidationErrors.outOfRange(path, number,
                shape.path("min").asLong(Long.MIN_VALUE), shape.path("max").asLong(Long.MAX_VALUE));
    }

    private static String enumViolation(JsonNode shape, String value, String path) {
        if (!shape.has("enum")) {
            return null;
        }
        List<String> allowed = new ArrayList<>();
        shape.path("enum").forEach(choice -> allowed.add(choice.asText()));
        return allowed.contains(value) ? null : SmithyValidationErrors.notInEnum(value, path, allowed);
    }
}
