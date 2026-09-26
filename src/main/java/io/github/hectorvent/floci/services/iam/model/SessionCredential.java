package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionCredential {

    private String accessKeyId;
    private String secretAccessKey;
    private String sessionToken;
    private String roleArn;
    private String roleSessionName;
    private String assumedRoleId;
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
    private String ec2InstanceId;
    private String ec2RoleId;
    /** Exact ECS task ARN this session was minted for, when this is a task-role session. */
    private String ecsTaskArn;

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

    public String getRoleSessionName() { return roleSessionName; }
    public void setRoleSessionName(String roleSessionName) { this.roleSessionName = roleSessionName; }

    public String getAssumedRoleId() { return assumedRoleId; }
    public void setAssumedRoleId(String assumedRoleId) { this.assumedRoleId = assumedRoleId; }

    public Instant getExpiration() { return expiration; }
    public void setExpiration(Instant expiration) { this.expiration = expiration; }

    public String getSessionPolicyDocument() { return sessionPolicyDocument; }
    public void setSessionPolicyDocument(String sessionPolicyDocument) { this.sessionPolicyDocument = sessionPolicyDocument; }

    public String getOriginAccountId() { return originAccountId; }
    public void setOriginAccountId(String originAccountId) { this.originAccountId = originAccountId; }

    public String getEc2RoleId() { return ec2RoleId; }
    public void setEc2RoleId(String ec2RoleId) { this.ec2RoleId = ec2RoleId; }

    public String getEc2InstanceId() { return ec2InstanceId; }
    public void setEc2InstanceId(String ec2InstanceId) { this.ec2InstanceId = ec2InstanceId; }

    public String getEcsTaskArn() { return ecsTaskArn; }
    public void setEcsTaskArn(String ecsTaskArn) { this.ecsTaskArn = ecsTaskArn; }

    public boolean isLambdaExecutionRole() { return lambdaExecutionRole; }
    public void setLambdaExecutionRole(boolean lambdaExecutionRole) { this.lambdaExecutionRole = lambdaExecutionRole; }
}
