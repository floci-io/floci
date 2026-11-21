package io.floci.aws.services.dynamodb.backend;

import io.floci.aws.core.resource.ExplorerResource;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.LocalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.TableDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Typed table operations for Java consumers that manage DynamoDB tables directly. */
public interface DynamoDbTableAccess {

    /** A table's time-to-live setting, as DescribeTimeToLive reports it. */
    record TimeToLive(boolean enabled, String attributeName) {
    }

    /** The provisioned capacity of a table or an index. */
    record CapacityUnits(long readCapacityUnits, long writeCapacityUnits) {
    }

    /** A warm throughput a table or an index should have. A null member is left as it is. */
    record WarmUnits(Long readUnitsPerSecond, Long writeUnitsPerSecond) {
    }

    /** What a template declares for one table or index. A null member is not declared. */
    record ThroughputSpec(CapacityUnits capacity, WarmUnits warm) {
    }

    /** The throughput to apply to a table and to some of its indexes, by index name. */
    record ThroughputChange(ThroughputSpec table, Map<String, ThroughputSpec> indexes) {
    }

    TableDefinition createTable(Scope scope, String tableName, List<KeySchemaElement> keySchema,
                                List<AttributeDefinition> attributeDefinitions, Long readCapacity,
                                Long writeCapacity, List<GlobalSecondaryIndex> globalSecondaryIndexes,
                                List<LocalSecondaryIndex> localSecondaryIndexes);

    /**
     * Creates a table with the declared throughput. An index takes its own capacity and warm
     * throughput from its {@link GlobalSecondaryIndex}.
     */
    TableDefinition createTable(Scope scope, String tableName, List<KeySchemaElement> keySchema,
                                List<AttributeDefinition> attributeDefinitions, boolean onDemand,
                                ThroughputSpec throughput, List<GlobalSecondaryIndex> globalSecondaryIndexes,
                                List<LocalSecondaryIndex> localSecondaryIndexes);

    /** One UpdateTable that changes the capacity and warm throughput of the table and of the named indexes. */
    TableDefinition updateThroughput(Scope scope, String tableName, ThroughputChange change);

    TableDefinition createOnDemandTable(Scope scope, String tableName, List<KeySchemaElement> keySchema,
                                        List<AttributeDefinition> attributeDefinitions,
                                        List<GlobalSecondaryIndex> globalSecondaryIndexes,
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

    TableDefinition enableKinesisStreamingDestination(Scope scope, String tableName, String streamArn,
                                                      String precision);

    TableDefinition disableKinesisStreamingDestination(Scope scope, String tableName, String streamArn);

    TableDefinition updateKinesisStreamingDestination(Scope scope, String tableName, String streamArn,
                                                      String precision);

    TimeToLive timeToLive(Scope scope, String tableName);

    void updateTimeToLive(Scope scope, String tableName, String attributeName, boolean enabled);

    Map<String, String> listTagsOfResource(Scope scope, String resourceArn);

    void tagResource(Scope scope, String resourceArn, Map<String, String> tags);

    void untagResource(Scope scope, String resourceArn, List<String> tagKeys);

    /**
     * The tags held for the table {@code arn} names, or for the table whose index it names, as an index inherits its
     * table's tags, in the account and Region the ARN names, read without changing anything; empty for an ARN naming
     * no table or index held, or any other resource.
     */
    Map<String, String> storedTags(String arn);

    TableDefinition applyReplicaUpdates(Scope scope, String tableName, List<String> addRegions,
                                        List<String> removeRegions);

    TableDefinition ensureGlobalTable(Scope scope, String tableName);

    List<ExplorerResource> resources(String accountId);
}
