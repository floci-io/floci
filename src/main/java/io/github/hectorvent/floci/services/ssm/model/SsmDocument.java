package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SsmDocument {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Content")
    private String content;

    @JsonProperty("DocumentType")
    private String documentType;

    @JsonProperty("DocumentVersion")
    private long documentVersion;

    @JsonProperty("Status")
    private String status = "Active";

    @JsonProperty("CreatedDate")
    private Instant createdDate;

    @JsonProperty("Owner")
    private String owner;

    @JsonProperty("SchemaVersion")
    private String schemaVersion = "1.0";

    @JsonProperty("DocumentFormat")
    private String documentFormat = "JSON";

    @JsonProperty("PlatformTypes")
    private List<String> platformTypes = List.of("Windows", "Linux", "MacOS");

    @JsonProperty("Versions")
    private Map<String, String> versions = new LinkedHashMap<>();

    public SsmDocument() {}

    public SsmDocument(String name, String content, String documentType) {
        this.name = name;
        this.content = content;
        this.documentType = documentType;
        this.documentVersion = 1;
        this.createdDate = Instant.now();
        if (content != null) {
            this.versions.put("1", content);
        }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public long getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(long documentVersion) { this.documentVersion = documentVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedDate() { return createdDate; }
    public void setCreatedDate(Instant createdDate) { this.createdDate = createdDate; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getDocumentFormat() { return documentFormat; }
    public void setDocumentFormat(String documentFormat) { this.documentFormat = documentFormat; }

    public List<String> getPlatformTypes() { return platformTypes; }
    public void setPlatformTypes(List<String> platformTypes) { this.platformTypes = platformTypes; }

    public Map<String, String> getVersions() {
        if (versions == null) {
            versions = new LinkedHashMap<>();
        }
        if (versions.isEmpty() && content != null) {
            versions.put(String.valueOf(documentVersion > 0 ? documentVersion : 1), content);
        }
        return versions;
    }

    public void setVersions(Map<String, String> versions) {
        this.versions = versions != null ? new LinkedHashMap<>(versions) : new LinkedHashMap<>();
    }

    public String getContentForVersion(String version) {
        return getVersions().get(version);
    }
}
