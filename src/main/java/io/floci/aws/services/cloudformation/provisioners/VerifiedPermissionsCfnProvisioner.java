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
import java.util.Iterator;
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
 * takes it, and a template that drops the schema clears it, since the whole template is the desired
 * state. Every property of a store updates in place.
 *
 * <p>A template's and a policy's {@code Ref} is the registry primary identifier, the parts joined
 * by {@code |} in the schema's order: {@code PolicyStoreId|PolicyTemplateId} for a template, as the
 * CloudFormation Template Reference spells out, and {@code PolicyId|PolicyStoreId} for a policy,
 * whose schema lists the policy id first. {@code PolicyStoreId} is createOnly on both, so a change
 * to it creates the replacement in the new store and the displaced entity is deleted once the
 * update commits. Everything else updates in place; the service refuses what Verified Permissions
 * refuses, a changed effect or scope, a template-linked policy's definition, and the stack reports
 * that as the resource's failure.
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
        String deletionMode = deletionProtection == null ? null : ctx.engine().resolve(deletionProtection.path("Mode"));
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String schema = schema(props, ctx);
        String region = ctx.region();

        ObjectNode request = MAPPER.createObjectNode();
        request.putObject("validationSettings").put("mode", mode);
        request.put("description", description == null ? "" : description);
        request.put("deletionProtection", deletionMode == null || deletionMode.isBlank() ? "DISABLED" : deletionMode);
        PolicyStore store;
        if (ctx.isUpdate()) {
            request.put("policyStoreId", ctx.priorPhysicalId());
            PolicyStore current = verifiedPermissions.getPolicyStore(ctx.priorPhysicalId(), region);
            if (schema != null) {
                verifiedPermissions.putSchema(schemaRequest(current.policyStoreId(), schema), region);
            } else if (current.schema() != null) {
                verifiedPermissions.putSchema(schemaRequest(current.policyStoreId(), EMPTY_SCHEMA), region);
            }
            store = verifiedPermissions.updatePolicyStore(request, region);
            reconcileTags(store.arn(), tags, region);
        } else {
            JsonNode encryption = optionalObject(props, "EncryptionSettings", ctx);
            if (encryption != null) {
                request.set("encryptionSettings", encryptionSettings(encryption, ctx));
            }
            if (!tags.isEmpty()) {
                ObjectNode tagNode = request.putObject("tags");
                tags.forEach(tagNode::put);
            }
            store = verifiedPermissions.createPolicyStore(request, region);
            if (schema != null) {
                try {
                    verifiedPermissions.putSchema(schemaRequest(store.policyStoreId(), schema), region);
                } catch (RuntimeException e) {
                    // A store the stack could not finish is not left standing beside the failure.
                    verifiedPermissions.deletePolicyStore(store.policyStoreId(), region);
                    throw e;
                }
            }
        }

        r.setPhysicalId(store.policyStoreId());
        r.getAttributes().put("Arn", store.arn());
        r.getAttributes().put("PolicyStoreId", store.policyStoreId());
        r.getAttributes().put("EncryptionState", encryptionState(store).toString());
    }

    private void provisionPolicyTemplate(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String policyStoreId = required(ctx.resolveOptional(props, "PolicyStoreId"), "PolicyStoreId", r);
        String statement = required(ctx.resolveOptional(props, "Statement"), "Statement", r);
        String description = ctx.resolveOptional(props, "Description");
        String name = ctx.resolveOptional(props, "Name");
        String region = ctx.region();

        ObjectNode request = MAPPER.createObjectNode();
        request.put("policyStoreId", policyStoreId);
        request.put("statement", statement);
        request.put("description", description == null ? "" : description);
        request.put("name", name == null ? "" : name);
        PolicyTemplate template;
        if (ctx.isUpdate() && policyStoreId.equals(storeOf(ctx.priorPhysicalId()))) {
            request.put("policyTemplateId", entityOf(ctx.priorPhysicalId()));
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

        ObjectNode request = MAPPER.createObjectNode();
        request.put("policyStoreId", policyStoreId);
        request.set("definition", lowerCamelKeys(definition));
        request.put("name", name == null ? "" : name);
        Policy policy;
        if (ctx.isUpdate() && policyStoreId.equals(storeOfPolicy(ctx.priorPhysicalId()))) {
            String policyId = entityOfPolicy(ctx.priorPhysicalId());
            Policy current = verifiedPermissions.getPolicy(policyStoreId, policyId, region);
            if ("TEMPLATE_LINKED".equals(current.policyType())) {
                // Verified Permissions takes no update of a template-linked policy, and the Template
                // Reference says the same of its definition: one that stands unchanged is left alone.
                if (!sameLinkedPolicy(current, request)) {
                    throw new AwsException("ValidationError", "Updating template-linked policy " + policyId
                            + " is not supported.", 400);
                }
                policy = current;
            } else {
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
            case POLICY_TEMPLATE -> CfnDeletes.safeDelete("Verified Permissions policy template", physicalId,
                    () -> verifiedPermissions.deletePolicyTemplate(storeOf(physicalId), entityOf(physicalId), region),
                    NOT_FOUND);
            case POLICY -> CfnDeletes.safeDelete("Verified Permissions policy", physicalId,
                    () -> verifiedPermissions.deletePolicy(storeOfPolicy(physicalId), entityOfPolicy(physicalId), region),
                    NOT_FOUND);
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
        return value == null || value.isBlank() ? null : value;
    }

    private static ObjectNode schemaRequest(String policyStoreId, String cedarJson) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("policyStoreId", policyStoreId);
        request.putObject("definition").put("cedarJson", cedarJson);
        return request;
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

    private static boolean sameLinkedPolicy(Policy current, ObjectNode request) {
        JsonNode linked = request.path("definition").path("templateLinked");
        return linked.isObject()
                && linked.path("policyTemplateId").asText().equals(current.policyTemplateId())
                && Objects.equals(current.principal(), entity(linked.get("principal")))
                && Objects.equals(current.resource(), entity(linked.get("resource")))
                && Objects.equals(current.name(), blankToNull(request.path("name").asText()));
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
     * The template's {@code Definition} in the API's spelling: the same tree with every key's first
     * letter lowered, {@code Static.Statement} as {@code static.statement}, intrinsics resolved.
     */
    private static JsonNode lowerCamelKeys(JsonNode node) {
        if (!node.isObject()) {
            return node;
        }
        ObjectNode out = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            out.set(Character.toLowerCase(key.charAt(0)) + key.substring(1), lowerCamelKeys(field.getValue()));
        }
        return out;
    }

    private static String storeOf(String templatePhysicalId) {
        return templatePhysicalId.substring(0, templatePhysicalId.indexOf('|'));
    }

    private static String entityOf(String templatePhysicalId) {
        return templatePhysicalId.substring(templatePhysicalId.indexOf('|') + 1);
    }

    private static String storeOfPolicy(String policyPhysicalId) {
        return policyPhysicalId.substring(policyPhysicalId.indexOf('|') + 1);
    }

    private static String entityOfPolicy(String policyPhysicalId) {
        return policyPhysicalId.substring(0, policyPhysicalId.indexOf('|'));
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
