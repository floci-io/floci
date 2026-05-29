package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountSuppressionAttributes {

    @JsonProperty("SuppressedReasons")
    private List<String> suppressedReasons = new ArrayList<>();

    public AccountSuppressionAttributes() {}

    public List<String> getSuppressedReasons() { return suppressedReasons; }
    public void setSuppressedReasons(List<String> suppressedReasons) {
        this.suppressedReasons = suppressedReasons != null ? suppressedReasons : new ArrayList<>();
    }
}
