package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emrcontainers.EmrContainersService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CloudFormation provisioning for {@code AWS::EMRContainers::VirtualCluster}, through the service the EMR on EKS API
 * itself uses. {@code Ref} is the virtual cluster's id; {@code Fn::GetAtt} gives {@code Id} and {@code Arn}.
 *
 * <p>It does what AWS's resource handler does (aws-cloudformation-resource-providers-emrcontainers): the properties are
 * read as the handler's model validation reads them, a template that sets the read-only {@code Arn} is refused in the
 * handler's words, and a create sends the rest to CreateVirtualCluster. {@code Name}, {@code ContainerProvider},
 * {@code SecurityConfigurationId} and {@code SessionEnabled} are create-only, so a change to any of them replaces the
 * virtual cluster; an update otherwise changes the tags alone, driving them to the template's and removing any other
 * but an {@code aws:} one. An update of a virtual cluster that is terminated or gone fails as the handler's does, and
 * the delete of one is taken as done, as the handler takes DeleteVirtualCluster's answer for it.
 */
@ApplicationScoped
public class EmrContainersCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::EMRContainers::VirtualCluster";
    /** The tags an in-place update is about to change, for {@link #rollbackUpdate} to put back. */
    static final String SNAPSHOT_ATTR = "__FlociEmrContainersVirtualClusterUpdateSnapshot";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The schema's read-only properties, which a user cannot set. */
    private static final List<String> READ_ONLY = List.of("Arn", "Id");
    private static final String SYSTEM_TAG_PREFIX = "aws:";

    private final EmrContainersService service;

    @Inject
    public EmrContainersCfnProvisioner(EmrContainersService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
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
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    /**
     * An in-place update whose own undo failed leaves its snapshot on the attempted resource, which the engine discards
     * for the previous one: carried over, it lets ContinueUpdateRollback try the restore again.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        CfnRollback.carryFailedUndoSnapshot(previous, attempted, SNAPSHOT_ATTR);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement or a stub migration is undone through the cleanup record, and an in-place update from the tag
     * snapshot taken before it. The snapshot is spent only once the restore succeeded, so one that throws leaves it for
     * ContinueUpdateRollback to retry.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
        if (snapshot == null) {
            return false;
        }
        restoreTags(snapshot);
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        return true;
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        JsonNode template = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(TYPE, props);
        for (String readOnly : READ_ONLY) {
            if (template.has(readOnly)) {
                throw new AwsException("InvalidRequest",
                        readOnly + " is a ReadOnly property which cannot be specified in the template", 400);
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        template.path("Tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        // A stack from before this type had a provisioner, or deployed while EMR on EKS was disabled, holds the
        // dispatcher's stub, which names no virtual cluster: taking over its id is a create.
        boolean stub = CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
        if (ctx.isUpdate() && !stub && !ctx.changes(TYPE, props).requiresReplacement()) {
            updateTagsInPlace(r, ctx, tags);
        } else {
            ObjectNode request = MAPPER.createObjectNode();
            request.set("name", template.path("Name"));
            request.set("containerProvider", CfnApiShapeConverter.convert(template.get("ContainerProvider"),
                    CfnApiShapeConverter.OMIT_NULL_PROPERTIES));
            request.put("clientToken", UUID.randomUUID().toString());
            if (!tags.isEmpty()) {
                request.set("tags", MAPPER.valueToTree(tags));
            }
            if (template.has("SecurityConfigurationId")) {
                request.set("securityConfigurationId", template.get("SecurityConfigurationId"));
            }
            if (template.has("SessionEnabled")) {
                request.set("sessionEnabled", template.get("SessionEnabled"));
            }
            ObjectNode created = service.createVirtualCluster(request);
            publish(r, created.path("id").asText(), created.path("arn").asText());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * The update AWS's handler makes for the one property that is not create-only: the tags, from the virtual cluster
     * the prior physical id names, which has to be running. They are snapshotted first, so a later resource's failure
     * puts them back, and a failure here puts them back, once their reconcile began, before it propagates.
     */
    private void updateTagsInPlace(StackResource r, ProvisionContext ctx, Map<String, String> tags) {
        String id = ctx.priorPhysicalId();
        JsonNode cluster = liveCluster(id);
        String arn = cluster.path("arn").asText();
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("arn", arn);
        snapshot.set("tags", cluster.path("tags").isObject() ? cluster.get("tags") : MAPPER.createObjectNode());
        publish(r, id, arn);
        r.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
        AtomicBoolean tagsTouched = new AtomicBoolean();
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> {
            if (tagsTouched.get()) {
                restoreTags(snapshot);
            }
        }, () -> {
            tagsTouched.set(true);
            reconcileTags(arn, tags);
        });
    }

    /** The virtual cluster {@code id}, failing as the handler's check of it does when it is terminated or gone. */
    private JsonNode liveCluster(String id) {
        AwsException notFound = new AwsException("NotFound",
                "Resource of type '" + TYPE + "' with identifier '" + id + "' was not found.", 404);
        JsonNode cluster;
        try {
            cluster = service.describeVirtualCluster(id).path("virtualCluster");
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw notFound;
            }
            throw e;
        }
        if ("TERMINATED".equals(cluster.path("state").asText())) {
            throw notFound;
        }
        return cluster;
    }

    private void restoreTags(JsonNode snapshot) {
        Map<String, String> tags = new LinkedHashMap<>();
        snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(snapshot.path("arn").asText(), tags);
    }

    /** Drives the virtual cluster's tags to {@code desired}, removing every other but an {@code aws:} one. */
    private void reconcileTags(String arn, Map<String, String> desired) {
        List<String> held = service.listTags(arn).keySet().stream()
                .filter(key -> !key.regionMatches(true, 0, SYSTEM_TAG_PREFIX, 0, SYSTEM_TAG_PREFIX.length()))
                .toList();
        CfnRollback.reconcileTags(held, desired, dropped -> service.untagResource(arn, dropped),
                added -> service.tagResource(arn, added));
    }

    private static void publish(StackResource r, String id, String arn) {
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
        r.getAttributes().put("Arn", arn);
    }

    /**
     * Deletes the virtual cluster; one that is gone is taken as deleted, and one already terminated stays as it is, as
     * DeleteVirtualCluster leaves it.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("EMR on EKS virtual cluster", physicalId, () -> {
            service.describeVirtualCluster(physicalId);
            service.deleteVirtualCluster(physicalId);
        }, "ResourceNotFoundException");
    }
}
