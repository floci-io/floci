package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamGroup;
import io.floci.aws.services.iam.model.IamPolicy;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.IamUser;
import io.floci.aws.services.iam.model.PolicyVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CloudFormation provisioning for {@code AWS::IAM::ManagedPolicy}, a standalone customer-managed
 * policy with an ARN that must be detached from its principals before deletion. {@code Ref} and
 * {@code Fn::GetAtt PolicyArn} return the policy ARN. A managed policy name is account-global, so
 * the physical name is honoured verbatim from {@code ManagedPolicyName} when set.
 *
 * <p>On {@code UpdateStack} with an unchanged name the policy this stack provisioned already
 * exists, so {@code PolicyDocument} is applied as a new default version in place (pruning the
 * oldest versions to stay under IAM's five-version cap) and principals no longer listed are detached,
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
        List<String> userNames = ctx.resolveStringList(props, "Users");
        List<String> groupNames = ctx.resolveStringList(props, "Groups");
        String existingArn = r.getPhysicalId();

        // Check every declared target before creating a policy or publishing a new version.
        List<IamRole> declaredRoles = roleNames.stream().map(iamService::getRole).toList();
        List<IamUser> declaredUsers = userNames.stream().map(iamService::getUser).toList();
        List<IamGroup> declaredGroups = groupNames.stream().map(iamService::getGroup).toList();
        String roleTargetIds = targetIds(declaredRoles, IamRole::getRoleId);
        String userTargetIds = targetIds(declaredUsers, IamUser::getUserId);
        String groupTargetIds = targetIds(declaredGroups, IamGroup::getGroupId);

        IamPolicy policy;
        IamService.PolicyEntities priorEntities = null;
        boolean createdPolicy = false;
        String previousDefaultVersionId = null;
        PolicyVersion createdVersionForRollback = null;
        List<PolicyVersion> prunedVersionsForRollback = new ArrayList<>();
        AttachmentChanges changes = new AttachmentChanges();
        String previousRoleTargets = r.getAttributes().get("ManagedPolicyRoleTargets");
        String previousUserTargets = r.getAttributes().get("ManagedPolicyUserTargets");
        String previousGroupTargets = r.getAttributes().get("ManagedPolicyGroupTargets");
        String previousRoleTargetIds = r.getAttributes().get("ManagedPolicyRoleTargetIds");
        String previousUserTargetIds = r.getAttributes().get("ManagedPolicyUserTargetIds");
        String previousGroupTargetIds = r.getAttributes().get("ManagedPolicyGroupTargetIds");
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
            priorEntities = iamService.listEntitiesForPolicy(existingArn);
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
            // Targets this stack attached on the previous pass but no longer listed in the
            // template are detached, matching CloudFormation's update semantics.
            for (String previousRole : resolvePreviousTargets(previousRoleTargets, previousRoleTargetIds,
                    priorEntities.roles(), IamRole::getRoleId, IamRole::getRoleName)) {
                if (!roleNames.contains(previousRole)) {
                    try {
                        iamService.detachRolePolicy(previousRole, existingArn);
                        changes.detachedRoles.add(previousRole);
                    } catch (AwsException detachFailure) {
                        if (!"NoSuchEntity".equals(detachFailure.getErrorCode())) {
                            restoreOnUpdateFailure(detachFailure, r, existingArn,
                                    false, changes,
                                    previousRoleTargets, previousUserTargets, previousGroupTargets,
                                    previousRoleTargetIds, previousUserTargetIds, previousGroupTargetIds,
                                    previousDefaultVersionId, createdVersionForRollback,
                                    prunedVersionsForRollback);
                            throw detachFailure;
                        }
                    }
                }
            }
            for (String previousUser : resolvePreviousTargetsWithIds(previousUserTargets, previousUserTargetIds,
                    priorEntities.users(), IamUser::getUserId, IamUser::getUserName)) {
                    if (!userNames.contains(previousUser)) {
                        try {
                            iamService.detachUserPolicy(previousUser, existingArn);
                            changes.detachedUsers.add(previousUser);
                        } catch (AwsException detachFailure) {
                            if (!"NoSuchEntity".equals(detachFailure.getErrorCode())) {
                                restoreOnUpdateFailure(detachFailure, r, existingArn,
                                        false, changes,
                                        previousRoleTargets, previousUserTargets, previousGroupTargets,
                                        previousRoleTargetIds, previousUserTargetIds, previousGroupTargetIds,
                                        previousDefaultVersionId, createdVersionForRollback,
                                        prunedVersionsForRollback);
                                throw detachFailure;
                            }
                        }
                    }
                }
            for (String previousGroup : resolvePreviousTargetsWithIds(previousGroupTargets, previousGroupTargetIds,
                    priorEntities.groups(), IamGroup::getGroupId, IamGroup::getGroupName)) {
                    if (!groupNames.contains(previousGroup)) {
                        try {
                            iamService.detachGroupPolicy(previousGroup, existingArn);
                            changes.detachedGroups.add(previousGroup);
                        } catch (AwsException detachFailure) {
                            if (!"NoSuchEntity".equals(detachFailure.getErrorCode())) {
                                restoreOnUpdateFailure(detachFailure, r, existingArn,
                                        false, changes,
                                        previousRoleTargets, previousUserTargets, previousGroupTargets,
                                        previousRoleTargetIds, previousUserTargetIds, previousGroupTargetIds,
                                        previousDefaultVersionId, createdVersionForRollback,
                                        prunedVersionsForRollback);
                                throw detachFailure;
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
        r.getAttributes().put("ManagedPolicyUserTargets", String.join("\n", userNames));
        r.getAttributes().put("ManagedPolicyGroupTargets", String.join("\n", groupNames));
        putTargetIds(r, "ManagedPolicyRoleTargetIds", roleTargetIds);
        putTargetIds(r, "ManagedPolicyUserTargetIds", userTargetIds);
        putTargetIds(r, "ManagedPolicyGroupTargetIds", groupTargetIds);

        String policyArn = policy.getArn();
        // On adopt, attachments from the previous pass are not this attempt's to undo.
        Set<String> previouslyAttachedRoles = priorEntities == null
                ? Set.of() : priorEntities.roles().stream()
                        .map(IamRole::getRoleName)
                        .collect(Collectors.toSet());
        Set<String> previouslyAttachedUsers = priorEntities == null
                ? Set.of() : priorEntities.users().stream()
                        .map(IamUser::getUserName)
                        .collect(Collectors.toSet());
        Set<String> previouslyAttachedGroups = priorEntities == null
                ? Set.of() : priorEntities.groups().stream()
                        .map(IamGroup::getGroupName)
                        .collect(Collectors.toSet());
        try {
            for (String roleName : roleNames) {
                iamService.attachRolePolicy(roleName, policyArn);
                if (!previouslyAttachedRoles.contains(roleName)) {
                    changes.attachedRoles.add(roleName);
                }
            }
            for (String userName : userNames) {
                iamService.attachUserPolicy(userName, policyArn);
                if (!previouslyAttachedUsers.contains(userName)) {
                    changes.attachedUsers.add(userName);
                }
            }
            for (String groupName : groupNames) {
                iamService.attachGroupPolicy(groupName, policyArn);
                if (!previouslyAttachedGroups.contains(groupName)) {
                    changes.attachedGroups.add(groupName);
                }
            }
        } catch (RuntimeException failure) {
            restoreOnUpdateFailure(failure, r, policyArn, createdPolicy, changes,
                    previousRoleTargets, previousUserTargets, previousGroupTargets,
                    previousRoleTargetIds, previousUserTargetIds, previousGroupTargetIds,
                    previousDefaultVersionId, createdVersionForRollback,
                    prunedVersionsForRollback);
            throw failure;
        }
    }

    /**
     * Undoes whatever this update attempt already did to the managed policy before it failed:
     * shared by the attach loop and the obsolete-target detach loops, since a detach failure can
     * escape before the attach loop even runs and must still restore the version and attachment
     * state already changed in this pass.
     */
    private void restoreOnUpdateFailure(RuntimeException failure, StackResource r, String policyArn,
                                        boolean createdPolicy, AttachmentChanges changes,
                                        String previousRoleTargets, String previousUserTargets,
                                         String previousGroupTargets,
                                         String previousRoleTargetIds, String previousUserTargetIds,
                                         String previousGroupTargetIds, String previousDefaultVersionId,
                                        PolicyVersion createdVersionForRollback,
                                        List<PolicyVersion> prunedVersionsForRollback) {
        boolean cleanupSucceeded = true;
        cleanupSucceeded &= attemptPrincipalCleanup(failure, policyArn, "group", changes.attachedGroups,
                name -> iamService.detachGroupPolicy(name, policyArn), "detach");
        cleanupSucceeded &= attemptPrincipalCleanup(failure, policyArn, "user", changes.attachedUsers,
                name -> iamService.detachUserPolicy(name, policyArn), "detach");
        cleanupSucceeded &= attemptPrincipalCleanup(failure, policyArn, "role", changes.attachedRoles,
                name -> iamService.detachRolePolicy(name, policyArn), "detach");
        if (createdPolicy
                && !CfnRollback.attemptIamCleanup(failure, "delete policy " + policyArn,
                        () -> iamService.deletePolicy(policyArn))) {
            cleanupSucceeded = false;
        }
        // An adopted update may already have detached targets and replaced the default version.
        cleanupSucceeded &= attemptPrincipalCleanup(failure, policyArn, "group", changes.detachedGroups,
                name -> iamService.attachGroupPolicy(name, policyArn), "reattach");
        cleanupSucceeded &= attemptPrincipalCleanup(failure, policyArn, "user", changes.detachedUsers,
                name -> iamService.attachUserPolicy(name, policyArn), "reattach");
        cleanupSucceeded &= attemptPrincipalCleanup(failure, policyArn, "role", changes.detachedRoles,
                name -> iamService.attachRolePolicy(name, policyArn), "reattach");
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
                        () -> iamService.restorePolicyVersion(policyArn, document, false))) {
                    cleanupSucceeded = false;
                }
            }
        }
        if (cleanupSucceeded && createdPolicy) {
            r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        } else if (cleanupSucceeded) {
            restoreTargetAttribute(r, "ManagedPolicyRoleTargets", previousRoleTargets);
            restoreTargetAttribute(r, "ManagedPolicyUserTargets", previousUserTargets);
            restoreTargetAttribute(r, "ManagedPolicyGroupTargets", previousGroupTargets);
            restoreTargetAttribute(r, "ManagedPolicyRoleTargetIds", previousRoleTargetIds);
            restoreTargetAttribute(r, "ManagedPolicyUserTargetIds", previousUserTargetIds);
            restoreTargetAttribute(r, "ManagedPolicyGroupTargetIds", previousGroupTargetIds);
            if (previousDefaultVersionId != null) {
                r.getAttributes().put("DefaultVersionId", previousDefaultVersionId);
            }
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

    private static void restoreTargetAttribute(StackResource resource, String key, String previousValue) {
        if (previousValue == null) {
            resource.getAttributes().remove(key);
        } else {
            resource.getAttributes().put(key, previousValue);
        }
    }

    private static <T> String targetIds(List<T> entities, Function<T, String> idGetter) {
        if (entities.isEmpty()
                || entities.stream().anyMatch(entity -> entity == null || idGetter.apply(entity) == null)) {
            return null;
        }
        return entities.stream().map(idGetter).collect(Collectors.joining("\n"));
    }

    private static <T> List<String> resolvePreviousTargets(String names, String ids, List<T> attached,
                                                            Function<T, String> idGetter,
                                                            Function<T, String> nameGetter) {
        List<String> targetNames = splitTargets(names);
        if (targetNames.isEmpty()) {
            return List.of();
        }
        if (ids == null) {
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

    private static <T> List<String> resolvePreviousTargetsWithIds(String names, String ids, List<T> attached,
                                                                   Function<T, String> idGetter,
                                                                   Function<T, String> nameGetter) {
        if (names == null) {
            return List.of();
        }
        return ids == null || ids.isBlank()
                ? splitTargets(names) : resolvePreviousTargets(names, ids, attached, idGetter, nameGetter);
    }

    private static List<String> splitTargets(String targets) {
        return targets == null ? List.of() : targets.lines().filter(target -> !target.isBlank()).toList();
    }

    private static void putTargetIds(StackResource resource, String key, String ids) {
        if (ids == null) {
            resource.getAttributes().remove(key);
        } else {
            resource.getAttributes().put(key, ids);
        }
    }

    private static boolean attemptPrincipalCleanup(RuntimeException failure, String policyArn, String type,
                                                   Collection<String> names, Consumer<String> action,
                                                   String operation) {
        boolean succeeded = true;
        List<String> reversed = new ArrayList<>(names);
        Collections.reverse(reversed);
        for (String name : reversed) {
            String preposition = "reattach".equals(operation) ? " to " : " from ";
            String description = operation + " policy " + policyArn + preposition + type + " " + name;
            if (!CfnRollback.attemptIamCleanup(failure, description, () -> action.accept(name))) {
                succeeded = false;
            }
        }
        return succeeded;
    }

    private static final class AttachmentChanges {
        private final Set<String> attachedRoles = new LinkedHashSet<>();
        private final Set<String> attachedUsers = new LinkedHashSet<>();
        private final Set<String> attachedGroups = new LinkedHashSet<>();
        private final List<String> detachedRoles = new ArrayList<>();
        private final List<String> detachedUsers = new ArrayList<>();
        private final List<String> detachedGroups = new ArrayList<>();
    }

    @Override
    public void delete(StackResource resource, String region) {
        IamManagedPolicyDeletes.detachAndDeletePolicy(iamService, resource);
    }
}
