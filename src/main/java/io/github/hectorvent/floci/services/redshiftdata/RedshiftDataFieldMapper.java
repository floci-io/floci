package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Maps a JDBC value onto the Redshift Data API {@code Field} union: exactly one
 * of {@code stringValue}, {@code longValue}, {@code doubleValue},
 * {@code booleanValue}, {@code blobValue}, or {@code isNull}.
 */
final class RedshiftDataFieldMapper {

    private static final Logger LOG = Logger.getLogger(RedshiftDataFieldMapper.class);

    private RedshiftDataFieldMapper() {
    }

    static ObjectNode toField(ObjectMapper om, Object value, int sqlType, boolean wasNull) {
        ObjectNode field = om.createObjectNode();
        if (wasNull || value == null) {
            field.put("isNull", true);
            return field;
        }
        switch (sqlType) {
            case Types.BIT, Types.BOOLEAN -> field.put("booleanValue", toBoolean(value));
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                    field.put("longValue", coerceNumber(value).longValue());
            case Types.REAL, Types.FLOAT, Types.DOUBLE ->
                    field.put("doubleValue", coerceNumber(value).doubleValue());
            case Types.NUMERIC, Types.DECIMAL -> field.put("stringValue", toPlainString(value));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> field.put("blobValue", toBytes(value));
            default -> field.put("stringValue", value.toString());
        }
        return field;
    }

    static List<ArrayNode> rows(ObjectMapper om, ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        List<ArrayNode> materialised = new ArrayList<>();
        while (rs.next()) {
            ArrayNode row = om.createArrayNode();
            for (int i = 1; i <= columns; i++) {
                Object value = rs.getObject(i);
                row.add(toField(om, value, meta.getColumnType(i), rs.wasNull()));
            }
            materialised.add(row);
        }
        return materialised;
    }

    static long serializedSize(List<ArrayNode> rows) {
        long total = 0;
        for (ArrayNode row : rows) {
            for (Iterator<JsonNode> it = row.elements(); it.hasNext(); ) {
                JsonNode field = it.next();
                if (field.has("stringValue")) {
                    total += field.get("stringValue").asText().getBytes(StandardCharsets.UTF_8).length;
                } else if (field.has("blobValue")) {
                    total += field.get("blobValue").asText().length();
                } else if (field.has("longValue")) {
                    total += Long.toString(field.get("longValue").asLong()).length();
                } else if (field.has("doubleValue")) {
                    total += Double.toString(field.get("doubleValue").asDouble()).length();
                } else if (field.has("booleanValue")) {
                    total += Boolean.toString(field.get("booleanValue").asBoolean()).length();
                }
            }
        }
        return total;
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.longValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static Number coerceNumber(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        return new BigDecimal(value.toString());
    }

    private static String toPlainString(Object value) {
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        return value.toString();
    }

    private static byte[] toBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof Blob blob) {
            try {
                return blob.getBytes(1, Math.toIntExact(blob.length()));
            } catch (SQLException e) {
                LOG.warnv("Could not read blob value for a Redshift Data API field, returning empty bytes: {0}",
                        e.getMessage());
                return new byte[0];
            }
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
}
