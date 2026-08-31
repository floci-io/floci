package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceSetting {

    @JsonProperty("SettingId")
    private String settingId;

    @JsonProperty("SettingValue")
    private String settingValue;

    @JsonProperty("LastModifiedDate")
    private Instant lastModifiedDate;

    @JsonProperty("LastModifiedUser")
    private String lastModifiedUser;

    @JsonProperty("ARN")
    private String arn;

    @JsonProperty("Status")
    private String status;

    public ServiceSetting() {}

    public ServiceSetting(String settingId, String settingValue, String arn, String status,
                          String lastModifiedUser) {
        this.settingId = settingId;
        this.settingValue = settingValue;
        this.arn = arn;
        this.status = status;
        this.lastModifiedUser = lastModifiedUser;
        this.lastModifiedDate = Instant.now();
    }

    public String getSettingId() {
        return settingId;
    }

    public void setSettingId(String settingId) {
        this.settingId = settingId;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(Instant lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public String getLastModifiedUser() {
        return lastModifiedUser;
    }

    public void setLastModifiedUser(String lastModifiedUser) {
        this.lastModifiedUser = lastModifiedUser;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
