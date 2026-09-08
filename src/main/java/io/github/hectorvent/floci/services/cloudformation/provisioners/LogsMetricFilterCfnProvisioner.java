package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Provisions {@code AWS::Logs::MetricFilter}. Log groups belong to {@link LogsCfnProvisioner}; this
 * class takes only the metric filter service so the test fixture can wire it from that service.
 *
 * <p>The physical id is the registry schema's composite primary identifier, {@code LogGroupName}
 * and {@code FilterName} joined by a pipe. A filter name may itself hold a pipe, a log group name
 * cannot, so the id is split at its first pipe. Both parts are create-only: an update that keeps
 * them puts the filter again, which is how PutMetricFilter updates, after keeping the definition it
 * replaces on the resource so a failed stack update can put it back; an update that changes
 * either, or drops an explicit name for a generated one, creates the new filter and leaves the
 * displaced one to the {@link ReplacementCleanup} record.
 *
 * <p>The schema declares no read-only properties, so there is nothing for {@code Fn::GetAtt}.
 */
@ApplicationScoped
public class LogsMetricFilterCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Logs::MetricFilter";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FILTER_NAME_MAX_LENGTH = 512;
    /** Records whether the name came from the template or was generated; see {@link LogsCfnProvisioner}. */
    private static final String NAME_MODE_ATTR = "FlociMetricFilterNameMode";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";

    private final CloudWatchLogsMetricFilterService metricFilters;

    @Inject
    public LogsMetricFilterCfnProvisioner(CloudWatchLogsMetricFilterService metricFilters) {
        this.metricFilters = metricFilters;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        MetricFilter definition = definition(props, ctx);
        String explicitName = ctx.resolveOptional(props, "FilterName");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String filterName = filterName(r, ctx, explicitName, hasExplicitName);
        definition.setFilterName(filterName);

        String physicalId = definition.getLogGroupName() + "|" + filterName;
        if (ctx.reusesPriorEntity(physicalId)) {
            snapshotBeforeUpdate(r, definition.getLogGroupName(), filterName, ctx.region());
        }
        metricFilters.putMetricFilter(definition, ctx.region());
        r.setPhysicalId(physicalId);
        r.getAttributes().put(NAME_MODE_ATTR, hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** The template's name, else the generated name the filter already has unless an explicit one was dropped. */
    private static String filterName(StackResource r, ProvisionContext ctx, String explicitName, boolean hasExplicitName) {
        if (hasExplicitName) {
            return explicitName;
        }
        boolean explicitNameRemoved = ctx.isUpdate()
                && NAME_MODE_EXPLICIT.equals(r.getAttributes().get(NAME_MODE_ATTR));
        if (ctx.isUpdate() && !explicitNameRemoved) {
            String prior = ctx.priorPhysicalId();
            int separator = prior.indexOf('|');
            if (separator > 0 && separator < prior.length() - 1) {
                return prior.substring(separator + 1);
            }
        }
        return ctx.generatePhysicalName(r.getLogicalId(), FILTER_NAME_MAX_LENGTH, false);
    }

    private MetricFilter definition(JsonNode props, ProvisionContext ctx) {
        MetricFilter definition = new MetricFilter();
        String logGroupName = ctx.resolveOptional(props, "LogGroupName");
        if (logGroupName == null || logGroupName.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires LogGroupName", 400);
        }
        definition.setLogGroupName(logGroupName);
        if (props == null || !props.has("FilterPattern") || props.get("FilterPattern").isNull()) {
            throw new AwsException("ValidationError", TYPE + " requires FilterPattern", 400);
        }
        definition.setFilterPattern(ctx.engine().resolve(props.get("FilterPattern")));

        JsonNode transformations = props.has("MetricTransformations")
                ? ctx.engine().resolveNode(props.get("MetricTransformations")) : null;
        if (transformations == null || !transformations.isArray() || transformations.size() != 1) {
            throw new AwsException("ValidationError",
                    TYPE + " requires MetricTransformations with exactly one transformation", 400);
        }
        definition.setMetricTransformations(List.of(transformation(transformations.get(0))));

        String applyOnTransformedLogs = ctx.resolveOptional(props, "ApplyOnTransformedLogs");
        if (applyOnTransformedLogs != null && !applyOnTransformedLogs.isBlank()) {
            definition.setApplyOnTransformedLogs(Boolean.parseBoolean(applyOnTransformedLogs));
        }
        definition.setFieldSelectionCriteria(ctx.resolveOptional(props, "FieldSelectionCriteria"));
        if (props.has("EmitSystemFieldDimensions")) {
            definition.setEmitSystemFieldDimensions(ctx.resolveStringList(props, "EmitSystemFieldDimensions"));
        }
        return definition;
    }

    private static MetricTransformation transformation(JsonNode node) {
        MetricTransformation t = new MetricTransformation();
        t.setMetricName(text(node, "MetricName"));
        t.setMetricNamespace(text(node, "MetricNamespace"));
        t.setMetricValue(text(node, "MetricValue"));
        JsonNode defaultValue = node.get("DefaultValue");
        if (defaultValue != null && !defaultValue.isNull()) {
            try {
                t.setDefaultValue(Double.parseDouble(defaultValue.asText().trim()));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        TYPE + " DefaultValue must be a number, got '" + defaultValue.asText() + "'", 400);
            }
        }
        JsonNode dimensions = node.get("Dimensions");
        if (dimensions != null && dimensions.isArray()) {
            Map<String, String> byKey = new LinkedHashMap<>();
            for (JsonNode dimension : dimensions) {
                String key = text(dimension, "Key");
                String value = text(dimension, "Value");
                if (key == null || value == null) {
                    throw new AwsException("ValidationError", TYPE + " Dimensions entries need Key and Value", 400);
                }
                byKey.put(key, value);
            }
            t.setDimensions(byKey);
        }
        t.setUnit(text(node, "Unit"));
        return t;
    }

    private static String text(JsonNode node, String member) {
        JsonNode value = node.get(member);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Keeps the definition the filter has before an in-place update replaces it, so
     * {@link #rollbackUpdate} can put it back. A filter that is already gone is recorded as absent,
     * so a rollback removes the one the update created in its place.
     */
    private void snapshotBeforeUpdate(StackResource r, String logGroupName, String filterName, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("logGroupName", logGroupName);
        snapshot.put("filterName", filterName);
        Optional<MetricFilter> current = metricFilters.findMetricFilter(logGroupName, filterName, region);
        if (current.isPresent()) {
            snapshot.set("definition", MAPPER.valueToTree(current.get()));
        } else {
            snapshot.put("absent", true);
        }
        r.getAttributes().put(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null) {
            return;
        }
        int separator = physicalId.indexOf('|');
        if (separator <= 0 || separator == physicalId.length() - 1) {
            return;
        }
        String logGroupName = physicalId.substring(0, separator);
        String filterName = physicalId.substring(separator + 1);
        // The service answers ResourceNotFoundException for a missing filter and for a log group
        // that is already gone; either way there is nothing left to delete.
        CfnDeletes.safeDelete("metric filter", physicalId,
                () -> metricFilters.deleteMetricFilter(logGroupName, filterName, region),
                "ResourceNotFoundException");
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
        resource.getAttributes().remove(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record and an in-place update from the snapshot
     * taken before it. With neither on the resource the provision failed before it changed anything,
     * since every mutation is preceded by the snapshot or followed by the record.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        String raw = resource.getAttributes().get(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the metric filter update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String region = snapshot.path("region").asText();
        if (snapshot.path("absent").asBoolean(false)) {
            delete(TYPE, snapshot.path("logGroupName").asText() + "|" + snapshot.path("filterName").asText(), region);
        } else {
            MetricFilter definition;
            try {
                definition = MAPPER.treeToValue(snapshot.get("definition"), MetricFilter.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not read the metric filter update snapshot for "
                        + resource.getLogicalId(), e);
            }
            metricFilters.putMetricFilter(definition, region);
        }
        resource.getAttributes().remove(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR);
        return true;
    }
}
