package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-configuration-set open/click tracking override. Mirrors the AWS SES V2
 * {@code TrackingOptions} shape: a custom redirect domain (which must be a
 * verified identity) and the HTTPS policy applied to tracking links.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrackingOptions {

    @JsonProperty("CustomRedirectDomain")
    private String customRedirectDomain;

    @JsonProperty("HttpsPolicy")
    private String httpsPolicy;

    public TrackingOptions() {}

    public String getCustomRedirectDomain() { return customRedirectDomain; }
    public void setCustomRedirectDomain(String customRedirectDomain) {
        this.customRedirectDomain = customRedirectDomain;
    }

    public String getHttpsPolicy() { return httpsPolicy; }
    public void setHttpsPolicy(String httpsPolicy) { this.httpsPolicy = httpsPolicy; }
}
