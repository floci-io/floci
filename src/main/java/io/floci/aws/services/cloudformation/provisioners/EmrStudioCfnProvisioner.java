package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emr.EmrStudioService;
import io.floci.aws.services.emr.EmrStudioService.Identity;
import io.floci.aws.services.emr.model.EmrStudio;
import io.floci.aws.services.emr.model.EmrStudioSessionMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * CloudFormation provisioning for {@code AWS::EMR::Studio} and {@code AWS::EMR::StudioSessionMapping}, through the
 * service the EMR Studio API itself uses.
 *
 * <p>A Studio's {@code Ref} is its id; {@code Fn::GetAtt} gives {@code StudioId}, {@code Arn} and {@code Url}. The
 * properties the registry marks create-only replace the Studio when they change. Of the others, UpdateStudio applies
 * {@code Name}, {@code Description}, {@code SubnetIds} and {@code DefaultS3Location} when the template changes them;
 * UpdateStudio has no member for {@code IdpAuthUrl} or {@code IdpRelayStateParameterName}, so a change to either is
 * accepted with a warning and not applied, and a dropped setting is left as it is. Only the tag keys the stack set are
 * its to change or drop, so a tag added outside the stack stays. A rollback puts back the name, description, default
 * location and tags the update changed; subnets it added stay, as UpdateStudio cannot take a Studio's subnets away.
 *
 * <p>A session mapping's physical id, and so its {@code Ref}, is its primary identifier, {@code StudioId},
 * {@code IdentityType} and {@code IdentityName} joined by {@code |}; those three are create-only, and an update changes
 * its {@code SessionPolicyArn} in place.
 *
 * <p>No AWS handler for these types is public, so each follows its schema and the API it calls. A template that sets
 * a Studio's read-only {@code Arn}, {@code StudioId} or {@code Url}, which the schema guide says a user cannot set, is
 * refused in the wording AWS's handler for EMR on EKS's virtual cluster gives its {@code Arn}.
 */
