package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * DynamoDB in {@code persistent} mode with its items in the {@code per-table} layout, split into
 * four shards, on a directory of its own. Every other service stays on the test default, memory.
 * {@code DynamoDbPartitionedStorageIntegrationTest} relies on all of it: it checks that the
 * configuration reaches the item store and that a write lands in shard files.
 */
public class DynamoDbPartitionedStorageProfile implements QuarkusTestProfile {

    public static final String STORAGE_DIR = "target/dynamodb-partitioned-it";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.storage.services.dynamodb.mode", "persistent",
                "floci.storage.services.dynamodb.item-layout", "per-table",
                "floci.storage.services.dynamodb.item-shards", "4",
                "floci.storage.persistent-path", STORAGE_DIR);
    }
}
