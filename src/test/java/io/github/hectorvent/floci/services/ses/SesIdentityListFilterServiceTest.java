package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ListEmailIdentities {@code Filter}. Every message and precedence below is what SES v2 answered
 * in us-west-2 on 2026-10-03.
 */
class SesIdentityListFilterServiceTest {

    private static final String REGION = "us-east-1";

    private SesIdentityService service;

    @BeforeEach
    void setUp() {
        service = new SesIdentityService(new InMemoryStorage<>(), null, Clock.systemUTC());
        identity("alpha.filter.test", "Domain", "Pending");
        identity("beta.filter.test", "Domain", "Success");
        identity("alice@filter.test", "EmailAddress", "Success");
        identity("bob@example.org", "EmailAddress", "TemporaryFailure");
        identity("carol@example.org", "EmailAddress", "Failed");
    }

    @Test
    void name_isTrimmedAndMatchedAnywhereWithoutCase() {
        assertEquals(List.of("alice@filter.test", "alpha.filter.test", "beta.filter.test"),
                names(Map.of("IDENTITY_NAME_CONTAINS", "  FILTER.Test  ")));
        assertEquals(List.of("alpha.filter.test"), names(Map.of("IDENTITY_NAME_CONTAINS", "pha")));
        assertEquals(List.of(), names(Map.of("IDENTITY_NAME_CONTAINS", "zzz")));
    }

    @Test
    void typeAndStatus_selectTheirV2Values() {
        assertEquals(List.of("alice@filter.test", "bob@example.org", "carol@example.org"),
                names(Map.of("IDENTITY_TYPE", "EMAIL_ADDRESS")));
        assertEquals(List.of("alpha.filter.test", "beta.filter.test"), names(Map.of("IDENTITY_TYPE", "DOMAIN")));
        assertEquals(List.of("alpha.filter.test"), names(Map.of("VERIFICATION_STATUS", "PENDING")));
        assertEquals(List.of("alice@filter.test", "beta.filter.test"),
                names(Map.of("VERIFICATION_STATUS", "SUCCESS")));
        assertEquals(List.of("carol@example.org"), names(Map.of("VERIFICATION_STATUS", "FAILED")));
        assertEquals(List.of("bob@example.org"), names(Map.of("VERIFICATION_STATUS", "TEMPORARY_FAILURE")));
    }

    @Test
    void keys_combine() {
        assertEquals(List.of("beta.filter.test"), names(Map.of("IDENTITY_NAME_CONTAINS", "filter",
                "IDENTITY_TYPE", "DOMAIN", "VERIFICATION_STATUS", "SUCCESS")));
    }

    @Test
    void nameLength_isCountedAfterTrimming() {
        assertRefused("Filter IDENTITY_NAME_CONTAINS must be at least 3 characters.",
                () -> list(Map.of("IDENTITY_NAME_CONTAINS", " ab "), null, null));
        assertRefused("Filter IDENTITY_NAME_CONTAINS must be at most 320 characters.",
                () -> list(Map.of("IDENTITY_NAME_CONTAINS", "a".repeat(321)), null, null));
        assertEquals(List.of(), names(Map.of("IDENTITY_NAME_CONTAINS", "a".repeat(320))));
    }

    @Test
    void nameLength_countsCharactersRatherThanUtf16Units() {
        assertRefused("Filter IDENTITY_NAME_CONTAINS must be at least 3 characters.",
                () -> list(Map.of("IDENTITY_NAME_CONTAINS", "\uD83D\uDE00\uD83D\uDE00"), null, null));
        assertEquals(List.of(), names(Map.of("IDENTITY_NAME_CONTAINS", "\uD83D\uDE00".repeat(320))));
    }

