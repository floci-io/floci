package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import io.github.hectorvent.floci.services.floci.appsync.FlociAppSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schema compilation now happens in the floci-app-sync sidecar (issue #2917) — these tests
 * verify {@code rehydrateSchemas} calls {@link FlociAppSyncClient#compileSchema} per persisted
 * SDL rather than registering directly into an in-process {@code SchemaRegistry}.
 */
@ExtendWith(MockitoExtension.class)
class SchemaCreationWorkerRehydrateTest {

    @Mock
    AccountAwareStorageBackend<SchemaCreationStatus> schemaStatusStore;
    @Mock
    AccountAwareStorageBackend<String> schemaStore;
    @Mock
    EmulatorConfig config;
    @Mock
    FlociAppSyncClient flociAppSyncClient;

    private SchemaCreationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new SchemaCreationWorker(
                flociAppSyncClient, schemaStatusStore, schemaStore, config, new ObjectMapper());
    }

    @Test
    void rehydrateCompilesSdlFromSchemaStore() {
        when(schemaStore.scanAllAccountsAsMap())
                .thenReturn(Map.of("api-1", "type Query { hello: String }"));

        worker.rehydrateSchemas();

        verify(flociAppSyncClient).compileSchema("api-1", "type Query { hello: String }");
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

        verify(flociAppSyncClient).compileSchema(eq("default-api"), anyString());
        verify(flociAppSyncClient).compileSchema(eq("other-acct-api"), anyString());
        verify(schemaStore, never()).keys();
        verify(schemaStore, never()).get(anyString());
    }

    @Test
    void rehydrateSkipsUnparseableSdl() {
        when(schemaStore.scanAllAccountsAsMap())
                .thenReturn(Map.of("bad-api", "not valid sdl {{{"));
        doThrow(new RuntimeException("boom")).when(flociAppSyncClient)
                .compileSchema(eq("bad-api"), anyString());

        worker.rehydrateSchemas();

        verify(flociAppSyncClient).compileSchema(eq("bad-api"), anyString());
    }

    @Test
    void rehydrateSkipsBlankEntries() {
        when(schemaStore.scanAllAccountsAsMap()).thenReturn(Map.of("empty", "   "));

        worker.rehydrateSchemas();

        verify(flociAppSyncClient, never()).compileSchema(anyString(), anyString());
    }
}
