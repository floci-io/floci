package io.floci.aws.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** One entry of an Events API's {@code eventConfig.authProviders}: the model's {@code AuthProvider} shape. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventAuthProvider {
    private String authType;
    private Map<String, Object> cognitoConfig;
    private Map<String, Object> openIDConnectConfig;
    private Map<String, Object> lambdaAuthorizerConfig;

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public Map<String, Object> getCognitoConfig() {
        return cognitoConfig;
    }

    public void setCognitoConfig(Map<String, Object> cognitoConfig) {
        this.cognitoConfig = cognitoConfig;
    }

    public Map<String, Object> getOpenIDConnectConfig() {
        return openIDConnectConfig;
    }

    public void setOpenIDConnectConfig(Map<String, Object> openIDConnectConfig) {
        this.openIDConnectConfig = openIDConnectConfig;
    }

    public Map<String, Object> getLambdaAuthorizerConfig() {
        return lambdaAuthorizerConfig;
    }

    public void setLambdaAuthorizerConfig(Map<String, Object> lambdaAuthorizerConfig) {
        this.lambdaAuthorizerConfig = lambdaAuthorizerConfig;
    }
}
