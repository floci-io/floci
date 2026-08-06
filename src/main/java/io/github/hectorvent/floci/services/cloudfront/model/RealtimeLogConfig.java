package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class RealtimeLogConfig {

    private String arn;
    private String name;
    private long samplingRate;
    private List<String> fields;
    private List<Map<String, Object>> endPoints;

    public RealtimeLogConfig() {}

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getSamplingRate() { return samplingRate; }
    public void setSamplingRate(long samplingRate) { this.samplingRate = samplingRate; }

    public List<String> getFields() { return fields; }
    public void setFields(List<String> fields) { this.fields = fields; }

    public List<Map<String, Object>> getEndPoints() { return endPoints; }
    public void setEndPoints(List<Map<String, Object>> endPoints) { this.endPoints = endPoints; }
}
