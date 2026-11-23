package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.dynamodb.model.VectorIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The read path of a vector index: SearchConditionExpression validation, candidate filtering,
 * scoring, top-K and result projection.
 *
 * <p>No vector index is materialized, the way no secondary index is materialized anywhere else in
 * Floci. A search reads the table's items and drops the ones the index does not hold.
 */
final class DynamoDbVectorSearch {

    private DynamoDbVectorSearch() {}

    /** One search result: the projected item and its distance score. */
    record Hit(ObjectNode item, double score) {}

    /** A scored candidate, before the top-K cut decides whether it is worth projecting. */
    private record Candidate(JsonNode item, double score) {}

    static List<Hit> search(TableDefinition table, VectorIndex index, List<JsonNode> items,
                            JsonNode searchVector, int topK, String searchConditionExpression,
                            JsonNode exprAttrNames, JsonNode exprAttrValues,
                            String projectionExpression) {
        float[] query = parseSearchVector(searchVector, index);
        ExpressionEvaluator.Expr condition = parseSearchCondition(table, index, searchConditionExpression,
                exprAttrNames, exprAttrValues);
        ProjectionEvaluator.validateExpression(projectionExpression, exprAttrNames);

        String hashAttribute = index.getHashAttributeName();
        String distanceFunction = index.getDistanceFunction();
        DynamoDbVectorScoring.Scorer scorer =
                new DynamoDbVectorScoring.Scorer(distanceFunction, query);
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode item : items) {
            if (hashAttribute != null && !item.hasNonNull(hashAttribute)) {
                continue;
            }
            if (!ExpressionEvaluator.evaluate(condition, item, exprAttrNames, exprAttrValues)) {
                continue;
            }
            float[] stored = storedVector(item, index);
            if (stored == null) {
                continue;
            }
            candidates.add(new Candidate(item, scorer.score(stored)));
        }

        Comparator<Candidate> byScore = Comparator.comparingDouble(Candidate::score);
        candidates.sort(DynamoDbVectorScoring.higherIsCloser(distanceFunction)
                ? byScore.reversed() : byScore);

