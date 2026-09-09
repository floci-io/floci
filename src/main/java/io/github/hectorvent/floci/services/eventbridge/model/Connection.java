package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class Connection {

    private String name;
    private String connectionArn;
    private String description;
    private String authorizationType;
    private ConnectionState connectionState;
    private String stateReason;
    // Raw request JSON, including secret values; responses must sanitize before returning.
    private String authParameters;
    private String invocationConnectivityParameters;
    private String secretArn;
    private String kmsKeyIdentifier;
    private Instant creationTime;
    private Instant lastModifiedTime;
    private Instant lastAuthorizedTime;

    public Connection() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getConnectionArn() { return connectionArn; }
    public void setConnectionArn(String connectionArn) { this.connectionArn = connectionArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAuthorizationType() { return authorizationType; }
    public void setAuthorizationType(String authorizationType) { this.authorizationType = authorizationType; }

    public ConnectionState getConnectionState() { return connectionState; }
    public void setConnectionState(ConnectionState connectionState) { this.connectionState = connectionState; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public String getAuthParameters() { return authParameters; }
    public void setAuthParameters(String authParameters) { this.authParameters = authParameters; }

    public String getInvocationConnectivityParameters() { return invocationConnectivityParameters; }
    public void setInvocationConnectivityParameters(String invocationConnectivityParameters) {
        this.invocationConnectivityParameters = invocationConnectivityParameters;
    }

    public String getSecretArn() { return secretArn; }
    public void setSecretArn(String secretArn) { this.secretArn = secretArn; }

    public String getKmsKeyIdentifier() { return kmsKeyIdentifier; }
    public void setKmsKeyIdentifier(String kmsKeyIdentifier) { this.kmsKeyIdentifier = kmsKeyIdentifier; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public Instant getLastAuthorizedTime() { return lastAuthorizedTime; }
    public void setLastAuthorizedTime(Instant lastAuthorizedTime) { this.lastAuthorizedTime = lastAuthorizedTime; }
}
