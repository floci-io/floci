package io.github.hectorvent.floci.services.iot.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Aliased;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.ArrayLiteral;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Call;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Comparison;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Conjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Disjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Expr;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Literal;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Membership;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Negation;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Operator;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Path;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Projection;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.SelectAll;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Evaluates a parsed topic rule statement against one published message, following the
 * operator, function and conversion tables of the AWS IoT SQL reference.
 *
 * <p>A value AWS reports as {@code Undefined} (a missing field, an out of range topic segment,
 * a function argument that cannot be converted) is a {@code null} {@link JsonNode}. It spreads:
 * a comparison, {@code IN}, {@code AND}, {@code OR} or {@code NOT} with an undefined operand is
 * undefined, and a rule fires only when its {@code WHERE} is true. JSON {@code null} is not
 * undefined: it is a value that equals only itself.
 *
 * <p>Payload numbers are read as {@code BigDecimal}, never through a {@code double}, so they
 * compare exactly at any size or precision, as AWS's Decimal does.
 */
public final class RuleSqlEvaluator {

    private static final Logger LOG = Logger.getLogger(RuleSqlEvaluator.class);

    /** The string forms AWS converts to a number when an ordering operator meets a string. */
    private static final Pattern NUMERIC_STRING = Pattern.compile("-?\\d+(\\.\\d+)?([eE]-?\\d+)?");

    /** What {@code clientid()} returns for a message that did not arrive over MQTT, as AWS documents. */
    private static final String NO_MQTT_CLIENT = "n/a";

    private final ObjectMapper objectMapper;
    private final ObjectReader payloadReader;
    private final Clock clock;

