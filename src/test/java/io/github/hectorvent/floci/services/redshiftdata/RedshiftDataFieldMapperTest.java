package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftDataFieldMapperTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void mapsEachJdbcTypeToTheRightFieldVariant() {
        assertEquals(7L, RedshiftDataFieldMapper.toField(om, 7, Types.INTEGER, false).get("longValue").asLong());
        assertEquals(7L, RedshiftDataFieldMapper.toField(om, 7L, Types.BIGINT, false).get("longValue").asLong());
        assertEquals(1.5, RedshiftDataFieldMapper.toField(om, 1.5d, Types.DOUBLE, false).get("doubleValue").asDouble());
        assertTrue(RedshiftDataFieldMapper.toField(om, true, Types.BOOLEAN, false).get("booleanValue").asBoolean());
        assertEquals("12.34",
                RedshiftDataFieldMapper.toField(om, new java.math.BigDecimal("12.34"), Types.NUMERIC, false)
                        .get("stringValue").asText());
        assertEquals("hello",
                RedshiftDataFieldMapper.toField(om, "hello", Types.VARCHAR, false).get("stringValue").asText());
        assertTrue(RedshiftDataFieldMapper.toField(om, null, Types.VARCHAR, true).get("isNull").asBoolean());

        ObjectNode blob = RedshiftDataFieldMapper.toField(om, new byte[] {1, 2, 3}, Types.VARBINARY, false);
        assertTrue(blob.has("blobValue"));
    }

    @Test
    void serializedSizeIsMonotonic() {
        ArrayNode oneRow = om.createArrayNode();
        oneRow.add(om.createObjectNode().put("stringValue", "abc"));
        long small = RedshiftDataFieldMapper.serializedSize(List.of(oneRow));

        ArrayNode bigRow = om.createArrayNode();
        bigRow.add(om.createObjectNode().put("stringValue", "abcdefghij"));
        long big = RedshiftDataFieldMapper.serializedSize(List.of(oneRow, bigRow));

        assertTrue(big > small);
        assertEquals("abc".getBytes(StandardCharsets.UTF_8).length, small);
    }
}
