package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::FlowLog}, backed by {@link FlowLogService}.
 * Ref and {@code Fn::GetAtt [Flow, Id]} both yield the {@code fl-} id, matching AWS. {@code Tags}
 * live in the EC2 tag store, as a {@code CreateFlowLogs} TagSpecification's do, and an update
 * reconciles them on the reused log. The reused log's tags are snapshotted before they change, so a
 * stack update that fails on a later resource puts them back.
 */
@ApplicationScoped
public class Ec2FlowLogCfnProvisioner implements CfnResourceProvisioner {

    /** AWS's default when MaxAggregationInterval is omitted (10 minutes). */
    private static final int DEFAULT_MAX_AGGREGATION_INTERVAL = 600;

    /** The defaults createFlowLog applies when the template omits these, kept in step with it. */
    private static final String DEFAULT_RESOURCE_TYPE = "VPC";
    private static final String DEFAULT_TRAFFIC_TYPE = "ALL";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The tags a reused flow log had before an update changed them, recorded so a stack rollback
     * can put them back. Internal, like the other {@code __Floci} attributes.
     */
    static final String PRIOR_TAGS_ATTR = "__FlociEc2FlowLogPriorTags";

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
                CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r), () -> {
                    snapshotTags(r, region, existingId, desiredTags);
                    Ec2Tags.reconcile(ec2Service, region, existingId, desiredTags);
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
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_TAGS_ATTR);
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_TAGS_ATTR);
    }

    /**
     * Tags are the only thing an update changes on a reused flow log, so putting back the snapshot
     * taken before the change restores it. Without a snapshot the update changed nothing.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        undoInPlace(resource);
        return true;
    }

    /**
     * Records the reused log's tags before the update changes them, only when they differ from
     * the template's, so an unchanged reuse leaves no snapshot.
     */
    private void snapshotTags(StackResource r, String region, String flowLogId, Map<String, String> desiredTags) {
        Map<String, String> currentTags = Ec2Tags.current(ec2Service, region, flowLogId);
        if (currentTags.equals(desiredTags)) {
            return;
        }
        ObjectNode prior = MAPPER.createObjectNode();
        prior.put("region", region);
        prior.put("flowLogId", flowLogId);
        ObjectNode tags = prior.putObject("tags");
        currentTags.forEach(tags::put);
        r.getAttributes().put(PRIOR_TAGS_ATTR, prior.toString());
    }

    /**
     * Puts a reused log's tags back from the snapshot. The snapshot is removed only after the
     * restore succeeds, so a failed one keeps it for the rollback walker's retry.
     */
    private void undoInPlace(StackResource r) {
        String snapshot = r.getAttributes().get(PRIOR_TAGS_ATTR);
        if (snapshot == null) {
            return;
        }
        JsonNode prior;
        try {
            prior = MAPPER.readTree(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable tag snapshot on " + r.getLogicalId(), e);
        }
        Map<String, String> priorTags = new LinkedHashMap<>();
        prior.path("tags").properties().forEach(f -> priorTags.put(f.getKey(), f.getValue().asText()));
        Ec2Tags.reconcile(ec2Service, prior.path("region").asText(), prior.path("flowLogId").asText(), priorTags);
        r.getAttributes().remove(PRIOR_TAGS_ATTR);
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
