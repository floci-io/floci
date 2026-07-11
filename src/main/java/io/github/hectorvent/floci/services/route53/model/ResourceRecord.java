package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ResourceRecord {

    private String value;

    public ResourceRecord() {}

    public ResourceRecord(String value) {
        this.value = value;
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
