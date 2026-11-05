package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A vended-logs delivery: one delivery source paired with one delivery destination. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Delivery {
    private String id;
    private String arn;
    private String deliverySourceName;
    private String deliveryDestinationArn;
    private String deliveryDestinationType;
    private List<String> recordFields = new ArrayList<>();
    private String fieldDelimiter;
    private Boolean enableHiveCompatiblePath;
    private String suffixPath;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getDeliverySourceName() { return deliverySourceName; }
    public void setDeliverySourceName(String name) { this.deliverySourceName = name; }
    public String getDeliveryDestinationArn() { return deliveryDestinationArn; }
    public void setDeliveryDestinationArn(String arn) { this.deliveryDestinationArn = arn; }
    public String getDeliveryDestinationType() { return deliveryDestinationType; }
    public void setDeliveryDestinationType(String type) { this.deliveryDestinationType = type; }
    public List<String> getRecordFields() { return recordFields; }
    public void setRecordFields(List<String> recordFields) {
        this.recordFields = recordFields != null ? recordFields : new ArrayList<>();
    }
    public String getFieldDelimiter() { return fieldDelimiter; }
    public void setFieldDelimiter(String fieldDelimiter) { this.fieldDelimiter = fieldDelimiter; }
    public Boolean getEnableHiveCompatiblePath() { return enableHiveCompatiblePath; }
    public void setEnableHiveCompatiblePath(Boolean enable) { this.enableHiveCompatiblePath = enable; }
    public String getSuffixPath() { return suffixPath; }
    public void setSuffixPath(String suffixPath) { this.suffixPath = suffixPath; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new LinkedHashMap<>(); }
}
