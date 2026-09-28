package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * The account's single stored credential report, Base64-encoded CSV content plus the time it
 * was generated. AWS stores at most one per account: generating a new one overwrites the
 * previous one, matching {@link io.github.hectorvent.floci.services.iam.IamService}'s other
 * single-value-per-account stores ({@code accountAliases}, {@code passwordPolicies}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CredentialReport {

    private String base64Content;
    private Instant generatedTime;

    public CredentialReport() {}

    public CredentialReport(String base64Content, Instant generatedTime) {
        this.base64Content = base64Content;
        this.generatedTime = generatedTime;
    }

    public String getBase64Content() { return base64Content; }
    public void setBase64Content(String base64Content) { this.base64Content = base64Content; }

    public Instant getGeneratedTime() { return generatedTime; }
    public void setGeneratedTime(Instant generatedTime) { this.generatedTime = generatedTime; }
}