    public RuleSqlEvaluator(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.payloadReader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonNodeFactory.withExactBigDecimals(true));
        this.clock = clock;
    }

    /**
     * Returns the document the rule's actions should receive, or empty when the rule does
     * not fire for this message.
     */
    public Optional<byte[]> evaluate(String ruleName, RuleSql query, RuleSqlContext context, byte[] payload) {
        byte[] message = payload == null ? new byte[0] : payload;
        if (query.isSelectAllOnly() && query.where() == null) {
            return Optional.of(message);
        }
        ObjectNode document = readObject(message);
        if (document == null) {
            LOG.debugv("Topic rule {0} skipped a payload that is not a JSON object on topic {1}",
                    ruleName, context.topic());
            return Optional.empty();
        }
        if (query.where() != null && !Boolean.TRUE.equals(truth(value(query.where(), context, document)))) {
            return Optional.empty();
        }
        return Optional.of(query.isSelectAllOnly() ? message : project(query, context, document));
    }

    private ObjectNode readObject(byte[] payload) {
        if (payload.length == 0) {
            return null;
        }
        try {
            JsonNode document = payloadReader.readTree(payload);
            return document instanceof ObjectNode object ? object : null;
        } catch (IOException e) {
            LOG.debugv(e, "Topic rule payload is not JSON");
            return null;
        }
    }

    /**
     * Builds the projected document. Every payload field is copied first when {@code *} is
     * present, so a select item whose alias collides with a payload field overwrites it,
     * which is what AWS does for statements such as {@code SELECT *, topic() as topic}.
     */
    private byte[] project(RuleSql query, RuleSqlContext context, ObjectNode document) {
        ObjectNode projected = objectMapper.createObjectNode();
        if (query.projections().stream().anyMatch(SelectAll.class::isInstance)) {
            projected.setAll(document);
        }
        for (Projection projection : query.projections()) {
            if (projection instanceof Aliased aliased) {
                JsonNode value = value(aliased.expression(), context, document);
                if (value != null) {
                    projected.set(aliased.alias(), value);
                }
            }
        }
        return projected.toString().getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode value(Expr expression, RuleSqlContext context, ObjectNode document) {
        return switch (expression) {
            case Literal literal -> literal.value();
            case Path path -> resolve(path, document);
            case Call call -> call(call, context, document);
            case ArrayLiteral array -> array(array, context, document);
            case Comparison comparison -> compare(comparison, context, document);
            case Membership membership -> contains(
                    value(membership.element(), context, document),
                    value(membership.array(), context, document));
            case Conjunction conjunction -> booleanNode(and(
                    truth(value(conjunction.left(), context, document)),
                    truth(value(conjunction.right(), context, document))));
            case Disjunction disjunction -> booleanNode(or(
                    truth(value(disjunction.left(), context, document)),
                    truth(value(disjunction.right(), context, document))));
            case Negation negation -> {
                Boolean operand = truth(value(negation.operand(), context, document));
                yield operand == null ? null : booleanNode(!operand);
            }
        };
    }

    private JsonNode resolve(Path path, ObjectNode document) {
        JsonNode current = document;
        for (String segment : path.segments()) {
            if (!(current instanceof ObjectNode object)) {
                return null;
            }
            current = object.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private JsonNode call(Call call, RuleSqlContext context, ObjectNode document) {
        return switch (call.function()) {
            case "topic" -> topic(call, context);
            case "clientid" -> TextNode.valueOf(context.clientId() == null ? NO_MQTT_CLIENT : context.clientId());
            case "timestamp" -> LongNode.valueOf(clock.millis());
            case "accountid" -> TextNode.valueOf(context.accountId());
            case "newuuid" -> TextNode.valueOf(UUID.randomUUID().toString());
            case "isNull" -> {
                JsonNode argument = value(call.arguments().getFirst(), context, document);
                yield BooleanNode.valueOf(argument != null && argument.isNull());
            }
            case "isUndefined" -> BooleanNode.valueOf(value(call.arguments().getFirst(), context, document) == null);
            default -> stringPredicate(call, context, document);
        };
    }

    private JsonNode topic(Call call, RuleSqlContext context) {
        if (call.arguments().isEmpty()) {
            return TextNode.valueOf(context.topic());
        }
        if (!(call.arguments().getFirst() instanceof Literal position)) {
            return null;
        }
        String[] segments = context.topic().split("/", -1);
        long index = position.value().asLong();
        return index < 1 || index > segments.length ? null : TextNode.valueOf(segments[(int) index - 1]);
    }

    private JsonNode stringPredicate(Call call, RuleSqlContext context, ObjectNode document) {
        String subject = text(value(call.arguments().get(0), context, document));
        String argument = text(value(call.arguments().get(1), context, document));
        if (subject == null || argument == null) {
            return null;
        }
        return booleanNode(call.function().equals("startswith")
                ? subject.startsWith(argument)
                : subject.endsWith(argument));
    }

    /** An array literal is undefined as a whole when any element is, so a position is never silently dropped. */
    private JsonNode array(ArrayLiteral literal, RuleSqlContext context, ObjectNode document) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Expr element : literal.elements()) {
            JsonNode value = value(element, context, document);
            if (value == null) {
                return null;
            }
            array.add(value);
        }
        return array;
    }

    /**
     * AWS's standard conversion to String: numbers, booleans, arrays and objects convert,
     * {@code null} and Undefined do not.
     */
    private String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        return value.isContainerNode() ? value.toString() : value.asText();
    }

    private JsonNode compare(Comparison comparison, RuleSqlContext context, ObjectNode document) {
        JsonNode left = value(comparison.left(), context, document);
        JsonNode right = value(comparison.right(), context, document);
        if (left == null || right == null) {
            return null;
        }
        Operator operator = comparison.operator();
        if (operator == Operator.EQUAL || operator == Operator.NOT_EQUAL) {
            return booleanNode(equal(left, right) == (operator == Operator.EQUAL));
        }
        BigDecimal leftNumber = decimal(left);
        BigDecimal rightNumber = decimal(right);
        return leftNumber == null || rightNumber == null
                ? null
                : booleanNode(matches(operator, leftNumber.compareTo(rightNumber)));
    }

    /** {@code IN} needs a defined element and an array; it is then a plain search with the equality rules. */
    private JsonNode contains(JsonNode element, JsonNode array) {
        if (element == null || array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode candidate : array) {
            if (equal(element, candidate)) {
                return BooleanNode.TRUE;
            }
        }
        return BooleanNode.FALSE;
    }

    /**
     * AWS compares two numbers by value, arrays element by element in order, objects by key
     * regardless of order, and everything else by type and value, so operands of different
     * types are simply not equal and {@code null} equals only {@code null}.
     */
    private boolean equal(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!equal(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> property : left.properties()) {
                JsonNode other = right.get(property.getKey());
                if (other == null || !equal(property.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        return left.getNodeType() == right.getNodeType() && left.equals(right);
    }

    /**
     * The ordering operators convert both operands to a number: numbers as they are, strings
     * when they look like a number, anything else is Undefined. A string whose exponent is
     * beyond what a BigDecimal can hold looks like a number but is not one either.
     */
    private BigDecimal decimal(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual() && NUMERIC_STRING.matcher(value.textValue()).matches()) {
            try {
                return new BigDecimal(value.textValue());
            } catch (NumberFormatException e) {
                LOG.debugv("Numeric string {0} is out of range and evaluates as Undefined", value.textValue());
                return null;
            }
        }
        return null;
    }

    private boolean matches(Operator operator, int comparison) {
        return switch (operator) {
            case EQUAL -> comparison == 0;
            case NOT_EQUAL -> comparison != 0;
            case LESS -> comparison < 0;
            case LESS_OR_EQUAL -> comparison <= 0;
            case GREATER -> comparison > 0;
            case GREATER_OR_EQUAL -> comparison >= 0;
        };
    }

    /** AWS gives Undefined for {@code AND} and {@code OR} unless both operands convert to a boolean. */
    private Boolean and(Boolean left, Boolean right) {
        return left == null || right == null ? null : left && right;
    }

    private Boolean or(Boolean left, Boolean right) {
        return left == null || right == null ? null : left || right;
    }

    /** A boolean, or the strings {@code "true"} and {@code "false"} in any case; anything else is Undefined. */
    private Boolean truth(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isTextual()) {
            if (value.textValue().equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (value.textValue().equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private JsonNode booleanNode(Boolean value) {
        return value == null ? null : BooleanNode.valueOf(value);
    }
}
