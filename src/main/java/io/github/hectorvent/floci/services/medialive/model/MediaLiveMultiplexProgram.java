package io.github.hectorvent.floci.services.medialive.model;

import com.fasterxml.jackson.databind.JsonNode;

public class MediaLiveMultiplexProgram {

    private String multiplexId;
    private String programName;
    private JsonNode multiplexProgramSettings;
    private String channelId;

    public String getMultiplexId() {
        return multiplexId;
    }

    public void setMultiplexId(String multiplexId) {
        this.multiplexId = multiplexId;
    }

    public String getProgramName() {
        return programName;
    }

    public void setProgramName(String programName) {
        this.programName = programName;
    }

    public JsonNode getMultiplexProgramSettings() {
        return multiplexProgramSettings;
    }

    public void setMultiplexProgramSettings(JsonNode multiplexProgramSettings) {
        this.multiplexProgramSettings = multiplexProgramSettings;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }
}
