package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::InstanceProfile}. {@code Ref} returns the
 * instance profile name (the primary identifier) and {@code Fn::GetAtt Arn} its arn. The declared
 * {@code Path} and {@code Roles} are applied, and the roles are detached before delete because IAM
 * refuses to delete a profile that still holds one.
 *
 * <p>{@code InstanceProfileName} and {@code Path} are both createOnly, so an update that changes
 * either, or drops an explicit name, replaces the profile: the new one is created and the displaced
 * one is deleted once the update commits (or restored on rollback), via {@link ReplacementCleanup}.
 * A rename creates the new name outright, and dropping the name (whether the profile was created
 * under an explicit one is recorded at create time) mints a generated one. A {@code Path}
 * change that keeps an explicit name is attempted with that same name, so {@code CreateInstanceProfile}
 * collides and IAM answers {@code EntityAlreadyExists}, exactly as CloudFormation surfaces that update.
 */
@ApplicationScoped
public class IamInstanceProfileCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IAM::InstanceProfile";
    /**
     * Whether the profile was created under an explicit {@code InstanceProfileName}, recorded at
     * create time so that dropping the name later reads as the createOnly change it is. Filtered
     * out of the published attributes by the {@code __Floci} prefix, as the other provisioners do.
     * Recorded rather than inferred from the id's shape: a generated name loses its
     * {@code <stack>-<logicalId>-} prefix once that would push the name past 128 characters, so a
     * shape check misreads every long-named stack as explicitly named and churns the profile on
     * every update.
     */
    private static final String EXPLICIT_NAME_ATTR = "__FlociIamInstanceProfileExplicitName";

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
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String explicitName = ctx.resolveOptional(props, "InstanceProfileName");
        String path = ctx.resolveOptional(props, "Path");
        if (path == null || path.isBlank()) {
            path = "/";
        }
        List<String> roles = ctx.resolveStringList(props, "Roles");
        if (roles.isEmpty()) {
            throw new AwsException("ValidationError",
                    "AWS::IAM::InstanceProfile requires at least one role in Roles.", 400);
        }

        InstanceProfile prior = ctx.isUpdate() ? findProfile(ctx.priorPhysicalId()) : null;
        boolean named = explicitName != null && !explicitName.isBlank();
        boolean nameChanged = named && !explicitName.equals(ctx.priorPhysicalId());
        // Dropping InstanceProfileName is a createOnly change too: a profile created under an explicit
        // name (recorded at create time) that now declares none must be replaced with a generated
        // name, not left standing under the old explicit one. A prior profile with no record (created
        // before the record existed) is treated as unnamed: keeping it is the conservative outcome.
        boolean nameDropped = !named && ctx.isUpdate()
                && Boolean.parseBoolean(attributesBefore.get(EXPLICIT_NAME_ATTR));
        boolean pathChanged = prior != null && !normalizePath(path).equals(prior.getPath());
        if (prior != null && (nameChanged || nameDropped || pathChanged)) {
            replaceProfile(r, ctx, explicitName, path, roles, attributesBefore);
            return;
        }

        // A create-only name kept stable across updates: an unnamed profile keeps the name it was
        // given rather than getting a fresh random one each update, which would orphan the first.
        String name = ctx.stablePhysicalName(explicitName, r.getLogicalId(), 128, false);
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
        }
        r.getAttributes().put("Arn", profile.getArn());
        r.getAttributes().put(EXPLICIT_NAME_ATTR, String.valueOf(named));
        reconcileRoles(name, profile.getRoleNames(), roles);
        // Nothing was displaced here, so this only carries forward any delete an earlier failed
        // rollback still owed; a genuine replacement is recorded in replaceProfile.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Replaces the profile a createOnly change displaced: creates the new one and leaves the old for
     * cleanup once the update commits. A rename uses the new explicit name; a dropped name or a Path
     * change on an auto-named profile mints a fresh name. A Path change that keeps an explicit name
     * is attempted with that same name, so {@code CreateInstanceProfile} collides and IAM answers
     * {@code EntityAlreadyExists}, exactly as CloudFormation surfaces that update.
     */
    private void replaceProfile(StackResource r, ProvisionContext ctx, String explicitName,
                                String path, List<String> roles, Map<String, String> attributesBefore) {
        boolean named = explicitName != null && !explicitName.isBlank();
        String replacementName = named ? explicitName : ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        r.setPhysicalId(replacementName);
        InstanceProfile replacement = iamService.createInstanceProfile(replacementName, path);
        r.getAttributes().put("Arn", replacement.getArn());
        r.getAttributes().put(EXPLICIT_NAME_ATTR, String.valueOf(named));
        reconcileRoles(replacementName, replacement.getRoleNames(), roles);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** The prior profile, or null when it was deleted out of band so the update recreates it. */
    private InstanceProfile findProfile(String name) {
        try {
            return iamService.getInstanceProfile(name);
        } catch (AwsException e) {
            if ("NoSuchEntity".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
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
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
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
