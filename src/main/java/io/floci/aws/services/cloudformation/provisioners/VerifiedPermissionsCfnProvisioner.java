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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * state. Every property of a store updates in place; a template or a policy is replaced when the
 * engine says the update replaces it.
 *
 * <p>A template answers {@code Ref} with {@code PolicyStoreId|PolicyTemplateId} and a policy with its
 * id alone, as the CloudFormation Template Reference spells each out. A policy's store is found from
 * the policy id, which Floci generates unique, and the id-only delete also takes Cloud Control's
 * {@code PolicyId|PolicyStoreId}. {@code PolicyStoreId} is createOnly on both, so a change to it
 * creates the replacement in the new store and the displaced entity is deleted once the update
 * commits. Everything else updates in place; the service refuses what Verified Permissions refuses,
 * a changed effect or scope, a template-linked policy's definition, and the stack reports that as the
 * resource's failure.
 */
@ApplicationScoped
public class VerifiedPermissionsCfnProvisioner implements CfnResourceProvisioner {

    private static final String POLICY_STORE = "AWS::VerifiedPermissions::PolicyStore";
    private static final String POLICY_TEMPLATE = "AWS::VerifiedPermissions::PolicyTemplate";
    private static final String POLICY = "AWS::VerifiedPermissions::Policy";
    private static final String NOT_FOUND = "ResourceNotFoundException";
    private static final String EMPTY_SCHEMA = "{}";
    private static final String SNAPSHOT_ATTR = CfnRollback.VERIFIED_PERMISSIONS_UPDATE_SNAPSHOT_ATTR;
    /** The tag keys the last deployment of a store wrote: the keys the stack owns. */
    static final String TAG_KEYS_ATTR = "__FlociVerifiedPermissionsTagKeys";
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

        ObjectNode request = MAPPER.createObjectNode();
        request.putObject("validationSettings").put("mode", mode);
        PropertyChanges changes = ctx.changes(POLICY_STORE, props);
        PolicyStore store;
        if (updatesInPlace(ctx, changes, r.getAttributes())) {
            putOrClear(request, "description", description, changes.kindOf("Description"), "");
            putOrClear(request, "deletionProtection", deletionMode, changes.kindOf("DeletionProtection"), "DISABLED");
            store = updatePolicyStore(r, props, ctx, changes, request, schema, tags);
        } else {
            if (description != null) {
                request.put("description", description);
            }
            store = createPolicyStore(r, props, ctx, request, schema, deletionMode, tags);
        }

