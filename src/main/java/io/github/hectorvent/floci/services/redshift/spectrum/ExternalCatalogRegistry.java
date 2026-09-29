package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ExternalCatalogRegistry implements Resettable {
    private static final char SEPARATOR = '|';
    private final AccountAwareStorageBackend<ExternalSchemaBinding> bindings;

    public ExternalCatalogRegistry(StorageFactory storageFactory) {
        bindings = storageFactory.create("redshift", "redshift-external-schemas.json", new TypeReference<>() { });
    }

    public synchronized void bind(ExternalSchemaBinding binding) {
        String key = key(binding.clusterKey(), binding.databaseName(), binding.schemaName());
        if (bindings.getForAccount(binding.accountId(), key).isPresent()) {
            throw new SpectrumSqlException("42P06", "schema \"" + binding.schemaName() + "\" already exists");
        }
        bindings.putForAccount(binding.accountId(), key, binding);
        bindings.flush();
    }

    public Optional<ExternalSchemaBinding> find(String accountId, String clusterKey, String databaseName, String schemaName) {
        return bindings.getForAccount(accountId, key(clusterKey, databaseName, schemaName));
    }

    public List<ExternalSchemaBinding> list(String accountId, String clusterKey, String databaseName) {
        String prefix = prefix(clusterKey, databaseName);
        return bindings.scanForAccount(accountId, key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(ExternalSchemaBinding::schemaName)).toList();
    }

    public synchronized void unbind(String accountId, String clusterKey, String databaseName, String schemaName) {
        bindings.deleteForAccount(accountId, key(clusterKey, databaseName, schemaName));
        bindings.flush();
    }

    public synchronized void removeCluster(String accountId, String clusterKey) {
        String prefix = prefix(clusterKey);
        for (String key : bindings.keysForAccount(accountId)) {
            if (key.startsWith(prefix)) {
                bindings.deleteForAccount(accountId, key);
            }
        }
        bindings.flush();
    }

    @Override
    public synchronized void clear() {
        bindings.clear();
        bindings.flush();
    }

    private static String key(String clusterKey, String databaseName, String schemaName) {
        return prefix(clusterKey, databaseName) + encode(schemaName);
    }

    private static String prefix(String clusterKey, String databaseName) {
        return prefix(clusterKey) + encode(databaseName) + SEPARATOR;
    }

    private static String prefix(String clusterKey) {
        return encode(clusterKey) + SEPARATOR;
    }

    /** Percent-encodes a component so a quoted identifier containing the separator cannot collide with another key. */
    private static String encode(String component) {
        return URLEncoder.encode(component, StandardCharsets.UTF_8);
    }
}
