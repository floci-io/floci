package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ImageConfiguration {
    private String imageUri;
    private String resolvedImageDigest;
    private Boolean applicationLevelDigestResolution;

    public String getImageUri() { return imageUri; }
    public void setImageUri(String imageUri) { this.imageUri = imageUri; }
    public String getResolvedImageDigest() { return resolvedImageDigest; }
    public void setResolvedImageDigest(String resolvedImageDigest) { this.resolvedImageDigest = resolvedImageDigest; }
    public Boolean getApplicationLevelDigestResolution() { return applicationLevelDigestResolution; }
    public void setApplicationLevelDigestResolution(Boolean applicationLevelDigestResolution) { this.applicationLevelDigestResolution = applicationLevelDigestResolution; }
}
