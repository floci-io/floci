package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.model.Dashboard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::CloudWatch::Dashboard}. Alarms belong to {@link CloudWatchCfnProvisioner};
 * this class takes only the dashboards service so the test fixture can wire it from that service.
 *
 * <p>The dashboard name is the physical id and {@code Ref}, generated when the template gives none
 * and kept across updates. It is create-only: an update that keeps it puts the body again and
 * drives the tags to the template's set, since {@code PutDashboard} leaves an existing dashboard's
 * tags alone; an update that changes it creates the new dashboard and leaves the displaced one to
 * the {@link ReplacementCleanup} record, deleted once the update commits or put back on rollback.
 *
 * <p>The schema declares no read-only properties, so there is nothing for {@code Fn::GetAtt}.
 */
@ApplicationScoped
public class CloudWatchDashboardCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::CloudWatch::Dashboard";
    private static final int DASHBOARD_NAME_MAX_LENGTH = 255;

    private final CloudWatchDashboardsService dashboardsService;

    @Inject
    public CloudWatchDashboardCfnProvisioner(CloudWatchDashboardsService dashboardsService) {
        this.dashboardsService = dashboardsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String explicitName = ctx.resolveOptional(props, "DashboardName");
        if (explicitName != null && explicitName.length() > DASHBOARD_NAME_MAX_LENGTH) {
            throw new AwsException("ValidationError",
                    TYPE + " DashboardName must be at most " + DASHBOARD_NAME_MAX_LENGTH + " characters", 400);
        }
        // The body is a string on the wire; a template may still write it as a JSON object.
        String body = props == null ? null : ctx.engine().resolveJsonAttribute(props.path("DashboardBody"));
        if (body == null || body.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires DashboardBody", 400);
        }
        String name = ctx.stablePhysicalName(explicitName, r.getLogicalId(), DASHBOARD_NAME_MAX_LENGTH, false);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        // PutDashboard creates or replaces the body in full, and applies the tags on create only.
        Dashboard dashboard = dashboardsService.putDashboard(name, body, tags, ctx.region());
        if (ctx.reusesPriorEntity(name)) {
            reconcileTags(dashboard.getDashboardArn(), tags, ctx.region());
        }
        r.setPhysicalId(name);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void reconcileTags(String dashboardArn, Map<String, String> desired, String region) {
        Map<String, String> current = dashboardsService.listTagsForResource(dashboardArn, region);
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            dashboardsService.untagResource(dashboardArn, stale, region);
        }
        if (!desired.isEmpty()) {
            dashboardsService.tagResource(dashboardArn, desired, region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        // ResourceNotFound is CloudWatch's own code for a dashboard that is already gone.
        CfnDeletes.safeDelete("CloudWatch dashboard", physicalId,
                () -> dashboardsService.deleteDashboards(List.of(physicalId), region),
                "ResourceNotFound");
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

    /**
     * A replacement is undone through the cleanup record. An in-place update keeps no snapshot of
     * the previous body and tags, so the engine is told it was not rolled back.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }
}
