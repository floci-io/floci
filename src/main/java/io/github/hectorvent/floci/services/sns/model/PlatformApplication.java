package io.github.hectorvent.floci.services.sns.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformApplication {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("PlatformApplicationArn")
    private String arn;

    /** APNS, APNS_SANDBOX, GCM, FCM. */
    @JsonProperty("Platform")
    private String platform;

    @JsonProperty("Attributes")
    private Map<String, String> attributes = new HashMap<>();

    @JsonProperty("CreatedAt")
    private Instant createdAt;

    public PlatformApplication() {}

    public PlatformApplication(String name, String arn, String platform) {
        this.name = name;
        this.arn = arn;
        this.platform = platform;
        this.createdAt = Instant.now();
        this.attributes.put("Enabled", "true");
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
