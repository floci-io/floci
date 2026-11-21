package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KinesisSchemaRegistryTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private KinesisSchemaRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshift"), eq("redshift-kinesis-schemas.json"), any(TypeReference.class)))
                .thenReturn(new AccountAwareStorageBackend<KinesisSchemaBinding>(new InMemoryStorage<>(), null, ACCOUNT));
        registry = new KinesisSchemaRegistry(factory);
    }

    private static KinesisSchemaBinding binding(String cluster, String database, String schema) {
        return new KinesisSchemaBinding(ACCOUNT, cluster, database, schema, null, ROLE);
    }

    @Test
    void bindsFindsListsAndUnbinds() {
        registry.bind(binding("c1", "dev", "b"));
        registry.bind(binding("c1", "dev", "a"));

        assertThat(registry.find(ACCOUNT, "c1", "dev", "a").orElseThrow().iamRoleArn(), equalTo(ROLE));
        assertThat(registry.list(ACCOUNT, "c1", "dev").stream().map(KinesisSchemaBinding::schemaName).toList(),
                contains("a", "b"));

        registry.unbind(ACCOUNT, "c1", "dev", "a");

        assertThat(registry.find(ACCOUNT, "c1", "dev", "a").isPresent(), equalTo(false));
        assertThat(registry.list(ACCOUNT, "c1", "dev").size(), equalTo(1));
    }

    @Test
    void rejectsADuplicateSchemaInTheSameDatabase() {
        registry.bind(binding("c1", "dev", "kds"));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> registry.bind(binding("c1", "dev", "kds")));

        assertThat(error.sqlState(), equalTo("42P06"));
    }

    @Test
    void keepsClustersAndDatabasesApart() {
        registry.bind(binding("c1", "dev", "kds"));
        registry.bind(binding("c1", "other", "kds"));
        registry.bind(binding("c2", "dev", "kds"));

        assertThat(registry.list(ACCOUNT, "c1", "dev").size(), equalTo(1));
        assertThat(registry.list(ACCOUNT, "c1", "other").size(), equalTo(1));
        assertThat(registry.list(ACCOUNT, "c3", "dev"), empty());
    }

    @Test
    void doesNotMixUpComponentsThatContainTheSeparator() {
        registry.bind(new KinesisSchemaBinding(ACCOUNT, "c|1", "dev", "kds", null, ROLE));

        assertThat(registry.find(ACCOUNT, "c", "1|dev", "kds").isPresent(), equalTo(false));
        assertThat(registry.find(ACCOUNT, "c|1", "dev", "kds").isPresent(), equalTo(true));
    }

    @Test
    void removesEverySchemaOfAClusterAndKeepsTheOthers() {
        registry.bind(binding("c1", "dev", "a"));
        registry.bind(binding("c1", "other", "b"));
        registry.bind(binding("c2", "dev", "c"));

        registry.removeCluster(ACCOUNT, "c1");

        assertThat(registry.list(ACCOUNT, "c1", "dev"), empty());
        assertThat(registry.list(ACCOUNT, "c1", "other"), empty());
        assertThat(registry.list(ACCOUNT, "c2", "dev").size(), equalTo(1));
    }

    @Test
    void clearForgetsEverything() {
        registry.bind(binding("c1", "dev", "a"));

        registry.clear();

        assertThat(registry.list(ACCOUNT, "c1", "dev"), empty());
    }

    @Test
    void keepsTheRegionOfASchemaThatNamesOne() {
        registry.bind(new KinesisSchemaBinding(ACCOUNT, "c1", "dev", "kds", "us-west-2", ROLE));
        registry.bind(binding("c1", "dev", "local"));

        assertThat(registry.find(ACCOUNT, "c1", "dev", "kds").orElseThrow().region(), equalTo("us-west-2"));
        assertThat(registry.find(ACCOUNT, "c1", "dev", "local").orElseThrow().region(), nullValue());
    }

    @Test
    void requiresTheRoleAndTheIdentifyingFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new KinesisSchemaBinding(ACCOUNT, "c1", "dev", "kds", null, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new KinesisSchemaBinding(ACCOUNT, "c1", "dev", "", null, ROLE));
    }
}
