package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::Role}, moved out of the
 * {@code CloudFormationResourceProvisioner} switch. The other IAM types (User, AccessKey, Policy,
 * ManagedPolicy, InstanceProfile) still live there and share {@link CfnRollback} with this class.
 */
@ApplicationScoped
public class IamRoleCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IamRoleCfnProvisioner.class);

    private static final String INLINE_NAMES_ATTR = "__FlociInlinePolicyNames";
    private static final String MANAGED_ARNS_ATTR = "__FlociManagedPolicyArns";

    private final IamService iamService;

    @Inject
    public IamRoleCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::IAM::Role");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String existingRoleName = r.getPhysicalId();
        String roleName = ctx.resolveOptional(props, "RoleName");
        if (roleName == null || roleName.isBlank()) {
            roleName = existingRoleName != null && !existingRoleName.isBlank()
                    ? existingRoleName
                    : ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }
        final String resolvedRoleName = roleName;
        if (existingRoleName != null && !existingRoleName.equals(resolvedRoleName)) {
            throw new AwsException("ValidationError",
                    "Updating RoleName requires resource replacement, which is not supported.", 400);
        }
        String assumeDoc = props != null && props.has("AssumeRolePolicyDocument")
                ? props.get("AssumeRolePolicyDocument").toString()
                : "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        String path = ctx.resolveOptional(props, "Path");
        if (path == null) {
            path = "/";
        }
        String description = ctx.resolveOptional(props, "Description");
        List<String> managedPolicyArns = ctx.resolveStringList(props, "ManagedPolicyArns");

        IamRole role;
        boolean createdRole = false;
        // Set only on the adoption path, to the role's trust policy (and verified RoleId) as they
        // were before this attempt touched them. Lets a later failure (e.g. bad ManagedPolicyArns)
        // restore the trust policy below, so a rolled-back update doesn't leave it changed despite
        // UPDATE_ROLLBACK_COMPLETE - and restore it onto the *same verified role*, not onto a
        // replacement that took the name in the meantime.
        String priorAssumeRolePolicyDocument = null;
        String priorAssumeRoleId = null;
        try {
            role = iamService.createRole(resolvedRoleName, path, assumeDoc, description, 3600, Map.of());
            createdRole = true;
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        } catch (AwsException e) {
            boolean stackAlreadyOwnsRole = existingRoleName != null
                    && existingRoleName.equals(resolvedRoleName);
            if (!stackAlreadyOwnsRole || !"EntityAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
            // Same-stack update/retry: both the physical name and immutable role ID must match.
            // A role deleted out of band and recreated under the same name belongs to its new owner.
            role = iamService.getRole(resolvedRoleName);
            String existingRoleId = r.getAttributes().get("RoleId");
            if (existingRoleId == null || existingRoleId.isBlank()
                    || !existingRoleId.equals(role.getRoleId())) {
                r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                throw e;
            }
            // createRole() only runs on first create; adopting an existing role on update must
            // still apply this template's current AssumeRolePolicyDocument, or a changed trust
            // policy is silently dropped while the stack still reports UPDATE_COMPLETE.
            //
            // Deliberately not re-fetching the role afterward: everything read from `role` below
            // (Arn, RoleId, AttachedPolicyArns) is unaffected by a trust-policy update, and a
            // failing re-fetch here would escape before the failure-cleanup block below can
            // restore the trust policy, leaving the new document active despite a rolled-back
            // update.
            // Pass existingRoleId through so IamService re-verifies identity atomically with the
            // write, closing the gap between the check above and this call: a role deleted and
            // recreated under the same name in between would otherwise silently receive an update
            // meant for the role this stack actually owns.
            priorAssumeRolePolicyDocument = role.getAssumeRolePolicyDocument();
            priorAssumeRoleId = existingRoleId;
            iamService.updateAssumeRolePolicy(resolvedRoleName, assumeDoc, existingRoleId);
        }

        r.setPhysicalId(resolvedRoleName);
        r.getAttributes().put("Arn", role.getArn());
        r.getAttributes().put("RoleId", role.getRoleId());

        // What the previous execution of this resource wrote, so an update can take out what the
        // template stopped declaring. Only these names are removed, which leaves inline policies and
        // attachments added out of band alone.
        Set<String> previousInlineNames = readTrackedSet(r, INLINE_NAMES_ATTR);
        Set<String> previousManagedArns = readTrackedSet(r, MANAGED_ARNS_ATTR);

        Set<String> originalPolicyArns = new HashSet<>(role.getAttachedPolicyArns());
        LinkedHashSet<String> attachedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> detachedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> inlineRemovedByThisAttempt = new LinkedHashSet<>();
        // What the inline policies looked like before this attempt, so a partial write can be put
        // back. On an update that adopts an existing role these are the values to restore; for a
        // name this attempt introduced there is no prior value and the policy is removed instead.
        Map<String, String> originalInlinePolicies = new HashMap<>(role.getInlinePolicies());
        LinkedHashSet<String> inlineWrittenByThisAttempt = new LinkedHashSet<>();
        final String documentToRestore = priorAssumeRolePolicyDocument;
        final String roleIdToRestore = priorAssumeRoleId;
        try {
            for (String policyArn : managedPolicyArns) {
                iamService.attachRolePolicy(resolvedRoleName, policyArn);
                if (!originalPolicyArns.contains(policyArn)) {
                    attachedByThisAttempt.add(policyArn);
                }
            }

            // Inline policies run inside the same protected block: a failure here used to leave
            // the created role, its managed attachments and any earlier inline writes behind,
            // because rollback only deletes resources that reached CREATE_COMPLETE.
            if (props != null && props.has("Policies")) {
                for (JsonNode policy : props.get("Policies")) {
                    String declaredName = ctx.resolveOptional(policy, "PolicyName");
                    if (declaredName == null || declaredName.isBlank()) {
                        // PolicyName is a required property of AWS::IAM::Role Policies. Generating
                        // one produced a fresh name on every execution, so an update accumulated
                        // copies of the same policy instead of replacing it.
                        throw new AwsException("ValidationError",
                                "An inline policy on role " + resolvedRoleName + " has no PolicyName.", 400);
                    }
                    final String policyName = declaredName;
                    JsonNode document = policy.get("PolicyDocument");
                    if (document == null || document.isNull()) {
                        // Skipping it and reporting CREATE_COMPLETE without the declared policy is
                        // the same class of bug as #1952 itself.
                        throw new AwsException("ValidationError",
                                "Inline policy '" + policyName + "' on role " + resolvedRoleName
                                + " has no PolicyDocument.", 400);
                    }
                    iamService.putRolePolicy(resolvedRoleName, policyName,
                            ctx.engine().resolveJsonAttribute(document));
                    inlineWrittenByThisAttempt.add(policyName);
                }
            }

            // Anything the previous execution wrote and this template no longer declares. Without
            // this the role keeps permissions the stack stopped declaring while the stack still
            // reports UPDATE_COMPLETE, which is the failure mode #1952 is about one level over.
            // A tracked policy that is already gone is skipped rather than deleted again. Both IAM
            // calls raise NoSuchEntity on an absent target, which would fail an update whose desired
            // end state, the policy not being on the role, already holds.
            for (String stale : previousInlineNames) {
                if (!inlineWrittenByThisAttempt.contains(stale)
                        && originalInlinePolicies.containsKey(stale)) {
                    iamService.deleteRolePolicy(resolvedRoleName, stale);
                    inlineRemovedByThisAttempt.add(stale);
                }
            }
            for (String stale : previousManagedArns) {
                if (!managedPolicyArns.contains(stale) && originalPolicyArns.contains(stale)) {
                    iamService.detachRolePolicy(resolvedRoleName, stale);
                    detachedByThisAttempt.add(stale);
                }
            }
        } catch (RuntimeException failure) {
            boolean cleanupSucceeded = true;

            // Reconciliation runs last, so unwinding it comes first. A policy this attempt took out
            // because the template stopped declaring it goes back with the document it had.
            for (String policyName : inlineRemovedByThisAttempt) {
                String prior = originalInlinePolicies.get(policyName);
                if (prior == null) {
                    continue;
                }
                if (!CfnRollback.attemptIamCleanup(failure,
                        "restore inline policy " + policyName + " on role " + resolvedRoleName,
                        () -> iamService.putRolePolicy(resolvedRoleName, policyName, prior))) {
                    cleanupSucceeded = false;
                }
            }
            for (String policyArn : detachedByThisAttempt) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "reattach policy " + policyArn + " to role " + resolvedRoleName,
                        () -> iamService.attachRolePolicy(resolvedRoleName, policyArn))) {
                    cleanupSucceeded = false;
                }
            }

            List<String> inlineRollback = new ArrayList<>(inlineWrittenByThisAttempt);
            Collections.reverse(inlineRollback);
            for (String policyName : inlineRollback) {
                String prior = originalInlinePolicies.get(policyName);
                String cleanupDescription = (prior == null ? "remove" : "restore")
                        + " inline policy " + policyName + " on role " + resolvedRoleName;
                if (!CfnRollback.attemptIamCleanup(failure, cleanupDescription, () -> {
                    if (prior == null) {
                        iamService.deleteRolePolicy(resolvedRoleName, policyName);
                    } else {
                        iamService.putRolePolicy(resolvedRoleName, policyName, prior);
                    }
                })) {
                    cleanupSucceeded = false;
                }
            }

            List<String> rollbackArns = new ArrayList<>(attachedByThisAttempt);
            Collections.reverse(rollbackArns);
            for (String policyArn : rollbackArns) {
                String cleanupDescription = "detach policy " + policyArn + " from role " + resolvedRoleName;
                if (!CfnRollback.attemptIamCleanup(failure, cleanupDescription,
                        () -> iamService.detachRolePolicy(resolvedRoleName, policyArn))) {
                    cleanupSucceeded = false;
                }
            }
            if (documentToRestore != null) {
                // ID-verified, same as the primary write above: if the role was deleted and
                // recreated under this name in between, this must not write the restored document
                // onto the replacement.
                if (!CfnRollback.attemptIamCleanup(failure, "restore prior trust policy on role " + resolvedRoleName,
                        () -> iamService.updateAssumeRolePolicy(
                                resolvedRoleName, documentToRestore, roleIdToRestore))) {
                    cleanupSucceeded = false;
                }
            }
            if (createdRole) {
                if (!CfnRollback.attemptIamCleanup(failure, "delete role " + resolvedRoleName,
                        () -> iamService.deleteRole(resolvedRoleName))) {
                    cleanupSucceeded = false;
                }
                if (cleanupSucceeded) {
                    r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                }
            }
            throw failure;
        }

        // What the next execution compares its template against.
        writeTrackedSet(r, INLINE_NAMES_ATTR, inlineWrittenByThisAttempt);
        writeTrackedSet(r, MANAGED_ARNS_ATTR, managedPolicyArns);
    }

    private static Set<String> readTrackedSet(StackResource r, String attribute) {
        String raw = r.getAttributes().get(attribute);
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(raw.split("\n")));
    }

    /**
     * Newline separated because an IAM policy name may contain a comma, per the
     * {@code [\w+=,.@-]+} pattern CloudFormation documents for {@code PolicyName}.
     */
    private static void writeTrackedSet(StackResource r, String attribute, Collection<String> values) {
        if (values.isEmpty()) {
            r.getAttributes().remove(attribute);
            return;
        }
        r.getAttributes().put(attribute, String.join("\n", values));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        IamRole role;
        try {
            role = iamService.getRole(physicalId);
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("IAM role already gone, treating as deleted: {0}", physicalId);
            return;
        }
        for (String policyArn : new ArrayList<>(role.getAttachedPolicyArns())) {
            iamService.detachRolePolicy(physicalId, policyArn);
        }
        for (String policyName : new ArrayList<>(role.getInlinePolicies().keySet())) {
            iamService.deleteRolePolicy(physicalId, policyName);
        }
        iamService.deleteRole(physicalId);
    }
}
