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

class MskSchemaRegistryTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final String CLUSTER = "arn:aws:kafka:us-east-1:000000000000:cluster/orders/abc-123";
    private MskSchemaRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshift"), eq("redshift-msk-schemas.json"), any(TypeReference.class)))
                .thenReturn(new AccountAwareStorageBackend<MskSchemaBinding>(new InMemoryStorage<>(), null, ACCOUNT));
        registry = new MskSchemaRegistry(factory);
    }

    private static MskSchemaBinding binding(String cluster, String database, String schema) {
        return new MskSchemaBinding(ACCOUNT, cluster, database, schema, null, ROLE, "IAM", CLUSTER);
    }

    @Test
    void bindsFindsListsAndUnbinds() {
        registry.bind(binding("c1", "dev", "b"));
        registry.bind(binding("c1", "dev", "a"));

        MskSchemaBinding found = registry.find(ACCOUNT, "c1", "dev", "a").orElseThrow();
        assertThat(found.iamRoleArn(), equalTo(ROLE));
        assertThat(found.authentication(), equalTo("IAM"));
        assertThat(found.clusterArn(), equalTo(CLUSTER));
        assertThat(registry.list(ACCOUNT, "c1", "dev").stream().map(MskSchemaBinding::schemaName).toList(),
                contains("a", "b"));

        registry.unbind(ACCOUNT, "c1", "dev", "a");

        assertThat(registry.find(ACCOUNT, "c1", "dev", "a").isPresent(), equalTo(false));
        assertThat(registry.list(ACCOUNT, "c1", "dev").size(), equalTo(1));
    }

    @Test
    void rejectsADuplicateSchemaInTheSameDatabase() {
        registry.bind(binding("c1", "dev", "msk"));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> registry.bind(binding("c1", "dev", "msk")));

        assertThat(error.sqlState(), equalTo("42P06"));
    }

    @Test
    void keepsClustersAndDatabasesApart() {
        registry.bind(binding("c1", "dev", "msk"));
        registry.bind(binding("c1", "other", "msk"));
        registry.bind(binding("c2", "dev", "msk"));

        assertThat(registry.list(ACCOUNT, "c1", "dev").size(), equalTo(1));
        assertThat(registry.list(ACCOUNT, "c1", "other").size(), equalTo(1));
        assertThat(registry.list(ACCOUNT, "c3", "dev"), empty());
    }

    @Test
    void doesNotMixUpComponentsThatContainTheSeparator() {
        registry.bind(new MskSchemaBinding(ACCOUNT, "c|1", "dev", "msk", null, ROLE, "IAM", CLUSTER));

        assertThat(registry.find(ACCOUNT, "c", "1|dev", "msk").isPresent(), equalTo(false));
        assertThat(registry.find(ACCOUNT, "c|1", "dev", "msk").isPresent(), equalTo(true));
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
    void keepsThePartitionOfASchemaThatNamesOne() {
        registry.bind(new MskSchemaBinding(ACCOUNT, "c1", "dev", "msk", "aws-us-gov", ROLE, "NONE", CLUSTER));
        registry.bind(binding("c1", "dev", "local"));

        assertThat(registry.find(ACCOUNT, "c1", "dev", "msk").orElseThrow().partition(), equalTo("aws-us-gov"));
        assertThat(registry.find(ACCOUNT, "c1", "dev", "local").orElseThrow().partition(), nullValue());
    }

    @Test
    void validatesRequiredFieldsAndAuthenticationMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new MskSchemaBinding(ACCOUNT, "c1", "dev", "msk", null, " ", "IAM", CLUSTER));
        assertThrows(IllegalArgumentException.class,
                () -> new MskSchemaBinding(ACCOUNT, "c1", "dev", "", null, ROLE, "IAM", CLUSTER));
        assertThrows(IllegalArgumentException.class,
                () -> new MskSchemaBinding(ACCOUNT, "c1", "dev", "msk", null, ROLE, "IAM", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new MskSchemaBinding(ACCOUNT, "c1", "dev", "msk", null, ROLE, "MTLS", CLUSTER));
        assertThrows(IllegalArgumentException.class,
                () -> new MskSchemaBinding(ACCOUNT, "c1", "dev", "msk", " ", ROLE, "IAM", CLUSTER));
    }
}
