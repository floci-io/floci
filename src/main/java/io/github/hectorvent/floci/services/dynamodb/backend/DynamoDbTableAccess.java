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

    TableDefinition enableStream(Scope scope, String tableName, String viewType);

    TableDefinition disableStream(Scope scope, String tableName);

    Map<String, String> listTagsOfResource(Scope scope, String resourceArn);

    void tagResource(Scope scope, String resourceArn, Map<String, String> tags);

    void untagResource(Scope scope, String resourceArn, List<String> tagKeys);

    TableDefinition applyReplicaUpdates(Scope scope, String tableName, List<String> addRegions,
                                        List<String> removeRegions);

    TableDefinition ensureGlobalTable(Scope scope, String tableName);

    List<ExplorerResource> resources(String accountId);
}
