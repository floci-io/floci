package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DistributionConfig {

    private String callerReference;
    private boolean enabled;
    private String comment;
    private String defaultRootObject;
    private List<Origin> origins;
    private DefaultCacheBehavior defaultCacheBehavior;
    private List<CacheBehavior> cacheBehaviors;
    private List<String> aliases;
    private Map<String, String> viewerCertificate;
    private Map<String, Object> geoRestriction;
    private String httpVersion = "http2";
    private String priceClass = "PriceClass_All";
    private boolean isIPV6Enabled = true;
    private String webAclId;
    private List<Map<String, Object>> customErrorResponses;
    private Map<String, Object> logging;
    private String continuousDeploymentPolicyId;
    private boolean staging;
    private String connectionMode;
    private TenantConfig tenantConfig;

    public DistributionConfig() {}

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getDefaultRootObject() { return defaultRootObject; }
    public void setDefaultRootObject(String defaultRootObject) { this.defaultRootObject = defaultRootObject; }

    public List<Origin> getOrigins() { return origins; }
    public void setOrigins(List<Origin> origins) { this.origins = origins; }

    public DefaultCacheBehavior getDefaultCacheBehavior() { return defaultCacheBehavior; }
    public void setDefaultCacheBehavior(DefaultCacheBehavior defaultCacheBehavior) { this.defaultCacheBehavior = defaultCacheBehavior; }

    public List<CacheBehavior> getCacheBehaviors() { return cacheBehaviors; }
    public void setCacheBehaviors(List<CacheBehavior> cacheBehaviors) { this.cacheBehaviors = cacheBehaviors; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) { this.aliases = aliases; }

    public Map<String, String> getViewerCertificate() { return viewerCertificate; }
    public void setViewerCertificate(Map<String, String> viewerCertificate) { this.viewerCertificate = viewerCertificate; }

    public Map<String, Object> getGeoRestriction() { return geoRestriction; }
    public void setGeoRestriction(Map<String, Object> geoRestriction) { this.geoRestriction = geoRestriction; }

    public String getHttpVersion() { return httpVersion; }
    public void setHttpVersion(String httpVersion) { this.httpVersion = httpVersion; }

    public String getPriceClass() { return priceClass; }
    public void setPriceClass(String priceClass) { this.priceClass = priceClass; }

    public boolean isIPV6Enabled() { return isIPV6Enabled; }
    public void setIPV6Enabled(boolean IPV6Enabled) { this.isIPV6Enabled = IPV6Enabled; }

    public String getWebAclId() { return webAclId; }
    public void setWebAclId(String webAclId) { this.webAclId = webAclId; }

    public List<Map<String, Object>> getCustomErrorResponses() { return customErrorResponses; }
    public void setCustomErrorResponses(List<Map<String, Object>> customErrorResponses) { this.customErrorResponses = customErrorResponses; }

    public Map<String, Object> getLogging() { return logging; }
    public void setLogging(Map<String, Object> logging) { this.logging = logging; }

    public String getContinuousDeploymentPolicyId() { return continuousDeploymentPolicyId; }
    public void setContinuousDeploymentPolicyId(String continuousDeploymentPolicyId) { this.continuousDeploymentPolicyId = continuousDeploymentPolicyId; }

    public boolean isStaging() { return staging; }
    public void setStaging(boolean staging) { this.staging = staging; }

    public String getConnectionMode() { return connectionMode; }
    public void setConnectionMode(String connectionMode) { this.connectionMode = connectionMode; }

    public TenantConfig getTenantConfig() { return tenantConfig; }
    public void setTenantConfig(TenantConfig tenantConfig) { this.tenantConfig = tenantConfig; }

    @RegisterForReflection
    public static class TenantConfig {
        private List<ParameterDefinition> parameterDefinitions;

        public TenantConfig() {}

        public List<ParameterDefinition> getParameterDefinitions() { return parameterDefinitions; }
        public void setParameterDefinitions(List<ParameterDefinition> parameterDefinitions) {
            this.parameterDefinitions = parameterDefinitions;
        }
    }

    @RegisterForReflection
    public static class ParameterDefinition {
        private String name;
        private ParameterDefinitionDetails definition;

        public ParameterDefinition() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public ParameterDefinitionDetails getDefinition() { return definition; }
        public void setDefinition(ParameterDefinitionDetails definition) { this.definition = definition; }
    }

    @RegisterForReflection
    public static class ParameterDefinitionDetails {
        private StringSchema stringSchema;

        public ParameterDefinitionDetails() {}

        public StringSchema getStringSchema() { return stringSchema; }
        public void setStringSchema(StringSchema stringSchema) { this.stringSchema = stringSchema; }
    }

    @RegisterForReflection
    public static class StringSchema {
        private Boolean required;
        private String defaultValue;
        private String comment;

        public StringSchema() {}

        public Boolean getRequired() { return required; }
        public void setRequired(Boolean required) { this.required = required; }

        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }
}
