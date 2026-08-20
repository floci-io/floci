package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The account's IAM password policy, as set by {@code UpdateAccountPasswordPolicy}.
 *
 * <p>{@code maxPasswordAge}, {@code passwordReusePrevention} and {@code hardExpiry} are
 * genuinely optional on the wire: AWS omits them from {@code GetAccountPasswordPolicy}'s
 * response entirely when the account never set them, rather than rendering a zero/false
 * default, so they are boxed here to keep "unset" distinct from "set to a default".
 * {@code ExpirePasswords} is not stored — it is computed at read time from whether
 * {@code maxPasswordAge} is set, per AWS's own documented rule.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PasswordPolicy {

    private int minimumPasswordLength = 6;
    private boolean requireSymbols;
    private boolean requireNumbers;
    private boolean requireUppercaseCharacters;
    private boolean requireLowercaseCharacters;
    private boolean allowUsersToChangePassword;
    private Integer maxPasswordAge;
    private Integer passwordReusePrevention;
    private Boolean hardExpiry;

    public PasswordPolicy() {}

    public int getMinimumPasswordLength() { return minimumPasswordLength; }
    public void setMinimumPasswordLength(int minimumPasswordLength) { this.minimumPasswordLength = minimumPasswordLength; }

    public boolean isRequireSymbols() { return requireSymbols; }
    public void setRequireSymbols(boolean requireSymbols) { this.requireSymbols = requireSymbols; }

    public boolean isRequireNumbers() { return requireNumbers; }
    public void setRequireNumbers(boolean requireNumbers) { this.requireNumbers = requireNumbers; }

    public boolean isRequireUppercaseCharacters() { return requireUppercaseCharacters; }
    public void setRequireUppercaseCharacters(boolean requireUppercaseCharacters) { this.requireUppercaseCharacters = requireUppercaseCharacters; }

    public boolean isRequireLowercaseCharacters() { return requireLowercaseCharacters; }
    public void setRequireLowercaseCharacters(boolean requireLowercaseCharacters) { this.requireLowercaseCharacters = requireLowercaseCharacters; }

    public boolean isAllowUsersToChangePassword() { return allowUsersToChangePassword; }
    public void setAllowUsersToChangePassword(boolean allowUsersToChangePassword) { this.allowUsersToChangePassword = allowUsersToChangePassword; }

    public Integer getMaxPasswordAge() { return maxPasswordAge; }
    public void setMaxPasswordAge(Integer maxPasswordAge) { this.maxPasswordAge = maxPasswordAge; }

    public Integer getPasswordReusePrevention() { return passwordReusePrevention; }
    public void setPasswordReusePrevention(Integer passwordReusePrevention) { this.passwordReusePrevention = passwordReusePrevention; }

    public Boolean getHardExpiry() { return hardExpiry; }
    public void setHardExpiry(Boolean hardExpiry) { this.hardExpiry = hardExpiry; }
}
