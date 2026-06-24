package io.github.hectorvent.floci.services.iot.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * An AWS IoT custom authorizer (token-based).
 *
 * <p>Holds the configuration the broker uses at connect time: the Lambda to invoke,
 * the request key that carries the token, the public keys used to verify the token
 * signature, and whether signature verification is required.
 */
public class IotAuthorizer {

    private String authorizerName;
    private String authorizerArn;
    private String authorizerFunctionArn;
    private String tokenKeyName;
    private Map<String, String> tokenSigningPublicKeys = new HashMap<>();
    private String status = "ACTIVE";
    private boolean signingDisabled = false;
    private boolean enableCachingForHttp = false;
    private Instant creationDate;
    private Instant lastModifiedDate;

    public IotAuthorizer() {
    }

    public String getAuthorizerName() {
        return authorizerName;
    }

    public void setAuthorizerName(String authorizerName) {
        this.authorizerName = authorizerName;
    }

    public String getAuthorizerArn() {
        return authorizerArn;
    }

    public void setAuthorizerArn(String authorizerArn) {
        this.authorizerArn = authorizerArn;
    }

    public String getAuthorizerFunctionArn() {
        return authorizerFunctionArn;
    }

    public void setAuthorizerFunctionArn(String authorizerFunctionArn) {
        this.authorizerFunctionArn = authorizerFunctionArn;
    }

    public String getTokenKeyName() {
        return tokenKeyName;
    }

    public void setTokenKeyName(String tokenKeyName) {
        this.tokenKeyName = tokenKeyName;
    }

    public Map<String, String> getTokenSigningPublicKeys() {
        return tokenSigningPublicKeys;
    }

    public void setTokenSigningPublicKeys(Map<String, String> tokenSigningPublicKeys) {
        this.tokenSigningPublicKeys = tokenSigningPublicKeys;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isSigningDisabled() {
        return signingDisabled;
    }

    public void setSigningDisabled(boolean signingDisabled) {
        this.signingDisabled = signingDisabled;
    }

    public boolean isEnableCachingForHttp() {
        return enableCachingForHttp;
    }

    public void setEnableCachingForHttp(boolean enableCachingForHttp) {
        this.enableCachingForHttp = enableCachingForHttp;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(Instant lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }
}
