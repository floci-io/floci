package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class CloudFrontFunction {

    private String name;
    private String stage;
    private String status;
    private String functionCode;
    private String runtime;
    private String comment;
    private String etag;
    private Instant createdTime;
    private Instant lastModifiedTime;
    private List<String> keyValueStoreArns = new ArrayList<>();

    public CloudFrontFunction() {}

    public List<String> getKeyValueStoreArns() { return keyValueStoreArns; }
    public void setKeyValueStoreArns(List<String> keyValueStoreArns) { this.keyValueStoreArns = keyValueStoreArns; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFunctionCode() { return functionCode; }
    public void setFunctionCode(String functionCode) { this.functionCode = functionCode; }

    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
}
