package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Maps JDBC {@link ResultSetMetaData} onto the Redshift Data API
 * {@code ColumnMetadata} shape. Every documented field is emitted for each
 * column; {@code label} matters as much as {@code name} because AWS clients
 * commonly hydrate rows by the result-set label.
 */
final class RedshiftDataColumnMetadata {

    private RedshiftDataColumnMetadata() {
    }

    static ArrayNode toColumnMetadata(ObjectMapper om, ResultSetMetaData meta) throws SQLException {
        ArrayNode columns = om.createArrayNode();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(column(om, meta, i));
        }
        return columns;
    }

    private static ObjectNode column(ObjectMapper om, ResultSetMetaData meta, int i) throws SQLException {
        String name = orEmpty(meta.getColumnName(i));
        String label = orEmpty(meta.getColumnLabel(i));
        ObjectNode column = om.createObjectNode();
        column.put("name", firstNonBlank(name, label));
        column.put("label", firstNonBlank(label, name));
        column.put("typeName", orEmpty(meta.getColumnTypeName(i)));
        column.put("nullable", meta.isNullable(i));
        column.put("length", meta.getColumnDisplaySize(i));
        column.put("precision", meta.getPrecision(i));
        column.put("scale", meta.getScale(i));
        column.put("isCaseSensitive", meta.isCaseSensitive(i));
        column.put("isCurrency", meta.isCurrency(i));
        column.put("isSigned", meta.isSigned(i));
        column.put("schemaName", orEmpty(meta.getSchemaName(i)));
        column.put("tableName", orEmpty(meta.getTableName(i)));
        column.putNull("columnDefault");
        return column;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : orEmpty(fallback);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
