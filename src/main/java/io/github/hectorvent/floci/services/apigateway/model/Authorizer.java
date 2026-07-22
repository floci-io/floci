package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Authorizer {
    private String id;
    private String name;
    private String type; // TOKEN, REQUEST, COGNITO_USER_POOLS
    private String authorizerUri;
    private String identitySource;
    private String authorizerResultTtlInSeconds;
    private List<String> providerARNs; // COGNITO_USER_POOLS

    public Authorizer() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAuthorizerUri() { return authorizerUri; }
    public void setAuthorizerUri(String authorizerUri) { this.authorizerUri = authorizerUri; }

    public String getIdentitySource() { return identitySource; }
    public void setIdentitySource(String identitySource) { this.identitySource = identitySource; }

    public String getAuthorizerResultTtlInSeconds() { return authorizerResultTtlInSeconds; }
    public void setAuthorizerResultTtlInSeconds(String ttl) { this.authorizerResultTtlInSeconds = ttl; }

    public List<String> getProviderARNs() { return providerARNs; }
    public void setProviderARNs(List<String> providerARNs) { this.providerARNs = providerARNs; }
}
