package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterIdentity {

    @JsonProperty("oidc")
    private OidcIdentity oidc;

    public ClusterIdentity() {}

    public ClusterIdentity(OidcIdentity oidc) {
        this.oidc = oidc;
    }

    public OidcIdentity getOidc() { return oidc; }
    public void setOidc(OidcIdentity oidc) { this.oidc = oidc; }
}
