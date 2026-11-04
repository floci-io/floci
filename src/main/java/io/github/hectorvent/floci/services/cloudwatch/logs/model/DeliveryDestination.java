package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A vended-logs delivery destination: a log group, bucket, Firehose stream or X-Ray. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeliveryDestination {
    private String name;
    private String arn;
    private String deliveryDestinationType;
    private String outputFormat;
    private String destinationResourceArn;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getDeliveryDestinationType() { return deliveryDestinationType; }
    public void setDeliveryDestinationType(String type) { this.deliveryDestinationType = type; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    public String getDestinationResourceArn() { return destinationResourceArn; }
    public void setDestinationResourceArn(String arn) { this.destinationResourceArn = arn; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new LinkedHashMap<>(); }
}
