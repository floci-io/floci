package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
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

    @JsonProperty("Tags")
    @JsonAlias({"tags", "Tags"})
    private Map<String, String> tags = new HashMap<>();

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

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
