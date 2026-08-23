package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The root of an organization's OU hierarchy. An organization has exactly one root,
 * so it is stored inside {@link Organization} rather than in its own backend.
 */
@RegisterForReflection
public class Root {

    private String id;
    private String arn;
    private String name = "Root";
    private List<PolicyTypeSummary> policyTypes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<PolicyTypeSummary> getPolicyTypes() {
        return policyTypes;
    }

    public void setPolicyTypes(List<PolicyTypeSummary> policyTypes) {
        this.policyTypes = policyTypes;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
