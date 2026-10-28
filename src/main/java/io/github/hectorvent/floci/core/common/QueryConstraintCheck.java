package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedMap;

import java.math.BigInteger;
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
 * <p>A list arrives numbered under its own name, {@code Audience.member.1} or the bare
 * {@code Audience.1}, and a member of a list of structures carries its fields under that prefix,
 * {@code Tags.member.1.Key}. Each is named in a message the way this package already names it:
 * {@code audience.1.member} and {@code tags.1.member.key}, with the index the request spelled
 * rather than the position the member sits at. A list of no members is how an absent list arrives
 * on this protocol, so a required one reads as null rather than as a list breaking its minimum, and
 * the count is held to the list's own bounds with the value left out of the message. A structure
 * member arrives as {@code TaskPolicyArn.Arn} and is named {@code taskPolicyArn.arn}.
 *
 * <p>A map is refused when the file loads rather than passed over quietly: this package answers one
 * today in a form of its own, {@code Map keys must satisfy constraint: [...]} against the whole map,
 * and which of the two forms AWS answers for a Query map is a question no source here settles. So is
 * anything nested deeper than a list of structures or a structure of scalars.
 */
public final class QueryConstraintCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shape types a Query parameter can carry on its own, without an index or an entry. */
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "long", "boolean");

    /** A number as the protocol writes one, of any width: the model's bounds are what hold it. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

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
            requireReadableMembers(operation.getKey(), operation.getValue().asText());
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

    private void requireReadableMembers(String operation, String inputShape) {
        for (Map.Entry<String, JsonNode> member : shapes.path(inputShape).path("members").properties()) {
            requireReadableMember(operation, member.getKey(), member.getValue().asText());
        }
    }

    private void requireReadableMember(String operation, String member, String shapeName) {
        String type = shapes.path(shapeName).path("type").asText();
        if (SCALAR_TYPES.contains(type)) {
            return;
        }
        switch (type) {
            case "list" -> requireReadableElement(operation, member, shapes.path(shapeName).path("member").asText());
            case "structure" -> requireScalarFields(operation, member, shapeName);
            default -> throw refusal(operation, member, type);
        }
    }

    private void requireReadableElement(String operation, String member, String elementShape) {
        String type = shapes.path(elementShape).path("type").asText();
        if (SCALAR_TYPES.contains(type)) {
            return;
        }
        if ("structure".equals(type)) {
            requireScalarFields(operation, member, elementShape);
            return;
        }
        throw refusal(operation, member, "list of " + type);
    }

    private void requireScalarFields(String operation, String member, String structure) {
        for (Map.Entry<String, JsonNode> field : shapes.path(structure).path("members").properties()) {
            String type = shapes.path(field.getValue().asText()).path("type").asText();
            if (!SCALAR_TYPES.contains(type)) {
                throw refusal(operation, member + "." + field.getKey(), type);
            }
        }
    }

    private static IllegalStateException refusal(String operation, String member, String type) {
        return new IllegalStateException(operation + "'s " + member + " is a " + type
                + ", which this check does not read yet");
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
        return structureViolations(shape, "", "", params);
    }

    /**
     * The violations the members of {@code structure} carry, in the order the model lists them.
     * {@code prefix} is what the request names them under and {@code path} what a message calls
     * them: both are empty at the top level, and name the member above inside a structure or inside
     * a list's member.
     */
    private List<String> structureViolations(JsonNode structure, String prefix, String path,
                                             MultivaluedMap<String, String> params) {
        Set<String> required = new LinkedHashSet<>();
        structure.path("required").forEach(name -> required.add(name.asText()));
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : structure.path("members").properties()) {
            String name = member.getKey();
            String shapeName = member.getValue().asText();
            String memberPath = path + memberPath(name);
            String param = prefix + name;
            boolean mandatory = required.contains(name);
            JsonNode shape = shapes.path(shapeName);
            switch (shape.path("type").asText()) {
                case "list" -> violations.addAll(listViolations(shape, param, memberPath, mandatory, params));
                case "structure" -> violations.addAll(nestedViolations(shape, param, memberPath, mandatory, params));
                default -> {
                    String value = params == null ? null : params.getFirst(param);
                    if (value == null) {
                        if (mandatory) {
                            violations.add(SmithyValidationErrors.notNull(memberPath));
                        }
                    } else {
                        violations.addAll(violations(shapeName, value, memberPath));
                    }
                }
            }
        }
        return violations;
    }

    /**
     * A list's members, held to the list's own bounds and then each to its element's. A list of no
     * members is how an absent list arrives, which is what makes a minimum of 1 unreachable: only a
     * list asking for two or more members can report one.
     */
    private List<String> listViolations(JsonNode shape, String param, String path, boolean required,
                                        MultivaluedMap<String, String> params) {
        List<AwsQueryLists.IndexedMember> members = params == null
                ? List.of()
                : AwsQueryLists.members(params, param, null);
        if (members.isEmpty()) {
            return required ? List.of(SmithyValidationErrors.notNull(path)) : List.of();
        }
        List<String> violations = new ArrayList<>();
        violations.addAll(single(SmithyValidationErrors.sizeOutOfRange(path, members.size(),
                shape.path("min").asInt(0), shape.path("max").asInt(Integer.MAX_VALUE))));
        String elementShape = shape.path("member").asText();
        boolean structure = "structure".equals(shapes.path(elementShape).path("type").asText());
        for (AwsQueryLists.IndexedMember member : members) {
            String memberPath = path + "." + member.index() + ".member";
            if (structure) {
                violations.addAll(structureViolations(shapes.path(elementShape),
                        member.prefix() + ".", memberPath + ".", params));
            } else {
                String value = params.getFirst(member.prefix());
                violations.addAll(violations(elementShape, value == null ? "" : value, memberPath));
            }
        }
        return violations;
    }

    /**
     * A structure member, whose fields arrive under its own name. A request naming none of them is
     * a request without the member at all, so a required one reads as null.
     */
    private List<String> nestedViolations(JsonNode shape, String param, String path, boolean required,
                                          MultivaluedMap<String, String> params) {
        boolean present = params != null
                && params.keySet().stream().anyMatch(key -> key.startsWith(param + "."));
        if (!present) {
            return required ? List.of(SmithyValidationErrors.notNull(path)) : List.of();
        }
        return structureViolations(shape, param + ".", path + ".", params);
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
     *
     * <p>A number too large for a {@code long} is a number all the same, and the bound it breaks is
     * one that fits in one, so the range is compared without a width of its own. The value is
     * written as the number reads rather than as the request spelled it, a leading zero or plus
     * gone, which is what a parsed value already answered.
     */
    private static String numberViolation(JsonNode shape, String value, String path) {
        String text = value.trim();
        if (!INTEGER.matcher(text).matches()) {
            // Not a number at all, so no range of the model's is what it breaks.
            return null;
        }
        BigInteger number = new BigInteger(text);
        long min = shape.path("min").asLong(Long.MIN_VALUE);
        long max = shape.path("max").asLong(Long.MAX_VALUE);
        if (number.compareTo(BigInteger.valueOf(min)) < 0) {
            return SmithyValidationErrors.valueTooSmall(path, number.toString(), min);
        }
        if (number.compareTo(BigInteger.valueOf(max)) > 0) {
            return SmithyValidationErrors.valueTooLarge(path, number.toString(), max);
        }
        return null;
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
