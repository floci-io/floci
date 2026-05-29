package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Represents a single published version of a Lambda layer.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LambdaLayerVersion {

    private String layerName;
    private String layerArn;
    private String layerVersionArn;
    private long version;
    private String description;
    private String licenseInfo;
    private List<String> compatibleRuntimes;
    private List<String> compatibleArchitectures;
    private String createdDate;
    private long codeSizeBytes;
    private String codeSha256;
    private String codeLocalPath;

    public LambdaLayerVersion() {
    }

    public String getLayerName() { return layerName; }
    public void setLayerName(String layerName) { this.layerName = layerName; }

    public String getLayerArn() { return layerArn; }
    public void setLayerArn(String layerArn) { this.layerArn = layerArn; }

    public String getLayerVersionArn() { return layerVersionArn; }
    public void setLayerVersionArn(String layerVersionArn) { this.layerVersionArn = layerVersionArn; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLicenseInfo() { return licenseInfo; }
    public void setLicenseInfo(String licenseInfo) { this.licenseInfo = licenseInfo; }

    public List<String> getCompatibleRuntimes() { return compatibleRuntimes; }
    public void setCompatibleRuntimes(List<String> compatibleRuntimes) { this.compatibleRuntimes = compatibleRuntimes; }

    public List<String> getCompatibleArchitectures() { return compatibleArchitectures; }
    public void setCompatibleArchitectures(List<String> compatibleArchitectures) { this.compatibleArchitectures = compatibleArchitectures; }

    public String getCreatedDate() { return createdDate; }
    public void setCreatedDate(String createdDate) { this.createdDate = createdDate; }

    public long getCodeSizeBytes() { return codeSizeBytes; }
    public void setCodeSizeBytes(long codeSizeBytes) { this.codeSizeBytes = codeSizeBytes; }

    public String getCodeSha256() { return codeSha256; }
    public void setCodeSha256(String codeSha256) { this.codeSha256 = codeSha256; }

    public String getCodeLocalPath() { return codeLocalPath; }
    public void setCodeLocalPath(String codeLocalPath) { this.codeLocalPath = codeLocalPath; }
}
