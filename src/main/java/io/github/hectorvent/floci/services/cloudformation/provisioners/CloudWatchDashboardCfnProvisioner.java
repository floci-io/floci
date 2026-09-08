package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.model.Dashboard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
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
 * tags alone, and keeps the body and tags it replaces on the resource so a failed stack update can
 * put them back. An update that changes it, or drops an explicit name for a generated one, creates
 * the new dashboard and leaves the displaced one to the {@link ReplacementCleanup} record, deleted
 * once the update commits or put back on rollback.
 *
 * <p>The schema declares no read-only properties, so there is nothing for {@code Fn::GetAtt}.
 */
@ApplicationScoped
public class CloudWatchDashboardCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::CloudWatch::Dashboard";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DASHBOARD_NAME_MAX_LENGTH = 255;
    /** The registry schema's {@code maxItems} for Tags, which CloudFormation checks before the handler runs. */
    private static final int MAX_TAGS = 50;
    /**
     * Records whether the dashboard's name came from the template or was generated, so a later
     * update can tell an explicit name being dropped, which replaces the dashboard on AWS, from
     * an unnamed dashboard keeping the name it was given. Read back off the stored attributes.
     */
    private static final String NAME_MODE_ATTR = "FlociDashboardNameMode";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";

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
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String explicitName = ctx.resolveOptional(props, "DashboardName");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        if (hasExplicitName && explicitName.length() > DASHBOARD_NAME_MAX_LENGTH) {
            throw new AwsException("ValidationError",
                    TYPE + " DashboardName must be at most " + DASHBOARD_NAME_MAX_LENGTH + " characters", 400);
        }
        // The body is a string on the wire; a template may still write it as a JSON object.
        String body = props == null ? null : ctx.engine().resolveJsonAttribute(props.path("DashboardBody"));
        if (body == null || body.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires DashboardBody", 400);
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("ValidationError",
                    TYPE + " Tags must hold at most " + MAX_TAGS + " entries, got " + tags.size(), 400);
        }
        String name = physicalName(r, ctx, explicitName, hasExplicitName);

        if (ctx.reusesPriorEntity(name)) {
            snapshotBeforeUpdate(r, name, ctx.region());
        }
        // PutDashboard creates or replaces the body in full, and applies the tags on create only.
        Dashboard dashboard = dashboardsService.putDashboard(name, body, tags, ctx.region());
        if (ctx.reusesPriorEntity(name)) {
            reconcileTags(dashboard.getDashboardArn(), tags, ctx.region());
        }
        r.setPhysicalId(name);
        r.getAttributes().put(NAME_MODE_ATTR, hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * The template's name when it gives one; otherwise the generated name the dashboard already
     * has, unless the previous name was explicit, since dropping an explicit name is a replacement
     * on AWS rather than the old name living on; and a fresh generated name failing both.
     */
    private static String physicalName(StackResource r, ProvisionContext ctx, String explicitName,
                                       boolean hasExplicitName) {
        if (hasExplicitName) {
            return explicitName;
        }
        boolean explicitNameRemoved = ctx.isUpdate()
                && NAME_MODE_EXPLICIT.equals(r.getAttributes().get(NAME_MODE_ATTR));
        if (ctx.isUpdate() && !explicitNameRemoved) {
            return ctx.priorPhysicalId();
        }
        return ctx.generatePhysicalName(r.getLogicalId(), DASHBOARD_NAME_MAX_LENGTH, false);
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

    /**
     * Keeps the body and tags the dashboard has before an in-place update changes them, so
     * {@link #rollbackUpdate} can put them back. A dashboard that is already gone is recorded as
     * absent, so a rollback removes the one the update created in its place.
     */
    private void snapshotBeforeUpdate(StackResource r, String name, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", name);
        try {
            Dashboard current = dashboardsService.getDashboard(name, region);
            snapshot.put("body", current.getDashboardBody());
            ObjectNode tags = snapshot.putObject("tags");
            current.getTags().forEach(tags::put);
        } catch (AwsException e) {
            if (!"ResourceNotFound".equals(e.getErrorCode())) {
                throw e;
            }
            snapshot.put("absent", true);
        }
        r.getAttributes().put(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
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
        resource.getAttributes().remove(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record and an in-place update from the snapshot
     * taken before it: the body is put back and the tags driven to the set the snapshot holds.
     * With neither on the resource the provision failed before it changed anything, since every
     * mutation is preceded by the snapshot or followed by the record, so there is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        String raw = resource.getAttributes().get(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the dashboard update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String region = snapshot.path("region").asText();
        String name = snapshot.path("name").asText();
        if (snapshot.path("absent").asBoolean(false)) {
            delete(TYPE, name, region);
        } else {
            Map<String, String> tags = new LinkedHashMap<>();
            snapshot.path("tags").fields().forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
            Dashboard restored = dashboardsService.putDashboard(name, snapshot.path("body").asText(), tags, region);
            reconcileTags(restored.getDashboardArn(), tags, region);
        }
        resource.getAttributes().remove(CfnRollback.DASHBOARD_UPDATE_SNAPSHOT_ATTR);
        return true;
    }
}
