package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A custom verification email template: the branded email SES sends when verifying a new
 * sender email address (as opposed to {@link EmailTemplate}, which is for the emails a caller
 * sends to recipients). Shared by the v1 and v2 custom-verification-template APIs.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomVerificationEmailTemplate {

    @JsonProperty("TemplateName")
    private String templateName;

    @JsonProperty("FromEmailAddress")
    private String fromEmailAddress;

    @JsonProperty("TemplateSubject")
    private String templateSubject;

    @JsonProperty("TemplateContent")
    private String templateContent;

    @JsonProperty("SuccessRedirectionURL")
    private String successRedirectionURL;

    @JsonProperty("FailureRedirectionURL")
    private String failureRedirectionURL;

    public CustomVerificationEmailTemplate() {}

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }

    public String getFromEmailAddress() { return fromEmailAddress; }
    public void setFromEmailAddress(String fromEmailAddress) { this.fromEmailAddress = fromEmailAddress; }

    public String getTemplateSubject() { return templateSubject; }
    public void setTemplateSubject(String templateSubject) { this.templateSubject = templateSubject; }

    public String getTemplateContent() { return templateContent; }
    public void setTemplateContent(String templateContent) { this.templateContent = templateContent; }

    public String getSuccessRedirectionURL() { return successRedirectionURL; }
    public void setSuccessRedirectionURL(String successRedirectionURL) {
        this.successRedirectionURL = successRedirectionURL;
    }

    public String getFailureRedirectionURL() { return failureRedirectionURL; }
    public void setFailureRedirectionURL(String failureRedirectionURL) {
        this.failureRedirectionURL = failureRedirectionURL;
    }
}
