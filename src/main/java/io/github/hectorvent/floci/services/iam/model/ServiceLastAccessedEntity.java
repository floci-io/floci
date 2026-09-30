package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entity recorded in an Access Advisor report, carrying the {@code EntityInfo} members AWS
 * requires: {@code Arn}, {@code Name}, {@code Type} and {@code Id}, plus the optional {@code Path}.
 *
 * <p>Captured when the report is generated rather than resolved on read, so the report a completed
 * job returns does not change when the underlying entity is renamed, re-tagged or deleted.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceLastAccessedEntity {

    private String arn;
    private String name;
    private String type;
    private String id;
    private String path;

    public ServiceLastAccessedEntity() {}

    public ServiceLastAccessedEntity(String arn, String name, String type, String id, String path) {
        this.arn = arn;
        this.name = name;
        this.type = type;
        this.id = id;
        this.path = path;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
