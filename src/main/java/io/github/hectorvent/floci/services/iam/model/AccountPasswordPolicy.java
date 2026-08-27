package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An account holds at most one password policy — this is the whole shape of it, not a
 * per-resource record. Unset optional integer fields ({@code maxPasswordAge}, {@code
 * passwordReusePrevention}) are {@code null} rather than defaulted, matching AWS:
 * {@code GetAccountPasswordPolicy} omits the element entirely when the caller never set it.
 * {@code hardExpiry} is different — AWS documents it as a boolean that always defaults to
 * {@code false}, so unlike the two integer fields it is never absent from the response.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountPasswordPolicy {

    private int minimumPasswordLength = 6;
    private boolean requireSymbols;
    private boolean requireNumbers;
    private boolean requireUppercaseCharacters;
    private boolean requireLowercaseCharacters;
    private boolean allowUsersToChangePassword;
    private Integer maxPasswordAge;
    private Integer passwordReusePrevention;
    private boolean hardExpiry;

    public AccountPasswordPolicy() {}

    public int getMinimumPasswordLength() { return minimumPasswordLength; }
    public void setMinimumPasswordLength(int minimumPasswordLength) {
        this.minimumPasswordLength = minimumPasswordLength;
    }

    public boolean isRequireSymbols() { return requireSymbols; }
    public void setRequireSymbols(boolean requireSymbols) { this.requireSymbols = requireSymbols; }

    public boolean isRequireNumbers() { return requireNumbers; }
    public void setRequireNumbers(boolean requireNumbers) { this.requireNumbers = requireNumbers; }

    public boolean isRequireUppercaseCharacters() { return requireUppercaseCharacters; }
    public void setRequireUppercaseCharacters(boolean requireUppercaseCharacters) {
        this.requireUppercaseCharacters = requireUppercaseCharacters;
    }

    public boolean isRequireLowercaseCharacters() { return requireLowercaseCharacters; }
    public void setRequireLowercaseCharacters(boolean requireLowercaseCharacters) {
        this.requireLowercaseCharacters = requireLowercaseCharacters;
    }

    public boolean isAllowUsersToChangePassword() { return allowUsersToChangePassword; }
    public void setAllowUsersToChangePassword(boolean allowUsersToChangePassword) {
        this.allowUsersToChangePassword = allowUsersToChangePassword;
    }

    public Integer getMaxPasswordAge() { return maxPasswordAge; }
    public void setMaxPasswordAge(Integer maxPasswordAge) { this.maxPasswordAge = maxPasswordAge; }

    public Integer getPasswordReusePrevention() { return passwordReusePrevention; }
    public void setPasswordReusePrevention(Integer passwordReusePrevention) {
        this.passwordReusePrevention = passwordReusePrevention;
    }

    public boolean isHardExpiry() { return hardExpiry; }
    public void setHardExpiry(boolean hardExpiry) { this.hardExpiry = hardExpiry; }

    /** AWS derives ExpirePasswords from whether a max age is set — it is never stored directly. */
    public boolean isExpirePasswords() { return maxPasswordAge != null; }
}
