package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Cognito::UserPoolUserToGroupAttachment}. {@code Ref}
 * returns {@code <UserPoolId>|<GroupName>|<Username>}, with the Username as the template gives it.
 *
 * <p>Every property is createOnly, so any change is a replacement: the new membership is added and
 * the old one is removed once the update commits, through {@link ReplacementCleanup}. The parts are
 * recorded per physical id, because a group name or username may contain {@code |}, and a membership
 * is removed from its recorded parts, as on AWS. Without a record, as for a Cloud Control delete of a
 * membership it did not create, the membership is removed from its id when the id splits into exactly
 * three parts. An id of fewer parts names no membership, so the delete does nothing, and an id of more
 * parts is refused as ambiguous. Adding a user who is already in the group is refused as
 * AlreadyExists, and a change that keeps the Ref is refused as a custom-named replacement, as on AWS.
 */
@ApplicationScoped
public class CognitoUserPoolUserToGroupAttachmentCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CognitoUserPoolUserToGroupAttachmentCfnProvisioner.class);
    private static final String TYPE = "AWS::Cognito::UserPoolUserToGroupAttachment";
    /** Physical id to its [UserPoolId, GroupName, Username], because names may contain {@code |}. */
    private static final String MEMBERSHIPS_ATTR = "__FlociCognitoMemberships";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitoService cognitoService;

    @Inject
    public CognitoUserPoolUserToGroupAttachmentCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userPoolId = require(props, "UserPoolId", ctx);
        String groupName = require(props, "GroupName", ctx);
        String username = require(props, "Username", ctx);

        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String physicalId = userPoolId + "|" + groupName + "|" + username;
        ArrayNode membership = MAPPER.createArrayNode().add(userPoolId).add(groupName).add(username);
        if (ctx.reusesPriorEntity(physicalId)) {
            JsonNode prior = memberships(attributesBefore).get(physicalId);
            if (prior != null && !prior.equals(membership)) {
                throw new AwsException("ValidationError",
                        "CloudFormation cannot update a stack when a custom-named resource requires replacing. Rename "
                                + physicalId + " and update the stack again.", 400);
            }
        } else {
            String member = cognitoService.adminGetUser(userPoolId, username).getUsername();
            if (cognitoService.getGroup(userPoolId, groupName).getUserNames().contains(member)) {
                throw new AwsException("AlreadyExists",
                        "User with name " + member + " already exists in Group " + groupName + ".", 400);
            }
            cognitoService.adminAddUserToGroup(userPoolId, groupName, username);
        }
        r.setPhysicalId(physicalId);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        recordMembership(r, attributesBefore, membership);
    }

    private static String require(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + name, 400);
        }
        return value;
    }

    @Override
    public void delete(StackResource resource, String region) {
        removeMembership(resource.getAttributes(), resource.getPhysicalId());
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
        return ReplacementCleanup.complete(resource, (type, physicalId, region) ->
                removeMembership(resource.getAttributes(), physicalId));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ReplacementCleanup.rollback(resource, (type, physicalId, region) ->
                removeMembership(resource.getAttributes(), physicalId));
        // Every property is createOnly, so without a replacement nothing changed, including an
        // update refused as AlreadyExists or as a custom-named replacement.
        return true;
    }

    private void removeMembership(Map<String, String> attributes, String physicalId) {
        String[] parts = physicalId == null ? null : membershipParts(attributes, physicalId);
        if (parts == null) {
            return;
        }
        String userPoolId = parts[0];
        String groupName = parts[1];
        String username = parts[2];
        CfnDeletes.safeDelete("Cognito group membership", physicalId,
                () -> cognitoService.adminRemoveUserFromGroup(userPoolId, groupName, username),
                "UserNotFoundException", "ResourceNotFoundException");
    }

    /**
     * The [UserPoolId, GroupName, Username] of a membership: its recorded parts, else its id split on
     * {@code |}. Null when the id is not a membership id; refused when a name holding {@code |} makes
     * the split ambiguous.
     */
    private static String[] membershipParts(Map<String, String> attributes, String physicalId) {
        JsonNode recorded = memberships(attributes).get(physicalId);
        if (recorded != null && recorded.isArray() && recorded.size() == 3) {
            return new String[] {recorded.get(0).asText(), recorded.get(1).asText(), recorded.get(2).asText()};
        }
        String[] parts = physicalId.split("\\|", -1);
        if (parts.length < 3) {
            return null;
        }
        if (parts.length > 3) {
            throw new AwsException("InvalidRequest", "Cannot tell the GroupName and Username of " + physicalId
                    + " apart without the attachment's recorded properties, because one of them holds |.", 400);
        }
        return parts;
    }

    /**
     * Keeps the parts of the membership the resource now names and of every membership still owed a
     * delete, from this update or an earlier one, so each is removed from its own parts. Every other
     * entry is dropped, so the record stays bounded across repeated replacements. Call after
     * {@link ReplacementCleanup#record}, so the membership this update displaced is already owed.
     */
    private static void recordMembership(StackResource r, Map<String, String> attributesBefore, ArrayNode membership) {
        ObjectNode prior = memberships(attributesBefore);
        ObjectNode memberships = MAPPER.createObjectNode();
        for (String owed : ReplacementCleanup.owedPhysicalIds(r)) {
            JsonNode parts = prior.get(owed);
            if (parts != null) {
                memberships.set(owed, parts);
            }
        }
        memberships.set(r.getPhysicalId(), membership);
        r.getAttributes().put(MEMBERSHIPS_ATTR, memberships.toString());
    }

    private static ObjectNode memberships(Map<String, String> attributes) {
        String recorded = attributes.get(MEMBERSHIPS_ATTR);
        if (recorded != null) {
            try {
                if (MAPPER.readTree(recorded) instanceof ObjectNode memberships) {
                    return memberships;
                }
            } catch (JsonProcessingException e) {
                LOG.warnv("Unreadable Cognito group membership record, ignoring it: {0}", e.getMessage());
            }
        }
        return MAPPER.createObjectNode();
    }
}
