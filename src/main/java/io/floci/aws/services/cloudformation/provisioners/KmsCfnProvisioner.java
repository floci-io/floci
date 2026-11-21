package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ReservedTags;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Provisions {@code AWS::KMS::Key} and {@code AWS::KMS::Alias}. */
@ApplicationScoped
public class KmsCfnProvisioner implements CfnResourceProvisioner {

    private static final String KEY = "AWS::KMS::Key";
    private static final String ALIAS = "AWS::KMS::Alias";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SNAPSHOT_ATTR = CfnRollback.KMS_KEY_UPDATE_SNAPSHOT_ATTR;
    /** The tag keys the template that provisioned the key set, resolved then: the next update drops the ones it omits. */
    static final String TAG_KEYS_ATTR = "__FlociKmsTagKeys";
    /**
     * The properties AWS refuses to change on an existing key: the update request fails whatever
     * UpdateReplacePolicy says, so a changed one never replaces the key.
     */
    private static final List<String> IMMUTABLE = List.of("KeySpec", "KeyUsage", "MultiRegion", "Origin");
    private static final String PENDING_IMPORT = "PendingImport";
    /** KMS's shortest deletion window, used to retire a key a failed create or update left behind. */
    private static final int MIN_DELETION_WINDOW_DAYS = 7;

    private final KmsService kmsService;

