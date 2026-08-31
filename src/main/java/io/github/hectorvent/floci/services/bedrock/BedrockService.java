package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrock.model.BatchManifest;
import io.github.hectorvent.floci.services.bedrock.model.CreateModelInvocationJobRequest;
import io.github.hectorvent.floci.services.bedrock.model.CreateModelInvocationJobResponse;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJob;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJobStatus;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJobSummary;
import io.github.hectorvent.floci.services.bedrockruntime.BedrockRuntimeService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@ApplicationScoped
public class BedrockService implements Resettable {

    private static final Logger LOG = Logger.getLogger(BedrockService.class);
    private static final int MAX_PAGE = 1000;
    private static final String CHARACTERS = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, ModelInvocationJob> jobStore;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final BedrockRuntimeService bedrockRuntimeService;
    private final S3Service s3Service;
    private final EventBridgeService eventBridgeService;

    @Inject
    public BedrockService(StorageFactory storageFactory,
                          EmulatorConfig config,
                          RegionResolver regionResolver,
                          ObjectMapper objectMapper,
                          BedrockRuntimeService bedrockRuntimeService,
                          S3Service s3Service,
                          EventBridgeService eventBridgeService) {
        this.jobStore = storageFactory.create("bedrock", "bedrock-jobs.json",
                new TypeReference<Map<String, ModelInvocationJob>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.bedrockRuntimeService = bedrockRuntimeService;
        this.s3Service = s3Service;
        this.eventBridgeService = eventBridgeService;
    }

    @Override
    public void clear() {
        jobStore.clear();
    }

    public CreateModelInvocationJobResponse createModelInvocationJob(
            CreateModelInvocationJobRequest request, String region) {
        if (request == null) {
            throw new AwsException("ValidationException", "Request payload is required.", 400);
        }
        if (request.jobName() == null || request.jobName().isBlank()) {
            throw new AwsException("ValidationException", "jobName is required.", 400);
        }
        if (request.modelId() == null || request.modelId().isBlank()) {
            throw new AwsException("ValidationException", "modelId is required.", 400);
        }
        if (request.roleArn() == null || request.roleArn().isBlank()) {
            throw new AwsException("ValidationException", "roleArn is required.", 400);
        }
        if (request.inputDataConfig() == null
                || request.inputDataConfig().s3InputDataConfig() == null
                || request.inputDataConfig().s3InputDataConfig().s3Uri() == null
                || request.inputDataConfig().s3InputDataConfig().s3Uri().isBlank()) {
            throw new AwsException("ValidationException",
                    "inputDataConfig.s3InputDataConfig.s3Uri is required.", 400);
        }
        if (request.outputDataConfig() == null
                || request.outputDataConfig().s3OutputDataConfig() == null
                || request.outputDataConfig().s3OutputDataConfig().s3Uri() == null
                || request.outputDataConfig().s3OutputDataConfig().s3Uri().isBlank()) {
            throw new AwsException("ValidationException",
                    "outputDataConfig.s3OutputDataConfig.s3Uri is required.", 400);
        }

        String effectiveRegion = region != null && !region.isBlank()
                ? region : regionResolver.getDefaultRegion();
        String accountId = regionResolver.getAccountId();
        String jobId = generateJobId();
        String jobArn = "arn:aws:bedrock:" + effectiveRegion + ":" + accountId + ":model-invocation-job/" + jobId;

        Instant now = Instant.now();
        int timeoutHours = request.timeoutDurationInHours() != null
                ? request.timeoutDurationInHours() : 24;

        ModelInvocationJob job = new ModelInvocationJob(
                jobArn,
                request.jobName(),
                request.modelId(),
                request.clientRequestToken(),
                request.roleArn(),
                ModelInvocationJobStatus.SUBMITTED,
                null,
                now,
                now,
                null,
                now.plus(Duration.ofHours(timeoutHours)),
                timeoutHours,
                request.inputDataConfig(),
                request.outputDataConfig(),
                request.vpcConfig(),
                request.tags()
        );

        jobStore.put(jobArn, job);
        LOG.infov("Created model invocation job: {0} ({1}) in region {2}",
                job.getJobName(), jobArn, effectiveRegion);

        emitStateChangeEvent(job, effectiveRegion, accountId);

        // Execute batch inference asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                executeBatchJob(job, effectiveRegion, accountId);
            } catch (Exception e) {
                LOG.errorv(e, "Unexpected error running batch job {0}", jobArn);
                failJob(job, "Unexpected batch error: " + e.getMessage(), effectiveRegion, accountId);
            }
        });

        return new CreateModelInvocationJobResponse(jobArn);
    }

    public ModelInvocationJob getModelInvocationJob(String jobIdentifier, String region) {
        if (jobIdentifier == null || jobIdentifier.isBlank()) {
            throw new AwsException("ValidationException", "jobIdentifier is required.", 400);
        }

        Optional<ModelInvocationJob> job = jobStore.scan(k -> true).stream()
                .filter(j -> jobIdentifier.equals(j.getJobArn())
                        || jobIdentifier.equals(j.getJobId())
                        || jobIdentifier.equals(j.getJobName()))
                .findFirst();

        return job.orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Could not find model invocation job with identifier: " + jobIdentifier, 404));
    }

    public void stopModelInvocationJob(String jobIdentifier, String region) {
        ModelInvocationJob job = getModelInvocationJob(jobIdentifier, region);
        if (job.getStatus() == ModelInvocationJobStatus.COMPLETED
                || job.getStatus() == ModelInvocationJobStatus.FAILED
                || job.getStatus() == ModelInvocationJobStatus.STOPPED
                || job.getStatus() == ModelInvocationJobStatus.EXPIRED
                || job.getStatus() == ModelInvocationJobStatus.PARTIALLY_COMPLETED) {
            throw new AwsException("ValidationException",
                    "Job " + job.getJobArn() + " is already in terminal status: " + job.getStatus(), 400);
        }

        String effectiveRegion = region != null && !region.isBlank()
                ? region : regionResolver.getDefaultRegion();
        String accountId = regionResolver.getAccountId();

        Instant now = Instant.now();
        job.setStatus(ModelInvocationJobStatus.STOPPED);
        job.setEndTime(now);
        job.setLastModifiedTime(now);
        jobStore.put(job.getJobArn(), job);

        LOG.infov("Stopped model invocation job: {0}", job.getJobArn());
        emitStateChangeEvent(job, effectiveRegion, accountId);
    }

    public PaginatedResult<ModelInvocationJobSummary> listModelInvocationJobs(
            ModelInvocationJobStatus statusEquals,
            String nameContains,
            Instant submitTimeAfter,
            Instant submitTimeBefore,
            String sortBy,
            String sortOrder,
            Integer maxResults,
            String nextToken,
            String region) {

        List<ModelInvocationJob> matched = jobStore.scan(k -> true).stream()
                .filter(job -> {
                    if (statusEquals != null && job.getStatus() != statusEquals) {
                        return false;
                    }
                    if (nameContains != null && !nameContains.isBlank()) {
                        if (job.getJobName() == null || !job.getJobName().contains(nameContains)) {
                            return false;
                        }
                    }
                    if (submitTimeAfter != null && job.getSubmitTime() != null
                            && !job.getSubmitTime().isAfter(submitTimeAfter)) {
                        return false;
                    }
                    if (submitTimeBefore != null && job.getSubmitTime() != null
                            && !job.getSubmitTime().isBefore(submitTimeBefore)) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        boolean ascending = "Ascending".equalsIgnoreCase(sortOrder);
        Comparator<ModelInvocationJob> comparator = Comparator.comparing(
                ModelInvocationJob::getSubmitTime,
                Comparator.nullsLast(Comparator.naturalOrder())
        );
        if (!ascending) {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparing(ModelInvocationJob::getJobArn);
        matched.sort(comparator);

        List<ModelInvocationJobSummary> summaries = matched.stream()
                .map(ModelInvocationJobSummary::from)
                .toList();

        return Pagination.paginate(
                summaries,
                ModelInvocationJobSummary::jobArn,
                maxResults,
                nextToken,
                MAX_PAGE,
                "ValidationException"
        );
    }

    private boolean isJobStopped(ModelInvocationJob job) {
        return jobStore.get(job.getJobArn())
                .map(j -> j.getStatus() == ModelInvocationJobStatus.STOPPED)
                .orElse(false);
    }

    private void executeBatchJob(ModelInvocationJob job, String region, String accountId) {
        // Validating
        job.setStatus(ModelInvocationJobStatus.VALIDATING);
        job.setLastModifiedTime(Instant.now());
        jobStore.put(job.getJobArn(), job);
        emitStateChangeEvent(job, region, accountId);

        if (isJobStopped(job)) return;

        // Scheduled
        job.setStatus(ModelInvocationJobStatus.SCHEDULED);
        job.setLastModifiedTime(Instant.now());
        jobStore.put(job.getJobArn(), job);
        emitStateChangeEvent(job, region, accountId);

        if (isJobStopped(job)) return;

        // Transition to InProgress
        job.setStatus(ModelInvocationJobStatus.IN_PROGRESS);
        job.setLastModifiedTime(Instant.now());
        jobStore.put(job.getJobArn(), job);
        emitStateChangeEvent(job, region, accountId);

        if (isJobStopped(job)) return;

        String inputS3Uri = job.getInputDataConfig().s3InputDataConfig().s3Uri();
        S3Location inputLoc = parseS3Uri(inputS3Uri);

        if (s3Service == null) {
            failJob(job, "S3 service is not available", region, accountId);
            return;
        }

        byte[] inputBytes;
        try {
            S3Object s3Obj = s3Service.getObject(inputLoc.bucket(), inputLoc.key());
            if (s3Obj == null || s3Obj.getData() == null) {
                if (isJobStopped(job)) return;
                failJob(job, "Input S3 file is empty or not found: " + inputS3Uri, region, accountId);
                return;
            }
            inputBytes = s3Obj.getData();
        } catch (Exception e) {
            if (isJobStopped(job)) return;
            LOG.warnv(e, "Failed to read S3 input file: {0}", inputS3Uri);
            failJob(job, "Failed to read input S3 file: " + e.getMessage(), region, accountId);
            return;
        }

        String content = new String(inputBytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\\r?\\n");
        List<String> outputLines = new ArrayList<>();
        long totalCount = 0;
        long processedCount = 0;
        long successCount = 0;
        long errorCount = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            totalCount++;
            processedCount++;

            String parsedRecordId = null;
            JsonNode parsedModelInput = null;
            try {
                JsonNode lineNode = objectMapper.readTree(line);
                parsedRecordId = lineNode.path("recordId").asText(null);
                parsedModelInput = lineNode.get("modelInput");

                if (parsedRecordId == null || parsedModelInput == null || !parsedModelInput.isObject()) {
                    ObjectNode errorRecord = objectMapper.createObjectNode();
                    errorRecord.put("recordId", parsedRecordId != null ? parsedRecordId : "RECORD_" + totalCount);
                    if (parsedModelInput != null) {
                        errorRecord.set("modelInput", parsedModelInput);
                    } else {
                        errorRecord.putObject("modelInput");
                    }
                    ObjectNode err = errorRecord.putObject("error");
                    err.put("errorCode", "400");
                    err.put("errorMessage", "Malformed record: recordId and modelInput object are required.");
                    outputLines.add(objectMapper.writeValueAsString(errorRecord));
                    errorCount++;
                    continue;
                }

                JsonNode modelOutputNode = processModelInput(job.getModelId(), (ObjectNode) parsedModelInput);
                totalInputTokens += extractInputTokens(modelOutputNode);
                totalOutputTokens += extractOutputTokens(modelOutputNode);

                ObjectNode outputRecord = objectMapper.createObjectNode();
                outputRecord.put("recordId", parsedRecordId);
                outputRecord.set("modelInput", parsedModelInput);
                outputRecord.set("modelOutput", modelOutputNode);
                outputLines.add(objectMapper.writeValueAsString(outputRecord));
                successCount++;
            } catch (Exception e) {
                LOG.warnv("Batch inference record error: {0}", e.getMessage());
                ObjectNode errorRecord = objectMapper.createObjectNode();
                errorRecord.put("recordId", parsedRecordId != null ? parsedRecordId : "RECORD_" + totalCount);
                if (parsedModelInput != null) {
                    errorRecord.set("modelInput", parsedModelInput);
                }
                ObjectNode err = errorRecord.putObject("error");
                err.put("errorCode", "500");
                err.put("errorMessage", e.getMessage());
                try {
                    outputLines.add(objectMapper.writeValueAsString(errorRecord));
                } catch (Exception ignored) {
                }
                errorCount++;
            }
        }

        // Write output files to S3
        String outputS3Uri = job.getOutputDataConfig().s3OutputDataConfig().s3Uri();
        S3Location outLoc = parseS3Uri(outputS3Uri);

        String inputKey = inputLoc.key();
        String inputFilename = inputKey.contains("/")
                ? inputKey.substring(inputKey.lastIndexOf('/') + 1) : inputKey;
        if (inputFilename.isBlank()) {
            inputFilename = "input.jsonl";
        }

        String outPrefix = outLoc.key().isEmpty()
                ? job.getJobId()
                : (outLoc.key().replaceAll("/+$", "") + "/" + job.getJobId());
        String outputJsonlKey = outPrefix + "/" + inputFilename + ".out";
        String manifestKey = outPrefix + "/manifest.json.out";

        String outputJsonlUri = "s3://" + outLoc.bucket() + "/" + outputJsonlKey;

        try {
            String outputContent = String.join("\n", outputLines)
                    + (outputLines.isEmpty() ? "" : "\n");
            s3Service.putObject(outLoc.bucket(), outputJsonlKey,
                    outputContent.getBytes(StandardCharsets.UTF_8),
                    "application/jsonlines", Map.of());

            BatchManifest manifest = new BatchManifest(
                    totalCount,
                    processedCount,
                    successCount,
                    errorCount,
                    totalInputTokens > 0 ? totalInputTokens : null,
                    totalOutputTokens > 0 ? totalOutputTokens : null
            );
            byte[] manifestBytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(manifest);
            s3Service.putObject(outLoc.bucket(), manifestKey, manifestBytes,
                    "application/json", Map.of());
        } catch (Exception e) {
            LOG.warnv(e, "Failed to write batch output files to S3 bucket {0}", outLoc.bucket());
            failJob(job, "Failed to write batch output to S3: " + e.getMessage(), region, accountId);
            return;
        }

        Instant completionTime = Instant.now();
        job.setEndTime(completionTime);
        job.setLastModifiedTime(completionTime);
        job.setTotalRecordCount(totalCount);
        job.setProcessedRecordCount(processedCount);
        job.setSuccessRecordCount(successCount);
        job.setErrorRecordCount(errorCount);
        job.setInputTokenCount(totalInputTokens > 0 ? totalInputTokens : null);
        job.setOutputTokenCount(totalOutputTokens > 0 ? totalOutputTokens : null);

        if (totalCount == 0) {
            job.setStatus(ModelInvocationJobStatus.COMPLETED);
        } else if (errorCount == 0) {
            job.setStatus(ModelInvocationJobStatus.COMPLETED);
        } else if (successCount > 0) {
            job.setStatus(ModelInvocationJobStatus.PARTIALLY_COMPLETED);
            job.setMessage(String.format("Batch job partially completed with %d successes and %d errors.",
                    successCount, errorCount));
        } else {
            job.setStatus(ModelInvocationJobStatus.FAILED);
            job.setMessage(String.format("Batch job failed: all %d records failed.", totalCount));
        }

        jobStore.put(job.getJobArn(), job);
        LOG.infov("Completed model invocation job {0} with status {1}",
                job.getJobArn(), job.getStatus());
        emitStateChangeEvent(job, region, accountId);
    }

    private static long extractInputTokens(JsonNode output) {
        if (output == null) {
            return 0;
        }
        if (output.path("usage").hasNonNull("inputTokens")) {
            return output.path("usage").get("inputTokens").asLong(0);
        }
        if (output.path("usage").hasNonNull("input_tokens")) {
            return output.path("usage").get("input_tokens").asLong(0);
        }
        if (output.path("usage").hasNonNull("prompt_tokens")) {
            return output.path("usage").get("prompt_tokens").asLong(0);
        }
        if (output.hasNonNull("inputTextTokenCount")) {
            return output.get("inputTextTokenCount").asLong(0);
        }
        if (output.hasNonNull("prompt_token_count")) {
            return output.get("prompt_token_count").asLong(0);
        }
        return 0;
    }

    private static long extractOutputTokens(JsonNode output) {
        if (output == null) {
            return 0;
        }
        if (output.path("usage").hasNonNull("outputTokens")) {
            return output.path("usage").get("outputTokens").asLong(0);
        }
        if (output.path("usage").hasNonNull("output_tokens")) {
            return output.path("usage").get("output_tokens").asLong(0);
        }
        if (output.path("usage").hasNonNull("completion_tokens")) {
            return output.path("usage").get("completion_tokens").asLong(0);
        }
        if (output.hasNonNull("generation_token_count")) {
            return output.get("generation_token_count").asLong(0);
        }
        if (output.path("results").isArray() && !output.path("results").isEmpty()) {
            return output.path("results").path(0).path("tokenCount").asLong(0);
        }
        return 0;
    }

    private JsonNode processModelInput(String modelId, ObjectNode modelInputNode) throws Exception {
        if (modelInputNode.has("anthropic_version") || modelInputNode.has("prompt")) {
            byte[] responseBytes = bedrockRuntimeService.buildInvokeModelResponse(
                    modelId, objectMapper.writeValueAsBytes(modelInputNode));
            return objectMapper.readTree(responseBytes);
        }
        if (modelInputNode.has("messages")) {
            try {
                return bedrockRuntimeService.buildConverseResponse(modelId, modelInputNode);
            } catch (Exception e) {
                byte[] responseBytes = bedrockRuntimeService.buildInvokeModelResponse(
                        modelId, objectMapper.writeValueAsBytes(modelInputNode));
                return objectMapper.readTree(responseBytes);
            }
        }
        byte[] responseBytes = bedrockRuntimeService.buildInvokeModelResponse(
                modelId, objectMapper.writeValueAsBytes(modelInputNode));
        return objectMapper.readTree(responseBytes);
    }

    private void failJob(ModelInvocationJob job, String message, String region, String accountId) {
        Instant now = Instant.now();
        job.setStatus(ModelInvocationJobStatus.FAILED);
        job.setMessage(message);
        job.setEndTime(now);
        job.setLastModifiedTime(now);
        jobStore.put(job.getJobArn(), job);
        LOG.warnv("Model invocation job {0} failed: {1}", job.getJobArn(), message);
        emitStateChangeEvent(job, region, accountId);
    }

    private static final DateTimeFormatter CREATION_TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private void emitStateChangeEvent(ModelInvocationJob job, String region, String accountId) {
        if (eventBridgeService == null) {
            return;
        }
        try {
            String effectiveAccount = accountId != null ? accountId : "000000000000";

            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("version", "0.0");
            detail.put("accountId", effectiveAccount);
            detail.put("batchJobName", job.getJobName());
            detail.put("batchJobArn", job.getJobArn());
            detail.put("batchModelId", job.getModelId());
            detail.put("status", job.getStatus() != null ? job.getStatus().getValue() : null);
            detail.put("failureMessage", job.getMessage() != null ? job.getMessage() : "");
            detail.put("creationTime", job.getSubmitTime() != null
                    ? CREATION_TIME_FMT.format(job.getSubmitTime()) : "");

            ArrayNode resources = objectMapper.createArrayNode();
            if (job.getJobArn() != null) {
                resources.add(job.getJobArn());
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", "aws.bedrock");
            entry.put("DetailType", "Batch Inference Job State Change");
            entry.put("Detail", objectMapper.writeValueAsString(detail));
            entry.put("Resources", resources);
            entry.put("EventBusName", "default");
            entry.put("Region", region);
            entry.put("Account", effectiveAccount);

            eventBridgeService.putEvents(List.of(entry), region, accountId);
        } catch (Exception e) {
            LOG.warnv("Failed to emit Bedrock EventBridge state change event for job {0}: {1}",
                    job.getJobArn(), e.getMessage());
        }
    }

    record S3Location(String bucket, String key) {}

    static S3Location parseS3Uri(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            throw new AwsException("ValidationException", "Invalid S3 URI: " + uri, 400);
        }
        String path = uri.substring(5);
        int slash = path.indexOf('/');
        if (slash == -1) {
            return new S3Location(path, "");
        }
        return new S3Location(path.substring(0, slash), path.substring(slash + 1));
    }

    private static String generateJobId() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
