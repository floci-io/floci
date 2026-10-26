package io.github.hectorvent.floci.services.iam;

import java.util.List;
import java.util.Optional;

/**
 * Looks up what a stored resource holds that the {@code iam:PassRole} check of a request needs, for
 * the operations that pass a role the request does not name itself: an ECS task started from a task
 * definition runs with the definition's roles, a self-managed StackSet that names no
 * administration role uses the default one, an Auto Scaling group launches its instances with
 * the instance profile of its launch template, or of the instance its launch configuration was made from,
 * and a Service Catalog constraint update names no type, which says whether it sets a launch role.
 *
 * <p>Implemented by the services that own those resources, each answering for its own and leaving
 * the default for the rest; consumed lazily by {@code IamEnforcementFilter} via
 * {@code Instance<PassedRoleLookup>} so IAM never depends on them directly. Lookups are made in the
 * request's account.</p>
 */
public interface PassedRoleLookup {

    /**
     * The task role and the task execution role of the ECS task definition {@code reference} names in
     * {@code region}, a family, a {@code family:revision} or an ARN, as the definition stores them;
     * empty when there is no such definition or it names neither.
     */
    default List<String> taskDefinitionRoles(String reference, String region) {
        return List.of();
    }

    /** The permission model of the StackSet named {@code stackSetName}; empty when there is none. */
    default Optional<String> stackSetPermissionModel(String stackSetName) {
        return Optional.empty();
    }

    /**
     * The instance profile, by its ARN or else its name, of the launch template version an Auto Scaling
     * group launches from when it names the template {@code templateId} or {@code templateName} and
     * {@code version} in {@code region}; empty when there is no such template or version, or it names
     * no instance profile.
     */
    default Optional<String> launchTemplateInstanceProfile(String templateId, String templateName, String version,
                                                           String region) {
        return Optional.empty();
    }

    /**
     * The instance profile, by its ARN, that a launch configuration made from the EC2 instance
     * {@code instanceId} in {@code region} takes from it; empty when there is no such instance or it has none.
     */
    default Optional<String> launchConfigurationInstanceProfile(String instanceId, String region) {
        return Optional.empty();
    }

    /**
     * The instance profile, by its ARN or else its name, of the launch template version the group
     * {@code groupName} in {@code region} launches from once an instance refresh applies a desired
     * configuration naming {@code templateId}, {@code templateName} and {@code version}, each as the
     * request gives it or null; empty when there is no such group, it then launches from no launch
     * template, or that version names no instance profile.
     */
    default Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId, String templateName,
                                                            String version, String region) {
        return Optional.empty();
    }

    /** The type of the Service Catalog constraint {@code constraintId}; empty when there is none. */
    default Optional<String> serviceCatalogConstraintType(String constraintId) {
        return Optional.empty();
    }
}
