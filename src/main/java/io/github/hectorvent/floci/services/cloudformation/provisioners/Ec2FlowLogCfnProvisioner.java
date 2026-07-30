package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.FlowLogService;
import io.github.hectorvent.floci.services.ec2.model.FlowLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::FlowLog}, backed by {@link FlowLogService}.
 * Ref and {@code Fn::GetAtt [Flow, Id]} both yield the {@code fl-} id, matching AWS.
 */
@ApplicationScoped
public class Ec2FlowLogCfnProvisioner implements CfnResourceProvisioner {

    /** AWS's default when MaxAggregationInterval is omitted (10 minutes). */
    private static final int DEFAULT_MAX_AGGREGATION_INTERVAL = 600;

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

    /** Without this the flow log outlives its stack and keeps showing up in DescribeFlowLogs. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        flowLogService.deleteFlowLogs(region, List.of(physicalId));
    }
}
