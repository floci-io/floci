package io.github.hectorvent.floci.core.common;

import java.util.Set;

public record ServiceDescriptor(
        String externalKey,
        String configKey,
        boolean enabled,
        boolean includeInStatus,
        String storageKey,
        String storageMode,
        long storageFlushIntervalMs,
        String xmlNamespace,
        ServiceProtocol defaultProtocol,
        Set<ServiceProtocol> supportedProtocols,
        Set<String> targetPrefixes,
        Set<String> credentialScopes,
        Set<String> cborSdkServiceIds,
        Set<Class<?>> resourceClasses
) {

    public boolean supportsStorage() {
        return storageKey != null;
    }

    public boolean supportsProtocol(ServiceProtocol protocol) {
        return supportedProtocols.contains(protocol);
    }
}
