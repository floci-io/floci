package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlCurrentState.Change;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RedshiftZeroEtlCurrentStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shapesTheTableAsAwsDoesWithKeyColumnsBeforeTheItemValue() {
        RedshiftZeroEtlCurrentState state = RedshiftZeroEtlCurrentState.of(table("orders",
                List.of(new KeySchemaElement("sk", "RANGE"), new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "N"))));

        assertEquals("orders", state.tableName());
        assertEquals(List.of("pk", "sk"), state.keyColumnNames());
        assertEquals("CREATE TABLE IF NOT EXISTS public.\"orders\" (\"pk\" TEXT NOT NULL, "
                + "\"sk\" NUMERIC NOT NULL, \"value\" JSONB NOT NULL, PRIMARY KEY (\"pk\", \"sk\"))", state.ddl());
        assertEquals("TRUNCATE TABLE public.\"orders\"", state.resetSql());
        assertEquals(Map.of("pk", "text", "sk", "numeric", "value", "jsonb"), state.expectedColumnTypes());
        assertEquals("INSERT INTO public.\"orders\" (\"pk\", \"sk\", \"value\") VALUES (?, ?, ?::jsonb) "
                + "ON CONFLICT (\"pk\", \"sk\") DO UPDATE SET \"value\" = EXCLUDED.\"value\"", state.upsertSql());
        assertEquals("DELETE FROM public.\"orders\" WHERE \"pk\" = ? AND \"sk\" = ?", state.deleteSql());
        assertEquals(3, state.valueParameterIndex());
    }

    @Test
    void quotesIdentifiersSoDynamoDbNamesSurviveCaseAndPunctuation() {
        RedshiftZeroEtlCurrentState state = RedshiftZeroEtlCurrentState.of(table("Orders-v2.prod",
                List.of(new KeySchemaElement("Order Id", "HASH")), List.of(new AttributeDefinition("Order Id", "B"))));

        assertEquals("CREATE TABLE IF NOT EXISTS public.\"Orders-v2.prod\" (\"Order Id\" BYTEA NOT NULL, "
                + "\"value\" JSONB NOT NULL, PRIMARY KEY (\"Order Id\"))", state.ddl());
    }

    @Test
    void refusesNamesPostgresWouldTruncate() {
        // 63 bytes is kept whole; 64 would be cut, and two names cut to the same prefix would share a table.
        assertEquals("x".repeat(63), RedshiftZeroEtlCurrentState.of(table("x".repeat(63),
                List.of(new KeySchemaElement("id", "HASH")), List.of(new AttributeDefinition("id", "S")))).tableName());
        AwsException longName = assertThrows(AwsException.class, () -> RedshiftZeroEtlCurrentState.of(
                table("x".repeat(64), List.of(new KeySchemaElement("id", "HASH")),
                        List.of(new AttributeDefinition("id", "S")))));
        assertEquals("InvalidParameterValue", longName.getErrorCode());
        assertTrue(longName.getMessage().contains("63 bytes"));
        // Bytes, not characters: a multibyte name is cut sooner.
        AwsException multibyte = assertThrows(AwsException.class, () -> RedshiftZeroEtlCurrentState.of(
                table("é".repeat(32), List.of(new KeySchemaElement("id", "HASH")),
                        List.of(new AttributeDefinition("id", "S")))));
        assertEquals("InvalidParameterValue", multibyte.getErrorCode());

        AwsException longKey = assertThrows(AwsException.class, () -> RedshiftZeroEtlCurrentState.of(
                table("orders", List.of(new KeySchemaElement("k".repeat(64), "HASH")),
                        List.of(new AttributeDefinition("k".repeat(64), "S")))));
        assertEquals("InvalidParameterValue", longKey.getErrorCode());

        AwsException valueKey = assertThrows(AwsException.class, () -> RedshiftZeroEtlCurrentState.of(
                table("orders", List.of(new KeySchemaElement("value", "HASH")),
                        List.of(new AttributeDefinition("value", "S")))));
        assertTrue(valueKey.getMessage().contains("value"));
    }

    @Test
    void bindsKeysByTheirDynamoDbType() throws Exception {
        RedshiftZeroEtlCurrentState state = RedshiftZeroEtlCurrentState.of(table("orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "N"))));
        ObjectNode keys = MAPPER.createObjectNode();
        keys.putObject("pk").put("S", "customer#1");
        keys.putObject("sk").put("N", "42.5");
        PreparedStatement statement = mock(PreparedStatement.class);

        state.bindKeys(statement, keys);

        verify(statement).setString(1, "customer#1");
        verify(statement).setBigDecimal(2, new BigDecimal("42.5"));
        verifyNoMoreInteractions(statement);
    }

    @Test
    void bindsNumericKeysExactlyHoweverLargeOrFine() throws Exception {
        RedshiftZeroEtlCurrentState state = RedshiftZeroEtlCurrentState.of(table("metrics",
                List.of(new KeySchemaElement("n", "HASH")), List.of(new AttributeDefinition("n", "N"))));
        PreparedStatement statement = mock(PreparedStatement.class);
        // 38 significant digits with 30 before the point, and a difference past the 18th decimal: both exact.
        for (String number : List.of("123456789012345678901234567890.12345678", "0.1234567890123456789",
                "0.1234567890123456788", "-1e125")) {
            ObjectNode keys = MAPPER.createObjectNode();
            keys.putObject("n").put("N", number);
            state.bindKeys(statement, keys);
            verify(statement).setBigDecimal(1, new BigDecimal(number));
        }
    }

    @Test
    void bindsBinaryKeysFromBase64() throws Exception {
        RedshiftZeroEtlCurrentState state = RedshiftZeroEtlCurrentState.of(table("blobs",
                List.of(new KeySchemaElement("id", "HASH")), List.of(new AttributeDefinition("id", "B"))));
        ObjectNode keys = MAPPER.createObjectNode();
        keys.putObject("id").put("B", "AQID");
        PreparedStatement statement = mock(PreparedStatement.class);

        state.bindKeys(statement, keys);

        verify(statement).setBytes(1, new byte[] {1, 2, 3});
    }

    @Test
    void aKeyOfAnotherTypeThanTheSchemaIsAnInternalFailure() {
        RedshiftZeroEtlCurrentState state = RedshiftZeroEtlCurrentState.of(table("orders",
                List.of(new KeySchemaElement("pk", "HASH")), List.of(new AttributeDefinition("pk", "S"))));
        ObjectNode keys = MAPPER.createObjectNode();
        keys.putObject("pk").put("N", "1");

        AwsException e = assertThrows(AwsException.class, () -> state.bindKeys(mock(PreparedStatement.class), keys));
        assertEquals("InternalFailure", e.getErrorCode());
    }

    @Test
    void aRecordIsProjectedFromTheItemAsTheSourceHoldsItNowNotFromItsOwnImage() {
        // A replayed MODIFY carries version two, but the source holds version three: the newer one is projected.
        ObjectNode replayed = record("MODIFY", true);
        ObjectNode current = MAPPER.createObjectNode();
        current.putObject("id").put("S", "1");
        current.putObject("note").put("S", "version three");

        Change change = RedshiftZeroEtlCurrentState.changeOf(replayed, keys -> current);

        assertEquals(replayed.path("dynamodb").path("Keys"), change.keys());
        assertEquals(current, change.item());
    }

    @Test
    void everyKindOfRecordProjectsTheSameStateOfItsKey() {
        ObjectNode current = MAPPER.createObjectNode();
        current.putObject("id").put("S", "1");

        for (String eventName : List.of("INSERT", "MODIFY", "REMOVE")) {
            assertEquals(current, RedshiftZeroEtlCurrentState.changeOf(record(eventName, true), keys -> current).item(),
                    eventName);
        }
    }

    @Test
    void aKeyTheSourceNoLongerHoldsIsDeleted() {
        // Even an INSERT record, replayed after its item was deleted, deletes: the source is the truth.
        Change delete = RedshiftZeroEtlCurrentState.changeOf(record("INSERT", true), keys -> null);

        assertTrue(delete.isDelete());
        assertEquals(record("INSERT", true).path("dynamodb").path("Keys"), delete.keys());
    }

    @Test
    void aRecordWithoutKeysProjectsNothing() {
        ObjectNode record = MAPPER.createObjectNode().put("eventName", "MODIFY");
        record.putObject("dynamodb").put("SequenceNumber", "1");

        assertNull(RedshiftZeroEtlCurrentState.changeOf(record, keys -> null));
    }

    private static ObjectNode record(String eventName, boolean withNewImage) {
        ObjectNode record = MAPPER.createObjectNode().put("eventID", "e").put("eventName", eventName);
        ObjectNode dynamodb = record.putObject("dynamodb").put("SequenceNumber", "1");
        dynamodb.putObject("Keys").putObject("id").put("S", "1");
        if (withNewImage) {
            ObjectNode image = dynamodb.putObject("NewImage");
            image.putObject("id").put("S", "1");
            image.putObject("note").put("S", "hello");
        }
        return record;
    }

    private static TableDefinition table(String name, List<KeySchemaElement> keySchema,
                                         List<AttributeDefinition> attributes) {
        TableDefinition table = new TableDefinition();
        table.setTableName(name);
        table.setKeySchema(keySchema);
        table.setAttributeDefinitions(attributes);
        return table;
    }
}
