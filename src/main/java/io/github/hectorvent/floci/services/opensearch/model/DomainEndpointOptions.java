package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * HTTPS / TLS-policy endpoint options. Round-tripped only — Floci serves the
 * domain over plain HTTP regardless of {@code EnforceHTTPS}. SDK clients still
 * need the field to round-trip so {@code DescribeDomain} confirms the
 * intended configuration.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DomainEndpointOptions {

    @JsonProperty("EnforceHTTPS")
    private boolean enforceHttps = false;

    @JsonProperty("TLSSecurityPolicy")
    private String tlsSecurityPolicy = "Policy-Min-TLS-1-0-2019-07";

    @JsonProperty("CustomEndpointEnabled")
    private boolean customEndpointEnabled = false;

    @JsonProperty("CustomEndpoint")
    private String customEndpoint;

    @JsonProperty("CustomEndpointCertificateArn")
    private String customEndpointCertificateArn;

    public DomainEndpointOptions() {}

    public boolean isEnforceHttps() {
        return enforceHttps;
    }

    public void setEnforceHttps(boolean enforceHttps) {
        this.enforceHttps = enforceHttps;
    }

    public String getTlsSecurityPolicy() {
        return tlsSecurityPolicy;
    }

    public void setTlsSecurityPolicy(String tlsSecurityPolicy) {
        this.tlsSecurityPolicy = tlsSecurityPolicy;
    }

    public boolean isCustomEndpointEnabled() {
        return customEndpointEnabled;
    }

    public void setCustomEndpointEnabled(boolean customEndpointEnabled) {
        this.customEndpointEnabled = customEndpointEnabled;
    }

    public String getCustomEndpoint() {
        return customEndpoint;
    }

    public void setCustomEndpoint(String customEndpoint) {
        this.customEndpoint = customEndpoint;
    }

    public String getCustomEndpointCertificateArn() {
        return customEndpointCertificateArn;
    }

    public void setCustomEndpointCertificateArn(String customEndpointCertificateArn) {
        this.customEndpointCertificateArn = customEndpointCertificateArn;
    }
}
