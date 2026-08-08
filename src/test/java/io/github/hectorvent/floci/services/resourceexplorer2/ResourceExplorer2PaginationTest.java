package io.github.hectorvent.floci.services.resourceexplorer2;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure pagination math behind ListResources/Search:
 * {@link ResourceExplorer2Service#pageBounds} and
 * {@link ResourceExplorer2Service#listResourcesEmitsToken}. Covers the offset clamp (live result sets
 * can shrink between paginated calls), the 1000-result hard cap that real AWS enforces on Search, and
 * the ListResources MaxResults=1000 no-NextToken quirk.
 */
class ResourceExplorer2PaginationTest {

    private static final int CAP = ResourceExplorer2Service.MAX_RESOURCE_RESULTS;

    private static String token(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes());
    }

    @Test
    void firstPageStartsAtZero() {
        var b = ResourceExplorer2Service.pageBounds(50, 10, null, CAP);
        assertEquals(0, b.start());
        assertEquals(10, b.end());
        assertEquals(50, b.total());
    }

    @Test
    void secondPageOffsetsByToken() {
        var b = ResourceExplorer2Service.pageBounds(50, 10, token(10), CAP);
        assertEquals(10, b.start());
        assertEquals(20, b.end());
        assertEquals(50, b.total());
    }

    @Test
    void lastPageIsPartial() {
        var b = ResourceExplorer2Service.pageBounds(25, 10, token(20), CAP);
        assertEquals(20, b.start());
        assertEquals(25, b.end());
    }

    @Test
    void nullMaxResultsDefaultsTo100() {
        var b = ResourceExplorer2Service.pageBounds(200, null, null, CAP);
        assertEquals(100, b.end());
    }

    @Test
    void offsetBeyondSizeClampsToEmptyPageInsteadOfThrowing() {
        // The live result set can shrink between calls; a stale offset must not produce
        // subList(fromIndex > toIndex) which would throw and 500.
        var b = ResourceExplorer2Service.pageBounds(10, 100, token(999), CAP);
        assertEquals(10, b.start());
        assertEquals(10, b.end());
        assertEquals(0, b.end() - b.start());
    }

    @Test
    void garbageTokenDecodesToZeroOffset() {
        var b = ResourceExplorer2Service.pageBounds(50, 10, "!!!not-base64!!!", CAP);
        assertEquals(0, b.start());
    }

    @Test
    void totalIsCappedAt1000() {
        var b = ResourceExplorer2Service.pageBounds(1500, 1000, null, CAP);
        assertEquals(1000, b.total());
        assertEquals(0, b.start());
        assertEquals(1000, b.end());
    }

    @Test
    void paginationStaysWithinTheCap() {
        var b = ResourceExplorer2Service.pageBounds(1500, 100, token(950), CAP);
        assertEquals(1000, b.total());
        assertEquals(950, b.start());
        assertEquals(1000, b.end());
    }

    @Test
    void offsetBeyondCapClampsToEmpty() {
        var b = ResourceExplorer2Service.pageBounds(1500, 100, token(1200), CAP);
        assertEquals(1000, b.total());
        assertEquals(1000, b.start());
        assertEquals(1000, b.end());
    }

    @Test
    void listResourcesSuppressesTokenAtMaxPageSize() {
        // Documented AWS quirk: "The ListResources operation does not generate a NextToken if you set
        // MaxResults to 1000."
        // https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListResources.html
        assertFalse(ResourceExplorer2Service.listResourcesEmitsToken(1000, 1500, 1000));
    }

    @Test
    void listResourcesSuppressesTokenAtMaxPageSizeRegardlessOfOffset() {
        // The quirk is page-size-driven, not offset-driven: a caller who switches to MaxResults=1000
        // mid-chain has the token suppressed even at a non-zero offset. The AWS docs describe the quirk
        // purely in terms of MaxResults, so suppression must not depend on how far in the page starts.
        // https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListResources.html
        assertFalse(ResourceExplorer2Service.listResourcesEmitsToken(1200, 1500, 1000));
    }

    @Test
    void listResourcesEmitsTokenBelowMaxPageSizeWhenMoreRemain() {
        assertTrue(ResourceExplorer2Service.listResourcesEmitsToken(500, 1500, 500));
    }

    @Test
    void listResourcesOmitsTokenWhenResultsExhausted() {
        assertFalse(ResourceExplorer2Service.listResourcesEmitsToken(1500, 1500, 500));
    }
}
