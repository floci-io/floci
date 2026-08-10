package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Org-wide IAM centralized root access management state.
 *
 * <p>Backs the IAM {@code ListOrganizationsFeatures} /
 * {@code Enable|DisableOrganizationsRootCredentialsManagement} /
 * {@code Enable|DisableOrganizationsRootSessions} operations. AWS exposes these on the
 * IAM (Query-protocol) endpoint even though the state is organization-scoped; the set of
 * currently-enabled features is what {@code ListOrganizationsFeatures} returns.
 *
 * <p>Recognized feature strings: {@code RootCredentialsManagement}, {@code RootSessions}.
 * A {@link LinkedHashSet} keeps enablement order stable and de-duplicates, so repeated
 * enable calls are idempotent.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationRootFeatures {

    private Set<String> enabledFeatures = new LinkedHashSet<>();

    public Set<String> getEnabledFeatures() {
        return enabledFeatures;
    }

    public void setEnabledFeatures(Set<String> enabledFeatures) {
        this.enabledFeatures = enabledFeatures != null ? enabledFeatures : new LinkedHashSet<>();
    }
}
