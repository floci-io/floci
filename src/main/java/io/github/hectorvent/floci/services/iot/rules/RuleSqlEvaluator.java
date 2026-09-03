package io.github.hectorvent.floci.services.iot.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Aliased;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Call;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Comparison;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Conjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Disjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Expr;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Literal;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Negation;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Operator;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Path;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Projection;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.SelectAll;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Evaluates a parsed topic rule statement against one published message.
 *
 * <p>A value that AWS reports as {@code Undefined} (a missing field, an out of range topic
 * segment, or an operand of the wrong type) is represented by a {@code null} {@link JsonNode}.
 * Every comparison against it yields {@code Undefined}, {@code AND}/{@code OR}/{@code NOT}
 * propagate it as SQL three-valued logic, and a rule fires only when its {@code WHERE}
 * evaluates to true, so an undefined predicate never triggers an action.
 */
public final class RuleSqlEvaluator {

    private static final Logger LOG = Logger.getLogger(RuleSqlEvaluator.class);

    private final ObjectMapper objectMapper;

    public RuleSqlEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the document the rule's actions should receive, or empty when the rule does
     * not fire for this message.
     */
    public Optional<byte[]> evaluate(String ruleName, RuleSql query, String topic, byte[] payload) {
        byte[] message = payload == null ? new byte[0] : payload;
        if (query.isSelectAllOnly() && query.where() == null) {
            return Optional.of(message);
        }
        ObjectNode document = readObject(message);
        if (document == null) {
            LOG.debugv("Topic rule {0} skipped a payload that is not a JSON object on topic {1}", ruleName, topic);
            return Optional.empty();
        }
        if (query.where() != null && !Boolean.TRUE.equals(truth(value(query.where(), topic, document)))) {
            return Optional.empty();
        }
        return Optional.of(query.isSelectAllOnly() ? message : project(query, topic, document));
    }

    private ObjectNode readObject(byte[] payload) {
        if (payload.length == 0) {
            return null;
        }
        try {
            JsonNode document = objectMapper.readTree(payload);
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
    private byte[] project(RuleSql query, String topic, ObjectNode document) {
        ObjectNode projected = objectMapper.createObjectNode();
        if (query.projections().stream().anyMatch(SelectAll.class::isInstance)) {
            projected.setAll(document);
        }
        for (Projection projection : query.projections()) {
            if (projection instanceof Aliased aliased) {
                JsonNode value = value(aliased.expression(), topic, document);
                if (value != null) {
                    projected.set(aliased.alias(), value);
                }
            }
        }
        return projected.toString().getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode value(Expr expression, String topic, ObjectNode document) {
        return switch (expression) {
            case Literal literal -> literal.value();
            case Path path -> resolve(path, document);
            case Call call -> call(call, topic, document);
            case Comparison comparison -> compare(comparison, topic, document);
            case Conjunction conjunction -> booleanNode(and(
                    truth(value(conjunction.left(), topic, document)),
                    truth(value(conjunction.right(), topic, document))));
            case Disjunction disjunction -> booleanNode(or(
                    truth(value(disjunction.left(), topic, document)),
                    truth(value(disjunction.right(), topic, document))));
            case Negation negation -> {
                Boolean operand = truth(value(negation.operand(), topic, document));
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

    private JsonNode call(Call call, String topic, ObjectNode document) {
        if (call.function().equals("topic")) {
            if (call.arguments().isEmpty()) {
                return TextNode.valueOf(topic);
            }
            return call.arguments().getFirst() instanceof Literal position
                    ? topicSegment(topic, position.value().asLong())
                    : null;
        }
        JsonNode subject = value(call.arguments().get(0), topic, document);
        JsonNode argument = value(call.arguments().get(1), topic, document);
        if (subject == null || !subject.isTextual() || argument == null || !argument.isTextual()) {
            return null;
        }
        return booleanNode(call.function().equals("startswith")
                ? subject.textValue().startsWith(argument.textValue())
                : subject.textValue().endsWith(argument.textValue()));
    }

    private JsonNode topicSegment(String topic, long position) {
        String[] segments = topic.split("/", -1);
        return position < 1 || position > segments.length ? null : TextNode.valueOf(segments[(int) position - 1]);
    }

    private JsonNode compare(Comparison comparison, String topic, ObjectNode document) {
        JsonNode left = value(comparison.left(), topic, document);
        JsonNode right = value(comparison.right(), topic, document);
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return null;
        }
        if (left.isNumber() && right.isNumber()) {
            return representable(left) && representable(right)
                    ? booleanNode(matches(comparison.operator(), left.decimalValue().compareTo(right.decimalValue())))
                    : null;
        }
        return equality(comparison.operator(), left, right);
    }

    /**
     * Numbers are compared exactly, so a value wider than a long or a double still orders
     * correctly. A JSON number too large for a double reaches us as an infinity, which has no
     * exact value left to compare: it is Undefined, like any other operand the rule cannot
     * evaluate.
     */
    private boolean representable(JsonNode value) {
        return !(value.isDouble() || value.isFloat()) || Double.isFinite(value.doubleValue());
    }

    /**
     * Ordering operators are numeric only, and equality holds only between operands of the
     * same JSON type. Anything else is a type mismatch, which AWS reports as Undefined.
     */
    private JsonNode equality(Operator operator, JsonNode left, JsonNode right) {
        if (operator != Operator.EQUAL && operator != Operator.NOT_EQUAL) {
            return null;
        }
        boolean comparable = (left.isTextual() && right.isTextual()) || (left.isBoolean() && right.isBoolean());
        if (!comparable) {
            return null;
        }
        return booleanNode(left.equals(right) == (operator == Operator.EQUAL));
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

    private Boolean and(Boolean left, Boolean right) {
        if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
            return Boolean.FALSE;
        }
        return left == null || right == null ? null : Boolean.TRUE;
    }

    private Boolean or(Boolean left, Boolean right) {
        if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
            return Boolean.TRUE;
        }
        return left == null || right == null ? null : Boolean.FALSE;
    }

    private Boolean truth(JsonNode value) {
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private JsonNode booleanNode(Boolean value) {
        return value == null ? null : BooleanNode.valueOf(value);
    }
}