        r.setPhysicalId(store.policyStoreId());
        r.getAttributes().put("Arn", store.arn());
        r.getAttributes().put("PolicyStoreId", store.policyStoreId());
        r.getAttributes().put("EncryptionState", encryptionState(store).toString());
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, tags);
    }

    /**
     * Creates the store, then puts its schema and only after that turns deletion protection on, so a
     * schema the Cedar sidecar refuses, or a protection the service refuses, leaves no store behind.
     */
    private PolicyStore createPolicyStore(StackResource r, JsonNode props, ProvisionContext ctx, ObjectNode request,
                                          String schema, String deletionMode, Map<String, String> tags) {
        String region = ctx.region();
        JsonNode encryption = optionalObject(props, "EncryptionSettings", ctx);
        if (encryption != null) {
            request.set("encryptionSettings", encryptionSettings(encryption, ctx));
        }
        if (!tags.isEmpty()) {
            ObjectNode tagNode = request.putObject("tags");
            tags.forEach(tagNode::put);
        }
        boolean protectAfterSchema = schema != null && deletionMode != null && !"DISABLED".equals(deletionMode);
        if (deletionMode != null && !protectAfterSchema) {
            request.put("deletionProtection", deletionMode);
        }
        r.getAttributes().remove(SNAPSHOT_ATTR);
        PolicyStore created = verifiedPermissions.createPolicyStore(request, region);
        String policyStoreId = created.policyStoreId();
        r.setPhysicalId(policyStoreId);
        PolicyStore[] finished = {created};
        CfnRollback.guarded(r, ctx, true, () -> verifiedPermissions.deletePolicyStore(policyStoreId, region), () -> { },
                () -> {
                    if (schema == null) {
                        return;
                    }
                    finished[0] = verifiedPermissions.putSchema(schemaRequest(policyStoreId, schema), region);
                    if (protectAfterSchema) {
                        ObjectNode protect = request.deepCopy();
                        protect.remove("encryptionSettings");
                        protect.remove("tags");
                        protect.put("policyStoreId", policyStoreId);
                        protect.put("deletionProtection", deletionMode);
                        finished[0] = verifiedPermissions.updatePolicyStore(protect, region);
                    }
                });
        return finished[0];
    }

    /**
     * Changes the store in place: its settings first, which the service validates before it writes,
     * then its schema, then the tags the stack owns. What it was is snapshotted before, so a step that
     * fails, or a later resource failing the update, puts it back.
     */
    private PolicyStore updatePolicyStore(StackResource r, JsonNode props, ProvisionContext ctx, PropertyChanges changes,
                                          ObjectNode request, String schema, Map<String, String> tags) {
        String region = ctx.region();
        String policyStoreId = ctx.priorPhysicalId();
        PropertyChanges.Kind encryption = changes.kindOf("EncryptionSettings");
        if (encryption != null && encryption != PropertyChanges.Kind.UNCHANGED) {
            throw new AwsException("ValidationError", "EncryptionSettings of " + r.getLogicalId()
                    + " can't change: Verified Permissions sets a policy store's encryption when it creates the store.", 400);
        }
        // A template that drops Schema, or declares one without CedarJson, wants no schema; one that never
        // declared it leaves a schema set outside the stack alone.
        boolean clearSchema = schema == null
                && (changes.kindOf("Schema") == PropertyChanges.Kind.DROPPED || declares(props, "Schema"));
        PolicyStore current = verifiedPermissions.getPolicyStore(policyStoreId, region);
        Set<String> owned = Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx);
        Set<String> touched = Ec2Tags.touched(owned, tags);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("policyStoreId", policyStoreId);
        snapshot.put("mode", current.validationMode());
        snapshot.put("description", current.description());
        snapshot.put("deletionProtection", current.deletionProtection());
        snapshot.put("schema", current.schema());
        Ec2Tags.snapshotOwned(snapshot, owned, tags, verifiedPermissions.listTags(current.arn(), region),
                r.getAttributes().get(TAG_KEYS_ATTR));
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());

        request.put("policyStoreId", policyStoreId);
        PolicyStore[] updated = {current};
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restoreSnapshot(r), () -> {
            updated[0] = verifiedPermissions.updatePolicyStore(request, region);
            if (schema != null) {
                verifiedPermissions.putSchema(schemaRequest(policyStoreId, schema), region);
            } else if (clearSchema && current.schema() != null) {
                verifiedPermissions.putSchema(schemaRequest(policyStoreId, EMPTY_SCHEMA), region);
            }
            reconcileTags(current.arn(), touched, tags, region);
        });
        return updated[0];
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
            String priorStoreId = storeOf(ctx.priorPhysicalId());
            String templateId = entityOf(ctx.priorPhysicalId());
            PolicyTemplate current = verifiedPermissions.getPolicyTemplate(priorStoreId, templateId, region);
            ObjectNode snapshot = entitySnapshot(region, priorStoreId, current.statement(), current.description(),
                    current.name());
            snapshot.put("policyTemplateId", templateId);
            r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
            request.put("policyTemplateId", templateId);
            PolicyTemplate[] updated = {current};
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> restoreSnapshot(r),
                    () -> updated[0] = verifiedPermissions.updatePolicyTemplate(request, region));
            template = updated[0];
        } else {
            r.getAttributes().remove(SNAPSHOT_ATTR);
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
            String policyId = policyIdOf(ctx.priorPhysicalId());
            Policy current = verifiedPermissions.getPolicy(policyStoreId, policyId, region);
            if ("TEMPLATE_LINKED".equals(current.policyType())) {
                // Verified Permissions takes no update of a template-linked policy, and the Template
                // Reference says the same of its definition: one that stands unchanged is left alone.
                if (!sameLinkedPolicy(current, request, name)) {
                    throw new AwsException("ValidationError", "Updating template-linked policy " + policyId
                            + " is not supported.", 400);
                }
                r.getAttributes().remove(SNAPSHOT_ATTR);
                policy = current;
            } else {
                request.put("policyId", policyId);
                JsonNode staticDefinition = request.path("definition").path("static");
                if (staticDefinition instanceof ObjectNode body && !body.has("description")
                        && current.description() != null) {
                    // The definition is declared whole, so a description it no longer carries is cleared,
                    // where the API on its own keeps the one the policy has.
                    body.put("description", "");
                }
                ObjectNode snapshot = entitySnapshot(region, policyStoreId, current.statement(), current.description(),
                        current.name());
                snapshot.put("policyId", policyId);
                r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
                Policy[] updated = {current};
                CfnRollback.guarded(r, ctx, false, () -> { }, () -> restoreSnapshot(r),
                        () -> updated[0] = verifiedPermissions.updatePolicy(request, region));
                policy = updated[0];
            }
        } else {
            r.getAttributes().remove(SNAPSHOT_ATTR);
            policy = verifiedPermissions.createPolicy(request, region);
        }

        r.setPhysicalId(policy.policyId());
        r.getAttributes().put("PolicyId", policy.policyId());
        r.getAttributes().put("PolicyType", policy.policyType());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Deletes the store, template or policy the physical id names. A store that is already gone
     * deletes silently in the service; one whose deletion protection is on fails, as on AWS. A
     * template or policy whose store is gone went with it, and so did a policy no store holds.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case POLICY_STORE -> verifiedPermissions.deletePolicyStore(physicalId, region);
            case POLICY_TEMPLATE -> CfnDeletes.safeDelete("Verified Permissions policy template", physicalId,
                    () -> verifiedPermissions.deletePolicyTemplate(storeOf(physicalId), entityOf(physicalId), region),
                    NOT_FOUND);
            case POLICY -> {
                String policyStoreId = storeOfPolicy(physicalId, region);
                if (policyStoreId != null) {
                    CfnDeletes.safeDelete("Verified Permissions policy", physicalId,
                            () -> verifiedPermissions.deletePolicy(policyStoreId, policyIdOf(physicalId), region),
                            NOT_FOUND);
                }
            }
            default -> throw new IllegalStateException(
                    "VerifiedPermissionsCfnProvisioner cannot delete " + resourceType);
        }
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
        // The update committed, so what an in-place change snapshotted for a rollback is spent.
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        CfnRollback.carryFailedUndoSnapshot(previous, attempted, SNAPSHOT_ATTR);
    }

    /**
     * Undoes this update when a later resource failed it: a replacement is deleted and the prior
     * entity named again, an in-place change is written back from its snapshot. An update that changed
     * nothing in place, a template-linked policy left as it was, has nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        restoreSnapshot(resource);
        return true;
    }

    /**
     * Writes back what the snapshot holds, if the resource carries one. It is spent only once the
     * restore succeeded: a restore that throws leaves it in place for the next attempt.
     */
    private void restoreSnapshot(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
        if (snapshot == null) {
            return;
        }
        String region = snapshot.path("region").asText();
        String policyStoreId = snapshot.path("policyStoreId").asText();
        switch (resource.getResourceType()) {
            case POLICY_STORE -> {
                ObjectNode settings = MAPPER.createObjectNode();
                settings.put("policyStoreId", policyStoreId);
                settings.putObject("validationSettings").put("mode", snapshot.path("mode").asText());
                settings.put("description", Objects.requireNonNullElse(CfnRollback.snapshotText(snapshot, "description"), ""));
                settings.put("deletionProtection", snapshot.path("deletionProtection").asText());
                PolicyStore restored = verifiedPermissions.updatePolicyStore(settings, region);
                String schema = CfnRollback.snapshotText(snapshot, "schema");
                if (!Objects.equals(schema, restored.schema())) {
                    // Verified Permissions has no call that removes a schema; an empty one is the nearest.
                    verifiedPermissions.putSchema(schemaRequest(policyStoreId, schema == null ? EMPTY_SCHEMA : schema),
                            region);
                }
                Set<String> touched = new LinkedHashSet<>();
                snapshot.path("touched").forEach(key -> touched.add(key.asText()));
                Map<String, String> priorTags = new LinkedHashMap<>();
                snapshot.path("tags").properties().forEach(tag -> priorTags.put(tag.getKey(), tag.getValue().asText()));
                reconcileTags(restored.arn(), touched, priorTags, region);
                Ec2Tags.restoreRecord(resource, TAG_KEYS_ATTR, snapshot);
            }
            case POLICY_TEMPLATE -> {
                ObjectNode prior = entityRequest(snapshot, policyStoreId);
                prior.put("policyTemplateId", snapshot.path("policyTemplateId").asText());
                verifiedPermissions.updatePolicyTemplate(prior, region);
            }
            case POLICY -> {
                ObjectNode prior = MAPPER.createObjectNode();
                prior.put("policyStoreId", policyStoreId);
                prior.put("policyId", snapshot.path("policyId").asText());
                prior.put("name", Objects.requireNonNullElse(CfnRollback.snapshotText(snapshot, "name"), ""));
                ObjectNode body = prior.putObject("definition").putObject("static");
                body.put("statement", snapshot.path("statement").asText());
                body.put("description", Objects.requireNonNullElse(CfnRollback.snapshotText(snapshot, "description"), ""));
                verifiedPermissions.updatePolicy(prior, region);
            }
            default -> throw new IllegalStateException(
                    "VerifiedPermissionsCfnProvisioner cannot restore " + resource.getResourceType());
        }
        resource.getAttributes().remove(SNAPSHOT_ATTR);
    }

    private static ObjectNode entitySnapshot(String region, String policyStoreId, String statement, String description,
                                             String name) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("policyStoreId", policyStoreId);
        snapshot.put("statement", statement);
        snapshot.put("description", description);
        snapshot.put("name", name);
        return snapshot;
    }

    private static ObjectNode entityRequest(JsonNode snapshot, String policyStoreId) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("policyStoreId", policyStoreId);
        request.put("statement", snapshot.path("statement").asText());
        request.put("description", Objects.requireNonNullElse(CfnRollback.snapshotText(snapshot, "description"), ""));
        request.put("name", Objects.requireNonNullElse(CfnRollback.snapshotText(snapshot, "name"), ""));
        return request;
    }

    /** Drives the tags among {@code managedKeys} to {@code desired}; keys the stack does not own stay. */
    private void reconcileTags(String arn, Set<String> managedKeys, Map<String, String> desired, String region) {
        CfnRollback.reconcileTags(managedKeys, desired,
                dropped -> verifiedPermissions.untagResource(arn, dropped, region),
                added -> verifiedPermissions.tagResource(arn, added, region));
    }

    private static boolean declares(JsonNode props, String property) {
        return props != null && props.has(property) && !props.get(property).isNull();
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

    private static boolean sameLinkedPolicy(Policy current, ObjectNode request, String name) {
        JsonNode linked = request.path("definition").path("templateLinked");
        return linked.isObject()
                && linked.path("policyTemplateId").asText().equals(current.policyTemplateId())
                && Objects.equals(current.principal(), entity(linked.get("principal")))
                && Objects.equals(current.resource(), entity(linked.get("resource")))
                && Objects.equals(current.name(), blankToNull(name));
    }

    /**
     * Whether the update changes the prior entity in place: the engine does not replace it, and the
     * prior is a real entity, not the dispatcher's stub a stack from before this provisioner holds,
     * whose update creates the entity instead.
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

    private static EntityIdentifier entity(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new EntityIdentifier(node.path("entityType").asText(), node.path("entityId").asText());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String storeOf(String templatePhysicalId) {
        return templatePhysicalId.substring(0, templatePhysicalId.indexOf('|'));
    }

    private static String entityOf(String templatePhysicalId) {
        return templatePhysicalId.substring(templatePhysicalId.indexOf('|') + 1);
    }

    /**
     * The store holding the policy a physical id names, or null when none does: CloudFormation's id is
     * the policy id alone, Cloud Control's {@code PolicyId|PolicyStoreId}.
     */
    private String storeOfPolicy(String physicalId, String region) {
        int separator = physicalId.indexOf('|');
        return separator < 0 ? verifiedPermissions.policyStoreIdOf(physicalId, region) : physicalId.substring(separator + 1);
    }

    private static String policyIdOf(String physicalId) {
        int separator = physicalId.indexOf('|');
        return separator < 0 ? physicalId : physicalId.substring(0, separator);
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
