package io.github.hectorvent.floci.services.appsync.graphql.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MapUtilTest {

    private final MapUtil mapUtil = new MapUtil();

    @Test
    void copyAndRetainAllKeys_basic() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        Map<String, Object> result = mapUtil.copyAndRetainAllKeys(map, List.of("a", "c"));
        assertThat(result, hasEntry("a", 1));
        assertThat(result, hasEntry("c", 3));
        assertThat(result, not(hasKey("b")));
    }

    @Test
    void copyAndRetainAllKeys_noOverlap() {
        Map<String, Object> map = Map.of("a", 1, "b", 2);
        Map<String, Object> result = mapUtil.copyAndRetainAllKeys(map, List.of("x", "y"));
        assertThat(result, anEmptyMap());
    }

    @Test
    void copyAndRetainAllKeys_allRetained() {
        Map<String, Object> map = Map.of("a", 1, "b", 2);
        Map<String, Object> result = mapUtil.copyAndRetainAllKeys(map, List.of("a", "b"));
        assertThat(result, hasEntry("a", 1));
        assertThat(result, hasEntry("b", 2));
    }

    @Test
    void copyAndRetainAllKeys_emptyKeys() {
        Map<String, Object> map = Map.of("a", 1);
        Map<String, Object> result = mapUtil.copyAndRetainAllKeys(map, List.of());
        assertThat(result, anEmptyMap());
    }

    @Test
    void copyAndRetainAllKeys_nullMap() {
        Map<String, Object> result = mapUtil.copyAndRetainAllKeys(null, List.of("a"));
        assertThat(result, anEmptyMap());
    }

    @Test
    void copyAndRetainAllKeys_nullKeys() {
        Map<String, Object> map = Map.of("a", 1);
        Map<String, Object> result = mapUtil.copyAndRetainAllKeys(map, null);
        assertThat(result, hasEntry("a", 1));
    }

    @Test
    void copyAndRemoveAllKeys_basic() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        Map<String, Object> result = mapUtil.copyAndRemoveAllKeys(map, List.of("b"));
        assertThat(result, hasEntry("a", 1));
        assertThat(result, hasEntry("c", 3));
        assertThat(result, not(hasKey("b")));
    }

    @Test
    void copyAndRemoveAllKeys_noOverlap() {
        Map<String, Object> map = Map.of("a", 1, "b", 2);
        Map<String, Object> result = mapUtil.copyAndRemoveAllKeys(map, List.of("x", "y"));
        assertThat(result, hasEntry("a", 1));
        assertThat(result, hasEntry("b", 2));
    }

    @Test
    void copyAndRemoveAllKeys_allRemoved() {
        Map<String, Object> map = Map.of("a", 1, "b", 2);
        Map<String, Object> result = mapUtil.copyAndRemoveAllKeys(map, List.of("a", "b"));
        assertThat(result, anEmptyMap());
    }

    @Test
    void copyAndRemoveAllKeys_emptyKeys() {
        Map<String, Object> map = Map.of("a", 1);
        Map<String, Object> result = mapUtil.copyAndRemoveAllKeys(map, List.of());
        assertThat(result, hasEntry("a", 1));
    }

    @Test
    void copyAndRemoveAllKeys_nullMap() {
        Map<String, Object> result = mapUtil.copyAndRemoveAllKeys(null, List.of("a"));
        assertThat(result, anEmptyMap());
    }

    @Test
    void copyAndRemoveAllKeys_nullKeys() {
        Map<String, Object> map = Map.of("a", 1);
        Map<String, Object> result = mapUtil.copyAndRemoveAllKeys(map, null);
        assertThat(result, hasEntry("a", 1));
    }
}
