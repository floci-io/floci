package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Fine-grained access control options. Round-tripped on Describe; the actual
 * security plugin remains disabled inside the OpenSearch container regardless,
 * since Floci is a local emulator and the plugin requires TLS + a real cluster
 * config that we don't ship.
 *
 * <p>Sensitive fields (master password, SAML metadata) are accepted on the
 * request but never serialized back — matching AWS behavior, which only echoes
 * the username and structural flags.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdvancedSecurityOptions {

    @JsonProperty("Enabled")
    private boolean enabled = false;

    @JsonProperty("InternalUserDatabaseEnabled")
    private boolean internalUserDatabaseEnabled = false;

    @JsonProperty("AnonymousAuthEnabled")
    private boolean anonymousAuthEnabled = false;

    @JsonProperty("AnonymousAuthDisableDate")
    private String anonymousAuthDisableDate;

    /** {@code MasterUserName} only — passwords / ARNs are intentionally not echoed back. */
    @JsonProperty("MasterUserOptions")
    private MasterUserOptions masterUserOptions;

    public AdvancedSecurityOptions() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isInternalUserDatabaseEnabled() {
        return internalUserDatabaseEnabled;
    }

    public void setInternalUserDatabaseEnabled(boolean internalUserDatabaseEnabled) {
        this.internalUserDatabaseEnabled = internalUserDatabaseEnabled;
    }

    public boolean isAnonymousAuthEnabled() {
        return anonymousAuthEnabled;
    }

    public void setAnonymousAuthEnabled(boolean anonymousAuthEnabled) {
        this.anonymousAuthEnabled = anonymousAuthEnabled;
    }

    public String getAnonymousAuthDisableDate() {
        return anonymousAuthDisableDate;
    }

    public void setAnonymousAuthDisableDate(String anonymousAuthDisableDate) {
        this.anonymousAuthDisableDate = anonymousAuthDisableDate;
    }

    public MasterUserOptions getMasterUserOptions() {
        return masterUserOptions;
    }

    public void setMasterUserOptions(MasterUserOptions masterUserOptions) {
        this.masterUserOptions = masterUserOptions;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MasterUserOptions {
        @JsonProperty("MasterUserName")
        private String masterUserName;

        @JsonProperty("MasterUserARN")
        private String masterUserArn;

        public MasterUserOptions() {}

        public String getMasterUserName() {
            return masterUserName;
        }

        public void setMasterUserName(String masterUserName) {
            this.masterUserName = masterUserName;
        }

        public String getMasterUserArn() {
            return masterUserArn;
        }

        public void setMasterUserArn(String masterUserArn) {
            this.masterUserArn = masterUserArn;
        }
    }
}
