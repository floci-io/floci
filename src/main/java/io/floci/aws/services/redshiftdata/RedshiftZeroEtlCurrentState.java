package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The table a DynamoDB zero-ETL integration projects its source's current item state into, shaped as AWS shapes
 * it: in the target's {@code public} schema under the DynamoDB table's name, one column per key attribute and a
 * {@code value} column holding the whole item in DynamoDB JSON. AWS types {@code value} as SUPER; the PostgreSQL
 * data plane holds it as {@code jsonb}, so {@code value->'payload'->'L'->0->>'S'} reads what
 * {@code "value"."payload"."L"[0]."S"} reads on Redshift.
 *
 * <p>Key columns take the DynamoDB key types: a string is {@code TEXT}, a number an unbounded {@code NUMERIC},
 * which holds every DynamoDB number exactly where AWS's {@code NUMERIC(38,18)} would round or overflow, and binary
 * {@code BYTEA}. Every other attribute lives in {@code value} alone, so items that differ in shape need no schema
 * change.
 */
public final class RedshiftZeroEtlCurrentState {

    /**
     * The longest identifier PostgreSQL keeps whole. AWS replicates names up to 127 characters, but a longer name
     * would be truncated here, and two names sharing their first 63 bytes would silently share a table.
     */
    static final int MAX_IDENTIFIER_BYTES = 63;
    static final String VALUE_COLUMN = "value";

    /** One projected change: the item to upsert under its keys, or, with a null item, the keys to delete. */
    public record Change(JsonNode keys, JsonNode item) {
        public boolean isDelete() {
            return item == null;
        }
    }

    record KeyColumn(String name, String dynamoDbType) {}

    private final String tableName;
    private final List<KeyColumn> keyColumns;

    RedshiftZeroEtlCurrentState(String tableName, List<KeyColumn> keyColumns) {
        this.tableName = tableName;
        this.keyColumns = List.copyOf(keyColumns);
    }

