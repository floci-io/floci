package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPool {
    private String id;
    private String name;
    private String arn;
    private String status = "Enabled";
    private String signingSecret;
    private String signingKeyId;
    private String signingPublicKey;
    private String signingPrivateKey;
    private long creationDate;
    private long lastModifiedDate;

    // Configuration fields
    private Map<String, Object> policies = new HashMap<>();
    private String deletionProtection = "INACTIVE";
    private Map<String, Object> lambdaConfig = new HashMap<>();
    private List<Map<String, Object>> schemaAttributes = new ArrayList<>();
    private List<String> autoVerifiedAttributes = new ArrayList<>();
    private List<String> aliasAttributes = new ArrayList<>();
    private List<String> usernameAttributes = new ArrayList<>();
    private String smsVerificationMessage;
    private String emailVerificationMessage;
    private String emailVerificationSubject;
    private Map<String, Object> verificationMessageTemplate = new HashMap<>();
    private String smsAuthenticationMessage;
    private String mfaConfiguration = "OFF";
    private Map<String, Object> deviceConfiguration = new HashMap<>();
    private int estimatedNumberOfUsers = 0;
    private Map<String, Object> emailConfiguration = new HashMap<>();
    private Map<String, Object> smsConfiguration = new HashMap<>();
    private Map<String, String> userPoolTags = new HashMap<>();
    private Map<String, Object> adminCreateUserConfig = new HashMap<>();
    private Map<String, Object> userPoolAddOns = new HashMap<>();
    private Map<String, Object> usernameConfiguration = new HashMap<>();
    private Map<String, Object> accountRecoverySetting = new HashMap<>();
    private String userPoolTier = "ESSENTIALS";

    public UserPool() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
        this.signingSecret = java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }

    public String getSigningKeyId() { return signingKeyId; }
    public void setSigningKeyId(String signingKeyId) { this.signingKeyId = signingKeyId; }

    public String getSigningPublicKey() { return signingPublicKey; }
    public void setSigningPublicKey(String signingPublicKey) { this.signingPublicKey = signingPublicKey; }

    public String getSigningPrivateKey() { return signingPrivateKey; }
    public void setSigningPrivateKey(String signingPrivateKey) { this.signingPrivateKey = signingPrivateKey; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public Map<String, Object> getPolicies() { return policies; }
    public void setPolicies(Map<String, Object> policies) { this.policies = policies; }

    public String getDeletionProtection() { return deletionProtection; }
    public void setDeletionProtection(String deletionProtection) { this.deletionProtection = deletionProtection; }

    public Map<String, Object> getLambdaConfig() { return lambdaConfig; }
    public void setLambdaConfig(Map<String, Object> lambdaConfig) { this.lambdaConfig = lambdaConfig; }

    public List<Map<String, Object>> getSchemaAttributes() { return schemaAttributes; }
    public void setSchemaAttributes(List<Map<String, Object>> schemaAttributes) { this.schemaAttributes = schemaAttributes; }

    public List<String> getAutoVerifiedAttributes() { return autoVerifiedAttributes; }
    public void setAutoVerifiedAttributes(List<String> autoVerifiedAttributes) { this.autoVerifiedAttributes = autoVerifiedAttributes; }

    public List<String> getAliasAttributes() { return aliasAttributes; }
    public void setAliasAttributes(List<String> aliasAttributes) { this.aliasAttributes = aliasAttributes; }

    public List<String> getUsernameAttributes() { return usernameAttributes; }
    public void setUsernameAttributes(List<String> usernameAttributes) { this.usernameAttributes = usernameAttributes; }

    public String getSmsVerificationMessage() { return smsVerificationMessage; }
    public void setSmsVerificationMessage(String smsVerificationMessage) { this.smsVerificationMessage = smsVerificationMessage; }

    public String getEmailVerificationMessage() { return emailVerificationMessage; }
    public void setEmailVerificationMessage(String emailVerificationMessage) { this.emailVerificationMessage = emailVerificationMessage; }

    public String getEmailVerificationSubject() { return emailVerificationSubject; }
    public void setEmailVerificationSubject(String emailVerificationSubject) { this.emailVerificationSubject = emailVerificationSubject; }

    public Map<String, Object> getVerificationMessageTemplate() { return verificationMessageTemplate; }
    public void setVerificationMessageTemplate(Map<String, Object> verificationMessageTemplate) { this.verificationMessageTemplate = verificationMessageTemplate; }

    public String getSmsAuthenticationMessage() { return smsAuthenticationMessage; }
    public void setSmsAuthenticationMessage(String smsAuthenticationMessage) { this.smsAuthenticationMessage = smsAuthenticationMessage; }

    public String getMfaConfiguration() { return mfaConfiguration; }
    public void setMfaConfiguration(String mfaConfiguration) { this.mfaConfiguration = mfaConfiguration; }

    public Map<String, Object> getDeviceConfiguration() { return deviceConfiguration; }
    public void setDeviceConfiguration(Map<String, Object> deviceConfiguration) { this.deviceConfiguration = deviceConfiguration; }

    public int getEstimatedNumberOfUsers() { return estimatedNumberOfUsers; }
    public void setEstimatedNumberOfUsers(int estimatedNumberOfUsers) { this.estimatedNumberOfUsers = estimatedNumberOfUsers; }

    public Map<String, Object> getEmailConfiguration() { return emailConfiguration; }
    public void setEmailConfiguration(Map<String, Object> emailConfiguration) { this.emailConfiguration = emailConfiguration; }

    public Map<String, Object> getSmsConfiguration() { return smsConfiguration; }
    public void setSmsConfiguration(Map<String, Object> smsConfiguration) { this.smsConfiguration = smsConfiguration; }

    public Map<String, String> getUserPoolTags() { return userPoolTags; }
    public void setUserPoolTags(Map<String, String> userPoolTags) { this.userPoolTags = userPoolTags; }

    public Map<String, Object> getAdminCreateUserConfig() { return adminCreateUserConfig; }
    public void setAdminCreateUserConfig(Map<String, Object> adminCreateUserConfig) { this.adminCreateUserConfig = adminCreateUserConfig; }

    public Map<String, Object> getUserPoolAddOns() { return userPoolAddOns; }
    public void setUserPoolAddOns(Map<String, Object> userPoolAddOns) { this.userPoolAddOns = userPoolAddOns; }

    public Map<String, Object> getUsernameConfiguration() { return usernameConfiguration; }
    public void setUsernameConfiguration(Map<String, Object> usernameConfiguration) { this.usernameConfiguration = usernameConfiguration; }

    public Map<String, Object> getAccountRecoverySetting() { return accountRecoverySetting; }
    public void setAccountRecoverySetting(Map<String, Object> accountRecoverySetting) { this.accountRecoverySetting = accountRecoverySetting; }

    public String getUserPoolTier() { return userPoolTier; }
    public void setUserPoolTier(String userPoolTier) { this.userPoolTier = userPoolTier; }
}
