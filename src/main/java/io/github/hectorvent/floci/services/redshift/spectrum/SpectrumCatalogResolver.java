package io.github.hectorvent.floci.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SpectrumCatalogResolver {
    private final ExternalCatalogRegistry registry;
    private final SpectrumCatalog catalog;

    public SpectrumCatalogResolver(ExternalCatalogRegistry registry, SpectrumCatalog catalog) {
        this.registry = registry;
        this.catalog = catalog;
    }

    public Optional<Resolution> resolve(String accountId, String clusterKey, String databaseName, String schemaName) {
        Optional<ExternalSchemaBinding> glue = registry.find(accountId, clusterKey, databaseName, schemaName);
        if (glue.isPresent()) {
            return Optional.of(new Resolution.Glue(glue.get()));
        }
        return catalog.findLegacySchema(accountId, databaseName, schemaName).map(Resolution.PhaseOne::new);
    }

    public Optional<SpectrumExternalTable> legacyTable(String accountId, String databaseName,
                                                        String schemaName, String tableName) {
        return catalog.table(accountId, databaseName, schemaName, tableName);
    }

    public List<String> legacySchemaNames(String accountId, String databaseName) {
        return catalog.legacySchemaNames(accountId, databaseName);
    }

    public sealed interface Resolution permits Resolution.Glue, Resolution.PhaseOne {
        Kind kind();

        enum Kind {
            GLUE,
            PHASE_ONE
        }

        record Glue(ExternalSchemaBinding binding) implements Resolution {
            @Override
            public Kind kind() {
                return Kind.GLUE;
            }
        }

        record PhaseOne(SpectrumExternalSchema schema) implements Resolution {
            @Override
            public Kind kind() {
                return Kind.PHASE_ONE;
            }
        }
    }
}
