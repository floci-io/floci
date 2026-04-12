package io.github.hectorvent.floci.core.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ServiceCatalog {

    private final List<ServiceDescriptor> all;
    private final List<ServiceDescriptor> statusDescriptors;
    private final Map<String, ServiceDescriptor> byExternalKey;
    private final Map<String, ServiceDescriptor> byStorageKey;
    private final Map<String, ServiceDescriptor> byCredentialScope;
    private final Map<Class<?>, ServiceDescriptor> byResourceClass;
    private final Map<String, ServiceDescriptor> byCborSdkServiceId;
    private final List<Map.Entry<String, ServiceDescriptor>> targetPrefixes;

    public ServiceCatalog(List<ServiceDescriptor> descriptors) {
        this.all = List.copyOf(descriptors);

        Map<String, ServiceDescriptor> external = new LinkedHashMap<>();
        Map<String, ServiceDescriptor> storage = new LinkedHashMap<>();
        Map<String, ServiceDescriptor> credentialScopes = new LinkedHashMap<>();
        Map<Class<?>, ServiceDescriptor> resourceClasses = new LinkedHashMap<>();
        Map<String, ServiceDescriptor> cborSdkServiceIds = new LinkedHashMap<>();
        List<Map.Entry<String, ServiceDescriptor>> targets = new ArrayList<>();
        List<ServiceDescriptor> status = new ArrayList<>();

        for (ServiceDescriptor descriptor : descriptors) {
            external.put(descriptor.externalKey(), descriptor);
            if (descriptor.includeInStatus()) {
                status.add(descriptor);
            }
            if (descriptor.storageKey() != null) {
                storage.put(descriptor.storageKey(), descriptor);
            }
            for (String scope : descriptor.credentialScopes()) {
                credentialScopes.put(scope, descriptor);
            }
            for (Class<?> resourceClass : descriptor.resourceClasses()) {
                resourceClasses.put(resourceClass, descriptor);
            }
            for (String serviceId : descriptor.cborSdkServiceIds()) {
                cborSdkServiceIds.put(serviceId, descriptor);
            }
            for (String prefix : descriptor.targetPrefixes()) {
                targets.add(Map.entry(prefix, descriptor));
            }
        }
        targets.sort(Comparator.comparingInt((Map.Entry<String, ServiceDescriptor> entry) -> entry.getKey().length())
                .reversed());

        this.statusDescriptors = List.copyOf(status);
        this.byExternalKey = Map.copyOf(external);
        this.byStorageKey = Map.copyOf(storage);
        this.byCredentialScope = Map.copyOf(credentialScopes);
        this.byResourceClass = Map.copyOf(resourceClasses);
        this.byCborSdkServiceId = Map.copyOf(cborSdkServiceIds);
        this.targetPrefixes = List.copyOf(targets);
    }

    public Optional<ServiceDescriptor> byExternalKey(String externalKey) {
        return Optional.ofNullable(byExternalKey.get(externalKey));
    }

    public Optional<ServiceDescriptor> byStorageKey(String storageKey) {
        return Optional.ofNullable(byStorageKey.get(storageKey));
    }

    public Optional<ServiceDescriptor> byCredentialScope(String credentialScope) {
        return Optional.ofNullable(byCredentialScope.get(credentialScope));
    }

    public Optional<ServiceDescriptor> byResourceClass(Class<?> resourceClass) {
        return Optional.ofNullable(byResourceClass.get(resourceClass));
    }

    public Optional<ServiceDescriptor> byCborSdkServiceId(String serviceId) {
        return Optional.ofNullable(byCborSdkServiceId.get(serviceId));
    }

    public Optional<TargetMatch> matchTarget(String target) {
        if (target == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, ServiceDescriptor> entry : targetPrefixes) {
            if (target.startsWith(entry.getKey())) {
                return Optional.of(new TargetMatch(
                        entry.getValue(),
                        entry.getKey(),
                        target.substring(entry.getKey().length())
                ));
            }
        }
        return Optional.empty();
    }

    public Optional<ServiceDescriptor> byTarget(String target) {
        return matchTarget(target).map(TargetMatch::descriptor);
    }

    public List<ServiceDescriptor> all() {
        return all;
    }

    public List<ServiceDescriptor> allStatusDescriptors() {
        return statusDescriptors;
    }

    public record TargetMatch(ServiceDescriptor descriptor, String prefix, String action) {
    }
}
