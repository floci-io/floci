package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.iam.IamRequestShapes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * CloudFormation provisioning for {@code AWS::IAM::Role}, moved out of the
 * former CloudFormation monolith's switch. The other IAM types (User, AccessKey, Policy,
 * ManagedPolicy, InstanceProfile) still live there and share {@link CfnRollback} with this class.
 */
@ApplicationScoped
public class IamRoleCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IamRoleCfnProvisioner.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DURATION_SNAPSHOT_ATTR = "__FlociRoleDurationSnapshot";

    private static final String INLINE_NAMES_ATTR = "__FlociInlinePolicyNames";
    private static final String MANAGED_ARNS_ATTR = "__FlociManagedPolicyArns";
    private static final String TAG_KEYS_ATTR = "__FlociRoleTagKeys";

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
        JsonNode assumeDocNode = props != null ? props.get("AssumeRolePolicyDocument") : null;
        String resolvedAssumeDoc = assumeDocNode != null ? ctx.engine().resolveJsonAttributeStrict(assumeDocNode) : null;
        String assumeDoc = resolvedAssumeDoc != null
                ? resolvedAssumeDoc
                : "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        String path = ctx.resolveOptional(props, "Path");
        if (path == null) {
            path = "/";
        }
        String description = ctx.resolveOptional(props, "Description");
        Integer declaredDuration = ctx.resolveOptionalInt(props, "MaxSessionDuration");
        int maxSessionDuration = declaredDuration != null ? declaredDuration : 3600;
        MultivaluedMap<String, String> durationParams = new MultivaluedHashMap<>();
        durationParams.putSingle("RoleName", resolvedRoleName);
        durationParams.putSingle("MaxSessionDuration", Integer.toString(maxSessionDuration));
        IamRequestShapes.check("UpdateRole", durationParams);
        List<String> managedPolicyArns = ctx.resolveStringList(props, "ManagedPolicyArns");
        // Blank means absent: Fn::If with Ref AWS::NoValue resolves to "" (CDK bootstrap does this).
        String templateBoundary = ctx.resolveOrDefault(props, "PermissionsBoundary", null);
        // A stack a delegation created caps every role it provisions, the guide limiting roles
        // "created or managed through temporary access". The cap is the customer's registration
        // rather than a choice of the template, so it wins over a boundary the template names, the
        // same way it wins over one a CreateRole call names.
        String permissionsBoundary = ctx.delegatedRoleBoundaryArn() != null
                ? ctx.delegatedRoleBoundaryArn() : templateBoundary;
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        checkTemplateTags(resolvedRoleName, tags);

        IamRole role;
        boolean createdRole = false;
        // Set only on the adoption path, to the tag state as it was before this attempt touched it:
        // the keys the previous deployment recorded as the stack's, and the values the role held for
        // them. Lets a later failure put them back, and drives the update to the template's tags.
        Set<String> ownedTagKeys = null;
        Map<String, String> priorRoleTags = null;
        // Set only on the adoption path, to the role's trust policy (and verified RoleId) as they
        // were before this attempt touched them. Lets a later failure (e.g. bad ManagedPolicyArns)
        // restore the trust policy below, so a rolled-back update doesn't leave it changed despite
        // UPDATE_ROLLBACK_COMPLETE - and restore it onto the *same verified role*, not onto a
        // replacement that took the name in the meantime.
        String priorAssumeRolePolicyDocument = null;
        String priorAssumeRoleId = null;
        // The adopted role's boundary before this attempt, restored on failure like the trust policy.
        String priorPermissionsBoundary = null;
        int priorMaxSessionDuration = 3600;
        try {
            role = iamService.createRole(resolvedRoleName, path, assumeDoc, description, maxSessionDuration, tags,
                    permissionsBoundary);
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
            ownedTagKeys = Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx);
            priorRoleTags = new LinkedHashMap<>(role.getTags());
            snapshotDuration(r, role, Ec2Tags.touched(ownedTagKeys, tags), priorRoleTags);
            priorAssumeRolePolicyDocument = role.getAssumeRolePolicyDocument();
            priorAssumeRoleId = existingRoleId;
            priorPermissionsBoundary = role.getPermissionsBoundaryArn();
            priorMaxSessionDuration = role.getMaxSessionDuration();
            iamService.updateAssumeRolePolicy(resolvedRoleName, assumeDoc, existingRoleId);
        }

        r.setPhysicalId(resolvedRoleName);
        r.getAttributes().put("Arn", role.getArn());
        r.getAttributes().put("RoleId", role.getRoleId());

        // What the previous execution of this resource wrote, so an update can take out what the
        // template stopped declaring. Only these names are removed, which leaves inline policies and
        // attachments added out of band alone.
        Set<String> previousInlineNames = CfnTrackedSets.read(r, INLINE_NAMES_ATTR);
        Set<String> previousManagedArns = CfnTrackedSets.read(r, MANAGED_ARNS_ATTR);

        Set<String> originalPolicyArns = new HashSet<>(role.getAttachedPolicyArns());
        List<String> canonicalPolicyArns = new ArrayList<>();
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
        final String boundaryToRestore = priorPermissionsBoundary;
        final int durationToRestore = priorMaxSessionDuration;
        final Set<String> ownedTags = ownedTagKeys;
        final Map<String, String> tagsToRestore = priorRoleTags;
        boolean boundaryChangedByThisAttempt = false;
        boolean durationChangedByThisAttempt = false;
        try {
            if (!createdRole && maxSessionDuration != durationToRestore) {
                iamService.updateRole(resolvedRoleName, null, maxSessionDuration, roleIdToRestore);
                durationChangedByThisAttempt = true;
            }
            // createRole() only applies PermissionsBoundary on first create. On adoption, set a
            // changed boundary, and remove one only if the role has one and the template dropped it.
            // ID-verified like the trust-policy write, here and in the rollback restore.
            if (!createdRole && !Objects.equals(permissionsBoundary, boundaryToRestore)) {
                if (permissionsBoundary != null) {
                    iamService.putRolePermissionsBoundary(resolvedRoleName, permissionsBoundary, roleIdToRestore);
                } else {
                    iamService.deleteRolePermissionsBoundary(resolvedRoleName, roleIdToRestore);
                }
                boundaryChangedByThisAttempt = true;
            }

            // Tags run inside the same protected block: on an update that adopts the role they are
            // driven to what the template declares, untagging only the keys the previous execution
            // recorded as the stack's, so a tag added outside the stack stays.
            if (ownedTags != null) {
                reconcileTags(resolvedRoleName, ownedTags, tags);
            }

            for (String policyArn : managedPolicyArns) {
                String canonicalArn = iamService.canonicalPolicyArn(policyArn);
                iamService.attachRolePolicy(resolvedRoleName, canonicalArn);
                canonicalPolicyArns.add(canonicalArn);
                if (!originalPolicyArns.contains(canonicalArn)) {
                    attachedByThisAttempt.add(canonicalArn);
                }
            }

            // Inline policies run inside the same protected block: a failure here used to leave
            // the created role, its managed attachments and any earlier inline writes behind,
            // because rollback only deletes resources that reached CREATE_COMPLETE.
            Map<String, String> desiredInlinePolicies = new LinkedHashMap<>();
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
                    desiredInlinePolicies.put(policyName, ctx.engine().resolveJsonAttributeStrict(document));
                }
            }

            // Anything the previous execution wrote and this template no longer declares. Without
            // this the role keeps permissions the stack stopped declaring while the stack still
            // reports UPDATE_COMPLETE, which is the failure mode #1952 is about one level over.
            // A tracked policy that is already gone is skipped rather than deleted again. Both IAM
            // calls raise NoSuchEntity on an absent target, which would fail an update whose desired
            // end state, the policy not being on the role, already holds.
            for (String stale : previousInlineNames) {
                if (!desiredInlinePolicies.containsKey(stale)
                        && originalInlinePolicies.containsKey(stale)) {
                    iamService.deleteRolePolicy(resolvedRoleName, stale);
                    inlineRemovedByThisAttempt.add(stale);
                }
            }
            // Free the quota held by renamed policies before writing their replacements.
            for (Map.Entry<String, String> policy : desiredInlinePolicies.entrySet()) {
                iamService.putRolePolicy(resolvedRoleName, policy.getKey(), policy.getValue());
                inlineWrittenByThisAttempt.add(policy.getKey());
            }
            for (String stale : previousManagedArns) {
                if (!canonicalPolicyArns.contains(stale) && originalPolicyArns.contains(stale)) {
                    iamService.detachRolePolicy(resolvedRoleName, stale);
                    detachedByThisAttempt.add(stale);
                }
            }
        } catch (RuntimeException failure) {
            boolean cleanupSucceeded = true;

            for (String policyArn : detachedByThisAttempt) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "reattach policy " + policyArn + " to role " + resolvedRoleName,
                        () -> iamService.attachRolePolicy(resolvedRoleName, policyArn))) {
                    cleanupSucceeded = false;
                }
            }

            // Remove this attempt's writes before restoring earlier documents, so temporary
            // overlap cannot exceed the role quota during rollback either.
            List<String> inlineRollback = new ArrayList<>(inlineWrittenByThisAttempt);
            Collections.reverse(inlineRollback);
            for (String policyName : inlineRollback) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "remove inline policy " + policyName + " on role " + resolvedRoleName,
                        () -> iamService.deleteRolePolicy(resolvedRoleName, policyName))) {
                    cleanupSucceeded = false;
                }
            }
            Set<String> inlineToRestore = new LinkedHashSet<>(inlineRollback);
            inlineToRestore.addAll(inlineRemovedByThisAttempt);
            for (String policyName : inlineToRestore) {
                String prior = originalInlinePolicies.get(policyName);
                if (prior != null && !CfnRollback.attemptIamCleanup(failure,
                        "restore inline policy " + policyName + " on role " + resolvedRoleName,
                        () -> iamService.restoreRolePolicy(resolvedRoleName, policyName, prior))) {
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
            if (boundaryChangedByThisAttempt) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "restore prior permissions boundary on role " + resolvedRoleName, () -> {
                            if (boundaryToRestore == null) {
                                iamService.deleteRolePermissionsBoundary(resolvedRoleName, roleIdToRestore);
                            } else {
                                iamService.putRolePermissionsBoundary(resolvedRoleName, boundaryToRestore, roleIdToRestore);
                            }
                        })) {
                    cleanupSucceeded = false;
                }
            }
            if (durationChangedByThisAttempt) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "restore prior session duration on role " + resolvedRoleName,
                        () -> iamService.updateRole(resolvedRoleName, null, durationToRestore, roleIdToRestore))) {
                    cleanupSucceeded = false;
                }
            }
            if (ownedTags != null) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "restore prior tags on role " + resolvedRoleName,
                        () -> reconcileTags(resolvedRoleName, ownedTags, tagsToRestore))) {
                    cleanupSucceeded = false;
                }
            }
            if (documentToRestore != null) {
                // ID-verified, same as the primary write above: if the role was deleted and
                // recreated under this name in between, this must not write the restored document
                // onto the replacement.
                if (!CfnRollback.attemptIamCleanup(failure, "restore prior trust policy on role " + resolvedRoleName,
                        () -> iamService.restoreAssumeRolePolicy(
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
        CfnTrackedSets.write(r, INLINE_NAMES_ATTR, inlineWrittenByThisAttempt);
        CfnTrackedSets.write(r, MANAGED_ARNS_ATTR, canonicalPolicyArns);
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, tags);
    }

    private void snapshotDuration(StackResource resource, IamRole role, Set<String> managedTagKeys,
                                  Map<String, String> currentTags) {
        if (requiresUpdateRecovery(resource)) {
            return;
        }
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("name", role.getRoleName());
        snapshot.put("id", role.getRoleId());
        snapshot.put("duration", role.getMaxSessionDuration());
        snapshot.set("otherConfiguration", otherConfiguration(role));
        CfnRollback.putTagSnapshot(snapshot, managedTagKeys, currentTags);
        snapshot.set("attributes", MAPPER.valueToTree(resource.getAttributes()));
        resource.getAttributes().put(DURATION_SNAPSHOT_ATTR, snapshot.toString());
    }

    /**
     * Holds the template's tags to the constraints IAM's {@code TagRole} request shape declares, the
     * same reading a {@code TagRole} request gets: a key or value outside its pattern, or more tags
     * than the 50 the list allows, fails the resource before anything exists, the way
     * {@code CreateRole} fails the whole request when one tag is invalid. The per-role 50-tag quota
     * itself stays with {@code createRole} and {@code tagRole}, which count the tags the role
     * already carries.
     */
    private void checkTemplateTags(String roleName, Map<String, String> tags) {
        if (tags.isEmpty()) {
            return;
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleName", roleName);
        int index = 1;
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            String member = "Tags.member." + index;
            params.putSingle(member + ".Key", tag.getKey());
            params.putSingle(member + ".Value", tag.getValue());
            index++;
        }
        IamRequestShapes.check("TagRole", params);
    }

    /**
     * Drives a role's tags to {@code desired} through the role's own {@code UntagRole} and
     * {@code TagRole}: the one path an update and its rollback take. Only {@code managedKeys}
     * absent from {@code desired} are removed, so a tag added outside the stack stays.
     */
    private void reconcileTags(String roleName, Collection<String> managedKeys, Map<String, String> desired) {
        CfnRollback.reconcileTags(managedKeys, desired, dropped -> iamService.untagRole(roleName, dropped),
                added -> iamService.tagRole(roleName, added));
    }

    private JsonNode otherConfiguration(IamRole role) {
        ObjectNode configuration = MAPPER.createObjectNode();
        configuration.put("trust", role.getAssumeRolePolicyDocument());
        configuration.put("boundary", role.getPermissionsBoundaryArn());
        configuration.set("inline", MAPPER.valueToTree(role.getInlinePolicies()));
        configuration.set("managed", MAPPER.valueToTree(new TreeSet<>(role.getAttachedPolicyArns())));
        return configuration;
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String raw = resource.getAttributes().get(DURATION_SNAPSHOT_ATTR);
        if (raw == null) {
            return false;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid IAM role duration rollback snapshot", e);
        }
        IamRole role = iamService.updateRole(snapshot.path("name").asText(), null,
                snapshot.path("duration").asInt(), snapshot.path("id").asText());
        // Tags are put back from the snapshot the update took, like the duration: the touched keys
        // that the role had get their prior value again, and one the update added is removed.
        reconcileTags(snapshot.path("name").asText(), CfnRollback.snapshotTagKeys(snapshot),
                CfnRollback.snapshotTags(snapshot));
        // Duration rollback must not report success for trust or policy changes it did not undo.
        if (!snapshot.path("otherConfiguration").equals(otherConfiguration(role))) {
            return false;
        }
        resource.getAttributes().clear();
        snapshot.path("attributes").properties().forEach(attribute ->
                resource.getAttributes().put(attribute.getKey(), attribute.getValue().asText()));
        return true;
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return requiresUpdateRecovery(resource);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return resource.getAttributes().containsKey(DURATION_SNAPSHOT_ATTR);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        clearUpdate(resource);
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(DURATION_SNAPSHOT_ATTR);
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
        // Asked before anything is cleared: the delete can be refused for an instance profile
        // this stack does not own, and nothing takes a role out of one on the caller's behalf, so
        // detaching first would leave a role that is still there and no longer has its policies.
        iamService.requireNotHeldByAnInstanceProfile(physicalId);
        for (String policyArn : new ArrayList<>(role.getAttachedPolicyArns())) {
            iamService.detachRolePolicy(physicalId, policyArn);
        }
        for (String policyName : new ArrayList<>(role.getInlinePolicies().keySet())) {
            iamService.deleteRolePolicy(physicalId, policyName);
        }
        iamService.deleteRole(physicalId);
    }
}
