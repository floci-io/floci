package io.floci.aws.services.ssoadmin;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.ssoadmin.model.CustomerManagedPolicyReference;
import io.floci.aws.services.ssoadmin.model.PermissionSet;
import io.floci.aws.services.ssoadmin.model.PermissionSetProvisioning;
import io.floci.aws.services.ssoadmin.model.SsoInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** IAM writes for a permission set run in the target account, not the administering account. */
@ApplicationScoped
public class SsoPermissionSetRoleProvisioner {
    private final IamService iam;

    @Inject
    public SsoPermissionSetRoleProvisioner(IamService iam) {
        this.iam = iam;
    }

    public IamRole provision(PermissionSet permissionSet, SsoInstance instance, String accountId,
                             PermissionSetProvisioning previous) {
        return RequestScopes.callAs(accountId, instance.primaryRegion(), () -> {
            List<String> policies = new ArrayList<>(permissionSet.managedPolicies().keySet());
            for (CustomerManagedPolicyReference reference : permissionSet.customerManagedPolicies().values()) {
                policies.add(customerPolicy(reference));
            }
            String boundary = null;
            if (permissionSet.permissionsBoundary() != null) {
                boundary = permissionSet.permissionsBoundary().managedPolicyArn();
                if (boundary == null) {
                    boundary = customerPolicy(permissionSet.permissionsBoundary().customerManagedPolicyReference());
                }
            }
            String partition = AwsArnUtils.parse(instance.instanceArn()).partition();
            for (String policy : policies) {
                requirePartition(policy, partition);
                iam.getPolicy(policy);
            }
            if (boundary != null) {
                requirePartition(boundary, partition);
                iam.getPolicy(boundary);
            }
            IamRole role = previous != null && previous.roleArn() != null ? trackedRole(previous) : null;
            boolean created = role == null;
            if (created) {
                String roleName = "AWSReservedSSO_" + permissionSet.name() + "_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                String path = "/aws-reserved/" + ServicePrincipals.of("sso") + "/"
                        + ("us-east-1".equals(instance.primaryRegion()) ? "" : instance.primaryRegion() + "/"); // partition-literal: AWS omits this region in Identity Center role paths.
                // The local portal issues sessions after checking assignments. SAML federation is separate.
                String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"Service\":\"" + ServicePrincipals.of("sso")
                        + "\"},\"Action\":\"sts:AssumeRole\"}]}";
                role = iam.createRole(roleName, path, trust, null, 0, null);
            }
            Map<String, String> inline = permissionSet.inlinePolicy() == null
                    ? Map.of() : Map.of("AwsSSOInlinePolicy", permissionSet.inlinePolicy());
            try {
                iam.replaceRolePolicies(role.getRoleName(), role.getRoleId(), policies, inline, boundary,
                        (int) Duration.parse(permissionSet.sessionDuration()).getSeconds());
            } catch (RuntimeException failure) {
                if (created) {
                    iam.deleteRole(role.getRoleName(), role.getRoleId());
                }
                throw failure;
            }
            return role;
        });
    }

    private static void requirePartition(String policyArn, String partition) {
        if (!partition.equals(AwsArnUtils.parse(policyArn).partition())) {
            throw new AwsException("NoSuchEntity", "The policy is not in the target account's partition.", 404);
        }
    }

    private String customerPolicy(CustomerManagedPolicyReference reference) {
        return iam.listPolicies("Local", reference.path()).stream()
                .filter(policy -> reference.path().equals(policy.getPath())
                        && reference.name().equalsIgnoreCase(policy.getPolicyName()))
                .findFirst().orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Customer managed policy not found in target account: " + reference.path() + reference.name(), 404))
                .getArn();
    }

    private IamRole trackedRole(PermissionSetProvisioning provisioning) {
        String resource = AwsArnUtils.parse(provisioning.roleArn()).resource();
        String roleName = resource.substring(resource.lastIndexOf('/') + 1);
        IamRole role = iam.findRole(provisioning.accountId(), roleName).orElse(null);
        if (role != null && (!role.getArn().equals(provisioning.roleArn())
                || !role.getRoleId().equals(provisioning.roleId()))) {
            throw new AwsException("ConflictException", "The provisioned IAM role identity has changed.", 400);
        }
        return role;
    }

    public IamRole forPortal(PermissionSetProvisioning provisioning, String region) {
        if (provisioning == null || provisioning.roleArn() == null || provisioning.roleId() == null) {
            throw new AwsException("ResourceNotFoundException", "Provision the permission set before requesting credentials.", 404);
        }
        IamRole role = RequestScopes.callAs(provisioning.accountId(), region, () -> trackedRole(provisioning));
        if (role == null) {
            throw new AwsException("ResourceNotFoundException", "The provisioned IAM role no longer exists.", 404);
        }
        return role;
    }

    public void remove(PermissionSetProvisioning provisioning, String region) {
        if (provisioning.roleArn() == null || provisioning.roleId() == null) {
            return;
        }
        RequestScopes.runAs(provisioning.accountId(), region, () -> {
            IamRole role = trackedRole(provisioning);
            if (role != null) {
                iam.replaceRolePolicies(role.getRoleName(), role.getRoleId(), List.of(), Map.of(), null, 0);
                iam.deleteRole(role.getRoleName(), role.getRoleId());
            }
        });
    }
}
