package io.floci.aws.core.common;

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
        Set<Class<?>> resourceClasses,
        Set<String> jsonOnlyTargetPrefixes,
        boolean acceptsLegacyAuthorization
) {

    public ServiceDescriptor(String externalKey, String configKey, boolean enabled, boolean includeInStatus,
                             String storageKey, String storageMode, long storageFlushIntervalMs,
                             String xmlNamespace, ServiceProtocol defaultProtocol,
                             Set<ServiceProtocol> supportedProtocols, Set<String> targetPrefixes,
                             Set<String> credentialScopes, Set<String> cborSdkServiceIds,
                             Set<Class<?>> resourceClasses, Set<String> jsonOnlyTargetPrefixes) {
        this(externalKey, configKey, enabled, includeInStatus, storageKey, storageMode, storageFlushIntervalMs,
                xmlNamespace, defaultProtocol, supportedProtocols, targetPrefixes, credentialScopes,
                cborSdkServiceIds, resourceClasses, jsonOnlyTargetPrefixes, false);
    }

    public ServiceDescriptor withLegacyAuthorization() {
        return new ServiceDescriptor(externalKey, configKey, enabled, includeInStatus, storageKey, storageMode,
                storageFlushIntervalMs, xmlNamespace, defaultProtocol, supportedProtocols, targetPrefixes,
                credentialScopes, cborSdkServiceIds, resourceClasses, jsonOnlyTargetPrefixes, true);
    }

    public boolean supportsStorage() {
        return storageKey != null;
    }

    public boolean supportsProtocol(ServiceProtocol protocol) {
        return supportedProtocols.contains(protocol);
    }
}
