package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::InstanceProfile}. {@code Ref} returns the
 * instance profile name (the primary identifier) and {@code Fn::GetAtt Arn} its arn. The declared
 * {@code Path} and {@code Roles} are applied, and the roles are detached before delete because IAM
 * refuses to delete a profile that still holds one.
 */
@ApplicationScoped
public class IamInstanceProfileCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IAM::InstanceProfile";

    private final IamService iamService;

    @Inject
    public IamInstanceProfileCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A create-only name kept stable across updates: an unnamed profile keeps the name it was
        // given rather than getting a fresh random one each update, which would orphan the first.
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "InstanceProfileName"),
                r.getLogicalId(), 128, false);
        if (ctx.isUpdate() && !name.equals(ctx.priorPhysicalId())) {
            throw new AwsException("ValidationError",
                    "Updating InstanceProfileName requires resource replacement, which is not supported.", 400);
        }
        String path = ctx.resolveOptional(props, "Path");
        if (path == null || path.isBlank()) {
            path = "/";
        }
        r.setPhysicalId(name);
        try {
            InstanceProfile profile = iamService.createInstanceProfile(name, path);
            r.getAttributes().put("Arn", profile.getArn());
        } catch (AwsException e) {
            // Only re-provisioning the same profile on update is tolerated; any other failure,
            // including a name collision with a profile outside this stack, propagates.
            if (!ctx.reusesPriorEntity(name) || !"EntityAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
            r.getAttributes().put("Arn", iamService.getInstanceProfile(name).getArn());
        }
        for (String roleName : ctx.resolveStringList(props, "Roles")) {
            iamService.addRoleToInstanceProfile(name, roleName);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // Detach roles first: deleteInstanceProfile rejects a profile that still holds one with
        // DeleteConflict, which must otherwise propagate. The already-gone case is tolerated.
        CfnDeletes.safeDelete("IAM instance profile", physicalId, () -> {
            InstanceProfile profile = iamService.getInstanceProfile(physicalId);
            for (String roleName : new ArrayList<>(profile.getRoleNames())) {
                iamService.removeRoleFromInstanceProfile(physicalId, roleName);
            }
            iamService.deleteInstanceProfile(physicalId);
        }, "NoSuchEntity");
    }
}
