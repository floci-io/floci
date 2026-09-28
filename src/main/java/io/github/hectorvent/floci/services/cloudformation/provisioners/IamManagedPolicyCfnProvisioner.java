package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CloudFormation provisioning for {@code AWS::IAM::ManagedPolicy}, a standalone customer-managed
 * policy with an ARN that must be detached from its roles before deletion. {@code Ref} and
 * {@code Fn::GetAtt PolicyArn} return the policy ARN. A managed policy name is account-global, so
 * the physical name is honoured verbatim from {@code ManagedPolicyName} when set.
 *
 * <p>On {@code UpdateStack} with an unchanged name the policy this stack provisioned already
 * exists, so {@code PolicyDocument} is applied as a new default version in place (pruning the
 * oldest versions to stay under IAM's five-version cap) and roles no longer listed are detached,
 * matching CloudFormation. A failure mid-update restores the version and attachment state this pass
 * changed rather than leaving the policy half-migrated.
 */
@ApplicationScoped
public class IamManagedPolicyCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IAM::ManagedPolicy";

    private final IamService iamService;

    @Inject
    public IamManagedPolicyCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String policyName = ctx.resolveOptional(props, "ManagedPolicyName");
        if (policyName == null || policyName.isBlank()) {
            policyName = ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }
        String document = ctx.resolvePolicyDocument(props);
        List<String> roleNames = ctx.resolveStringList(props, "Roles");
        String existingArn = r.getPhysicalId();

        IamPolicy policy;
        boolean createdPolicy = false;
        String previousDefaultVersionId = null;
        PolicyVersion createdVersionForRollback = null;
        List<PolicyVersion> prunedVersionsForRollback = new ArrayList<>();
        List<String> detachedObsoleteRoles = new ArrayList<>();
        try {
            policy = iamService.createPolicy(policyName, "/", null, document, Map.of());
            createdPolicy = true;
        } catch (AwsException e) {
            // Stack UPDATE with an unchanged policy name: the policy this stack provisioned on a
            // previous pass already exists. PolicyDocument is a mutable property, so CloudFormation
            // updates the policy in place (a new default version) rather than replacing it. Only
            // adopt when the existing physical id is this exact policy: a collision with a policy
            // some other stack owns must still fail like AWS does.
            boolean stackAlreadyOwnsPolicy = existingArn != null
                    && existingArn.endsWith(":policy/" + policyName);
            if (!stackAlreadyOwnsPolicy || !"EntityAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
            policy = iamService.getPolicy(existingArn);
            String policyId = r.getAttributes().get("PolicyId");
            // A missing PolicyId means this resource predates PolicyId tracking (an upgrade from an
            // older floci pass): its identity cannot be verified, and the ARN alone is not proof of
            // ownership, since a policy deleted and recreated under the same name reuses the same
            // ARN with a different PolicyId. Fail closed rather than adopting a policy this stack no
            // longer owns.
            if (policyId == null || !policyId.equals(policy.getPolicyId())) {
                throw e;
            }
            previousDefaultVersionId = policy.getDefaultVersionId();
            // IAM caps a managed policy at 5 versions; prune the oldest non-default ones the way
            // CloudFormation does, so repeated stack updates never die on LimitExceeded.
            List<PolicyVersion> versions = iamService.listPolicyVersions(existingArn).stream()
                    .filter(v -> !v.isDefaultVersion())
                    .sorted(Comparator.comparingInt(v -> Integer.parseInt(v.getVersionId().substring(1))))
                    .toList();
            for (int i = 0; i <= versions.size() - 4; i++) {
                PolicyVersion pruned = versions.get(i);
                // Captured before deletion so a later failure in this same update can recreate the
                // content: the version id itself is gone for good (AWS never reissues one), but the
                // document must survive a rollback that reports COMPLETE.
                prunedVersionsForRollback.add(pruned);
                iamService.deletePolicyVersion(existingArn, pruned.getVersionId());
            }
            createdVersionForRollback = iamService.createPolicyVersion(existingArn, document, true);
            // Roles this stack attached on the previous pass but no longer listed in the template
            // are detached, matching CloudFormation's update semantics.
            String previousTargets = r.getAttributes().get("ManagedPolicyRoleTargets");
            if (previousTargets != null && !previousTargets.isBlank()) {
                for (String previousRole : previousTargets.split("\n")) {
                    if (!roleNames.contains(previousRole)) {
                        try {
                            iamService.detachRolePolicy(previousRole, existingArn);
                            detachedObsoleteRoles.add(previousRole);
                        } catch (AwsException detachFailure) {
                            // Update is idempotent like the delete path: the attachment can already
                            // be gone on a retry, but other failures must surface and still restore
                            // the version/attachments this pass changed, the same as a failure in
                            // the attach loop below (this loop runs first, so that loop's own catch
                            // never sees this failure).
                            if (!"NoSuchEntity".equals(detachFailure.getErrorCode())) {
                                restoreOnUpdateFailure(detachFailure, r, existingArn,
                                        false, Set.of(), detachedObsoleteRoles,
                                        previousDefaultVersionId, createdVersionForRollback,
                                        prunedVersionsForRollback);
                                throw detachFailure;
                            }
                        }
                    }
                }
            }
        }
        r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        r.getAttributes().put("PolicyId", policy.getPolicyId());
        r.setPhysicalId(policy.getArn());
        // PolicyArn is the attribute CloudFormation documents for this type, and what a template
        // written against AWS asks for. Without it Fn::GetAtt does not resolve and the unresolved
        // literal reaches whatever consumed it, a role's ManagedPolicyArns typically, which then
        // fails with "policy does not exist" and rolls the stack back. "Arn" stays for callers
        // already using it.
        r.getAttributes().put("Arn", policy.getArn());
        r.getAttributes().put("PolicyArn", policy.getArn());
        r.getAttributes().put("DefaultVersionId", policy.getDefaultVersionId());
        r.getAttributes().put("ManagedPolicyRoleTargets", String.join("\n", roleNames));

        String policyArn = policy.getArn();
        // On adopt, attachments from the previous pass are not this attempt's to undo.
        Set<String> previouslyAttached = createdPolicy
                ? Set.of()
                : iamService.listEntitiesForPolicy(policyArn).roles().stream()
                        .map(role -> role.getRoleName())
                        .collect(Collectors.toSet());
        LinkedHashSet<String> attachedRoleNames = new LinkedHashSet<>();
        try {
            for (String roleName : roleNames) {
                iamService.attachRolePolicy(roleName, policyArn);
                if (!previouslyAttached.contains(roleName)) {
                    attachedRoleNames.add(roleName);
                }
            }
        } catch (RuntimeException failure) {
            restoreOnUpdateFailure(failure, r, policyArn, createdPolicy, attachedRoleNames,
                    detachedObsoleteRoles, previousDefaultVersionId, createdVersionForRollback,
                    prunedVersionsForRollback);
            throw failure;
        }
    }

    /**
     * Undoes whatever this update attempt already did to the managed policy before it failed:
     * shared by the attach loop and the obsolete-role detach loop, since a detach failure can
     * escape before the attach loop even runs and must still restore the version and attachment
     * state already changed in this pass.
     */
    private void restoreOnUpdateFailure(RuntimeException failure, StackResource r, String policyArn,
                                        boolean createdPolicy, Set<String> attachedRoleNames,
                                        List<String> detachedObsoleteRoles, String previousDefaultVersionId,
                                        PolicyVersion createdVersionForRollback,
                                        List<PolicyVersion> prunedVersionsForRollback) {
        List<String> rollbackRoles = new ArrayList<>(attachedRoleNames);
        Collections.reverse(rollbackRoles);
        boolean cleanupSucceeded = true;
        for (String roleName : rollbackRoles) {
            String cleanupDescription = "detach policy " + policyArn + " from role " + roleName;
            if (!CfnRollback.attemptIamCleanup(failure, cleanupDescription,
                    () -> iamService.detachRolePolicy(roleName, policyArn))) {
                cleanupSucceeded = false;
            }
        }
        if (createdPolicy
                && !CfnRollback.attemptIamCleanup(failure, "delete policy " + policyArn,
                        () -> iamService.deletePolicy(policyArn))) {
            cleanupSucceeded = false;
        }
        // An adopted update that fails here already replaced the default version and/or detached
        // now-obsolete roles before this attach loop ran; undo both so the failed update does not
        // leave the policy half-migrated under UPDATE_ROLLBACK_COMPLETE.
        List<String> reattachRoles = new ArrayList<>(detachedObsoleteRoles);
        Collections.reverse(reattachRoles);
        for (String roleName : reattachRoles) {
            String cleanupDescription = "reattach policy " + policyArn + " to role " + roleName;
            if (!CfnRollback.attemptIamCleanup(failure, cleanupDescription,
                    () -> iamService.attachRolePolicy(roleName, policyArn))) {
                cleanupSucceeded = false;
            }
        }
        if (previousDefaultVersionId != null) {
            String restoredVersionId = previousDefaultVersionId;
            String restoreDescription = "restore default policy version " + restoredVersionId + " on " + policyArn;
            if (!CfnRollback.attemptIamCleanup(failure, restoreDescription,
                    () -> iamService.setDefaultPolicyVersion(policyArn, restoredVersionId))) {
                cleanupSucceeded = false;
            }
            if (createdVersionForRollback != null) {
                String strayVersionId = createdVersionForRollback.getVersionId();
                String pruneDescription = "delete stray policy version " + strayVersionId + " on " + policyArn;
                if (!CfnRollback.attemptIamCleanup(failure, pruneDescription,
                        () -> iamService.deletePolicyVersion(policyArn, strayVersionId))) {
                    cleanupSucceeded = false;
                }
            }
            // Versions pruned to stay under IAM's 5-version cap before publishing this attempt's new
            // default are gone for good under their original version id, but the document itself
            // must not be: restoring only the default and deleting the stray version above frees
            // exactly the slot(s) needed to recreate their content now, so a "successful" rollback
            // does not quietly destroy policy history that predates this update.
            for (PolicyVersion prunedVersion : prunedVersionsForRollback) {
                String document = prunedVersion.getDocument();
                String restoreContentDescription = "restore pruned policy version content on " + policyArn;
                if (!CfnRollback.attemptIamCleanup(failure, restoreContentDescription,
                        () -> iamService.createPolicyVersion(policyArn, document, false))) {
                    cleanupSucceeded = false;
                }
            }
        }
        if (cleanupSucceeded && createdPolicy) {
            r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        }
        if (!cleanupSucceeded) {
            // A compensating call above failed (added as a suppressed exception on failure): the
            // policy's version/attachments were only partially restored. Surface that so the stack
            // reports UPDATE_ROLLBACK_FAILED instead of assuming this resource is fully restored.
            String reason = failure.getMessage() != null
                    ? failure.getMessage()
                    : failure.getClass().getSimpleName();
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        IamManagedPolicyDeletes.detachRolesAndDeletePolicy(iamService, resource);
    }
}