@ApplicationScoped
public class EmrStudioCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EmrStudioCfnProvisioner.class);
    private static final String STUDIO = "AWS::EMR::Studio";
    private static final String MAPPING = "AWS::EMR::StudioSessionMapping";
    /** What an in-place Studio update is about to change, for {@link #rollbackUpdate} to put back. */
    static final String STUDIO_SNAPSHOT_ATTR = "__FlociEmrStudioUpdateSnapshot";
    /** The session policy an in-place mapping update is about to change. */
    static final String MAPPING_SNAPSHOT_ATTR = "__FlociEmrStudioSessionMappingUpdateSnapshot";
    /** The tag keys the template that provisioned the Studio set: the next update drops the ones it omits. */
    static final String TAG_KEYS_ATTR = "__FlociEmrStudioTagKeys";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> READ_ONLY = List.of("Arn", "StudioId", "Url");
    /** The settings no Studio API changes, accepted with a warning when an update changes them. */
    private static final List<String> NOT_UPDATED = List.of("IdpAuthUrl", "IdpRelayStateParameterName");
    private static final String SEPARATOR = "|";

    private final EmrStudioService studios;

    @Inject
    public EmrStudioCfnProvisioner(EmrStudioService studios) {
        this.studios = studios;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(STUDIO, MAPPING);
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
        resource.getAttributes().remove(STUDIO_SNAPSHOT_ATTR);
        resource.getAttributes().remove(MAPPING_SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    /**
     * An in-place update whose own undo failed leaves its snapshot on the attempted resource, which the engine discards
     * for the previous one: carried over, it lets ContinueUpdateRollback try the restore again.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        CfnRollback.carryFailedUndoSnapshot(previous, attempted, STUDIO_SNAPSHOT_ATTR, MAPPING_SNAPSHOT_ATTR);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(STUDIO_SNAPSHOT_ATTR);
        resource.getAttributes().remove(MAPPING_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement or a stub migration is undone through the cleanup record, and an in-place update from the snapshot
     * taken before it, which is spent only once the restore succeeded.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        JsonNode studio = CfnRollback.updateSnapshot(resource, STUDIO_SNAPSHOT_ATTR);
        if (studio != null) {
            restoreStudio(studio, true, true);
            Ec2Tags.restoreRecord(resource, TAG_KEYS_ATTR, studio);
            resource.getAttributes().remove(STUDIO_SNAPSHOT_ATTR);
            return true;
        }
        JsonNode mapping = CfnRollback.updateSnapshot(resource, MAPPING_SNAPSHOT_ATTR);
        if (mapping != null) {
            restorePolicy(mapping);
            resource.getAttributes().remove(MAPPING_SNAPSHOT_ATTR);
            return true;
        }
        return false;
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(STUDIO_SNAPSHOT_ATTR);
        r.getAttributes().remove(MAPPING_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        // A stack from before these types had a provisioner holds the dispatcher's stub, which names nothing.
        boolean stub = CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
        switch (r.getResourceType()) {
            case STUDIO -> provisionStudio(r, props, ctx, stub);
            case MAPPING -> provisionMapping(r, props, ctx, stub);
            default -> throw new IllegalStateException("EmrStudioCfnProvisioner cannot provision "
                    + r.getResourceType());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionStudio(StackResource r, JsonNode props, ProvisionContext ctx, boolean stub) {
        JsonNode template = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(STUDIO, props);
        for (String readOnly : READ_ONLY) {
            if (template.has(readOnly)) {
                throw new AwsException("InvalidRequest",
                        readOnly + " is a ReadOnly property which cannot be specified in the template", 400);
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        template.path("Tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        PropertyChanges changes = ctx.isUpdate() && !stub ? ctx.changes(STUDIO, props) : null;
        if (changes != null && !changes.requiresReplacement()) {
            updateStudioInPlace(r, ctx, template, changes, tags);
            return;
        }
        EmrStudio requested = new EmrStudio();
        requested.setName(text(template, "Name"));
        requested.setDescription(text(template, "Description"));
        requested.setAuthMode(text(template, "AuthMode"));
        requested.setVpcId(text(template, "VpcId"));
        requested.setSubnetIds(strings(template.get("SubnetIds")));
        requested.setServiceRole(text(template, "ServiceRole"));
        requested.setUserRole(text(template, "UserRole"));
        requested.setWorkspaceSecurityGroupId(text(template, "WorkspaceSecurityGroupId"));
        requested.setEngineSecurityGroupId(text(template, "EngineSecurityGroupId"));
        requested.setDefaultS3Location(text(template, "DefaultS3Location"));
        requested.setIdpAuthUrl(text(template, "IdpAuthUrl"));
        requested.setIdpRelayStateParameterName(text(template, "IdpRelayStateParameterName"));
        requested.setIdcInstanceArn(text(template, "IdcInstanceArn"));
        requested.setIdcUserAssignment(text(template, "IdcUserAssignment"));
        requested.setEncryptionKeyArn(text(template, "EncryptionKeyArn"));
        JsonNode propagation = template.get("TrustedIdentityPropagationEnabled");
        requested.setTrustedIdentityPropagationEnabled(propagation == null ? null : propagation.booleanValue());
        requested.setTags(tags);
        EmrStudio created = studios.createStudio(requested);
        publishStudio(r, created);
        Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, tags);
    }

    /**
     * The update of a Studio the prior physical id names, which fails when it is gone. What it is about to change is
     * snapshotted first, so a later resource's failure puts it back, and a failure here puts back what it had changed
     * before it propagates.
     */
    private void updateStudioInPlace(StackResource r, ProvisionContext ctx, JsonNode template,
                                     PropertyChanges changes, Map<String, String> tags) {
        String id = ctx.priorPhysicalId();
        EmrStudio live = liveStudio(id);
        for (String name : NOT_UPDATED) {
            if (changed(changes, name)) {
                LOG.warnv("EMR Studio {0}: no Studio API changes {1}, so the change is not applied", id, name);
            }
        }
        String name = changed(changes, "Name") ? text(template, "Name") : null;
        String description = changed(changes, "Description") ? text(template, "Description") : null;
        List<String> subnetIds = changed(changes, "SubnetIds") ? strings(template.get("SubnetIds")) : null;
        String location = changed(changes, "DefaultS3Location") ? text(template, "DefaultS3Location") : null;
        boolean settings = name != null || description != null || subnetIds != null || location != null;

        Set<String> owned = Ec2Tags.ownedKeys(r, TAG_KEYS_ATTR, ctx);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("studioId", id);
        if (name != null) {
            snapshot.put("name", live.getName());
        }
        if (description != null) {
            // UpdateStudio leaves a null description as it is, so one the Studio did not have is put back empty.
            snapshot.put("description", live.getDescription() == null ? "" : live.getDescription());
        }
        if (location != null) {
            snapshot.put("defaultS3Location", live.getDefaultS3Location());
        }
        Map<String, String> current = live.getTags() == null ? Map.of() : live.getTags();
        Ec2Tags.snapshotOwned(snapshot, owned, tags, current, r.getAttributes().get(TAG_KEYS_ATTR));
        publishStudio(r, live);
        r.getAttributes().put(STUDIO_SNAPSHOT_ATTR, snapshot.toString());
        AtomicBoolean settingsChanged = new AtomicBoolean();
        AtomicBoolean tagsTouched = new AtomicBoolean();
        CfnRollback.guarded(r, ctx, false, () -> { },
                () -> restoreStudio(snapshot, settingsChanged.get(), tagsTouched.get()), () -> {
                    if (settings) {
                        studios.updateStudio(id, name, description, subnetIds, location, null);
                        settingsChanged.set(true);
                    }
                    tagsTouched.set(true);
                    reconcileTags(id, Ec2Tags.touched(owned, tags), tags);
                    Ec2Tags.recordKeys(r, TAG_KEYS_ATTR, tags);
                });
    }

    private void restoreStudio(JsonNode snapshot, boolean settingsChanged, boolean tagsTouched) {
        String id = snapshot.path("studioId").asText();
        if (settingsChanged) {
            String name = CfnRollback.snapshotText(snapshot, "name");
            String description = CfnRollback.snapshotText(snapshot, "description");
            String location = CfnRollback.snapshotText(snapshot, "defaultS3Location");
            if (name != null || description != null || location != null) {
                studios.updateStudio(id, name, description, null, location, null);
            }
        }
        if (tagsTouched) {
            List<String> touched = new ArrayList<>();
            snapshot.path("touched").forEach(key -> touched.add(key.asText()));
            reconcileTags(id, touched, CfnRollback.snapshotTags(snapshot));
        }
    }

    private void reconcileTags(String studioId, Collection<String> managedKeys, Map<String, String> desired) {
        CfnRollback.reconcileTags(managedKeys, desired, dropped -> studios.removeTags(studioId, dropped),
                added -> studios.addTags(studioId, added));
    }

    private EmrStudio liveStudio(String id) {
        return orNotFound(STUDIO, id, () -> studios.describeStudio(id));
    }

    /**
     * What {@code lookup} reads, failing as an update of a gone resource fails when the Studio or mapping does not
     * exist, which the Studio service answers with InvalidRequestException.
     */
    private static <T> T orNotFound(String type, String id, Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (AwsException e) {
            if ("InvalidRequestException".equals(e.getErrorCode())) {
                throw notFound(type, id);
            }
            throw e;
        }
    }

    private static void publishStudio(StackResource r, EmrStudio studio) {
        r.setPhysicalId(studio.getStudioId());
        r.getAttributes().put("StudioId", studio.getStudioId());
        r.getAttributes().put("Arn", studio.getStudioArn());
        r.getAttributes().put("Url", studio.getUrl());
    }

    private void provisionMapping(StackResource r, JsonNode props, ProvisionContext ctx, boolean stub) {
        JsonNode template = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(MAPPING, props);
        String studioId = text(template, "StudioId");
        String type = text(template, "IdentityType");
        String identityName = text(template, "IdentityName");
        String policy = text(template, "SessionPolicyArn");
        PropertyChanges changes = ctx.isUpdate() && !stub ? ctx.changes(MAPPING, props) : null;
        if (changes != null && !changes.requiresReplacement()) {
            String id = ctx.priorPhysicalId();
            Identity identity = identity(id);
            EmrStudioSessionMapping live = liveMapping(id, identity);
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.put("id", id);
            snapshot.put("sessionPolicyArn", live.getSessionPolicyArn());
            r.setPhysicalId(id);
            r.getAttributes().put(MAPPING_SNAPSHOT_ATTR, snapshot.toString());
            AtomicBoolean policyChanged = new AtomicBoolean();
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> {
                if (policyChanged.get()) {
                    restorePolicy(snapshot);
                }
            }, () -> {
                if (!policy.equals(live.getSessionPolicyArn())) {
                    studios.updateSessionMapping(studioOf(id), identity, policy);
                    policyChanged.set(true);
                }
            });
            return;
        }
        studios.createSessionMapping(studioId, new Identity(type, null, identityName), policy);
        r.setPhysicalId(studioId + SEPARATOR + type + SEPARATOR + identityName);
    }

    private void restorePolicy(JsonNode snapshot) {
        String id = snapshot.path("id").asText();
        studios.updateSessionMapping(studioOf(id), identity(id), snapshot.path("sessionPolicyArn").asText());
    }

    private EmrStudioSessionMapping liveMapping(String id, Identity identity) {
        return orNotFound(MAPPING, id, () -> studios.getSessionMapping(studioOf(id), identity));
    }

    /** The Studio of a mapping's physical id, {@code StudioId|IdentityType|IdentityName}. */
    private static String studioOf(String physicalId) {
        return physicalId.split("\\|", 3)[0];
    }

    /** The identity of a mapping's physical id, named by its type and name. */
    private static Identity identity(String physicalId) {
        String[] parts = physicalId.split("\\|", 3);
        return new Identity(parts[1], null, parts[2]);
    }

    private static AwsException notFound(String type, String id) {
        return new AwsException("NotFound", "Resource of type '" + type + "' with identifier '" + id
                + "' was not found.", 404);
    }

    private static boolean changed(PropertyChanges changes, String name) {
        PropertyChanges.Kind kind = changes.kindOf(name);
        return kind == PropertyChanges.Kind.ADDED || kind == PropertyChanges.Kind.CHANGED;
    }

    private static String text(JsonNode template, String name) {
        return template.path(name).textValue();
    }

    private static List<String> strings(JsonNode list) {
        if (list == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        list.forEach(value -> values.add(value.asText()));
        return values;
    }

    /**
     * Deletes the Studio, its session mappings with it, or the one session mapping, unless it is gone: a Studio the
     * Region does not hold, or a mapping its Studio does not list.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case STUDIO -> {
                if (studios.studioArn(physicalId, region).isPresent()) {
                    studios.deleteStudio(physicalId);
                }
            }
            case MAPPING -> {
                String studioId = studioOf(physicalId);
                Identity identity = identity(physicalId);
                boolean mapped = studios.studioArn(studioId, region).isPresent()
                        && studios.describeStudio(studioId).getSessionMappings().stream()
                                .anyMatch(mapping -> identity.type().equals(mapping.getIdentityType())
                                        && identity.name().equals(mapping.getIdentityName()));
                if (mapped) {
                    studios.deleteSessionMapping(studioId, identity);
                }
            }
            default -> throw new IllegalStateException("EmrStudioCfnProvisioner cannot delete " + resourceType);
        }
    }
}
