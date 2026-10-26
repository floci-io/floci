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
 * {@code CfnResourceDispatcher} consults this for every type; a type nothing here serves is stubbed
 * by the dispatcher, never provisioned.
 */
@ApplicationScoped
public class CloudFormationResourceRegistry {

    private static final String CUSTOM_RESOURCE_TYPE = "AWS::CloudFormation::CustomResource";

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
        CfnResourceProvisioner provisioner = lookup(resourceType);
        if (provisioner != null && provisioner.serviceDisabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(provisioner);
    }

    /**
     * Whether a provisioner exists for {@code resourceType} but its service is turned off, so
     * {@link #forType} serves nothing for it: the type is stubbed, not unsupported.
     */
    public boolean isServiceDisabled(String resourceType) {
        CfnResourceProvisioner provisioner = lookup(resourceType);
        return provisioner != null && provisioner.serviceDisabled();
    }

    /** The config property that enables the disabled service behind {@code resourceType}, or null. */
    public String serviceEnabledFlag(String resourceType) {
        CfnResourceProvisioner provisioner = lookup(resourceType);
        return provisioner == null ? null : provisioner.serviceEnabledFlag();
    }

    private CfnResourceProvisioner lookup(String resourceType) {
        CfnResourceProvisioner provisioner = byType.get(resourceType);
        if (provisioner == null && resourceType != null && resourceType.startsWith("Custom::")) {
            // Custom::* is a dynamic, unenumerable prefix, so it cannot be a registered key. An
            // exact entry still wins (Custom::DynamoDBReplica is registered to DynamoDbCfnProvisioner
            // above); only otherwise-unmatched Custom:: types fall through to the generic handler.
            provisioner = byType.get(CUSTOM_RESOURCE_TYPE);
        }
        return provisioner;
    }

    /**
     * Whether {@code resourceType} is served by the generic custom-resource handler, whose
     * attributes are the handler's response Data: {@code AWS::CloudFormation::CustomResource} and
     * every {@code Custom::} type without an exact entry. {@code Custom::DynamoDBReplica} has its
     * own provisioner and is not.
     */
    public boolean isCustomResourceType(String resourceType) {
        CfnResourceProvisioner generic = byType.get(CUSTOM_RESOURCE_TYPE);
        return generic != null && forType(resourceType).orElse(null) == generic;
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
