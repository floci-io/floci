package io.github.hectorvent.floci.services.codeartifact.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The origin-control restrictions a caller explicitly set on a package via
 * {@code PutPackageOriginConfiguration}. A package with no record here still has an origin
 * configuration, it is just CodeArtifact's documented default (publish ALLOW, upstream BLOCK);
 * see {@code CodeArtifactService.PackageDescription}'s own Javadoc for why that default holds
 * for every package Floci serves until a caller overrides it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageOriginConfig {
    private String publishRestriction;
    private String upstreamRestriction;

    public PackageOriginConfig() {
    }

    public PackageOriginConfig(String publishRestriction, String upstreamRestriction) {
        this.publishRestriction = publishRestriction;
        this.upstreamRestriction = upstreamRestriction;
    }

    public String getPublishRestriction() {
        return publishRestriction;
    }

    public void setPublishRestriction(String publishRestriction) {
        this.publishRestriction = publishRestriction;
    }

    public String getUpstreamRestriction() {
        return upstreamRestriction;
    }

    public void setUpstreamRestriction(String upstreamRestriction) {
        this.upstreamRestriction = upstreamRestriction;
    }
}
