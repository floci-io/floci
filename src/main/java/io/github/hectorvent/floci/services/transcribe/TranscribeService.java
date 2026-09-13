package io.github.hectorvent.floci.services.transcribe;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJob;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJobSummary;
import io.github.hectorvent.floci.services.transcribe.model.VocabularyInfo;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub for Amazon Transcribe. Jobs transition to COMPLETED and vocabularies
 * to READY immediately. No real transcription is performed.
 *
 * @see <a href="https://docs.aws.amazon.com/transcribe/latest/APIReference/Welcome.html">Transcribe API</a>
 */
@ApplicationScoped
public class TranscribeService implements Resettable {

    private final StorageFactory storageFactory;
    private final RegionResolver regionResolver;

    // Transcription jobs are transient (async work deleted after processing); vocabularies are durable.
    private final ConcurrentHashMap<String, TranscriptionJob> transcriptionJobs = new ConcurrentHashMap<>();
    private AccountAwareStorageBackend<VocabularyInfo> vocabularies;

    @Inject
    public TranscribeService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.storageFactory = storageFactory;
        this.regionResolver = regionResolver;
    }

    TranscribeService(StorageFactory storageFactory) {
        this(storageFactory, new RegionResolver("us-east-1", "000000000000"));
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            vocabularies = AccountAwareStorageBackend.inMemory(regionResolver.getDefaultAccountId());
            return;
        }
        this.vocabularies = storageFactory.create("transcribe",
                "transcribe-vocabularies.json", new TypeReference<Map<String, VocabularyInfo>>() {});
    }

    public void clear() {
        transcriptionJobs.clear();
        if (vocabularies != null) {
            vocabularies.clear();
        }
    }

    public TranscriptionJob startTranscriptionJob(String jobName, String mediaFileUri,
                                                  String languageCode, String mediaFormat) {
        requireNonBlank(jobName, "TranscriptionJobName");
        requireNonBlank(mediaFileUri, "Media.MediaFileUri");
        String jobKey = jobKey(jobName);
        if (transcriptionJobs.containsKey(jobKey)) {
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

        transcriptionJobs.put(jobKey, job);
        return job;
    }

    public TranscriptionJob getTranscriptionJob(String jobName) {
        requireNonBlank(jobName, "TranscriptionJobName");
        TranscriptionJob job = transcriptionJobs.get(jobKey(jobName));
        if (job == null) {
            throw new AwsException("NotFoundException",
                    "The requested job couldn't be found. Check the job name and try your request again.", 400);
        }
        return job;
    }

    public ListTranscriptionJobsResult listTranscriptionJobs(String statusFilter, String jobNameContains,
                                                             Integer maxResults) {
        int limit = maxResults != null ? Math.min(maxResults, 100) : 100;

        String currentPrefix = currentJobPrefix();
        List<TranscriptionJobSummary> filtered = transcriptionJobs.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(currentPrefix))
                .map(Map.Entry::getValue)
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
        if (transcriptionJobs.remove(jobKey(jobName)) == null) {
            throw new AwsException("BadRequestException",
                    "The requested job couldn't be found. Check the job name and try your request again.", 400);
        }
    }

    public VocabularyInfo createVocabulary(String vocabularyName, String languageCode) {
        requireNonBlank(vocabularyName, "VocabularyName");
        requireNonBlank(languageCode, "LanguageCode");
        if (getStoredVocabulary(vocabularyName).isPresent()) {
            throw new AwsException("ConflictException",
                    "The requested vocabulary name already exists. Use a different vocabulary name.", 400);
        }

        VocabularyInfo vocab = new VocabularyInfo(
                vocabularyName, languageCode, "READY", Instant.now().getEpochSecond());
        vocabularies.putForAccount(regionResolver.getAccountId(), vocabularyKey(vocabularyName), vocab);
        return vocab;
    }

    public VocabularyInfo getVocabulary(String vocabularyName) {
        requireNonBlank(vocabularyName, "VocabularyName");
        VocabularyInfo vocab = getStoredVocabulary(vocabularyName).orElse(null);
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

        migrateDefaultScopeVocabularies();

        String currentPrefix = currentVocabularyPrefix();
        List<VocabularyInfo> filtered = vocabularies.scanForAccount(regionResolver.getAccountId(),
                        key -> key.startsWith(currentPrefix))
                .stream()
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
        if (getStoredVocabulary(vocabularyName).isEmpty()) {
            throw new AwsException("NotFoundException",
                    "The requested vocabulary couldn't be found. Check the vocabulary name and try your request again.",
                    400);
        }
        vocabularies.deleteForAccount(regionResolver.getAccountId(), vocabularyKey(vocabularyName));
    }

    private String jobKey(String jobName) {
        return currentJobPrefix() + jobName;
    }

    private String vocabularyKey(String vocabularyName) {
        return regionResolver.getRegion() + "/" + vocabularyName;
    }

    private String currentJobPrefix() {
        return regionResolver.getAccountId() + "/" + regionResolver.getRegion() + "/";
    }

    private String currentVocabularyPrefix() {
        return regionResolver.getRegion() + "/";
    }

    private Optional<VocabularyInfo> getStoredVocabulary(String vocabularyName) {
        String accountId = regionResolver.getAccountId();
        String region = regionResolver.getRegion();
        boolean isDefaultScope = regionResolver.getDefaultAccountId().equals(accountId)
                && regionResolver.getDefaultRegion().equals(region);
        return vocabularies.getForAccountMigratingLegacyKeys(
                accountId,
                region + "/" + vocabularyName,
                List.of(vocabularyName),
                ignored -> true,
                isDefaultScope);
    }

    private void migrateDefaultScopeVocabularies() {
        boolean isDefaultAccount = regionResolver.getDefaultAccountId().equals(regionResolver.getAccountId());
        if (isDefaultAccount && regionResolver.getDefaultRegion().equals(regionResolver.getRegion())) {
            vocabularies.scanUnscopedLegacy(ignored -> true).stream()
                    .map(VocabularyInfo::vocabularyName)
                    .forEach(this::getStoredVocabulary);
        }
        vocabularies.keysForAccount(regionResolver.getAccountId()).stream()
                .filter(key -> !key.contains("/"))
                .forEach(this::getStoredVocabulary);
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
