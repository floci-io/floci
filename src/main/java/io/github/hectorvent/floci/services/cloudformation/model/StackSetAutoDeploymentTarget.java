package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StackSetAutoDeploymentTarget {
    private String stackSetName;
    private String organizationalUnitId;
    private List<String> regions = new ArrayList<>();

    public StackSetAutoDeploymentTarget() {}

    public StackSetAutoDeploymentTarget(String stackSetName, String organizationalUnitId, List<String> regions) {
        this.stackSetName = stackSetName;
        this.organizationalUnitId = organizationalUnitId;
        this.regions = regions == null ? new ArrayList<>() : new ArrayList<>(regions);
    }

    public String getStackSetName() { return stackSetName; }
    public void setStackSetName(String stackSetName) { this.stackSetName = stackSetName; }
    public String getOrganizationalUnitId() { return organizationalUnitId; }
    public void setOrganizationalUnitId(String organizationalUnitId) { this.organizationalUnitId = organizationalUnitId; }
    public List<String> getRegions() { return regions; }
    public void setRegions(List<String> regions) { this.regions = regions == null ? new ArrayList<>() : new ArrayList<>(regions); }
}
