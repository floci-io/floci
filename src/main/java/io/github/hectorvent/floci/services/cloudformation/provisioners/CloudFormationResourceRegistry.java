package io.github.hectorvent.floci.services.cloudformation.provisioners;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a CloudFormation resource type to the {@link CfnResourceProvisioner} that serves it.
 * {@code CloudFormationResourceProvisioner} consults this first and falls through to its own
 * switch for types not yet extracted, so the two coexist during the incremental migration.
 */
@ApplicationScoped
public class CloudFormationResourceRegistry {

    private final Map<String, CfnResourceProvisioner> byType = new HashMap<>();

    @Inject
    public CloudFormationResourceRegistry(Instance<CfnResourceProvisioner> provisioners) {
        provisioners.forEach(this::register);
    }

    /** Factory constructor: build a registry from an explicit list, bypassing CDI (tests). */
    public CloudFormationResourceRegistry(Collection<CfnResourceProvisioner> provisioners) {
        provisioners.forEach(this::register);
    }

    private void register(CfnResourceProvisioner provisioner) {
        for (String type : provisioner.resourceTypes()) {
            CfnResourceProvisioner existing = byType.put(type, provisioner);
            if (existing != null) {
                throw new IllegalStateException("Duplicate CloudFormation provisioner for " + type
                        + ": " + existing.getClass().getSimpleName() + " and "
                        + provisioner.getClass().getSimpleName());
            }
        }
    }

    public Optional<CfnResourceProvisioner> forType(String resourceType) {
        return Optional.ofNullable(byType.get(resourceType));
    }
}
