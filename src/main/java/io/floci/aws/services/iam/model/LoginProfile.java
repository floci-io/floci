package io.floci.aws.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A user's console (password) login profile. At most one per user, mirroring AWS: creating
 * one when the user already has one is {@code EntityAlreadyExists}, and a user with no
 * console password simply has no entry here. The password, and the history the reuse window is
 * checked against, are stored in plain text, matching how {@link AccessKey} already stores its
 * secret: this is an emulator, not a credential store, and AWS itself never echoes the password
 * back on any of these actions.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginProfile {

    private String userName;
    private String password;
    private boolean passwordResetRequired;
    private Instant createDate;
    /**
     * When the password was last set: the credential report's {@code password_last_changed}.
     * Set alongside {@code createDate} initially, and again whenever {@code UpdateLoginProfile}
     * actually changes the password (not merely {@code passwordResetRequired}). {@code null} for
     * a profile persisted before this field existed; callers fall back to {@link #createDate}.
     */
    private Instant passwordLastChanged;
    /**
     * The passwords this profile held before the current one, most recent first, bounded at the 24
     * {@code PasswordReusePrevention} is allowed to ask for. Recorded whether or not the account
     * sets that policy, so switching it on has something to compare against, and empty for a
     * profile persisted before this field existed.
     */
    private List<String> previousPasswords = new ArrayList<>();

    public LoginProfile() {}

    public LoginProfile(String userName, String password, boolean passwordResetRequired) {
        this.userName = userName;
        this.password = password;
        this.passwordResetRequired = passwordResetRequired;
        this.createDate = Instant.now();
        this.passwordLastChanged = this.createDate;
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

    public Instant getPasswordLastChanged() { return passwordLastChanged; }
    public void setPasswordLastChanged(Instant passwordLastChanged) { this.passwordLastChanged = passwordLastChanged; }

    public List<String> getPreviousPasswords() {
        if (previousPasswords == null) {
            previousPasswords = new ArrayList<>();
        }
        return previousPasswords;
    }

    public void setPreviousPasswords(List<String> previousPasswords) {
        this.previousPasswords =
                previousPasswords != null ? new ArrayList<>(previousPasswords) : new ArrayList<>();
    }
}
