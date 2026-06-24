package io.github.hectorvent.floci.services.s3vectors.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class VectorData {
    private String key;
    private List<Float> data;
    private Map<String, Object> metadata;

    public VectorData() {}

    public VectorData(String key, List<Float> data, Map<String, Object> metadata) {
        this.key = key;
        this.data = data;
        this.metadata = metadata;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public List<Float> getData() { return data; }
    public void setData(List<Float> data) { this.data = data; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
