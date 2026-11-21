package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.verifiedpermissions.VerifiedPermissionsService;
import io.floci.aws.services.verifiedpermissions.model.EntityIdentifier;
import io.floci.aws.services.verifiedpermissions.model.Policy;
import io.floci.aws.services.verifiedpermissions.model.PolicyStore;
import io.floci.aws.services.verifiedpermissions.model.PolicyTemplate;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provisions {@code AWS::VerifiedPermissions::PolicyStore}, {@code AWS::VerifiedPermissions::PolicyTemplate}
 * and {@code AWS::VerifiedPermissions::Policy} over {@link VerifiedPermissionsService}.
 *
 * <p>A policy store answers {@code Ref} with its id and sets {@code Arn}, {@code PolicyStoreId} and
 * {@code EncryptionState}. Its {@code Schema} is put after the store exists, which is how the API
 * takes it. Every property of a store updates in place: a property the template drops goes back to
 * AWS's default (a dropped schema is cleared), and one the stack never declared is left as it stands.
 * {@code EncryptionSettings} applies at create only, since {@code UpdatePolicyStore} takes no encryption.
 *
 * <p>A template's and a policy's {@code Ref} is the registry primary identifier, the parts joined
 * by {@code |} in the schema's order: {@code PolicyStoreId|PolicyTemplateId} for a template, as the
 * CloudFormation Template Reference spells out, and {@code PolicyId|PolicyStoreId} for a policy,
 * whose schema lists the policy id first. {@code PolicyStoreId} is createOnly on both, so a change
 * to it creates the replacement in the new store and the displaced entity is deleted once the
 * update commits. Everything else updates in place; the service refuses what Verified Permissions
 * refuses, a changed effect or scope, a template-linked policy's definition, and the stack reports
 * that as the resource's failure.
 *
 * <p>A stack from before these types had a provisioner holds the dispatcher's stub for them, which
 * names nothing to update, so its update creates the entity.
 */
@ApplicationScoped
public class VerifiedPermissionsCfnProvisioner implements CfnResourceProvisioner {

    private static final String POLICY_STORE = "AWS::VerifiedPermissions::PolicyStore";
    private static final String POLICY_TEMPLATE = "AWS::VerifiedPermissions::PolicyTemplate";
    private static final String POLICY = "AWS::VerifiedPermissions::Policy";
    private static final String NOT_FOUND = "ResourceNotFoundException";
    private static final String EMPTY_SCHEMA = "{}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VerifiedPermissionsService verifiedPermissions;

    public VerifiedPermissionsCfnProvisioner(VerifiedPermissionsService verifiedPermissions) {
        this.verifiedPermissions = verifiedPermissions;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(POLICY_STORE, POLICY_TEMPLATE, POLICY);
    }

