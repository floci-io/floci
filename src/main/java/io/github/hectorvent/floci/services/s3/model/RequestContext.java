package main.java.io.github.hectorvent.floci.services.s3.model;

public class RequestContext {

    private String bucket;
    private String prefix;
    private String maxKeys;
    private String keyMarker;

    public RequestContext(String bucket, String prefix, String maxKeys, String keyMarker) {
        this.bucket = bucket;
        this.prefix = prefix;
        this.maxKeys = maxKeys;
        this.keyMarker = keyMarker;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getMaxKeys() {
        return maxKeys;
    }

    public void setMaxKeys(String maxKeys) {
        this.maxKeys = maxKeys;
    }

    public String getKeyMarker() {
        return keyMarker;
    }

    public void setKeyMarker(String keyMarker) {
        this.keyMarker = keyMarker;
    }

}