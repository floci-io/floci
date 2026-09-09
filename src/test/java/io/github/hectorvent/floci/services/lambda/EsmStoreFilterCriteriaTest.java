package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Proves {@code FilterCriteria} persists on {@code EventSourceMapping}: once through the real
 * {@code EsmStore} (wiring), and once through an explicit Jackson serialize/reload leg that reproduces
 * {@code PersistentStorage}'s mechanism: since {@code InMemoryStorage} keeps live references, the wiring
 * test alone would not exercise JSON serialization.
 */
class EsmStoreFilterCriteriaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT_ID = "000000000000";

    private static EventSourceMapping esmWithFilters(String uuid, String... patterns) {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid(uuid);
        esm.setAccountId(ACCOUNT_ID);
        esm.setRegion("us-east-1");
        esm.setFunctionName("fn");
        EventSourceMapping.FilterCriteria fc = new EventSourceMapping.FilterCriteria();
        List<EventSourceMapping.Filter> filters = new ArrayList<>();
        for (String p : patterns) {
            EventSourceMapping.Filter f = new EventSourceMapping.Filter();
            f.setPattern(p);
            filters.add(f);
        }
        fc.setFilters(filters);
        esm.setFilterCriteria(fc);
        return esm;
    }

    @Test
    void filterCriteriaSurvivesEsmStore() {
        EsmStore store = new EsmStore(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT_ID));
        store.saveForAccount(ACCOUNT_ID, esmWithFilters("esm-fc", "{\"data\":{\"type\":[\"buy\"]}}"));
        EventSourceMapping reloaded = store.getForAccount(ACCOUNT_ID, "esm-fc").orElseThrow();
        assertNotNull(reloaded.getFilterCriteria());
        assertEquals(1, reloaded.getFilterCriteria().getFilters().size());
        assertEquals("{\"data\":{\"type\":[\"buy\"]}}",
                reloaded.getFilterCriteria().getFilters().get(0).getPattern());
    }

    @Test
    void filterCriteriaSurvivesJacksonSerializeReload() throws Exception {
        EventSourceMapping esm = esmWithFilters("esm-fc",
                "{\"body\":{\"status\":[\"active\"]}}", "{\"partitionKey\":[\"pk-1\"]}");
        // Mirror PersistentStorage: write the whole map to JSON, then read it back into a fresh graph.
        String json = MAPPER.writeValueAsString(Map.of(esm.getUuid(), esm));
        Map<String, EventSourceMapping> after =
                MAPPER.readValue(json, new TypeReference<Map<String, EventSourceMapping>>() {});
        EventSourceMapping reloaded = after.get("esm-fc");
        assertNotNull(reloaded.getFilterCriteria());
        List<EventSourceMapping.Filter> filters = reloaded.getFilterCriteria().getFilters();
        assertEquals(2, filters.size());
        assertEquals("{\"body\":{\"status\":[\"active\"]}}", filters.get(0).getPattern());
        assertEquals("{\"partitionKey\":[\"pk-1\"]}", filters.get(1).getPattern());
    }

    @Test
    void unsetFilterCriteriaRoundTripsAsNull() throws Exception {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-none");
        esm.setAccountId(ACCOUNT_ID);
        String json = MAPPER.writeValueAsString(Map.of("esm-none", esm));
        Map<String, EventSourceMapping> after =
                MAPPER.readValue(json, new TypeReference<Map<String, EventSourceMapping>>() {});
        assertNull(after.get("esm-none").getFilterCriteria());
    }
}
