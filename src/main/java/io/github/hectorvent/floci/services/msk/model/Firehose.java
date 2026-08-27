package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Firehose {

    @JsonProperty("deliveryStream")
    private String deliveryStream;

    @JsonProperty("enabled")
    private Boolean enabled;

    public Firehose() {}

    public String getDeliveryStream() { return deliveryStream; }
    public void setDeliveryStream(String deliveryStream) { this.deliveryStream = deliveryStream; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}