package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.*;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Base64;
import java.util.function.Supplier;
import java.util.stream.Collectors;

class DynamoDbPartiQLHandler {

    private final DynamoDbService service;
    private final ObjectMapper mapper;

    DynamoDbPartiQLHandler(DynamoDbService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    JsonNode execute(Stmt stmt, PartiQLExecuteContext ctx, String region) {
        return switch (stmt) {
            case Stmt.Select s -> executeSelect(s, ctx, region);
            case Stmt.Insert s -> executeInsert(s, region);
            case Stmt.Update s -> executeUpdate(s, region);
            case Stmt.Delete s -> executeDelete(s, region);
            case Stmt.Exists ignored -> throw new AwsException("ValidationException",
                    "EXISTS can only be used in ExecuteTransaction write requests.", 400);
        };
    }

    // --- SELECT ---

    private JsonNode executeSelect(Stmt.Select stmt, PartiQLExecuteContext ctx, String region) {
        TableDefinition table = service.describeTable(stmt.table(), region);
        // ExecuteStatement omits the index name from the missing-index message,
        // unlike Query/Scan (characterised on real AWS, eu-west-1, 2026-09-02).
        DynamoDbAccessPath accessPath = DynamoDbAccessPath.resolve(table, stmt.index(),
                "The table does not have the specified index");

        // Consistent reads are rejected on statements qualified with a GSI.
        // Characterised on real AWS (eu-west-1, 2026-09-02): the wording
        // differs from the Query/Scan rejection.
        if (ctx.consistentRead() && accessPath.isGlobalSecondaryIndex()) {
            throw new AwsException("ValidationException",
                    "Strongly consistent read is not supported on Global Secondary Indexes", 400);
        }

        // A GSI-qualified statement can only select attributes the index
        // projects; the message preserves statement order (characterised on
        // real AWS, eu-west-1, 2026-09-02). An LSI read reaches the co-located
        // base item, so non-projected columns stay legal there.
        if (accessPath.isGlobalSecondaryIndex() && !"ALL".equals(accessPath.projectionType())
                && !stmt.columns().isEmpty()) {
            Set<String> projected = accessPath.projectedAttributeNames(table);
            List<String> unprojected = stmt.columns().stream()
                    .map(Path::root)
                    .filter(c -> !"*".equals(c))
                    .filter(c -> !projected.contains(c))
                    .toList();
            if (!unprojected.isEmpty()) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Global secondary index "
                                + accessPath.indexName() + " does not project ["
                                + String.join(", ", unprojected) + "]", 400);
            }
        }

        requireFilterAttributesProjected(stmt, accessPath, table);

        String pkName = accessPath.partitionKeyName();
        String skName = accessPath.sortKeyName();

        Cond.Eq pkEq = null;
        Cond skCond = null;
        List<Cond> filterConds = new ArrayList<>();

        for (Cond c : stmt.where()) {
            if (isKeyCondition(c, pkName)) {
                if (c instanceof Cond.Eq eq) {
                    pkEq = eq;
                } else {
                    filterConds.add(c);
                }
            } else if (skName != null && isKeyCondition(c, skName)) {
                skCond = c;
            } else {
                filterConds.add(c);
            }
        }

        Routing routing = new Routing(pkEq, skCond, filterConds);
        Supplier<JsonNode> startKey = () -> decodeNextToken(ctx.nextToken(), ctx.tokenBinding());
        Page page = stmt.orderBy().isEmpty()
                ? readPage(stmt, table, accessPath, routing, startKey, ctx.limit(), region)
                : readOrdered(stmt, table, accessPath, routing, startKey.get(), ctx.limit(), region);
        List<JsonNode> items = page.items();
        JsonNode lastEvaluatedKey = page.lastEvaluatedKey();

        // Apply column projection for non-* SELECT. Projects against the full
        // item: an LSI read can reach attributes outside the index projection
        // (characterised on real AWS, eu-west-1, 2026-09-02).
        if (!stmt.columns().isEmpty()) {
            List<SelectedColumn> columns = selectedColumns(stmt);
            items = items.stream()
                    .map(item -> (JsonNode) selectColumns(item, columns))
                    .toList();
        }

        // SELECT * over an index reads the projection, not the whole item. An
        // ALL projection stores every base attribute, so nothing is trimmed.
        if (stmt.columns().isEmpty() && accessPath.isIndex()
                && !"ALL".equals(accessPath.projectionType())) {
            Set<String> projected = accessPath.projectedAttributeNames(table);
            items = items.stream()
                    .map(item -> (JsonNode) ProjectionEvaluator.trimToAttributes((ObjectNode) item, projected))
                    .toList();
        }

