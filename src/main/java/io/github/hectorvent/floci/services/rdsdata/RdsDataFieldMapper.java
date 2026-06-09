package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

final class RdsDataFieldMapper {

    private static final DateTimeFormatter TIMESTAMP_BASE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RdsDataFieldMapper() {
    }

    static ObjectNode toField(ObjectMapper objectMapper, Object value, int sqlType) throws SQLException {
        ObjectNode field = objectMapper.createObjectNode();
        if (value == null) {
            field.put("isNull", true);
            return field;
        }

        switch (sqlType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
                if (value instanceof Number number) {
                    field.put("longValue", number.longValue());
                } else {
                    field.put("longValue", Long.parseLong(value.toString()));
                }
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                if (value instanceof byte[] bytes) {
                    field.put("blobValue", bytes);
                } else if (value instanceof Blob blob) {
                    field.put("blobValue", blob.getBytes(1, Math.toIntExact(blob.length())));
                } else {
                    field.put("blobValue", value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            case Types.BOOLEAN, Types.BIT -> {
                if (value instanceof Boolean bool) {
                    field.put("booleanValue", bool);
                } else if (value instanceof Number number) {
                    field.put("booleanValue", number.longValue() != 0);
                } else {
                    field.put("booleanValue", Boolean.parseBoolean(value.toString()));
                }
            }
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> {
                if (value instanceof Number number) {
                    field.put("doubleValue", number.doubleValue());
                } else {
                    field.put("doubleValue", Double.parseDouble(value.toString()));
                }
            }
            case Types.NUMERIC, Types.DECIMAL -> mapDecimal(field, value);
            case Types.DATE -> field.put("stringValue", formatDate(value));
            case Types.TIME -> field.put("stringValue", formatTime(value));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> field.put("stringValue", formatTimestamp(value));
            default -> field.put("stringValue", value.toString());
        }
        return field;
    }

    private static String formatDate(Object value) {
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        return value.toString();
    }

    private static String formatTime(Object value) {
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof LocalTime localTime) {
            return localTime.toString();
        }
        return value.toString();
    }

    private static String formatTimestamp(Object value) {
        if (value instanceof Timestamp timestamp) {
            return formatLocalDateTime(timestamp.toLocalDateTime());
        }
        if (value instanceof LocalDateTime localDateTime) {
            return formatLocalDateTime(localDateTime);
        }
        return value.toString();
    }

    private static String formatLocalDateTime(LocalDateTime localDateTime) {
        String value = localDateTime.format(TIMESTAMP_BASE_FORMATTER);
        int nanos = localDateTime.getNano();
        if (nanos == 0) {
            return value;
        }
        String fraction = String.format("%09d", nanos);
        int end = fraction.length();
        while (end > 0 && fraction.charAt(end - 1) == '0') {
            end--;
        }
        return value + "." + fraction.substring(0, end);
    }

    private static void mapDecimal(ObjectNode field, Object value) {
        if (value instanceof BigDecimal decimal) {
            field.put("stringValue", decimal.toPlainString());
            return;
        }
        field.put("stringValue", value.toString());
    }
}
