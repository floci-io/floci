package io.github.hectorvent.floci.services.route53;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53PaginationTest {

    private record Item(String id) {
    }

    private static final List<Item> SORTED = List.of(new Item("A"), new Item("B"), new Item("C"));

    @Test
    void markerIsInclusiveAndNextMarkerStartsTheFollowingPage() {
        Route53Pagination.Page<Item> first = Route53Pagination.page(SORTED, "A", 2, Item::id);
        assertEquals(List.of(SORTED.get(0), SORTED.get(1)), first.items());
        assertTrue(first.isTruncated());
        assertEquals("C", first.nextMarker());

        Route53Pagination.Page<Item> last = Route53Pagination.page(SORTED, first.nextMarker(), 2, Item::id);
        assertEquals(List.of(SORTED.get(2)), last.items());
        assertFalse(last.isTruncated());
        assertNull(last.nextMarker());
    }

    @Test
    void exactPageAndEmptyInputAreNotTruncated() {
        Route53Pagination.Page<Item> exact = Route53Pagination.page(SORTED, "B", 2, Item::id);
        assertEquals(List.of(SORTED.get(1), SORTED.get(2)), exact.items());
        assertFalse(exact.isTruncated());

        Route53Pagination.Page<Item> empty = Route53Pagination.page(List.<Item>of(), null, 2, Item::id);
        assertEquals(List.of(), empty.items());
        assertFalse(empty.isTruncated());
    }

    @Test
    void unknownMarkerAndNonpositiveSizeKeepExistingBehavior() {
        Route53Pagination.Page<Item> unknown = Route53Pagination.page(SORTED, "missing", 2, Item::id);
        assertEquals(List.of(SORTED.get(0), SORTED.get(1)), unknown.items());
        assertEquals("C", unknown.nextMarker());

        Route53Pagination.Page<Item> unlimited = Route53Pagination.page(SORTED, "B", 0, Item::id);
        assertEquals(List.of(SORTED.get(1), SORTED.get(2)), unlimited.items());
        assertFalse(unlimited.isTruncated());

        Route53Pagination.Page<Item> negative = Route53Pagination.page(SORTED, "B", -1, Item::id);
        assertEquals(unlimited, negative);
    }

    @Test
    void traversesTheSuppliedSortOrderWithoutAssumingIdOrder() {
        List<Item> byZoneName = List.of(new Item("Z"), new Item("A"), new Item("M"));
        Route53Pagination.Page<Item> page = Route53Pagination.page(byZoneName, "A", 1, Item::id);
        assertEquals(List.of(byZoneName.get(1)), page.items());
        assertEquals("M", page.nextMarker());
    }

    @Test
    void maximumPageSizeDoesNotOverflow() {
        Route53Pagination.Page<Item> page = Route53Pagination.page(SORTED, null, Integer.MAX_VALUE, Item::id);
        assertEquals(SORTED, page.items());
        assertFalse(page.isTruncated());
    }
}
