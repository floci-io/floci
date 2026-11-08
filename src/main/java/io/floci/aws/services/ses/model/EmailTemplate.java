package io.floci.aws.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmailTemplate {

    @JsonProperty("TemplateName")
    private String templateName;

    @JsonProperty("Subject")
    private String subject;

    @JsonProperty("TextPart")
    private String textPart;

    @JsonProperty("HtmlPart")
    private String htmlPart;

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("LastUpdatedTimestamp")
    private Instant lastUpdatedTimestamp;

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    public EmailTemplate() {}

    public EmailTemplate(String templateName, String subject, String textPart, String htmlPart) {
        this.templateName = templateName;
        this.subject = subject;
        this.textPart = textPart;
        this.htmlPart = htmlPart;
    }

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getTextPart() { return textPart; }
    public void setTextPart(String textPart) { this.textPart = textPart; }

    public String getHtmlPart() { return htmlPart; }
    public void setHtmlPart(String htmlPart) { this.htmlPart = htmlPart; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public Instant getLastUpdatedTimestamp() { return lastUpdatedTimestamp; }
    public void setLastUpdatedTimestamp(Instant lastUpdatedTimestamp) { this.lastUpdatedTimestamp = lastUpdatedTimestamp; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }
}
