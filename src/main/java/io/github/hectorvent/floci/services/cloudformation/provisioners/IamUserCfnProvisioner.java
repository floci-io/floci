package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamUser;
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
 * CloudFormation provisioning for {@code AWS::IAM::User}, moved out of the
 * {@code CloudFormationResourceProvisioner} switch.
 */
@ApplicationScoped
public class IamUserCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IamUserCfnProvisioner.class);

    private static final String INLINE_NAMES_ATTR = "__FlociInlinePolicyNames";
    private static final String MANAGED_ARNS_ATTR = "__FlociManagedPolicyArns";
    private static final String GROUPS_ATTR = "__FlociGroups";
    private static final String USER_ID_ATTR = "__FlociUserId";

    private final IamService iamService;

    @Inject
    public IamUserCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::IAM::User");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userName = ctx.stablePhysicalName(ctx.resolveOptional(props, "UserName"),
                r.getLogicalId(), 64, false);
        final String resolvedUserName = userName;
        if (ctx.isUpdate() && !ctx.priorPhysicalId().equals(resolvedUserName)) {
            throw new AwsException("ValidationError",
                    "Updating UserName requires resource replacement, which is not supported.", 400);
        }

        String path = ctx.resolveOptional(props, "Path");
        if (path == null) {
            path = "/";
        }
        final String resolvedPath = path;

        List<String> managedPolicyArns = ctx.resolveStringList(props, "ManagedPolicyArns");
        List<String> groups = ctx.resolveStringList(props, "Groups");

        IamUser user;
        boolean createdUser = false;
        String priorPath = null;
        String priorUserId = null;
        try {
            user = iamService.createUser(resolvedUserName, resolvedPath);
            createdUser = true;
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        } catch (AwsException e) {
            if (!ctx.reusesPriorEntity(resolvedUserName) || !"EntityAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
            user = iamService.getUser(resolvedUserName);
            String existingUserId = r.getAttributes().get(USER_ID_ATTR);
            if (existingUserId == null || existingUserId.isBlank()
                    || !existingUserId.equals(user.getUserId())) {
                r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                throw e;
            }
            priorPath = user.getPath();
            priorUserId = existingUserId;
            if (!resolvedPath.equals(user.getPath())) {
                iamService.updateUser(resolvedUserName, null, resolvedPath, existingUserId);
                user = iamService.getUser(resolvedUserName);
            }
        }

        r.setPhysicalId(resolvedUserName);
        r.getAttributes().put("Arn", user.getArn());
        r.getAttributes().put(USER_ID_ATTR, user.getUserId());

        Set<String> previousInlineNames = readTrackedSet(r, INLINE_NAMES_ATTR);
        Set<String> previousManagedArns = readTrackedSet(r, MANAGED_ARNS_ATTR);
        Set<String> previousGroups = readTrackedSet(r, GROUPS_ATTR);

        Set<String> originalGroups = new HashSet<>(user.getGroupNames());
        Set<String> originalPolicyArns = new HashSet<>(user.getAttachedPolicyArns());
        Map<String, String> originalInlinePolicies = new HashMap<>(user.getInlinePolicies());

        LinkedHashSet<String> groupsAddedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> groupsRemovedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> attachedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> detachedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> inlineWrittenByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> inlineRemovedByThisAttempt = new LinkedHashSet<>();

        final String pathToRestore = priorPath;
        final String userIdToRestore = priorUserId;

        try {
            for (String groupName : groups) {
                iamService.addUserToGroup(groupName, resolvedUserName);
                if (!originalGroups.contains(groupName)) {
                    groupsAddedByThisAttempt.add(groupName);
                }
            }

            for (String policyArn : managedPolicyArns) {
                iamService.attachUserPolicy(resolvedUserName, policyArn);
                if (!originalPolicyArns.contains(policyArn)) {
                    attachedByThisAttempt.add(policyArn);
                }
            }

            if (props != null && props.has("Policies")) {
                for (JsonNode policy : props.get("Policies")) {
                    String declaredName = ctx.resolveOptional(policy, "PolicyName");
                    if (declaredName == null || declaredName.isBlank()) {
                        throw new AwsException("ValidationError",
                                "An inline policy on user " + resolvedUserName + " has no PolicyName.", 400);
                    }
                    final String policyName = declaredName;
                    JsonNode document = policy.get("PolicyDocument");
                    if (document == null || document.isNull()) {
                        throw new AwsException("ValidationError",
                                "Inline policy '" + policyName + "' on user " + resolvedUserName
                                        + " has no PolicyDocument.", 400);
                    }
                    iamService.putUserPolicy(resolvedUserName, policyName,
                            ctx.engine().resolveJsonAttribute(document));
                    inlineWrittenByThisAttempt.add(policyName);
                }
            }

            for (String stale : previousInlineNames) {
                if (!inlineWrittenByThisAttempt.contains(stale)
                        && originalInlinePolicies.containsKey(stale)) {
                    iamService.deleteUserPolicy(resolvedUserName, stale);
                    inlineRemovedByThisAttempt.add(stale);
                }
            }
            for (String stale : previousManagedArns) {
                if (!managedPolicyArns.contains(stale) && originalPolicyArns.contains(stale)) {
                    iamService.detachUserPolicy(resolvedUserName, stale);
                    detachedByThisAttempt.add(stale);
                }
            }
            for (String stale : previousGroups) {
                if (!groups.contains(stale) && originalGroups.contains(stale)) {
                    iamService.removeUserFromGroup(stale, resolvedUserName);
                    groupsRemovedByThisAttempt.add(stale);
                }
            }
        } catch (RuntimeException failure) {
            boolean cleanupSucceeded = true;

            for (String groupName : groupsRemovedByThisAttempt) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "re-add user " + resolvedUserName + " to group " + groupName,
                        () -> iamService.addUserToGroup(groupName, resolvedUserName))) {
                    cleanupSucceeded = false;
                }
            }
            for (String policyName : inlineRemovedByThisAttempt) {
                String prior = originalInlinePolicies.get(policyName);
                if (prior == null) {
                    continue;
                }
                if (!CfnRollback.attemptIamCleanup(failure,
                        "restore inline policy " + policyName + " on user " + resolvedUserName,
                        () -> iamService.putUserPolicy(resolvedUserName, policyName, prior))) {
                    cleanupSucceeded = false;
                }
            }
            for (String policyArn : detachedByThisAttempt) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "reattach policy " + policyArn + " to user " + resolvedUserName,
                        () -> iamService.attachUserPolicy(resolvedUserName, policyArn))) {
                    cleanupSucceeded = false;
                }
            }

            List<String> inlineRollback = new ArrayList<>(inlineWrittenByThisAttempt);
            Collections.reverse(inlineRollback);
            for (String policyName : inlineRollback) {
                String prior = originalInlinePolicies.get(policyName);
                String cleanupDescription = (prior == null ? "remove" : "restore")
                        + " inline policy " + policyName + " on user " + resolvedUserName;
                if (!CfnRollback.attemptIamCleanup(failure, cleanupDescription, () -> {
                    if (prior == null) {
                        iamService.deleteUserPolicy(resolvedUserName, policyName);
                    } else {
                        iamService.putUserPolicy(resolvedUserName, policyName, prior);
                    }
                })) {
                    cleanupSucceeded = false;
                }
            }

            List<String> rollbackArns = new ArrayList<>(attachedByThisAttempt);
            Collections.reverse(rollbackArns);
            for (String policyArn : rollbackArns) {
                String cleanupDescription = "detach policy " + policyArn + " from user " + resolvedUserName;
                if (!CfnRollback.attemptIamCleanup(failure, cleanupDescription,
                        () -> iamService.detachUserPolicy(resolvedUserName, policyArn))) {
                    cleanupSucceeded = false;
                }
            }

            List<String> rollbackGroups = new ArrayList<>(groupsAddedByThisAttempt);
            Collections.reverse(rollbackGroups);
            for (String groupName : rollbackGroups) {
                String cleanupDescription = "remove user " + resolvedUserName + " from group " + groupName;
                if (!CfnRollback.attemptIamCleanup(failure, cleanupDescription,
                        () -> iamService.removeUserFromGroup(groupName, resolvedUserName))) {
                    cleanupSucceeded = false;
                }
            }

            if (pathToRestore != null) {
                if (!CfnRollback.attemptIamCleanup(failure, "restore prior path on user " + resolvedUserName,
                        () -> iamService.updateUser(resolvedUserName, null, pathToRestore, userIdToRestore))) {
                    cleanupSucceeded = false;
                }
            }

            if (createdUser) {
                if (!CfnRollback.attemptIamCleanup(failure, "delete user " + resolvedUserName,
                        () -> iamService.deleteUser(resolvedUserName))) {
                    cleanupSucceeded = false;
                }
                if (cleanupSucceeded) {
                    r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                }
            }
            throw failure;
        }

        writeTrackedSet(r, INLINE_NAMES_ATTR, inlineWrittenByThisAttempt);
        writeTrackedSet(r, MANAGED_ARNS_ATTR, managedPolicyArns);
        writeTrackedSet(r, GROUPS_ATTR, groups);
    }

    private static Set<String> readTrackedSet(StackResource r, String attribute) {
        String raw = r.getAttributes().get(attribute);
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(raw.split("\n")));
    }

    private static void writeTrackedSet(StackResource r, String attribute, Collection<String> values) {
        if (values.isEmpty()) {
            r.getAttributes().remove(attribute);
            return;
        }
        r.getAttributes().put(attribute, String.join("\n", values));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        IamUser user;
        try {
            user = iamService.getUser(physicalId);
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("IAM user already gone, treating as deleted: {0}", physicalId);
            return;
        }

        for (String policyArn : new ArrayList<>(user.getAttachedPolicyArns())) {
            CfnDeletes.safeDelete("attached policy " + policyArn + " on user", physicalId,
                    () -> iamService.detachUserPolicy(physicalId, policyArn), "NoSuchEntity");
        }

        for (String policyName : new ArrayList<>(user.getInlinePolicies().keySet())) {
            CfnDeletes.safeDelete("inline policy " + policyName + " on user", physicalId,
                    () -> iamService.deleteUserPolicy(physicalId, policyName), "NoSuchEntity");
        }

        for (String groupName : new ArrayList<>(user.getGroupNames())) {
            CfnDeletes.safeDelete("user " + physicalId + " membership in group " + groupName, physicalId,
                    () -> iamService.removeUserFromGroup(groupName, physicalId), "NoSuchEntity");
        }

        try {
            for (AccessKey key : iamService.listAccessKeys(physicalId)) {
                CfnDeletes.safeDelete("access key " + key.getAccessKeyId() + " on user", physicalId,
                        () -> iamService.deleteAccessKey(physicalId, key.getAccessKeyId()), "NoSuchEntity");
            }
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("IAM user access keys already gone, treating as deleted: {0}", physicalId);
        }

        CfnDeletes.safeDelete("IAM user", physicalId,
                () -> iamService.deleteUser(physicalId), "NoSuchEntity");
    }
}
