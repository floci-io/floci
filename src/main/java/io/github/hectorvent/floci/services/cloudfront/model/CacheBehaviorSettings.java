package io.github.hectorvent.floci.services.cloudfront.model;

import java.util.List;
import java.util.Map;

/**
 * The fields {@link CacheBehavior} and {@link DefaultCacheBehavior} share, letting XML-parsing
 * code populate either one through a single code path.
 */
public interface CacheBehaviorSettings {

    void setTargetOriginId(String targetOriginId);

    void setViewerProtocolPolicy(String viewerProtocolPolicy);

    void setAllowedMethods(List<String> allowedMethods);

    void setCachedMethods(List<String> cachedMethods);

    void setCachePolicyId(String cachePolicyId);

    void setOriginRequestPolicyId(String originRequestPolicyId);

    void setResponseHeadersPolicyId(String responseHeadersPolicyId);

    void setFieldLevelEncryptionId(String fieldLevelEncryptionId);

    void setRealtimeLogConfigArn(String realtimeLogConfigArn);

    void setFunctionAssociations(List<Map<String, String>> functionAssociations);

    void setLambdaFunctionAssociations(List<Map<String, Object>> lambdaFunctionAssociations);

    void setCompress(boolean compress);

    void setSmoothStreaming(boolean smoothStreaming);

    void setMinTTL(Long minTTL);

    void setDefaultTTL(Long defaultTTL);

    void setMaxTTL(Long maxTTL);

    void setForwardedValues(Map<String, Object> forwardedValues);

    void setTrustedKeyGroupsEnabled(boolean trustedKeyGroupsEnabled);

    void setTrustedKeyGroups(List<String> trustedKeyGroups);

    default void setPathPattern(String pathPattern) {
        // DefaultCacheBehavior has no PathPattern; only CacheBehavior overrides this.
    }
}
