package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A DynamoDB backend with no state: each typed call is recorded with the account and region it
 * was given and answered with a canned value, so a test can see exactly what a consumer asked for.
 */
public final class RecordingDynamoDbBackend implements DynamoDbItemAccess, DynamoDbTableAccess {

    /** One typed call. {@code region} is null for {@code resources}, which takes an account only. */
    public record Invocation(String method, String accountId, String region) {

        public static Invocation of(String method, Scope scope) {
            return new Invocation(method, scope.accountId(), scope.region());
        }
    }

    public static final String TABLE_NAME = "recorded-table";
    public static final String TABLE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE_NAME;
    public static final ExplorerResource RESOURCE = new ExplorerResource(TABLE_ARN, "dynamodb:table", "dynamodb",
            "us-east-1", "000000000000", Instant.EPOCH, Map.of());

    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    private TableDefinition record(String method, Scope scope) {
        invocations.add(Invocation.of(method, scope));
        TableDefinition table = new TableDefinition();
        table.setTableName(TABLE_NAME);
        table.setTableArn(TABLE_ARN);
        table.setKeySchema(List.of(new KeySchemaElement("id", "HASH")));
        return table;
    }

    @Override
    public void putItem(Scope scope, String tableName, JsonNode item, String conditionExpression,
                        JsonNode expressionAttributeNames, JsonNode expressionAttributeValues) {
        record("putItem", scope);
    }

    @Override
    public JsonNode getItem(Scope scope, String tableName, JsonNode key) {
        record("getItem", scope);
        return null;
    }

    @Override
    public void deleteItem(Scope scope, String tableName, JsonNode key, String conditionExpression,
                           JsonNode expressionAttributeNames, JsonNode expressionAttributeValues) {
        record("deleteItem", scope);
    }

    @Override
    public JsonNode updateItem(Scope scope, String tableName, JsonNode key, JsonNode attributeUpdates,
                               String updateExpression, JsonNode expressionAttributeNames,
                               JsonNode expressionAttributeValues, String returnValues, String conditionExpression) {
        record("updateItem", scope);
        return null;
    }

    @Override
    public ScanPage scan(Scope scope, String tableName, String filterExpression, JsonNode expressionAttributeNames,
                         JsonNode expressionAttributeValues, JsonNode scanFilter, Integer limit,
                         JsonNode exclusiveStartKey) {
        record("scan", scope);
        return new ScanPage(List.of(), 0, null);
    }

    @Override
    public TableDefinition createTable(Scope scope, String tableName, List<KeySchemaElement> keySchema,
                                       List<AttributeDefinition> attributeDefinitions, Long readCapacity,
                                       Long writeCapacity, List<GlobalSecondaryIndex> globalSecondaryIndexes,
                                       List<LocalSecondaryIndex> localSecondaryIndexes) {
        return record("createTable", scope);
    }

    @Override
    public TableDefinition describeTable(Scope scope, String tableName) {
        return record("describeTable", scope);
    }

    @Override
    public Optional<TableDefinition> findTable(Scope scope, String tableName) {
        return Optional.of(record("findTable", scope));
    }

    @Override
    public void deleteTable(Scope scope, String tableName) {
        record("deleteTable", scope);
    }

    @Override
    public TableDefinition enableStream(Scope scope, String tableName, String viewType) {
        return record("enableStream", scope);
    }

    @Override
    public TableDefinition disableStream(Scope scope, String tableName) {
        return record("disableStream", scope);
    }

    @Override
    public Map<String, String> listTagsOfResource(Scope scope, String resourceArn) {
        record("listTagsOfResource", scope);
        return Map.of();
    }

    @Override
    public void tagResource(Scope scope, String resourceArn, Map<String, String> tags) {
        record("tagResource", scope);
    }

    @Override
    public void untagResource(Scope scope, String resourceArn, List<String> tagKeys) {
        record("untagResource", scope);
    }

    @Override
    public TableDefinition applyReplicaUpdates(Scope scope, String tableName, List<String> addRegions,
                                               List<String> removeRegions) {
        return record("applyReplicaUpdates", scope);
    }

    @Override
    public TableDefinition ensureGlobalTable(Scope scope, String tableName) {
        return record("ensureGlobalTable", scope);
    }

    @Override
    public List<ExplorerResource> resources(String accountId) {
        invocations.add(new Invocation("resources", accountId, null));
        return List.of(RESOURCE);
    }
}
