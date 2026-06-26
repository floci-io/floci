package io.github.hectorvent.floci.services.appsync.graphql.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ListUtilTest {

    private final ListUtil listUtil = new ListUtil();

    @Test
    void copyAndRetainAll_basic() {
        List<Object> result = listUtil.copyAndRetainAll(List.of(1, 2, 3), List.of(2, 3, 4));
        assertThat(result, containsInAnyOrder(2, 3));
    }

    @Test
    void copyAndRetainAll_noOverlap() {
        List<Object> result = listUtil.copyAndRetainAll(List.of(1, 2, 3), List.of(4, 5));
        assertThat(result, empty());
    }

    @Test
    void copyAndRetainAll_allRetained() {
        List<Object> result = listUtil.copyAndRetainAll(List.of(1, 2, 3), List.of(1, 2, 3));
        assertThat(result, containsInAnyOrder(1, 2, 3));
    }

    @Test
    void copyAndRetainAll_emptyKeep() {
        List<Object> result = listUtil.copyAndRetainAll(List.of(1, 2, 3), List.of());
        assertThat(result, empty());
    }

    @Test
    void copyAndRetainAll_nullList() {
        List<Object> result = listUtil.copyAndRetainAll(null, List.of(1));
        assertThat(result, empty());
    }

    @Test
    void copyAndRetainAll_nullKeep() {
        List<Object> result = listUtil.copyAndRetainAll(List.of(1, 2, 3), null);
        assertThat(result, containsInAnyOrder(1, 2, 3));
    }

    @Test
    void copyAndRemoveAll_basic() {
        List<Object> result = listUtil.copyAndRemoveAll(List.of(1, 2, 3), List.of(2));
        assertThat(result, containsInAnyOrder(1, 3));
    }

    @Test
    void copyAndRemoveAll_noOverlap() {
        List<Object> result = listUtil.copyAndRemoveAll(List.of(1, 2, 3), List.of(4, 5));
        assertThat(result, containsInAnyOrder(1, 2, 3));
    }

    @Test
    void copyAndRemoveAll_allRemoved() {
        List<Object> result = listUtil.copyAndRemoveAll(List.of(1, 2, 3), List.of(1, 2, 3));
        assertThat(result, empty());
    }

    @Test
    void copyAndRemoveAll_emptyRemove() {
        List<Object> result = listUtil.copyAndRemoveAll(List.of(1, 2, 3), List.of());
        assertThat(result, containsInAnyOrder(1, 2, 3));
    }

    @Test
    void copyAndRemoveAll_nullList() {
        List<Object> result = listUtil.copyAndRemoveAll(null, List.of(1));
        assertThat(result, empty());
    }

    @Test
    void copyAndRemoveAll_nullRemove() {
        List<Object> result = listUtil.copyAndRemoveAll(List.of(1, 2, 3), null);
        assertThat(result, containsInAnyOrder(1, 2, 3));
    }

    @Test
    void sortList_basic() {
        List<Object> result = listUtil.sortList(List.of(3, 1, 2), false, null);
        assertThat(result, contains(1, 2, 3));
    }

    @Test
    void sortList_descending() {
        List<Object> result = listUtil.sortList(List.of(3, 1, 2), true, null);
        assertThat(result, contains(3, 2, 1));
    }

    @Test
    void sortList_withFieldName() {
        var map1 = new java.util.HashMap<String, Object>();
        map1.put("name", "Charlie");
        var map2 = new java.util.HashMap<String, Object>();
        map2.put("name", "Alice");
        var map3 = new java.util.HashMap<String, Object>();
        map3.put("name", "Bob");
        List<Object> input = List.of(map1, map2, map3);
        List<Object> result = listUtil.sortList(input, false, "name");
        assertThat(((Map<?, ?>) result.get(0)).get("name"), is("Alice"));
        assertThat(((Map<?, ?>) result.get(1)).get("name"), is("Bob"));
        assertThat(((Map<?, ?>) result.get(2)).get("name"), is("Charlie"));
    }

    @Test
    void sortList_null() {
        List<Object> result = listUtil.sortList(null, false, null);
        assertThat(result, empty());
    }

    @Test
    void sortList_empty() {
        List<Object> result = listUtil.sortList(List.of(), false, null);
        assertThat(result, empty());
    }
}
