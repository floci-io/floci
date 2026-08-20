package io.github.hectorvent.floci.services.accessanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * IAM Access Analyzer's {@code AnalyzerSummary}/{@code AnalyzerSummary} shape. AccessAnalyzer's
 * REST-JSON wire format is lowerCamelCase (a Smithy {@code restJson1} service, unlike the
 * PascalCase {@code json}-protocol services such as Backup), so field names here match the
 * JSON keys directly with no {@code @JsonProperty} translation needed - the same style
 * {@code cloudmap.model.Namespace} already uses.
 *
 * <p>{@code lastResourceAnalyzed}/{@code lastResourceAnalyzedAt}/{@code statusReason} are
 * genuinely optional on the wire (AWS omits them until an analysis actually runs, or unless
 * the analyzer failed) and floci runs no real analysis, so they stay {@code null} and
 * {@code @JsonInclude(NON_NULL)} drops them rather than serializing a fabricated value.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Analyzer {

    private String arn;
    private String name;
    private String type;
    private Instant createdAt;
    private String status;
    private String lastResourceAnalyzed;
    private Instant lastResourceAnalyzedAt;
    private Map<String, String> tags = new HashMap<>();

    public Analyzer() {}

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLastResourceAnalyzed() { return lastResourceAnalyzed; }
    public void setLastResourceAnalyzed(String lastResourceAnalyzed) { this.lastResourceAnalyzed = lastResourceAnalyzed; }

    public Instant getLastResourceAnalyzedAt() { return lastResourceAnalyzedAt; }
    public void setLastResourceAnalyzedAt(Instant lastResourceAnalyzedAt) { this.lastResourceAnalyzedAt = lastResourceAnalyzedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
}
