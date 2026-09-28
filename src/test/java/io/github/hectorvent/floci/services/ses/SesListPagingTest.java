package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SesListPagingTest {

    private static final List<String> ITEMS = List.of("a", "b", "c");

    @Test
    void pageSize_defaultsPerOperation() {
        assertEquals(10, SesListPaging.V2_LIST_EMAIL_TEMPLATES.pageSize(null));
        assertEquals(10, SesListPaging.V1_LIST_TEMPLATES.pageSize(null));
        assertEquals(100, SesListPaging.V2_LIST_EXPORT_JOBS.pageSize(null));
        assertEquals(1, SesListPaging.V2_LIST_EMAIL_TEMPLATES.pageSize(1));
        assertEquals(100, SesListPaging.V2_LIST_EMAIL_TEMPLATES.pageSize(100));
    }

    @Test
    void pageSize_outOfRangeIsRefusedWithThePerOperationMessage() {
        assertError("BadRequestException", "The page size must be between 1 and 100",
                () -> SesListPaging.V2_LIST_EMAIL_TEMPLATES.pageSize(101));
        assertError("BadRequestException", "PageSize must be between 1 and 100",
                () -> SesListPaging.V2_LIST_EXPORT_JOBS.pageSize(0));
    }

    @Test
    void pageSize_v1TemplatesServeTheBoundForAnOutOfRangeValue() {
        for (int size : new int[] {0, -1, 101, Integer.MAX_VALUE}) {
            assertEquals(100, SesListPaging.V1_LIST_TEMPLATES.pageSize(size));
        }
    }

    @Test
    void templateTokens_areSharedBetweenV1AndV2AndRefusedByExportJobs() {
        String v1Token = page(SesListPaging.V1_LIST_TEMPLATES, 1, null).nextToken();

        assertThat(page(SesListPaging.V2_LIST_EMAIL_TEMPLATES, 1, v1Token).items(), contains("b"));
        assertError("BadRequestException", "Failed to deserialize token. ",
                () -> page(SesListPaging.V2_LIST_EXPORT_JOBS, 1, v1Token));
    }

    @Test
    void invalidToken_messagesQuoteTheToken() {
        assertError("BadRequestException", "Invalid PageToken <garbage>.",
                () -> page(SesListPaging.V2_LIST_EMAIL_TEMPLATES, 1, "garbage"));
        assertError("InvalidParameterValue", "Invalid PageToken <garbage>.",
                () -> page(SesListPaging.V1_LIST_TEMPLATES, 1, "garbage"));
    }

    @Test
    void emptyToken_isTheFirstPageForTemplatesAndAnErrorForExportJobs() {
        assertThat(page(SesListPaging.V2_LIST_EMAIL_TEMPLATES, 1, "").items(), contains("a"));
        assertError("BadRequestException", "Failed to deserialize token. ",
                () -> page(SesListPaging.V2_LIST_EXPORT_JOBS, 1, ""));
    }

    @Test
    void lastPage_hasNoToken() {
        PaginatedResult<String> page = page(SesListPaging.V2_LIST_EMAIL_TEMPLATES, 3, null);
        assertThat(page.items(), contains("a", "b", "c"));
        assertNull(page.nextToken());
    }

    @Test
    void newestFirst_ordersByTimeDescendingThenById() {
        Instant earlier = Instant.parse("2026-09-25T00:00:00Z");
        Instant later = earlier.plusMillis(1);

        assertThat(SesListPaging.newestFirst(later, "z"),
                lessThan(SesListPaging.newestFirst(earlier, "a")));
        assertThat(SesListPaging.newestFirst(later, "a"),
                lessThan(SesListPaging.newestFirst(later, "b")));
    }

    @Test
    void parseQueryPageSize_refusesANonInteger() {
        assertNull(SesListPaging.parseQueryPageSize(null));
        assertNull(SesListPaging.parseQueryPageSize(""));
        assertEquals(5, SesListPaging.parseQueryPageSize("5"));
        assertError("SerializationException", "'99999999999' can not be converted to Integer",
                () -> SesListPaging.parseQueryPageSize("99999999999"));
    }

    @Test
    void parseQueryProtocolPageSize_refusesANonIntegerWithoutAMessage() {
        assertNull(SesListPaging.parseQueryProtocolPageSize(null));
        assertEquals(5, SesListPaging.parseQueryProtocolPageSize("5"));
        assertError("MalformedInput", null, () -> SesListPaging.parseQueryProtocolPageSize("1.5"));
    }

    @Test
    void parseQueryProtocolPageSize_refusesAnEmptyValue() {
        assertError("MalformedInput", "missing value for decimal type",
                () -> SesListPaging.parseQueryProtocolPageSize(""));
    }

    @Test
    void pageSize_isReportedBeforeTheToken() {
        assertError("BadRequestException", "PageSize must be between 1 and 100",
                () -> SesListPaging.V2_LIST_EXPORT_JOBS.page(ITEMS, Function.identity(), 0, ""));
        assertError("BadRequestException", "The page size must be between 1 and 100",
                () -> SesListPaging.V2_LIST_EMAIL_TEMPLATES.page(ITEMS, Function.identity(), 0, "garbage"));
    }

    private static PaginatedResult<String> page(SesListPaging paging, int size, String token) {
        return paging.page(ITEMS, Function.identity(), size, token);
    }

    private static void assertError(String code, String message, Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals(code, e.getErrorCode());
        assertEquals(message, e.getMessage());
    }
}
