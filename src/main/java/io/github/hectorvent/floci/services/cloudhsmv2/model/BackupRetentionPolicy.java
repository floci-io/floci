package io.github.hectorvent.floci.services.cloudhsmv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class BackupRetentionPolicy {
    private String type;
    private String value;

    public BackupRetentionPolicy() {}

    public BackupRetentionPolicy(String type, String value) {
        this.type = type;
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
