package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotAuthorizerService;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Provisions {@code AWS::IoT::Authorizer}. Things, policies and topic rules belong to
 * {@link IotCfnProvisioner}; this class takes only the authorizer service so the test fixture can
 * wire it from that service.
 *
 * <p>The authorizer name is the physical id and {@code Ref}, generated when the template gives none
 * and kept across updates; {@code Fn::GetAtt Arn} is the authorizer ARN. The name and
 * {@code SigningDisabled} are create-only. An update that keeps both changes the authorizer in
 * place through UpdateAuthorizer, sending only the properties the template declares, so a property
 * dropped from the template keeps its stored value, reconciles the tags, and keeps what it replaces
 * on the resource so a failed stack update can put it back. An in-place update whose tag reconcile
 * fails puts the authorizer back itself before reporting the failure, because the resource the
 * stack restores never carried that snapshot. An update that changes either, or drops
 * an explicit name, creates the new authorizer and leaves the displaced one to the
 * {@link ReplacementCleanup} record; a replacement that would keep an explicit name is refused, as
 * CloudFormation refuses it for any custom-named resource. Declaring the generated name as an
 * explicit name is refused the same way, because adding the create-only name is a replacement that
 * would keep it. A stack created before this provisioner existed holds the dispatcher's stub, which
 * names no authorizer, so its first update creates the authorizer as a first deploy would. A delete
 * deactivates the authorizer first, because the API refuses to delete an ACTIVE one.
 */
