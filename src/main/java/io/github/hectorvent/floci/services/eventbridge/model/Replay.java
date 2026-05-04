package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class Replay {

    private String replayName;
    private String replayArn;
    private String description;
    private String eventSourceArn;
    private String destinationArn;
    private Instant eventStartTime;
    private Instant eventEndTime;
    private Instant eventLastReplayedTime;
    private ReplayState state;
    private String stateReason;
    private Instant replayStartTime;
    private Instant replayEndTime;

    public Replay() {}

    public String getReplayName() { return replayName; }
    public void setReplayName(String replayName) { this.replayName = replayName; }

    public String getReplayArn() { return replayArn; }
    public void setReplayArn(String replayArn) { this.replayArn = replayArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEventSourceArn() { return eventSourceArn; }
    public void setEventSourceArn(String eventSourceArn) { this.eventSourceArn = eventSourceArn; }

    public String getDestinationArn() { return destinationArn; }
    public void setDestinationArn(String destinationArn) { this.destinationArn = destinationArn; }

    public Instant getEventStartTime() { return eventStartTime; }
    public void setEventStartTime(Instant eventStartTime) { this.eventStartTime = eventStartTime; }

    public Instant getEventEndTime() { return eventEndTime; }
    public void setEventEndTime(Instant eventEndTime) { this.eventEndTime = eventEndTime; }

    public Instant getEventLastReplayedTime() { return eventLastReplayedTime; }
    public void setEventLastReplayedTime(Instant eventLastReplayedTime) { this.eventLastReplayedTime = eventLastReplayedTime; }

    public ReplayState getState() { return state; }
    public void setState(ReplayState state) { this.state = state; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public Instant getReplayStartTime() { return replayStartTime; }
    public void setReplayStartTime(Instant replayStartTime) { this.replayStartTime = replayStartTime; }

    public Instant getReplayEndTime() { return replayEndTime; }
    public void setReplayEndTime(Instant replayEndTime) { this.replayEndTime = replayEndTime; }
}
