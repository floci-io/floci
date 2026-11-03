package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Contributor Insights setting of a table and of its indexes survives the persistent storage modes. */
class ContributorInsightsJacksonRoundTripTest {

    /** Same configuration {@code PersistentStorage}, {@code HybridStorage} and {@code WalStorage} use. */
    private final ObjectMapper mapper = new ObjectMapper();

    ContributorInsightsJacksonRoundTripTest() {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void tableAndIndexSettingsSurviveARoundTrip() throws Exception {
        TableDefinition table = new TableDefinition();
        table.setTableName("orders");
        table.setKeySchema(List.of(new KeySchemaElement("pk", "HASH")));
        table.setContributorInsights(insights(true, "THROTTLED_KEYS", Instant.parse("2026-11-02T10:15:30.123Z")));
        GlobalSecondaryIndex gsi = new GlobalSecondaryIndex("byOwner",
                List.of(new KeySchemaElement("owner", "HASH")), null, "ALL", null);
        gsi.setContributorInsights(insights(false, "ACCESSED_AND_THROTTLED_KEYS",
                Instant.parse("2026-11-02T11:00:00Z")));
        table.getGlobalSecondaryIndexes().add(gsi);

        TableDefinition reloaded = mapper.readValue(mapper.writeValueAsString(table), TableDefinition.class);

        ContributorInsights tableInsights = reloaded.getContributorInsights();
        assertTrue(tableInsights.isEnabled());
        assertEquals("THROTTLED_KEYS", tableInsights.getMode());
        assertEquals(Instant.parse("2026-11-02T10:15:30.123Z"), tableInsights.getRulesCreatedAt());
        assertEquals(Instant.parse("2026-11-02T10:15:30.123Z"), tableInsights.getLastUpdateDateTime());
        ContributorInsights indexInsights = reloaded.findGsi("byOwner").orElseThrow().getContributorInsights();
        assertEquals(false, indexInsights.isEnabled());
        assertEquals("ACCESSED_AND_THROTTLED_KEYS", indexInsights.getMode());
    }

    @Test
    void aTableStoredBeforeContributorInsightsReadsWithoutIt() throws Exception {
        TableDefinition reloaded = mapper.readValue("{\"tableName\":\"legacy\"}", TableDefinition.class);
        assertNull(reloaded.getContributorInsights());
    }

    private static ContributorInsights insights(boolean enabled, String mode, Instant at) {
        ContributorInsights insights = new ContributorInsights();
        insights.setEnabled(enabled);
        insights.setMode(mode);
        insights.setLastUpdateDateTime(at);
        insights.setRulesCreatedAt(at);
        return insights;
    }
}
