package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Processor;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessorParameter;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a flushed batch through the stream's Lambda transform before it is
 * delivered: one invocation per batch carrying every buffered record, the
 * returned data replacing the original, and records the function dropped or
 * failed taken out of the delivery. The failed ones go to the error output,
 * one NDJSON line each, under the evaluated ErrorOutputPrefix with
 * {@code !{firehose:error-output-type}} resolved to {@code processing-failed}.
 *
 * The per-record semantics were probed against real AWS (us-west-2,
 * 2026-09-10): {@code Ok} delivers the returned data, {@code Dropped} leaves no
 * trace at all, and anything else routes that record to the error output while
 * the rest of the batch still delivers. A function that errors is retried, a
 * response the function shaped wrongly is not. Every message below is AWS's own
 * except the two {@code Lambda.InvalidReturnFormat} ones this class invents, for
 * a {@code result} AWS does not define and for {@code data} that is not base64.
 *
 * This runs ahead of data format conversion, so a stream with both configured
 * converts what the transform returned.
 */
@ApplicationScoped
public class FirehoseLambdaTransformer {

    private static final Logger LOG = Logger.getLogger(FirehoseLambdaTransformer.class);
    private static final String ERROR_OUTPUT_TYPE = "processing-failed";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final int DEFAULT_NUMBER_OF_RETRIES = 3;
    private static final int MAX_NUMBER_OF_RETRIES = 100;
    private static final String RESULT_OK = "Ok";
    private static final String RESULT_DROPPED = "Dropped";
    private static final String RESULT_PROCESSING_FAILED = "ProcessingFailed";

    private static final String FUNCTION_ERROR_MESSAGE =
            "The Lambda function was successfully invoked but it returned an error result.";
    private static final String MISSING_RECORD_ID_MESSAGE =
            "One or more record Ids were not returned. Ensure that the Lambda function returns"
                    + " all received record Ids.";
    private static final String DUPLICATED_RECORD_ID_MESSAGE =
            "Multiple records were returned with the same record Id. Ensure that the Lambda"
                    + " function returns a unique record Id for each record.";
    private static final String PROCESSING_FAILED_MESSAGE = "ProcessingFailed status set for record";
    private static final String NULL_DATA_MESSAGE =
            "The data field cannot be null when the SourceType is DirectPut or KinesisStreamAsSource"
                    + " and processing result is Ok. The value field cannot be null when the SourceType"
                    + " is MSKAsSource and processing result is Ok.";

    private final LambdaService lambdaService;
    private final S3Service s3Service;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public FirehoseLambdaTransformer(LambdaService lambdaService, S3Service s3Service,
                                     ObjectMapper mapper, RegionResolver regionResolver) {
        this.lambdaService = lambdaService;
        this.s3Service = s3Service;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    /** What a transformation produced: the records still to deliver, plus the counts for the flush log line. */
    public record Outcome(List<byte[]> records, int droppedRecords, int failedRecords, String errorKey) {}

    public Outcome transform(DeliveryStreamDescription stream, String bucket, List<byte[]> records,
                             Instant deliveryTime) {
        S3Destination s3 = stream.s3Destination();
        Processor processor = lambdaProcessor(s3);
        if (processor == null) {
            return new Outcome(records, 0, 0, null);
        }
        String functionArn = parameterValue(processor, "LambdaArn");

        List<String> recordIds = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            recordIds.add(UUID.randomUUID().toString());
        }

        Invocation invocation = invoke(stream, functionArn, recordIds, records, deliveryTime,
                numberOfRetries(processor) + 1);

        List<byte[]> delivered = new ArrayList<>(records.size());
        List<FailedRecord> failures = new ArrayList<>();
        int dropped = 0;
        for (int i = 0; i < records.size(); i++) {
            byte[] record = records.get(i);
            Verdict verdict = invocation.verdictFor(recordIds.get(i));
            if (verdict.errorCode() != null) {
                failures.add(new FailedRecord(record, verdict.errorCode(), verdict.errorMessage()));
            } else if (verdict.data() == null) {
                dropped++;
            } else {
                delivered.add(verdict.data());
            }
        }

        String errorKey = failures.isEmpty() ? null
                : writeErrorOutput(stream, s3, bucket, failures, deliveryTime, functionArn,
                        invocation.attemptsMade());
        return new Outcome(delivered, dropped, failures.size(), errorKey);
    }

    // ── configuration ────────────────────────────────────────────────────────

    /**
     * The single Lambda processor of an enabled ProcessingConfiguration, or null when the
     * stream has none: the other processor types AWS models are stored and echoed but not
     * applied. The validator has already made "at most one" true.
     */
    private static Processor lambdaProcessor(S3Destination s3) {
        if (s3 == null || !s3.isProcessingEnabled() || s3.getProcessingConfiguration().getProcessors() == null) {
            return null;
        }
        for (Processor processor : s3.getProcessingConfiguration().getProcessors()) {
            if (processor != null && "Lambda".equals(processor.getType())
                    && parameterValue(processor, "LambdaArn") != null) {
                return processor;
            }
        }
        return null;
    }

