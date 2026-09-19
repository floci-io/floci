package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::AccessKey}. {@code Ref} returns the access key
 * id, and {@code Fn::GetAtt} exposes the same id plus the one-time {@code SecretAccessKey}. The
 * owning user name is stored so the key can be deleted with the stack.
 */
@ApplicationScoped
public class IamAccessKeyCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IAM::AccessKey";
    private static final String USER_NAME_ATTR = "__FlociAccessKeyUserName";

    private final IamService iamService;

    @Inject
    public IamAccessKeyCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userName = ctx.resolveOptional(props, "UserName");
        if (userName == null || userName.isBlank()) {
            throw new AwsException("ValidationError", "AWS::IAM::AccessKey requires a UserName.", 400);
        }

        // UserName and Serial are the only inputs and both are createOnly, so an update that keeps
        // the same user reuses the existing key rather than minting another one. IamService caps a
        // user at two keys, so recreating on every update would fail with LimitExceeded and orphan
        // the earlier keys. A UserName change is a replacement, which this provisioner does not do.
        if (ctx.isUpdate()) {
            String priorUser = r.getAttributes().get(USER_NAME_ATTR);
            if (priorUser != null && !priorUser.equals(userName)) {
                throw new AwsException("ValidationError",
                        "Updating UserName requires resource replacement, which is not supported.", 400);
            }
            return;
        }

        AccessKey key = iamService.createAccessKey(userName);
        r.setPhysicalId(key.getAccessKeyId());
        r.getAttributes().put("Id", key.getAccessKeyId());
        r.getAttributes().put("SecretAccessKey", key.getSecretAccessKey());
        r.getAttributes().put(USER_NAME_ATTR, userName);
    }

    @Override
    public void delete(StackResource resource, String region) {
        String userName = resource.getAttributes().get(USER_NAME_ATTR);
        if (userName != null && !userName.isBlank() && resource.getPhysicalId() != null) {
            CfnDeletes.safeDelete("IAM access key", resource.getPhysicalId(),
                    () -> iamService.deleteAccessKey(userName, resource.getPhysicalId()), "NoSuchEntity");
        }
    }
}