@ApplicationScoped
public class IotAuthorizerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IoT::Authorizer";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int NAME_MAX_LENGTH = 128;
    /**
     * Records whether the name came from the template or was generated, so a later update can tell
     * an explicit name being dropped, which replaces the authorizer on AWS, from an unnamed
     * authorizer keeping the name it was given. Read back off the stored attributes.
     */
    private static final String NAME_MODE_ATTR = "FlociAuthorizerNameMode";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";

    private final IotAuthorizerService authorizerService;

    public IotAuthorizerCfnProvisioner(IotAuthorizerService authorizerService) {
        this.authorizerService = authorizerService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String functionArn = resolvePresent(props, "AuthorizerFunctionArn", ctx);
        if (functionArn == null) {
            // The registry schema's required key, checked before the handler makes any call.
            throw new AwsException("InvalidRequest",
                    "Model validation failed (#: required key [AuthorizerFunctionArn] not found)", 400);
        }
        String explicitName = resolvePresent(props, "AuthorizerName", ctx);
        boolean hasExplicitName = explicitName != null;
        String signingDisabled = resolveBoolean(props, "SigningDisabled", ctx);
        ObjectNode declared = MAPPER.createObjectNode().put("authorizerFunctionArn", functionArn);
        putIfPresent(declared, "status", resolvePresent(props, "Status", ctx));
        putIfPresent(declared, "tokenKeyName", resolvePresent(props, "TokenKeyName", ctx));
        String caching = resolveBoolean(props, "EnableCachingForHttp", ctx);
        if (caching != null) {
            declared.put("enableCachingForHttp", Boolean.parseBoolean(caching));
        }
        JsonNode keys = props == null ? null : ctx.engine().resolveNode(props.get("TokenSigningPublicKeys"));
        if (keys != null && keys.isObject()) {
            ObjectNode present = declared.putObject("tokenSigningPublicKeys");
            // ponytail: an intrinsic resolving to "" counts as AWS::NoValue and is dropped,
            // so an empty PEM chosen by Fn::If is not refused; a literal "" still is.
            for (Map.Entry<String, JsonNode> key : keys.properties()) {
                boolean literal = props.path("TokenSigningPublicKeys").path(key.getKey()).isTextual();
                if (literal || !"".equals(key.getValue().textValue())) {
                    present.set(key.getKey(), key.getValue());
                }
            }
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        // A stack from before this type had a provisioner holds the dispatcher's stub here, which
        // names no authorizer and never recorded a name mode, so the authorizer is created as on a first deploy.
        boolean updatesAuthorizer = ctx.isUpdate() && attributesBefore.containsKey(NAME_MODE_ATTR);
        IotAuthorizer prior = updatesAuthorizer
                ? authorizerService.describeAuthorizer(ctx.priorPhysicalId(), ctx.region())
                : null;
        // ponytail: compared with the stored flag, so an absent SigningDisabled reads as its API default false.
        boolean signingChanged = prior != null && prior.isSigningDisabled() != Boolean.parseBoolean(signingDisabled);
        boolean nameWasGenerated = NAME_MODE_GENERATED.equals(attributesBefore.get(NAME_MODE_ATTR));
        if ((signingChanged || nameWasGenerated) && hasExplicitName && explicitName.equals(ctx.priorPhysicalId())) {
            throw new AwsException("ValidationError",
                    "CloudFormation cannot update a stack when a custom-named resource requires "
                            + "replacing. Rename " + explicitName + " and update the stack again.", 400);
        }
        String name = physicalName(r, ctx, explicitName, updatesAuthorizer, signingChanged);

        IotAuthorizer authorizer;
        if (updatesAuthorizer && ctx.reusesPriorEntity(name)) {
            snapshotBeforeUpdate(r, prior, ctx.region());
            authorizer = authorizerService.updateAuthorizer(name, declared, ctx.region());
            try {
                reconcileTags(prior, tags);
            } catch (RuntimeException failure) {
                unwind(r, name, failure);
                throw failure;
            }
        } else {
            if (signingDisabled != null) {
                declared.put("signingDisabled", Boolean.parseBoolean(signingDisabled));
            }
            if (!tags.isEmpty()) {
                ArrayNode tagList = declared.putArray("tags");
                tags.forEach((key, value) -> tagList.addObject().put("Key", key).put("Value", value));
            }
            authorizer = authorizerService.createAuthorizer(name, declared, ctx.region());
        }
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", authorizer.getAuthorizerArn());
        r.getAttributes().put(NAME_MODE_ATTR, hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * An in-place update that changed the authorizer and then failed on its tags undoes itself.
     * CloudFormationService puts the resource the stack held before the attempt back in its place,
     * and that object never carried the snapshot taken here, so it marks the resource restored and
     * the rollback walker skips {@link #rollbackUpdate}: without this the stack reports a completed
     * rollback while the changed authorizer is still live. The stack must report the original
     * failure, so an unwind that cannot restore is attached to it and recorded as a rollback
     * failure, which ends the stack in UPDATE_ROLLBACK_FAILED. The unwind repeats the tag calls the
     * update just made, so it can come back carrying the very exception that interrupted the
     * update; a throwable cannot be suppressed under itself.
     */
    private void unwind(StackResource r, String name, RuntimeException failure) {
        try {
            rollbackUpdate(r);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
        } catch (RuntimeException unwindFailure) {
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                    "Could not roll back the update of authorizer " + name + ": " + unwindFailure.getMessage());
            if (unwindFailure != failure) {
                failure.addSuppressed(unwindFailure);
            }
        }
    }

    /**
     * The resolved property, or null when it is absent or an intrinsic resolved to AWS::NoValue,
     * which the engine yields as an empty string. A literal string, and an intrinsic result that is
     * only whitespace, is kept as written.
     */
    private static String resolvePresent(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        boolean literal = props != null && props.path(name).isTextual();
        return literal || (value != null && !value.isEmpty()) ? value : null;
    }

    /** A present boolean property, refused unless it is true or false rather than read as false. */
    private static String resolveBoolean(JsonNode props, String name, ProvisionContext ctx) {
        String value = resolvePresent(props, name, ctx);
        if (value != null && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("ValidationError", TYPE + " " + name + " must be true or false, got " + value, 400);
        }
        return value;
    }

    private static void putIfPresent(ObjectNode body, String field, String value) {
        if (value != null) {
            body.put(field, value);
        }
    }

    /**
     * The template's name when it gives one; otherwise the generated name the authorizer already
     * has, unless the previous name was explicit or signing changed, since either replaces the
     * authorizer on AWS, or the prior resource names no authorizer; and a fresh generated name
     * otherwise. Logical ids only hold characters the IoT name rule allows, and the generator
     * truncates to its limit.
     */
    private static String physicalName(StackResource r, ProvisionContext ctx, String explicitName,
                                       boolean updatesAuthorizer, boolean signingChanged) {
        if (explicitName != null) {
            return explicitName;
        }
        boolean explicitNameRemoved = ctx.isUpdate()
                && NAME_MODE_EXPLICIT.equals(r.getAttributes().get(NAME_MODE_ATTR));
        if (updatesAuthorizer && !explicitNameRemoved && !signingChanged) {
            return ctx.priorPhysicalId();
        }
        return generatedName(r.getLogicalId());
    }

    /** CloudFormation names an unnamed authorizer {@code <LogicalId>_<12 chars>}, with no stack prefix. */
    private static String generatedName(String logicalId) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int prefixMax = NAME_MAX_LENGTH - 1 - suffix.length();
        String prefix = logicalId.length() > prefixMax ? logicalId.substring(0, prefixMax) : logicalId;
        return prefix + "_" + suffix;
    }

    private void reconcileTags(IotAuthorizer authorizer, Map<String, String> desired) {
        List<String> stale = ProvisionContext.staleTagKeys(authorizer.getTags(), desired);
        if (!stale.isEmpty()) {
            authorizerService.untagResource(authorizer.getAuthorizerArn(), stale);
        }
        if (!desired.isEmpty()) {
            authorizerService.tagResource(authorizer.getAuthorizerArn(), desired);
        }
    }

    /**
     * Keeps what an in-place update can change, as the UpdateAuthorizer body that puts it back,
     * with the tags beside it, so {@link #rollbackUpdate} can restore them.
     */
    private static void snapshotBeforeUpdate(StackResource r, IotAuthorizer current, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", current.getAuthorizerName());
        ObjectNode restore = snapshot.putObject("body")
                .put("authorizerFunctionArn", current.getAuthorizerFunctionArn())
                .put("status", current.getStatus())
                .put("enableCachingForHttp", current.isEnableCachingForHttp());
        putIfPresent(restore, "tokenKeyName", current.getTokenKeyName());
        // ponytail: UpdateAuthorizer merges keys by name, so a key the update added survives a rollback.
        if (current.getTokenSigningPublicKeys() != null) {
            restore.set("tokenSigningPublicKeys", MAPPER.valueToTree(current.getTokenSigningPublicKeys()));
        }
        snapshot.set("tags", MAPPER.valueToTree(current.getTags()));
        r.getAttributes().put(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    /** Deactivates, then deletes, as the AWS handler does; an authorizer already gone counts as deleted. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("IoT authorizer", physicalId, () -> {
            authorizerService.updateAuthorizer(physicalId,
                    MAPPER.createObjectNode().put("status", "INACTIVE"), region);
            authorizerService.deleteAuthorizer(physicalId, region);
        }, "ResourceNotFoundException");
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
        resource.getAttributes().remove(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record and an in-place update from the snapshot
     * taken before it. With neither on the resource the provision failed before it changed
     * anything, so there is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        String raw = resource.getAttributes().get(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the authorizer update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String region = snapshot.path("region").asText();
        IotAuthorizer restored = authorizerService.updateAuthorizer(snapshot.path("name").asText(),
                snapshot.path("body"), region);
        Map<String, String> tags = new LinkedHashMap<>();
        snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(restored, tags);
        resource.getAttributes().remove(CfnRollback.AUTHORIZER_UPDATE_SNAPSHOT_ATTR);
        return true;
    }
}
