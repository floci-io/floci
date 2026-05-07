package io.github.hectorvent.floci.services.route53.model;

public class ChangeInfo {

    private String id;
    private String status;
    private String submittedAt;
    private String comment;

    public ChangeInfo() {}

    public ChangeInfo(String id, String submittedAt, String comment) {
        this.id = id;
        this.status = "INSYNC";
        this.submittedAt = submittedAt;
        this.comment = comment;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
