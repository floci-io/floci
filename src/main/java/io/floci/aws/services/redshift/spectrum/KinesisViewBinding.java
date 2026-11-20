package io.floci.aws.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A streaming materialized view over a Kinesis stream. {@code select} is the view's query as written, which
 * reads the stream's relation of the external schema; {@code checkpoints} holds, per shard, the sequence number
 * of the last record a committed refresh loaded, so the next refresh starts after it.
 */
@RegisterForReflection
public record KinesisViewBinding(String accountId, String clusterKey, String databaseName, String viewSchema,
                                 String viewName, String streamSchema, String streamName, String select,
                                 Map<String, String> checkpoints) implements SchemaBinding {
    /** Separates the schema from the view in the registry key; a quoted identifier cannot be told apart otherwise. */
    private static final char KEY_SEPARATOR = '\u001f';

    public KinesisViewBinding {
        require(accountId, "accountId");
        require(clusterKey, "clusterKey");
        require(databaseName, "databaseName");
        require(viewSchema, "viewSchema");
        require(viewName, "viewName");
        require(streamSchema, "streamSchema");
        require(streamName, "streamName");
        require(select, "select");
        checkpoints = checkpoints == null ? Map.of() : Map.copyOf(checkpoints);
    }

    @Override
    public String schemaName() {
        return viewSchema;
    }

    @Override
    public String bindingName() {
        return nameOf(viewSchema, viewName);
    }

    /** The registry name of the view {@code viewName} of {@code viewSchema}. */
    static String nameOf(String viewSchema, String viewName) {
        return viewSchema + KEY_SEPARATOR + viewName;
    }

    public KinesisViewBinding withCheckpoint(String shardId, String sequenceNumber) {
        Map<String, String> updated = new LinkedHashMap<>(checkpoints);
        updated.put(shardId, sequenceNumber);
        return new KinesisViewBinding(accountId, clusterKey, databaseName, viewSchema, viewName, streamSchema,
                streamName, select, updated);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
