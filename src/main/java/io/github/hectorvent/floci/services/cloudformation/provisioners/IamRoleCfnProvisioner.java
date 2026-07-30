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
        }

        r.setPhysicalId(resolvedRoleName);
        r.getAttributes().put("Arn", role.getArn());
        r.getAttributes().put("RoleId", role.getRoleId());

        Set<String> originalPolicyArns = new HashSet<>(role.getAttachedPolicyArns());
        LinkedHashSet<String> attachedByThisAttempt = new LinkedHashSet<>();
        // What the inline policies looked like before this attempt, so a partial write can be put
        // back. On an update that adopts an existing role these are the values to restore; for a
        // name this attempt introduced there is no prior value and the policy is removed instead.
        Map<String, String> originalInlinePolicies = new HashMap<>(role.getInlinePolicies());
        LinkedHashSet<String> inlineWrittenByThisAttempt = new LinkedHashSet<>();
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
                    final String policyName = declaredName == null || declaredName.isBlank()
                            ? ctx.generatePhysicalName(r.getLogicalId() + "Policy", 128, false)
                            : declaredName;
                    JsonNode document = policy.get("PolicyDocument");
                    if (document == null || document.isNull()) {
                        // Skipping it and reporting CREATE_COMPLETE without the declared policy is
                        // the same class of bug as #1952 itself.
                        throw new AwsException("ValidationError",
                                "Inline policy '" + policyName + "' on role " + resolvedRoleName
                                + " has no PolicyDocument.", 400);
                    }
                    iamService.putRolePolicy(resolvedRoleName, policyName,
                            ctx.engine().resolveNode(document).toString());
                    inlineWrittenByThisAttempt.add(policyName);
                }
            }
        } catch (RuntimeException failure) {
            boolean cleanupSucceeded = true;

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
