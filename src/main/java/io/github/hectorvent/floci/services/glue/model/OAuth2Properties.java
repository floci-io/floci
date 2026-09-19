package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * OAuth2 settings of a connection. The two credential-bearing members,
 * {@code AuthorizationCodeProperties} and {@code OAuth2Credentials}, exist only on the input
 * shape: AWS accepts them on Create and Update and never returns them, so {@link Connection}
 * is built through {@link #toOutput()}, which leaves them behind.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuth2Properties {
    @JsonProperty("OAuth2GrantType")
    private String oAuth2GrantType;

    @JsonProperty("OAuth2ClientApplication")
    private OAuth2ClientApplication oAuth2ClientApplication;

    @JsonProperty("TokenUrl")
    private String tokenUrl;

    @JsonProperty("TokenUrlParametersMap")
    private Map<String, String> tokenUrlParametersMap;

    @JsonProperty("AuthorizationCodeProperties")
    private JsonNode authorizationCodeProperties;

    @JsonProperty("OAuth2Credentials")
    private JsonNode oAuth2Credentials;

    public OAuth2Properties toOutput() {
        OAuth2Properties output = new OAuth2Properties();
        output.oAuth2GrantType = oAuth2GrantType;
        output.oAuth2ClientApplication = oAuth2ClientApplication;
        output.tokenUrl = tokenUrl;
        output.tokenUrlParametersMap = tokenUrlParametersMap;
        return output;
    }

    public String getOAuth2GrantType() { return oAuth2GrantType; }
    public void setOAuth2GrantType(String oAuth2GrantType) { this.oAuth2GrantType = oAuth2GrantType; }

    public OAuth2ClientApplication getOAuth2ClientApplication() { return oAuth2ClientApplication; }
    public void setOAuth2ClientApplication(OAuth2ClientApplication oAuth2ClientApplication) {
        this.oAuth2ClientApplication = oAuth2ClientApplication;
    }

    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }

    public Map<String, String> getTokenUrlParametersMap() { return tokenUrlParametersMap; }
    public void setTokenUrlParametersMap(Map<String, String> tokenUrlParametersMap) {
        this.tokenUrlParametersMap = tokenUrlParametersMap;
    }

    public JsonNode getAuthorizationCodeProperties() { return authorizationCodeProperties; }
    public void setAuthorizationCodeProperties(JsonNode authorizationCodeProperties) {
        this.authorizationCodeProperties = authorizationCodeProperties;
    }

    public JsonNode getOAuth2Credentials() { return oAuth2Credentials; }
    public void setOAuth2Credentials(JsonNode oAuth2Credentials) { this.oAuth2Credentials = oAuth2Credentials; }
}
