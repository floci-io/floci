package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
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
    private String codeLocalPath;
    private boolean hotReload;
    private Map<String, String> environment = new HashMap<>();
    private Map<String, String> tags = new HashMap<>();
    private long lastModified;
    private String revisionId;
    private LambdaUrlConfig urlConfig;

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

    public String getCodeLocalPath() { return codeLocalPath; }
    public void setCodeLocalPath(String codeLocalPath) { this.codeLocalPath = codeLocalPath; }

    public boolean isHotReload() { return hotReload; }
    public void setHotReload(boolean hotReload) { this.hotReload = hotReload; }

    public Map<String, String> getEnvironment() { return environment; }
    public void setEnvironment(Map<String, String> environment) { this.environment = environment; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public LambdaUrlConfig getUrlConfig() { return urlConfig; }
    public void setUrlConfig(LambdaUrlConfig urlConfig) { this.urlConfig = urlConfig; }

    @JsonIgnore
    public ContainerState getContainerState() { return containerState; }
    @JsonIgnore
    public void setContainerState(ContainerState containerState) { this.containerState = containerState; }
}
