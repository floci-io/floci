package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-configuration-set delivery override. Mirrors the AWS SES V2
 * {@code DeliveryOptions} shape: the TLS policy, an optional dedicated-IP
 * sending pool, and the maximum delivery time.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeliveryOptions {

    @JsonProperty("TlsPolicy")
    private String tlsPolicy;

    @JsonProperty("SendingPoolName")
    private String sendingPoolName;

    @JsonProperty("MaxDeliverySeconds")
    private Long maxDeliverySeconds;

    public DeliveryOptions() {}

    public String getTlsPolicy() { return tlsPolicy; }
    public void setTlsPolicy(String tlsPolicy) { this.tlsPolicy = tlsPolicy; }

    public String getSendingPoolName() { return sendingPoolName; }
    public void setSendingPoolName(String sendingPoolName) { this.sendingPoolName = sendingPoolName; }

    public Long getMaxDeliverySeconds() { return maxDeliverySeconds; }
    public void setMaxDeliverySeconds(Long maxDeliverySeconds) { this.maxDeliverySeconds = maxDeliverySeconds; }
}