    public KmsCfnProvisioner(KmsService kmsService) {
        this.kmsService = kmsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(KEY, ALIAS);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case KEY -> provisionKey(r, props, ctx);
            case ALIAS -> provisionAlias(r, props, ctx);
            default -> throw new IllegalStateException(
                    "KmsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(SNAPSHOT_ATTR);
        // A stack from before this type had a provisioner holds the dispatcher's stub, which names
        // no key, so the key is created as on a first deploy.
        boolean stub = CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), r.getAttributes());
        KmsKey existing = ctx.isUpdate() && !stub ? liveKey(ctx.priorPhysicalId(), ctx.region()) : null;
        if (existing != null) {
            updateInPlace(r, props, ctx, existing);
            return;
        }
        createKey(r, props, ctx);
        if (ctx.isUpdate() && !stub) {
            // The key the update started from is gone, so this one is the update's to undo. A stub
            // never named a key, and LeafCfnProvisionerTest pins the attributes of that case.
            CfnRollback.recordCreatedByUpdate(r, ctx, SNAPSHOT_ATTR);
        }
    }

    /** Creates the key with the properties its create call takes, then applies Enabled and EnableKeyRotation. */
    private void createKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        // Unstripped: createKey reads the reserved override-id tag, then drops it.
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);
        boolean multiRegion = Boolean.TRUE.equals(ctx.resolveOptionalBoolean(props, "MultiRegion"));
        String origin = ctx.resolveOptional(props, "Origin");
        String description = ctx.resolveOptional(props, "Description");
        String usage = ctx.resolveOptional(props, "KeyUsage");
        String spec = ctx.resolveOptional(props, "KeySpec");
        String policy = keyPolicy(props, ctx);
        Boolean rotation = ctx.resolveOptionalBoolean(props, "EnableKeyRotation");
        // Resolved with the rest, before the key is stored: an invalid value must not leave a key behind.
        Boolean enabled = ctx.resolveOptionalBoolean(props, "Enabled");
        // The shorter overload is the same call with the origin and multi-region defaults.
        KmsKey key = origin == null && !multiRegion
                ? kmsService.createKey(description, usage, spec, policy, tags, ctx.region())
                : kmsService.createKey(description, usage, spec, policy, tags, origin, multiRegion, ctx.region());
        String keyId = key.getKeyId();
        r.setPhysicalId(keyId);
        r.getAttributes().put("Arn", key.getArn());
        r.getAttributes().put("KeyId", keyId);
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, ReservedTags.stripReservedTags(tags));
        // A step that fails after the key is stored schedules it for deletion: a key cannot be removed outright.
        CfnRollback.guarded(r, ctx, true,
                () -> kmsService.scheduleKeyDeletion(keyId, MIN_DELETION_WINDOW_DAYS, ctx.region()),
                () -> { }, () -> {
                    if (rotation != null && rotation) {
                        kmsService.enableKeyRotation(keyId, ctx.region());
                    }
                    // A key imported later (Origin EXTERNAL) starts disabled, which is what Enabled false asks for.
                    if (enabled != null && !enabled && key.isEnabled()) {
                        kmsService.disableKey(keyId, ctx.region());
                    }
                });
    }

    /** The template's KeyPolicy as JSON, or null when it has none, which keeps the key's current policy. */
    private static String keyPolicy(JsonNode props, ProvisionContext ctx) {
        String policy = props == null ? null : ctx.engine().resolveJsonAttributeStrict(props.path("KeyPolicy"));
        return policy == null || policy.isBlank() ? null : policy;
    }

    /** The key an update starts from, or null when it is gone and the update creates one. */
    private KmsKey liveKey(String keyId, String region) {
        try {
            return kmsService.describeKey(keyId, region);
        } catch (AwsException e) {
            if ("NotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * The update AWS makes: the mutable properties go through the calls KMS offers for them and the
     * key keeps its id. KeySpec, KeyUsage, MultiRegion and Origin cannot change on an existing key,
     * so an update that changes one fails before anything is written.
     */
    private void updateInPlace(StackResource r, JsonNode props, ProvisionContext ctx, KmsKey key) {
        PropertyChanges changes = ctx.changes(KEY, props);
        for (String name : IMMUTABLE) {
            PropertyChanges.Kind kind = changes.kindOf(name);
            if (kind == null || kind == PropertyChanges.Kind.UNCHANGED || sameAsLive(name, key, props, ctx)) {
                continue;
            }
            throw new AwsException("ValidationError", "Update of " + name + " on " + KEY + " "
                    + r.getLogicalId() + " is not allowed: a KMS key cannot change its " + name
                    + " after it is created.", 400);
        }
        String keyId = key.getKeyId();
        String region = ctx.region();
        // A property the template drops goes back to its create default; one it never declared keeps
        // the value set outside the stack (null: leave as is).
        Set<String> dropped = ctx.droppedProperties(props);
        String description = ctx.resolveOptional(props, "Description");
        if (description == null && dropped.contains("Description")) {
            description = "";
        }
        Boolean enabled = ctx.resolveOptionalBoolean(props, "Enabled");
        if (enabled == null && dropped.contains("Enabled")) {
            // A key waiting for its key material keeps its state.
            enabled = PENDING_IMPORT.equals(key.getKeyState()) ? key.isEnabled() : Boolean.TRUE;
        }
        Boolean rotation = ctx.resolveOptionalBoolean(props, "EnableKeyRotation");
        if (rotation == null && dropped.contains("EnableKeyRotation")) {
            rotation = Boolean.FALSE;
        }
        Map<String, String> desiredTags = ReservedTags.stripReservedTags(
                parseCfnTags(props != null ? props.get("Tags") : null, ctx));
        // Only the keys the previous template or this one sets are the stack's to change or drop.
        Set<String> priorTagKeys = Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx);
        Set<String> managedTagKeys = new TreeSet<>(priorTagKeys);
        managedTagKeys.addAll(desiredTags.keySet());
        // Resolved before the snapshot: a template that cannot be read changes nothing.
        String policy = keyPolicy(props, ctx);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("keyId", keyId);
        snapshot.put("region", region);
        snapshot.put("description", key.getDescription());
        snapshot.put("policy", key.getPolicy());
        snapshot.put("enabled", key.isEnabled());
        snapshot.put("rotation", key.isKeyRotationEnabled());
        Ec2Tags.snapshotOwned(snapshot, priorTagKeys, desiredTags, key.getTags(), r.getAttributes().get(TAG_KEYS_ATTR));
        r.setPhysicalId(keyId);
        r.getAttributes().put("Arn", key.getArn());
        r.getAttributes().put("KeyId", keyId);
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
        String finalDescription = description;
        Boolean finalEnabled = enabled;
        Boolean finalRotation = rotation;
        // A dropped KeyPolicy keeps the current policy: no AWS documentation says it resets to the default.
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restore(snapshot), () ->
                converge(key, finalDescription, policy, finalEnabled, finalRotation, desiredTags,
                        managedTagKeys, region));
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, desiredTags);
    }

    /**
     * Drives {@code key} to the given state, calling only what differs: the one path both an update
     * and its rollback take. A null {@code description}, {@code policy}, {@code enabled} or
     * {@code rotation} leaves that setting as it is; only {@code managedTagKeys}
     * absent from {@code tags} are removed, so a tag added outside the stack stays.
     */
    private void converge(KmsKey key, String description, String policy, Boolean wantEnabled, Boolean wantRotation,
                          Map<String, String> tags, Collection<String> managedTagKeys, String region) {
        String keyId = key.getKeyId();
        // Read before the first call: the service may change the stored key object in place.
        boolean enabledNow = key.isEnabled();
        boolean rotationNow = key.isKeyRotationEnabled();
        String descriptionNow = key.getDescription();
        String policyNow = key.getPolicy();
        boolean enabled = wantEnabled == null ? enabledNow : wantEnabled;
        boolean rotation = wantRotation == null ? rotationNow : wantRotation;
        if (description != null && !Objects.equals(description, descriptionNow)) {
            kmsService.updateKeyDescription(keyId, description, region);
        }
        if (policy != null && !policy.equals(policyNow)) {
            kmsService.putKeyPolicy(keyId, policy, region);
        }
        // Rotation changes need an enabled key: enable first, and disable only after rotation is set.
        if (enabled && !enabledNow) {
            kmsService.enableKey(keyId, region);
        }
        if (rotation != rotationNow) {
            if (rotation) {
                kmsService.enableKeyRotation(keyId, region);
            } else {
                kmsService.disableKeyRotation(keyId, region);
            }
        }
        if (!enabled && enabledNow) {
            kmsService.disableKey(keyId, region);
        }
        CfnRollback.reconcileTags(managedTagKeys, tags, dropped -> kmsService.untagResource(keyId, dropped, region),
                added -> kmsService.tagResource(keyId, added, region));
    }

    /**
     * Whether the requested value of an immutable property is the one the key has. An absent value
     * names the property's default (KeySpec SYMMETRIC_DEFAULT, KeyUsage ENCRYPT_DECRYPT, MultiRegion
     * false, Origin AWS_KMS), so adding or dropping a default is not a change.
     */
    private static boolean sameAsLive(String name, KmsKey key, JsonNode props, ProvisionContext ctx) {
        return switch (name) {
            // A key stored before these fields existed holds null, which means the default.
            case "KeySpec" -> orDefault(key.getKeySpec() == null ? null : key.getKeySpec().name(),
                    "SYMMETRIC_DEFAULT").equalsIgnoreCase(
                    orDefault(ctx.resolveOptional(props, "KeySpec"), "SYMMETRIC_DEFAULT"));
            case "KeyUsage" -> orDefault(key.getKeyUsage() == null ? null : key.getKeyUsage().name(),
                    "ENCRYPT_DECRYPT").equalsIgnoreCase(
                    orDefault(ctx.resolveOptional(props, "KeyUsage"), "ENCRYPT_DECRYPT"));
            case "MultiRegion" -> Boolean.TRUE.equals(ctx.resolveOptionalBoolean(props, "MultiRegion"))
                    == key.isMultiRegion();
            case "Origin" -> orDefault(ctx.resolveOptional(props, "Origin"), "AWS_KMS").equals(orDefault(key.getOrigin(), "AWS_KMS"));
            default -> false;
        };
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void restore(JsonNode snapshot) {
        String keyId = snapshot.path("keyId").asText();
        String region = snapshot.path("region").asText();
        String description = CfnRollback.snapshotText(snapshot, "description");
        converge(kmsService.describeKey(keyId, region), description == null ? "" : description,
                CfnRollback.snapshotText(snapshot, "policy"), snapshot.path("enabled").asBoolean(),
                snapshot.path("rotation").asBoolean(), CfnRollback.snapshotTags(snapshot),
                touchedKeys(snapshot), region);
    }

    private static List<String> touchedKeys(JsonNode snapshot) {
        List<String> keys = new ArrayList<>();
        snapshot.path("touched").forEach(key -> keys.add(key.asText()));
        return keys;
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
        if (snapshot == null) {
            return false;
        }
        // A key the update created because the prior one was gone is scheduled for deletion instead.
        if (CfnRollback.undoCreatedByUpdate(resource, snapshot, SNAPSHOT_ATTR, "KeyId",
                (createdId, region) -> kmsService.scheduleKeyDeletion(createdId, MIN_DELETION_WINDOW_DAYS, region))) {
            resource.getAttributes().remove("Arn");
            return true;
        }
        // Spent only once the restore succeeded: one that throws leaves it for the next attempt.
        restore(snapshot);
        Ec2Tags.restoreRecord(resource, TAG_KEYS_ATTR, snapshot);
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return true;
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        String aliasName = ctx.resolveOptional(props, "AliasName");
        String targetKeyId = ctx.resolveOptional(props, "TargetKeyId");
        if (aliasName != null && targetKeyId != null) {
            if (ctx.isUpdate() && aliasName.equals(ctx.priorPhysicalId())) {
                kmsService.updateAlias(aliasName, targetKeyId, ctx.region());
            } else {
                kmsService.createAlias(aliasName, targetKeyId, ctx.region());
            }
        }
        r.setPhysicalId(aliasName != null
                ? aliasName
                : "alias/cfn-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // A KMS key cannot be deleted immediately, only scheduled, so a stack delete leaves it.
        if (ALIAS.equals(resourceType)) {
            kmsService.deleteAlias(physicalId, region);
        }
    }

    /**
     * Kept rather than delegating to {@link ProvisionContext#resolveTags}, which is not
     * equivalent: it skips a blank key this keeps and orders entries by insertion rather than hash.
     */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        tagsNode = ctx.engine().resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
