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
import java.util.List;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::User}, moved out of the
 * {@code CloudFormationResourceProvisioner} switch.
 */
@ApplicationScoped
public class IamUserCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IamUserCfnProvisioner.class);

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
        String existingUserName = ctx.priorPhysicalId() != null ? ctx.priorPhysicalId() : r.getPhysicalId();
        String userName = ctx.resolveOptional(props, "UserName");
        if (userName == null || userName.isBlank()) {
            userName = existingUserName != null && !existingUserName.isBlank()
                    ? existingUserName
                    : ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }
        final String resolvedUserName = userName;
        if (existingUserName != null && !existingUserName.equals(resolvedUserName)) {
            throw new AwsException("ValidationError",
                    "Updating UserName requires resource replacement, which is not supported.", 400);
        }

        String path = ctx.resolveOptional(props, "Path");
        if (path == null) {
            path = "/";
        }

        List<String> managedPolicyArns = ctx.resolveStringList(props, "ManagedPolicyArns");
        List<String> groups = ctx.resolveStringList(props, "Groups");

        IamUser user;
        try {
            user = iamService.createUser(resolvedUserName, path);
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        } catch (AwsException e) {
            boolean stackAlreadyOwnsUser = existingUserName != null && existingUserName.equals(resolvedUserName);
            if (!stackAlreadyOwnsUser || !"EntityAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
            user = iamService.getUser(resolvedUserName);
        }

        r.setPhysicalId(resolvedUserName);
        r.getAttributes().put("Arn", user.getArn());

        for (String groupName : groups) {
            iamService.addUserToGroup(groupName, resolvedUserName);
        }

        for (String policyArn : managedPolicyArns) {
            iamService.attachUserPolicy(resolvedUserName, policyArn);
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
            }
        }
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
            try {
                iamService.detachUserPolicy(physicalId, policyArn);
            } catch (AwsException e) {
                if (!"NoSuchEntity".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }

        for (String policyName : new ArrayList<>(user.getInlinePolicies().keySet())) {
            try {
                iamService.deleteUserPolicy(physicalId, policyName);
            } catch (AwsException e) {
                if (!"NoSuchEntity".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }

        for (String groupName : new ArrayList<>(user.getGroupNames())) {
            try {
                iamService.removeUserFromGroup(groupName, physicalId);
            } catch (AwsException e) {
                if (!"NoSuchEntity".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }

        try {
            for (AccessKey key : iamService.listAccessKeys(physicalId)) {
                try {
                    iamService.deleteAccessKey(physicalId, key.getAccessKeyId());
                } catch (AwsException e) {
                    if (!"NoSuchEntity".equals(e.getErrorCode())) {
                        throw e;
                    }
                }
            }
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
        }

        try {
            iamService.deleteUser(physicalId);
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }
}
