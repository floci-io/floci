package io.floci.aws.services.codeartifact.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * One restriction type's own stored setting on a {@link PackageGroup}: a mode ({@code ALLOW},
 * {@code BLOCK}, {@code ALLOW_SPECIFIC_REPOSITORIES}, or {@code INHERIT}), plus the
 * allowed-repository list {@code ALLOW_SPECIFIC_REPOSITORIES} uses. The list is kept even when
 * {@code mode} is not {@code ALLOW_SPECIFIC_REPOSITORIES}, the same way real CodeArtifact
 * preserves an allowed-repository list across a mode change away from and back to it (confirmed
 * against {@code UpdatePackageGroupOriginConfiguration}'s own documented behavior: {@code
 * addAllowedRepositories}/{@code removeAllowedRepositories} are independent of {@code
 * restrictions} in the same call).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageGroupRestriction {
    private String mode;
    private List<String> allowedRepositories = new ArrayList<>();

    public PackageGroupRestriction() {
    }

    public PackageGroupRestriction(String mode, List<String> allowedRepositories) {
        this.mode = mode;
        this.allowedRepositories = allowedRepositories;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<String> getAllowedRepositories() {
        return allowedRepositories;
    }

    public void setAllowedRepositories(List<String> allowedRepositories) {
        this.allowedRepositories = allowedRepositories;
    }
}
