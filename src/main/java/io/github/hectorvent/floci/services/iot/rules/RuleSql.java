package io.github.hectorvent.floci.services.iot.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * A parsed AWS IoT topic rule statement: the {@code SELECT} list, the {@code FROM} topic
 * filter, and the optional {@code WHERE} predicate. Produced by {@link RuleSqlParser} and
 * consumed by {@link RuleSqlEvaluator}.
 */
public record RuleSql(List<Projection> projections, String topicFilter, Expr where) {

    public RuleSql {
        projections = List.copyOf(projections);
    }

    /**
     * True when {@code *} is the only select item. Such a rule forwards the published bytes
     * unchanged instead of rebuilding a document, so the payload keeps its exact formatting
     * and, when there is no {@code WHERE}, does not have to be JSON at all.
     */
    public boolean isSelectAllOnly() {
        return projections.size() == 1 && projections.getFirst() instanceof SelectAll;
    }

    /**
     * The result of parsing a rule's SQL once. {@code query} is null when the statement is
     * outside the subset Floci evaluates. Such a rule keeps the behaviour it had before this
     * parser existed: it fires on every publish matching its topic filter and its actions
     * receive the whole payload.
     */
    public record Compilation(RuleSql query) {

        public static final Compilation PASSTHROUGH = new Compilation(null);

        public static Compilation of(RuleSql query) {
            return new Compilation(query);
        }
    }

    public sealed interface Projection permits SelectAll, Aliased {
    }

    /** The {@code *} select item: every field of the payload. */
    public record SelectAll() implements Projection {
    }

    /** A select item written under {@code alias} in the projected document. */
    public record Aliased(Expr expression, String alias) implements Projection {
    }

    public sealed interface Expr permits Path, Literal, Call, Comparison, Conjunction, Disjunction, Negation {
    }

    /** A dotted field path such as {@code state.reported.temperature}. */
    public record Path(List<String> segments) implements Expr {
        public Path {
            segments = List.copyOf(segments);
        }
    }

    public record Literal(JsonNode value) implements Expr {
    }

    /** A supported function call: {@code topic}, {@code startswith} or {@code endswith}. */
    public record Call(String function, List<Expr> arguments) implements Expr {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    public record Comparison(Expr left, Operator operator, Expr right) implements Expr {
    }

    public record Conjunction(Expr left, Expr right) implements Expr {
    }

    public record Disjunction(Expr left, Expr right) implements Expr {
    }

    public record Negation(Expr operand) implements Expr {
    }

    public enum Operator {
        EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL
    }

    public static Literal text(String value) {
        return new Literal(TextNode.valueOf(value));
    }

    public static Literal number(long value) {
        return new Literal(LongNode.valueOf(value));
    }

    /** Kept as a {@code BigDecimal} so a literal a double cannot hold exactly still compares exactly. */
    public static Literal decimal(BigDecimal value) {
        return new Literal(DecimalNode.valueOf(value));
    }

    public static Literal bool(boolean value) {
        return new Literal(BooleanNode.valueOf(value));
    }

    public static Literal nullValue() {
        return new Literal(NullNode.getInstance());
    }
}
