package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.SearchSchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.VectorIndex;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HASH = "HASH";

    private DynamoDbVectorSearch() {}

    /** One search result: the projected item and its distance score. */
    record Hit(ObjectNode item, double score) {}

    /** A scored candidate, before the top-K cut decides whether it is worth projecting. */
    private record Candidate(JsonNode item, float[] vector, double score) {}

    static List<Hit> search(TableDefinition table, VectorIndex index, List<JsonNode> items,
                            JsonNode searchVector, int topK, String searchConditionExpression,
                            JsonNode exprAttrNames, JsonNode exprAttrValues,
                            String projectionExpression) {
        float[] query = parseSearchVector(searchVector, index);
        ExpressionEvaluator.Expr condition = parseSearchCondition(index, searchConditionExpression,
                exprAttrNames, exprAttrValues);
        ProjectionEvaluator.validateExpression(projectionExpression);

        String hashAttribute = hashAttribute(index);
        String distanceFunction = index.getDistanceFunction();
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
            candidates.add(new Candidate(item, stored,
                    DynamoDbVectorScoring.score(distanceFunction, query, stored)));
        }

        Comparator<Candidate> byScore = Comparator.comparingDouble(Candidate::score);
        candidates.sort(DynamoDbVectorScoring.higherIsCloser(distanceFunction)
                ? byScore.reversed() : byScore);

        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : candidates.subList(0, Math.min(topK, candidates.size()))) {
            hits.add(new Hit(projectHit(candidate.item(), candidate.vector(), table, index,
                    projectionExpression, exprAttrNames), candidate.score()));
        }
        return hits;
    }

    // ── Search vector ──

    private static float[] parseSearchVector(JsonNode searchVector, VectorIndex index) {
        int size = searchVector != null && searchVector.isArray() ? searchVector.size() : 0;
        float[] query = new float[size];
        for (int i = 0; i < size; i++) {
            JsonNode number = searchVector.get(i).path("N");
            if (!number.isValueNode()) {
                throw invalidSearchVector();
            }
            try {
                query[i] = Float.parseFloat(number.asText());
            } catch (NumberFormatException e) {
                throw invalidSearchVector();
            }
        }
        long dimensions = index.getDimensions() != null ? index.getDimensions() : size;
        if (size != dimensions) {
            throw new AwsException("ValidationException",
                    "Input search vector dimension " + size
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
     * <p>Only equality is supported, on HASH and INLINE_FILTER elements alike. The comparator is
     * checked on every clause before any membership check, because AWS answers {@code tenant < :t}
     * on an index that declares {@code tenant} with the comparator message and {@code tenant = :t}
     * on an index with no SearchSchema with the membership one.
     */
    private static ExpressionEvaluator.Expr parseSearchCondition(VectorIndex index, String expression,
                                                                 JsonNode exprAttrNames,
                                                                 JsonNode exprAttrValues) {
        String hashAttribute = hashAttribute(index);
        if (expression == null || expression.isBlank()) {
            if (hashAttribute != null) {
                throw missingHashCondition();
            }
            return null;
        }
        ExpressionEvaluator.validateExpression(expression, "SearchConditionExpression",
                exprAttrNames, exprAttrValues);

        ExpressionEvaluator.Expr condition = ExpressionEvaluator.parse(expression);
        List<ExpressionEvaluator.CompareExpr> clauses = new ArrayList<>();
        collectClauses(condition, clauses);
        for (ExpressionEvaluator.CompareExpr clause : clauses) {
            if (clause.op() != ExpressionEvaluator.TokenType.EQ) {
                throw invalidComparator();
            }
        }

        Set<String> schemaAttributes = new HashSet<>(index.getSearchSchemaAttributeNames());
        boolean hashConstrained = false;
        for (ExpressionEvaluator.CompareExpr clause : clauses) {
            String attribute = conditionAttribute(clause, exprAttrNames);
            if (!schemaAttributes.contains(attribute)) {
                throw new AwsException("ValidationException",
                        "SearchConditionExpression must not contain any attributes that is not in "
                        + "SearchSchema. Invalid attribute: " + attribute, 400);
            }
            hashConstrained |= attribute.equals(hashAttribute);
        }
        if (hashAttribute != null && !hashConstrained) {
            throw missingHashCondition();
        }
        return condition;
    }

    private static void collectClauses(ExpressionEvaluator.Expr expr,
                                       List<ExpressionEvaluator.CompareExpr> clauses) {
        if (expr instanceof ExpressionEvaluator.AndExpr and) {
            for (ExpressionEvaluator.Expr operand : and.operands()) {
                collectClauses(operand, clauses);
            }
        } else if (expr instanceof ExpressionEvaluator.CompareExpr compare) {
            clauses.add(compare);
        } else {
            throw invalidComparator();
        }
    }

    /** The attribute a clause constrains, with any {@code #alias} resolved. */
    private static String conditionAttribute(ExpressionEvaluator.CompareExpr clause,
                                             JsonNode exprAttrNames) {
        ExpressionEvaluator.Operand operand = clause.left() instanceof ExpressionEvaluator.PathOperand
                ? clause.left() : clause.right();
        if (!(operand instanceof ExpressionEvaluator.PathOperand path)) {
            throw invalidComparator();
        }
        StringBuilder name = new StringBuilder();
        for (String segment : path.segments()) {
            String resolved = segment.startsWith("#") && exprAttrNames != null
                    && exprAttrNames.hasNonNull(segment)
                    ? exprAttrNames.get(segment).asText() : segment;
            if (!name.isEmpty() && !resolved.startsWith("[")) {
                name.append('.');
            }
            name.append(resolved);
        }
        return name.toString();
    }

    private static String hashAttribute(VectorIndex index) {
        for (SearchSchemaElement element : index.getSearchSchema()) {
            if (HASH.equals(element.getSearchSchemaElementType())) {
                return element.getAttributeName();
            }
        }
        return null;
    }

    private static AwsException missingHashCondition() {
        return new AwsException("ValidationException",
                "SearchConditionExpression must be provided when SearchSchema has a HASH key", 400);
    }

    private static AwsException invalidComparator() {
        return new AwsException("ValidationException",
                "Invalid SearchConditionExpression: Invalid comparator used in "
                + "SearchConditionExpression", 400);
    }

    // ── Candidates ──

    /**
     * The item's vector narrowed to f32, or null when the item is not in the index at all. A
     * missing attribute, a non-list value, a member that is not a number and a length other than
     * the index dimension all leave the item out of the index rather than failing the search.
     */
    private static float[] storedVector(JsonNode item, VectorIndex index) {
        JsonNode list = item.path(index.getVectorAttributeName()).path("L");
        if (!list.isArray()) {
            return null;
        }
        long dimensions = index.getDimensions() != null ? index.getDimensions() : list.size();
        if (list.size() != dimensions) {
            return null;
        }
        float[] stored = new float[list.size()];
        for (int i = 0; i < stored.length; i++) {
            JsonNode number = list.get(i).path("N");
            if (!number.isValueNode()) {
                return null;
            }
            try {
                stored[i] = Float.parseFloat(number.asText());
            } catch (NumberFormatException expected) {
                // A stored value the index cannot hold is simply not in it, never a search error.
                return null;
            }
        }
        return stored;
    }

    // ── Projection ──

    /**
     * The attributes a hit carries. The index view is intersected with the ProjectionExpression
     * when there is one, and the vector attribute is left out unless that expression names it.
     */
    private static ObjectNode projectHit(JsonNode item, float[] vector, TableDefinition table,
                                         VectorIndex index, String projectionExpression,
                                         JsonNode exprAttrNames) {
        ObjectNode view = "ALL".equals(index.getProjectionType())
                ? shallowCopy((ObjectNode) item)
                : ProjectionEvaluator.trimToAttributes((ObjectNode) item,
                        projectedAttributeNames(table, index));
        if (projectionExpression != null && !projectionExpression.isBlank()) {
            view.set(index.getVectorAttributeName(), renderVector(vector));
            return ProjectionEvaluator.project(view, projectionExpression, exprAttrNames);
        }
        view.remove(index.getVectorAttributeName());
        return view;
    }

    /** A copy the projection can prune without touching the stored item. Values stay shared. */
    private static ObjectNode shallowCopy(ObjectNode item) {
        ObjectNode copy = MAPPER.createObjectNode();
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
        ArrayNode list = MAPPER.createArrayNode();
        for (float value : stored) {
            ObjectNode element = MAPPER.createObjectNode();
            element.put("N", DynamoDbVectorScoring.render(value));
            list.add(element);
        }
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("L", list);
        return wrapper;
    }
}
