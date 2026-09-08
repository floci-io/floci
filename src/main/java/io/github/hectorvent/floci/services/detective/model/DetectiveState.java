package io.github.hectorvent.floci.services.detective.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DetectiveState {
    private String adminAccountId;
    private boolean graph;
    private boolean autoEnable;
    private Map<String, DetectiveMember> members = new LinkedHashMap<>();

    public DetectiveState() {
    }

    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public boolean isGraph() { return graph; }
    public void setGraph(boolean graph) { this.graph = graph; }
    public boolean isAutoEnable() { return autoEnable; }
    public void setAutoEnable(boolean autoEnable) { this.autoEnable = autoEnable; }
    public Map<String, DetectiveMember> getMembers() { return members; }
    public void setMembers(Map<String, DetectiveMember> members) { this.members = members; }
}
