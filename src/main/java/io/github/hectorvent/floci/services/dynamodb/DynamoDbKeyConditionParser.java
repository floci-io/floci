package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.AndExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.CompareExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.Expr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.PathOperand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.PlaceholderOperand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.TokenType;

import java.util.List;

/**
 * Narrow read-only view over a Query {@code KeyConditionExpression}: the scalar value the
 * partition key is pinned to by an equality condition.
 *
 * <p>Exists so IAM condition-key extraction can reuse the expression parser without making
 * the whole {@link DynamoDbAccessPathValidator} public. Everything here is best effort: a
 * malformed or unsupported expression yields {@code null}, and the caller treats that as
 * "the leading key could not be determined", which fails closed at the policy layer.
 */
final class DynamoDbKeyConditionParser {

    private DynamoDbKeyConditionParser() {}

    /**
     * @param keyConditionExpression the raw Query KeyConditionExpression
     * @param exprAttrNames          ExpressionAttributeNames, or {@code null}
     * @param exprAttrValues         ExpressionAttributeValues, or {@code null}
     * @param pkName                 the table's HASH key attribute name
     * @return the S / N / B scalar the partition key equals, or {@code null} when it cannot
     *         be resolved
     */
    static String partitionKeyEqualityValue(String keyConditionExpression,
                                            JsonNode exprAttrNames,
                                            JsonNode exprAttrValues,
                                            String pkName) {
        if (keyConditionExpression == null || keyConditionExpression.isBlank()
                || pkName == null || exprAttrValues == null) {
            return null;
        }
        Expr root;
        try {
            root = ExpressionEvaluator.parse(keyConditionExpression);
        } catch (RuntimeException e) {
            return null; // unparseable expression → leading key unknown → fail closed upstream
        }
        if (root == null) {
            return null;
        }
        List<Expr> conditions = root instanceof AndExpr and ? and.operands() : List.of(root);
        for (Expr condition : conditions) {
            if (!(condition instanceof CompareExpr compare)
                    || compare.op() != TokenType.EQ
                    || !(compare.right() instanceof PlaceholderOperand placeholder)) {
                continue;
            }
            if (!pkName.equals(topLevelAttribute(compare.left(), exprAttrNames))) {
                continue;
            }
            return scalarValue(exprAttrValues.get(placeholder.name()));
        }
        return null;
    }

    /** Resolves a single-segment path operand, following a {@code #alias} when one is used. */
    private static String topLevelAttribute(Object operand, JsonNode exprAttrNames) {
        if (!(operand instanceof PathOperand path) || path.segments().size() != 1) {
            return null;
        }
        String segment = path.segments().getFirst();
        if (segment.startsWith("#") && exprAttrNames != null && exprAttrNames.has(segment)) {
            return exprAttrNames.get(segment).asText();
        }
        return segment;
    }

    /** Unwraps an AttributeValue to its scalar text. Only S, N and B can be key values. */
    private static String scalarValue(JsonNode attributeValue) {
        if (attributeValue == null || !attributeValue.isObject()) {
            return null;
        }
        for (String type : List.of("S", "N", "B")) {
            JsonNode payload = attributeValue.get(type);
            if (payload != null && payload.isTextual()) {
                return payload.asText();
            }
        }
        return null;
    }
}
