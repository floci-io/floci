package io.floci.aws.core.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class StorageJsonTest {

    /** A record as the stores keep them: a plain Jackson bean, with a Java time field. */
    public static class Item {
        private Instant updatedAt;
        private Map<String, String> tags = new LinkedHashMap<>();

        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }
    }

    @Test
    void aCopyHoldsTheSameValuesInARecordOfItsOwn() {
        Item item = new Item();
        item.setUpdatedAt(Instant.parse("2026-11-01T00:00:00.123456789Z"));
        item.getTags().put("team", "data");

        Item copy = StorageJson.copy(item);
        copy.getTags().put("team", "web");

        assertNotSame(item, copy);
        assertEquals(item.getUpdatedAt(), copy.getUpdatedAt());
        assertEquals(Map.of("team", "data"), item.getTags());
    }

    @Test
    void theMapperWritesTimesAsTextAsTheFileStoragesDo() throws Exception {
        assertEquals("\"2026-11-01T00:00:00Z\"", StorageJson.mapper().writeValueAsString(Instant.parse("2026-11-01T00:00:00Z")));
    }
}
