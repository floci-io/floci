package io.github.hectorvent.floci.services.ivs.model;

import java.util.Map;

public class IvsChannel {

    private String arn;
    private String name;
    private String latencyMode;
    private String type;
    private String preset;
    private boolean authorized;
    private boolean insecureIngest;
    private String recordingConfigurationArn;
    private String ingestEndpoint;
    private String playbackUrl;
    private Map<String, String> tags;
    private String streamKeyArn;
    private String streamKeyValue;
    private String accountId;

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLatencyMode() {
        return latencyMode;
    }

    public void setLatencyMode(String latencyMode) {
        this.latencyMode = latencyMode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean authorized) {
        this.authorized = authorized;
    }

    public boolean isInsecureIngest() {
        return insecureIngest;
    }

    public void setInsecureIngest(boolean insecureIngest) {
        this.insecureIngest = insecureIngest;
    }

    public String getRecordingConfigurationArn() {
        return recordingConfigurationArn;
    }

    public void setRecordingConfigurationArn(String recordingConfigurationArn) {
        this.recordingConfigurationArn = recordingConfigurationArn;
    }

    public String getIngestEndpoint() {
        return ingestEndpoint;
    }

    public void setIngestEndpoint(String ingestEndpoint) {
        this.ingestEndpoint = ingestEndpoint;
    }

    public String getPlaybackUrl() {
        return playbackUrl;
    }

    public void setPlaybackUrl(String playbackUrl) {
        this.playbackUrl = playbackUrl;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getStreamKeyArn() {
        return streamKeyArn;
    }

    public void setStreamKeyArn(String streamKeyArn) {
        this.streamKeyArn = streamKeyArn;
    }

    public String getStreamKeyValue() {
        return streamKeyValue;
    }

    public void setStreamKeyValue(String streamKeyValue) {
        this.streamKeyValue = streamKeyValue;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
