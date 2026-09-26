package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaginationTest {

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

    private static PaginatedResult<String> page(int limit, String token, String namespace) {
        return Pagination.paginate(ITEMS, Function.identity(), limit, token, namespace, INVALID_TOKEN);
    }

    private static String decode(String token) {
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }
}
