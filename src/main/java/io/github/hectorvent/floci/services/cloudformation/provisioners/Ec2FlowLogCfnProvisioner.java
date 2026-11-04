package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.FlowLogService;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::FlowLog}, backed by {@link FlowLogService}.
 * Ref and {@code Fn::GetAtt [Flow, Id]} both yield the {@code fl-} id, matching AWS. {@code Tags}
 * live in the EC2 tag store, as a {@code CreateFlowLogs} TagSpecification's do, and an update
 * reconciles the keys the template owns on the reused log, keeping tags added outside the stack, as
 * AWS does. What an update changes is recorded in {@link CfnRollback#FLOW_LOG_UPDATE_SNAPSHOT_ATTR},
 * so a stack update that fails on a later resource puts the touched keys back, or deletes a log the
 * update recreated because the stack's own was gone.
 */
@ApplicationScoped
public class Ec2FlowLogCfnProvisioner implements CfnResourceProvisioner {

    /** AWS's default when MaxAggregationInterval is omitted (10 minutes). */
    private static final int DEFAULT_MAX_AGGREGATION_INTERVAL = 600;

    /** The defaults createFlowLog applies when the template omits these, kept in step with it. */
    private static final String DEFAULT_RESOURCE_TYPE = "VPC";
    private static final String DEFAULT_TRAFFIC_TYPE = "ALL";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SNAPSHOT_ATTR = CfnRollback.FLOW_LOG_UPDATE_SNAPSHOT_ATTR;

    private final FlowLogService flowLogService;
    private final Ec2Service ec2Service;

    @Inject
    public Ec2FlowLogCfnProvisioner(FlowLogService flowLogService, Ec2Service ec2Service) {
        this.flowLogService = flowLogService;
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::FlowLog");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // An update re-invokes provision with the prior physical id. Creating unconditionally
        // would leave a second flow log on every update and orphan the first, which then outlives
        // the stack, since delete only knows the id recorded last.
        String region = ctx.region();
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        // Every tag rule CreateTags applies (reserved keys, lengths, the count limit) is checked
        // before anything is created, so a bad tag fails the resource before a log exists.
        Ec2Service.validateNewResourceTags(Ec2Tags.validatedTags(desiredTags));
        String existingId = r.getPhysicalId();
        if (existingId != null && !existingId.isBlank()) {
            FlowLog existing = flowLogService.describeFlowLogs(ctx.region(), List.of(existingId))
                    .stream().findFirst().orElse(null);
            if (existing != null) {
                // Every property except Tags is createOnly, so only the tags change on the reused
                // log and a change to any other property is a replacement. Reusing regardless would
                // report the stack complete while DescribeFlowLogs kept serving the old config.
                requireUnchanged(existing, props, ctx);
                r.getAttributes().put("Id", existingId);
                // Only the keys the previous template declared are the stack's to remove.
                Set<String> owned = ctx.resolveTags(ctx.priorProperties(), "Tags").keySet();
                CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r), () -> {
                    snapshotTags(r, region, existingId, owned, desiredTags);
                    Ec2Tags.reconcileOwned(ec2Service, region, existingId, owned, desiredTags);
                });
                return;
            }
        }
        FlowLog fl = flowLogService.createFlowLog(ctx.region(),
                ctx.resolveOptional(props, "ResourceId"),
                ctx.resolveOptional(props, "ResourceType"),
                ctx.resolveOptional(props, "TrafficType"),
                ctx.resolveOptional(props, "LogDestinationType"),
                ctx.resolveOptional(props, "LogDestination"),
                ctx.resolveOptional(props, "DeliverLogsPermissionArn"),
                ctx.resolveOptional(props, "LogFormat"),
                props != null && props.hasNonNull("MaxAggregationInterval")
                        ? props.get("MaxAggregationInterval").asInt()
                        : DEFAULT_MAX_AGGREGATION_INTERVAL);
        String flowLogId = fl.getFlowLogId();
        r.setPhysicalId(flowLogId);
        r.getAttributes().put("Id", flowLogId);
        // A tag write that still fails deletes the new log rather than leaving it outside the stack.
        CfnRollback.guarded(r, ctx, true, () -> flowLogService.deleteFlowLogs(region, List.of(flowLogId)),
                () -> { }, () -> Ec2Tags.reconcile(ec2Service, region, flowLogId, desiredTags));
        if (ctx.isUpdate()) {
            // The stack's log was removed outside it, so a rollback deletes this one and names the
            // prior id again rather than keeping the failed update's log.
            CfnRollback.recordCreatedByUpdate(r, ctx, SNAPSHOT_ATTR);
        }
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

    /**
     * Undoes what the failed update recorded: deletes a log it recreated, or puts back the tag keys
     * it touched on the reused one. Without a snapshot the update changed nothing.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, SNAPSHOT_ATTR);
        if (snapshot != null && CfnRollback.undoCreatedByUpdate(resource, snapshot, SNAPSHOT_ATTR, "Id",
                (id, region) -> flowLogService.deleteFlowLogs(region, List.of(id)))) {
            return true;
        }
        undoInPlace(resource);
        return true;
    }

    /**
     * Records the values the touched keys (the owned ones and the declared ones) had before the
     * update changes them, only when one of them is about to change, so an unchanged reuse leaves
     * no snapshot. Tags outside that set are never written, so they are not recorded either.
     */
    private void snapshotTags(StackResource r, String region, String flowLogId, Set<String> owned,
                              Map<String, String> desiredTags) {
        Map<String, String> currentTags = Ec2Tags.current(ec2Service, region, flowLogId);
        Set<String> touched = new LinkedHashSet<>(owned);
        touched.addAll(desiredTags.keySet());
        boolean changes = touched.stream().anyMatch(key -> desiredTags.containsKey(key)
                ? !desiredTags.get(key).equals(currentTags.get(key))
                : currentTags.containsKey(key));
        if (!changes) {
            return;
        }
        ObjectNode prior = MAPPER.createObjectNode();
        prior.put("region", region);
        prior.put("flowLogId", flowLogId);
        touched.forEach(prior.putArray("touched")::add);
        ObjectNode tags = prior.putObject("tags");
        touched.stream().filter(currentTags::containsKey).forEach(key -> tags.put(key, currentTags.get(key)));
        r.getAttributes().put(SNAPSHOT_ATTR, prior.toString());
    }

    /**
     * Puts the touched keys of a reused log back from the snapshot: a key it had gets its value
     * again, and a key the update added is removed. The snapshot is removed only after the restore
     * succeeds, so a failed one keeps it for the rollback walker's retry.
     */
    private void undoInPlace(StackResource r) {
        JsonNode prior = CfnRollback.updateSnapshot(r, SNAPSHOT_ATTR);
        if (prior == null) {
            return;
        }
        Set<String> touched = new LinkedHashSet<>();
        prior.path("touched").forEach(key -> touched.add(key.asText()));
        Map<String, String> priorTags = new LinkedHashMap<>();
        prior.path("tags").properties().forEach(f -> priorTags.put(f.getKey(), f.getValue().asText()));
        Ec2Tags.reconcileOwned(ec2Service, prior.path("region").asText(), prior.path("flowLogId").asText(),
                touched, priorTags);
        r.getAttributes().remove(SNAPSHOT_ATTR);
    }

    /**
     * Rejects a change to any createOnly property. AWS replaces the flow log for these, and this
     * provisioner has no replacement path, so reporting is better than reusing a log whose
     * configuration no longer matches the template.
     */
    private void requireUnchanged(FlowLog existing, JsonNode props, ProvisionContext ctx) {
        // Compared as effective values on both sides. createFlowLog defaults ResourceType,
        // TrafficType and LogDestinationType when the template omits them, so a re-apply of an
        // unchanged template reads null here against a stored default. Defaulting the requested
        // side the same way keeps a no-op update a no-op, while a genuine add, drop or change
        // still differs. The other three have no create-time default and stay null on both sides.
        rejectIfChanged("ResourceId", existing.getResourceId(),
                ctx.resolveOptional(props, "ResourceId"));
        rejectIfChanged("ResourceType", existing.getResourceType(),
                orDefault(ctx.resolveOptional(props, "ResourceType"), DEFAULT_RESOURCE_TYPE));
        rejectIfChanged("TrafficType", existing.getTrafficType(),
                orDefault(ctx.resolveOptional(props, "TrafficType"), DEFAULT_TRAFFIC_TYPE));
        rejectIfChanged("LogDestinationType", existing.getLogDestinationType(),
                FlowLogService.effectiveLogDestinationType(ctx.resolveOptional(props, "LogDestinationType"),
                        ctx.resolveOptional(props, "LogDestination"), null));
        rejectIfChanged("LogDestination", existing.getLogDestination(),
                ctx.resolveOptional(props, "LogDestination"));
        rejectIfChanged("DeliverLogsPermissionArn", existing.getDeliverLogsPermissionArn(),
                ctx.resolveOptional(props, "DeliverLogsPermissionArn"));
        rejectIfChanged("LogFormat", existing.getLogFormat(), ctx.resolveOptional(props, "LogFormat"));
        int requestedInterval = props != null && props.hasNonNull("MaxAggregationInterval")
                ? props.get("MaxAggregationInterval").asInt()
                : DEFAULT_MAX_AGGREGATION_INTERVAL;
        if (requestedInterval != existing.getMaxAggregationInterval()) {
            throw new AwsException("ValidationError",
                    "Updating MaxAggregationInterval requires resource replacement, which is not supported.", 400);
        }
    }

    private static String orDefault(String requested, String fallback) {
        return requested == null || requested.isBlank() ? fallback : requested;
    }

    /**
     * Both sides are the effective value, so an absent one means genuinely absent rather than
     * unknown. Adding a property, dropping one, and altering one all read as changes.
     */
    private void rejectIfChanged(String property, String existing, String requested) {
        String a = existing == null || existing.isBlank() ? null : existing;
        String b = requested == null || requested.isBlank() ? null : requested;
        if (Objects.equals(a, b)) {
            return;
        }
        throw new AwsException("ValidationError",
                "Updating " + property + " requires resource replacement, which is not supported.", 400);
    }

    /** Without this the flow log outlives its stack and keeps showing up in DescribeFlowLogs. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        flowLogService.deleteFlowLogs(region, List.of(physicalId));
    }
}
