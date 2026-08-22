package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

record DynamoDbAccessPath(String indexName, Kind kind, List<KeySchemaElement> keySchema,
                          String projectionType, List<String> nonKeyAttributes) {

    enum Kind { TABLE, GLOBAL_SECONDARY_INDEX, LOCAL_SECONDARY_INDEX }

    static DynamoDbAccessPath resolve(TableDefinition table, String indexName) {
        if (indexName == null) {
            return new DynamoDbAccessPath(null, Kind.TABLE, table.getKeySchema(), "ALL", List.of());
        }

        GlobalSecondaryIndex gsi = table.findGsi(indexName).orElse(null);
        if (gsi != null) {
            return new DynamoDbAccessPath(indexName, Kind.GLOBAL_SECONDARY_INDEX,
                    gsi.getKeySchema(), gsi.getProjectionType(), gsi.getNonKeyAttributes());
        }

        LocalSecondaryIndex lsi = table.findLsi(indexName).orElse(null);
        if (lsi != null) {
            return new DynamoDbAccessPath(indexName, Kind.LOCAL_SECONDARY_INDEX,
                    lsi.getKeySchema(), lsi.getProjectionType(), lsi.getNonKeyAttributes());
        }

        throw new AwsException("ValidationException",
                "The table does not have the specified index: " + indexName, 400);
    }

    DynamoDbAccessPath {
        keySchema = keySchema != null ? List.copyOf(keySchema) : List.of();
        projectionType = projectionType != null ? projectionType : "ALL";
        nonKeyAttributes = nonKeyAttributes != null ? List.copyOf(nonKeyAttributes) : List.of();
    }

    String partitionKeyName() {
        return partitionKeyNames().getFirst();
    }

    List<String> partitionKeyNames() {
        return keySchema.stream()
                .filter(key -> "HASH".equals(key.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .toList();
    }

    List<String> sortKeyNames() {
        return keySchema.stream()
                .filter(key -> "RANGE".equals(key.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .toList();
    }

    String sortKeyName() {
        return sortKeyNames().stream().findFirst().orElse(null);
    }

    Set<String> keyAttributeNames() {
        return keySchema.stream()
                .map(KeySchemaElement::getAttributeName)
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean isIndex() {
        return kind != Kind.TABLE;
    }

    boolean isGlobalSecondaryIndex() {
        return kind == Kind.GLOBAL_SECONDARY_INDEX;
    }

    Set<String> projectedAttributeNames(TableDefinition table) {
        var projected = table.getKeySchema().stream()
                .map(KeySchemaElement::getAttributeName)
                .collect(Collectors.toSet());
        projected.addAll(keyAttributeNames());
        if ("INCLUDE".equals(projectionType)) {
            projected.addAll(nonKeyAttributes);
        }
        return Set.copyOf(projected);
    }
}