    /** The projection of a DynamoDB table, its partition key column before its sort key's. */
    public static RedshiftZeroEtlCurrentState of(TableDefinition table) {
        String tableName = table.getTableName();
        if (tableName == null || tableName.isBlank() || tooLong(tableName)) {
            throw new AwsException("InvalidParameterValue", "Table names from DynamoDB longer than "
                    + MAX_IDENTIFIER_BYTES + " bytes, the PostgreSQL identifier limit, are not supported.", 400);
        }
        List<KeyColumn> columns = new ArrayList<>();
        for (String keyType : List.of("HASH", "RANGE")) {
            for (KeySchemaElement element : table.getKeySchema()) {
                if (keyType.equals(element.getKeyType())) {
                    columns.add(new KeyColumn(element.getAttributeName(), attributeType(table, element.getAttributeName())));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new AwsException("InternalFailure", "DynamoDB table " + tableName + " has no key schema.", 500);
        }
        for (KeyColumn column : columns) {
            if (tooLong(column.name())) {
                throw new AwsException("InvalidParameterValue", "Column names for the partition key or sort key "
                        + "longer than " + MAX_IDENTIFIER_BYTES + " bytes, the PostgreSQL identifier limit, are not "
                        + "supported.", 400);
            }
            if (VALUE_COLUMN.equals(column.name())) {
                throw new AwsException("InvalidParameterValue",
                        "A key attribute named " + VALUE_COLUMN + " collides with the replicated item column.", 400);
            }
        }
        return new RedshiftZeroEtlCurrentState(tableName, columns);
    }

    private static boolean tooLong(String identifier) {
        return identifier.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES;
    }

    private static String attributeType(TableDefinition table, String attributeName) {
        for (AttributeDefinition definition : table.getAttributeDefinitions()) {
            if (attributeName.equals(definition.getAttributeName())) {
                return definition.getAttributeType();
            }
        }
        throw new AwsException("InternalFailure",
                "DynamoDB table " + table.getTableName() + " defines no type for key attribute " + attributeName + ".", 500);
    }

    public String tableName() {
        return tableName;
    }

    List<String> keyColumnNames() {
        return keyColumns.stream().map(KeyColumn::name).toList();
    }

    /**
     * The columns a table of this name must have for the integration to write it, the keys then {@code value}, with
     * the type each has as {@code information_schema.columns.data_type} names it.
     */
    Map<String, String> expectedColumnTypes() {
        Map<String, String> columns = new LinkedHashMap<>();
        for (KeyColumn column : keyColumns) {
            columns.put(column.name(), sqlType(column).toLowerCase(Locale.ROOT));
        }
        columns.put(VALUE_COLUMN, "jsonb");
        return columns;
    }

    /** Empties the table, for an integration starting its backfill over a table a previous one left behind. */
    String resetSql() {
        return "TRUNCATE TABLE " + qualifiedName();
    }

    /**
     * The change a stream record makes to the current state: the record names a key, and the key is projected as
     * the source holds it now, read through {@code lookup}. The item is upserted, or deleted when the source no
     * longer has it. The record's own images are not used: a record replayed after the backfill scan read a newer
     * version of its item, or after the item changed again, would otherwise put its older image over the newer one
     * until the later record was replayed. Read as it is now, every record of a key projects the same state, so
     * replay order cannot regress a row. A record without keys projects nothing.
     */
    public static Change changeOf(JsonNode record, Function<JsonNode, JsonNode> lookup) {
        JsonNode keys = record.path("dynamodb").get("Keys");
        if (keys == null || !keys.isObject() || keys.isEmpty()) {
            return null;
        }
        JsonNode current = lookup.apply(keys);
        return new Change(keys, current != null && current.isObject() ? current : null);
    }

    String ddl() {
        String columns = keyColumns.stream()
                .map(column -> quote(column.name()) + " " + sqlType(column) + " NOT NULL")
                .collect(Collectors.joining(", "));
        String primaryKey = keyColumns.stream().map(column -> quote(column.name())).collect(Collectors.joining(", "));
        return "CREATE TABLE IF NOT EXISTS " + qualifiedName() + " (" + columns + ", "
                + quote(VALUE_COLUMN) + " JSONB NOT NULL, PRIMARY KEY (" + primaryKey + "))";
    }

    String upsertSql() {
        String names = keyColumns.stream().map(column -> quote(column.name())).collect(Collectors.joining(", "));
        String placeholders = keyColumns.stream().map(column -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + qualifiedName() + " (" + names + ", " + quote(VALUE_COLUMN) + ") VALUES ("
                + placeholders + ", ?::jsonb) ON CONFLICT (" + names + ") DO UPDATE SET "
                + quote(VALUE_COLUMN) + " = EXCLUDED." + quote(VALUE_COLUMN);
    }

    String deleteSql() {
        return "DELETE FROM " + qualifiedName() + " WHERE " + keyColumns.stream()
                .map(column -> quote(column.name()) + " = ?").collect(Collectors.joining(" AND "));
    }

    /** The {@code value} parameter of {@link #upsertSql()}, after the key parameters. */
    int valueParameterIndex() {
        return keyColumns.size() + 1;
    }

    /** Binds the record's key attributes as the statement's first parameters, in key column order. */
    void bindKeys(PreparedStatement statement, JsonNode keys) throws SQLException {
        int index = 1;
        for (KeyColumn column : keyColumns) {
            JsonNode attribute = keys.get(column.name());
            JsonNode typed = attribute == null ? null : attribute.get(column.dynamoDbType());
            if (typed == null || typed.isNull()) {
                throw new AwsException("InternalFailure", "A DynamoDB stream record of " + tableName
                        + " carries no " + column.dynamoDbType() + " value for key attribute " + column.name() + ".", 500);
            }
            switch (column.dynamoDbType()) {
                case "N" -> statement.setBigDecimal(index, new BigDecimal(typed.asText()));
                case "B" -> statement.setBytes(index, Base64.getDecoder().decode(typed.asText()));
                default -> statement.setString(index, typed.asText());
            }
            index++;
        }
    }

    String qualifiedName() {
        return "public." + quote(tableName);
    }

    private static String sqlType(KeyColumn column) {
        return switch (column.dynamoDbType()) {
            case "N" -> "NUMERIC";
            case "B" -> "BYTEA";
            default -> "TEXT";
        };
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
