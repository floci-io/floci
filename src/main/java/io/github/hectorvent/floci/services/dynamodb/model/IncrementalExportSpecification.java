package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IncrementalExportSpecification {

    @JsonProperty("ExportFromTime")
    private BigDecimal exportFromTime;

    @JsonProperty("ExportToTime")
    private BigDecimal exportToTime;

    @JsonProperty("ExportViewType")
    private String exportViewType;

    public IncrementalExportSpecification() {}

    public IncrementalExportSpecification(BigDecimal exportFromTime, BigDecimal exportToTime, String exportViewType) {
        this.exportFromTime = exportFromTime;
        this.exportToTime = exportToTime;
        this.exportViewType = exportViewType;
    }

    public BigDecimal getExportFromTime() { return exportFromTime; }
    public void setExportFromTime(BigDecimal exportFromTime) { this.exportFromTime = exportFromTime; }

    public BigDecimal getExportToTime() { return exportToTime; }
    public void setExportToTime(BigDecimal exportToTime) { this.exportToTime = exportToTime; }

    public String getExportViewType() { return exportViewType; }
    public void setExportViewType(String exportViewType) { this.exportViewType = exportViewType; }
}
