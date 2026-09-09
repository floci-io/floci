package io.github.hectorvent.floci.services.cloudformation.provisioners;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Every resource type served by an extracted provisioner, as resolved by CDI. Read by the
     * inventory test: a provisioner missing {@code @ApplicationScoped} compiles and unit-tests
     * green but never reaches this map, so only the discovered set can catch it.
     */
    public Set<String> registeredTypes() {
        return Set.copyOf(byType.keySet());
    }

    /**
     * The provisioner class serving {@code resourceType}, or null when none does. Under CDI the
     * instance is an ArC client proxy ({@code SqsCfnProvisioner_ClientProxy}), so the generated
     * suffix is trimmed to leave the authored class name.
     */
    public String ownerOf(String resourceType) {
        CfnResourceProvisioner provisioner = byType.get(resourceType);
        if (provisioner == null) {
            return null;
        }
        String name = provisioner.getClass().getSimpleName();
        int generatedSuffix = name.indexOf('_');
        return generatedSuffix < 0 ? name : name.substring(0, generatedSuffix);
    }
}
