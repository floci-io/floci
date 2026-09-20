package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * CloudFormation provisioning for {@code AWS::IAM::Policy}, which in AWS is an <em>inline</em>
 * policy embedded in the named roles, users and groups (PutRolePolicy / PutUserPolicy /
 * PutGroupPolicy), not a standalone managed policy. {@code Ref} returns the policy name and the
 * resource exposes no ARN. Because an inline policy name is scoped to its owning principal, the
 * name is stable across updates unless the template changes it, and a failed update rolls back the
 * partially applied puts, recording anything it could not undo for the next provision to drain.
 *
 * <p>A physical id that is a managed policy ARN is a pre-inline upgrade: those stacks persisted the
 * policy as a customer-managed policy, so the legacy representation is migrated (detached and
 * deleted) here and removed on delete.
 */
@ApplicationScoped
public class IamPolicyCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IamPolicyCfnProvisioner.class);

    private static final String TYPE = "AWS::IAM::Policy";
    private static final String INLINE_CLEANUP_POLICY_NAME_ATTR = "__FlociInlineCleanupPolicyName";
    private static final String INLINE_CLEANUP_ROLE_TARGETS_ATTR = "__FlociInlineCleanupRoleTargets";
    private static final String INLINE_CLEANUP_USER_TARGETS_ATTR = "__FlociInlineCleanupUserTargets";
    private static final String INLINE_CLEANUP_GROUP_TARGETS_ATTR = "__FlociInlineCleanupGroupTargets";

    private final IamService iamService;

    @Inject
    public IamPolicyCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String previousPolicyName = r.getPhysicalId();
        String previousRoleTargets = r.getAttributes().get("InlineRoleTargets");
        String previousUserTargets = r.getAttributes().get("InlineUserTargets");
        String previousGroupTargets = r.getAttributes().get("InlineGroupTargets");
        boolean legacyManagedPolicy = isIamManagedPolicyArn(previousPolicyName);
        String policyName = ctx.resolveOptional(props, "PolicyName");
        if (policyName == null || policyName.isBlank()) {
            policyName = previousPolicyName != null && !previousPolicyName.isBlank() && !legacyManagedPolicy
                    ? previousPolicyName
                    : ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }
        String document = ctx.resolvePolicyDocument(props);

        final String name = policyName;
        final String doc = document;
        List<String> roleTargets = new ArrayList<>();
        List<String> userTargets = new ArrayList<>();
        List<String> groupTargets = new ArrayList<>();
        try {
            cleanupPendingInlinePolicies(r);
            putInlinePolicy(props, "Roles", engine, roleTargets,
                    principal -> iamService.putRolePolicy(principal, name, doc));
            putInlinePolicy(props, "Users", engine, userTargets,
                    principal -> iamService.putUserPolicy(principal, name, doc));
            putInlinePolicy(props, "Groups", engine, groupTargets,
                    principal -> iamService.putGroupPolicy(principal, name, doc));

            if (legacyManagedPolicy) {
                migrateLegacyManagedPolicy(r);
            } else {
                deleteRemovedInlinePolicies(previousRoleTargets, roleTargets,
                        previousPolicyName, policyName,
                        principal -> iamService.deleteRolePolicy(principal, previousPolicyName));
                deleteRemovedInlinePolicies(previousUserTargets, userTargets,
                        previousPolicyName, policyName,
                        principal -> iamService.deleteUserPolicy(principal, previousPolicyName));
                deleteRemovedInlinePolicies(previousGroupTargets, groupTargets,
                        previousPolicyName, policyName,
                        principal -> iamService.deleteGroupPolicy(principal, previousPolicyName));
            }
        } catch (RuntimeException failure) {
            if (previousPolicyName == null) {
                r.setPhysicalId(policyName);
                recordInlinePolicyTargets(r, roleTargets, userTargets, groupTargets);
            } else {
                rollbackInlinePolicyUpdate(r, failure, previousPolicyName, policyName,
                        previousRoleTargets, previousUserTargets, previousGroupTargets,
                        roleTargets, userTargets, groupTargets);
            }
            throw failure;
        }

        r.setPhysicalId(policyName);
        r.getAttributes().remove("Arn");
        recordInlinePolicyTargets(r, roleTargets, userTargets, groupTargets);
    }

    @Override
    public void delete(StackResource resource, String region) {
        cleanupPendingInlinePolicies(resource);
        if (isIamManagedPolicyArn(resource.getPhysicalId())) {
            // Legacy representation from before AWS::IAM::Policy was modelled as inline: delete the
            // customer-managed policy it was persisted as.
            deleteLegacyManagedPolicy(resource);
            return;
        }
        String policyName = resource.getPhysicalId();
        detachInline(resource.getAttributes().get("InlineRoleTargets"),
                name -> iamService.deleteRolePolicy(name, policyName));
        detachInline(resource.getAttributes().get("InlineUserTargets"),
                name -> iamService.deleteUserPolicy(name, policyName));
        detachInline(resource.getAttributes().get("InlineGroupTargets"),
                name -> iamService.deleteGroupPolicy(name, policyName));
    }

    private void putInlinePolicy(JsonNode props, String propName, CloudFormationTemplateEngine engine,
                                 List<String> successfulTargets, Consumer<String> op) {
        if (props == null || !props.has(propName)) {
            return;
        }
        for (JsonNode entry : props.get(propName)) {
            String name = engine.resolve(entry);
            if (name != null && !name.isBlank()) {
                op.accept(name);
                successfulTargets.add(name);
            }
        }
    }

    private void recordInlinePolicyTargets(StackResource resource, List<String> roleTargets,
                                           List<String> userTargets, List<String> groupTargets) {
        // Newlines are unambiguous because IAM principal names allow commas but never newlines.
        resource.getAttributes().put("InlineRoleTargets", String.join("\n", roleTargets));
        resource.getAttributes().put("InlineUserTargets", String.join("\n", userTargets));
        resource.getAttributes().put("InlineGroupTargets", String.join("\n", groupTargets));
        if (!roleTargets.isEmpty() || !userTargets.isEmpty() || !groupTargets.isEmpty()) {
            resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        }
    }

    private void rollbackInlinePolicyUpdate(StackResource resource, RuntimeException failure,
                                            String previousPolicyName, String currentPolicyName,
                                            String previousRoleTargets, String previousUserTargets,
                                            String previousGroupTargets, List<String> appliedRoleTargets,
                                            List<String> appliedUserTargets, List<String> appliedGroupTargets) {
        List<String> pendingRoles = rollbackAppliedInlinePolicies(
                failure, previousRoleTargets, appliedRoleTargets, previousPolicyName, currentPolicyName,
                principal -> iamService.deleteRolePolicy(principal, currentPolicyName));
        List<String> pendingUsers = rollbackAppliedInlinePolicies(
                failure, previousUserTargets, appliedUserTargets, previousPolicyName, currentPolicyName,
                principal -> iamService.deleteUserPolicy(principal, currentPolicyName));
        List<String> pendingGroups = rollbackAppliedInlinePolicies(
                failure, previousGroupTargets, appliedGroupTargets, previousPolicyName, currentPolicyName,
                principal -> iamService.deleteGroupPolicy(principal, currentPolicyName));
        recordPendingInlineCleanup(resource, currentPolicyName, pendingRoles, pendingUsers, pendingGroups);
        resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
    }

    private List<String> rollbackAppliedInlinePolicies(RuntimeException failure, String previousTargets,
                                                       List<String> appliedTargets, String previousPolicyName,
                                                       String currentPolicyName, Consumer<String> cleanup) {
        Set<String> previous = inlineTargetSet(previousTargets);
        List<String> rollbackTargets = new ArrayList<>();
        for (String target : new LinkedHashSet<>(appliedTargets)) {
            if (!previousPolicyName.equals(currentPolicyName) || !previous.contains(target)) {
                rollbackTargets.add(target);
            }
        }
        Collections.reverse(rollbackTargets);

        List<String> pendingTargets = new ArrayList<>();
        for (String target : rollbackTargets) {
            String description = "delete inline policy " + currentPolicyName + " from " + target;
            if (!CfnRollback.attemptIamCleanup(failure, description, () -> detachInline(target, cleanup))) {
                pendingTargets.add(target);
            }
        }
        Collections.reverse(pendingTargets);
        return pendingTargets;
    }

    private void recordPendingInlineCleanup(StackResource resource, String policyName,
                                            List<String> roleTargets, List<String> userTargets,
                                            List<String> groupTargets) {
        if (roleTargets.isEmpty() && userTargets.isEmpty() && groupTargets.isEmpty()) {
            return;
        }
        resource.getAttributes().put(INLINE_CLEANUP_POLICY_NAME_ATTR, policyName);
        resource.getAttributes().put(INLINE_CLEANUP_ROLE_TARGETS_ATTR, String.join("\n", roleTargets));
        resource.getAttributes().put(INLINE_CLEANUP_USER_TARGETS_ATTR, String.join("\n", userTargets));
        resource.getAttributes().put(INLINE_CLEANUP_GROUP_TARGETS_ATTR, String.join("\n", groupTargets));
    }

    private void cleanupPendingInlinePolicies(StackResource resource) {
        String policyName = resource.getAttributes().get(INLINE_CLEANUP_POLICY_NAME_ATTR);
        if (policyName == null || policyName.isBlank()) {
            return;
        }
        detachInline(resource.getAttributes().get(INLINE_CLEANUP_ROLE_TARGETS_ATTR),
                principal -> iamService.deleteRolePolicy(principal, policyName));
        detachInline(resource.getAttributes().get(INLINE_CLEANUP_USER_TARGETS_ATTR),
                principal -> iamService.deleteUserPolicy(principal, policyName));
        detachInline(resource.getAttributes().get(INLINE_CLEANUP_GROUP_TARGETS_ATTR),
                principal -> iamService.deleteGroupPolicy(principal, policyName));
        resource.getAttributes().remove(INLINE_CLEANUP_POLICY_NAME_ATTR);
        resource.getAttributes().remove(INLINE_CLEANUP_ROLE_TARGETS_ATTR);
        resource.getAttributes().remove(INLINE_CLEANUP_USER_TARGETS_ATTR);
        resource.getAttributes().remove(INLINE_CLEANUP_GROUP_TARGETS_ATTR);
    }

    private void deleteRemovedInlinePolicies(String previousTargets, List<String> currentTargets,
                                             String previousPolicyName, String currentPolicyName,
                                             Consumer<String> op) {
        if (previousPolicyName == null) {
            return;
        }
        Set<String> retainedTargets = new HashSet<>(currentTargets);
        detachInline(previousTargets, name -> {
            if (!previousPolicyName.equals(currentPolicyName) || !retainedTargets.contains(name)) {
                op.accept(name);
            }
        });
    }

    private Set<String> inlineTargetSet(String targets) {
        if (targets == null || targets.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(targets.split("\n")));
    }

    private void detachInline(String targets, Consumer<String> op) {
        if (targets == null || targets.isBlank()) {
            return;
        }
        for (String name : targets.split("\n")) {
            if (!name.isBlank()) {
                try {
                    op.accept(name);
                } catch (AwsException e) {
                    // The principal may already be gone (deleted earlier in the same teardown),
                    // but permission and service failures must keep the stack in DELETE_FAILED.
                    if (!"NoSuchEntity".equals(e.getErrorCode())) {
                        throw e;
                    }
                    LOG.debugv("Inline policy principal already gone, treating as detached: {0}", name);
                }
            }
        }
    }

    private boolean isIamManagedPolicyArn(String physicalId) {
        return physicalId != null
                && physicalId.startsWith("arn:")
                && physicalId.contains(":iam::")
                && physicalId.contains(":policy/");
    }

    // ── Legacy managed-policy representation (pre-inline upgrade path) ───────────

    private void migrateLegacyManagedPolicy(StackResource resource) {
        String policyArn = resource.getPhysicalId();
        List<String> detachedRoles = new ArrayList<>();
        try {
            for (String roleName : managedPolicyRoleTargets(resource)) {
                try {
                    iamService.detachRolePolicy(roleName, policyArn);
                    detachedRoles.add(roleName);
                } catch (AwsException e) {
                    if (!"NoSuchEntity".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
            }
            deleteManagedPolicySafe(policyArn);
        } catch (RuntimeException failure) {
            Collections.reverse(detachedRoles);
            for (String roleName : detachedRoles) {
                CfnRollback.attemptIamCleanup(failure,
                        "reattach legacy policy " + policyArn + " to role " + roleName,
                        () -> iamService.attachRolePolicy(roleName, policyArn));
            }
            throw failure;
        }
    }

    private void deleteLegacyManagedPolicy(StackResource resource) {
        String policyArn = resource.getPhysicalId();
        for (String roleName : managedPolicyRoleTargets(resource)) {
            try {
                iamService.detachRolePolicy(roleName, policyArn);
            } catch (AwsException e) {
                // Deletion is idempotent: the role or attachment can already be absent on a retry,
                // but permission/service failures must keep the stack in DELETE_FAILED.
                if (!"NoSuchEntity".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }
        deleteManagedPolicySafe(policyArn);
    }

    private void deleteManagedPolicySafe(String policyArn) {
        try {
            iamService.deletePolicy(policyArn);
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("IAM policy already gone, treating as deleted: {0}", policyArn);
        }
    }

    private List<String> managedPolicyRoleTargets(StackResource resource) {
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
        return Arrays.stream(targets.split("\n"))
                .filter(roleName -> !roleName.isBlank())
                .toList();
    }
}
