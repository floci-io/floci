package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.appsync.graphql.scalars.AppSyncScalarRegistry;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

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
    StorageBackend<String, String> schemaStore;
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
        when(schemaStore.keys()).thenReturn(Set.of("api-1"));
        when(schemaStore.get("api-1")).thenReturn(Optional.of("type Query { hello: String }"));

        worker.rehydrateSchemas();

        assertTrue(schemaRegistry.getSchema("api-1").isPresent());
        assertTrue(schemaRegistry.getGraphQL("api-1").isPresent());
    }

    @Test
    void rehydrateSkipsUnparseableSdl() {
        when(schemaStore.keys()).thenReturn(Set.of("bad-api"));
        when(schemaStore.get("bad-api")).thenReturn(Optional.of("not valid sdl {{{"));

        worker.rehydrateSchemas();

        assertTrue(schemaRegistry.getSchema("bad-api").isEmpty());
    }

    @Test
    void rehydrateSkipsBlankEntries() {
        when(schemaStore.keys()).thenReturn(Set.of("empty"));
        when(schemaStore.get("empty")).thenReturn(Optional.of("   "));

        worker.rehydrateSchemas();

        assertTrue(schemaRegistry.getSchema("empty").isEmpty());
        verify(schemaStore, never()).put(anyString(), anyString());
    }
}
