package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalFunctionRegistryTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":c", "dev", List.of(), false);
    private ExternalFunctionRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshift"), eq("redshift-external-functions.json"), any(TypeReference.class)))
                .thenReturn(new AccountAwareStorageBackend<ExternalFunctionBinding>(new InMemoryStorage<>(), null, ACCOUNT));
        registry = new ExternalFunctionRegistry(factory);
    }

    private static ExternalFunctionBinding binding(String cluster, String schema, String name, List<String> args) {
        return new ExternalFunctionBinding(ACCOUNT, cluster, "dev", schema, name, args, "integer", "STABLE",
                "lambda_" + name, ROLE, 20_000L, Integer.MAX_VALUE, 5L * 1024 * 1024);
    }

    @Test
    void findsAFunctionBySignatureSoOverloadsStaySeparate() {
        registry.replace(binding(SESSION.clusterKey(), "public", "f", List.of("integer")));
        registry.replace(binding(SESSION.clusterKey(), "public", "f", List.of("integer", "integer")));

        assertThat(registry.findFunction(SESSION, "public", "f", List.of("integer")).orElseThrow().argumentTypes(),
                equalTo(List.of("integer")));
        assertThat(registry.findFunction(SESSION, "public", "f", List.of("integer", "integer")).isPresent(),
                equalTo(true));
        assertThat(registry.findFunction(SESSION, "public", "f", List.of("bigint")).isPresent(), equalTo(false));
    }

    @Test
    void replaceOverwritesAnExistingFunctionOfTheSameSignature() {
        registry.replace(binding(SESSION.clusterKey(), "public", "f", List.of("integer")));
        ExternalFunctionBinding changed = new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev", "public",
                "f", List.of("integer"), "bigint", "VOLATILE", "other", ROLE, 0L, 10, 1024L);

        registry.replace(changed);

        assertThat(registry.findFunction(SESSION, "public", "f", List.of("integer")).orElseThrow(), equalTo(changed));
        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev").size(), equalTo(1));
    }

    @Test
    void unbindsOneFunctionAndForgetsAWholeCluster() {
        ExternalFunctionBinding one = binding(SESSION.clusterKey(), "public", "f", List.of("integer"));
        registry.replace(one);
        registry.replace(binding(SESSION.clusterKey(), "sales", "g", List.of()));
        registry.replace(binding(ACCOUNT + ":other", "public", "f", List.of("integer")));

        registry.unbindFunction(one);
        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev").stream()
                .map(ExternalFunctionBinding::functionName).toList(), contains("g"));

        registry.removeCluster(ACCOUNT, SESSION.clusterKey());
        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev"), empty());
        assertThat(registry.list(ACCOUNT, ACCOUNT + ":other", "dev").size(), equalTo(1));
    }

    @Test
    void clearForgetsEverything() {
        registry.replace(binding(SESSION.clusterKey(), "public", "f", List.of()));

        registry.clear();

        assertThat(registry.list(ACCOUNT, SESSION.clusterKey(), "dev"), empty());
    }

    @Test
    void rejectsABindingThatNamesNeitherVolatilityNorALambda() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalFunctionBinding(ACCOUNT, "c", "dev", "public",
                "f", List.of(), "integer", "IMMUTABLE", "l", ROLE, 0L, 1, 1024L));
        assertThrows(IllegalArgumentException.class, () -> new ExternalFunctionBinding(ACCOUNT, "c", "dev", "public",
                "f", List.of(), "integer", "STABLE", " ", ROLE, 0L, 1, 1024L));
    }
}
