package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReceiptRule {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("TlsPolicy")
    private String tlsPolicy;

    @JsonProperty("Recipients")
    private List<String> recipients = new ArrayList<>();

    @JsonProperty("ScanEnabled")
    private boolean scanEnabled;

    @JsonProperty("Actions")
    private List<ReceiptAction> actions = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTlsPolicy() {
        return tlsPolicy;
    }

    public void setTlsPolicy(String tlsPolicy) {
        this.tlsPolicy = tlsPolicy;
    }

    public List<String> getRecipients() {
        if (recipients == null) {
            recipients = new ArrayList<>();
        }
        return recipients;
    }

    public void setRecipients(List<String> recipients) {
        this.recipients = recipients;
    }

    public boolean isScanEnabled() {
        return scanEnabled;
    }

    public void setScanEnabled(boolean scanEnabled) {
        this.scanEnabled = scanEnabled;
    }

    public List<ReceiptAction> getActions() {
        if (actions == null) {
            actions = new ArrayList<>();
        }
        return actions;
    }

    public void setActions(List<ReceiptAction> actions) {
        this.actions = actions;
    }
}
