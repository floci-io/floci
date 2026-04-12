package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ServiceConfigAccess {

    private final ResolvedServiceCatalog catalog;
    private final EmulatorConfig config;

    @Inject
    public ServiceConfigAccess(ResolvedServiceCatalog catalog, EmulatorConfig config) {
        this.catalog = catalog;
        this.config = config;
    }

    public boolean isEnabled(String externalKey) {
        return catalog.byExternalKey(externalKey)
                .map(ServiceDescriptor::enabled)
                .orElse(true);
    }

    public String storageMode(String storageKey) {
        return catalog.byStorageKey(storageKey)
                .map(ServiceDescriptor::storageMode)
                .orElse(config.storage().mode());
    }

    public long storageFlushInterval(String storageKey) {
        return catalog.byStorageKey(storageKey)
                .map(ServiceDescriptor::storageFlushIntervalMs)
                .orElse(5000L);
    }
}
