package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for floci-io/floci#2415: any {@code List<String>}-returning derived getter with no
 * backing setter (here {@code getSortKeyNames()}) is a Jackson trap. On deserialization Jackson
 * finds no setter, falls back to "getter as setter", calls the getter, and appends into the list
 * it returned. {@code getSortKeyNames()} builds that list with {@code Stream.toList()}, which is
 * immutable, so the append throws {@code UnsupportedOperationException} for every RANGE-keyed
 * schema and is swallowed as {@code []} only when there is no sort key to append.
 *
 * <p>{@code getSortKeyNames()} was added to all three classes in the same commit (#2114), so the
 * trap is identical on {@link GlobalSecondaryIndex}, {@link LocalSecondaryIndex} and
 * {@link TableDefinition} itself (a table with its own composite primary key), even though the
 * issue only reports the GSI case.
 */
class SortKeyNamesJacksonRoundTripTest {

    /** Same configuration {@code PersistentStorage}, {@code HybridStorage} and {@code WalStorage} use. */
    private final ObjectMapper mapper = new ObjectMapper();

    SortKeyNamesJacksonRoundTripTest() {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private List<KeySchemaElement> hashAndRangeSchema(String hashName, String rangeName) {
        return List.of(
                new KeySchemaElement(hashName, "HASH"),
                new KeySchemaElement(rangeName, "RANGE"));
    }

    @Test
    void globalSecondaryIndexWithSortKeySurvivesARoundTrip() {
        GlobalSecondaryIndex gsi = new GlobalSecondaryIndex(
                "GSI1", hashAndRangeSchema("GSI1PK", "GSI1SK"), null, "ALL", null);

        String json = assertDoesNotThrow(() -> mapper.writeValueAsString(gsi));
        GlobalSecondaryIndex reloaded =
                assertDoesNotThrow(() -> mapper.readValue(json, GlobalSecondaryIndex.class));

        assertEquals(List.of("GSI1SK"), reloaded.getSortKeyNames());
    }

    @Test
    void localSecondaryIndexWithSortKeySurvivesARoundTrip() {
        LocalSecondaryIndex lsi = new LocalSecondaryIndex(
                "LSI1", hashAndRangeSchema("PK", "LSI1SK"), null, "ALL");

        String json = assertDoesNotThrow(() -> mapper.writeValueAsString(lsi));
        LocalSecondaryIndex reloaded =
                assertDoesNotThrow(() -> mapper.readValue(json, LocalSecondaryIndex.class));

        assertEquals(List.of("LSI1SK"), reloaded.getSortKeyNames());
    }

    @Test
    void tableWithACompositePrimaryKeySurvivesARoundTrip() {
        TableDefinition table = new TableDefinition();
        table.setTableName("orders");
        table.setKeySchema(hashAndRangeSchema("PK", "SK"));

        String json = assertDoesNotThrow(() -> mapper.writeValueAsString(table));
        TableDefinition reloaded =
                assertDoesNotThrow(() -> mapper.readValue(json, TableDefinition.class));

        assertEquals(List.of("SK"), reloaded.getSortKeyNames());
    }
}
