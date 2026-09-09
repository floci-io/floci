package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Maps JDBC {@link ResultSetMetaData} onto the AWS RDS Data API
 * {@code ColumnMetadata} shape.
 *
 * <p>Every documented field is emitted for each column. {@code label} matters
 * as much as {@code name}: AWS clients commonly hydrate rows by the result-set
 * label, so a response carrying only {@code name} collapses every row to a
 * single key.
 *
 * <p>{@code type} is a {@link java.sql.Types} code and {@code typeName} is the
 * engine's own type name, exactly as AWS reports them (for example {@code 4}
 * and {@code int4} for a PostgreSQL integer). {@code arrayBaseColumnType} is
 * always {@code 0} because array columns are not mapped to {@code arrayValue}
 * fields yet.
 */
final class RdsDataColumnMetadata {

    private RdsDataColumnMetadata() {
    }

    static ArrayNode toColumnMetadata(ObjectMapper objectMapper, ResultSetMetaData meta) throws SQLException {
        ArrayNode columns = objectMapper.createArrayNode();
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            columns.add(toColumn(objectMapper, meta, index));
        }
        return columns;
    }

    private static ObjectNode toColumn(ObjectMapper objectMapper, ResultSetMetaData meta, int index)
            throws SQLException {
        String name = meta.getColumnName(index);
        String label = meta.getColumnLabel(index);

        ObjectNode column = objectMapper.createObjectNode();
        column.put("arrayBaseColumnType", 0);
        column.put("isAutoIncrement", meta.isAutoIncrement(index));
        column.put("isCaseSensitive", meta.isCaseSensitive(index));
        column.put("isCurrency", meta.isCurrency(index));
        column.put("isSigned", meta.isSigned(index));
        column.put("label", firstNonBlank(label, name));
        column.put("name", firstNonBlank(name, label));
        column.put("nullable", meta.isNullable(index));
        column.put("precision", meta.getPrecision(index));
        column.put("scale", meta.getScale(index));
        column.put("schemaName", orEmpty(meta.getSchemaName(index)));
        column.put("tableName", orEmpty(meta.getTableName(index)));
        column.put("type", meta.getColumnType(index));
        column.put("typeName", orEmpty(meta.getColumnTypeName(index)));
        return column;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return orEmpty(fallback);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
