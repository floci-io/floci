package io.floci.aws.core.common;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.PassedRoleLookup;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What {@link PassedRoles} looks up to find the roles a request passes, in one account and Region: the roles IAM holds
 * and the instance profiles holding them, and what the services owning a resource that names roles answer
 * ({@link PassedRoleLookup}). A request made directly and a call made on a caller's behalf (a state machine's task)
 * look them up alike.
 */
@ApplicationScoped
public class PassedRoleLookups {

    private final IamService iamService;
    private final Iterable<PassedRoleLookup> services;

    @Inject
    public PassedRoleLookups(IamService iamService, Instance<PassedRoleLookup> services) {
        this(iamService, (Iterable<PassedRoleLookup>) services);
    }

    /**
     * @param iamService the roles and instance profiles IAM holds; without it IAM holds none
     * @param services   the services that answer for a resource of theirs; without them none answers
     */
    public PassedRoleLookups(IamService iamService, Iterable<PassedRoleLookup> services) {
        this.iamService = iamService;
        this.services = services;
    }

    /** What is looked up for a request made in {@code accountId} and {@code region}, in {@code partition}. */
    PassedRoles.Lookups in(String partition, String accountId, String region) {
        return new PassedRoles.Lookups() {
            @Override
            public List<String> instanceProfileRoleArns(String profileName, String profileArn) {
                return PassedRoleLookups.this.instanceProfileRoleArns(accountId, profileName, profileArn);
            }

            @Override
            public List<String> taskDefinitionRoles(String reference) {
                List<String> roles = new ArrayList<>();
                for (PassedRoleLookup lookup : services()) {
                    roles.addAll(lookup.taskDefinitionRoles(reference, region));
                }
                return roles;
            }

            @Override
            public Optional<String> stackSetPermissionModel(String stackSetName) {
                return firstFound(lookup -> lookup.stackSetPermissionModel(stackSetName));
            }

            @Override
            public Optional<String> launchTemplateInstanceProfile(String templateId, String templateName,
                                                                  String version) {
                return firstFound(lookup -> lookup.launchTemplateInstanceProfile(templateId, templateName, version,
                        region));
            }

            @Override
            public Optional<String> launchConfigurationInstanceProfile(String instanceId) {
                return firstFound(lookup -> lookup.launchConfigurationInstanceProfile(instanceId, region));
            }

            @Override
            public Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId,
                                                                   String templateName, String version) {
                return firstFound(lookup -> lookup.instanceRefreshInstanceProfile(groupName, templateId, templateName,
                        version, region));
            }

            @Override
            public String roleArn(String roleName) {
                return AwsArnUtils.Arn.global(partition, "iam", accountId, "role/" + roleName).toString();
            }

            @Override
            public Optional<String> heldRoleArn(String roleName) {
                return iamService == null ? Optional.empty()
                        : iamService.findRole(accountId, roleName).map(IamRole::getArn);
            }

            @Override
            public Optional<String> serviceCatalogConstraintType(String constraintId) {
                return firstFound(lookup -> lookup.serviceCatalogConstraintType(constraintId));
            }
        };
    }

    private Iterable<PassedRoleLookup> services() {
        return services == null ? List.of() : services;
    }

    /** The first answer a {@link PassedRoleLookup} gives, each answering only for what its service owns. */
    private Optional<String> firstFound(Function<PassedRoleLookup, Optional<String>> question) {
        for (PassedRoleLookup lookup : services()) {
            Optional<String> answer = question.apply(lookup);
            if (answer.isPresent()) {
                return answer;
            }
        }
        return Optional.empty();
    }

    /** The ARNs of the roles the instance profile, named by {@code name} or {@code arn}, holds. */
    private List<String> instanceProfileRoleArns(String accountId, String name, String arn) {
        if (iamService == null) {
            return List.of();
        }
        String profileName = arn != null ? arn.substring(arn.lastIndexOf('/') + 1) : name;
        return iamService.findInstanceProfile(accountId, profileName)
                .filter(profile -> arn == null || arn.equals(profile.getArn()))
                .map(profile -> profile.getRoleNames().stream()
                        .flatMap(roleName -> iamService.findRole(accountId, roleName).stream())
                        .map(IamRole::getArn)
                        .toList())
                .orElse(List.of());
    }
}
