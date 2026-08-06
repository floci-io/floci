package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class KeyGroup {

    private String id;
    private Instant lastModifiedTime;
    private String etag;
    private String name;
    private String comment;
    private List<String> items;

    public KeyGroup() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public List<String> getItems() { return items; }
    public void setItems(List<String> items) { this.items = items; }
}
