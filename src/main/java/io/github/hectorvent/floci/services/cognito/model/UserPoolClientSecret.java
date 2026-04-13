package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPoolClientSecret {
    private String clientSecretId;
    private String clientSecretValue;
    private long clientSecretCreateDate;

    public UserPoolClientSecret(String clientSecretId,
                                long clientSecretCreateDate, String clientSecretValue) {
        this.clientSecretCreateDate = clientSecretCreateDate;
        this.clientSecretId = clientSecretId;
        this.clientSecretValue = clientSecretValue;
    }

    public long getClientSecretCreateDate() {
        return clientSecretCreateDate;
    }
    public void setClientSecretCreateDate(long clientSecretCreateDate) {
        this.clientSecretCreateDate = clientSecretCreateDate;
    }

    public String getClientSecretId() {
        return clientSecretId;
    }
    public void setClientSecretId(String clientSecretId) {
        this.clientSecretId = clientSecretId;
    }

    public String getClientSecretValue() {
        return clientSecretValue;
    }
    public void setClientSecretValue(String clientSecretValue) {
        this.clientSecretValue = clientSecretValue;
    }
}
