package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ITEMS = List.of("c", "a", "b");
    private static final AwsException INVALID = new AwsException("BadRequestException", "bad", 400);
    private static final Function<String, AwsException> INVALID_TOKEN = token -> INVALID;

    @Test
    void namespacedPaging_walksTheSortedListAndEndsWithoutAToken() {
        PaginatedResult<String> first = page(2, null, "ns");
        assertThat(first.items(), contains("a", "b"));
        assertThat(decode(first.nextToken()), startsWith("ns:"));

        PaginatedResult<String> last = page(2, first.nextToken(), "ns");
        assertThat(last.items(), contains("c"));
        assertNull(last.nextToken());
    }

    @Test
    void namespacedPaging_refusesATokenFromAnotherNamespace() {
        String foreign = page(1, null, "other").nextToken();

        assertSame(INVALID, assertThrows(AwsException.class, () -> page(1, foreign, "ns")));
    }

    @Test
    void namespacedPaging_refusesATokenThatDoesNotDecode() {
        assertSame(INVALID, assertThrows(AwsException.class, () -> page(1, "!!!", "ns")));
    }

    @Test
    void namespacedPaging_passesTheRawTokenToTheErrorFactory() {
        AwsException e = assertThrows(AwsException.class, () -> Pagination.paginate(ITEMS,
                Function.identity(), 1, "!!!", "ns",
                token -> new AwsException("BadRequestException", "bad " + token, 400)));
        assertEquals("bad !!!", e.getMessage());
    }

    @Test
    void namespacedPaging_treatsAnEmptyTokenAsTheFirstPage() {
        assertThat(page(1, "", "ns").items(), contains("a"));
    }

    @Test
    void namespacedPaging_refusesALimitBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> page(0, null, "ns"));
    }

    @Test
    void signedPaging_resumesWithAnIssuedMarkerAndRejectsForeignOrChangedMarkers() {
        SignedMarkers markers = new SignedMarkers();
        PaginatedResult<String> first = signedPage(markers, 1, null, "account:region:");
        assertThat(first.items(), contains("a"));
        assertEquals("account:region:a", markers.cursorOf(first.nextToken()));

        PaginatedResult<String> second = signedPage(markers, 2, first.nextToken(), "account:region:");
        assertThat(second.items(), contains("b", "c"));
        assertNull(second.nextToken());

        assertSame(INVALID, assertThrows(AwsException.class,
                () -> signedPage(markers, 1, first.nextToken(), "other:region:")));
        assertSame(INVALID, assertThrows(AwsException.class,
                () -> signedPage(new SignedMarkers(), 1, first.nextToken(), "account:region:")));
        assertSame(INVALID, assertThrows(AwsException.class,
                () -> signedPage(markers, 1, first.nextToken() + "x", "account:region:")));
    }

    @Test
    void signedPaging_rejectsAnEmptyOrMalformedMarker() {
        SignedMarkers markers = new SignedMarkers();
        assertSame(INVALID, assertThrows(AwsException.class,
                () -> signedPage(markers, 1, "", "")));
        assertSame(INVALID, assertThrows(AwsException.class,
                () -> signedPage(markers, 1, "!!!", "")));
    }

    @Test
    void expiringPaging_validatesTheTokenBeforeLoadingItems() {
        SignedMarkers markers = new SignedMarkers();
        Clock clock = Clock.systemUTC();
        Duration lifetime = Duration.ofDays(1);

        assertSame(INVALID, assertThrows(AwsException.class,
                () -> Pagination.paginateExpiring(() -> {
                    throw new AssertionError("items loaded before token validation");
                }, Function.identity(), 1, "not-a-token", "scope", clock, lifetime, markers, INVALID_TOKEN)));

        PaginatedResult<String> first = Pagination.paginateExpiring(() -> ITEMS, Function.identity(),
                1, null, "scope", clock, lifetime, markers, INVALID_TOKEN);
        assertThat(first.items(), contains("a"));
    }

    @Test
    void paging_aLimitNearIntMaxDoesNotReportAFurtherPage() {
        String afterFirst = page(1, null, "ns").nextToken();

        PaginatedResult<String> rest = page(Integer.MAX_VALUE, afterFirst, "ns");
        assertThat(rest.items(), contains("b", "c"));
        assertNull(rest.nextToken());
    }

    @Test
    void legacyPaging_keepsItsTokenFormatAndError() {
        PaginatedResult<String> first = Pagination.paginate(ITEMS, Function.identity(), 1, null, 3,
                "ValidationException");
        assertEquals("a", decode(first.nextToken()));
        assertThat(Pagination.paginate(ITEMS, Function.identity(), 1, first.nextToken(), 3,
                "ValidationException").items(), contains("b"));

        AwsException e = assertThrows(AwsException.class, () -> Pagination.paginate(ITEMS,
                Function.identity(), 1, "!!!", 3, "ValidationException"));
        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Invalid nextToken.", e.getMessage());
    }

    @Test
    void legacyPaging_namesTheTokenParameterTheCallerGives() {
        AwsException e = assertThrows(AwsException.class, () -> Pagination.paginate(ITEMS,
                Function.identity(), 1, "!!!", 3, 3, "InvalidParameterValue", "Marker"));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("Invalid Marker.", e.getMessage());
    }

    @Test
    void tokenStartsWith_matchesTheDecodedCursorPrefix() {
        String token = page(1, null, "ns").nextToken();

        assertTrue(Pagination.tokenStartsWith(token, "ns:"));
        assertFalse(Pagination.tokenStartsWith(token, "other:"));
    }

    @Test
    void tokenStartsWith_isFalseForATokenThatDoesNotDecode() {
        assertFalse(Pagination.tokenStartsWith("!!!", ""));
    }

    @Test
    void tokenStartsWith_isFalseForAnAbsentToken() {
        assertFalse(Pagination.tokenStartsWith(null, ""));
        assertFalse(Pagination.tokenStartsWith("", ""));
    }

    @Test
    void boundedNamespacedPaging_usesTheDefaultAndCanChangePageSize() {
        PaginatedResult<String> first = bounded(null, null);
        assertEquals(List.of("a", "b"), first.items());
        PaginatedResult<String> last = bounded(3, first.nextToken());
        assertEquals(List.of("c"), last.items());
        assertNull(last.nextToken());
    }

    @Test
    void boundedNamespacedPaging_refusesOutOfRangeSizes() {
        for (int size : new int[] {-1, 0, 4}) {
            AwsException error = assertInvalid(() -> bounded(size, null));
            assertEquals("Page size must be between 1 and 3.", error.getMessage());
        }
    }

    @Test
    void boundedNamespacedPaging_refusesLongTokensEvenWhenTheirScopeIsValid() {
        String boundary = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("ns:" + "z".repeat(21)).getBytes(StandardCharsets.UTF_8));
        assertEquals(32, boundary.length());
        assertTrue(bounded(1, boundary).items().isEmpty());
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("ns:" + "a".repeat(100)).getBytes(StandardCharsets.UTF_8));
        AwsException error = assertInvalid(() -> bounded(1, token));
        assertEquals("Pagination token must not exceed 32 characters.", error.getMessage());
    }

    @Test
    void boundedNamespacedPaging_treatsAnEmptyTokenAsTheFirstPage() {
        assertThat(bounded(1, "").items(), contains("a"));
    }

    @Test
    void boundedNamespacedPaging_refusesMalformedAndForeignTokens() {
        assertInvalid(() -> bounded(1, "!!!"));
        assertInvalid(() -> bounded(1, page(1, null, "other").nextToken()));
    }

    @Test
    void unencodedPagingReturnsTheRawCursorAndResumesAfterItIsDeleted() {
        PaginatedResult<String> first = Pagination.paginateUnencoded(ITEMS, Function.identity(),
                1, null, 2, 3, 128, "InvalidInput");
        assertThat(first.items(), contains("a"));
        assertEquals("a", first.nextToken());

        PaginatedResult<String> rest = Pagination.paginateUnencoded(List.of("b", "c"),
                Function.identity(), 1, first.nextToken(), 2, 3, 128, "InvalidInput");
        assertThat(rest.items(), contains("b"));
        assertEquals("b", rest.nextToken());
    }

    @Test
    void unencodedPagingValidatesBoundsAndTokenLength() {
        AwsException pageSizeError = assertInvalid(() -> Pagination.paginateUnencoded(ITEMS,
                Function.identity(), 0, null, 2, 3, 128, "InvalidInput"));
        assertEquals("Page size must be between 1 and 3.", pageSizeError.getMessage());
        for (String token : List.of("", "x".repeat(129))) {
            AwsException tokenError = assertInvalid(() -> Pagination.paginateUnencoded(ITEMS,
                    Function.identity(), 1, token, 2, 3, 128, "InvalidInput"));
            assertEquals("Pagination token must be between 1 and 128 characters.", tokenError.getMessage());
        }
    }

    @Test
    void jsonPageSize_preservesOmittedNullZeroAndIntegerValues() throws Exception {
        for (String field : List.of("MaxResults", "PageSize")) {
            assertNull(Pagination.parseMaxResults(MAPPER.readTree("{}"), field, "InvalidInput"));
            assertNull(Pagination.parseMaxResults(MAPPER.readTree("{\"" + field + "\":null}"), field, "InvalidInput"));
            assertEquals(0, Pagination.parseMaxResults(MAPPER.readTree("{\"" + field + "\":0}"), field, "InvalidInput"));
            assertEquals(2, Pagination.parseMaxResults(MAPPER.readTree("{\"" + field + "\":2}"), field, "InvalidInput"));
        }
    }

    @Test
    void jsonPageSize_refusesOverflowAndCoercibleNonIntegers() throws Exception {
        for (String value : List.of("4294967297", "1.5", "\"1\"", "{}", "[]", "true")) {
            assertInvalid(() -> Pagination.parseMaxResults(MAPPER.readTree("{\"MaxResults\":" + value + "}"),
                    "MaxResults", "InvalidInput"));
        }
    }

    @Test
    void jsonToken_preservesNullEmptyAndTextButRefusesOtherShapes() throws Exception {
        for (String field : List.of("NextToken", "PageToken")) {
            assertNull(Pagination.parseNextToken(MAPPER.readTree("{}"), field, "InvalidInput"));
            assertNull(Pagination.parseNextToken(MAPPER.readTree("{\"" + field + "\":null}"), field, "InvalidInput"));
            assertEquals("", Pagination.parseNextToken(MAPPER.readTree("{\"" + field + "\":\"\"}"), field, "InvalidInput"));
            assertEquals("token", Pagination.parseNextToken(MAPPER.readTree("{\"" + field + "\":\"token\"}"), field, "InvalidInput"));
            for (String value : List.of("1", "true", "{}", "[]")) {
                assertInvalid(() -> Pagination.parseNextToken(MAPPER.readTree("{\"" + field + "\":" + value + "}"),
                        field, "InvalidInput"));
            }
        }
    }

    private static PaginatedResult<String> bounded(Integer size, String token) {
        return Pagination.paginate(ITEMS, Function.identity(), size, token, 2, 3, 32, "InvalidInput", "ns");
    }

    private static AwsException assertInvalid(Executable call) {
        AwsException error = assertThrows(AwsException.class, call);
        assertEquals("InvalidInput", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        return error;
    }

    private static PaginatedResult<String> page(int limit, String token, String namespace) {
        return Pagination.paginate(ITEMS, Function.identity(), limit, token, namespace, INVALID_TOKEN);
    }

    private static PaginatedResult<String> signedPage(SignedMarkers markers, int limit,
                                                       String marker, String scope) {
        return Pagination.paginate(ITEMS, Function.identity(), limit, marker, markers, scope, INVALID_TOKEN);
    }

    private static String decode(String token) {
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }
}