    @Test
    void typeAndStatus_areCaseSensitive_andManagedDomainIsRefused() {
        assertRefused("Invalid identity type <MANAGED_DOMAIN> in filter.",
                () -> list(Map.of("IDENTITY_TYPE", "MANAGED_DOMAIN"), null, null));
        assertRefused("Invalid identity type <domain> in filter.",
                () -> list(Map.of("IDENTITY_TYPE", "domain"), null, null));
        assertRefused("Invalid verification status <Pending> in filter.",
                () -> list(Map.of("VERIFICATION_STATUS", "Pending"), null, null));
        assertRefused("Invalid verification status <NotStarted>.",
                () -> list(Map.of("VERIFICATION_STATUS", "NOT_STARTED"), null, null));
    }

    @Test
    void precedence_pageSizeThenNameThenTypeThenStatusThenToken() {
        assertRefused("Value 0 for parameter PageSize is invalid. PageSize must be between 1 and 1000.",
                () -> list(Map.of("IDENTITY_NAME_CONTAINS", "ab"), 0, null));
        assertRefused("Filter IDENTITY_NAME_CONTAINS must be at least 3 characters.",
                () -> list(Map.of("IDENTITY_NAME_CONTAINS", "ab", "IDENTITY_TYPE", "BOGUS"), null, null));
        assertRefused("Invalid identity type <BOGUS> in filter.",
                () -> list(Map.of("IDENTITY_TYPE", "BOGUS", "VERIFICATION_STATUS", "BOGUS"), null, null));
        assertRefused("Invalid verification status <BOGUS> in filter.",
                () -> list(Map.of("IDENTITY_TYPE", "EMAIL_ADDRESS", "VERIFICATION_STATUS", "BOGUS"), null, null));
        assertRefused("Invalid identity type <BOGUS> in filter.",
                () -> list(Map.of("IDENTITY_TYPE", "BOGUS"), null, "garbage"));
    }

    @Test
    void filteredPages_areExact_andTheTokenIgnoresTheNamesCase() {
        PaginatedResult<Identity> first = list(Map.of("IDENTITY_NAME_CONTAINS", "filter"), 2, null);
        assertEquals(List.of("alice@filter.test", "alpha.filter.test"), names(first));
        PaginatedResult<Identity> second = list(Map.of("IDENTITY_NAME_CONTAINS", " FILTER "), 2, first.nextToken());
        assertEquals(List.of("beta.filter.test"), names(second));
        assertNull(second.nextToken());
    }

    @Test
    void token_isBoundToTheFilter_andTheRefusalDependsOnWhetherTheRequestHasOne() {
        String filtered = list(Map.of("IDENTITY_NAME_CONTAINS", "filter"), 1, null).nextToken();
        assertRefused("Invalid NextToken <" + filtered + ">.", () -> list(Map.of(), 1, filtered));
        assertRefused("Invalid NextToken.", () -> list(Map.of("IDENTITY_NAME_CONTAINS", "other"), 1, filtered));
        String unfiltered = list(Map.of(), 1, null).nextToken();
        assertRefused("Invalid NextToken.", () -> list(Map.of("IDENTITY_TYPE", "DOMAIN"), 1, unfiltered));
        assertEquals(List.of("alpha.filter.test"), names(list(Map.of(), 1, unfiltered)));
    }

    @Test
    void emptyFilter_sharesItsTokensWithTheV1List() {
        String v1 = service.listIdentities(null, REGION, SesListPaging.V1_LIST_IDENTITIES, 1, null).nextToken();
        assertEquals(List.of("alpha.filter.test"), names(list(Map.of(), 1, v1)));
    }

    private void identity(String name, String type, String status) {
        Identity identity = new Identity(name, type);
        identity.setVerificationStatus(status);
        service.save(identity, REGION);
    }

    private PaginatedResult<Identity> list(Map<String, String> filter, Integer pageSize, String nextToken) {
        return service.listV2Identities(REGION, filter, pageSize, nextToken);
    }

    private List<String> names(Map<String, String> filter) {
        return names(list(filter, null, null));
    }

    private static List<String> names(PaginatedResult<Identity> page) {
        return page.items().stream().map(Identity::getIdentity).toList();
    }

    private static void assertRefused(String message, Executable call) {
        AwsException e = assertThrows(AwsException.class, call);
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals(message, e.getMessage());
    }
}
