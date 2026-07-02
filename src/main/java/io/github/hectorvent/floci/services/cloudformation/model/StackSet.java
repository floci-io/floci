package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StackSet {
    private String stackSetId;
    private String stackSetName;
    private String description;
    private String status = "ACTIVE";
    private String templateBody;
    private List<String> capabilities = new ArrayList<>();
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    /** SELF_MANAGED or SERVICE_MANAGED; Floci only models SELF_MANAGED behavior. */
    private String permissionModel = "SELF_MANAGED";

    public String getStackSetId() { return stackSetId; }
    public void setStackSetId(String stackSetId) { this.stackSetId = stackSetId; }
    public String getStackSetName() { return stackSetName; }
    public void setStackSetName(String stackSetName) { this.stackSetName = stackSetName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTemplateBody() { return templateBody; }
    public void setTemplateBody(String templateBody) { this.templateBody = templateBody; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getPermissionModel() { return permissionModel; }
    public void setPermissionModel(String permissionModel) { this.permissionModel = permissionModel; }
}
