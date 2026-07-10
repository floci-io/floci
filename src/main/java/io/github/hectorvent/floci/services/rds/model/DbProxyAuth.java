package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One entry of an {@code AWS::RDS::DBProxy} {@code Auth} array (a secret-based auth descriptor). */
@RegisterForReflection
public class DbProxyAuth {

    private String authScheme;              // "SECRETS"
    private String secretArn;
    private String iamAuth;                 // "REQUIRED" | "DISABLED"
    private String clientPasswordAuthType;  // nullable
    private String description;             // nullable

    public DbProxyAuth() {}

    public DbProxyAuth(String authScheme, String secretArn, String iamAuth,
                       String clientPasswordAuthType, String description) {
        this.authScheme = authScheme;
        this.secretArn = secretArn;
        this.iamAuth = iamAuth;
        this.clientPasswordAuthType = clientPasswordAuthType;
        this.description = description;
    }

    public String getAuthScheme() { return authScheme; }
    public void setAuthScheme(String authScheme) { this.authScheme = authScheme; }

    public String getSecretArn() { return secretArn; }
    public void setSecretArn(String secretArn) { this.secretArn = secretArn; }

    public String getIamAuth() { return iamAuth; }
    public void setIamAuth(String iamAuth) { this.iamAuth = iamAuth; }

    public String getClientPasswordAuthType() { return clientPasswordAuthType; }
    public void setClientPasswordAuthType(String clientPasswordAuthType) { this.clientPasswordAuthType = clientPasswordAuthType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
