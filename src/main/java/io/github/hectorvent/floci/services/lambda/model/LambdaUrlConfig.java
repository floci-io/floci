package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LambdaUrlConfig {

    @JsonProperty("FunctionUrl")
    private String functionUrl;

    @JsonProperty("FunctionArn")
    private String functionArn;

    @JsonProperty("AuthType")
    private String authType; // NONE or AWS_IAM

    @JsonProperty("InvokeMode")
    private String invokeMode = "BUFFERED";

    @JsonProperty("CreationTime")
    private String creationTime;

    @JsonProperty("LastModifiedTime")
    private String lastModifiedTime;

    @JsonProperty("Cors")
    private Cors cors;

    public LambdaUrlConfig() {}

    public String getFunctionUrl() { return functionUrl; }
    public void setFunctionUrl(String functionUrl) { this.functionUrl = functionUrl; }

    public String getFunctionArn() { return functionArn; }
    public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getInvokeMode() { return invokeMode; }
    public void setInvokeMode(String invokeMode) { this.invokeMode = invokeMode; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(String lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }

    @RegisterForReflection
    public static class Cors {
        @JsonProperty("AllowCredentials")
        private boolean allowCredentials;

        @JsonProperty("AllowHeaders")
        private String[] allowHeaders;

        @JsonProperty("AllowMethods")
        private String[] allowMethods;

        @JsonProperty("AllowOrigins")
        private String[] allowOrigins;

        @JsonProperty("ExposeHeaders")
        private String[] exposeHeaders;

        @JsonProperty("MaxAge")
        private Integer maxAge;

        public boolean isAllowCredentials() { return allowCredentials; }
        public void setAllowCredentials(boolean allowCredentials) { this.allowCredentials = allowCredentials; }

        public String[] getAllowHeaders() { return allowHeaders; }
        public void setAllowHeaders(String[] allowHeaders) { this.allowHeaders = allowHeaders; }

        public String[] getAllowMethods() { return allowMethods; }
        public void setAllowMethods(String[] allowMethods) { this.allowMethods = allowMethods; }

        public String[] getAllowOrigins() { return allowOrigins; }
        public void setAllowOrigins(String[] allowOrigins) { this.allowOrigins = allowOrigins; }

        public String[] getExposeHeaders() { return exposeHeaders; }
        public void setExposeHeaders(String[] exposeHeaders) { this.exposeHeaders = exposeHeaders; }

        public Integer getMaxAge() { return maxAge; }
        public void setMaxAge(Integer maxAge) { this.maxAge = maxAge; }
    }
}
