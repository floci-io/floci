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
 * <p>Whether the offending value is quoted beside the member is the service's own, so the check is
 * loaded with the form that service answers: IAM names the member and leaves the value out, STS
 * quotes it. That is DRIFT-13's reading, taken from the captures in hand and recorded in
 * {@code docs/services/iam.md}, and reading the constraints here is what makes it one object to
 * change rather than every message in a package.
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
 * <p>A map arrives as entries numbered under its own name, {@code Properties.entry.1.key} and
 * {@code Properties.entry.1.value}, or bare as {@code Properties.1.key}. An entry's value is named
 * under the key the entry carries, {@code properties.Team/Owner}, which is how the hand-written
 * checks these waves replace already named one and how {@link RequestConstraintCheck} names a map's
 * value on the JSON protocols. An entry numbered with no key of its own is no entry the map could
 * hold, so it is neither counted nor answered. A key outside its shape's rules is answered with the
 * whole set the shape declares, nested under the map's own name, which is the form AWS answers for
 * a map key everywhere this repository has one recorded.
 *
 * <p>Every one of those may hold any of the others: a structure's field may be a list, a list's
 * member a map, a map's value a structure. A shape of some other type is one this walk would pass
 * over without reading, so the file refuses to load rather than letting a request through
 * unchecked.
 */
