package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinition;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinition.QueryParameter;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinitionToken;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudWatchLogsQueryDefinitionServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private final RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
    private final InMemoryStorage<String, QueryDefinition> store = new InMemoryStorage<>();
    private final InMemoryStorage<String, QueryDefinitionToken> tokens = new InMemoryStorage<>();
    private final CloudWatchLogsQueryDefinitionService service = new CloudWatchLogsQueryDefinitionService(
            store, tokens, resolver, Clock.fixed(Instant.ofEpochMilli(1234567890000L), ZoneOffset.UTC));

    @TempDir
    Path directory;

    @Test
    void updateReplacesOptionalFieldsAndPreservesId() {
        QueryDefinition request = new QueryDefinition(null, "queries/errors", "fields @message", List.of("/missing"),
                "CWLI", List.of(new QueryParameter("level", "ERROR", "Log level")), 0, null);
        String id = service.putQueryDefinition(request, REGION);
        assertEquals(id, service.putQueryDefinition(definition(id, "renamed", "stats count()", null), REGION));
        QueryDefinition saved = page(null, null, null, null).items().getFirst();
        assertEquals(id, saved.queryDefinitionId());
        assertEquals("renamed", saved.name());
        assertEquals("stats count()", saved.queryString());
        assertEquals("CWLI", saved.queryLanguage());
        assertTrue(saved.logGroupNames().isEmpty());
        assertTrue(saved.parameters().isEmpty());
        assertEquals(1234567890000L, saved.lastModified());
    }

    @Test
    void clientTokenRetryReturnsTheSameDefinitionAndRejectsDifferentInput() {
        String token = UUID.randomUUID().toString();
        QueryDefinition request = definition(null, "retry", "fields @message", token);
        String id = service.putQueryDefinition(request, REGION);
        assertEquals(id, service.putQueryDefinition(request, REGION));
        assertEquals(1, page(null, null, null, null).items().size());
        assertInvalid(() -> service.putQueryDefinition(definition(null, "other", "fields @message", token), REGION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createRetryAfterUpdatePreservesTheUpdatedDefinition(boolean updateHasToken) {
        String token = UUID.randomUUID().toString();
        QueryDefinition create = definition(null, "original", "fields @message", token);
        String id = service.putQueryDefinition(create, REGION);
        service.putQueryDefinition(definition(id, "updated", "stats count()",
                updateHasToken ? UUID.randomUUID().toString() : null), REGION);

        assertEquals(id, service.putQueryDefinition(create, REGION));
        List<QueryDefinition> saved = page(null, null, null, null).items();
        assertEquals(1, saved.size());
        assertEquals("updated", saved.getFirst().name());
        assertEquals("stats count()", saved.getFirst().queryString());
        assertInvalid(() -> service.putQueryDefinition(definition(null, "conflict", "fields @message", token), REGION));
        assertInvalid(() -> service.putQueryDefinition(definition(id, "original", "fields @message", token), REGION));
    }

    @Test
    void updateRetryAfterAnotherUpdateDoesNotRestoreTheOldVersion() {
        String id = service.putQueryDefinition(definition(null, "original", "fields @message", null), REGION);
        String token = UUID.randomUUID().toString();
        QueryDefinition update = definition(id, "first-update", "stats count()", token);
        service.putQueryDefinition(update, REGION);
        service.putQueryDefinition(definition(id, "second-update", "fields @timestamp", null), REGION);

        assertEquals(id, service.putQueryDefinition(update, REGION));
        assertEquals("second-update", page(null, null, null, null).items().getFirst().name());
        assertInvalid(() -> service.putQueryDefinition(definition(null, "first-update", "stats count()", token), REGION));
    }

    @Test
    void deletingDefinitionRetainsItsTokenUntilResetWithoutRecreatingIt() {
        String token = UUID.randomUUID().toString();
        QueryDefinition create = definition(null, "original", "fields @message", token);
        String id = service.putQueryDefinition(create, REGION);
        assertTrue(service.deleteQueryDefinition(id, REGION));

        assertEquals(id, service.putQueryDefinition(create, REGION));
        assertTrue(page(null, null, null, null).items().isEmpty());
        assertInvalid(() -> service.putQueryDefinition(definition(null, "conflict", "fields @message", token), REGION));
        service.clear();
        assertNotEquals(id, service.putQueryDefinition(create, REGION));
    }

    @Test
    void clientTokensAreIsolatedByRegion() {
        QueryDefinition create = definition(null, "original", "fields @message", UUID.randomUUID().toString());
        String id = service.putQueryDefinition(create, REGION);
        String other = service.putQueryDefinition(create, "eu-west-1");
        assertNotEquals(id, other);
        assertEquals(id, service.putQueryDefinition(create, REGION));
        assertEquals(other, service.putQueryDefinition(create, "eu-west-1"));
    }

    @Test
    void paginationFiltersBeforePagingAndCanResumeAfterDeletingTheCursor() {
        for (int i = 0; i < 3; i++) {
            service.putQueryDefinition(definition(null, "match/" + i, "fields @message", null), REGION);
        }
        service.putQueryDefinition(definition(null, "other", "fields @message", null), REGION);
        service.putQueryDefinition(new QueryDefinition(null, "match/sql", "SELECT * FROM logs", List.of(),
                "SQL", List.of(), 0, null), REGION);
        PaginatedResult<QueryDefinition> first = page("match/", "CWLI", 1, null);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());
        service.deleteQueryDefinition(first.items().getFirst().queryDefinitionId(), REGION);
        PaginatedResult<QueryDefinition> rest = page("match/", "CWLI", 1000, first.nextToken());
        assertEquals(2, rest.items().size());
        assertNull(rest.nextToken());
        assertInvalid(() -> page(null, "CWLI", 1, first.nextToken()));
        assertInvalid(() -> service.describeQueryDefinitions("match/", "CWLI", 1, first.nextToken(), "eu-west-1"));
    }

    @Test
    void tokenExpiresAfterTwentyFourHours() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        CloudWatchLogsQueryDefinitionService timed = new CloudWatchLogsQueryDefinitionService(store, tokens, resolver, clock);
        timed.putQueryDefinition(definition(null, "one", "fields @message", null), REGION);
        timed.putQueryDefinition(definition(null, "two", "fields @message", null), REGION);
        String token = timed.describeQueryDefinitions(null, null, 1, null, REGION).nextToken();
        when(clock.millis()).thenReturn(86_400_001L);
        assertInvalid(() -> timed.describeQueryDefinitions(null, null, 1, token, REGION));
    }

    @Test
    void definitionsAreIsolatedByRegionAndResetClearsThem() {
        String id = service.putQueryDefinition(definition(null, "one", "fields @message", null), REGION);
        assertTrue(service.describeQueryDefinitions(null, null, null, null, "eu-west-1").items().isEmpty());
        assertNotFound(() -> service.deleteQueryDefinition(id, "eu-west-1"));
        assertTrue(service.deleteQueryDefinition(id, REGION));
        assertNotFound(() -> service.deleteQueryDefinition(id, REGION));
        assertNotFound(() -> service.putQueryDefinition(definition(id, "gone", "fields @message", null), REGION));
        service.putQueryDefinition(definition(null, "two", "fields @message", null), REGION);
        service.clear();
        assertTrue(page(null, null, null, null).items().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "e30"})
    void rejectsMalformedSignedTokens(String token) {
        assertInvalid(() -> page(null, null, 1, token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SQL", "PPL"})
    void queryParametersAreOnlySupportedForCwli(String language) {
        assertInvalid(() -> service.putQueryDefinition(new QueryDefinition(null, "name", "query", List.of(),
                language, List.of(new QueryParameter("level", "ERROR", null)), 0, null), REGION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void storageReloadRetainsUpdatesAndDeletesAndIsolatesAccounts(String mode) {
        String id;
        QueryDefinition create = definition(null, "saved", "fields @message", UUID.randomUUID().toString());
        try (FactoryScope first = open(mode, ACCOUNT)) {
            id = first.service.putQueryDefinition(create, REGION);
            first.service.putQueryDefinition(new QueryDefinition(id, "updated", "stats count()", List.of("/missing"),
                    "PPL", List.of(), 0, null), REGION);
            String deleted = first.service.putQueryDefinition(definition(null, "deleted", "fields @message", null), REGION);
            first.service.deleteQueryDefinition(deleted, REGION);
        }
        try (FactoryScope other = open(mode, "222222222222")) {
            assertTrue(other.service.describeQueryDefinitions(null, null, null, null, REGION).items().isEmpty());
            assertNotEquals(id, other.service.putQueryDefinition(create, REGION));
        }
        try (FactoryScope second = open(mode, ACCOUNT)) {
            List<QueryDefinition> loaded = second.service.describeQueryDefinitions(null, null, null, null, REGION).items();
            if (mode.equals("memory")) {
                assertTrue(loaded.isEmpty());
            } else {
                assertEquals(id, second.service.putQueryDefinition(create, REGION));
                assertEquals(1, loaded.size());
                assertEquals(id, loaded.getFirst().queryDefinitionId());
                assertEquals("updated", loaded.getFirst().name());
                assertEquals("stats count()", loaded.getFirst().queryString());
                assertEquals("PPL", loaded.getFirst().queryLanguage());
                assertEquals(List.of("/missing"), loaded.getFirst().logGroupNames());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void storageReloadRetainsTokensForDeletedDefinitions(String mode) {
        QueryDefinition create = definition(null, "saved", "fields @message", UUID.randomUUID().toString());
        String id;
        try (FactoryScope first = open(mode, ACCOUNT)) {
            id = first.service.putQueryDefinition(create, REGION);
            first.service.deleteQueryDefinition(id, REGION);
        }
        try (FactoryScope second = open(mode, ACCOUNT)) {
            assertEquals(id, second.service.putQueryDefinition(create, REGION));
            assertTrue(second.service.describeQueryDefinitions(null, null, null, null, REGION).items().isEmpty());
        }
    }

    private FactoryScope open(String mode, String account) {
        StorageFactory factory = StorageFactories.open(directory, mode, account);
        return new FactoryScope(factory, new CloudWatchLogsQueryDefinitionService(factory,
                new RegionResolver(REGION, account)));
    }

    private record FactoryScope(StorageFactory factory, CloudWatchLogsQueryDefinitionService service)
            implements AutoCloseable {
        @Override
        public void close() {
            factory.shutdownAll();
        }
    }

    private PaginatedResult<QueryDefinition> page(String prefix, String language, Integer limit, String token) {
        return service.describeQueryDefinitions(prefix, language, limit, token, REGION);
    }

    private static QueryDefinition definition(String id, String name, String query, String token) {
        return new QueryDefinition(id, name, query, List.of(), null, List.of(), 0, token);
    }

    private static void assertInvalid(Runnable operation) {
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, operation::run).getErrorCode());
    }

    private static void assertNotFound(Runnable operation) {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, operation::run).getErrorCode());
    }
}
