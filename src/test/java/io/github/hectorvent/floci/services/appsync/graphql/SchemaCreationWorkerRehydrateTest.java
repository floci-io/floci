package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.appsync.graphql.scalars.AppSyncScalarRegistry;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaCreationWorkerRehydrateTest {

    @Mock
    AccountAwareStorageBackend<SchemaCreationStatus> schemaStatusStore;
    @Mock
    AccountAwareStorageBackend<String> schemaStore;
    @Mock
    EmulatorConfig config;

    private SchemaRegistry schemaRegistry;
    private SchemaCreationWorker worker;

    @BeforeEach
    void setUp() {
        schemaRegistry = new SchemaRegistry(new AppSyncSchemaParser(new AppSyncScalarRegistry()));
        worker = new SchemaCreationWorker(
                schemaRegistry, schemaStatusStore, schemaStore, config, new ObjectMapper());
    }

    @Test
    void rehydrateRegistersSdlFromSchemaStore() {
        when(schemaStore.scanAllAccountsAsMap())
                .thenReturn(Map.of("api-1", "type Query { hello: String }"));

        worker.rehydrateSchemas();

        assertTrue(schemaRegistry.getSchema("api-1").isPresent());
        assertTrue(schemaRegistry.getGraphQL("api-1").isPresent());
    }

    @Test
    void rehydrateLoadsSchemasFromNonDefaultAccounts() {
        // Startup has no request context; keys()/get() would only see the default account.
        // scanAllAccountsAsMap must surface SDLs stored under other accounts.
        Map<String, String> acrossAccounts = new LinkedHashMap<>();
        acrossAccounts.put("default-api", "type Query { fromDefault: String }");
        acrossAccounts.put("other-acct-api", "type Query { fromOther: String }");
        when(schemaStore.scanAllAccountsAsMap()).thenReturn(acrossAccounts);

        worker.rehydrateSchemas();

        assertTrue(schemaRegistry.getSchema("default-api").isPresent());
        assertTrue(schemaRegistry.getSchema("other-acct-api").isPresent());
        assertTrue(schemaRegistry.getGraphQL("other-acct-api").isPresent());
        verify(schemaStore, never()).keys();
        verify(schemaStore, never()).get(anyString());
    }

    @Test
    void rehydrateSkipsUnparseableSdl() {
        when(schemaStore.scanAllAccountsAsMap())
                .thenReturn(Map.of("bad-api", "not valid sdl {{{"));

        worker.rehydrateSchemas();

        assertTrue(schemaRegistry.getSchema("bad-api").isEmpty());
    }

    @Test
    void rehydrateSkipsBlankEntries() {
        when(schemaStore.scanAllAccountsAsMap()).thenReturn(Map.of("empty", "   "));

        worker.rehydrateSchemas();

        assertTrue(schemaRegistry.getSchema("empty").isEmpty());
        verify(schemaStore, never()).put(anyString(), anyString());
    }
}
