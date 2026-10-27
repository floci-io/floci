package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.InlinePolicyChange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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
            // PolicyName is required for AWS::IAM::Policy (registry required set, and PutRolePolicy
            // demands it). A genuine first create has nothing to fall back to, so reject rather than
            // invent a name. An update keeps its prior: a normal one reuses the recorded name, and a
            // legacy managed-policy migration generates a fresh inline name because the prior id is
            // an ARN, not a usable policy name.
            if (previousPolicyName == null || previousPolicyName.isBlank()) {
                throw new AwsException("ValidationError",
                        "AWS::IAM::Policy " + r.getLogicalId() + " is missing the required PolicyName property", 400);
            }
            policyName = legacyManagedPolicy
                    ? ctx.generatePhysicalName(r.getLogicalId(), 128, false)
                    : previousPolicyName;
        }
        // An inline policy must attach to at least one principal; AWS rejects a Policy that names
        // none of Roles, Users or Groups.
        if (!hasPrincipals(props, "Roles") && !hasPrincipals(props, "Users") && !hasPrincipals(props, "Groups")) {
            throw new AwsException("ValidationError",
                    "AWS::IAM::Policy " + r.getLogicalId()
                            + " must specify at least one of Roles, Users or Groups", 400);
        }
        String document = ctx.resolvePolicyDocument(props);

        final String name = policyName;
        final String doc = document;
        List<String> roleTargets = new ArrayList<>();
        List<String> userTargets = new ArrayList<>();
        List<String> groupTargets = new ArrayList<>();
        List<InlinePolicyUndo> writtenPolicies = new ArrayList<>();
        List<InlinePolicyUndo> removedPolicies = new ArrayList<>();
        try {
            List<String> desiredRoles = resolveInlineTargets(props, "Roles", engine);
            List<String> desiredUsers = resolveInlineTargets(props, "Users", engine);
            List<String> desiredGroups = resolveInlineTargets(props, "Groups", engine);
            cleanupPendingInlinePolicies(r);
            if (!legacyManagedPolicy && previousPolicyName != null && !previousPolicyName.equals(name)) {
                removeRenamedInlinePolicies(previousRoleTargets, desiredRoles,
                        policyAccess("Role", previousPolicyName), removedPolicies);
                removeRenamedInlinePolicies(previousUserTargets, desiredUsers,
                        policyAccess("User", previousPolicyName), removedPolicies);
                removeRenamedInlinePolicies(previousGroupTargets, desiredGroups,
                        policyAccess("Group", previousPolicyName), removedPolicies);
            }
            putInlinePolicy(desiredRoles, roleTargets, doc,
                    policyAccess("Role", name), previousPolicyName != null, writtenPolicies);
            putInlinePolicy(desiredUsers, userTargets, doc,
                    policyAccess("User", name), previousPolicyName != null, writtenPolicies);
            putInlinePolicy(desiredGroups, groupTargets, doc,
                    policyAccess("Group", name), previousPolicyName != null, writtenPolicies);

            if (legacyManagedPolicy) {
                migrateLegacyManagedPolicy(r);
            } else {
                deleteRemovedInlinePolicies(previousRoleTargets, roleTargets,
                        previousPolicyName, policyAccess("Role", previousPolicyName), removedPolicies);
                deleteRemovedInlinePolicies(previousUserTargets, userTargets,
                        previousPolicyName, policyAccess("User", previousPolicyName), removedPolicies);
                deleteRemovedInlinePolicies(previousGroupTargets, groupTargets,
                        previousPolicyName, policyAccess("Group", previousPolicyName), removedPolicies);
            }
        } catch (RuntimeException failure) {
            if (previousPolicyName == null) {
                r.setPhysicalId(policyName);
                recordInlinePolicyTargets(r, roleTargets, userTargets, groupTargets);
            } else {
                rollbackInlinePolicyUpdate(r, failure, policyName, writtenPolicies, removedPolicies);
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

    private static boolean hasPrincipals(JsonNode props, String propName) {
        JsonNode node = props == null ? null : props.get(propName);
        return node != null && node.isArray() && !node.isEmpty();
    }

    private record InlinePolicyAccess(String kind, String name, BiConsumer<String, String> write,
                                      BiFunction<String, String, InlinePolicyChange> update,
                                      Function<String, InlinePolicyChange> remove) {}

    private record InlinePolicyUndo(InlinePolicyAccess access, String principal, InlinePolicyChange change) {
        void restore() {
            change.rollback().run();
        }
    }

    private InlinePolicyAccess policyAccess(String kind, String name) {
        return switch (kind) {
            case "Role" -> new InlinePolicyAccess(kind, name,
                    (principal, document) -> iamService.putRolePolicy(principal, name, document),
                    (principal, document) -> iamService.putRolePolicyWithRollback(principal, name, document),
                    principal -> iamService.deleteRolePolicyWithRollback(principal, name));
            case "User" -> new InlinePolicyAccess(kind, name,
                    (principal, document) -> iamService.putUserPolicy(principal, name, document),
                    (principal, document) -> iamService.putUserPolicyWithRollback(principal, name, document),
                    principal -> iamService.deleteUserPolicyWithRollback(principal, name));
            case "Group" -> new InlinePolicyAccess(kind, name,
                    (principal, document) -> iamService.putGroupPolicy(principal, name, document),
                    (principal, document) -> iamService.putGroupPolicyWithRollback(principal, name, document),
                    principal -> iamService.deleteGroupPolicyWithRollback(principal, name));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private List<String> resolveInlineTargets(JsonNode props, String propName, CloudFormationTemplateEngine engine) {
        List<String> targets = new ArrayList<>();
        if (props != null && props.has(propName)) {
            for (JsonNode entry : props.get(propName)) {
                String name = engine.resolve(entry);
                if (name != null && !name.isBlank()) {
                    targets.add(name);
                }
            }
        }
        return targets;
    }

    private void putInlinePolicy(List<String> targets, List<String> successfulTargets,
                                 String document, InlinePolicyAccess access,
                                 boolean updating, List<InlinePolicyUndo> writtenPolicies) {
        for (String name : targets) {
            if (updating) {
                InlinePolicyChange change = access.update().apply(name, document);
                writtenPolicies.add(new InlinePolicyUndo(access, name, change));
            } else {
                access.write().accept(name, document);
            }
            successfulTargets.add(name);
        }
    }

    private void removeRenamedInlinePolicies(String previousTargets, List<String> desiredTargets,
                                             InlinePolicyAccess access, List<InlinePolicyUndo> removedPolicies) {
        Set<String> retainedTargets = new HashSet<>(desiredTargets);
        detachInline(previousTargets, name -> {
            if (retainedTargets.contains(name)) {
                InlinePolicyChange change = access.remove().apply(name);
                removedPolicies.add(new InlinePolicyUndo(access, name, change));
            }
        });
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
                                            String currentPolicyName, List<InlinePolicyUndo> writtenPolicies,
                                            List<InlinePolicyUndo> removedPolicies) {
        List<String> pendingRoles = new ArrayList<>();
        List<String> pendingUsers = new ArrayList<>();
        List<String> pendingGroups = new ArrayList<>();
        // Undo puts first so restored old names do not compete with this attempt's new names for quota.
        List<InlinePolicyUndo> rollback = new ArrayList<>(writtenPolicies.reversed());
        rollback.addAll(removedPolicies.reversed());
        boolean restored = true;
        for (InlinePolicyUndo undo : rollback) {
            String description = "restore inline policy " + undo.access().name() + " on " + undo.principal();
            if (!CfnRollback.attemptIamCleanup(failure, description, undo::restore)) {
                restored = false;
                Throwable[] cleanupFailures = failure.getSuppressed();
                Throwable cleanupFailure = cleanupFailures[cleanupFailures.length - 1];
                boolean ownershipChanged = cleanupFailure instanceof AwsException aws
                        && "ConcurrentModification".equals(aws.getErrorCode());
                if (!ownershipChanged && undo.change().previousDocument() == null
                        && currentPolicyName.equals(undo.access().name())) {
                    switch (undo.access().kind()) {
                        case "Role" -> pendingRoles.add(undo.principal());
                        case "User" -> pendingUsers.add(undo.principal());
                        case "Group" -> pendingGroups.add(undo.principal());
                        default -> throw new IllegalArgumentException(undo.access().kind());
                    }
                }
            }
        }
        recordPendingInlineCleanup(resource, currentPolicyName, pendingRoles, pendingUsers, pendingGroups);
        resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR);
        if (restored) {
            resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
        } else {
            resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                    "Could not restore inline policies after failed update: " + failure.getMessage());
        }
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
                                             String previousPolicyName,
                                             InlinePolicyAccess access, List<InlinePolicyUndo> removedPolicies) {
        if (previousPolicyName == null) {
            return;
        }
        Set<String> retainedTargets = new HashSet<>(currentTargets);
        detachInline(previousTargets, name -> {
            if (!retainedTargets.contains(name)) {
                InlinePolicyChange change = access.remove().apply(name);
                removedPolicies.add(new InlinePolicyUndo(access, name, change));
            }
        });
    }

    private static void detachInline(String targets, Consumer<String> op) {
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
        return AwsArnUtils.isArn(physicalId)
                && physicalId.contains(":iam::")
                && physicalId.contains(":policy/");
    }

    // ── Legacy managed-policy representation (pre-inline upgrade path) ───────────

    private void migrateLegacyManagedPolicy(StackResource resource) {
        String policyArn = resource.getPhysicalId();
        List<String> detachedRoles = new ArrayList<>();
        try {
            for (String roleName : IamManagedPolicyDeletes.roleTargets(iamService, resource)) {
                try {
                    iamService.detachRolePolicy(roleName, policyArn);
                    detachedRoles.add(roleName);
                } catch (AwsException e) {
                    if (!"NoSuchEntity".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
            }
            IamManagedPolicyDeletes.deletePolicyTolerating(iamService, policyArn);
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
        IamManagedPolicyDeletes.detachRolesAndDeletePolicy(iamService, resource);
    }
}
