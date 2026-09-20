package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
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
        List<String> roles = ctx.resolveStringList(props, "Roles");
        if (roles.isEmpty()) {
            throw new AwsException("ValidationError",
                    "AWS::IAM::InstanceProfile requires at least one role in Roles.", 400);
        }
        r.setPhysicalId(name);

        InstanceProfile profile;
        try {
            profile = iamService.createInstanceProfile(name, path);
        } catch (AwsException e) {
            // Only re-provisioning the same profile on update is tolerated; any other failure,
            // including a name collision with a profile outside this stack, propagates.
            if (!ctx.reusesPriorEntity(name) || !"EntityAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
            profile = iamService.getInstanceProfile(name);
            // Path is createOnly: a change would require replacement, which a same-named profile
            // cannot do, so it is rejected rather than silently ignored.
            if (!normalizePath(path).equals(profile.getPath())) {
                throw new AwsException("ValidationError",
                        "Updating Path requires resource replacement, which is not supported.", 400);
            }
        }
        r.getAttributes().put("Arn", profile.getArn());
        reconcileRoles(name, profile.getRoleNames(), roles);
    }

    /**
     * Brings the profile's attached roles to the declared set: an instance profile holds one role,
     * so a swap has to remove the old one before adding the new, and a no-op adds and removes
     * nothing.
     */
    private void reconcileRoles(String name, List<String> current, List<String> desired) {
        for (String roleName : new ArrayList<>(current)) {
            if (!desired.contains(roleName)) {
                iamService.removeRoleFromInstanceProfile(name, roleName);
            }
        }
        for (String roleName : desired) {
            if (!current.contains(roleName)) {
                iamService.addRoleToInstanceProfile(name, roleName);
            }
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String withLeading = path.startsWith("/") ? path : "/" + path;
        return withLeading.endsWith("/") ? withLeading : withLeading + "/";
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
