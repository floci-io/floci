package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** A custom authorizer as AWS IoT Core reports it through DescribeAuthorizer, plus its tags and default flag. */
@RegisterForReflection
public class IotAuthorizer {
    private String authorizerName;
    private String authorizerArn;
    private String authorizerFunctionArn;
    private String tokenKeyName;
    private Map<String, String> tokenSigningPublicKeys;
    private String status;
    private boolean signingDisabled;
    private boolean enableCachingForHttp;
    private Instant creationDate;
    private Instant lastModifiedDate;
    private boolean defaultAuthorizer;
    private Map<String, String> tags = new TreeMap<>();

    public String getAuthorizerName() { return authorizerName; }
    public void setAuthorizerName(String authorizerName) { this.authorizerName = authorizerName; }
    public String getAuthorizerArn() { return authorizerArn; }
    public void setAuthorizerArn(String authorizerArn) { this.authorizerArn = authorizerArn; }
    public String getAuthorizerFunctionArn() { return authorizerFunctionArn; }
    public void setAuthorizerFunctionArn(String authorizerFunctionArn) { this.authorizerFunctionArn = authorizerFunctionArn; }
    public String getTokenKeyName() { return tokenKeyName; }
    public void setTokenKeyName(String tokenKeyName) { this.tokenKeyName = tokenKeyName; }
    public Map<String, String> getTokenSigningPublicKeys() { return tokenSigningPublicKeys; }
    public void setTokenSigningPublicKeys(Map<String, String> tokenSigningPublicKeys) { this.tokenSigningPublicKeys = tokenSigningPublicKeys; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isSigningDisabled() { return signingDisabled; }
    public void setSigningDisabled(boolean signingDisabled) { this.signingDisabled = signingDisabled; }
    public boolean isEnableCachingForHttp() { return enableCachingForHttp; }
    public void setEnableCachingForHttp(boolean enableCachingForHttp) { this.enableCachingForHttp = enableCachingForHttp; }
    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }
    public Instant getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(Instant lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
    public boolean isDefaultAuthorizer() { return defaultAuthorizer; }
    public void setDefaultAuthorizer(boolean defaultAuthorizer) { this.defaultAuthorizer = defaultAuthorizer; }
    public Map<String, String> getTags() { return tags == null ? Map.of() : tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new TreeMap<>() : new TreeMap<>(tags); }
}
