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
        if (accessPath.isGlobalSecondaryIndex()) {
            Set<String> projected = accessPath.projectedAttributeNames(table);
            List<String> unprojected = stmt.columns().stream()
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

        String pkName = accessPath.partitionKeyName();
        String skName = accessPath.sortKeyName();

        Cond.Eq pkEq = null;
        Cond skCond = null;
        List<Cond> filterConds = new ArrayList<>();

        for (Cond c : stmt.where()) {
            String attr = condAttr(c);
            if (pkName.equals(attr)) {
                if (c instanceof Cond.Eq eq) {
                    pkEq = eq;
                } else {
                    filterConds.add(c);
                }
            } else if (skName != null && skName.equals(attr)) {
                skCond = c;
            } else {
                filterConds.add(c);
            }
        }

        List<JsonNode> items;
        JsonNode lastEvaluatedKey = null;

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
            JsonNode exclusiveStartKey = decodeNextToken(ctx.nextToken(), ctx.tokenBinding());
            DynamoDbAccessPathValidator.validateExclusiveStartKey(exclusiveStartKey, table, accessPath, true);
            DynamoDbService.ScanResult result = service.scan(stmt.table(), fe,
                    ean.isEmpty() ? null : ean.toNode(mapper),
                    eav.isEmpty() ? null : eav.toNode(mapper),
                    null, ctx.limit(), exclusiveStartKey, accessPath.indexName(), region);
            items = result.items();
            lastEvaluatedKey = result.lastEvaluatedKey();
        } else if (accessPath.kind() == DynamoDbAccessPath.Kind.TABLE && skName == null
                && skCond == null && filterConds.isEmpty()) {
            ObjectNode key = mapper.createObjectNode();
            key.set(pkName, toTypedNode(pkEq.val()));
            JsonNode item = service.getItem(stmt.table(), key, region);
            items = item != null ? List.of(item) : Collections.emptyList();
        } else {
            ExprAttrBuilder eav = new ExprAttrBuilder();
            ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
            String kce = buildKce(pkEq, skCond, pkName, skName, eav, ean);
            String fe = buildFe(filterConds, eav, ean);
            JsonNode exclusiveStartKey = decodeNextToken(ctx.nextToken(), ctx.tokenBinding());
            DynamoDbAccessPathValidator.validateExclusiveStartKey(exclusiveStartKey, table, accessPath, false);
            DynamoDbService.QueryResult result = service.query(
                    stmt.table(), null, eav.toNode(mapper), kce, fe,
                    ctx.limit(), null, accessPath.indexName(), exclusiveStartKey,
                    ean.isEmpty() ? null : ean.toNode(mapper), region);
            items = result.items();
            lastEvaluatedKey = result.lastEvaluatedKey();
        }

        // Apply column projection for non-* SELECT. Projects against the full
        // item: an LSI read can reach attributes outside the index projection
        // (characterised on real AWS, eu-west-1, 2026-09-02).
        if (!stmt.columns().isEmpty()) {
            ExprAttrNameBuilder projEan = new ExprAttrNameBuilder();
            String proj = stmt.columns().stream()
                    .map(projEan::alias)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            ObjectNode eanNode = projEan.isEmpty() ? null : projEan.toNode(mapper);
            items = items.stream()
                    .map(item -> (JsonNode) ProjectionEvaluator.project(item, proj, eanNode))
                    .toList();
        }

        // SELECT * over an index reads the projection, not the whole item.
        if (stmt.columns().isEmpty() && accessPath.isIndex()) {
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
            case Cond.Eq eq   -> ean.alias(eq.attr()) + " = " + eav.add(toTypedNode(eq.val()));
            case Cond.Cmp cmp -> ean.alias(cmp.attr()) + " " + cmp.op() + " " + eav.add(toTypedNode(cmp.val()));
            case Cond.Between b ->
                ean.alias(b.attr()) + " BETWEEN " + eav.add(toTypedNode(b.lo())) + " AND " + eav.add(toTypedNode(b.hi()));
            case Cond.BeginsWith bw ->
                "begins_with(" + ean.alias(bw.attr()) + ", " + eav.add(toTypedNode(bw.prefix())) + ")";
        };
    }

    private static String condAttr(Cond c) {
        return switch (c) {
            case Cond.Eq eq         -> eq.attr();
            case Cond.Cmp cmp       -> cmp.attr();
            case Cond.Between b     -> b.attr();
            case Cond.BeginsWith bw -> bw.attr();
        };
    }

    // --- INSERT ---

    private JsonNode executeInsert(Stmt.Insert stmt, String region) {
        TableDefinition table = service.describeTable(stmt.table(), region);
        String pkName = table.getPartitionKeyName();

        ObjectNode item = mapper.createObjectNode();
        stmt.item().forEach((k, v) -> item.set(k, toTypedNode(v)));

        try {
            service.putItem(stmt.table(), item, "attribute_not_exists(" + pkName + ")", null, null, region, "NONE");
        } catch (ConditionalCheckFailedException e) {
            throw new AwsException("DuplicateItemException", "Duplicate primary key exists in table", 400);
        }
        return emptyItemsResponse();
    }

    // --- UPDATE ---

    private JsonNode executeUpdate(Stmt.Update stmt, String region) {
        TableDefinition table = service.describeTable(stmt.table(), region);
        ObjectNode key = buildKey(table, stmt.where());

        ExprAttrBuilder eav = new ExprAttrBuilder();
        ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
        StringBuilder ue = new StringBuilder();
        if (!stmt.sets().isEmpty()) {
            ue.append("SET ");
            List<String> parts = new ArrayList<>();
            for (Assign a : stmt.sets()) {
                parts.add(ean.alias(a.attr()) + " = " + eav.add(toTypedNode(a.val())));
            }
            ue.append(String.join(", ", parts));
        }
        if (!stmt.removes().isEmpty()) {
            if (!ue.isEmpty()) ue.append(" ");
            ue.append("REMOVE ");
            ue.append(stmt.removes().stream().map(ean::alias).reduce((a, b) -> a + ", " + b).orElse(""));
        }

        service.updateItem(stmt.table(), key, null, ue.toString(),
                ean.isEmpty() ? null : ean.toNode(mapper),
                eav.isEmpty() ? null : eav.toNode(mapper), "NONE", region);
        return emptyItemsResponse();
    }

    // --- DELETE ---

    private JsonNode executeDelete(Stmt.Delete stmt, String region) {
        TableDefinition table = service.describeTable(stmt.table(), region);
        ObjectNode key = buildKey(table, stmt.where());
        service.deleteItem(stmt.table(), key, null, null, null, region, "NONE");
        return emptyItemsResponse();
    }

    // --- Transaction item builder ---

    JsonNode toTransactItem(Stmt stmt, String region) {
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
                TableDefinition table = service.describeTable(upd.table(), region);
                ObjectNode key = buildKey(table, upd.where());
                ExprAttrBuilder eav = new ExprAttrBuilder();
                ExprAttrNameBuilder ean = new ExprAttrNameBuilder();
                StringBuilder ue = new StringBuilder();
                if (!upd.sets().isEmpty()) {
                    ue.append("SET ");
                    List<String> parts = new ArrayList<>();
                    for (Assign a : upd.sets()) {
                        parts.add(ean.alias(a.attr()) + " = " + eav.add(toTypedNode(a.val())));
                    }
                    ue.append(String.join(", ", parts));
                }
                if (!upd.removes().isEmpty()) {
                    if (!ue.isEmpty()) ue.append(" ");
                    ue.append("REMOVE ");
                    ue.append(upd.removes().stream().map(ean::alias).reduce((a, b) -> a + ", " + b).orElse(""));
                }
                ObjectNode update = mapper.createObjectNode();
                update.put("TableName", upd.table());
                update.set("Key", key);
                update.put("UpdateExpression", ue.toString());
                if (!eav.isEmpty()) update.set("ExpressionAttributeValues", eav.toNode(mapper));
                if (!ean.isEmpty()) update.set("ExpressionAttributeNames", ean.toNode(mapper));
                txItem.set("Update", update);
            }
            case Stmt.Delete del -> {
                TableDefinition table = service.describeTable(del.table(), region);
                ObjectNode key = buildKey(table, del.where());
                ObjectNode delete = mapper.createObjectNode();
                delete.put("TableName", del.table());
                delete.set("Key", key);
                txItem.set("Delete", delete);
            }
            case Stmt.Select ignored ->
                throw new AwsException("ValidationException",
                        "SELECT is not supported inside ExecuteTransaction", 400);
        }
        return txItem;
    }

    // --- Shared helpers ---

    private ObjectNode buildKey(TableDefinition table, List<Cond> where) {
        String pkName = table.getPartitionKeyName();
        String skName = table.getSortKeyName();
        ObjectNode key = mapper.createObjectNode();
        for (Cond c : where) {
            if (c instanceof Cond.Eq eq) {
                if (pkName.equals(eq.attr()) || (skName != null && skName.equals(eq.attr()))) {
                    key.set(eq.attr(), toTypedNode(eq.val()));
                }
            }
        }
        if (!key.has(pkName)) {
            throw new AwsException("ValidationException",
                    "WHERE clause must include an equality condition on the partition key", 400);
        }
        return key;
    }

    JsonNode toTypedNode(PVal val) {
        ObjectNode node = mapper.createObjectNode();
        switch (val) {
            case PVal.Str s  -> node.put("S", s.v());
            case PVal.Num n  -> node.put("N", DynamoDbNumberUtils.validateAndNormalize(n.v()));
            case PVal.Bool b -> node.put("BOOL", b.v());
            case PVal.Null ignored -> node.put("NULL", true);
        }
        return node;
    }

    private ObjectNode emptyItemsResponse() {
        ObjectNode resp = mapper.createObjectNode();
        resp.set("Items", mapper.createArrayNode());
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

        boolean isEmpty() { return aliasToName.isEmpty(); }

        ObjectNode toNode(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            aliasToName.forEach(node::put);
            return node;
        }
    }
}
