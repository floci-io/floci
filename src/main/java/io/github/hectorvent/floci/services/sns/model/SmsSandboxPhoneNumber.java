package io.github.hectorvent.floci.services.sns.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A destination phone number in an account's SMS sandbox for one region, with its verification
 * status and the one-time password sent to it while it is still {@code Pending}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsSandboxPhoneNumber {

    public static final String PENDING = "Pending";
    public static final String VERIFIED = "Verified";

    @JsonProperty("PhoneNumber")
    private String phoneNumber;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("OneTimePassword")
    private String oneTimePassword;

    public SmsSandboxPhoneNumber() {}

    public SmsSandboxPhoneNumber(String phoneNumber, String status, String oneTimePassword) {
        this.phoneNumber = phoneNumber;
        this.status = status;
        this.oneTimePassword = oneTimePassword;
    }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOneTimePassword() { return oneTimePassword; }
    public void setOneTimePassword(String oneTimePassword) { this.oneTimePassword = oneTimePassword; }
}
