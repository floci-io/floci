package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalCatalogRegistryTest {
    private static final String ACCOUNT = "000000000000";
    private ExternalCatalogRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshift"), any(String.class), any(TypeReference.class)))
                .thenReturn(new AccountAwareStorageBackend<ExternalSchemaBinding>(new InMemoryStorage<>(), null, ACCOUNT));
        registry = new ExternalCatalogRegistry(factory);
    }

    private static ExternalSchemaBinding binding(String cluster, String database, String schema) {
        return new ExternalSchemaBinding(ACCOUNT, cluster, database, schema, "lake", "arn:aws:iam::000000000000:role/R");
    }

    @Test
    void bindsFindsListsAndUnbinds() {
        registry.bind(binding("c1", "dev", "b"));
        registry.bind(binding("c1", "dev", "a"));

        assertThat(registry.find(ACCOUNT, "c1", "dev", "a").isPresent(), equalTo(true));
        assertThat(registry.list(ACCOUNT, "c1", "dev").stream().map(ExternalSchemaBinding::schemaName).toList(),
                contains("a", "b"));
        registry.unbind(ACCOUNT, "c1", "dev", "a");
        assertThat(registry.find(ACCOUNT, "c1", "dev", "a").isPresent(), equalTo(false));
    }

    @Test
    void rejectsDuplicateSchema() {
        registry.bind(binding("c1", "dev", "a"));

        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> registry.bind(binding("c1", "dev", "a")));
        assertThat(exception.sqlState(), equalTo("42P06"));
    }

    @Test
    void identifiersContainingTheSeparatorDoNotCollideOrLeakAcrossScopes() {
        registry.bind(binding("c1", "dev|x", "s"));
        registry.bind(binding("c1", "dev", "x|s"));

        assertThat(registry.list(ACCOUNT, "c1", "dev").stream().map(ExternalSchemaBinding::schemaName).toList(),
                contains("x|s"));
        assertThat(registry.list(ACCOUNT, "c1", "dev|x").stream().map(ExternalSchemaBinding::schemaName).toList(),
                contains("s"));
    }

    @Test
    void removeClusterDropsOnlyThatCluster() {
        registry.bind(binding("c1", "dev", "a"));
        registry.bind(binding("c10", "dev", "a"));

        registry.removeCluster(ACCOUNT, "c1");

        assertThat(registry.list(ACCOUNT, "c1", "dev"), empty());
        assertThat(registry.list(ACCOUNT, "c10", "dev").size(), equalTo(1));
    }
}
