package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphqlApi {
    private String apiId;
    private String name;
    private String arn;
    private AuthenticationType authenticationType;
    private Map<String, String> uris = new HashMap<>();
    private Map<String, Object> logConfig;
    private List<AdditionalAuthenticationProvider> additionalAuthenticationProviders;
    private Boolean xrayEnabled;
    private Map<String, String> tags = new HashMap<>();
    private Map<String, String> environmentVariables = new HashMap<>();
    private String apiType = "GRAPHQL";
    private String owner;
    private String ownerContact;
    private String visibility = "GLOBAL";
    private String introspectionConfig;
    private Integer queryDepthLimit;
    private Integer resolverCountLimit;
    private Map<String, Object> enhancedMetricsConfig;
    private Map<String, Object> lambdaAuthorizerConfig;
    private Map<String, Object> openIDConnectConfig;
    private Map<String, Object> userPoolConfig;
    private Map<String, String> dns;
    private String wafWebAclArn;
    private String mergedApiExecutionRoleArn;

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public AuthenticationType getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(AuthenticationType authenticationType) { this.authenticationType = authenticationType; }

    public Map<String, String> getUris() { return uris; }
    public void setUris(Map<String, String> uris) { this.uris = uris; }

    public Map<String, Object> getLogConfig() { return logConfig; }
    public void setLogConfig(Map<String, Object> logConfig) { this.logConfig = logConfig; }

    public List<AdditionalAuthenticationProvider> getAdditionalAuthenticationProviders() { return additionalAuthenticationProviders; }
    public void setAdditionalAuthenticationProviders(List<AdditionalAuthenticationProvider> additionalAuthenticationProviders) { this.additionalAuthenticationProviders = additionalAuthenticationProviders; }

    public Boolean getXrayEnabled() { return xrayEnabled; }
    public void setXrayEnabled(Boolean xrayEnabled) { this.xrayEnabled = xrayEnabled; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
    public void setEnvironmentVariables(Map<String, String> environmentVariables) { this.environmentVariables = environmentVariables; }

    public String getApiType() { return apiType; }
    public void setApiType(String apiType) { this.apiType = apiType; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getOwnerContact() { return ownerContact; }
    public void setOwnerContact(String ownerContact) { this.ownerContact = ownerContact; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public String getIntrospectionConfig() { return introspectionConfig; }
    public void setIntrospectionConfig(String introspectionConfig) { this.introspectionConfig = introspectionConfig; }

    public Integer getQueryDepthLimit() { return queryDepthLimit; }
    public void setQueryDepthLimit(Integer queryDepthLimit) { this.queryDepthLimit = queryDepthLimit; }

    public Integer getResolverCountLimit() { return resolverCountLimit; }
    public void setResolverCountLimit(Integer resolverCountLimit) { this.resolverCountLimit = resolverCountLimit; }

    public Map<String, Object> getEnhancedMetricsConfig() { return enhancedMetricsConfig; }
    public void setEnhancedMetricsConfig(Map<String, Object> enhancedMetricsConfig) { this.enhancedMetricsConfig = enhancedMetricsConfig; }

    public Map<String, Object> getLambdaAuthorizerConfig() { return lambdaAuthorizerConfig; }
    public void setLambdaAuthorizerConfig(Map<String, Object> lambdaAuthorizerConfig) { this.lambdaAuthorizerConfig = lambdaAuthorizerConfig; }

    public Map<String, Object> getOpenIDConnectConfig() { return openIDConnectConfig; }
    public void setOpenIDConnectConfig(Map<String, Object> openIDConnectConfig) { this.openIDConnectConfig = openIDConnectConfig; }

    public Map<String, Object> getUserPoolConfig() { return userPoolConfig; }
    public void setUserPoolConfig(Map<String, Object> userPoolConfig) { this.userPoolConfig = userPoolConfig; }

    public Map<String, String> getDns() { return dns; }
    public void setDns(Map<String, String> dns) { this.dns = dns; }

    public String getWafWebAclArn() { return wafWebAclArn; }
    public void setWafWebAclArn(String wafWebAclArn) { this.wafWebAclArn = wafWebAclArn; }

    public String getMergedApiExecutionRoleArn() { return mergedApiExecutionRoleArn; }
    public void setMergedApiExecutionRoleArn(String mergedApiExecutionRoleArn) { this.mergedApiExecutionRoleArn = mergedApiExecutionRoleArn; }
}
