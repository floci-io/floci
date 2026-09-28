package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A REST (v1) VPC Link: the private connection between an API and a Network Load Balancer, named
 * by an integration's {@code connectionId} when its {@code connectionType} is {@code VPC_LINK}.
 *
 * <p>Distinct from the v2 {@code VpcLink}, which is described by subnets and security groups rather
 * than by target ARNs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class VpcLink {

    private String id;
    private String name;
    private String description;
    private List<String> targetArns = new ArrayList<>();
    /** PENDING, AVAILABLE, DELETING or FAILED. */
    private String status = "AVAILABLE";
    private String statusMessage;
    private Map<String, String> tags = new HashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTargetArns() {
        return targetArns;
    }

    public void setTargetArns(List<String> targetArns) {
        this.targetArns = targetArns != null ? targetArns : new ArrayList<>();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }
}
