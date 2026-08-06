package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class OriginAccessControl {

    private String id;
    private String name;
    private String description;
    private String etag;
    private Instant lastModifiedTime;
    private String signingBehavior;
    private String signingProtocol;
    private String originAccessControlOriginType;

    public OriginAccessControl() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getSigningBehavior() { return signingBehavior; }
    public void setSigningBehavior(String signingBehavior) { this.signingBehavior = signingBehavior; }

    public String getSigningProtocol() { return signingProtocol; }
    public void setSigningProtocol(String signingProtocol) { this.signingProtocol = signingProtocol; }

    public String getOriginAccessControlOriginType() { return originAccessControlOriginType; }
    public void setOriginAccessControlOriginType(String originAccessControlOriginType) { this.originAccessControlOriginType = originAccessControlOriginType; }
}
