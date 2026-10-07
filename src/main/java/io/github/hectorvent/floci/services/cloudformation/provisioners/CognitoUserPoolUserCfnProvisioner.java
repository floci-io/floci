package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Cognito::UserPoolUser}. {@code Ref} returns the
 * username Cognito reports: the template's {@code Username}, a generated name when it has none, or
 * the user's {@code sub} in an email-as-username pool. Every property is createOnly, so a changed
 * template replaces the user through {@link ReplacementCleanup}, except that a user with an explicit
 * {@code Username} that stays the same is refused as a custom-named replacement, as on AWS.
 */
@ApplicationScoped
public class CognitoUserPoolUserCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CognitoUserPoolUserCfnProvisioner.class);
    private static final String TYPE = "AWS::Cognito::UserPoolUser";
    private static final String CREATE_ONLY_ATTR = "__FlociCreateOnly";
    private static final String CREATE_ONLY_PRIOR_ATTR = "__FlociCreateOnlyPrior";
    /**
     * Physical id to pool id for the current user and every user still owed a delete, so a displaced
     * user is deleted in its own pool, not the current one.
     */
    private static final String POOLS_ATTR = "__FlociCognitoUserPools";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitoService cognitoService;

    @Inject
    public CognitoUserPoolUserCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        JsonNode resolved = props == null ? MAPPER.createObjectNode() : ctx.engine().resolveNode(props);
        String userPoolId = text(resolved, "UserPoolId");
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new AwsException("ValidationError", "AWS::Cognito::UserPoolUser requires UserPoolId", 400);
        }

        JsonNode prior = priorCreateOnly(attributesBefore);
        if (ctx.isUpdate() && resolved.equals(prior)) {
            ReplacementCleanup.record(r, ctx, attributesBefore);
            return;
        }
        String username = text(resolved, "Username");
        boolean customNamed = username != null && !username.isBlank();
        if (ctx.isUpdate() && customNamed && prior != null && username.equals(text(prior, "Username"))) {
            throw new AwsException("ValidationError",
                    "CloudFormation cannot update a stack when a custom-named resource requires "
                            + "replacing. Rename " + username + " and update the stack again.", 400);
        }

        Map<String, String> userAttributes = new LinkedHashMap<>();
        for (JsonNode attribute : resolved.path("UserAttributes")) {
            userAttributes.put(attribute.path("Name").asText(), attribute.path("Value").asText());
        }
        // DesiredDeliveryMediums only reaches a message transport, which AdminCreateUser does not
        // drive here. ValidationData and ClientMetadata are not passed to the PreSignUp trigger,
        // which sees empty maps. All three only take part in the createOnly record.
        String name = customNamed ? username : ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        String messageAction = text(resolved, "MessageAction");
        if ("RESEND".equalsIgnoreCase(messageAction)) {
            // RESEND hands back an existing user the stack never created, and AWS refuses it as a name conflict.
            CognitoUser existing = cognitoService.adminGetUser(userPoolId, name);
            throw new AwsException("AlreadyExists", "Resource of type 'AWS::Cognito::UserPoolUser' with identifier '"
                    + userPoolId + "|" + existing.getUsername() + "' already exists.", 400);
        }
        CognitoUser user = cognitoService.adminCreateUser(userPoolId, name, userAttributes, null, messageAction,
                Boolean.parseBoolean(text(resolved, "ForceAliasCreation")));

        r.setPhysicalId(user.getUsername());
        r.getAttributes().put(CREATE_ONLY_ATTR, resolved.toString());
        ReplacementCleanup.record(r, ctx, attributesBefore);
        recordPool(r, attributesBefore, userPoolId);
        if (ctx.isUpdate() && !ctx.priorPhysicalId().equals(r.getPhysicalId())) {
            r.getAttributes().put(CREATE_ONLY_PRIOR_ATTR, attributesBefore.getOrDefault(CREATE_ONLY_ATTR, ""));
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        deleteUser(poolFor(resource.getAttributes(), resource.getPhysicalId()), resource.getPhysicalId());
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
                deleteUser(poolFor(resource.getAttributes(), physicalId), physicalId));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CREATE_ONLY_PRIOR_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * Deletes the replacement and points the resource back at the prior user, with its createOnly
     * record. A user is never changed in place, so an update that replaced nothing, including a
     * refused custom-named one, has nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String prior = resource.getAttributes().remove(CREATE_ONLY_PRIOR_ATTR);
        if (prior != null) {
            if (prior.isEmpty()) {
                resource.getAttributes().remove(CREATE_ONLY_ATTR);
            } else {
                resource.getAttributes().put(CREATE_ONLY_ATTR, prior);
            }
        }
        ReplacementCleanup.rollback(resource, (type, physicalId, region) ->
                deleteUser(poolFor(resource.getAttributes(), physicalId), physicalId));
        return true;
    }

    private void deleteUser(String userPoolId, String username) {
        if (userPoolId == null || userPoolId.isBlank() || username == null || username.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("Cognito user", username, () -> cognitoService.adminDeleteUser(userPoolId, username),
                "UserNotFoundException", "ResourceNotFoundException");
    }

    /**
     * Keeps the pool of the user the resource now names and of every user still owed a delete, from
     * this update or an earlier one, so each is deleted in its own pool. Every other entry is
     * dropped, so the record stays bounded across repeated replacements. Call after
     * {@link ReplacementCleanup#record}, so the user this update displaced is already owed.
     */
    private static void recordPool(StackResource r, Map<String, String> attributesBefore, String userPoolId) {
        ObjectNode prior = pools(attributesBefore);
        ObjectNode pools = MAPPER.createObjectNode();
        for (String owed : ReplacementCleanup.owedPhysicalIds(r)) {
            JsonNode pool = prior.get(owed);
            if (pool != null) {
                pools.set(owed, pool);
            }
        }
        pools.put(r.getPhysicalId(), userPoolId);
        r.getAttributes().put(POOLS_ATTR, pools.toString());
    }

    private static String poolFor(Map<String, String> attributes, String physicalId) {
        JsonNode pool = physicalId == null ? null : pools(attributes).get(physicalId);
        return pool != null && pool.isTextual() ? pool.textValue() : null;
    }

    private static ObjectNode pools(Map<String, String> attributes) {
        String recorded = attributes.get(POOLS_ATTR);
        if (recorded != null) {
            try {
                if (MAPPER.readTree(recorded) instanceof ObjectNode pools) {
                    return pools;
                }
            } catch (JsonProcessingException e) {
                LOG.warnv("Unreadable Cognito user pool record, ignoring it: {0}", e.getMessage());
            }
        }
        return MAPPER.createObjectNode();
    }

    private static JsonNode priorCreateOnly(Map<String, String> attributesBefore) {
        String prior = attributesBefore.get(CREATE_ONLY_ATTR);
        if (prior == null) {
            return null;
        }
        try {
            return MAPPER.readTree(prior);
        } catch (JsonProcessingException e) {
            LOG.warnv("Unreadable Cognito user createOnly record, treating it as absent: {0}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
