package io.floci.aws.services.redshift.spectrum;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A streaming materialized view over a topic of an MSK external schema. {@code select} is the view's query as
 * written, which reads the topic's relation of the external schema; {@code checkpoints} holds, per partition,
 * the offset of the last record a committed refresh loaded, so the next refresh starts at the offset after it.
 */
@RegisterForReflection
public record MskViewBinding(String accountId, String clusterKey, String databaseName, String viewSchema,
                             String viewName, String topicSchema, String topicName, String select,
                             Map<String, Long> checkpoints) implements SchemaBinding {
    /** Separates the schema from the view in the registry key; a quoted identifier cannot be told apart otherwise. */
    private static final char KEY_SEPARATOR = '\u001f';

    public MskViewBinding {
        SchemaBinding.require(accountId, "accountId");
        SchemaBinding.require(clusterKey, "clusterKey");
        SchemaBinding.require(databaseName, "databaseName");
        SchemaBinding.require(viewSchema, "viewSchema");
        SchemaBinding.require(viewName, "viewName");
        SchemaBinding.require(topicSchema, "topicSchema");
        SchemaBinding.require(topicName, "topicName");
        SchemaBinding.require(select, "select");
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

    /** This view with each partition moved to the given last loaded offset. */
    public MskViewBinding withCheckpoints(Map<Integer, Long> lastLoaded) {
        Map<String, Long> updated = new LinkedHashMap<>(checkpoints);
        lastLoaded.forEach((partition, offset) -> updated.put(Integer.toString(partition), offset));
        return new MskViewBinding(accountId, clusterKey, databaseName, viewSchema, viewName, topicSchema,
                topicName, select, updated);
    }
}
