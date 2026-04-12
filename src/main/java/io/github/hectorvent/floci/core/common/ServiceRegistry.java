package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of enabled AWS services based on configuration.
 */
@ApplicationScoped
public class ServiceRegistry {

    private static final Logger LOG = Logger.getLogger(ServiceRegistry.class);

    private final ResolvedServiceCatalog catalog;

    @Inject
    public ServiceRegistry(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    public boolean isServiceEnabled(String serviceName) {
        return catalog.byExternalKey(serviceName)
                .map(ServiceDescriptor::enabled)
                .orElse(true);
    }

    public List<String> getEnabledServices() {
        List<String> enabled = new ArrayList<>();
        for (ServiceDescriptor descriptor : catalog.allStatusDescriptors()) {
            if (descriptor.enabled()) {
                enabled.add(descriptor.externalKey());
            }
        }
        return enabled;
    }

    /**
     * Returns all known services with their status: "running" if enabled, "available" if not.
     */
    public Map<String, String> getServices() {
        Map<String, String> services = new LinkedHashMap<>();
        for (ServiceDescriptor descriptor : catalog.allStatusDescriptors()) {
            services.put(descriptor.externalKey(), status(descriptor.enabled()));
        }
        return services;
    }

    private static String status(boolean enabled) {
        return enabled ? "running" : "available";
    }

    public void logEnabledServices() {
        LOG.infov("Enabled services: {0}", getEnabledServices());
    }
}
