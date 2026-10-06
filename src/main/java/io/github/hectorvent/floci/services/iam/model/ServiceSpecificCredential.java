package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A credential scoped to one AWS service, owned by one IAM user. The service named at creation is
 * "the only service that can be accessed using these credentials".
 *
 * <p>The model carries two different credential shapes and a credential has one of them, which is
 * why neither pair is required on the shape. A service taking a user name and password, such as
 * CodeCommit, gets {@code serviceUserName} and {@code servicePassword}. A service taking a
 * long-term API key gets {@code serviceCredentialAlias} and {@code serviceCredentialSecret}, and
 * only that kind can carry an {@code expirationDate}, since {@code CredentialAgeDays} "is only
 * valid for services that support long-term API keys".
 *
 * <p>The secret half, whichever it is, is returned only by the create and the reset. There is no
 * get operation, and the list returns metadata that the model defines without either secret, so
 * nothing else can hand it back.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceSpecificCredential {
    private String userName;
    private String serviceName;
    private String serviceSpecificCredentialId;
    private String status = "Active";
    private Instant createDate = Instant.now();

    /** The user-name-and-password shape. Never echoed back after the create or a reset. */
    private String serviceUserName;
    private String servicePassword;

    /** The long-term API key shape. The secret is never echoed back either. */
    private String serviceCredentialAlias;
    private String serviceCredentialSecret;

    /** Only ever set for a long-term API key, and only when CredentialAgeDays was given. */
    private Instant expirationDate;

    public ServiceSpecificCredential() {}

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getServiceSpecificCredentialId() { return serviceSpecificCredentialId; }
    public void setServiceSpecificCredentialId(String serviceSpecificCredentialId) {
        this.serviceSpecificCredentialId = serviceSpecificCredentialId;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }
    public String getServiceUserName() { return serviceUserName; }
    public void setServiceUserName(String serviceUserName) {
        this.serviceUserName = serviceUserName;
    }
    public String getServicePassword() { return servicePassword; }
    public void setServicePassword(String servicePassword) {
        this.servicePassword = servicePassword;
    }
    public String getServiceCredentialAlias() { return serviceCredentialAlias; }
    public void setServiceCredentialAlias(String serviceCredentialAlias) {
        this.serviceCredentialAlias = serviceCredentialAlias;
    }
    public String getServiceCredentialSecret() { return serviceCredentialSecret; }
    public void setServiceCredentialSecret(String serviceCredentialSecret) {
        this.serviceCredentialSecret = serviceCredentialSecret;
    }
    public Instant getExpirationDate() { return expirationDate; }
    public void setExpirationDate(Instant expirationDate) {
        this.expirationDate = expirationDate;
    }
}
