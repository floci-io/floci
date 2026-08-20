package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.FlowLogService;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::FlowLog}, backed by {@link FlowLogService}.
 * Ref and {@code Fn::GetAtt [Flow, Id]} both yield the {@code fl-} id, matching AWS.
 */
@ApplicationScoped
public class Ec2FlowLogCfnProvisioner implements CfnResourceProvisioner {

    /** AWS's default when MaxAggregationInterval is omitted (10 minutes). */
    private static final int DEFAULT_MAX_AGGREGATION_INTERVAL = 600;

    /** The defaults createFlowLog applies when the template omits these, kept in step with it. */
    private static final String DEFAULT_RESOURCE_TYPE = "VPC";
    private static final String DEFAULT_TRAFFIC_TYPE = "ALL";
    private static final String DEFAULT_LOG_DESTINATION_TYPE = "s3";

    private final FlowLogService flowLogService;

    @Inject
    public Ec2FlowLogCfnProvisioner(FlowLogService flowLogService) {
        this.flowLogService = flowLogService;
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
        String existingId = r.getPhysicalId();
        if (existingId != null && !existingId.isBlank()) {
            FlowLog existing = flowLogService.describeFlowLogs(ctx.region(), List.of(existingId))
                    .stream().findFirst().orElse(null);
            if (existing != null) {
                // Every property except Tags is createOnly, so there is nothing to modify on the
                // reused log and a change to any of them is a replacement. Reusing regardless would
                // report the stack complete while DescribeFlowLogs kept serving the old config.
                requireUnchanged(existing, props, ctx);
                r.getAttributes().put("Id", existingId);
                return;
            }
        }
        FlowLog fl = flowLogService.createFlowLog(ctx.region(),
                ctx.resolveOptional(props, "ResourceId"),
                ctx.resolveOptional(props, "ResourceType"),
                ctx.resolveOptional(props, "TrafficType"),
                ctx.resolveOptional(props, "LogDestinationType"),
                ctx.resolveOptional(props, "LogDestination"),
                ctx.resolveOptional(props, "LogFormat"),
                props != null && props.hasNonNull("MaxAggregationInterval")
                        ? props.get("MaxAggregationInterval").asInt()
                        : DEFAULT_MAX_AGGREGATION_INTERVAL);
        r.setPhysicalId(fl.getFlowLogId());
        r.getAttributes().put("Id", fl.getFlowLogId());
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
                orDefault(ctx.resolveOptional(props, "LogDestinationType"), DEFAULT_LOG_DESTINATION_TYPE));
        rejectIfChanged("LogDestination", existing.getLogDestination(),
                ctx.resolveOptional(props, "LogDestination"));
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
