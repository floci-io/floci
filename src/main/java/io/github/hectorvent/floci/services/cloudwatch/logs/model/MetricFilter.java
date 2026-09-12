package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A metric filter on a log group, the AWS MetricFilter shape. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricFilter {

    private String filterName;
    private String logGroupName;
    private String filterPattern;
    private List<MetricTransformation> metricTransformations = new ArrayList<>();
    private long creationTime;
    private Boolean applyOnTransformedLogs;
    private String fieldSelectionCriteria;
    private List<String> emitSystemFieldDimensions;

    public MetricFilter() {}

    public String getFilterName() { return filterName; }
    public void setFilterName(String filterName) { this.filterName = filterName; }

    public String getLogGroupName() { return logGroupName; }
    public void setLogGroupName(String logGroupName) { this.logGroupName = logGroupName; }

    public String getFilterPattern() { return filterPattern; }
    public void setFilterPattern(String filterPattern) { this.filterPattern = filterPattern; }

    public List<MetricTransformation> getMetricTransformations() { return metricTransformations; }
    public void setMetricTransformations(List<MetricTransformation> metricTransformations) {
        this.metricTransformations = metricTransformations != null ? metricTransformations : new ArrayList<>();
    }

    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long creationTime) { this.creationTime = creationTime; }

    public Boolean getApplyOnTransformedLogs() { return applyOnTransformedLogs; }
    public void setApplyOnTransformedLogs(Boolean applyOnTransformedLogs) {
        this.applyOnTransformedLogs = applyOnTransformedLogs;
    }

    public String getFieldSelectionCriteria() { return fieldSelectionCriteria; }
    public void setFieldSelectionCriteria(String fieldSelectionCriteria) {
        this.fieldSelectionCriteria = fieldSelectionCriteria;
    }

    public List<String> getEmitSystemFieldDimensions() { return emitSystemFieldDimensions; }
    public void setEmitSystemFieldDimensions(List<String> emitSystemFieldDimensions) {
        this.emitSystemFieldDimensions = emitSystemFieldDimensions;
    }
}
