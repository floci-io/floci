package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Internal storage record caching the push timestamp for a digest, since the
 * Docker Registry HTTP API does not expose push timestamps.
 */
@RegisterForReflection
public class ImageMetadata {
    private String digest;
    private Instant pushedAt;

    public ImageMetadata() {}

    public ImageMetadata(String digest, Instant pushedAt) {
        this.digest = digest;
        this.pushedAt = pushedAt;
    }

    public String getDigest() { return digest; }
    public void setDigest(String digest) { this.digest = digest; }

    public Instant getPushedAt() { return pushedAt; }
    public void setPushedAt(Instant pushedAt) { this.pushedAt = pushedAt; }
}
