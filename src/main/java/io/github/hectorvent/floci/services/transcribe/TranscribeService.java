package io.github.hectorvent.floci.services.transcribe;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJob;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJobSummary;
import io.github.hectorvent.floci.services.transcribe.model.VocabularyInfo;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub for Amazon Transcribe. Jobs transition to COMPLETED and vocabularies
 * to READY immediately. No real transcription is performed.
 *
 * @see <a href="https://docs.aws.amazon.com/transcribe/latest/APIReference/Welcome.html">Transcribe API</a>
 */
@ApplicationScoped
public class TranscribeService {

    private final ConcurrentHashMap<String, TranscriptionJob> transcriptionJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VocabularyInfo> vocabularies = new ConcurrentHashMap<>();

    public TranscriptionJob startTranscriptionJob(String jobName, String mediaFileUri,
                                                  String languageCode, String mediaFormat) {
        requireNonBlank(jobName, "TranscriptionJobName");
        requireNonBlank(mediaFileUri, "Media.MediaFileUri");
        if (transcriptionJobs.containsKey(jobName)) {
            throw new AwsException("ConflictException",
                    "The requested job name already exists. Use a different job name.", 400);
        }

        long now = Instant.now().getEpochSecond();
        TranscriptionJob job = new TranscriptionJob(
                jobName,
                "COMPLETED",
                languageCode != null ? languageCode : "en-US",
                mediaFormat != null ? mediaFormat : "mp4",
                48000,
                new TranscriptionJob.Media(mediaFileUri),
                new TranscriptionJob.Transcript("s3://floci-transcribe-output/" + jobName + ".json"),
                now, now, now);

        transcriptionJobs.put(jobName, job);
        return job;
    }

    public TranscriptionJob getTranscriptionJob(String jobName) {
        requireNonBlank(jobName, "TranscriptionJobName");
        TranscriptionJob job = transcriptionJobs.get(jobName);
        if (job == null) {
            throw new AwsException("NotFoundException",
                    "The requested job couldn't be found. Check the job name and try your request again.", 400);
        }
        return job;
    }

    public ListTranscriptionJobsResult listTranscriptionJobs(String statusFilter, String jobNameContains,
                                                             Integer maxResults) {
        int limit = maxResults != null ? Math.min(maxResults, 100) : 100;

        List<TranscriptionJobSummary> filtered = transcriptionJobs.values().stream()
                .filter(j -> statusFilter == null || statusFilter.equals(j.transcriptionJobStatus()))
                .filter(j -> jobNameContains == null || j.transcriptionJobName().contains(jobNameContains))
                .sorted(Comparator.comparing(TranscriptionJob::transcriptionJobName))
                .map(TranscriptionJobSummary::from)
                .toList();

        List<TranscriptionJobSummary> page = filtered.subList(0, Math.min(limit, filtered.size()));
        String nextToken = page.size() < filtered.size()
                ? page.get(page.size() - 1).transcriptionJobName() : null;

        return new ListTranscriptionJobsResult(page, statusFilter, nextToken);
    }

    public void deleteTranscriptionJob(String jobName) {
        requireNonBlank(jobName, "TranscriptionJobName");
        if (transcriptionJobs.remove(jobName) == null) {
            throw new AwsException("BadRequestException",
                    "The requested job couldn't be found. Check the job name and try your request again.", 400);
        }
    }

    public VocabularyInfo createVocabulary(String vocabularyName, String languageCode) {
        requireNonBlank(vocabularyName, "VocabularyName");
        requireNonBlank(languageCode, "LanguageCode");
        if (vocabularies.containsKey(vocabularyName)) {
            throw new AwsException("ConflictException",
                    "The requested vocabulary name already exists. Use a different vocabulary name.", 400);
        }

        VocabularyInfo vocab = new VocabularyInfo(
                vocabularyName, languageCode, "READY", Instant.now().getEpochSecond());
        vocabularies.put(vocabularyName, vocab);
        return vocab;
    }

    public VocabularyInfo getVocabulary(String vocabularyName) {
        requireNonBlank(vocabularyName, "VocabularyName");
        VocabularyInfo vocab = vocabularies.get(vocabularyName);
        if (vocab == null) {
            throw new AwsException("NotFoundException",
                    "The requested vocabulary couldn't be found. Check the vocabulary name and try your request again.",
                    400);
        }
        return vocab;
    }

    public ListVocabulariesResult listVocabularies(String stateEquals, String nameContains,
                                                   Integer maxResults) {
        int limit = maxResults != null ? Math.min(maxResults, 100) : 100;

        List<VocabularyInfo> filtered = vocabularies.values().stream()
                .filter(v -> stateEquals == null || stateEquals.equals(v.vocabularyState()))
                .filter(v -> nameContains == null || v.vocabularyName().contains(nameContains))
                .sorted(Comparator.comparing(VocabularyInfo::vocabularyName))
                .toList();

        List<VocabularyInfo> page = filtered.subList(0, Math.min(limit, filtered.size()));
        String nextToken = page.size() < filtered.size()
                ? page.get(page.size() - 1).vocabularyName() : null;

        return new ListVocabulariesResult(page, stateEquals, nextToken);
    }

    public void deleteVocabulary(String vocabularyName) {
        requireNonBlank(vocabularyName, "VocabularyName");
        if (vocabularies.remove(vocabularyName) == null) {
            throw new AwsException("NotFoundException",
                    "The requested vocabulary couldn't be found. Check the vocabulary name and try your request again.",
                    400);
        }
    }

    private void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value null at '" + fieldName
                            + "' failed to satisfy constraint: Member must not be null",
                    400);
        }
    }

    public record ListTranscriptionJobsResult(
            List<TranscriptionJobSummary> summaries, String status, String nextToken) {}

    public record ListVocabulariesResult(
            List<VocabularyInfo> vocabularies, String status, String nextToken) {}
}
