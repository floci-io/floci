package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Parameter {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Value")
    private String value;

    @JsonProperty("Type")
    private String type;

    @JsonProperty("Version")
    private long version;

    @JsonProperty("LastModifiedDate")
    private Instant lastModifiedDate;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("ARN")
    private String arn;

    @JsonProperty("DataType")
    private String dataType = "text";

    @JsonProperty("KeyId")
    private String keyId;

    @JsonProperty("AllowedPattern")
    private String allowedPattern;

    @JsonProperty("Tier")
    private String tier;

    // Each policy object as submitted; DescribeParameters re-serializes it as the PolicyText.
    @JsonProperty("Policies")
    private List<JsonNode> policies;

    @JsonProperty("Tags")
    @JsonAlias({"tags", "Tags"})
    private Map<String, String> tags = new HashMap<>();

    // Only set on a copy answering a name:version or name:label read; never persisted.
    @JsonIgnore
    private String selector;

    // Only set on a Secrets Manager reference: AWS's GetSecretValue result as a JSON string.
    @JsonIgnore
    private String sourceResult;

    public Parameter() {}

    public Parameter(String name, String value, String type) {
        this.name = name;
        this.value = value;
        this.type = type;
        this.version = 1;
        this.lastModifiedDate = Instant.now();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(Instant lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getAllowedPattern() { return allowedPattern; }
    public void setAllowedPattern(String allowedPattern) { this.allowedPattern = allowedPattern; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public List<JsonNode> getPolicies() { return policies; }
    public void setPolicies(List<JsonNode> policies) { this.policies = policies; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getSelector() { return selector; }
    public void setSelector(String selector) { this.selector = selector; }

    public String getSourceResult() { return sourceResult; }
    public void setSourceResult(String sourceResult) { this.sourceResult = sourceResult; }
}
