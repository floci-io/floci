package io.github.hectorvent.floci.services.transcribe.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptionJobSummary(
        @JsonProperty("TranscriptionJobName") String transcriptionJobName,
        @JsonProperty("TranscriptionJobStatus") String transcriptionJobStatus,
        @JsonProperty("LanguageCode") String languageCode,
        @JsonProperty("CreationTime") Long creationTime,
        @JsonProperty("StartTime") Long startTime,
        @JsonProperty("CompletionTime") Long completionTime
) {
    public static TranscriptionJobSummary from(TranscriptionJob job) {
        return new TranscriptionJobSummary(
                job.transcriptionJobName(),
                job.transcriptionJobStatus(),
                job.languageCode(),
                job.creationTime(),
                job.startTime(),
                job.completionTime());
    }
}
