package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ListTenants {@code Filter}. Every message and precedence below is what SES v2 answered in
 * us-west-2 on 2026-10-03, except the order of the name and status checks, which SES never showed.
 */
class SesTenantListFilterServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private InMemoryStorage<String, Tenant> tenantStore;
    private SesTenantService service;

    @BeforeEach
    void setUp() {
        tenantStore = new InMemoryStorage<>();
        service = new SesTenantService(tenantStore, new InMemoryStorage<>(), Clock.systemUTC(), new SecureRandom());
        for (String name : List.of("Filter-Alpha", "filter-beta", "unrelated")) {
            service.createTenant(name, List.of(), ACCOUNT, REGION);
        }
        tenantStore.put("tenant::" + REGION + "::other-FILTER-gamma", new Tenant("other-FILTER-gamma",
                "tn-gamma", "arn", Instant.now(), List.of(), "DISABLED", null));
    }

    @Test
    void name_isMatchedAnywhereWithoutCase_butNotTrimmed() {
        assertEquals(List.of("Filter-Alpha", "filter-beta", "other-FILTER-gamma"),
                names(Map.of("TENANT_NAME_CONTAINS", "FILTER")));
        assertEquals(List.of("Filter-Alpha"), names(Map.of("TENANT_NAME_CONTAINS", "alpha")));
        assertEquals(List.of(), names(Map.of("TENANT_NAME_CONTAINS", "  alpha  ")));
    }

    @Test
    void sendingStatus_selectsTheStatus_andKeysCombine() {
        assertEquals(List.of("Filter-Alpha", "filter-beta", "unrelated"),
                names(Map.of("SENDING_STATUS", "ENABLED")));
        assertEquals(List.of("other-FILTER-gamma"), names(Map.of("SENDING_STATUS", "DISABLED")));
        assertEquals(List.of(), names(Map.of("SENDING_STATUS", "REINSTATED")));
        assertEquals(List.of("filter-beta"),
                names(Map.of("TENANT_NAME_CONTAINS", "beta", "SENDING_STATUS", "ENABLED")));
        assertEquals(List.of(), names(Map.of("TENANT_NAME_CONTAINS", "beta", "SENDING_STATUS", "DISABLED")));
    }

    @Test
    void name_needsThreeCharacters_andHasNoUpperBoundOfItsOwn() {
        assertRefused("TENANT_NAME_CONTAINS must be at least 3 characters.",
                () -> list(Map.of("TENANT_NAME_CONTAINS", "ab"), null, null));
        assertRefused("TENANT_NAME_CONTAINS must be at least 3 characters.",
                () -> list(Map.of("TENANT_NAME_CONTAINS", "😀😀"), null, null));
        assertEquals(List.of(), names(Map.of("TENANT_NAME_CONTAINS", "a".repeat(65))));
    }

    @Test
    void sendingStatus_isCaseSensitive() {
        assertRefused("Invalid sending status <enabled>.",
                () -> list(Map.of("SENDING_STATUS", "enabled"), null, null));
        assertRefused("Invalid sending status <BOGUS>.", () -> list(Map.of("SENDING_STATUS", "BOGUS"), null, null));
    }

    @Test
    void precedence_pageSizeAndEmptyTokenComeBeforeTheFilter() {
        assertRefused("1 validation error detected: Value '0' at 'pageSize' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1",
                () -> list(Map.of("SENDING_STATUS", "BOGUS"), 0, null));
        assertRefused("1 validation error detected: Value '' at 'nextToken' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1",
                () -> list(Map.of("TENANT_NAME_CONTAINS", "ab"), null, ""));
        assertRefused("Invalid sending status <BOGUS>.",
                () -> list(Map.of("SENDING_STATUS", "BOGUS"), null, "garbage"));
    }

    @Test
    void filteredPages_areExact_andTheTokenIgnoresTheNamesCase() {
        PaginatedResult<Tenant> first = list(Map.of("TENANT_NAME_CONTAINS", "filter"), 2, null);
        assertEquals(List.of("Filter-Alpha", "filter-beta"), names(first));
        PaginatedResult<Tenant> second = list(Map.of("TENANT_NAME_CONTAINS", "FILTER"), 2, first.nextToken());
        assertEquals(List.of("other-FILTER-gamma"), names(second));
        assertNull(second.nextToken());
    }

    @Test
    void token_isBoundToTheFilter_bothWays() {
        String filtered = list(Map.of("TENANT_NAME_CONTAINS", "filter"), 1, null).nextToken();
        assertRefused("Invalid Next Token", () -> list(Map.of(), 1, filtered));
        assertRefused("Invalid Next Token", () -> list(Map.of("SENDING_STATUS", "ENABLED"), 1, filtered));
        String unfiltered = list(Map.of(), 1, null).nextToken();
        assertRefused("Invalid Next Token", () -> list(Map.of("TENANT_NAME_CONTAINS", "filter"), 1, unfiltered));
        assertEquals(List.of("filter-beta"), names(list(Map.of(), 1, unfiltered)));
    }

    private PaginatedResult<Tenant> list(Map<String, String> filter, Integer pageSize, String nextToken) {
        return service.listTenants(REGION, filter, pageSize, nextToken);
    }

    private List<String> names(Map<String, String> filter) {
        return names(list(filter, null, null));
    }

    private static List<String> names(PaginatedResult<Tenant> page) {
        return page.items().stream().map(Tenant::tenantName).toList();
    }

    private static void assertRefused(String message, Executable call) {
        AwsException e = assertThrows(AwsException.class, call);
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals(message, e.getMessage());
    }
}
