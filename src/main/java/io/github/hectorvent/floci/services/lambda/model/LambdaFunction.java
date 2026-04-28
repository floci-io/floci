package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LambdaFunction {

    private String functionName;
    private String functionArn;
    private String runtime;
    private String role;
    private String handler;
    private String description;
    private int timeout = 3;
    private int memorySize = 128;
    private String state = "Active";
    private String stateReason;
    private String stateReasonCode;
    private long codeSizeBytes;
    private String packageType = "Zip";
    private String imageUri;
    private List<String> imageConfigCommand;
    private List<String> imageConfigEntryPoint;
    private String codeLocalPath;
    private String s3Bucket;
    private String s3Key;
    private Map<String, String> environment = new HashMap<>();
    private Map<String, String> tags = new HashMap<>();
    private List<Map<String, Object>> policies = new ArrayList<>();
    private long lastModified;
    private String revisionId;
    private String version = "$LATEST";
    private LambdaUrlConfig urlConfig;
    private Integer reservedConcurrentExecutions;
    private List<String> architectures;
    private int ephemeralStorageSize = 512;
    private String tracingMode = "PassThrough";
    private String deadLetterTargetArn;
    private List<String> layers = new ArrayList<>();
    private String kmsKeyArn;
    private Map<String, Object> vpcConfig;
    private String codeSha256;

    /** Non-null only for hot-reload functions. Holds the Docker-host path bind-mounted into /var/task. */
    private String hotReloadHostPath;

    @JsonIgnore
    private volatile ContainerState containerState = ContainerState.COLD;

    public LambdaFunction() {
    }

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }

    public String getFunctionArn() { return functionArn; }
    public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    public int getMemorySize() { return memorySize; }
    public void setMemorySize(int memorySize) { this.memorySize = memorySize; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public String getStateReasonCode() { return stateReasonCode; }
    public void setStateReasonCode(String stateReasonCode) { this.stateReasonCode = stateReasonCode; }

    public long getCodeSizeBytes() { return codeSizeBytes; }
    public void setCodeSizeBytes(long codeSizeBytes) { this.codeSizeBytes = codeSizeBytes; }

    public String getPackageType() { return packageType; }
    public void setPackageType(String packageType) { this.packageType = packageType; }

    public String getImageUri() { return imageUri; }
    public void setImageUri(String imageUri) { this.imageUri = imageUri; }

    public List<String> getImageConfigCommand() { return imageConfigCommand; }
    public void setImageConfigCommand(List<String> imageConfigCommand) { this.imageConfigCommand = imageConfigCommand; }

    public List<String> getImageConfigEntryPoint() { return imageConfigEntryPoint; }
    public void setImageConfigEntryPoint(List<String> imageConfigEntryPoint) { this.imageConfigEntryPoint = imageConfigEntryPoint; }

    public String getCodeLocalPath() { return codeLocalPath; }
    public void setCodeLocalPath(String codeLocalPath) { this.codeLocalPath = codeLocalPath; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getS3Key() { return s3Key; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }

    public Map<String, String> getEnvironment() { return environment; }
    public void setEnvironment(Map<String, String> environment) { this.environment = environment; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<Map<String, Object>> getPolicies() { return policies; }
    public void setPolicies(List<Map<String, Object>> policies) { this.policies = policies; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public LambdaUrlConfig getUrlConfig() { return urlConfig; }
    public void setUrlConfig(LambdaUrlConfig urlConfig) { this.urlConfig = urlConfig; }

    public Integer getReservedConcurrentExecutions() { return reservedConcurrentExecutions; }
    public void setReservedConcurrentExecutions(Integer reservedConcurrentExecutions) { this.reservedConcurrentExecutions = reservedConcurrentExecutions; }

    public List<String> getArchitectures() { return architectures; }
    public void setArchitectures(List<String> architectures) { this.architectures = architectures; }

    public int getEphemeralStorageSize() { return ephemeralStorageSize; }
    public void setEphemeralStorageSize(int ephemeralStorageSize) { this.ephemeralStorageSize = ephemeralStorageSize; }

    public String getTracingMode() { return tracingMode; }
    public void setTracingMode(String tracingMode) { this.tracingMode = tracingMode; }

    public String getDeadLetterTargetArn() { return deadLetterTargetArn; }
    public void setDeadLetterTargetArn(String deadLetterTargetArn) { this.deadLetterTargetArn = deadLetterTargetArn; }

    public List<String> getLayers() { return layers; }
    public void setLayers(List<String> layers) { this.layers = layers; }

    public String getKmsKeyArn() { return kmsKeyArn; }
    public void setKmsKeyArn(String kmsKeyArn) { this.kmsKeyArn = kmsKeyArn; }

    public Map<String, Object> getVpcConfig() { return vpcConfig; }
    public void setVpcConfig(Map<String, Object> vpcConfig) { this.vpcConfig = vpcConfig; }

    public String getCodeSha256() { return codeSha256; }
    public void setCodeSha256(String codeSha256) { this.codeSha256 = codeSha256; }

    public String getHotReloadHostPath() { return hotReloadHostPath; }
    public void setHotReloadHostPath(String hotReloadHostPath) { this.hotReloadHostPath = hotReloadHostPath; }

    @JsonIgnore
    public boolean isHotReload() { return hotReloadHostPath != null; }

    @JsonIgnore
    public ContainerState getContainerState() { return containerState; }
    @JsonIgnore
    public void setContainerState(ContainerState containerState) { this.containerState = containerState; }
}
