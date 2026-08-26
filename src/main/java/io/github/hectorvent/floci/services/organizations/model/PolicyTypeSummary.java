package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A policy type and its enablement status on a root, as returned inside
 * {@code ListRoots} and {@code DescribeOrganization}.
 */
@RegisterForReflection
public class PolicyTypeSummary {

    private String type;
    private String status;

    public PolicyTypeSummary() {
    }

    public PolicyTypeSummary(String type, String status) {
        this.type = type;
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
