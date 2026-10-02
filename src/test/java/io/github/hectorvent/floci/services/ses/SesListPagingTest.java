package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

    private static final String REGION = "us-east-1";
    private static final List<String> ITEMS = List.of("a", "b", "c");
    private static final String EMPTY_TOKEN_VIOLATION = "Value '' at 'nextToken' failed to satisfy constraint: "
            + "Member must have length greater than or equal to 1";
    private static final String EMPTY_TOKEN_MESSAGE = "1 validation error detected: " + EMPTY_TOKEN_VIOLATION;

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
    void pageSize_sharedResourceDefaultsFollowTheProbe() {
        assertEquals(25, SesListPaging.V2_LIST_EMAIL_IDENTITIES.pageSize(null));
        assertEquals(1000, SesListPaging.V1_LIST_IDENTITIES.pageSize(null));
        assertEquals(50, SesListPaging.V2_LIST_CONFIGURATION_SETS.pageSize(null));
        assertEquals(50, SesListPaging.V1_LIST_CONFIGURATION_SETS.pageSize(null));
        assertEquals(50, SesListPaging.V2_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES.pageSize(null));
        assertEquals(50, SesListPaging.V1_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES.pageSize(null));
    }

    @Test
    void pageSize_v1ConfigurationSetsAndCvetsServeFiftyForAnOutOfRangeValue() {
        for (int size : new int[] {0, -1, 1001, Integer.MAX_VALUE}) {
            assertEquals(50, SesListPaging.V1_LIST_CONFIGURATION_SETS.pageSize(size));
            assertEquals(50, SesListPaging.V1_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES.pageSize(size));
        }
    }

    @Test
    void pageSize_sharedResourceOutOfRangeMessages() {
        assertError("BadRequestException",
                "Value 1001 for parameter PageSize is invalid. PageSize must be between 1 and 1000.",
                () -> SesListPaging.V2_LIST_EMAIL_IDENTITIES.pageSize(1001));
        assertError("InvalidParameterValue",
                "Value 0 for parameter MaxItems is invalid. MaxItems must be between 1 and 1000.",
                () -> SesListPaging.V1_LIST_IDENTITIES.pageSize(0));
        assertError("BadRequestException", "The page size must be between 1 and 1000",
                () -> SesListPaging.V2_LIST_CONFIGURATION_SETS.pageSize(0));
        assertError("BadRequestException", "The page size must be between 1 and 50",
                () -> SesListPaging.V2_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES.pageSize(51));
    }

    @Test
    void sharedResourceTokens_crossBetweenV1AndV2ButNotBetweenKinds() {
        String identityToken = page(SesListPaging.V1_LIST_IDENTITIES, 1, null).nextToken();
        assertThat(page(SesListPaging.V2_LIST_EMAIL_IDENTITIES, 1, identityToken).items(), contains("b"));

        String configSetToken = page(SesListPaging.V2_LIST_CONFIGURATION_SETS, 1, null).nextToken();
        assertThat(page(SesListPaging.V1_LIST_CONFIGURATION_SETS, 1, configSetToken).items(), contains("b"));

        String cvetToken = page(SesListPaging.V2_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES, 1, null).nextToken();
        assertThat(page(SesListPaging.V1_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES, 1, cvetToken).items(),
                contains("b"));

        assertError("BadRequestException", "invalid nextToken " + identityToken,
                () -> page(SesListPaging.V2_LIST_CONFIGURATION_SETS, 1, identityToken));
        assertError("InvalidParameterValue", null,
                () -> page(SesListPaging.V1_LIST_CONFIGURATION_SETS, 1, identityToken));
    }

    @Test
    void invalidToken_sharedResourceMessages() {
        assertError("BadRequestException", "Invalid NextToken <garbage>.",
                () -> page(SesListPaging.V2_LIST_EMAIL_IDENTITIES, 1, "garbage"));
        assertError("InvalidParameterValue", "Invalid NextToken <garbage>.",
                () -> page(SesListPaging.V1_LIST_IDENTITIES, 1, "garbage"));
        assertError("BadRequestException", "invalid nextToken garbage",
                () -> page(SesListPaging.V2_LIST_CONFIGURATION_SETS, 1, "garbage"));
        assertError("BadRequestException", "Invalid nextToken <garbage>.",
                () -> page(SesListPaging.V2_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES, 1, "garbage"));
        assertError("InvalidParameterValue", "Invalid nextToken <garbage>.",
                () -> page(SesListPaging.V1_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES, 1, "garbage"));
    }

    @Test
    void templateTokens_areSharedBetweenV1AndV2AndRefusedByExportJobs() {
        String v1Token = page(SesListPaging.V1_LIST_TEMPLATES, 1, null).nextToken();

        assertThat(page(SesListPaging.V2_LIST_EMAIL_TEMPLATES, 1, v1Token).items(), contains("b"));
        assertError("BadRequestException", "Failed to deserialize token. ",
                () -> page(SesListPaging.V2_LIST_EXPORT_JOBS, 1, v1Token));
    }

    @Test
    void aTokenFromAnotherRegion_isRefusedAsUnreadable() {
        String token = page(SesListPaging.V2_LIST_EMAIL_IDENTITIES, 1, null).nextToken();

        assertError("BadRequestException", "Invalid NextToken <" + token + ">.",
                () -> SesListPaging.V2_LIST_EMAIL_IDENTITIES.page("us-west-2", ITEMS, Function.identity(), 1, token));
        assertError("InvalidParameterValue", "Invalid NextToken <" + token + ">.",
                () -> SesListPaging.V1_LIST_IDENTITIES.page("us-west-2", ITEMS, Function.identity(), 1, token));
        assertThat(page(SesListPaging.V1_LIST_IDENTITIES, 1, token).items(), contains("b"));
    }

    @Test
    void aTokenFromAnotherScope_isRefusedAsUnreadable() {
        SesListPaging paging = SesListPaging.V2_LIST_SUPPRESSED_DESTINATIONS;
        String token = paging.page(REGION, "BOUNCE", ITEMS, Function.identity(), 1, null).nextToken();

        assertThat(paging.page(REGION, "BOUNCE", ITEMS, Function.identity(), 1, token).items(), contains("b"));
        assertError("InvalidNextTokenException", "Token is invalid.",
                () -> paging.page(REGION, "COMPLAINT", ITEMS, Function.identity(), 1, token));
        assertError("InvalidNextTokenException", "Token is invalid.",
                () -> paging.page(REGION, ITEMS, Function.identity(), 1, token));
    }

    @Test
    void contactsDefaultToFiftyAndTheUnmeasuredListsToTheirBound() {
        assertEquals(50, SesListPaging.V2_LIST_CONTACTS.pageSize(null));
        assertEquals(1000, SesListPaging.V2_LIST_CONTACT_LISTS.pageSize(null));
        assertEquals(1000, SesListPaging.V2_LIST_DEDICATED_IP_POOLS.pageSize(null));
        assertEquals(1000, SesListPaging.V2_GET_DEDICATED_IPS.pageSize(null));
        assertEquals(1000, SesListPaging.V2_LIST_SUPPRESSED_DESTINATIONS.pageSize(null));
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
                () -> SesListPaging.V2_LIST_EXPORT_JOBS.page(REGION, ITEMS, Function.identity(), 0, ""));
        assertError("BadRequestException", "The page size must be between 1 and 100",
                () -> SesListPaging.V2_LIST_EMAIL_TEMPLATES.page(REGION, ITEMS, Function.identity(), 0, "garbage"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantStyleLists")
    void tenantStyle_pageSizeDefaultsToTheBound(SesListPaging paging) {
        assertEquals(100, paging.pageSize(null));
        assertEquals(1, paging.pageSize(1));
        assertEquals(100, paging.pageSize(100));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantStyleLists")
    void tenantStyle_outOfRangePageSizeAnswersTheSmithyMessageWithTheValue(SesListPaging paging) {
        assertError("BadRequestException", pageSizeMessage(0, "greater than or equal to 1"),
                () -> paging.pageSize(0));
        assertError("BadRequestException", pageSizeMessage(-7, "greater than or equal to 1"),
                () -> paging.pageSize(-7));
        assertError("BadRequestException", pageSizeMessage(101, "less than or equal to 100"),
                () -> paging.pageSize(101));
        assertError("BadRequestException", pageSizeMessage(0, "greater than or equal to 1"),
                () -> page(paging, 0, "garbage"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantStyleLists")
    void tenantStyle_unreadableAndEmptyTokensAreRefusedDifferently(SesListPaging paging) {
        assertError("BadRequestException", "Invalid Next Token", () -> page(paging, 1, "garbage"));
        assertError("BadRequestException", EMPTY_TOKEN_MESSAGE, () -> page(paging, 1, ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantStyleLists")
    void tenantStyle_aTokenIsRefusedByEveryOtherKind(SesListPaging minting) {
        String token = page(minting, 1, null).nextToken();

        assertThat(page(minting, 1, token).items(), contains("b"));
        for (SesListPaging other : tenantStyleLists()) {
            if (other != minting) {
                assertError("BadRequestException", "Invalid Next Token", () -> page(other, 1, token));
            }
        }
        assertError("BadRequestException", "Failed to deserialize token. ",
                () -> page(SesListPaging.V2_LIST_EXPORT_JOBS, 1, token));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantStyleLists")
    void tenantStyle_aBadPageSizeAndAnEmptyTokenAreReportedTogether(SesListPaging paging) {
        assertError("BadRequestException", "2 validation errors detected: " + EMPTY_TOKEN_VIOLATION + "; "
                + pageSizeViolation(0, "greater than or equal to 1"), () -> page(paging, 0, ""));
        assertError("BadRequestException", "2 validation errors detected: " + EMPTY_TOKEN_VIOLATION + "; "
                + pageSizeViolation(101, "less than or equal to 100"), () -> page(paging, 101, ""));
    }

    @Test
    void scope_bindsATokenToWhatTheRequestSelected() {
        SesListPaging paging = SesListPaging.V2_LIST_TENANT_RESOURCES;
        String token = paging.page(REGION, "tenant-a", ITEMS, Function.identity(), 1, null).nextToken();

        assertThat(paging.page(REGION, "tenant-a", ITEMS, Function.identity(), 1, token).items(), contains("b"));
        assertError("BadRequestException", "Invalid Next Token",
                () -> paging.page(REGION, "tenant-b", ITEMS, Function.identity(), 1, token));
        assertError("BadRequestException", "Invalid Next Token", () -> page(paging, 1, token));

        // A request that selected nothing has an empty scope, and its token is refused once one is added.
        String unscoped = page(paging, 1, null).nextToken();
        assertError("BadRequestException", "Invalid Next Token",
                () -> paging.page(REGION, "tenant-a", ITEMS, Function.identity(), 1, unscoped));
    }

    @Test
    void scope_isNotMatchedByAScopeItMerelyStartsWith() {
        SesListPaging paging = SesListPaging.V2_LIST_RESOURCE_TENANTS;
        // An identity name may hold the separator the token ends its scope with.
        String longer = paging.page(REGION, "identity/a:b", ITEMS, Function.identity(), 1, null).nextToken();
        String shorter = paging.page(REGION, "identity/a", ITEMS, Function.identity(), 1, null).nextToken();

        assertError("BadRequestException", "Invalid Next Token",
                () -> paging.page(REGION, "identity/a", ITEMS, Function.identity(), 1, longer));
        assertError("BadRequestException", "Invalid Next Token",
                () -> paging.page(REGION, "identity/a:b", ITEMS, Function.identity(), 1, shorter));
        assertThat(paging.page(REGION, "identity/a:b", ITEMS, Function.identity(), 1, longer).items(),
                contains("b"));
    }

    @Test
    void oldestFirst_ordersByTimeAscendingThenById() {
        Instant earlier = Instant.parse("2026-09-25T00:00:00Z");
        Instant later = earlier.plusMillis(1);

        assertThat(SesListPaging.oldestFirst(earlier, "z"),
                lessThan(SesListPaging.oldestFirst(later, "a")));
        assertThat(SesListPaging.oldestFirst(later, "a"),
                lessThan(SesListPaging.oldestFirst(later, "b")));
        // The cursor is compared as text, so a stamp with fewer digits must still sort first.
        assertThat(SesListPaging.oldestFirst(Instant.ofEpochSecond(9), "a"),
                lessThan(SesListPaging.oldestFirst(Instant.ofEpochSecond(10), "a")));
        // Two stamps inside one millisecond keep their order, whatever the ids.
        assertThat(SesListPaging.oldestFirst(earlier.plusNanos(1), "z"),
                lessThan(SesListPaging.oldestFirst(earlier.plusNanos(2), "a")));
        // A missing stamp sorts last, as the unpaged lists order it.
        assertThat(SesListPaging.oldestFirst(later, "z"),
                lessThan(SesListPaging.oldestFirst(null, "a")));
    }

    private static List<SesListPaging> tenantStyleLists() {
        return List.of(SesListPaging.V2_LIST_TENANTS, SesListPaging.V2_LIST_TENANT_RESOURCES,
                SesListPaging.V2_LIST_RESOURCE_TENANTS, SesListPaging.V2_LIST_IMPORT_JOBS);
    }

    private static String pageSizeMessage(int value, String constraint) {
        return "1 validation error detected: " + pageSizeViolation(value, constraint);
    }

    private static String pageSizeViolation(int value, String constraint) {
        return "Value '" + value + "' at 'pageSize' failed to satisfy constraint: Member must have value "
                + constraint;
    }

    private static PaginatedResult<String> page(SesListPaging paging, int size, String token) {
        return paging.page(REGION, ITEMS, Function.identity(), size, token);
    }

    private static void assertError(String code, String message, Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals(code, e.getErrorCode());
        assertEquals(message, e.getMessage());
    }
}
