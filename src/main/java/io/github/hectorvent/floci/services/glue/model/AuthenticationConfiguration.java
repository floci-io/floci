package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * How a connection authenticates. {@code BasicAuthenticationCredentials} and
 * {@code CustomAuthenticationCredentials} are input-only on AWS: a Get never echoes a
 * credential, so {@link #toOutput()} drops them along with the OAuth2 secrets.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthenticationConfiguration {
    @JsonProperty("AuthenticationType")
    private String authenticationType;

    @JsonProperty("SecretArn")
    private String secretArn;

    @JsonProperty("KmsKeyArn")
    private String kmsKeyArn;

    @JsonProperty("OAuth2Properties")
    private OAuth2Properties oAuth2Properties;

    @JsonProperty("BasicAuthenticationCredentials")
    private Map<String, String> basicAuthenticationCredentials;

    @JsonProperty("CustomAuthenticationCredentials")
    private Map<String, String> customAuthenticationCredentials;

    public AuthenticationConfiguration toOutput() {
        AuthenticationConfiguration output = new AuthenticationConfiguration();
        output.authenticationType = authenticationType;
        output.secretArn = secretArn;
        output.kmsKeyArn = kmsKeyArn;
        output.oAuth2Properties = oAuth2Properties == null ? null : oAuth2Properties.toOutput();
        return output;
    }

    public String getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(String authenticationType) { this.authenticationType = authenticationType; }

    public String getSecretArn() { return secretArn; }
    public void setSecretArn(String secretArn) { this.secretArn = secretArn; }

    public String getKmsKeyArn() { return kmsKeyArn; }
    public void setKmsKeyArn(String kmsKeyArn) { this.kmsKeyArn = kmsKeyArn; }

    public OAuth2Properties getOAuth2Properties() { return oAuth2Properties; }
    public void setOAuth2Properties(OAuth2Properties oAuth2Properties) { this.oAuth2Properties = oAuth2Properties; }

    public Map<String, String> getBasicAuthenticationCredentials() { return basicAuthenticationCredentials; }
    public void setBasicAuthenticationCredentials(Map<String, String> basicAuthenticationCredentials) {
        this.basicAuthenticationCredentials = basicAuthenticationCredentials;
    }

    public Map<String, String> getCustomAuthenticationCredentials() { return customAuthenticationCredentials; }
    public void setCustomAuthenticationCredentials(Map<String, String> customAuthenticationCredentials) {
        this.customAuthenticationCredentials = customAuthenticationCredentials;
    }
}