    /**
     * AWS does not range-check NumberOfRetries at configuration time, so a stored value
     * can be any number the parameter's string type allows, 2147483647 included. It is
     * clamped rather than honored: a flush occupies the single flusher thread, so a retry
     * count that large would stall every other stream's delivery behind it. The cap is
     * well above the documented 1 to 8 and the 9 the probe saw honored. A value that is
     * not a number falls back to the 3 AWS itself defaults to.
     *
     * Read as a BigInteger so the clamp holds at any magnitude: a value too wide even for
     * a long is still a number, and treating it as unparseable would hand a larger
     * NumberOfRetries fewer attempts than a smaller one.
     */
    private static int numberOfRetries(Processor processor) {
        String configured = parameterValue(processor, "NumberOfRetries");
        if (configured == null) {
            return DEFAULT_NUMBER_OF_RETRIES;
        }
        try {
            return new BigInteger(configured.trim())
                    .max(BigInteger.ZERO)
                    .min(BigInteger.valueOf(MAX_NUMBER_OF_RETRIES))
                    .intValue();
        } catch (NumberFormatException e) {
            LOG.warnv("Firehose Lambda processor has a non-numeric NumberOfRetries {0}, using {1}",
                    configured, DEFAULT_NUMBER_OF_RETRIES);
            return DEFAULT_NUMBER_OF_RETRIES;
        }
    }

    private static String parameterValue(Processor processor, String name) {
        if (processor.getParameters() == null) {
            return null;
        }
        for (ProcessorParameter parameter : processor.getParameters()) {
            if (parameter != null && name.equals(parameter.getParameterName())) {
                return parameter.getParameterValue();
            }
        }
        return null;
    }

    // ── invocation ───────────────────────────────────────────────────────────

    /**
     * What one batch's invocation settled on: either a function-level failure every
     * record inherits, or the per-record results the function returned.
     */
    private record Invocation(int attemptsMade, boolean functionError,
                              Map<String, LambdaResult> results, Set<String> duplicatedIds) {

        Verdict verdictFor(String recordId) {
            if (functionError) {
                return Verdict.failed("Lambda.FunctionError", FUNCTION_ERROR_MESSAGE);
            }
            if (duplicatedIds.contains(recordId)) {
                return Verdict.failed("Lambda.DuplicatedRecordId", DUPLICATED_RECORD_ID_MESSAGE);
            }
            LambdaResult result = results.get(recordId);
            if (result == null) {
                return Verdict.failed("Lambda.MissingRecordId", MISSING_RECORD_ID_MESSAGE);
            }
            if (RESULT_DROPPED.equals(result.result())) {
                return Verdict.dropped();
            }
            if (RESULT_PROCESSING_FAILED.equals(result.result())) {
                return Verdict.failed("Lambda.ProcessingFailedStatus", PROCESSING_FAILED_MESSAGE);
            }
            if (!RESULT_OK.equals(result.result())) {
                return Verdict.failed("Lambda.InvalidReturnFormat",
                        "The result field must be one of Ok, Dropped or ProcessingFailed.");
            }
            if (result.data() == null) {
                return Verdict.failed("Lambda.InvalidReturnFormat", NULL_DATA_MESSAGE);
            }
            try {
                return Verdict.transformed(Base64.getDecoder().decode(result.data()));
            } catch (IllegalArgumentException e) {
                return Verdict.failed("Lambda.InvalidReturnFormat",
                        "The data field must be base64 encoded: " + e.getMessage());
            }
        }
    }

    private record LambdaResult(String result, String data) {}

    /** A transformed payload, a failure, or, with every member null, a drop. */
    private record Verdict(byte[] data, String errorCode, String errorMessage) {

        static Verdict transformed(byte[] data) {
            return new Verdict(data, null, null);
        }

        static Verdict dropped() {
            return new Verdict(null, null, null);
        }

        static Verdict failed(String errorCode, String errorMessage) {
            return new Verdict(null, errorCode, errorMessage);
        }
    }

