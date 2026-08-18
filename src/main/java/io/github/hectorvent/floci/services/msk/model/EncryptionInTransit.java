package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EncryptionInTransit {

    @JsonProperty("clientBroker")
    private String clientBroker;

    @JsonProperty("inCluster")
    private Boolean inCluster;

    public EncryptionInTransit() {}

    public String getClientBroker() { return clientBroker; }
    public void setClientBroker(String clientBroker) { this.clientBroker = clientBroker; }

    public Boolean getInCluster() { return inCluster; }
    public void setInCluster(Boolean inCluster) { this.inCluster = inCluster; }
}