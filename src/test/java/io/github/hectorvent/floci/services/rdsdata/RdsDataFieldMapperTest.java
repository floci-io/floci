package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsDataFieldMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsCoreDataApiFieldVariants() throws Exception {
        ObjectNode stringField = RdsDataFieldMapper.toField(objectMapper, "hello", Types.VARCHAR);
        ObjectNode longField = RdsDataFieldMapper.toField(objectMapper, 42L, Types.BIGINT);
        ObjectNode blobField = RdsDataFieldMapper.toField(objectMapper, new byte[] {1, 2, 3}, Types.BLOB);
        ObjectNode nullField = RdsDataFieldMapper.toField(objectMapper, null, Types.VARCHAR);

        assertEquals("hello", stringField.get("stringValue").asText());
        assertEquals(42L, longField.get("longValue").asLong());
        assertArrayEquals(new byte[] {1, 2, 3}, blobField.get("blobValue").binaryValue());
        assertTrue(nullField.get("isNull").asBoolean());
    }

    @Test
    void mapsBooleanAndDoubleFollowUpVariants() throws Exception {
        ObjectNode booleanField = RdsDataFieldMapper.toField(objectMapper, true, Types.BOOLEAN);
        ObjectNode doubleField = RdsDataFieldMapper.toField(objectMapper, 1.5d, Types.DOUBLE);

        assertTrue(booleanField.get("booleanValue").asBoolean());
        assertEquals(1.5d, doubleField.get("doubleValue").asDouble());
    }

    @Test
    void mapsSqlTemporalAndDecimalValues() throws Exception {
        ObjectNode timestampField = RdsDataFieldMapper.toField(objectMapper,
                Timestamp.valueOf("2026-06-09 12:34:56.123456789"), Types.TIMESTAMP);
        ObjectNode localDateTimeField = RdsDataFieldMapper.toField(objectMapper,
                LocalDateTime.of(2021, 3, 4, 5, 6, 7, 891_000_000), Types.TIMESTAMP);
        ObjectNode wholeSecondLocalDateTimeField = RdsDataFieldMapper.toField(objectMapper,
                LocalDateTime.of(2021, 3, 4, 5, 6, 7), Types.TIMESTAMP);
        ObjectNode dateField = RdsDataFieldMapper.toField(objectMapper, Date.valueOf("2026-06-09"), Types.DATE);
        ObjectNode localDateField = RdsDataFieldMapper.toField(objectMapper, LocalDate.of(2021, 3, 4), Types.DATE);
        ObjectNode timeField = RdsDataFieldMapper.toField(objectMapper, Time.valueOf("12:34:56"), Types.TIME);
        ObjectNode localTimeField = RdsDataFieldMapper.toField(objectMapper, LocalTime.of(5, 6, 7, 891_000_000), Types.TIME);
        ObjectNode integerDecimalField = RdsDataFieldMapper.toField(objectMapper, new BigDecimal("42"), Types.DECIMAL);
        ObjectNode fractionalDecimalField = RdsDataFieldMapper.toField(objectMapper, new BigDecimal("42.25"), Types.DECIMAL);

        assertEquals("2026-06-09 12:34:56.123456789", timestampField.get("stringValue").asText());
        assertEquals("2021-03-04 05:06:07.891", localDateTimeField.get("stringValue").asText());
        assertEquals("2021-03-04 05:06:07", wholeSecondLocalDateTimeField.get("stringValue").asText());
        assertEquals("2026-06-09", dateField.get("stringValue").asText());
        assertEquals("2021-03-04", localDateField.get("stringValue").asText());
        assertEquals("12:34:56", timeField.get("stringValue").asText());
        assertEquals("05:06:07.891", localTimeField.get("stringValue").asText());
        assertEquals("42", integerDecimalField.get("stringValue").asText());
        assertEquals("42.25", fractionalDecimalField.get("stringValue").asText());
    }

    @Test
    void mapsBitNumberToBoolean() throws Exception {
        ObjectNode bitField = RdsDataFieldMapper.toField(objectMapper, 1, Types.BIT);

        assertTrue(bitField.get("booleanValue").asBoolean());
    }
}
