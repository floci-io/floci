package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExportSummary {

    @JsonProperty("ExportArn")
    private String exportArn;

    @JsonProperty("ExportStatus")
    private String exportStatus;

    @JsonProperty("ExportType")
    private String exportType;

    public ExportSummary() {}

    public ExportSummary(ExportDescription desc) {
        this.exportArn = desc.getExportArn();
        this.exportStatus = desc.getExportStatus();
        this.exportType = desc.getExportType();
    }

    public String getExportArn() { return exportArn; }
    public void setExportArn(String exportArn) { this.exportArn = exportArn; }

    public String getExportStatus() { return exportStatus; }
    public void setExportStatus(String exportStatus) { this.exportStatus = exportStatus; }

    public String getExportType() { return exportType; }
    public void setExportType(String exportType) { this.exportType = exportType; }
}
