package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ImageConfiguration {
    private String imageUri;
    private String resolvedImageDigest;

    public String getImageUri() { return imageUri; }
    public void setImageUri(String imageUri) { this.imageUri = imageUri; }
    public String getResolvedImageDigest() { return resolvedImageDigest; }
    public void setResolvedImageDigest(String resolvedImageDigest) { this.resolvedImageDigest = resolvedImageDigest; }
}
