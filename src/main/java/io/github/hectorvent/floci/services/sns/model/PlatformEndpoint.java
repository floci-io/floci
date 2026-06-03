package io.github.hectorvent.floci.services.sns.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformEndpoint {

    @JsonProperty("EndpointArn")
    private String arn;

    @JsonProperty("PlatformApplicationArn")
    private String platformApplicationArn;

    @JsonProperty("Token")
    private String token;

    @JsonProperty("Attributes")
    private Map<String, String> attributes = new HashMap<>();

    @JsonProperty("CreatedAt")
    private Instant createdAt;

    public PlatformEndpoint() {}

    public PlatformEndpoint(String arn, String platformApplicationArn, String token) {
        this.arn = arn;
        this.platformApplicationArn = platformApplicationArn;
        this.token = token;
        this.createdAt = Instant.now();
        this.attributes.put("Token", token);
        this.attributes.putIfAbsent("Enabled", "true");
        this.attributes.putIfAbsent("CustomUserData", "");
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getPlatformApplicationArn() { return platformApplicationArn; }
    public void setPlatformApplicationArn(String platformApplicationArn) { this.platformApplicationArn = platformApplicationArn; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
