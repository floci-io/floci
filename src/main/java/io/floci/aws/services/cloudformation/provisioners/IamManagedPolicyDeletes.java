package io.floci.aws.services.cloudformation.provisioners;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamGroup;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.IamUser;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Delete helpers shared by the two IAM policy provisioners: {@code IamManagedPolicyCfnProvisioner}
 * (a customer-managed policy) and {@code IamPolicyCfnProvisioner}'s legacy path (an inline policy
 * once persisted as a managed one). Both detach a stack-owned managed policy from its principals
 * before deleting it, and both resolve those principals the same way.
 */
final class IamManagedPolicyDeletes {

    private static final Logger LOG = Logger.getLogger(IamManagedPolicyDeletes.class);

    private IamManagedPolicyDeletes() {
    }

    /**
     * The roles a stack-owned managed policy is attached to: the targets recorded at provision
     * time, or, for a resource persisted before that metadata existed, the roles that currently
     * reference the policy ARN.
     */
    static List<String> roleTargets(IamService iamService, StackResource resource) {
        String policyArn = resource.getPhysicalId();
        String targets = resource.getAttributes().get("ManagedPolicyRoleTargets");
        String ids = resource.getAttributes().get("ManagedPolicyRoleTargetIds");
        if (targets != null) {
            List<String> recordedNames = splitTargets(targets);
            if (recordedNames.isEmpty() || ids == null || ids.isBlank()) {
                return recordedNames;
            }
            return recordedTargets(targets, ids,
                    iamService.listRoles("/"), IamRole::getRoleId, IamRole::getRoleName);
        }
        // Stacks persisted before target metadata was introduced still need to be deletable.
        // The policy is stack-owned, so discover only roles that currently reference this ARN.
        List<IamRole> attachedRoles = iamService.listRoles("/");
        targets = attachedRoles.stream()
                .filter(role -> role.getAttachedPolicyArns().contains(policyArn))
                .map(IamRole::getRoleName)
                .collect(Collectors.joining("\n"));
        if (targets.isBlank()) {
            return List.of();
        }
        return recordedTargets(targets, ids, attachedRoles,
                IamRole::getRoleId, IamRole::getRoleName);
    }

    static List<String> userTargets(IamService iamService, StackResource resource) {
        return principalTargets(resource, "ManagedPolicyUserTargets", "ManagedPolicyUserTargetIds",
                () -> iamService.listUsers("/"), IamUser::getUserId, IamUser::getUserName);
    }

    static List<String> groupTargets(IamService iamService, StackResource resource) {
        return principalTargets(resource, "ManagedPolicyGroupTargets", "ManagedPolicyGroupTargetIds",
                () -> iamService.listGroups("/"), IamGroup::getGroupId, IamGroup::getGroupName);
    }

    private static <T> List<String> principalTargets(StackResource resource, String namesKey, String idsKey,
                                                     Supplier<List<T>> listPrincipals,
                                                     Function<T, String> idGetter,
                                                     Function<T, String> nameGetter) {
        String names = resource.getAttributes().get(namesKey);
        String ids = resource.getAttributes().get(idsKey);
        if (splitTargets(names).isEmpty()) {
            return List.of();
        }
        return recordedTargets(names, ids, ids == null || ids.isBlank() ? List.of() : listPrincipals.get(),
                idGetter, nameGetter);
    }

    /**
     * Detaches the policy from every target, then deletes it. Deletion is idempotent: a principal,
     * attachment or the policy itself already being gone (NoSuchEntity) is tolerated, while a
     * permission or service failure propagates so the stack stays DELETE_FAILED.
     */
    static void detachAndDeletePolicy(IamService iamService, StackResource resource) {
        String policyArn = resource.getPhysicalId();
        detachTargets(policyArn, roleTargets(iamService, resource), iamService::detachRolePolicy);
        detachTargets(policyArn, userTargets(iamService, resource), iamService::detachUserPolicy);
        detachTargets(policyArn, groupTargets(iamService, resource), iamService::detachGroupPolicy);
        deletePolicyTolerating(iamService, policyArn);
    }

    static <T> List<String> recordedTargets(String targets, String ids, List<T> attached,
                                            Function<T, String> idGetter,
                                            Function<T, String> nameGetter) {
        List<String> targetNames = splitTargets(targets);
        if (targetNames.isEmpty()) {
            return List.of();
        }
        if (ids == null || ids.isBlank()) {
            return targetNames;
        }
        Map<String, String> currentNames = attached.stream().collect(Collectors.toMap(
                idGetter, nameGetter, (first, ignored) -> first));
        List<String> targetIds = splitTargets(ids);
        List<String> resolved = new ArrayList<>();
        for (int i = 0; i < targetNames.size(); i++) {
            String id = i < targetIds.size() ? targetIds.get(i) : null;
            if (id == null || id.isBlank()) {
                resolved.add(targetNames.get(i));
            } else if (currentNames.containsKey(id)) {
                resolved.add(currentNames.get(id));
            }
        }
        return resolved;
    }

    private static List<String> splitTargets(String targets) {
        return targets == null ? List.of() : targets.lines().filter(target -> !target.isBlank()).toList();
    }

    private static void detachTargets(String policyArn, List<String> names,
                                      BiConsumer<String, String> detach) {
        for (String name : names) {
            try {
                detach.accept(name, policyArn);
            } catch (AwsException e) {
                if (!"NoSuchEntity".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }
    }

    /** Deletes the policy, tolerating one already gone. */
    static void deletePolicyTolerating(IamService iamService, String policyArn) {
        try {
            iamService.deletePolicy(policyArn);
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("IAM policy already gone, treating as deleted: {0}", policyArn);
        }
    }
}
