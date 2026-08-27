package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcConnectivity {

    @JsonProperty("clientAuthentication")
    private ClientAuthentication clientAuthentication;

    public VpcConnectivity() {}

    public ClientAuthentication getClientAuthentication() { return clientAuthentication; }
    public void setClientAuthentication(ClientAuthentication clientAuthentication) { this.clientAuthentication = clientAuthentication; }
}