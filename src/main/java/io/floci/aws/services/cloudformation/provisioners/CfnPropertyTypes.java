package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestShapeModel;
import io.floci.aws.core.common.RequestShapeModel.Shape;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CloudFormation's checks of a property value against the type the resource schema declares, with
 * the messages they give (measured in eu-west-1, 2026-10-18). The types come from the vendored
 * {@code aws/cfn-property-types.json}, generated from the published resource schemas by
 * {@code tools/aws/regen_cfn_property_types.py}.
 *
 * <p>Template validation runs over the whole template before any resource is touched and names the
 * property, or the list index, alone: "Property [Layers] expected type: JSONArray, found: String".
 * Where an object, a map or a list is expected, a number or a boolean reads as a String. Where text
 * is expected, any scalar is taken as its text. Where an integer is expected, a whole number or text
 * that spells one is taken, and anything else is named by what it is: Float (4.5 or "4.5"),
 * Boolean, String, JSONObject, JSONArray. Where a boolean is expected, only an object or a list is
 * refused there. A list of strings also takes every intrinsic that yields a list, as
 * {@link #readStringList} says. A number, and a value the schema leaves free (a free-form object,
 * several types at once), are taken as they are.
 *
 * <p>The resource handler's model validation runs after it, on the values CloudFormation converted,
 * and names the whole path: a boolean that is not true or false in any case, and an integer beyond
 * 32 bits, fail as "Model validation failed (#/Targets/0/EcsParameters/EnableECSManagedTags:
 * expected type: Boolean, found: String)". Some resource types get no template validation, and
 * their handler converts the values itself; {@link HandlerReader} reads those.
 */
final class CfnPropertyTypes {

    static final String RESOURCE_NAME = "aws/cfn-property-types.json";

    private static final String LIST = "JSONArray";
    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?\\d+");
    private static final Pattern SIGNED_WHOLE_NUMBER = Pattern.compile("[+-]?\\d+");
    private static final Set<String> TRUE_TEXT = Set.of("true", "True", "TRUE");
    private static final Set<String> FALSE_TEXT = Set.of("false", "False", "FALSE");
    private static final String INTEGER_RANGE_HINT = ". This may occur when a numeric value exceeds 32-bit"
            + " integer max (2147483647). Consider using 'number' type or ensure value fits in integer range.";

    private static final class Holder {
        static final RequestShapeModel MODEL = RequestShapeModel.load(RESOURCE_NAME, "CloudFormation property types");
    }

    private CfnPropertyTypes() {
    }

    /** The shape the resource schema of {@code resourceType} declares for its property {@code property}. */
    static String propertyShape(String resourceType, String property) {
        String properties = Holder.MODEL.operations().get(resourceType);
        String shape = properties == null ? null : Holder.MODEL.shapes().get(properties).members().get(property);
        if (shape == null) {
            throw notVendored(resourceType + "." + property);
        }
        return shape;
    }

    /** The shape the vendored shape {@code shapeName} declares for its member {@code member}. */
    static String memberShape(String shapeName, String member) {
        Shape owner = Holder.MODEL.shapes().get(shapeName);
        String shape = owner == null || owner.members() == null ? null : owner.members().get(member);
        if (shape == null) {
            throw notVendored(shapeName + "." + member);
        }
        return shape;
    }

    private static IllegalStateException notVendored(String what) {
        return new IllegalStateException(what + " is not in " + RESOURCE_NAME
                + "; add the type to tools/aws/regen_cfn_property_types.py");
    }

    /**
     * Refuses a value of another type than {@code expected}, an object or a list, with template
     * validation's message, where a number or a boolean reads as a String.
     */
    static void requireType(String property, JsonNode value, JsonNodeType expected) {
        if (value.getNodeType() != expected) {
            throw templateMismatch(property, expected == JsonNodeType.OBJECT ? "JSONObject" : LIST,
                    containerName(value));
        }
    }

