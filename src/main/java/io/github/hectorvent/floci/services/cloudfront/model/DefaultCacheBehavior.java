package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DefaultCacheBehavior implements CacheBehaviorSettings {

    private String targetOriginId;
    private String viewerProtocolPolicy = "redirect-to-https";
    private List<String> allowedMethods;
    private List<String> cachedMethods;
    private String cachePolicyId;
    private String originRequestPolicyId;
    private String responseHeadersPolicyId;
    private String fieldLevelEncryptionId;
    private String realtimeLogConfigArn;
    private List<Map<String, String>> functionAssociations;
    private List<Map<String, Object>> lambdaFunctionAssociations;
    private boolean compress;
    private boolean smoothStreaming;
    private Long defaultTTL;
    private Long minTTL;
    private Long maxTTL;
    private Map<String, Object> forwardedValues;
    private boolean trustedSignersEnabled;
    private List<String> trustedSigners;
    private boolean trustedKeyGroupsEnabled;
    private List<String> trustedKeyGroups;

    public DefaultCacheBehavior() {}

    public String getTargetOriginId() { return targetOriginId; }
    public void setTargetOriginId(String targetOriginId) { this.targetOriginId = targetOriginId; }

    public String getViewerProtocolPolicy() { return viewerProtocolPolicy; }
    public void setViewerProtocolPolicy(String viewerProtocolPolicy) { this.viewerProtocolPolicy = viewerProtocolPolicy; }

    public List<String> getAllowedMethods() { return allowedMethods; }
    public void setAllowedMethods(List<String> allowedMethods) { this.allowedMethods = allowedMethods; }

    public List<String> getCachedMethods() { return cachedMethods; }
    public void setCachedMethods(List<String> cachedMethods) { this.cachedMethods = cachedMethods; }

    public String getCachePolicyId() { return cachePolicyId; }
    public void setCachePolicyId(String cachePolicyId) { this.cachePolicyId = cachePolicyId; }

    public String getOriginRequestPolicyId() { return originRequestPolicyId; }
    public void setOriginRequestPolicyId(String originRequestPolicyId) { this.originRequestPolicyId = originRequestPolicyId; }

    public String getResponseHeadersPolicyId() { return responseHeadersPolicyId; }
    public void setResponseHeadersPolicyId(String responseHeadersPolicyId) { this.responseHeadersPolicyId = responseHeadersPolicyId; }

    public String getFieldLevelEncryptionId() { return fieldLevelEncryptionId; }
    public void setFieldLevelEncryptionId(String fieldLevelEncryptionId) { this.fieldLevelEncryptionId = fieldLevelEncryptionId; }

    public String getRealtimeLogConfigArn() { return realtimeLogConfigArn; }
    public void setRealtimeLogConfigArn(String realtimeLogConfigArn) { this.realtimeLogConfigArn = realtimeLogConfigArn; }

    public List<Map<String, String>> getFunctionAssociations() { return functionAssociations; }
    public void setFunctionAssociations(List<Map<String, String>> functionAssociations) { this.functionAssociations = functionAssociations; }

    public List<Map<String, Object>> getLambdaFunctionAssociations() { return lambdaFunctionAssociations; }
    public void setLambdaFunctionAssociations(List<Map<String, Object>> lambdaFunctionAssociations) { this.lambdaFunctionAssociations = lambdaFunctionAssociations; }

    public boolean isCompress() { return compress; }
    public void setCompress(boolean compress) { this.compress = compress; }

    public Long getDefaultTTL() { return defaultTTL; }
    public void setDefaultTTL(Long defaultTTL) { this.defaultTTL = defaultTTL; }

    public Long getMinTTL() { return minTTL; }
    public void setMinTTL(Long minTTL) { this.minTTL = minTTL; }

    public Long getMaxTTL() { return maxTTL; }
    public void setMaxTTL(Long maxTTL) { this.maxTTL = maxTTL; }

    public boolean isSmoothStreaming() { return smoothStreaming; }
    public void setSmoothStreaming(boolean smoothStreaming) { this.smoothStreaming = smoothStreaming; }

    public Map<String, Object> getForwardedValues() { return forwardedValues; }
    public void setForwardedValues(Map<String, Object> forwardedValues) { this.forwardedValues = forwardedValues; }

    public boolean isTrustedSignersEnabled() { return trustedSignersEnabled; }
    public void setTrustedSignersEnabled(boolean trustedSignersEnabled) { this.trustedSignersEnabled = trustedSignersEnabled; }

    public List<String> getTrustedSigners() { return trustedSigners; }
    public void setTrustedSigners(List<String> trustedSigners) { this.trustedSigners = trustedSigners; }

    public boolean isTrustedKeyGroupsEnabled() { return trustedKeyGroupsEnabled; }
    public void setTrustedKeyGroupsEnabled(boolean trustedKeyGroupsEnabled) { this.trustedKeyGroupsEnabled = trustedKeyGroupsEnabled; }

    public List<String> getTrustedKeyGroups() { return trustedKeyGroups; }
    public void setTrustedKeyGroups(List<String> trustedKeyGroups) { this.trustedKeyGroups = trustedKeyGroups; }
}
