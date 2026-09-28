package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Delete helpers shared by the two IAM policy provisioners: {@code IamManagedPolicyCfnProvisioner}
 * (a customer-managed policy) and {@code IamPolicyCfnProvisioner}'s legacy path (an inline policy
 * once persisted as a managed one). Both detach a stack-owned managed policy from its roles before
 * deleting it, and both resolve those roles the same way.
 */
final class IamManagedPolicyDeletes {

    private static final Logger LOG = Logger.getLogger(IamManagedPolicyDeletes.class);

    private IamManagedPolicyDeletes() {
    }

    /**
     * The roles a stack-owned managed policy is attached to: the targets recorded at provision time,
     * or, for a resource persisted before that metadata existed, the roles that currently reference
     * the policy ARN.
     */
    static List<String> roleTargets(IamService iamService, StackResource resource) {
        String policyArn = resource.getPhysicalId();
        String targets = resource.getAttributes().get("ManagedPolicyRoleTargets");
        if (targets == null) {
            // Stacks persisted before target metadata was introduced still need to be deletable.
            // The policy is stack-owned, so discover only roles that currently reference this ARN.
            targets = iamService.listRoles("/").stream()
                    .filter(role -> role.getAttachedPolicyArns().contains(policyArn))
                    .map(IamRole::getRoleName)
                    .collect(Collectors.joining("\n"));
        }
        if (targets == null || targets.isBlank()) {
            return List.of();
        }
        return targets.lines().filter(roleName -> !roleName.isBlank()).toList();
    }

    /**
     * Detaches the policy from every role it targets, then deletes it. Deletion is idempotent: a
     * role, attachment or the policy itself already being gone (NoSuchEntity) is tolerated, while a
     * permission or service failure propagates so the stack stays DELETE_FAILED.
     */
    static void detachRolesAndDeletePolicy(IamService iamService, StackResource resource) {
        String policyArn = resource.getPhysicalId();
        for (String roleName : roleTargets(iamService, resource)) {
            try {
                iamService.detachRolePolicy(roleName, policyArn);
            } catch (AwsException e) {
                if (!"NoSuchEntity".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }
        deletePolicyTolerating(iamService, policyArn);
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