    /**
     * The list-of-strings property written as {@code raw} in the template, or null when it is
     * {@code Ref: AWS::NoValue}, directly or through {@code Fn::If}. A literal of another type is
     * refused (see {@link #requireType}), and so is an intrinsic that yields a string (see
     * {@link CloudFormationTemplateEngine#yieldsString}). Any other intrinsic is read through
     * {@link CloudFormationTemplateEngine#resolveStringList}, as CloudFormation reads a {@code Ref}
     * to a {@code CommaDelimitedList} parameter, which the engine resolves to the comma-joined text,
     * and {@code Fn::Split} (measured, eu-west-1, 2026-10-18).
     */
    static List<String> readStringList(String property, JsonNode raw, CloudFormationTemplateEngine engine) {
        String found = listType(raw, engine);
        if (found == null) {
            return null;
        }
        if (!LIST.equals(found)) {
            throw templateMismatch(property, LIST, found);
        }
        return engine.resolveStringList(raw);
    }

    /**
     * What a list-of-strings property written as {@code raw} yields, as {@link #readStringList}
     * reads it: JSONArray for a list, null for {@code AWS::NoValue}, and otherwise the name of what
     * it yields instead.
     */
    private static String listType(JsonNode raw, CloudFormationTemplateEngine engine) {
        if (raw.isValueNode()) {
            return containerName(raw);
        }
        if (raw.isObject()) {
            JsonNode resolved = engine.resolveNodeOmittingNoValue(raw);
            if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
                return null;
            }
            if (resolved.isObject() || (!resolved.isArray() && engine.yieldsString(raw))) {
                return containerName(resolved);
            }
        }
        return LIST;
    }

    /**
     * A walk over template values against the shapes the resource schemas declare, resolving them
     * on the way. What {@code AWS::NoValue} stands for is left out, and members a shape does not
     * declare are resolved as they are. How a scalar converts, and what a value of the wrong type
     * does, is each reader's own.
     */
    private abstract static class ShapeWalk {

        final CloudFormationTemplateEngine engine;
        final RequestShapeModel model;

        ShapeWalk(CloudFormationTemplateEngine engine, RequestShapeModel model) {
            this.engine = engine;
            this.model = model;
        }

        /** A value of another type than {@code expected} at {@code path}, whose last name is {@code property}. */
        abstract void mismatch(String path, String property, String expected, String found);

        abstract JsonNode readInteger(String path, String property, JsonNode value);

        abstract JsonNode readBoolean(String path, String property, JsonNode value);

        /** Checks the members {@code shape} requires of the object {@code value} at {@code path}; none by default. */
        void checkRequired(String path, JsonNode value, Shape shape) {
        }

        /** The members of the object {@code value} in the order they are read: the template's by default. */
        Iterable<Map.Entry<String, JsonNode>> members(JsonNode value, Shape shape) {
            return value.properties();
        }

        JsonNode read(String path, String property, JsonNode raw, String shapeName) {
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return null;
            }
            Shape shape = model.shapes().get(shapeName);
            if ("list".equals(shape.type()) && "string".equals(model.shapes().get(shape.element()).type())) {
                return readStringList(path, property, raw);
            }
            JsonNode value = raw.isContainerNode() && !CloudFormationTemplateEngine.callsIntrinsic(raw)
                    ? raw : engine.resolveNode(raw);
            if (value == null || value.isNull() || value.isMissingNode()) {
                return null;
            }
            return switch (shape.type()) {
                case "structure" -> readObject(path, property, value, shape);
                case "map" -> readMap(path, property, value, shape.element());
                case "list" -> readList(path, property, value, shape.element());
                case "string" -> readString(path, property, value);
                case "integer" -> readInteger(path, property, value);
                case "boolean" -> readBoolean(path, property, value);
                default -> value == raw ? engine.resolveNode(raw) : value;
            };
        }

        private boolean expect(String path, String property, JsonNode value, JsonNodeType expected) {
            if (value.getNodeType() == expected) {
                return true;
            }
            mismatch(path, property, expected == JsonNodeType.OBJECT ? "JSONObject" : LIST, containerName(value));
            return false;
        }

        private JsonNode readObject(String path, String property, JsonNode value, Shape shape) {
            if (!expect(path, property, value, JsonNodeType.OBJECT)) {
                return null;
            }
            checkRequired(path, value, shape);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> member : members(value, shape)) {
                String memberShape = shape.members().get(member.getKey());
                JsonNode read = memberShape == null ? engine.resolveNode(member.getValue())
                        : read(child(path, member.getKey()), member.getKey(), member.getValue(), memberShape);
                if (read != null) {
                    out.set(member.getKey(), read);
                }
            }
            return out;
        }

        private JsonNode readMap(String path, String property, JsonNode value, String valueShape) {
            if (!expect(path, property, value, JsonNodeType.OBJECT)) {
                return null;
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                JsonNode read = read(child(path, entry.getKey()), entry.getKey(), entry.getValue(), valueShape);
                if (read != null) {
                    out.set(entry.getKey(), read);
                }
            }
            return out;
        }

        private JsonNode readList(String path, String property, JsonNode value, String itemShape) {
            if (!expect(path, property, value, JsonNodeType.ARRAY)) {
                return null;
            }
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (int i = 0; i < value.size(); i++) {
                JsonNode read = read(child(path, String.valueOf(i)), String.valueOf(i), value.get(i), itemShape);
                if (read != null) {
                    out.add(read);
                }
            }
            return out;
        }

        /**
         * A literal list keeps its blank entries where the template put them, as CloudFormation
         * passes it on, and an item that is an object or a list is refused by its index, as for any
         * string; any other value is read as {@link CfnPropertyTypes#readStringList} says.
         */
        private JsonNode readStringList(String path, String property, JsonNode raw) {
            List<String> values;
            if (raw.isArray()) {
                boolean strings = true;
                for (int i = 0; i < raw.size(); i++) {
                    JsonNode item = raw.get(i);
                    if (item.isContainerNode() && !CloudFormationTemplateEngine.callsIntrinsic(item)) {
                        mismatch(child(path, String.valueOf(i)), String.valueOf(i), "String", containerName(item));
                        strings = false;
                    }
                }
                values = strings ? engine.resolveLiteralList(raw) : null;
            } else {
                String found = listType(raw, engine);
                if (found != null && !LIST.equals(found)) {
                    mismatch(path, property, LIST, found);
                }
                values = LIST.equals(found) ? engine.resolveStringList(raw) : null;
            }
            if (values == null) {
                return null;
            }
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            values.forEach(out::add);
            return out;
        }

        private JsonNode readString(String path, String property, JsonNode value) {
            if (value.isContainerNode()) {
                mismatch(path, property, "String", containerName(value));
                return null;
            }
            return value.isTextual() ? value : TextNode.valueOf(value.asText());
        }

        static String child(String path, String name) {
            return path.isEmpty() ? name : path + "/" + name;
        }
    }

    /**
     * Reads template values against the shapes the resource schemas declare and resolves them,
     * converting them as CloudFormation does before the handler sees them: an integer or a boolean
     * given as text becomes one, and a number or a boolean where text is expected becomes text.
     * Template validation failures are thrown at once; the first model validation failure waits for
     * {@link #finish}, so every value read has passed template validation first, as on AWS.
     */
    static final class Reader extends ShapeWalk {

        private AwsException modelFailure;

        Reader(CloudFormationTemplateEngine engine) {
            this(engine, Holder.MODEL);
        }

        Reader(CloudFormationTemplateEngine engine, RequestShapeModel model) {
            super(engine, model);
        }

        /**
         * The template value {@code raw}, at {@code path} in the resource's properties, read as the
         * shape {@code shapeName}; null when it is absent or {@code AWS::NoValue}.
         */
        JsonNode read(String path, JsonNode raw, String shapeName) {
            return read(path, path.substring(path.lastIndexOf('/') + 1), engine.omitNoValue(raw), shapeName);
        }

        /** Throws the first model validation failure found by {@link #read}, if any. */
        void finish() {
            if (modelFailure != null) {
                throw modelFailure;
            }
        }

        @Override
        void mismatch(String path, String property, String expected, String found) {
            throw templateMismatch(property, expected, found);
        }

        @Override
        JsonNode readInteger(String path, String property, JsonNode value) {
            String text = value.isIntegralNumber() ? value.bigIntegerValue().toString() : value.asText();
            if (value.isContainerNode() || value.isBoolean() || !WHOLE_NUMBER.matcher(text).matches()) {
                throw templateMismatch(property, "Integer", scalarName(value));
            }
            BigDecimal number = new BigDecimal(text);
            if (number.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                    || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                recordModelFailure(path, "Integer", INTEGER_RANGE_HINT);
                return value;
            }
            return IntNode.valueOf(number.intValue());
        }

        @Override
        JsonNode readBoolean(String path, String property, JsonNode value) {
            if (value.isContainerNode()) {
                throw templateMismatch(property, "Boolean", containerName(value));
            }
            String text = value.asText();
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return BooleanNode.valueOf(Boolean.parseBoolean(text));
            }
            recordModelFailure(path, "Boolean", "");
            return value;
        }

        private void recordModelFailure(String path, String expected, String hint) {
            if (modelFailure == null) {
                modelFailure = modelValidation("#/" + path + ": expected type: " + expected + ", found: String"
                        + hint + ")");
            }
        }
    }

    /**
     * Reads a resource's properties as its handler does when CloudFormation hands them over without
     * template validation, every scalar as text, as it does an {@code AWS::ECS::TaskDefinition}'s
     * (measured in eu-west-1, 2026-10-19). The handler converts each value to the type its model
     * declares: an integer is a sign and digits once trimmed, within 32 bits, and a boolean is true
     * or false in lower, upper or title case once trimmed.
     *
     * <p>When a value does not convert, or an object, a list and a scalar stand for one another, the
     * resource fails with every violation of the unconverted model, where every scalar is a String,
     * so an integer or a boolean that converts is listed too: "Model validation failed
     * (#/ContainerDefinitions/0/Memory: expected type: Integer, found: String. This may occur ...
     * \n#/ContainerDefinitions/0/LinuxParameters/Capabilities/Add: expected type: JSONArray, found:
     * String)". At each object its missing required members come first, then its members in the
     * schema's order. When every value converts, only the required members the converted model lacks
     * fail it, grouped where their paths meet as the schema validator groups them: "Model validation
     * failed (#/ContainerDefinitions: 2 schema violations found)\n#/ContainerDefinitions/0: required
     * key [Name] not found (#/ContainerDefinitions/0)\n...".
     */
    static final class HandlerReader extends ShapeWalk {

        private final List<Violation> violations = new ArrayList<>();
        private boolean unconverted;

        HandlerReader(CloudFormationTemplateEngine engine) {
            this(engine, Holder.MODEL);
        }

        HandlerReader(CloudFormationTemplateEngine engine, RequestShapeModel model) {
            super(engine, model);
        }

        /** The properties {@code props} of a {@code resourceType}, resolved and converted as its handler reads them. */
        JsonNode read(String resourceType, JsonNode props) {
            String shape = model.operations().get(resourceType);
            if (shape == null) {
                throw notVendored(resourceType);
            }
            JsonNode raw = props == null || props.isNull() ? JsonNodeFactory.instance.objectNode() : props;
            JsonNode read = read("", "", engine.omitNoValue(raw), shape);
            if (unconverted) {
                throw modelValidation(String.join("\n", violations.stream().map(Violation::text).toList()) + ")");
            }
            List<Violation> missing = violations.stream().filter(Violation::required).toList();
            if (!missing.isEmpty()) {
                throw modelValidation(grouped(missing));
            }
            return read;
        }

        @Override
        void mismatch(String path, String property, String expected, String found) {
            record(new Violation(path, "expected type: " + expected + ", found: " + found, false), false);
        }

        @Override
        JsonNode readInteger(String path, String property, JsonNode value) {
            if (value.isContainerNode()) {
                mismatch(path, property, "Integer", containerName(value));
                return null;
            }
            String text = value.asText().trim();
            Integer number = null;
            if (SIGNED_WHOLE_NUMBER.matcher(text).matches()) {
                try {
                    number = Integer.parseInt(text);
                } catch (NumberFormatException beyond32Bits) {
                    number = null;
                }
            }
            record(new Violation(path, "expected type: Integer, found: String" + INTEGER_RANGE_HINT, false),
                    number != null);
            return number == null ? value : IntNode.valueOf(number);
        }

        @Override
        JsonNode readBoolean(String path, String property, JsonNode value) {
            if (value.isContainerNode()) {
                mismatch(path, property, "Boolean", containerName(value));
                return null;
            }
            String text = value.asText().trim();
            Boolean flag = TRUE_TEXT.contains(text) ? Boolean.TRUE : FALSE_TEXT.contains(text) ? Boolean.FALSE : null;
            record(new Violation(path, "expected type: Boolean, found: String", false), flag != null);
            return flag == null ? value : BooleanNode.valueOf(flag);
        }

        @Override
        void checkRequired(String path, JsonNode value, Shape shape) {
            for (String member : shape.required()) {
                if (!value.has(member)) {
                    record(new Violation(path, "required key [" + member + "] not found", true), true);
                }
            }
        }

        /** The members the shape declares in the schema's order, which the handler reports in, then the others. */
        @Override
        Iterable<Map.Entry<String, JsonNode>> members(JsonNode value, Shape shape) {
            Map<String, JsonNode> ordered = new LinkedHashMap<>();
            shape.members().keySet().forEach(name -> {
                if (value.has(name)) {
                    ordered.put(name, value.get(name));
                }
            });
            value.properties().forEach(member -> ordered.putIfAbsent(member.getKey(), member.getValue()));
            return ordered.entrySet();
        }

        private void record(Violation violation, boolean converts) {
            violations.add(violation);
            unconverted |= !converts;
        }

        /**
         * The violations {@code leaves}, one or several, as the schema validator reports them: several
         * are counted where their paths meet, and listed by that node's direct causes, a group of them
         * under one member counted where its own paths meet.
         */
        private static String grouped(List<Violation> leaves) {
            if (leaves.size() == 1) {
                return leaves.getFirst().text() + ")";
            }
            String meeting = meeting(leaves);
            StringBuilder message = new StringBuilder(pointer(meeting) + ": " + leaves.size()
                    + " schema violations found)");
            Map<String, List<Violation>> causes = new LinkedHashMap<>();
            for (Violation leaf : leaves) {
                String rest = leaf.path().equals(meeting) ? null
                        : leaf.path().substring(meeting.isEmpty() ? 0 : meeting.length() + 1);
                String cause = rest == null ? "#" + causes.size() : rest.split("/", 2)[0];
                causes.computeIfAbsent(cause, key -> new ArrayList<>()).add(leaf);
            }
            for (List<Violation> cause : causes.values()) {
                String at = cause.size() == 1 ? cause.getFirst().path() : meeting(cause);
                String text = cause.size() == 1 ? cause.getFirst().text()
                        : pointer(at) + ": " + cause.size() + " schema violations found";
                message.append('\n').append(text).append(" (").append(pointer(at)).append(')');
            }
            return message.toString();
        }

        /** The deepest path every one of {@code violations} lies under. */
        private static String meeting(List<Violation> violations) {
            String[] common = violations.getFirst().path().split("/");
            int length = violations.getFirst().path().isEmpty() ? 0 : common.length;
            for (Violation violation : violations) {
                String[] segments = violation.path().isEmpty() ? new String[0] : violation.path().split("/");
                int shared = 0;
                while (shared < Math.min(length, segments.length) && common[shared].equals(segments[shared])) {
                    shared++;
                }
                length = shared;
            }
            return String.join("/", List.of(common).subList(0, length));
        }

        private static String pointer(String path) {
            return path.isEmpty() ? "#" : "#/" + path;
        }

        /** One violation of the model at {@code path}, a missing required member or a type. */
        private record Violation(String path, String message, boolean required) {

            String text() {
                return pointer(path) + ": " + message;
            }
        }
    }

    /** The resource handler's model validation failure; {@code violations} ends with its closing parenthesis. */
    private static AwsException modelValidation(String violations) {
        return new AwsException("InvalidRequest", "Model validation failed (" + violations, 400);
    }

    private static AwsException templateMismatch(String property, String expected, String found) {
        return new AwsException("ValidationError", "Property [" + property + "] expected type: "
                + expected + ", found: " + found, 400);
    }

    private static String containerName(JsonNode value) {
        return switch (value.getNodeType()) {
            case OBJECT -> "JSONObject";
            case ARRAY -> LIST;
            default -> "String";
        };
    }

    /** What template validation calls a value that is not a whole number where an integer is expected. */
    private static String scalarName(JsonNode value) {
        if (value.isContainerNode()) {
            return containerName(value);
        }
        if (value.isBoolean()) {
            return "Boolean";
        }
        if (value.isNumber()) {
            return "Float";
        }
        try {
            new BigDecimal(value.asText());
            return "Float";
        } catch (NumberFormatException notANumber) {
            return "String";
        }
    }
}
