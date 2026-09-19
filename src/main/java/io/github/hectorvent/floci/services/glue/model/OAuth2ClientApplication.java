package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuth2ClientApplication {
    @JsonProperty("UserManagedClientApplicationClientId")
    private String userManagedClientApplicationClientId;

    @JsonProperty("AWSManagedClientApplicationReference")
    private String awsManagedClientApplicationReference;

    public String getUserManagedClientApplicationClientId() { return userManagedClientApplicationClientId; }
    public void setUserManagedClientApplicationClientId(String userManagedClientApplicationClientId) {
        this.userManagedClientApplicationClientId = userManagedClientApplicationClientId;
    }

    public String getAwsManagedClientApplicationReference() { return awsManagedClientApplicationReference; }
    public void setAwsManagedClientApplicationReference(String awsManagedClientApplicationReference) {
        this.awsManagedClientApplicationReference = awsManagedClientApplicationReference;
    }
}
