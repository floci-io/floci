package io.github.hectorvent.floci.services.iot.model;

public class IotShadow {
    private String thingName;
    private String shadowName;
    private String document;
    private long version;

    public String getThingName() { return thingName; }
    public void setThingName(String thingName) { this.thingName = thingName; }
    public String getShadowName() { return shadowName; }
    public void setShadowName(String shadowName) { this.shadowName = shadowName; }
    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