    /**
     * Invokes the function, retrying only a function-level error: a response the
     * function returned successfully is taken as final however malformed it is (probed,
     * a response with no records at all reports attemptsMade 1 while a throwing
     * function reports NumberOfRetries + 1).
     */
    private Invocation invoke(DeliveryStreamDescription stream, String functionArn, List<String> recordIds,
                              List<byte[]> records, Instant deliveryTime, int maxAttempts) {
        byte[] payload = buildPayload(stream, recordIds, records, deliveryTime);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                InvokeResult result = lambdaService.invokeArn(functionArn, payload, InvocationType.RequestResponse);
                if (result.getFunctionError() == null) {
                    return parseResponse(attempt, result.getPayload());
                }
                LOG.warnv("Firehose transform {0} returned {1} on attempt {2} of {3}",
                        functionArn, result.getFunctionError(), attempt, maxAttempts);
            } catch (Exception e) {
                LOG.warnv("Firehose transform {0} could not be invoked on attempt {1} of {2}: {3}",
                        functionArn, attempt, maxAttempts, e.getMessage());
            }
        }
        return new Invocation(maxAttempts, true, Map.of(), Set.of());
    }

    private byte[] buildPayload(DeliveryStreamDescription stream, List<String> recordIds,
                                List<byte[]> records, Instant deliveryTime) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("invocationId", UUID.randomUUID().toString());
        event.put("deliveryStreamArn", stream.getDeliveryStreamARN());
        event.put("region", AwsArnUtils.regionOrDefault(stream.getDeliveryStreamARN(),
                regionResolver.getDefaultRegion()));
        List<Map<String, Object>> eventRecords = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> eventRecord = new LinkedHashMap<>();
            eventRecord.put("recordId", recordIds.get(i));
            // Floci does not track per-record arrival, so the batch's delivery time stands
            // in for all of them, as it does in the error output.
            eventRecord.put("approximateArrivalTimestamp", deliveryTime.toEpochMilli());
            eventRecord.put("data", Base64.getEncoder().encodeToString(records.get(i)));
            eventRecords.add(eventRecord);
        }
        event.put("records", eventRecords);
        try {
            return mapper.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new AwsException("InternalServerException",
                    "Failed to serialize a Firehose transformation payload: " + e.getMessage(), 500);
        }
    }

    /**
     * A response that carries no usable records array leaves every record unaccounted
     * for, which is what AWS reports for it: Lambda.MissingRecordId per record rather
     * than a batch-level failure (probed). An entry naming a record the batch never
     * contained is ignored, also as AWS does.
     */
    private Invocation parseResponse(int attempt, byte[] responsePayload) {
        Map<String, LambdaResult> results = new LinkedHashMap<>();
        Set<String> duplicatedIds = new HashSet<>();
        JsonNode records = readRecords(responsePayload);
        if (records != null) {
            for (JsonNode record : records) {
                String recordId = text(record, "recordId");
                if (recordId == null) {
                    continue;
                }
                LambdaResult result = new LambdaResult(text(record, "result"), text(record, "data"));
                if (results.putIfAbsent(recordId, result) != null) {
                    duplicatedIds.add(recordId);
                }
            }
        }
        return new Invocation(attempt, false, results, duplicatedIds);
    }

    private JsonNode readRecords(byte[] responsePayload) {
        if (responsePayload == null || responsePayload.length == 0) {
            return null;
        }
        try {
            JsonNode response = mapper.readTree(responsePayload);
            JsonNode records = response == null ? null : response.get("records");
            return records != null && records.isArray() ? records : null;
        } catch (Exception e) {
            LOG.warnv("Firehose transform returned a response that is not JSON: {0}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    // ── error output ─────────────────────────────────────────────────────────

    private record FailedRecord(byte[] data, String errorCode, String errorMessage) {}

    /**
     * One NDJSON line per failed record, in the shape and member order real AWS writes
     * (probed). It is not the format-conversion error object: that one leads with
     * attemptsMade and carries lastErrorCode and a dataCatalogTable block, where this
     * one leads with rawData and ends with lambdaARN, in that casing.
     *
     * Both timestamps are the delivery time, a deviation: Floci tracks no per-record
     * arrival. attemptsMade counts the batch's attempts, a record never having retries
     * of its own.
     */
    private String writeErrorOutput(DeliveryStreamDescription stream, S3Destination s3, String bucket,
                                    List<FailedRecord> failures, Instant deliveryTime,
                                    String functionArn, int attemptsMade) {
        String errorKey = S3ObjectKeyResolver.resolveErrorKey(s3, stream.getDeliveryStreamName(),
                stream.getVersionId(), deliveryTime, ERROR_OUTPUT_TYPE);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (FailedRecord failure : failures) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("rawData", Base64.getEncoder().encodeToString(failure.data()));
            line.put("errorCode", failure.errorCode());
            line.put("errorMessage", failure.errorMessage());
            line.put("attemptsMade", attemptsMade);
            line.put("arrivalTimestamp", deliveryTime.toEpochMilli());
            line.put("attemptEndingTimestamp", deliveryTime.toEpochMilli());
            line.put("lambdaARN", functionArn);
            try {
                body.write(mapper.writeValueAsBytes(line));
                body.write('\n');
            } catch (Exception e) {
                throw new AwsException("InternalServerException",
                        "Failed to serialize a Firehose error-output record: " + e.getMessage(), 500);
            }
        }
        s3Service.putObject(bucket, errorKey, body.toByteArray(), CONTENT_TYPE, Map.of());
        return errorKey;
    }
}
