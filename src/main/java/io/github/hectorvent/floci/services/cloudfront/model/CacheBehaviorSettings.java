package io.github.hectorvent.floci.services.cloudfront.model;

import java.util.List;
import java.util.Map;

/**
 * The members CloudFront's DefaultCacheBehavior and CacheBehavior structures have in common —
 * CacheBehavior adds only PathPattern. Sharing the accessors lets one parser and one
 * serializer cover both structures.
 */
public interface CacheBehaviorSettings {

    String getTargetOriginId();

    void setTargetOriginId(String targetOriginId);

    String getViewerProtocolPolicy();

    void setViewerProtocolPolicy(String viewerProtocolPolicy);

    List<String> getAllowedMethods();

    void setAllowedMethods(List<String> allowedMethods);

    List<String> getCachedMethods();

    void setCachedMethods(List<String> cachedMethods);

    String getCachePolicyId();

    void setCachePolicyId(String cachePolicyId);

    String getOriginRequestPolicyId();

    void setOriginRequestPolicyId(String originRequestPolicyId);

    String getResponseHeadersPolicyId();

    void setResponseHeadersPolicyId(String responseHeadersPolicyId);

    String getFieldLevelEncryptionId();

    void setFieldLevelEncryptionId(String fieldLevelEncryptionId);

    String getRealtimeLogConfigArn();

    void setRealtimeLogConfigArn(String realtimeLogConfigArn);

    List<Map<String, String>> getFunctionAssociations();

    void setFunctionAssociations(List<Map<String, String>> functionAssociations);

    List<Map<String, Object>> getLambdaFunctionAssociations();

    void setLambdaFunctionAssociations(List<Map<String, Object>> lambdaFunctionAssociations);

    boolean isCompress();

    void setCompress(boolean compress);

    boolean isSmoothStreaming();

    void setSmoothStreaming(boolean smoothStreaming);

    Long getDefaultTTL();

    void setDefaultTTL(Long defaultTTL);

    Long getMinTTL();

    void setMinTTL(Long minTTL);

    Long getMaxTTL();

    void setMaxTTL(Long maxTTL);

    /**
     * The legacy ForwardedValues structure, kept as submitted so it round-trips verbatim.
     * Keys: {@code QueryString}, {@code Forward}, {@code WhitelistedNames}, {@code Headers},
     * {@code QueryStringCacheKeys}. Null when the behavior uses a cache policy instead.
     */
    Map<String, Object> getForwardedValues();

    void setForwardedValues(Map<String, Object> forwardedValues);

    boolean isTrustedSignersEnabled();

    void setTrustedSignersEnabled(boolean trustedSignersEnabled);

    List<String> getTrustedSigners();

    void setTrustedSigners(List<String> trustedSigners);

    boolean isTrustedKeyGroupsEnabled();

    void setTrustedKeyGroupsEnabled(boolean trustedKeyGroupsEnabled);

    List<String> getTrustedKeyGroups();

    void setTrustedKeyGroups(List<String> trustedKeyGroups);
}
