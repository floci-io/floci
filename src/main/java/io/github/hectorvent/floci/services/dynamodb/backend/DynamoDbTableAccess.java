package io.github.hectorvent.floci.services.dynamodb.backend;

import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Typed table operations for Java consumers that manage DynamoDB tables directly. */
public interface DynamoDbTableAccess {

    /** A table's time-to-live setting, as DescribeTimeToLive reports it. */
    record TimeToLive(boolean enabled, String attributeName) {
    }

    TableDefinition createTable(Scope scope, String tableName, List<KeySchemaElement> keySchema,
                                List<AttributeDefinition> attributeDefinitions, Long readCapacity,
                                Long writeCapacity, List<GlobalSecondaryIndex> globalSecondaryIndexes,
                                List<LocalSecondaryIndex> localSecondaryIndexes);

    TableDefinition describeTable(Scope scope, String tableName);

    Optional<TableDefinition> findTable(Scope scope, String tableName);

    /**
     * The direct engine delete, with no deletion-protection check. The public DeleteTable wire
     * operation, which checks deletion protection, goes through {@link DynamoDbOperations}.
     */
    void deleteTable(Scope scope, String tableName);

    /**
     * UpdateTable with a billing mode and table throughput, or with the global secondary indexes to
     * create and delete; a null billing mode or capacity, or an empty list, leaves that part as it
     * is. As on UpdateTable, one call does not change an index and the capacity together, and creates
     * or deletes at most one index; {@code attributeDefinitions} defines the key attributes of every
     * index it creates.
     */
    TableDefinition updateTable(Scope scope, String tableName, String billingMode, Long readCapacity,
                                Long writeCapacity, List<AttributeDefinition> attributeDefinitions,
                                List<GlobalSecondaryIndex> gsiCreates, List<String> gsiDeletes);

    TableDefinition enableStream(Scope scope, String tableName, String viewType);

    TableDefinition disableStream(Scope scope, String tableName);

    TimeToLive timeToLive(Scope scope, String tableName);

    void updateTimeToLive(Scope scope, String tableName, String attributeName, boolean enabled);

    Map<String, String> listTagsOfResource(Scope scope, String resourceArn);

    void tagResource(Scope scope, String resourceArn, Map<String, String> tags);

    void untagResource(Scope scope, String resourceArn, List<String> tagKeys);

    TableDefinition applyReplicaUpdates(Scope scope, String tableName, List<String> addRegions,
                                        List<String> removeRegions);

    TableDefinition ensureGlobalTable(Scope scope, String tableName);

    List<ExplorerResource> resources(String accountId);
}
