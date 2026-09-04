package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionCredential {

    private String accessKeyId;
    private String secretAccessKey;
    /** Session token returned by AWS STS/ECS; null for legacy sessions that predate token tracking. */
    private String sessionToken;
    private String roleArn;
    private Instant expiration;
    /** Inline session policy passed to AssumeRole/GetFederationToken — further restricts role policies. */
    private String sessionPolicyDocument;
    /**
     * Account of the caller that minted this session, captured at mint time. Used to route
     * temporary credentials that carry no role ARN (e.g. GetSessionToken) back to the caller.
     */
    private String originAccountId;
    /** True when this session belongs to a Floci-launched Lambda container. */
    private boolean lambdaExecutionRole;
    /** True when this session was minted for an ECS task-role credential endpoint. */
    private boolean ecsTaskRole;
    /** Exact ECS task ARN that owns this transient session. */
    private String taskArn;
    /** Opaque path component used by AWS_CONTAINER_CREDENTIALS_RELATIVE_URI. */
    private String credentialPath;
    /** Last issuance time used for AWS-compatible refresh metadata. */
    private Instant lastUpdated;
    /** Revocation tombstone for fail-closed ECS credential authentication. */
    private boolean revoked;

    public SessionCredential() {}

    public SessionCredential(String accessKeyId, String roleArn, Instant expiration) {
        this.accessKeyId = accessKeyId;
        this.roleArn = roleArn;
        this.expiration = expiration;
    }

    public SessionCredential(String accessKeyId, String roleArn, Instant expiration, String sessionPolicyDocument) {
        this.accessKeyId = accessKeyId;
        this.roleArn = roleArn;
        this.expiration = expiration;
        this.sessionPolicyDocument = sessionPolicyDocument;
    }

    public SessionCredential(String accessKeyId, String secretAccessKey, String roleArn, Instant expiration,
                              String sessionPolicyDocument) {
        this(accessKeyId, secretAccessKey, null, roleArn, expiration, sessionPolicyDocument);
    }

    public SessionCredential(String accessKeyId, String secretAccessKey, String sessionToken, String roleArn,
                              Instant expiration, String sessionPolicyDocument) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.roleArn = roleArn;
        this.expiration = expiration;
        this.sessionPolicyDocument = sessionPolicyDocument;
    }

    public SessionCredential(String accessKeyId, String secretAccessKey, String roleArn, Instant expiration,
                              String sessionPolicyDocument, String originAccountId) {
        this(accessKeyId, secretAccessKey, null, roleArn, expiration, sessionPolicyDocument, originAccountId);
    }

    public SessionCredential(String accessKeyId, String secretAccessKey, String sessionToken, String roleArn,
                              Instant expiration, String sessionPolicyDocument, String originAccountId) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.roleArn = roleArn;
        this.expiration = expiration;
        this.sessionPolicyDocument = sessionPolicyDocument;
        this.originAccountId = originAccountId;
    }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }

    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public Instant getExpiration() { return expiration; }
    public void setExpiration(Instant expiration) { this.expiration = expiration; }

    public String getSessionPolicyDocument() { return sessionPolicyDocument; }
    public void setSessionPolicyDocument(String sessionPolicyDocument) { this.sessionPolicyDocument = sessionPolicyDocument; }

    public String getOriginAccountId() { return originAccountId; }
    public void setOriginAccountId(String originAccountId) { this.originAccountId = originAccountId; }

    public boolean isLambdaExecutionRole() { return lambdaExecutionRole; }
    public void setLambdaExecutionRole(boolean lambdaExecutionRole) { this.lambdaExecutionRole = lambdaExecutionRole; }

    public boolean isEcsTaskRole() { return ecsTaskRole; }
    public void setEcsTaskRole(boolean ecsTaskRole) { this.ecsTaskRole = ecsTaskRole; }

    public String getTaskArn() { return taskArn; }
    public void setTaskArn(String taskArn) { this.taskArn = taskArn; }

    public String getCredentialPath() { return credentialPath; }
    public void setCredentialPath(String credentialPath) { this.credentialPath = credentialPath; }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
