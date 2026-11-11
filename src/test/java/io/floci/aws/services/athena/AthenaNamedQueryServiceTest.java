package io.floci.aws.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.NamedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AthenaNamedQueryServiceTest {

    @TempDir
    Path directory;

    @Test
    void reloadRestoresQueriesAndTheOriginalIdempotencyRequest() {
        AthenaNamedQueryService service = persistentService();
        String token = UUID.randomUUID().toString();
        String id = service.createNamedQuery("eu-west-2", "original", "db", "select 1", "description", null, token);
        service.updateNamedQuery("eu-west-2", id, "updated", "select 2", "");

        AthenaNamedQueryService reloaded = persistentService();
        NamedQuery saved = reloaded.getNamedQuery("eu-west-2", id);
        assertEquals("updated", saved.name());
        assertEquals("db", saved.database());
        assertEquals("select 2", saved.queryString());
        assertEquals("", saved.description());
        assertEquals("primary", saved.workGroup());
        assertEquals(id, reloaded.createNamedQuery("eu-west-2", "original", "db", "select 1", "description", null, token));
        assertThrows(AwsException.class, () -> reloaded.createNamedQuery("eu-west-2", "updated", "db", "select 2",
                "", null, token));
        assertEquals(1, reloaded.listNamedQueries("eu-west-2", null, null, null).items().size());

        reloaded.deleteNamedQuery("eu-west-2", id);
        AthenaNamedQueryService deleted = persistentService();
        assertThrows(AwsException.class, () -> deleted.getNamedQuery("eu-west-2", id));
        assertTrue(deleted.listNamedQueries("eu-west-2", null, null, null).items().isEmpty());
    }

    @Test
    void recursiveWorkGroupDeletionPersistsAndRemovesItsRetryRequests() {
        AthenaNamedQueryService service = persistentService();
        String token = UUID.randomUUID().toString();
        String removed = service.createNamedQuery("eu-west-2", "saved", "db", "select 1", null, "removed", token);
        String preserved = service.createNamedQuery("eu-west-2", "saved", "db", "select 1", null,
                "primary", UUID.randomUUID().toString());
        service.deleteWorkGroup("eu-west-2", "removed", true);

        AthenaNamedQueryService reloaded = persistentService();
        assertThrows(AwsException.class, () -> reloaded.getNamedQuery("eu-west-2", removed));
        assertEquals(preserved, reloaded.getNamedQuery("eu-west-2", preserved).namedQueryId());
        String recreated = reloaded.createNamedQuery("eu-west-2", "saved", "db", "select 1", null, "removed", token);
        assertNotEquals(removed, recreated);
        assertEquals(recreated, reloaded.getNamedQuery("eu-west-2", recreated).namedQueryId());
    }

    private AthenaNamedQueryService persistentService() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<NamedQuery>create(eq("athena"), anyString(), any())).thenAnswer(invocation -> {
            Path file = directory.resolve(invocation.getArgument(1, String.class));
            PersistentStorage<String, NamedQuery> backend = new PersistentStorage<>(file,
                    new TypeReference<Map<String, NamedQuery>>() {});
            backend.load();
            return new AccountAwareStorageBackend<>(backend, null, "741680800000");
        });
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn("741680800000");
        return new AthenaNamedQueryService(factory, mock(AthenaService.class), resolver);
    }
}
