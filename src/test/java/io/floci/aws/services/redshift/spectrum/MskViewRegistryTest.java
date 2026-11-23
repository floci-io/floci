package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MskViewRegistryTest {
    private static final String ACCOUNT = "000000000000";
    private MskViewRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshift"), eq("redshift-msk-views.json"), any(TypeReference.class)))
                .thenReturn(new AccountAwareStorageBackend<MskViewBinding>(new InMemoryStorage<>(), null, ACCOUNT));
        registry = new MskViewRegistry(factory);
    }

    private static MskViewBinding view(String schema, String name) {
        return new MskViewBinding(ACCOUNT, "c1", "dev", schema, name, "msk", "orders", "SELECT 1", Map.of());
    }

    @Test
    void aViewIsKeyedBySchemaAndNameSoTheSameNameInTwoSchemasIsTwoViews() {
        registry.bind(view("public", "mv"));
        registry.bind(view("analytics", "mv"));

        assertThat(registry.find(ACCOUNT, "c1", "dev", MskViewBinding.nameOf("public", "mv")).isPresent(), is(true));
        assertThat(registry.find(ACCOUNT, "c1", "dev", MskViewBinding.nameOf("analytics", "mv")).isPresent(), is(true));
        assertThat(registry.list(ACCOUNT, "c1", "dev").size(), equalTo(2));
    }

    @Test
    void rejectsADuplicateView() {
        registry.bind(view("public", "mv"));

        assertThrows(SpectrumSqlException.class, () -> registry.bind(view("public", "mv")));
    }

    @Test
    void replacingAViewKeepsItsNewCheckpoints() {
        MskViewBinding bound = view("public", "mv");
        registry.bind(bound);

        registry.replace(bound.withCheckpoints(Map.of(0, 41L, 1, 7L)));

        MskViewBinding found = registry.find(ACCOUNT, "c1", "dev", bound.bindingName()).orElseThrow();
        assertThat(found.checkpoints(), equalTo(Map.of("0", 41L, "1", 7L)));
    }

    @Test
    void withCheckpointsKeepsThePartitionsItDoesNotMention() {
        MskViewBinding moved = view("public", "mv").withCheckpoints(Map.of(0, 1L, 1, 2L)).withCheckpoints(Map.of(1, 5L));

        assertThat(moved.checkpoints(), equalTo(Map.of("0", 1L, "1", 5L)));
    }

    @Test
    void aBindingSurvivesAJsonRoundTripWithItsCheckpoints() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MskViewBinding original = view("public", "mv").withCheckpoints(Map.of(0, 9_000_000_000L));

        MskViewBinding restored = mapper.readValue(mapper.writeValueAsString(original), MskViewBinding.class);

        assertThat(restored, equalTo(original));
    }

    @Test
    void clearRemovesEveryView() {
        registry.bind(view("public", "mv"));

        registry.clear();

        assertThat(registry.list(ACCOUNT, "c1", "dev").isEmpty(), is(true));
    }
}
