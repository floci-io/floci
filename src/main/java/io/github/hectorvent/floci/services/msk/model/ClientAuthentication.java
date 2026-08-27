package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientAuthentication {

    @JsonProperty("sasl")
    private Sasl sasl;

    @JsonProperty("tls")
    private Tls tls;

    @JsonProperty("unauthenticated")
    private Unauthenticated unauthenticated;

    public ClientAuthentication() {}

    public Sasl getSasl() { return sasl; }
    public void setSasl(Sasl sasl) { this.sasl = sasl; }

    public Tls getTls() { return tls; }
    public void setTls(Tls tls) { this.tls = tls; }

    public Unauthenticated getUnauthenticated() { return unauthenticated; }
    public void setUnauthenticated(Unauthenticated unauthenticated) { this.unauthenticated = unauthenticated; }
}