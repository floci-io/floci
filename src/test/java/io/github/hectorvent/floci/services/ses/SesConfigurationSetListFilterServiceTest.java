package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ListConfigurationSets {@code Filter}. Every message and precedence below is what SES v2
 * answered in us-west-2 on 2026-10-03.
 */
class SesConfigurationSetListFilterServiceTest {

    private static final String REGION = "us-east-1";

    private SesConfigurationSetService service;

    @BeforeEach
    void setUp() {
        service = new SesConfigurationSetService(new InMemoryStorage<>());
        for (String name : List.of("Filter-Alpha", "filter-beta", "other-FILTER-gamma", "unrelated")) {
            ConfigurationSet configSet = new ConfigurationSet();
            configSet.setName(name);
            service.create(configSet, REGION);
        }
    }

    @Test
    void name_isMatchedAnywhereWithoutCase_butNotTrimmed() {
        assertEquals(List.of("Filter-Alpha", "filter-beta", "other-FILTER-gamma"), names("FILTER"));
        assertEquals(List.of("Filter-Alpha"), names("alpha"));
        assertEquals(List.of(), names("  alpha  "));
        assertEquals(List.of(), names("a b*"));
    }

    @Test
    void nullName_listsEverySet() {
        assertEquals(List.of("Filter-Alpha", "filter-beta", "other-FILTER-gamma", "unrelated"),
                names(list(null, null, null)));
    }

    @Test
    void nameLength_isBetween3And64() {
        String message = "CONFIGURATION_SET_NAME_CONTAINS must be between 3 and 64 characters";
        assertRefused(message, () -> list("ab", null, null));
        assertRefused(message, () -> list("a".repeat(65), null, null));
        assertEquals(List.of(), names("a".repeat(64)));
        assertRefused(message, () -> list("\uD83D\uDE00\uD83D\uDE00", null, null));
        assertEquals(List.of(), names("\uD83D\uDE00".repeat(64)));
    }

    @Test
    void precedence_pageSizeThenNameThenToken() {
        assertRefused("The page size must be between 1 and 1000", () -> list("ab", 0, null));
        assertRefused("CONFIGURATION_SET_NAME_CONTAINS must be between 3 and 64 characters",
                () -> list("a".repeat(65), null, "garbage"));
    }

    @Test
    void filteredPages_areExact_andTheTokenIgnoresTheNamesCase() {
        PaginatedResult<ConfigurationSet> first = list("filter", 2, null);
        assertEquals(List.of("Filter-Alpha", "filter-beta"), names(first));
        PaginatedResult<ConfigurationSet> second = list("FILTER", 2, first.nextToken());
        assertEquals(List.of("other-FILTER-gamma"), names(second));
        assertNull(second.nextToken());
    }

    @Test
    void token_isBoundToTheName_bothWays() {
        String filtered = list("filter", 1, null).nextToken();
        assertRefused("invalid nextToken " + filtered, () -> list(null, 1, filtered));
        assertRefused("invalid nextToken " + filtered, () -> list("alpha", 1, filtered));
        String unfiltered = list(null, 1, null).nextToken();
        assertRefused("invalid nextToken " + unfiltered, () -> list("filter", 1, unfiltered));
    }

    private PaginatedResult<ConfigurationSet> list(String name, Integer pageSize, String nextToken) {
        return service.listV2(REGION, name, pageSize, nextToken);
    }

    private List<String> names(String name) {
        return names(list(name, null, null));
    }

    private static List<String> names(PaginatedResult<ConfigurationSet> page) {
        return page.items().stream().map(ConfigurationSet::getName).toList();
    }

    private static void assertRefused(String message, Executable call) {
        AwsException e = assertThrows(AwsException.class, call);
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals(message, e.getMessage());
    }
}
