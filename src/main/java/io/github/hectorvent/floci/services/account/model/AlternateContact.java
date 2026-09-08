package io.github.hectorvent.floci.services.account.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlternateContact {
    @JsonProperty("AlternateContactType")
    private String alternateContactType;
    @JsonProperty("EmailAddress")
    private String emailAddress;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("PhoneNumber")
    private String phoneNumber;
    @JsonProperty("Title")
    private String title;

    public AlternateContact() {
    }

    public AlternateContact(String alternateContactType, String emailAddress, String name,
                            String phoneNumber, String title) {
        this.alternateContactType = alternateContactType;
        this.emailAddress = emailAddress;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.title = title;
    }

    public String getAlternateContactType() {
        return alternateContactType;
    }

    public void setAlternateContactType(String alternateContactType) {
        this.alternateContactType = alternateContactType;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
