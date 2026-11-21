package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedMapsTest {

    @Test
    void anEntryPastTheLimitEvictsTheLeastRecentlyUsedOne() {
        Map<String, Integer> map = BoundedMaps.leastRecentlyUsed(2);
        map.put("a", 1);
        map.put("b", 2);
        map.get("a");
        map.put("c", 3);

        assertEquals(List.of("a", "c"), List.copyOf(map.keySet()));
    }
}
