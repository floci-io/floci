package io.floci.aws.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportGroup {
    public ReportGroup() {}

    private String arn;
    private String name;
    private String type;
    private Map<String, Object> exportConfig;
    private Double created;
    private Double lastModified;
    private List<Map<String, String>> tags;
    private String status;

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Map<String, Object> getExportConfig() { return exportConfig; }
    public void setExportConfig(Map<String, Object> exportConfig) { this.exportConfig = exportConfig; }

    public Double getCreated() { return created; }
    public void setCreated(Double created) { this.created = created; }

    public Double getLastModified() { return lastModified; }
    public void setLastModified(Double lastModified) { this.lastModified = lastModified; }

    public List<Map<String, String>> getTags() { return tags; }
    public void setTags(List<Map<String, String>> tags) { this.tags = tags; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