    @Override
    public Map<String, Set<String>> jsonAttributes() {
        return Map.of(POLICY_STORE, Set.of("EncryptionState"));
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case POLICY_STORE -> provisionPolicyStore(r, props, ctx);
            case POLICY_TEMPLATE -> provisionPolicyTemplate(r, props, ctx);
            case POLICY -> provisionPolicy(r, props, ctx);
            default -> throw new IllegalStateException(
                    "VerifiedPermissionsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionPolicyStore(StackResource r, JsonNode props, ProvisionContext ctx) {
        JsonNode validation = requiredObject(props, "ValidationSettings", r, ctx);
        String mode = required(ctx.engine().resolve(validation.path("Mode")), "ValidationSettings.Mode", r);
        String description = ctx.resolveOptional(props, "Description");
        JsonNode deletionProtection = optionalObject(props, "DeletionProtection", ctx);
        String deletionMode = deletionProtection == null
                ? null : blankToNull(ctx.engine().resolve(deletionProtection.path("Mode")));
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String schema = schema(props, ctx);
        String region = ctx.region();
        PropertyChanges changes = ctx.changes(POLICY_STORE, props);

        PolicyStore store;
        if (updatesInPlace(ctx, changes, r.getAttributes())) {
            String policyStoreId = ctx.priorPhysicalId();
            // The schema goes first: it is the change Verified Permissions is likeliest to refuse,
            // and a refused one leaves the store as it was.
            if (schema != null) {
                putSchema(policyStoreId, schema, region);
            } else if (changes.kindOf("Schema") == PropertyChanges.Kind.DROPPED) {
                putSchema(policyStoreId, EMPTY_SCHEMA, region);
            }
            ObjectNode request = settingsRequest(mode);
            request.put("policyStoreId", policyStoreId);
            putOrClear(request, "description", description, changes.kindOf("Description"), "");
            putOrClear(request, "deletionProtection", deletionMode, changes.kindOf("DeletionProtection"), "DISABLED");
            store = verifiedPermissions.updatePolicyStore(request, region);
            reconcileTags(store.arn(), tags, region);
        } else {
            store = createPolicyStore(mode, description, deletionMode, tags, schema, props, ctx);
        }

        r.setPhysicalId(store.policyStoreId());
        r.getAttributes().put("Arn", store.arn());
        r.getAttributes().put("PolicyStoreId", store.policyStoreId());
        r.getAttributes().put("EncryptionState", encryptionState(store).toString());
    }

    /**
     * Creates the store, then puts its schema. Deletion protection goes on once the schema is in, so
     * a store whose schema is refused can still be removed rather than left standing beside the failure.
     */
    private PolicyStore createPolicyStore(String mode, String description, String deletionMode,
                                          Map<String, String> tags, String schema, JsonNode props,
                                          ProvisionContext ctx) {
        String region = ctx.region();
        boolean protectAfterSchema = schema != null && "ENABLED".equals(deletionMode);
        ObjectNode request = settingsRequest(mode);
        if (description != null) {
            request.put("description", description);
        }
        if (deletionMode != null && !protectAfterSchema) {
            request.put("deletionProtection", deletionMode);
        }
        JsonNode encryption = optionalObject(props, "EncryptionSettings", ctx);
        if (encryption != null) {
            request.set("encryptionSettings", encryptionSettings(encryption, ctx));
        }
        if (!tags.isEmpty()) {
            ObjectNode tagNode = request.putObject("tags");
            tags.forEach(tagNode::put);
        }
        PolicyStore store = verifiedPermissions.createPolicyStore(request, region);
        if (schema == null) {
            return store;
        }
        try {
            putSchema(store.policyStoreId(), schema, region);
        } catch (RuntimeException e) {
            try {
                verifiedPermissions.deletePolicyStore(store.policyStoreId(), region);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        if (!protectAfterSchema) {
            return store;
        }
        ObjectNode protect = settingsRequest(mode);
        protect.put("policyStoreId", store.policyStoreId());
        protect.put("deletionProtection", deletionMode);
        return verifiedPermissions.updatePolicyStore(protect, region);
    }

    private void provisionPolicyTemplate(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String policyStoreId = required(ctx.resolveOptional(props, "PolicyStoreId"), "PolicyStoreId", r);
        String statement = required(ctx.resolveOptional(props, "Statement"), "Statement", r);
        String description = ctx.resolveOptional(props, "Description");
        String name = ctx.resolveOptional(props, "Name");
        String region = ctx.region();
        PropertyChanges changes = ctx.changes(POLICY_TEMPLATE, props);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("policyStoreId", policyStoreId);
        request.put("statement", statement);
        putOrClear(request, "description", description, changes.kindOf("Description"), "");
        putOrClear(request, "name", name, changes.kindOf("Name"), "");
        PolicyTemplate template;
        if (updatesInPlace(ctx, changes, attributesBefore)) {
            request.put("policyTemplateId", idParts(ctx.priorPhysicalId())[1]);
            template = verifiedPermissions.updatePolicyTemplate(request, region);
        } else {
            template = verifiedPermissions.createPolicyTemplate(request, region);
        }

        r.setPhysicalId(template.policyStoreId() + "|" + template.policyTemplateId());
        r.getAttributes().put("PolicyTemplateId", template.policyTemplateId());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String policyStoreId = required(ctx.resolveOptional(props, "PolicyStoreId"), "PolicyStoreId", r);
        JsonNode definition = requiredObject(props, "Definition", r, ctx);
        String name = ctx.resolveOptional(props, "Name");
        String region = ctx.region();
        PropertyChanges changes = ctx.changes(POLICY, props);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("policyStoreId", policyStoreId);
        request.set("definition", CfnApiShapeConverter.convert(definition, CfnApiShapeConverter.OMIT_NULL_PROPERTIES));
        putOrClear(request, "name", name, changes.kindOf("Name"), "");
        Policy policy;
        if (updatesInPlace(ctx, changes, attributesBefore)) {
            String policyId = idParts(ctx.priorPhysicalId())[0];
            Policy current = verifiedPermissions.getPolicy(policyStoreId, policyId, region);
            if ("TEMPLATE_LINKED".equals(current.policyType())) {
                // Verified Permissions takes no update of a template-linked policy, and the Template
                // Reference says the same of its definition: one that stands unchanged is left alone.
                if (!sameLinkedPolicy(current, request, name)) {
                    throw new AwsException("ValidationError", "Updating template-linked policy " + policyId
                            + " is not supported.", 400);
                }
                policy = current;
            } else {
                // The definition is declared whole, so a description it no longer carries is cleared,
                // where the API on its own keeps the one the policy has.
                if (request.path("definition").path("static") instanceof ObjectNode body
                        && !body.has("description") && current.description() != null) {
                    body.put("description", "");
                }
                request.put("policyId", policyId);
                policy = verifiedPermissions.updatePolicy(request, region);
            }
        } else {
            policy = verifiedPermissions.createPolicy(request, region);
        }

        r.setPhysicalId(policy.policyId() + "|" + policy.policyStoreId());
        r.getAttributes().put("PolicyId", policy.policyId());
        r.getAttributes().put("PolicyType", policy.policyType());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Deletes the store, template or policy the physical id names. A store that is already gone
     * deletes silently in the service; one whose deletion protection is on fails, as on AWS. A
     * template or policy whose store is gone went with it.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case POLICY_STORE -> verifiedPermissions.deletePolicyStore(physicalId, region);
            case POLICY_TEMPLATE -> {
                String[] ids = idParts(physicalId);
                CfnDeletes.safeDelete("Verified Permissions policy template", physicalId,
                        () -> verifiedPermissions.deletePolicyTemplate(ids[0], ids[1], region), NOT_FOUND);
            }
            case POLICY -> {
                String[] ids = idParts(physicalId);
                CfnDeletes.safeDelete("Verified Permissions policy", physicalId,
                        () -> verifiedPermissions.deletePolicy(ids[1], ids[0], region), NOT_FOUND);
            }
            default -> throw new IllegalStateException(
                    "VerifiedPermissionsCfnProvisioner cannot delete " + resourceType);
        }
    }

    /** A policy's id puts the policy first, so its prefix never names the store it belongs to. */
    @Override
    public boolean idPrefixCanNameParent(String resourceType) {
        return !POLICY.equals(resourceType);
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

    /**
     * Whether this update changes the entity the prior id names in place: not a create, not a
     * replacement, and not a takeover of the dispatcher's stub, which names nothing.
     */
    private static boolean updatesInPlace(ProvisionContext ctx, PropertyChanges changes,
                                          Map<String, String> attributesBefore) {
        return ctx.isUpdate() && !changes.requiresReplacement()
                && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
    }

    /**
     * Sets {@code field} to the template's value, to {@code cleared} when the template dropped the
     * property, and leaves it out when the stack never declared it, so the service keeps what it has.
     */
    private static void putOrClear(ObjectNode request, String field, String value, PropertyChanges.Kind kind,
                                   String cleared) {
        if (value != null) {
            request.put(field, value);
        } else if (kind == PropertyChanges.Kind.DROPPED) {
            request.put(field, cleared);
        }
    }

    private static ObjectNode settingsRequest(String mode) {
        ObjectNode request = MAPPER.createObjectNode();
        request.putObject("validationSettings").put("mode", mode);
        return request;
    }

    /** {@code Schema.CedarJson} as the API takes it, or null when the template declares no schema. */
    private static String schema(JsonNode props, ProvisionContext ctx) {
        JsonNode schema = optionalObject(props, "Schema", ctx);
        if (schema == null || !schema.has("CedarJson") || schema.get("CedarJson").isNull()) {
            return null;
        }
        JsonNode cedarJson = schema.get("CedarJson");
        // The schema is a JSON document given as a string; a template that writes it as an object
        // is taken as the document itself.
        String value = cedarJson.isObject() ? cedarJson.toString() : ctx.engine().resolve(cedarJson);
        return blankToNull(value);
    }

    private void putSchema(String policyStoreId, String cedarJson, String region) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("policyStoreId", policyStoreId);
        request.putObject("definition").put("cedarJson", cedarJson);
        verifiedPermissions.putSchema(request, region);
    }

    /** {@code EncryptionSettings}, a union of {@code Default} and {@code KmsEncryptionSettings}, as the API takes it. */
    private static ObjectNode encryptionSettings(JsonNode settings, ProvisionContext ctx) {
        ObjectNode request = MAPPER.createObjectNode();
        JsonNode kms = settings.get("KmsEncryptionSettings");
        if (kms == null || kms.isNull()) {
            request.putObject("default");
            return request;
        }
        ObjectNode kmsRequest = request.putObject("kmsEncryptionSettings");
        kmsRequest.put("key", ctx.engine().resolve(kms.path("Key")));
        JsonNode context = kms.get("EncryptionContext");
        if (context != null && context.isObject()) {
            ObjectNode contextRequest = kmsRequest.putObject("encryptionContext");
            context.fields().forEachRemaining(entry -> contextRequest.put(entry.getKey(), ctx.engine().resolve(entry.getValue())));
        }
        return request;
    }

    /** The store's {@code EncryptionState}, in the shape the registry schema gives the attribute. */
    private static ObjectNode encryptionState(PolicyStore store) {
        ObjectNode state = MAPPER.createObjectNode();
        if (store.encryptionKeyArn() == null) {
            state.putObject("Default");
            return state;
        }
        ObjectNode kms = state.putObject("KmsEncryptionState");
        kms.put("Key", store.encryptionKeyArn());
        ObjectNode context = kms.putObject("EncryptionContext");
        store.encryptionContext().forEach(context::put);
        return state;
    }

    private static boolean sameLinkedPolicy(Policy current, ObjectNode request, String name) {
        JsonNode linked = request.path("definition").path("templateLinked");
        return linked.isObject()
                && linked.path("policyTemplateId").asText().equals(current.policyTemplateId())
                && Objects.equals(current.principal(), entity(linked.get("principal")))
                && Objects.equals(current.resource(), entity(linked.get("resource")))
                && Objects.equals(current.name(), blankToNull(name));
    }

    private static EntityIdentifier entity(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new EntityIdentifier(node.path("entityType").asText(), node.path("entityId").asText());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Drives the store's tags to the template's set: dropped keys are untagged, the rest written. */
    private void reconcileTags(String arn, Map<String, String> desired, String region) {
        List<String> stale = ProvisionContext.staleTagKeys(verifiedPermissions.listTags(arn, region), desired);
        if (!stale.isEmpty()) {
            verifiedPermissions.untagResource(arn, stale, region);
        }
        if (!desired.isEmpty()) {
            verifiedPermissions.tagResource(arn, desired, region);
        }
    }

    /**
     * The two ids a physical id joins with {@code |}: {@code PolicyStoreId}, then
     * {@code PolicyTemplateId} for a template, and {@code PolicyId}, then {@code PolicyStoreId} for a policy.
     */
    private static String[] idParts(String physicalId) {
        int bar = physicalId.indexOf('|');
        return new String[] {physicalId.substring(0, bar), physicalId.substring(bar + 1)};
    }

    private static JsonNode requiredObject(JsonNode props, String property, StackResource r, ProvisionContext ctx) {
        JsonNode node = optionalObject(props, property, ctx);
        if (node == null) {
            throw new AwsException("ValidationError", "Property " + property + " of " + r.getLogicalId()
                    + " (" + r.getResourceType() + ") is required.", 400);
        }
        return node;
    }

    private static JsonNode optionalObject(JsonNode props, String property, ProvisionContext ctx) {
        if (props == null || !props.has(property) || props.get(property).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(property));
        return resolved == null || resolved.isNull() || resolved.isMissingNode() ? null : resolved;
    }

    private static String required(String value, String property, StackResource r) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", "Property " + property + " of " + r.getLogicalId()
                    + " (" + r.getResourceType() + ") is required.", 400);
        }
        return value;
    }
}
