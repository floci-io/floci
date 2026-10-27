package io.github.hectorvent.floci.services.codeartifact.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The origin-control restrictions a caller explicitly set on a package via
 * {@code PutPackageOriginConfiguration}. A package with no record here still has an origin
 * configuration, it is just CodeArtifact's documented default (publish ALLOW, upstream BLOCK);
 * see {@code CodeArtifactService.PackageDescription}'s own Javadoc for why that default holds
 * for every package Floci serves until a caller overrides it.
 *
 * <p>{@code format}/{@code namespace}/{@code packageName} are carried on the value here, not only
 * encoded into the storage key that names it, for the same reason {@code
 * CodeArtifactPackageVersion} already carries its own: {@code ListPackages} needs to enumerate
 * every package a whole repository holds, including one that only ever got an origin
 * configuration and no real version, and the key itself is escaped specifically to make that
 * lossy to recover (see {@code CodeArtifactService#escapeKeySegment}'s own javadoc). Reading
 * these fields back off the scanned value instead, the same way {@code
 * CodeArtifactService#genericPackageVersions} already reads a version record's own fields rather
 * than parsing its key, avoids that entirely. {@code null} for a record written before this field
 * existed; a package that old is simply left out of that one enumeration, the same accepted gap
 * {@code GetPackageVersionReadme}'s own pre-existing-field limitation already documents elsewhere.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageOriginConfig {
    private String publishRestriction;
    private String upstreamRestriction;
    private String format;
    private String namespace;
    private String packageName;

    public PackageOriginConfig() {
    }

    public PackageOriginConfig(String publishRestriction, String upstreamRestriction, String format,
            String namespace, String packageName) {
        this.publishRestriction = publishRestriction;
        this.upstreamRestriction = upstreamRestriction;
        this.format = format;
        this.namespace = namespace;
        this.packageName = packageName;
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

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }
}
