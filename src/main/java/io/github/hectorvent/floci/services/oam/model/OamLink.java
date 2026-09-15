package io.github.hectorvent.floci.services.oam.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class OamLink {
    private String arn;
    private String id;
    private String label;
    private String labelTemplate;
    private String sinkArn;
    private String region;
    private String sourceAccountId;
    private List<String> resourceTypes = List.of();
    private Map<String, Object> linkConfiguration = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public OamLink() {}

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getLabelTemplate() { return labelTemplate; }
    public void setLabelTemplate(String labelTemplate) { this.labelTemplate = labelTemplate; }
    public String getSinkArn() { return sinkArn; }
    public void setSinkArn(String sinkArn) { this.sinkArn = sinkArn; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getSourceAccountId() { return sourceAccountId; }
    public void setSourceAccountId(String sourceAccountId) { this.sourceAccountId = sourceAccountId; }
    public List<String> getResourceTypes() { return resourceTypes; }
    public void setResourceTypes(List<String> resourceTypes) { this.resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes); }
    public Map<String, Object> getLinkConfiguration() { return linkConfiguration; }
    public void setLinkConfiguration(Map<String, Object> linkConfiguration) { this.linkConfiguration = linkConfiguration == null ? new LinkedHashMap<>() : new LinkedHashMap<>(linkConfiguration); }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }
}
