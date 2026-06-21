package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigurationSet {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    @JsonProperty("EventDestinations")
    private List<EventDestination> eventDestinations = new ArrayList<>();

    @JsonProperty("SuppressionOptions")
    private SuppressionOptions suppressionOptions;

    @JsonProperty("SendingEnabled")
    private Boolean sendingEnabled;

    @JsonProperty("ReputationMetricsEnabled")
    private Boolean reputationMetricsEnabled;

    @JsonProperty("TrackingOptions")
    private TrackingOptions trackingOptions;

    @JsonProperty("DeliveryOptions")
    private DeliveryOptions deliveryOptions;

    @JsonProperty("ArchivingOptions")
    private ArchivingOptions archivingOptions;

    @JsonProperty("VdmOptions")
    private VdmOptions vdmOptions;

    public ConfigurationSet() {}

    public ConfigurationSet(String name) {
        this.name = name;
        this.createdTimestamp = Instant.now();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

    public List<EventDestination> getEventDestinations() { return eventDestinations; }
    public void setEventDestinations(List<EventDestination> eventDestinations) {
        this.eventDestinations = eventDestinations != null ? eventDestinations : new ArrayList<>();
    }

    public SuppressionOptions getSuppressionOptions() { return suppressionOptions; }
    public void setSuppressionOptions(SuppressionOptions suppressionOptions) {
        this.suppressionOptions = suppressionOptions;
    }

    public Boolean getSendingEnabled() { return sendingEnabled; }
    public void setSendingEnabled(Boolean sendingEnabled) { this.sendingEnabled = sendingEnabled; }

    /**
     * Effective sending-enabled flag with the AWS default applied — an unset
     * (null) field is treated as enabled, matching the AWS behaviour where a
     * configuration set without an explicit {@code SendingEnabled} state is
     * implicitly enabled. Use this anywhere v1/v2 read or enforce the toggle
     * so the default rule stays consistent across surfaces.
     */
    @JsonIgnore
    public boolean isSendingEnabledEffective() {
        return sendingEnabled == null || sendingEnabled;
    }

    public Boolean getReputationMetricsEnabled() { return reputationMetricsEnabled; }
    public void setReputationMetricsEnabled(Boolean reputationMetricsEnabled) {
        this.reputationMetricsEnabled = reputationMetricsEnabled;
    }

    /**
     * Effective reputation-metrics flag with the AWS default applied — an unset
     * (null) field reads as enabled, matching AWS where GetConfigurationSet
     * always returns {@code ReputationOptions.ReputationMetricsEnabled} (true by
     * default) for a configuration set with no explicit override.
     */
    @JsonIgnore
    public boolean isReputationMetricsEnabledEffective() {
        return reputationMetricsEnabled == null || reputationMetricsEnabled;
    }

    public TrackingOptions getTrackingOptions() { return trackingOptions; }
    public void setTrackingOptions(TrackingOptions trackingOptions) {
        this.trackingOptions = trackingOptions;
    }

    public DeliveryOptions getDeliveryOptions() { return deliveryOptions; }
    public void setDeliveryOptions(DeliveryOptions deliveryOptions) {
        this.deliveryOptions = deliveryOptions;
    }

    public ArchivingOptions getArchivingOptions() { return archivingOptions; }
    public void setArchivingOptions(ArchivingOptions archivingOptions) {
        this.archivingOptions = archivingOptions;
    }

    public VdmOptions getVdmOptions() { return vdmOptions; }
    public void setVdmOptions(VdmOptions vdmOptions) {
        this.vdmOptions = vdmOptions;
    }
}