        ArrayNode arr = mapper.createArrayNode();
        items.forEach(arr::add);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("Items", arr);
        if (lastEvaluatedKey != null) {
            resp.put("NextToken", encodeNextToken(lastEvaluatedKey, ctx.tokenBinding()));
        }
        return resp;
    }

    private record Routing(Cond.Eq pkEq, Cond skCond, List<Cond> filterConds) {}

    private record Page(List<JsonNode> items, JsonNode lastEvaluatedKey) {}

    private Page readPage(Stmt.Select stmt, TableDefinition table, DynamoDbAccessPath accessPath,
                          Routing routing, Supplier<JsonNode> startKey, Integer limit, String region) {
        String pkName = accessPath.partitionKeyName();
        String skName = accessPath.sortKeyName();
        Cond.Eq pkEq = routing.pkEq();
        Cond skCond = routing.skCond();
        List<Cond> filterConds = routing.filterConds();

        if (pkEq == null) {
            // No equality on the selected source's partition key: AWS performs
            // a full scan of the table or index and applies the remaining
            // conditions as a filter (characterised on real AWS, eu-west-1,
            // 2026-09-02).
            ExprAttrBuilder eav = new ExprAttrBuilder();
            ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
            List<Cond> scanFilters = new ArrayList<>(filterConds);
            if (skCond != null) {
                scanFilters.add(skCond);
            }
            String fe = buildFe(scanFilters, eav, ean);
            JsonNode exclusiveStartKey = startKey.get();
            DynamoDbAccessPathValidator.validateExclusiveStartKey(exclusiveStartKey, table, accessPath, true);
            DynamoDbService.ScanResult result = service.scan(stmt.table(), fe,
                    ean.isEmpty() ? null : ean.toNode(mapper),
                    eav.isEmpty() ? null : eav.toNode(mapper),
                    null, limit, exclusiveStartKey, accessPath.indexName(), region);
            return new Page(result.items(), result.lastEvaluatedKey());
        }
        if (accessPath.kind() == DynamoDbAccessPath.Kind.TABLE && skName == null
                && skCond == null && filterConds.isEmpty()) {
            ObjectNode key = mapper.createObjectNode();
            key.set(pkName, toTypedNode(pkEq.val()));
            JsonNode item = service.getItem(stmt.table(), key, region);
            return new Page(item != null ? List.of(item) : Collections.emptyList(), null);
        }
        ExprAttrBuilder eav = new ExprAttrBuilder();
        ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
        String kce = buildKce(pkEq, skCond, pkName, skName, eav, ean);
        String fe = buildFe(filterConds, eav, ean);
        JsonNode exclusiveStartKey = startKey.get();
        DynamoDbAccessPathValidator.validateExclusiveStartKey(exclusiveStartKey, table, accessPath, false);
        DynamoDbService.QueryResult result = service.query(
                stmt.table(), null, eav.toNode(mapper), kce, fe,
                limit, null, accessPath.indexName(), exclusiveStartKey,
                ean.isEmpty() ? null : ean.toNode(mapper), region);
        return new Page(result.items(), result.lastEvaluatedKey());
    }

    // Sorts every matching row, then pages the sorted rows. The NextToken names the last row returned.
    private Page readOrdered(Stmt.Select stmt, TableDefinition table, DynamoDbAccessPath accessPath,
                             Routing routing, JsonNode after, Integer limit, String region) {
        requireOrderByShape(stmt, accessPath);
        List<JsonNode> rows = new ArrayList<>();
        JsonNode lastKey = null;
        do {
            JsonNode from = lastKey;
            Page page = readPage(stmt, table, accessPath, routing, () -> from, null, region);
            rows.addAll(page.items());
            lastKey = page.lastEvaluatedKey();
        } while (lastKey != null);

        Comparator<JsonNode> order = orderByComparator(stmt.orderBy(), accessPath, table);
        rows.sort(order);
        int start = 0;
        if (after != null) {
            while (start < rows.size() && order.compare(rows.get(start), after) <= 0) {
                start++;
            }
        }
        int end = limit == null ? rows.size() : Math.min(rows.size(), start + limit);
        JsonNode resumeAfter = start < end && end < rows.size() ? orderingKey(rows.get(end - 1), accessPath, table) : null;
        return new Page(new ArrayList<>(rows.subList(start, end)), resumeAfter);
    }

    // The rules and their wording were checked on real AWS (eu-west-2, 2026-09-17).
    private static void requireOrderByShape(Stmt.Select stmt, DynamoDbAccessPath accessPath) {
        if (stmt.where().isEmpty()) {
            throw new AwsException("ValidationException",
                    "Must have WHERE clause in the statement when using ORDER BY clause.", 400);
        }
        Set<String> keys = accessPath.keyAttributeNames();
        for (OrderTerm term : stmt.orderBy()) {
            if (!keys.contains(term.attribute())) {
                throw new AwsException("ValidationException",
                        "Attribute " + term.attribute() + " in ORDER BY clause must be part of the primary key", 400);
            }
        }
        String pkName = accessPath.partitionKeyName();
        int hashKeyConditions = stmt.where().stream().mapToInt(c -> hashKeyConditionCount(c, pkName)).sum();
        if (hashKeyConditions == 0) {
            throw new AwsException("ValidationException",
                    "Must have at least one non-optional hash key condition in WHERE clause when using ORDER BY clause.",
                    400);
        }
        boolean ordersByHashKey = stmt.orderBy().stream().anyMatch(term -> term.attribute().equals(pkName));
        if (hashKeyConditions > 1 && !ordersByHashKey) {
            throw new AwsException("ValidationException",
                    "Must have hash key in ORDER BY clause when more than one hash key condition specified in WHERE clause.",
                    400);
        }
    }

    private static int hashKeyConditionCount(Cond cond, String pkName) {
        return switch (cond) {
            case Cond.Eq eq when isKeyCondition(eq, pkName) -> 1;
            case Cond.In in when isKeyCondition(in, pkName) -> in.values().size();
            case Cond.Or or when or.operands().stream()
                    .allMatch(o -> o instanceof Cond.Eq && isKeyCondition(o, pkName)) -> or.operands().size();
            default -> 0;
        };
    }

    // Without its own term the partition key sorts ascending and the sort key descending, even
    // under ORDER BY pk ASC (checked on real AWS, eu-west-2, 2026-09-17).
    private static Comparator<JsonNode> orderByComparator(List<OrderTerm> terms, DynamoDbAccessPath accessPath,
                                                          TableDefinition table) {
        String pkName = accessPath.partitionKeyName();
        String skName = accessPath.sortKeyName();
        boolean pkDescending = directionOf(terms, pkName).orElse(false);
        Comparator<JsonNode> order = byAttribute(pkName, pkDescending);
        if (skName != null) {
            order = order.thenComparing(byAttribute(skName, directionOf(terms, skName).orElse(true)));
        }
        for (String tableKey : List.of(table.getPartitionKeyName(), Objects.toString(table.getSortKeyName(), ""))) {
            if (!tableKey.isEmpty() && !tableKey.equals(pkName) && !tableKey.equals(skName)) {
                order = order.thenComparing(byAttribute(tableKey, false));
            }
        }
        return order;
    }

    private static Optional<Boolean> directionOf(List<OrderTerm> terms, String attribute) {
        return terms.stream().filter(term -> term.attribute().equals(attribute)).findFirst().map(OrderTerm::descending);
    }

    private static Comparator<JsonNode> byAttribute(String attribute, boolean descending) {
        Comparator<JsonNode> ascending = (a, b) -> ExpressionEvaluator.compareAttributeValues(a.path(attribute), b.path(attribute));
        return descending ? ascending.reversed() : ascending;
    }

    private JsonNode orderingKey(JsonNode row, DynamoDbAccessPath accessPath, TableDefinition table) {
        ObjectNode key = mapper.createObjectNode();
        Set<String> names = new LinkedHashSet<>(accessPath.keyAttributeNames());
        names.add(table.getPartitionKeyName());
        if (table.getSortKeyName() != null) {
            names.add(table.getSortKeyName());
        }
        names.forEach(name -> key.set(name, row.get(name)));
        return key;
    }

    // NextToken is opaque to the client, so it carries a digest binding it to
    // the statement text and parameter values that minted it. Real AWS rejects
    // a token replayed against any other statement, table or access path with
    // "NextToken does not match request", while a changed Limit or a dropped
    // ConsistentRead is accepted (characterised on real AWS, eu-west-1,
    // 2026-09-03).
    String tokenBinding(String statement, List<JsonNode> parameters) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(statement.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (JsonNode parameter : parameters) {
                digest.update(mapper.writeValueAsBytes(parameter));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Failed to bind the PartiQL NextToken to its statement", e);
        }
    }

    private String encodeNextToken(JsonNode lastEvaluatedKey, String tokenBinding) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.set("k", lastEvaluatedKey);
            payload.put("b", tokenBinding);
            return Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new AwsException("InternalServerError", "Failed to encode NextToken", 500);
        }
    }

    private JsonNode decodeNextToken(String nextToken, String tokenBinding) {
        if (nextToken == null) return null;
        JsonNode payload;
        try {
            payload = mapper.readTree(Base64.getDecoder().decode(nextToken));
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid NextToken", 400);
        }
        if (!payload.isObject() || !payload.hasNonNull("k")
                || !tokenBinding.equals(payload.path("b").asText())) {
            throw new AwsException("ValidationException", "NextToken does not match request", 400);
        }
        return payload.get("k");
    }

    private String buildKce(Cond.Eq pkEq, Cond skCond, String pkName, String skName,
                             ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        // Query branch: callers only reach this with a partition-key equality
        // in the WHERE clause; every other shape routes to the scan branch.
        String pkAlias = ean.alias(pkName);
        String pkPlaceholder = eav.add(toTypedNode(pkEq.val()));
        StringBuilder kce = new StringBuilder(pkAlias).append(" = ").append(pkPlaceholder);
        if (skCond != null) {
            kce.append(" AND ").append(buildCondExpr(skCond, eav, ean));
        }
        return kce.toString();
    }

    private String buildFe(List<Cond> filterConds, ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        if (filterConds.isEmpty()) return null;
        List<String> parts = filterConds.stream().map(c -> buildCondExpr(c, eav, ean)).toList();
        return String.join(" AND ", parts);
    }

    private String buildCondExpr(Cond c, ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        return switch (c) {
            case Cond.Eq eq   -> ean.alias(eq.path()) + " = " + eav.add(toTypedNode(eq.val()));
            case Cond.Cmp cmp -> ean.alias(cmp.path()) + " " + cmp.op() + " " + eav.add(toTypedNode(cmp.val()));
            case Cond.Between b ->
                ean.alias(b.path()) + " BETWEEN " + eav.add(toTypedNode(b.lo())) + " AND " + eav.add(toTypedNode(b.hi()));
            case Cond.BeginsWith bw ->
                "begins_with(" + ean.alias(bw.path()) + ", " + eav.add(toTypedNode(bw.prefix())) + ")";
            case Cond.In in -> ean.alias(in.path()) + " IN (" + in.values().stream()
                    .map(v -> eav.add(toTypedNode(v)))
                    .collect(Collectors.joining(", ")) + ")";
            case Cond.Missing m ->
                (m.negated() ? "attribute_exists(" : "attribute_not_exists(") + ean.alias(m.path()) + ")";
            case Cond.Contains ct ->
                "contains(" + ean.alias(ct.path()) + ", " + eav.add(toTypedNode(ct.operand())) + ")";
            case Cond.AttributeType at ->
                "attribute_type(" + ean.alias(at.path()) + ", " + eav.add(toTypedNode(at.type())) + ")";
            case Cond.SizeCmp sc ->
                "size(" + ean.alias(sc.path()) + ") " + sc.op() + " " + eav.add(toTypedNode(sc.val()));
            // IS NULL holds only for a stored NULL, so a missing attribute IS NOT NULL
            // (checked on real AWS, eu-west-2, 2026-09-17).
            case Cond.IsNull isNull -> (isNull.negated() ? "NOT " : "") + "attribute_type(" + ean.alias(isNull.path())
                    + ", " + eav.add(mapper.createObjectNode().put("S", "NULL")) + ")";
            case Cond.And and -> joinOperands(and.operands(), " AND ", eav, ean);
            case Cond.Or or   -> joinOperands(or.operands(), " OR ", eav, ean);
            case Cond.Not not -> "NOT (" + buildCondExpr(not.operand(), eav, ean) + ")";
        };
    }

    private String joinOperands(List<Cond> operands, String separator,
                                ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        return "(" + operands.stream()
                .map(operand -> buildCondExpr(operand, eav, ean))
                .collect(Collectors.joining(separator)) + ")";
    }

    // Unkeyed the read is a scan of the index, which matches nothing rather than
    // failing. The wording says Secondary index for both index kinds.
    private static void requireFilterAttributesProjected(Stmt.Select stmt, DynamoDbAccessPath accessPath,
                                                         TableDefinition table) {
        if (!accessPath.isIndex() || "ALL".equals(accessPath.projectionType())) {
            return;
        }
        Set<String> indexKeys = accessPath.keyAttributeNames();
        if (stmt.where().stream().noneMatch(c -> routesAsIndexKey(c, indexKeys))) {
            return;
        }
        Set<String> projected = accessPath.projectedAttributeNames(table);
        List<String> unprojected = stmt.where().stream()
                .map(DynamoDbPartiQLHandler::filterAttribute)
                .flatMap(Optional::stream)
                .map(Path::root)
                .filter(root -> !projected.contains(root))
                .distinct()
                .toList();
        if (!unprojected.isEmpty()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Secondary index "
                            + accessPath.indexName()
                            + " does not project one or more filter attributes: ["
                            + String.join(", ", unprojected) + "]", 400);
        }
    }

    private static boolean routesAsIndexKey(Cond cond, Set<String> indexKeys) {
        return cond.bareAttribute().filter(indexKeys::contains).isPresent();
    }

    // A NOT names whatever its operand names; an AND or OR names nothing.
    private static Optional<Path> filterAttribute(Cond cond) {
        return switch (cond) {
            case Cond.Not not     -> filterAttribute(not.operand());
            case Cond.Leaf leaf   -> Optional.of(leaf.path());
            case Cond.And ignored -> Optional.empty();
            case Cond.Or ignored  -> Optional.empty();
        };
    }

    private static boolean isKeyCondition(Cond cond, String keyName) {
        return cond.bareAttribute().filter(keyName::equals).isPresent();
    }

    private record SelectedColumn(Path path, String reportedName) {}

    private static List<SelectedColumn> selectedColumns(Stmt.Select stmt) {
        return stmt.columns().stream()
                .map(column -> new SelectedColumn(column, column.leafName()))
                .toList();
    }

    private ObjectNode selectColumns(JsonNode item, List<SelectedColumn> columns) {
        ObjectNode projected = mapper.createObjectNode();
        for (SelectedColumn column : columns) {
            JsonNode value = resolvePath(item, column.path());
            if (value != null && !value.isMissingNode()) {
                projected.set(column.reportedName(), value);
            }
        }
        return projected;
    }

    private JsonNode resolvePath(JsonNode item, Path path) {
        List<Seg> segments = path.segments();
        JsonNode current = item.get(((Seg.Name) segments.getFirst()).value());
        for (int i = 1; i < segments.size() && current != null; i++) {
            current = switch (segments.get(i)) {
                case Seg.Name name -> current.path("M").get(name.value());
                case Seg.Index index -> {
                    JsonNode list = current.path("L");
                    yield index.value() < list.size() ? list.get((int) index.value()) : null;
                }
            };
        }
        return current;
    }

    // --- INSERT ---

    private JsonNode executeInsert(Stmt.Insert stmt, String region) {
        TableDefinition table = service.describeTable(stmt.table(), region);
        String pkName = table.getPartitionKeyName();

        ObjectNode item = mapper.createObjectNode();
        stmt.item().forEach((k, v) -> item.set(k, toTypedNode(v)));
        DynamoDbJsonHandler.validateItemSets(item);

        try {
            service.putItem(stmt.table(), item, "attribute_not_exists(" + pkName + ")", null, null, region, "NONE");
        } catch (ConditionalCheckFailedException e) {
            throw new AwsException("DuplicateItemException", "Duplicate primary key exists in table", 400);
        }
        return itemsResponse(null);
    }

    // --- UPDATE ---

    private JsonNode executeUpdate(Stmt.Update stmt, String region) {
        requireNoIndexQualifier(stmt.index());
        TableDefinition table = service.describeTable(stmt.table(), region);
        ObjectNode key = buildKey(table, stmt.where());

        ExprAttrBuilder eav = new ExprAttrBuilder();
        ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
        String ue = buildUpdateExpression(stmt.sets(), stmt.removes(), eav, ean);
        String ce = updateCondition(table, stmt.where(), eav, ean);
        String returnValues = stmt.returning().returnValues();

        DynamoDbService.UpdateResult result = service.updateItem(stmt.table(), key, null, ue,
                ean.isEmpty() ? null : ean.toNode(mapper),
                eav.isEmpty() ? null : eav.toNode(mapper), returnValues, ce, region, "NONE");
        return itemsResponse(returnedAttributes(stmt.returning(), result));
    }

    private static void requireNoIndexQualifier(String index) {
        if (index != null) {
            throw new AwsException("ValidationException",
                    "This operation is not supported on an index", 400);
        }
    }

    private static Returning returningOf(Stmt stmt) {
        return switch (stmt) {
            case Stmt.Update upd -> upd.returning();
            case Stmt.Delete del -> del.returning();
            case Stmt.Select ignored -> Returning.NONE;
            case Stmt.Insert ignored -> Returning.NONE;
            case Stmt.Exists ignored -> Returning.NONE;
        };
    }

    // MODIFIED reports the paths the update touched, so it holds neither the key
    // nor an attribute the statement left alone.
    private JsonNode returnedAttributes(Returning returning, DynamoDbService.UpdateResult result) {
        return switch (returning) {
            case NONE -> null;
            case ALL_OLD -> result.oldItem();
            case ALL_NEW -> result.newItem();
            case MODIFIED_OLD -> DynamoDbUpdatedAttributes.collect(result.touched(), false);
            case MODIFIED_NEW -> DynamoDbUpdatedAttributes.collect(result.touched(), true);
        };
    }

    // --- DELETE ---

    private JsonNode executeDelete(Stmt.Delete stmt, String region) {
        requireNoIndexQualifier(stmt.index());
        TableDefinition table = service.describeTable(stmt.table(), region);
        ObjectNode key = buildKey(table, stmt.where());

        ExprAttrBuilder eav = new ExprAttrBuilder();
        ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
        String ce = deleteCondition(table, stmt.where(), eav, ean);

        JsonNode oldItem = service.deleteItem(stmt.table(), key, ce,
                ean.isEmpty() ? null : ean.toNode(mapper),
                eav.isEmpty() ? null : eav.toNode(mapper), region, "NONE");
        return itemsResponse(stmt.returning() == Returning.ALL_OLD ? oldItem : null);
    }

    // --- Transaction item builder ---

    JsonNode toTransactItem(Stmt stmt, String region) {
        if (returningOf(stmt) != Returning.NONE) {
            throw new AwsException("ValidationException",
                    "RETURNING clause is not supported in ExecuteTransaction.", 400);
        }
        ObjectNode txItem = mapper.createObjectNode();
        switch (stmt) {
            case Stmt.Insert ins -> {
                TableDefinition table = service.describeTable(ins.table(), region);
                String pkName = table.getPartitionKeyName();
                ObjectNode item = mapper.createObjectNode();
                ins.item().forEach((k, v) -> item.set(k, toTypedNode(v)));
                ObjectNode put = mapper.createObjectNode();
                put.put("TableName", ins.table());
                put.set("Item", item);
                put.put("ConditionExpression", "attribute_not_exists(" + pkName + ")");
                txItem.set("Put", put);
            }
            case Stmt.Update upd -> {
                requireNoIndexQualifier(upd.index());
                TableDefinition table = service.describeTable(upd.table(), region);
                ObjectNode key = buildKey(table, upd.where());
                ExprAttrBuilder eav = new ExprAttrBuilder();
                ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
                String ue = buildUpdateExpression(upd.sets(), upd.removes(), eav, ean);
                ObjectNode update = mapper.createObjectNode();
                update.put("TableName", upd.table());
                update.set("Key", key);
                update.put("UpdateExpression", ue);
                update.put("ConditionExpression", updateCondition(table, upd.where(), eav, ean));
                if (!eav.isEmpty()) update.set("ExpressionAttributeValues", eav.toNode(mapper));
                if (!ean.isEmpty()) update.set("ExpressionAttributeNames", ean.toNode(mapper));
                txItem.set("Update", update);
            }
            case Stmt.Delete del -> {
                requireNoIndexQualifier(del.index());
                TableDefinition table = service.describeTable(del.table(), region);
                ObjectNode key = buildKey(table, del.where());
                ExprAttrBuilder eav = new ExprAttrBuilder();
                ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
                String ce = deleteCondition(table, del.where(), eav, ean);
                ObjectNode delete = mapper.createObjectNode();
                delete.put("TableName", del.table());
                delete.set("Key", key);
                if (ce != null) {
                    delete.put("ConditionExpression", ce);
                }
                if (!eav.isEmpty()) {
                    delete.set("ExpressionAttributeValues", eav.toNode(mapper));
                }
                if (!ean.isEmpty()) {
                    delete.set("ExpressionAttributeNames", ean.toNode(mapper));
                }
                txItem.set("Delete", delete);
            }
            case Stmt.Select ignored ->
                throw new AwsException("ValidationException",
                        "SELECT is not supported inside ExecuteTransaction", 400);
            case Stmt.Exists exists -> txItem.set("ConditionCheck", conditionCheck(exists.select(), region));
        }
        return txItem;
    }

    // EXISTS needs the full key and at least one more condition, and IN does not count as
    // naming the key (checked on real AWS, eu-west-2, 2026-09-17).
    private ObjectNode conditionCheck(Stmt.Select select, String region) {
        TableDefinition table = service.describeTable(select.table(), region);
        List<Cond> conditions = nonKeyConditions(table, select.where());
        if (!pinsFullKey(table, select.where()) || conditions.isEmpty()) {
            throw new AwsException("ValidationException",
                    "EXISTS() must contain a single item read with additional condition", 400);
        }
        ExprAttrBuilder eav = new ExprAttrBuilder();
        ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
        ObjectNode check = mapper.createObjectNode();
        check.put("TableName", select.table());
        check.set("Key", buildKey(table, select.where()));
        check.put("ConditionExpression", joinOperands(conditions, " AND ", eav, ean));
        if (!eav.isEmpty()) {
            check.set("ExpressionAttributeValues", eav.toNode(mapper));
        }
        if (!ean.isEmpty()) {
            check.set("ExpressionAttributeNames", ean.toNode(mapper));
        }
        return check;
    }

    // describeTable counts the items to refresh the definition, so the caller carries
    // one cache across the members rather than paying that per statement.
    JsonNode toTransactGetItem(Stmt.Select stmt, String region, Map<String, TableDefinition> tables) {
        if (stmt.index() != null) {
            throw new AwsException("ValidationException",
                    "Reads on indices are not supported within transactions.", 400);
        }
        TableDefinition table = tables.computeIfAbsent(stmt.table(),
                name -> service.describeTable(name, region));
        ObjectNode get = mapper.createObjectNode();
        get.put("TableName", stmt.table());
        get.set("Key", buildKey(table, stmt.where()));
        ObjectNode txItem = mapper.createObjectNode();
        txItem.set("Get", get);
        return txItem;
    }

    JsonNode projectSelected(Stmt.Select stmt, JsonNode item) {
        if (item == null || stmt.columns().isEmpty()) {
            return item;
        }
        return selectColumns(item, selectedColumns(stmt));
    }

    // --- Shared helpers ---

    // set_add and set_delete run as the ADD and DELETE actions, which is also where a set
    // on an absent attribute is created or left alone (checked on real AWS, eu-west-2, 2026-09-17).
    private String buildUpdateExpression(List<SetClause> sets, List<Path> removes,
                                         ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        List<String> assignments = new ArrayList<>();
        List<String> adds = new ArrayList<>();
        List<String> deletes = new ArrayList<>();
        for (SetClause clause : sets) {
            switch (clause) {
                case Assign assign -> assignments.add(assignExpr(assign, eav, ean));
                case SetAdd add -> adds.add(ean.alias(add.path()) + " " + eav.add(toTypedNode(add.bag())));
                case SetDelete delete -> deletes.add(ean.alias(delete.path()) + " " + eav.add(toTypedNode(delete.bag())));
            }
        }
        List<String> clauses = new ArrayList<>();
        if (!assignments.isEmpty()) {
            clauses.add("SET " + String.join(", ", assignments));
        }
        if (!removes.isEmpty()) {
            clauses.add("REMOVE " + removes.stream()
                    .map(ean::alias)
                    .collect(Collectors.joining(", ")));
        }
        if (!adds.isEmpty()) {
            clauses.add("ADD " + String.join(", ", adds));
        }
        if (!deletes.isEmpty()) {
            clauses.add("DELETE " + String.join(", ", deletes));
        }
        return String.join(" ", clauses);
    }

    private String assignExpr(Assign assign, ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        String assignment = ean.alias(assign.path()) + " = " + operandExpr(assign.left(), eav, ean);
        if (assign.op() == null) {
            return assignment;
        }
        return assignment + " " + assign.op() + " " + operandExpr(assign.right(), eav, ean);
    }

    private String operandExpr(Operand operand, ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        return switch (operand) {
            case Operand.Value value -> eav.add(toTypedNode(value.val()));
            case Operand.Attribute attribute -> ean.alias(attribute.path());
            case Operand.ListAppend append -> "list_append(" + operandExpr(append.first(), eav, ean)
                    + ", " + operandExpr(append.second(), eav, ean) + ")";
        };
    }

    private ObjectNode buildKey(TableDefinition table, List<Cond> where) {
        Set<String> keyNames = keyAttributeNames(table);
        ObjectNode key = mapper.createObjectNode();
        for (Cond c : where) {
            if (isKeyEquality(c, keyNames)) {
                Cond.Eq eq = (Cond.Eq) c;
                key.set(eq.path().root(), toTypedNode(eq.val()));
            }
        }
        if (key.size() != keyNames.size()) {
            throw new AwsException("ValidationException",
                    "Where clause does not contain a mandatory equality on all key attributes", 400);
        }
        return key;
    }

    static boolean pinsFullKey(TableDefinition table, List<Cond> where) {
        Set<String> keyNames = keyAttributeNames(table);
        return where.stream()
                .filter(c -> isKeyEquality(c, keyNames))
                .map(c -> ((Cond.Eq) c).path().root())
                .distinct()
                .count() == keyNames.size();
    }

    private static Set<String> keyAttributeNames(TableDefinition table) {
        return DynamoDbAccessPath.resolve(table, null).keyAttributeNames();
    }

    private static boolean isKeyEquality(Cond cond, Set<String> keyNames) {
        return cond instanceof Cond.Eq eq && eq.bareAttribute().filter(keyNames::contains).isPresent();
    }

    private static List<Cond> nonKeyConditions(TableDefinition table, List<Cond> where) {
        Set<String> keyNames = keyAttributeNames(table);
        return where.stream().filter(c -> !isKeyEquality(c, keyNames)).toList();
    }

    // PartiQL UPDATE is not an upsert, so the key has to hold an item already.
    private String updateCondition(TableDefinition table, List<Cond> where,
                                   ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        String exists = "attribute_exists(" + ean.alias(table.getPartitionKeyName()) + ")";
        List<Cond> filters = nonKeyConditions(table, where);
        return filters.isEmpty() ? exists : exists + " AND " + joinOperands(filters, " AND ", eav, ean);
    }

    // A DELETE on a key holding no item is a no-op rather than a failure, so an
    // absent item satisfies the condition.
    private String deleteCondition(TableDefinition table, List<Cond> where,
                                   ExprAttrBuilder eav, ExprAttrNameBuilder ean) {
        List<Cond> filters = nonKeyConditions(table, where);
        if (filters.isEmpty()) {
            return null;
        }
        return "attribute_not_exists(" + ean.alias(table.getPartitionKeyName()) + ") OR "
                + joinOperands(filters, " AND ", eav, ean);
    }

    JsonNode toTypedNode(PVal val) {
        return switch (val) {
            case PVal.Str s  -> mapper.createObjectNode().put("S", s.v());
            case PVal.Num n  -> mapper.createObjectNode().put("N", DynamoDbNumberUtils.validateAndNormalize(n.v()));
            case PVal.Bool b -> mapper.createObjectNode().put("BOOL", b.v());
            case PVal.Null ignored -> mapper.createObjectNode().put("NULL", true);
            case PVal.Av av -> av.node();
            case PVal.ListOf list -> {
                ObjectNode node = mapper.createObjectNode();
                ArrayNode items = node.putArray("L");
                list.items().forEach(item -> items.add(toTypedNode(item)));
                yield node;
            }
            case PVal.Tuple tuple -> {
                ObjectNode node = mapper.createObjectNode();
                ObjectNode fields = node.putObject("M");
                tuple.fields().forEach((name, value) -> fields.set(name, toTypedNode(value)));
                yield node;
            }
            case PVal.Bag bag -> {
                ObjectNode node = mapper.createObjectNode();
                ArrayNode members = node.putArray(bag.type());
                bag.members().forEach(member -> members.add(toTypedNode(member).path(bag.type().substring(0, 1))));
                yield node;
            }
        };
    }

    // A RETURNING clause answers one row, and an empty projection answers none.
    private ObjectNode itemsResponse(JsonNode returned) {
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode items = mapper.createArrayNode();
        if (returned != null && !returned.isEmpty()) {
            items.add(returned);
        }
        resp.set("Items", items);
        return resp;
    }

    // Builds ExpressionAttributeValues with positional :p0, :p1, … placeholders
    private static class ExprAttrBuilder {
        private final Map<String, JsonNode> values = new LinkedHashMap<>();
        private int idx = 0;

        String add(JsonNode val) {
            String key = ":p" + idx++;
            values.put(key, val);
            return key;
        }

        boolean isEmpty() { return values.isEmpty(); }

        ObjectNode toNode(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            values.forEach(node::set);
            return node;
        }
    }

    // Builds ExpressionAttributeNames with #n0, #n1, … aliases
    private static class ExprAttrNameBuilder {
        private final Map<String, String> aliasToName = new LinkedHashMap<>();
        private final Map<String, String> nameToAlias = new LinkedHashMap<>();
        private int idx = 0;

        String alias(String name) {
            return nameToAlias.computeIfAbsent(name, n -> {
                String a = "#n" + idx++;
                aliasToName.put(a, n);
                return a;
            });
        }

        String alias(Path path) {
            StringBuilder expression = new StringBuilder();
            for (Seg segment : path.segments()) {
                switch (segment) {
                    case Seg.Name name -> {
                        if (!expression.isEmpty()) {
                            expression.append('.');
                        }
                        expression.append(alias(name.value()));
                    }
                    case Seg.Index index -> expression.append('[').append(index.value()).append(']');
                }
            }
            return expression.toString();
        }

        boolean isEmpty() { return aliasToName.isEmpty(); }

        ObjectNode toNode(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            aliasToName.forEach(node::put);
            return node;
        }
    }
}
