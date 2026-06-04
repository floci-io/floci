package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-configuration-set override of the account-level suppression reasons.
 * Mirrors the AWS SES V2 {@code SuppressionOptions} shape. When present on a
 * {@link ConfigurationSet}, its {@code SuppressedReasons} take precedence over
 * the account-level {@link AccountSuppressionAttributes} for any send using
 * that configuration set; an empty list explicitly disables suppression
 * filtering for the set.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SuppressionOptions {

    @JsonProperty("SuppressedReasons")
    private List<String> suppressedReasons = new ArrayList<>();

    public SuppressionOptions() {}

    public List<String> getSuppressedReasons() { return suppressedReasons; }
    public void setSuppressedReasons(List<String> suppressedReasons) {
        this.suppressedReasons = suppressedReasons != null ? suppressedReasons : new ArrayList<>();
    }
}
