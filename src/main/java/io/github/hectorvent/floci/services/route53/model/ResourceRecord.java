package io.github.hectorvent.floci.services.route53.model;

public class ResourceRecord {

    private String value;

    public ResourceRecord() {}

    public ResourceRecord(String value) {
        this.value = value;
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