        List<Hit> hits = new ArrayList<>();
        List<Candidate> top = candidates.subList(0, Math.min(topK, candidates.size()));
        if (top.isEmpty()) {
            return hits;
        }
        Set<String> projectedAttributes = projectedAttributeNames(table, index);
        boolean rendersVector = projectionExpression != null && !projectionExpression.isBlank()
                && ProjectionEvaluator.topLevelAttributes(projectionExpression, exprAttrNames)
                        .contains(index.getVectorAttributeName());
        for (Candidate candidate : top) {
            hits.add(new Hit(projectHit(candidate.item(), index, projectedAttributes,
                    projectionExpression, exprAttrNames, rendersVector), candidate.score()));
        }
        return hits;
    }

    // ── Search vector ──

    private static float[] parseSearchVector(JsonNode searchVector, VectorIndex index) {
        float[] query = searchVector != null && searchVector.isArray()
                ? toVector(searchVector)
                : new float[0];
        if (query == null) {
            throw invalidSearchVector();
        }
        long dimensions = index.getDimensions();
        if (query.length != dimensions) {
            throw new AwsException("ValidationException",
                    "Input search vector dimension " + query.length
                    + " does not match vector index dimension " + dimensions, 400);
        }
        return query;
    }

    private static AwsException invalidSearchVector() {
        return new AwsException("ValidationException",
                "Search vector contains invalid values. All values in the search vector must be a "
                + "32-bit floating-point number attribute", 400);
    }

    // ── SearchConditionExpression ──

    /**
     * Parses and validates the condition, returning null when there is none.
     *
     * <p>The leaf conditions are checked in the order AWS checks them. First each leaf is checked
     * for functions, nested attributes and the number of attribute names, in written order. Then,
     * when the index has a HASH element, the conditions on it must be one equality reached through
     * AND only. Last each leaf is checked in written order for membership in the SearchSchema, the
     * operator against the attribute type, and the value types against the attribute type.
     */
    private static ExpressionEvaluator.Expr parseSearchCondition(TableDefinition table,
                                                                 VectorIndex index,
                                                                 String expression,
                                                                 JsonNode exprAttrNames,
                                                                 JsonNode exprAttrValues) {
        String hashAttribute = index.getHashAttributeName();
        if (expression == null || expression.isBlank()) {
            if (hashAttribute != null) {
                throw missingHashCondition();
            }
            return null;
        }
        ExpressionEvaluator.validateSyntax(expression, "SearchConditionExpression");
        ExpressionEvaluator.validateSemantics(expression, "SearchConditionExpression",
                exprAttrNames, exprAttrValues);

        ExpressionEvaluator.Expr condition = ExpressionEvaluator.parse(expression);
        List<Leaf> leaves = new ArrayList<>();
        collectLeaves(condition, false, leaves);
        for (Leaf leaf : leaves) {
            checkLeafShape(leaf.expr());
        }
        if (hashAttribute != null) {
            checkHashConditions(leaves, hashAttribute, exprAttrNames);
        }

        Set<String> schemaAttributes = new HashSet<>(index.getSearchSchemaAttributeNames());
        for (Leaf leaf : leaves) {
            String attribute = leafAttribute(leaf.expr(), exprAttrNames);
            if (!schemaAttributes.contains(attribute)) {
                throw new AwsException("ValidationException",
                        "SearchConditionExpression must not contain any attributes that is not in "
                        + "SearchSchema. Invalid attribute: " + attribute, 400);
            }
            String attributeType = attributeType(table, attribute);
            checkOperatorType(leaf.expr(), attribute, attributeType);
            checkValueTypes(leaf.expr(), attribute, attributeType, exprAttrValues);
        }
        return condition;
    }

    /** A leaf condition, and whether an OR or NOT sits between it and the root. */
    private record Leaf(ExpressionEvaluator.Expr expr, boolean underOrNot) {}

    private static void collectLeaves(ExpressionEvaluator.Expr expr, boolean underOrNot,
                                      List<Leaf> leaves) {
        switch (expr) {
            case ExpressionEvaluator.AndExpr and -> {
                for (ExpressionEvaluator.Expr operand : and.operands()) {
                    collectLeaves(operand, underOrNot, leaves);
                }
            }
            case ExpressionEvaluator.OrExpr or -> {
                for (ExpressionEvaluator.Expr operand : or.operands()) {
                    collectLeaves(operand, true, leaves);
                }
            }
            case ExpressionEvaluator.NotExpr not -> collectLeaves(not.operand(), true, leaves);
            default -> leaves.add(new Leaf(expr, underOrNot));
        }
    }

    private static List<ExpressionEvaluator.Operand> leafOperands(ExpressionEvaluator.Expr leaf) {
        return switch (leaf) {
            case ExpressionEvaluator.CompareExpr compare -> List.of(compare.left(), compare.right());
            case ExpressionEvaluator.BetweenExpr between ->
                    List.of(between.value(), between.low(), between.high());
            case ExpressionEvaluator.InExpr in -> {
                List<ExpressionEvaluator.Operand> operands = new ArrayList<>();
                operands.add(in.value());
                operands.addAll(in.candidates());
                yield operands;
            }
            case ExpressionEvaluator.FunctionCallExpr call -> call.args();
            default -> List.of();
        };
    }

    private static void checkLeafShape(ExpressionEvaluator.Expr leaf) {
        if (leaf instanceof ExpressionEvaluator.FunctionCallExpr call
                && !call.functionName().equals("attribute_exists")
                && !call.functionName().equals("attribute_not_exists")) {
            throw functionNotAllowed(call.functionName());
        }
        List<ExpressionEvaluator.PathOperand> paths = new ArrayList<>();
        for (ExpressionEvaluator.Operand operand : leafOperands(leaf)) {
            if (operand instanceof ExpressionEvaluator.FunctionOperand function) {
                throw functionNotAllowed(function.functionName());
            }
            if (operand instanceof ExpressionEvaluator.PathOperand path) {
                paths.add(path);
            }
        }
        for (ExpressionEvaluator.PathOperand path : paths) {
            if (path.segments().size() > 1) {
                throw invalidCondition("SearchConditionExpression cannot have conditions on "
                        + "nested attributes");
            }
        }
        if (paths.isEmpty()) {
            throw invalidCondition("Invalid condition in SearchConditionExpression: No attribute "
                    + "names used in a condition");
        }
        if (paths.size() > 1) {
            throw invalidCondition("Invalid condition in SearchConditionExpression: Multiple "
                    + "attribute names used in one condition");
        }
    }

    private static void checkHashConditions(List<Leaf> leaves, String hashAttribute,
                                            JsonNode exprAttrNames) {
        int equalities = 0;
        for (Leaf leaf : leaves) {
            if (!leafAttribute(leaf.expr(), exprAttrNames).equals(hashAttribute)) {
                continue;
            }
            boolean equality = leaf.expr() instanceof ExpressionEvaluator.CompareExpr compare
                    && compare.op() == ExpressionEvaluator.TokenType.EQ;
            if (!equality || leaf.underOrNot()) {
                throw new AwsException("ValidationException",
                        "SearchConditionExpression must use = on the HASH attribute, not inside "
                        + "OR or NOT; HASH attribute: " + hashAttribute, 400);
            }
            equalities++;
        }
        if (equalities > 1) {
            throw new AwsException("ValidationException",
                    "SearchConditionExpression must contain only one condition on the HASH "
                    + "attribute; to search more than one HASH value, make one SearchVectors call "
                    + "for each value", 400);
        }
        if (equalities == 0) {
            throw new AwsException("ValidationException",
                    "SearchConditionExpression must have all HASH attributes in configured "
                    + "SearchSchema", 400);
        }
    }

    private static void checkOperatorType(ExpressionEvaluator.Expr leaf, String attribute,
                                          String attributeType) {
        String operator = switch (leaf) {
            case ExpressionEvaluator.BetweenExpr between -> "BETWEEN";
            case ExpressionEvaluator.CompareExpr compare -> switch (compare.op()) {
                case LT -> "<";
                case LE -> "<=";
                case GT -> ">";
                case GE -> ">=";
                default -> null;
            };
            default -> null;
        };
        if (operator != null && !"N".equals(attributeType)) {
            throw new AwsException("ValidationException",
                    "SearchConditionExpression allows this operator only on Number (N) "
                    + "attributes; operator: " + operator + ", attribute: " + attribute
                    + ", attribute type: " + attributeType, 400);
        }
    }

    private static void checkValueTypes(ExpressionEvaluator.Expr leaf, String attribute,
                                        String attributeType, JsonNode exprAttrValues) {
        if (attributeType == null) {
            return;
        }
        for (ExpressionEvaluator.Operand operand : leafOperands(leaf)) {
            if (!(operand instanceof ExpressionEvaluator.PlaceholderOperand placeholder)) {
                continue;
            }
            JsonNode value = exprAttrValues == null ? null : exprAttrValues.get(placeholder.name());
            if (value == null || !value.fieldNames().hasNext()) {
                continue;
            }
            String valueType = value.fieldNames().next();
            if (!valueType.equals(attributeType)) {
                throw new AwsException("ValidationException",
                        "Type of '" + attribute + "' attribute in SearchConditionExpression ("
                        + valueType + ") does not match type in search schema (" + attributeType
                        + ")", 400);
            }
        }
    }

    private static String attributeType(TableDefinition table, String attribute) {
        for (AttributeDefinition definition : table.getAttributeDefinitions()) {
            if (definition.getAttributeName().equals(attribute)) {
                return definition.getAttributeType();
            }
        }
        return null;
    }

    /** The attribute a leaf constrains, with any {@code #alias} resolved. */
    private static String leafAttribute(ExpressionEvaluator.Expr leaf, JsonNode exprAttrNames) {
        for (ExpressionEvaluator.Operand operand : leafOperands(leaf)) {
            if (operand instanceof ExpressionEvaluator.PathOperand path) {
                String segment = path.segments().get(0);
                return segment.startsWith("#") && exprAttrNames != null
                        && exprAttrNames.hasNonNull(segment)
                        ? exprAttrNames.get(segment).asText() : segment;
            }
        }
        throw new IllegalStateException("A search condition leaf has no attribute");
    }

    private static AwsException functionNotAllowed(String name) {
        return invalidCondition("The function is not allowed in a search condition; function: "
                + name);
    }

    private static AwsException invalidCondition(String detail) {
        return new AwsException("ValidationException",
                "Invalid SearchConditionExpression: " + detail, 400);
    }

    private static AwsException missingHashCondition() {
        return new AwsException("ValidationException",
                "SearchConditionExpression must be provided when SearchSchema has a HASH key", 400);
    }

    // ── Candidates ──

    /**
     * The item's vector narrowed to f32, or null when the item is not in the index at all. A
     * missing attribute, a non-list value, a member that is not a number and a length other than
     * the index dimension all leave the item out of the index rather than failing the search.
     */
    private static float[] storedVector(JsonNode item, VectorIndex index) {
        JsonNode list = item.path(index.getVectorAttributeName()).path("L");
        if (!list.isArray() || list.size() != index.getDimensions()) {
            return null;
        }
        return toVector(list);
    }

    /**
     * The list's members narrowed to f32, or null at the first member that is not a number
     * attribute. The caller decides what that means: the search vector answers an error, a
     * stored vector leaves its item out of the index.
     */
    private static float[] toVector(JsonNode list) {
        float[] values = new float[list.size()];
        for (int i = 0; i < values.length; i++) {
            JsonNode number = list.get(i).path("N");
            if (!number.isValueNode()) {
                return null;
            }
            try {
                values[i] = Float.parseFloat(number.asText());
            } catch (NumberFormatException expected) {
                // Not a number the index can hold, which the caller reports its own way.
                return null;
            }
        }
        return values;
    }

    // ── Projection ──

    /**
     * The attributes a hit carries. The index view is intersected with the ProjectionExpression
     * when there is one, and the vector attribute is left out unless that expression names it.
     */
    private static ObjectNode projectHit(JsonNode item, VectorIndex index,
                                         Set<String> projectedAttributes,
                                         String projectionExpression, JsonNode exprAttrNames,
                                         boolean rendersVector) {
        ObjectNode view = "ALL".equals(index.getProjectionType())
                ? shallowCopy((ObjectNode) item)
                : ProjectionEvaluator.trimToAttributes((ObjectNode) item, projectedAttributes);
        if (projectionExpression != null && !projectionExpression.isBlank()) {
            if (rendersVector) {
                view.set(index.getVectorAttributeName(),
                        renderVector(storedVector(item, index)));
            }
            return ProjectionEvaluator.project(view, projectionExpression, exprAttrNames);
        }
        view.remove(index.getVectorAttributeName());
        return view;
    }

    /** A copy the projection can prune without touching the stored item. Values stay shared. */
    private static ObjectNode shallowCopy(ObjectNode item) {
        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        copy.setAll(item);
        return copy;
    }

    private static Set<String> projectedAttributeNames(TableDefinition table, VectorIndex index) {
        Set<String> projected = new HashSet<>();
        for (KeySchemaElement key : table.getKeySchema()) {
            projected.add(key.getAttributeName());
        }
        projected.addAll(index.getSearchSchemaAttributeNames());
        if ("INCLUDE".equals(index.getProjectionType())) {
            projected.addAll(index.getNonKeyAttributes());
        }
        return projected;
    }

    /** The index's own f32 copy of the vector, which is what a search returns. */
    private static ObjectNode renderVector(float[] stored) {
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        for (float value : stored) {
            ObjectNode element = JsonNodeFactory.instance.objectNode();
            element.put("N", DynamoDbVectorScoring.render(value));
            list.add(element);
        }
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        wrapper.set("L", list);
        return wrapper;
    }
}
