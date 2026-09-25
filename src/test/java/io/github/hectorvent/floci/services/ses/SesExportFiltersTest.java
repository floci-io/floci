package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesExportFiltersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SesExportPayloads.InsightsRow row(String destination, String tenantName) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("destination", destination);
        columns.put("isp", "UNKNOWN_ISP");
        columns.put("last_delivery_event", "DELIVERY");
        columns.put("last_engagement_event", null);
        return new SesExportPayloads.InsightsRow(columns, tenantName);
    }

    private static JsonNode source(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("bad test json", e);
        }
    }

    private static List<String> destinations(List<SesExportPayloads.InsightsRow> rows) {
        return rows.stream().map(row -> row.columns().get("destination")).toList();
    }

    private static final List<SesExportPayloads.InsightsRow> ROWS = List.of(
            row("one@example.com", "blue"),
            row("two@example.com", "green"),
            row("three@example.com", null));

    @Test
    void includeOnTenantNameKeepsOnlyThatTenant() {
        List<SesExportPayloads.InsightsRow> kept = SesExportFilters.apply(ROWS,
                source("{\"Include\": {\"TenantName\": [\"blue\"]}}"));

        assertEquals(List.of("one@example.com"), destinations(kept));
    }

    @Test
    void includeOnTenantNameAcceptsSeveralValues() {
        List<SesExportPayloads.InsightsRow> kept = SesExportFilters.apply(ROWS,
                source("{\"Include\": {\"TenantName\": [\"blue\", \"green\"]}}"));

        assertEquals(List.of("one@example.com", "two@example.com"), destinations(kept));
    }

    @Test
    void excludeOnTenantNameDropsThatTenantAndKeepsTheTenantlessSend() {
        List<SesExportPayloads.InsightsRow> kept = SesExportFilters.apply(ROWS,
                source("{\"Exclude\": {\"TenantName\": [\"blue\"]}}"));

        assertEquals(List.of("two@example.com", "three@example.com"), destinations(kept));
    }

    @Test
    void membersWithinOneIncludeAllHaveToMatch() {
        List<SesExportPayloads.InsightsRow> kept = SesExportFilters.apply(ROWS,
                source("{\"Include\": {\"TenantName\": [\"blue\"], \"Isp\": [\"GMAIL\"]}}"));

        assertTrue(kept.isEmpty(), "the ISP member excludes the row the tenant member keeps");
    }

    @Test
    void anIncludeOnLastEngagementEventSelectsNothing() {
        // Floci derives no opens or clicks, so the column is always null and cannot match.
        List<SesExportPayloads.InsightsRow> kept = SesExportFilters.apply(ROWS,
                source("{\"Include\": {\"LastEngagementEvent\": [\"OPEN\"]}}"));

        assertTrue(kept.isEmpty());
    }

    @Test
    void anExcludeCarryingNoValuesKeepsEveryRow() {
        // Every member is absent, so the block constrains nothing. Read as a match it would empty
        // the file, which is the one direction where "no constraint" is not harmless.
        assertEquals(3, SesExportFilters.apply(ROWS, source("{\"Exclude\": {}}")).size());
        assertEquals(3, SesExportFilters.apply(ROWS,
                source("{\"Exclude\": {\"TenantName\": []}}")).size());
    }

    @Test
    void maxResultsTruncatesAfterFiltering() {
        List<SesExportPayloads.InsightsRow> kept = SesExportFilters.apply(ROWS,
                source("{\"Exclude\": {\"TenantName\": [\"blue\"]}, \"MaxResults\": 1}"));

        assertEquals(List.of("two@example.com"), destinations(kept));
    }
}
