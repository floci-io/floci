package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class KeyValueStore {

    private String name;
    private String id;
    private String comment;
    private String arn;
    private String status;
    private String etag;
    private String dataEtag;
    private Instant created;
    private Instant lastModifiedTime;
    private Map<String, String> items = new LinkedHashMap<>();

    public KeyValueStore() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getDataEtag() { return dataEtag; }
    public void setDataEtag(String dataEtag) { this.dataEtag = dataEtag; }

    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public Map<String, String> getItems() { return items; }
    public void setItems(Map<String, String> items) { this.items = items; }
}