public final class QueryConstraintCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shape types a Query parameter can carry on its own, without an index or an entry. */
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "long", "boolean");

    /** A number as the protocol writes one, of any width: the model's bounds are what hold it. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    private final JsonNode operations;
    private final JsonNode shapes;
    private final SmithyValidationErrors.Violations messages;
    private final Map<String, Pattern> patterns = new HashMap<>();

    private QueryConstraintCheck(JsonNode model, SmithyValidationErrors.Violations messages) {
        this.operations = model.path("operations");
        this.shapes = model.path("shapes");
        this.messages = messages;
        for (Map.Entry<String, JsonNode> shape : shapes.properties()) {
            if (shape.getValue().has("pattern")) {
                patterns.put(shape.getKey(), Pattern.compile(shape.getValue().get("pattern").asText()));
            }
        }
        for (Map.Entry<String, JsonNode> operation : operations.properties()) {
            requireReadableMembers(operation.getKey(), operation.getValue().asText());
        }
    }

    /**
     * The vendored resource {@code name}, answering in the form most services do, with the
     * offending value quoted; {@code description} names it in a failure.
     */
    public static QueryConstraintCheck load(String name, String description) {
        return load(name, description, SmithyValidationErrors.Violations.QUOTED);
    }

    /** The same, in the message form {@code messages} writes, which is the service's own. */
    public static QueryConstraintCheck load(String name, String description,
                                            SmithyValidationErrors.Violations messages) {
        return VendoredResources.read(name, description,
                input -> new QueryConstraintCheck(MAPPER.readTree(input), messages));
    }

    public static QueryConstraintCheck of(JsonNode model) {
        return of(model, SmithyValidationErrors.Violations.QUOTED);
    }

    public static QueryConstraintCheck of(JsonNode model, SmithyValidationErrors.Violations messages) {
        return new QueryConstraintCheck(model, messages);
    }

    private void requireReadableMembers(String operation, String inputShape) {
        for (Map.Entry<String, JsonNode> member : shapes.path(inputShape).path("members").properties()) {
            requireReadable(operation, member.getKey(), member.getValue().asText(), new LinkedHashSet<>());
        }
    }

    /**
     * Every member of every operation the file carries is read, whatever it nests. {@code seen}
     * carries the shapes on the way down, so a shape that holds itself is read once rather than
     * forever.
     */
    private void requireReadable(String operation, String member, String shapeName, Set<String> seen) {
        JsonNode shape = shapes.path(shapeName);
        String type = shape.path("type").asText();
        if (SCALAR_TYPES.contains(type) || !seen.add(shapeName)) {
            return;
        }
        switch (type) {
            case "list" -> requireReadable(operation, member, shape.path("member").asText(), seen);
            case "map" -> {
                requireReadable(operation, member + ".key", shape.path("key").asText(), seen);
                requireReadable(operation, member + ".value", shape.path("value").asText(), seen);
            }
            case "structure" -> {
                for (Map.Entry<String, JsonNode> field : shape.path("members").properties()) {
                    requireReadable(operation, member + "." + field.getKey(),
                            field.getValue().asText(), seen);
                }
            }
            default -> throw refusal(operation, member, type);
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
        return violations(operation, params, Map.of());
    }

    /**
     * The same, with each violation of {@code documented} reported in its own member's place: a
     * constraint the service documents where the model declares none, keyed by the member's name as
     * the model spells it. It is a reading rather than a published shape, so it is reported where
     * that member's own violations would sit, and only for a member the request carries that breaks
     * none of the constraints the model declares. A value a declared constraint already refuses is
     * not read against a documented set as well, which is how the hand-written checks these waves
     * replace answered one, and a member that never arrived has no value to read against anything.
     */
    public List<String> violations(String operation, MultivaluedMap<String, String> params,
                                   Map<String, String> documented) {
        JsonNode shape = shapes.path(operations.path(operation).asText());
        if (!shape.hasNonNull("members")) {
            return List.of();
        }
        return structureViolations(shape, "", "", params, documented);
    }

    /**
     * The violations the members of {@code structure} carry, in the order the model lists them.
     * {@code prefix} is what the request names them under and {@code path} what a message calls
     * them: both are empty at the top level, and name the member above inside a structure or inside
     * a list's member. {@code documented} carries the violations the service documents rather than
     * declares, which only the operation's own members have: a nested walk is handed none.
     */
    private List<String> structureViolations(JsonNode structure, String prefix, String path,
                                             MultivaluedMap<String, String> params,
                                             Map<String, String> documented) {
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
            List<String> own = memberViolations(shapeName, param, memberPath, mandatory, params);
            violations.addAll(own);
            if (own.isEmpty() && documented.containsKey(name) && carries(params, param)) {
                violations.add(documented.get(name));
            }
        }
        return violations;
    }

    /**
     * True when the request names {@code param} itself or anything under it, whatever kind of
     * member it is: a scalar at its own name, a list numbered under it, a structure's fields below
     * it. An absent member is answered for being required or for nothing at all, so it is not also
     * answered for a constraint read off a value it never sent.
     */
    private static boolean carries(MultivaluedMap<String, String> params, String param) {
        return params != null && (params.containsKey(param)
                || params.keySet().stream().anyMatch(key -> key.startsWith(param + ".")));
    }

    /**
     * One member, whatever its shape nests: a scalar at its own name, a list or a map numbered
     * under it, a structure's fields below it.
     */
    private List<String> memberViolations(String shapeName, String param, String path,
                                          boolean required, MultivaluedMap<String, String> params) {
        JsonNode shape = shapes.path(shapeName);
        return switch (shape.path("type").asText()) {
            case "list" -> listViolations(shape, param, path, required, params);
            case "map" -> mapViolations(shape, param, path, required, params);
            case "structure" -> nestedViolations(shape, param, path, required, params);
            default -> scalarViolations(shapeName, param, path, required, params);
        };
    }

    /**
     * A map's entries, held to the map's own bounds and then each key and value to its shape. An
     * entry arrives as {@code Properties.entry.1.key} and {@code Properties.entry.1.value}, and
     * the value is named under the key the entry carries. An entry is read off that key, as the
     * count of one has always been here, so one numbered with no key is not an entry the map could
     * hold: it is neither counted nor answered.
     */
    private List<String> mapViolations(JsonNode shape, String param, String path, boolean required,
                                       MultivaluedMap<String, String> params) {
        List<AwsQueryLists.IndexedMember> entries = params == null
                ? List.of()
                : AwsQueryLists.members(params, param, "entry").stream()
                        .filter(entry -> params.getFirst(entry.prefix() + ".key") != null)
                        .toList();
        if (entries.isEmpty()) {
            return required ? List.of(SmithyValidationErrors.notNull(path)) : List.of();
        }
        String valueShape = shape.path("value").asText();
        boolean structure = "structure".equals(shapes.path(valueShape).path("type").asText());
        List<String> violations = new ArrayList<>();
        violations.addAll(single(SmithyValidationErrors.sizeOutOfRange(path, entries.size(),
                shape.path("min").asInt(0), shape.path("max").asInt(Integer.MAX_VALUE))));
        for (AwsQueryLists.IndexedMember entry : entries) {
            String key = params.getFirst(entry.prefix() + ".key");
            violations.addAll(keyViolations(shape.path("key").asText(), key, path));
            String valueParam = entry.prefix() + ".value";
            String valuePath = path + "." + key;
            if (structure) {
                // An entry the request numbered with a key is an entry, so what the structure
                // under it requires is answered inside it rather than the value reading as absent.
                violations.addAll(structureViolations(shapes.path(valueShape), valueParam + ".",
                        valuePath + ".", params, Map.of()));
            } else {
                violations.addAll(memberViolations(valueShape, valueParam, valuePath, true, params));
            }
        }
        return violations;
    }

    /**
     * A map key outside its shape's rules, answered with the whole set that shape declares rather
     * than with the one rule the key broke. Which rules a key breaks is read the way every other
     * value is, so one object decides whether a key is good and another how a map words it.
     */
    private List<String> keyViolations(String shapeName, String key, String path) {
        if (violations(shapeName, key, path).isEmpty()) {
            return List.of();
        }
        JsonNode shape = shapes.path(shapeName);
        return List.of(messages.mapKeys(path, key, SmithyValidationErrors.constraintSet(
                shape.path("min").asInt(0), shape.path("max").asInt(Integer.MAX_VALUE),
                shape.path("pattern").asText(null), allowedValues(shape))));
    }

    /**
     * A member one parameter carries. A request naming it at all carries a value, so only one it
     * names nowhere is absent, and a required member is what an absent one is reported for.
     */
    private List<String> scalarViolations(String shapeName, String param, String path,
                                         boolean required, MultivaluedMap<String, String> params) {
        String value = params == null ? null : params.getFirst(param);
        if (value == null) {
            return required ? List.of(SmithyValidationErrors.notNull(path)) : List.of();
        }
        return violations(shapeName, value, path);
    }

    /**
     * A list's members, held to the list's own bounds and then each to its element's. A list of no
     * members is how an absent list arrives, which is what makes a minimum of 1 unreachable: only a
     * list asking for two or more members can report one.
     */
    private List<String> listViolations(JsonNode shape, String param, String path, boolean required,
                                        MultivaluedMap<String, String> params) {
        String elementShape = shape.path("member").asText();
        boolean scalar = SCALAR_TYPES.contains(shapes.path(elementShape).path("type").asText());
        // A scalar member's value lives at its own name, so an index the request numbered only
        // under a deeper key carries no value and is no member of the list: nothing reads one
        // there either. A member present and empty is a member all the same.
        List<AwsQueryLists.IndexedMember> members = params == null
                ? List.of()
                : AwsQueryLists.members(params, param, null).stream()
                        .filter(member -> !scalar || params.getFirst(member.prefix()) != null)
                        .toList();
        if (members.isEmpty()) {
            return required ? List.of(SmithyValidationErrors.notNull(path)) : List.of();
        }
        List<String> violations = new ArrayList<>();
        violations.addAll(single(SmithyValidationErrors.sizeOutOfRange(path, members.size(),
                shape.path("min").asInt(0), shape.path("max").asInt(Integer.MAX_VALUE))));
        for (AwsQueryLists.IndexedMember member : members) {
            String memberPath = path + "." + member.index() + ".member";
            if (scalar) {
                violations.addAll(violations(elementShape, params.getFirst(member.prefix()),
                        memberPath));
            } else {
                violations.addAll(memberViolations(elementShape, member.prefix(), memberPath,
                        false, params));
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
        return structureViolations(shape, param + ".", path + ".", params, Map.of());
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
        String length = messages.lengthOutOfRange(path, value,
                shape.path("min").asInt(0), shape.path("max").asInt(Integer.MAX_VALUE));
        if (length != null) {
            violations.add(length);
        }
        Pattern pattern = patterns.get(shapeName);
        if (pattern != null && !pattern.matcher(value).matches()) {
            violations.add(messages.pattern(path, value, pattern.pattern()));
        }
        violations.addAll(single(enumViolation(shape, value, path)));
        return violations;
    }

    private static List<String> single(String violation) {
        return violation == null ? List.of() : List.of(violation);
    }

    /**
     * True when {@code value} is a number as the protocol writes one, of any width: the model's
     * bounds are what hold it, so a digit string that overflows a {@code long} is a number all the
     * same. An operation answering a value that is no number at all asks this rather than keeping a
     * reading of its own, since the same answer decides whether a range applies.
     */
    public static boolean isInteger(String value) {
        return value != null && INTEGER.matcher(value.trim()).matches();
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
    private String numberViolation(JsonNode shape, String value, String path) {
        String text = value.trim();
        if (!isInteger(text)) {
            // Not a number at all, so no range of the model's is what it breaks.
            return null;
        }
        BigInteger number = new BigInteger(text);
        long min = shape.path("min").asLong(Long.MIN_VALUE);
        long max = shape.path("max").asLong(Long.MAX_VALUE);
        if (number.compareTo(BigInteger.valueOf(min)) < 0) {
            return messages.valueTooSmall(path, number.toString(), min);
        }
        if (number.compareTo(BigInteger.valueOf(max)) > 0) {
            return messages.valueTooLarge(path, number.toString(), max);
        }
        return null;
    }

    private String enumViolation(JsonNode shape, String value, String path) {
        List<String> allowed = allowedValues(shape);
        if (allowed.isEmpty()) {
            return null;
        }
        return allowed.contains(value) ? null : messages.notInEnum(value, path, allowed);
    }

    /** The values a shape's enum admits, in the model's order, or none for a shape without one. */
    private static List<String> allowedValues(JsonNode shape) {
        List<String> allowed = new ArrayList<>();
        shape.path("enum").forEach(choice -> allowed.add(choice.asText()));
        return allowed;
    }
}
