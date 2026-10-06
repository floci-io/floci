package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::Cognito::UserPoolGroup}. The physical id and {@code Ref} are the bare group
 * name, generated when the template gives none. {@code UserPoolId} and {@code GroupName} are
 * create-only: an update that keeps both changes the group in place, and one that changes either,
 * or drops an explicit name, creates the new group and leaves the displaced one to the
 * {@link ReplacementCleanup} record. A pool move that keeps an explicit name keeps the physical id,
 * so, as on AWS, nothing is deleted: a committed move leaves the group in the old pool behind, and
 * a rolled-back one points the resource at the old pool's group again and leaves the new one.
 * Declaring an unnamed group's generated name explicitly, in the same pool, needs a replacement
 * keeping both the pool and the name, which, as on AWS, is refused before anything is created,
 * unless the record predates the name mode.
 */
@ApplicationScoped
public class CognitoUserPoolGroupCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CognitoUserPoolGroupCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Attribute holding the owning pool id, the half of the key the physical id cannot carry. */
    static final String USER_POOL_ID_ATTR = "UserPoolId";
    /**
     * Records whether the name came from the template or was generated, so a later update can tell
     * an explicit name being dropped, which replaces the group, from an unnamed group keeping the
     * name it was given. A resource without it predates the attribute, and its name reads as
     * generated when it has the generated shape.
     */
    private static final String NAME_MODE_ATTR = "FlociGroupNameMode";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";
    /**
     * The current group name and every one still owed a delete, mapped to its pool, because the
     * replacement cleanup addresses a displaced group by its name alone and a group is deleted by
     * pool and name.
     */
    // ponytail: keyed by name alone. Two groups of one name are owed deletes together only after a
    // group delete failed, and deleteGroup fails only with ResourceNotFoundException, which counts as deleted.
    private static final String GROUP_POOLS_ATTR = "__FlociGroupPools";

    private final CognitoService cognitoService;

    @Inject
    public CognitoUserPoolGroupCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Cognito::UserPoolGroup");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String userPoolId = ctx.resolveOptional(props, "UserPoolId");
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new IllegalArgumentException("UserPoolId is required for AWS::Cognito::UserPoolGroup");
        }
        String explicitName = ctx.resolveOptional(props, "GroupName");
        if (explicitName != null && explicitName.isBlank()) {
            explicitName = null;
        }
        String priorPoolId = attributesBefore.get(USER_POOL_ID_ATTR);
        boolean poolChanged = ctx.isUpdate() && priorPoolId != null && !userPoolId.equals(priorPoolId);
        String groupName = groupName(r, ctx, explicitName, poolChanged);
        String description = ctx.resolveOptional(props, "Description");
        Integer precedence = parsePrecedence(ctx.resolveOptional(props, "Precedence"));
        String roleArn = ctx.resolveOptional(props, "RoleArn");

        // ponytail: only a recorded mode refuses. A legacy record's shape cannot tell an explicit
        // name from a generated one, so it updates in place as before.
        if (explicitName != null && !poolChanged && ctx.reusesPriorEntity(explicitName)
                && NAME_MODE_GENERATED.equals(r.getAttributes().get(NAME_MODE_ATTR))) {
            throw new AwsException("ValidationError",
                    "CloudFormation cannot update a stack when a custom-named resource requires "
                            + "replacing. Rename " + explicitName + " and update the stack again.", 400);
        }
        boolean replaced = poolChanged || !ctx.reusesPriorEntity(groupName);
        if (replaced) {
            cognitoService.createGroup(userPoolId, groupName, description, precedence, roleArn);
        } else {
            cognitoService.updateGroup(userPoolId, groupName, description, precedence, roleArn);
        }

        r.setPhysicalId(groupName);
        r.getAttributes().put(USER_POOL_ID_ATTR, userPoolId);
        r.getAttributes().put(NAME_MODE_ATTR, explicitName != null ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        ReplacementCleanup.record(r, ctx, attributesBefore, replaced);
        ObjectNode known = groupPools(r);
        if (ctx.isUpdate() && priorPoolId != null) {
            known.put(ctx.priorPhysicalId(), priorPoolId);
        }
        ObjectNode pools = MAPPER.createObjectNode();
        for (String owed : ReplacementCleanup.owedPhysicalIds(r)) {
            JsonNode pool = known.get(owed);
            if (pool != null) {
                pools.set(owed, pool);
            }
        }
        pools.put(groupName, userPoolId);
        r.getAttributes().put(GROUP_POOLS_ATTR, pools.toString());
    }

    /**
     * The template's name when it gives one; otherwise the generated name the group already has,
     * unless the previous name was explicit or the pool changed, since either replaces the group;
     * and a fresh generated name failing both.
     */
    private static String groupName(StackResource r, ProvisionContext ctx, String explicitName,
                                     boolean poolChanged) {
        if (explicitName != null) {
            return explicitName;
        }
        if (ctx.isUpdate() && !poolChanged && priorNameGenerated(r, ctx)) {
            return ctx.priorPhysicalId();
        }
        return ctx.generatePhysicalName(r.getLogicalId(), 128, false);
    }

    /** Whether the prior name was generated, read from its shape when the mode was never recorded. */
    private static boolean priorNameGenerated(StackResource r, ProvisionContext ctx) {
        String mode = r.getAttributes().get(NAME_MODE_ATTR);
        if (mode == null) {
            return ctx.isGeneratedPhysicalName(ctx.priorPhysicalId(), r.getLogicalId(), 128, false);
        }
        return !NAME_MODE_EXPLICIT.equals(mode);
    }

    /**
     * Deletes the current group from its {@code UserPoolId}, not through the pool map: after a
     * rolled-back pool move the map's entry for the name points at the new pool.
     */
    @Override
    public void delete(StackResource resource, String region) {
        deleteGroup(resource.getAttributes().get(USER_POOL_ID_ATTR), resource.getPhysicalId());
    }

    /** Deletes a group from the given pool, treating one that is already gone as deleted. */
    private void deleteGroup(String userPoolId, String groupName) {
        if (userPoolId == null || userPoolId.isBlank()) {
            return;
        }
        try {
            cognitoService.deleteGroup(userPoolId, groupName);
        } catch (AwsException e) {
            // An already-deleted group is the one failure that genuinely means "done". Anything
            // else is a real error worth surfacing rather than hiding behind a debug line.
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Cognito group already gone, treating as deleted: {0}", groupName);
        }
    }

    /** Deletes a displaced group from the pool the map recorded for its name. */
    private ReplacementCleanup.Deleter displacedDeleter(StackResource resource) {
        return (resourceType, groupName, region) -> deleteGroup(
                groupPools(resource).path(groupName).asText(resource.getAttributes().get(USER_POOL_ID_ATTR)),
                groupName);
    }

    private static ObjectNode groupPools(StackResource r) {
        String raw = r.getAttributes().get(GROUP_POOLS_ATTR);
        if (raw != null) {
            try {
                if (MAPPER.readTree(raw) instanceof ObjectNode pools) {
                    return pools;
                }
            } catch (JsonProcessingException e) {
                LOG.warnv("Unreadable group pool record on {0}, ignoring it: {1}", r.getLogicalId(), e.getMessage());
            }
        }
        return MAPPER.createObjectNode();
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
        return ReplacementCleanup.complete(resource, displacedDeleter(resource));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record. A named pool move kept the physical id,
     * so its rollback points the resource at the old pool's group again and, as on AWS, leaves the
     * new one. Without a record the group was updated in place, and putting that back needs a
     * snapshot this provisioner does not keep.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, displacedDeleter(resource));
    }

    private static Integer parsePrecedence(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Precedence must be an integer for AWS::Cognito::UserPoolGroup, got: " + value, e);
        }
    }
}
