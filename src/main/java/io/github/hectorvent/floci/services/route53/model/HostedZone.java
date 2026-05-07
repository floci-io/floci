package io.github.hectorvent.floci.services.route53.model;

public class HostedZone {

    private String id;
    private String name;
    private String callerReference;
    private String comment;
    private boolean privateZone;
    private int resourceRecordSetCount;

    public HostedZone() {}

    public HostedZone(String id, String name, String callerReference,
                      String comment, boolean privateZone) {
        this.id = id;
        this.name = name;
        this.callerReference = callerReference;
        this.comment = comment;
        this.privateZone = privateZone;
        this.resourceRecordSetCount = 2;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public boolean isPrivateZone() { return privateZone; }
    public void setPrivateZone(boolean privateZone) { this.privateZone = privateZone; }

    public int getResourceRecordSetCount() { return resourceRecordSetCount; }
    public void setResourceRecordSetCount(int resourceRecordSetCount) {
        this.resourceRecordSetCount = resourceRecordSetCount;
    }
}
