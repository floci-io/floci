package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdditionalAuthenticationProvider {
    private AuthenticationType authenticationType;
    private Map<String, Object> lambdaAuthorizerConfig;
    private Map<String, Object> openIDConnectConfig;
    private Map<String, Object> userPoolConfig;

    public AuthenticationType getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(AuthenticationType authenticationType) { this.authenticationType = authenticationType; }

    public Map<String, Object> getLambdaAuthorizerConfig() { return lambdaAuthorizerConfig; }
    public void setLambdaAuthorizerConfig(Map<String, Object> lambdaAuthorizerConfig) { this.lambdaAuthorizerConfig = lambdaAuthorizerConfig; }

    public Map<String, Object> getOpenIDConnectConfig() { return openIDConnectConfig; }
    public void setOpenIDConnectConfig(Map<String, Object> openIDConnectConfig) { this.openIDConnectConfig = openIDConnectConfig; }

    public Map<String, Object> getUserPoolConfig() { return userPoolConfig; }
    public void setUserPoolConfig(Map<String, Object> userPoolConfig) { this.userPoolConfig = userPoolConfig; }
}
