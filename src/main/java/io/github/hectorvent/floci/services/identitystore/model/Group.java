package io.github.hectorvent.floci.services.identitystore.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Group {
    private String groupId;
    private String identityStoreId;
    private ObjectNode attributes;
    private String createdAt;
    private String updatedAt;

    public Group() {
    }

    public Group(String groupId, String identityStoreId, ObjectNode attributes, String createdAt, String updatedAt) {
        this.groupId = groupId;
        this.identityStoreId = identityStoreId;
        this.attributes = attributes;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String groupId() {
        return groupId;
    }

    public String identityStoreId() {
        return identityStoreId;
    }

    public ObjectNode attributes() {
        return attributes;
    }

    public String createdAt() {
        return createdAt;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public String displayName() {
        return textAttribute("DisplayName");
    }

    public String description() {
        return textAttribute("Description");
    }

    public void setAttributes(ObjectNode attributes) {
        this.attributes = attributes;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    private String textAttribute(String name) {
        if (attributes == null || !attributes.path(name).isTextual()) {
            return null;
        }
        return attributes.path(name).textValue();
    }
}
