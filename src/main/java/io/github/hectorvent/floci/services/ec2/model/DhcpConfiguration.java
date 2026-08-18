package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DhcpConfiguration {

    private String key;
    private List<String> values = new ArrayList<>();

    public DhcpConfiguration() {}

    public DhcpConfiguration(String key, List<String> values) {
        this.key = key;
        this.values = values != null ? values : new ArrayList<>();
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public List<String> getValues() { return values; }
    public void setValues(List<String> values) { this.values = values; }
}
