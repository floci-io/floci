package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventDestination {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("MatchingEventTypes")
    private List<String> matchingEventTypes = new ArrayList<>();

    @JsonProperty("SnsDestination")
    private SnsDestination snsDestination;

    @JsonProperty("CloudWatchDestination")
    private CloudWatchDestination cloudWatchDestination;

    @JsonProperty("KinesisFirehoseDestination")
    private KinesisFirehoseDestination kinesisFirehoseDestination;

    @JsonProperty("EventBridgeDestination")
    private EventBridgeDestination eventBridgeDestination;

    @JsonProperty("PinpointDestination")
    private PinpointDestination pinpointDestination;

    public EventDestination() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getMatchingEventTypes() { return matchingEventTypes; }
    public void setMatchingEventTypes(List<String> matchingEventTypes) {
        this.matchingEventTypes = matchingEventTypes != null ? matchingEventTypes : new ArrayList<>();
    }

    public SnsDestination getSnsDestination() { return snsDestination; }
    public void setSnsDestination(SnsDestination snsDestination) { this.snsDestination = snsDestination; }

    public CloudWatchDestination getCloudWatchDestination() { return cloudWatchDestination; }
    public void setCloudWatchDestination(CloudWatchDestination cloudWatchDestination) { this.cloudWatchDestination = cloudWatchDestination; }

    public KinesisFirehoseDestination getKinesisFirehoseDestination() { return kinesisFirehoseDestination; }
    public void setKinesisFirehoseDestination(KinesisFirehoseDestination kinesisFirehoseDestination) { this.kinesisFirehoseDestination = kinesisFirehoseDestination; }

    public EventBridgeDestination getEventBridgeDestination() { return eventBridgeDestination; }
    public void setEventBridgeDestination(EventBridgeDestination eventBridgeDestination) { this.eventBridgeDestination = eventBridgeDestination; }

    public PinpointDestination getPinpointDestination() { return pinpointDestination; }
    public void setPinpointDestination(PinpointDestination pinpointDestination) { this.pinpointDestination = pinpointDestination; }
}
