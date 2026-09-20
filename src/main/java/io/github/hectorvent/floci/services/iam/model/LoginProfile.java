package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A user's console (password) login profile. At most one per user, mirroring AWS: creating
 * one when the user already has one is {@code EntityAlreadyExists}, and a user with no
 * console password simply has no entry here. The password is stored in plain text, matching
 * how {@link AccessKey} already stores its secret: this is an emulator, not a credential
 * store, and AWS itself never echoes the password back on any of these actions.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginProfile {

    private String userName;
    private String password;
    private boolean passwordResetRequired;
    private Instant createDate;

    public LoginProfile() {}

    public LoginProfile(String userName, String password, boolean passwordResetRequired) {
        this.userName = userName;
        this.password = password;
        this.passwordResetRequired = passwordResetRequired;
        this.createDate = Instant.now();
    }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isPasswordResetRequired() { return passwordResetRequired; }
    public void setPasswordResetRequired(boolean passwordResetRequired) {
        this.passwordResetRequired = passwordResetRequired;
    }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }
}
