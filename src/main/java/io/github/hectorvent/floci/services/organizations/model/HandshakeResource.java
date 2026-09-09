package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of information attached to a handshake. AWS nests these — the {@code ORGANIZATION}
 * resource carries {@code MASTER_EMAIL} and {@code MASTER_NAME} children — so the shape is
 * recursive.
 */
@RegisterForReflection
public class HandshakeResource {

    private String value;
    private String type;
    private List<HandshakeResource> resources = new ArrayList<>();

    public HandshakeResource() {
    }

    public HandshakeResource(String value, String type) {
        this.value = value;
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<HandshakeResource> getResources() {
        return resources;
    }

    public void setResources(List<HandshakeResource> resources) {
        this.resources = resources;
    }
}
