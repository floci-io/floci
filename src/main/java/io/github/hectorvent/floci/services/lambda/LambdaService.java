package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import io.github.hectorvent.floci.services.lambda.model.ScalingConfig;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.s3.model.S3ObjectUpdatedEvent;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business logic for Lambda function management and invocation.
 */
@ApplicationScoped
public class LambdaService {

    private static final Logger LOG = Logger.getLogger(LambdaService.class);

    private final LambdaFunctionStore functionStore;
    private final LambdaExecutorService executorService;
    private final LambdaConcurrencyLimiter concurrencyLimiter;
    private final WarmPool warmPool;
    private final CodeStore codeStore;
    private final ZipExtractor zipExtractor;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EsmStore esmStore;
    private final LambdaAliasStore aliasStore;
    private final S3Service s3Service;
    private final SqsService sqsService;
    private final SqsEventSourcePoller poller;
    private final KinesisEventSourcePoller kinesisPoller;
    private final DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller;
    private final ConcurrentHashMap<String, Integer> versionCounters = new ConcurrentHashMap<>();
    /**
     * Per-function locks covering PutFunctionConcurrency,
     * DeleteFunctionConcurrency, and deleteFunction itself. Serializing the
     * limiter update + persistence pair against itself for a given function
     * prevents the limiter and store from diverging on interleaved concurrent
     * requests.
     *
     * <p>Entries are intentionally never removed — see {@code deleteFunction}
     * for the race this avoids. The map therefore grows by one {@code Object}
     * per distinct function ARN the emulator has ever seen (create/delete
     * cycles with fresh names included). Acceptable footprint for a local
     * emulator workload.
     */
    private final ConcurrentHashMap<String, Object> concurrencyOpLocks = new ConcurrentHashMap<>();

    /**
     * Package-private constructor for testing without CDI. Config defaults
     * (timeout=3, memory=128) apply. A real {@link LambdaConcurrencyLimiter}
     * with AWS-default limits is wired so concurrency operations exercise
     * the same validation and bookkeeping as production rather than
     * silently no-op'ing past null checks.
     */
    LambdaService(LambdaFunctionStore functionStore,
                  WarmPool warmPool,
                  CodeStore codeStore,
                  ZipExtractor zipExtractor,
                  RegionResolver regionResolver) {
        this.functionStore = functionStore;
        this.executorService = null;
        this.concurrencyLimiter = new LambdaConcurrencyLimiter();
        this.warmPool = warmPool;
        this.codeStore = codeStore;
        this.zipExtractor = zipExtractor;
        this.config = null;
        this.regionResolver = regionResolver;
        this.esmStore = null;
        this.aliasStore = null;
        this.s3Service = null;
        this.sqsService = null;
        this.poller = null;
        this.kinesisPoller = null;
        this.dynamodbStreamsPoller = null;
    }

    @Inject
    public LambdaService(LambdaFunctionStore functionStore,
                          LambdaExecutorService executorService,
                          LambdaConcurrencyLimiter concurrencyLimiter,
                          WarmPool warmPool,
                          CodeStore codeStore,
                          ZipExtractor zipExtractor,
                          EmulatorConfig config,
                          RegionResolver regionResolver,
                          EsmStore esmStore,
                          LambdaAliasStore aliasStore,
                          S3Service s3Service,
                          SqsService sqsService,
                          SqsEventSourcePoller poller,
                          KinesisEventSourcePoller kinesisPoller,
                          DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller) {
        this.functionStore = functionStore;
        this.executorService = executorService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.warmPool = warmPool;
        this.codeStore = codeStore;
        this.zipExtractor = zipExtractor;
        this.config = config;
        this.regionResolver = regionResolver;
        this.esmStore = esmStore;
        this.aliasStore = aliasStore;
        this.s3Service = s3Service;
        this.sqsService = sqsService;
        this.poller = poller;
        this.kinesisPoller = kinesisPoller;
        this.dynamodbStreamsPoller = dynamodbStreamsPoller;
    }

    /** Package-private accessor for tests that want to assert limiter state directly. */
    LambdaConcurrencyLimiter concurrencyLimiter() {
        return concurrencyLimiter;
    }

    /**
     * Rehydrates reserved concurrency into the limiter from persisted function state.
     * Without this, restarts leave {@code totalReserved()=0} and allow validatePut /
     * unreserved-pool sizing to drift until each function is re-Put.
     */
    @PostConstruct
    void rehydrateConcurrency() {
        if (concurrencyLimiter == null) {
            return;
        }
        int count = 0;
        for (LambdaFunction fn : functionStore.listAll()) {
            // Reserved concurrency is a function-level property; published
            // versions share the $LATEST record's value. Skip non-$LATEST
            // entries to avoid double-counting into totalReserved().
            if (!"$LATEST".equals(fn.getVersion())) {
                continue;
            }
            Integer reserved = fn.getReservedConcurrentExecutions();
            if (reserved != null) {
                concurrencyLimiter.setReserved(fn.getFunctionArn(), reserved);
                count++;
            }
        }
        if (count > 0) {
            LOG.infov("Restored reserved concurrency for {0} function(s)", count);
        }
    }

    public LambdaFunction createFunction(String region, Map<String, Object> request) {
        String functionName = (String) request.get("FunctionName");
        String role = (String) request.get("Role");
        String handler = (String) request.get("Handler");
        String runtime = (String) request.get("Runtime");
        String packageType = request.getOrDefault("PackageType", "Zip").toString();
        String description = (String) request.get("Description");
        int timeout = toInt(request.get("Timeout"), config != null ? config.services().lambda().defaultTimeoutSeconds() : 3);
        int memorySize = toInt(request.get("MemorySize"), config != null ? config.services().lambda().defaultMemoryMb() : 128);

        if (functionName == null || functionName.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "FunctionName is required", 400);
        }
        // Accept bare name, partial ARN, or full ARN. Normalize to the short
        // name so duplicate detection works regardless of which form the
        // caller supplies across successive calls.
        functionName = canonicalFunctionName(region, functionName);
        if (role == null || role.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Role is required", 400);
        }
        if ("Zip".equals(packageType) && (handler == null || handler.isBlank())) {
            throw new AwsException("InvalidParameterValueException", "Handler is required", 400);
        }
        if ("Zip".equals(packageType) && (runtime == null || runtime.isBlank())) {
            throw new AwsException("InvalidParameterValueException", "Runtime is required for Zip package type", 400);
        }

        if (functionStore.get(region, functionName).isPresent()) {
            throw new AwsException("ResourceConflictException",
                    "Function already exist: " + functionName, 409);
        }

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(functionName);
        fn.setFunctionArn(regionResolver.buildArn("lambda", region, "function:" + functionName));
        fn.setRuntime(runtime);
        fn.setRole(role);
        fn.setHandler(handler);
        fn.setDescription(description);
        fn.setTimeout(timeout);
        fn.setMemorySize(memorySize);
        fn.setPackageType(packageType);
        fn.setState("Active");
        fn.setLastModified(System.currentTimeMillis());
        fn.setRevisionId(UUID.randomUUID().toString());

        // Handle environment variables
        @SuppressWarnings("unchecked")
        Map<String, Object> envBlock = (Map<String, Object>) request.get("Environment");
        if (envBlock != null) {
            @SuppressWarnings("unchecked")
            Map<String, String> vars = (Map<String, String>) envBlock.get("Variables");
            if (vars != null) fn.setEnvironment(vars);
        }

        // Handle tags
        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) request.get("Tags");
        if (tags != null) fn.setTags(tags);

        // Architectures
        @SuppressWarnings("unchecked")
        List<String> architectures = request.get("Architectures") instanceof List
                ? (List<String>) request.get("Architectures") : null;
        if (architectures != null && !architectures.isEmpty()) {
            fn.setArchitectures(new ArrayList<>(architectures));
        }

        // EphemeralStorage
        if (request.get("EphemeralStorage") instanceof Map<?, ?> es) {
            fn.setEphemeralStorageSize(toInt(es.get("Size"), 512));
        }

        // TracingConfig
        if (request.get("TracingConfig") instanceof Map<?, ?> tc) {
            Object mode = tc.get("Mode");
            fn.setTracingMode(mode != null ? mode.toString() : "PassThrough");
        }

        // DeadLetterConfig
        if (request.get("DeadLetterConfig") instanceof Map<?, ?> dlq) {
            fn.setDeadLetterTargetArn((String) dlq.get("TargetArn"));
        }

        // Layers
        @SuppressWarnings("unchecked")
        List<String> layers = request.get("Layers") instanceof List
                ? (List<String>) request.get("Layers") : null;
        if (layers != null) {
            fn.setLayers(new ArrayList<>(layers));
        }

        if (request.containsKey("KMSKeyArn")) {
            fn.setKmsKeyArn((String) request.get("KMSKeyArn"));
        }

        if (request.get("VpcConfig") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vpc = (Map<String, Object>) request.get("VpcConfig");
            fn.setVpcConfig(vpc);
        }

        // Handle code deployment
        @SuppressWarnings("unchecked")
        Map<String, Object> code = (Map<String, Object>) request.get("Code");
        if (code != null) {
            String imageUri = (String) code.get("ImageUri");
            if (imageUri != null) {
                fn.setImageUri(imageUri);
            }
            String zipFileBase64 = (String) code.get("ZipFile");
            if (zipFileBase64 != null) {
                fn.setS3Bucket(null);
                fn.setS3Key(null);
                extractZipCode(fn, zipFileBase64);
            }
            String s3Bucket = (String) code.get("S3Bucket");
            String s3Key = (String) code.get("S3Key");
            if (s3Bucket != null && s3Key != null) {
                extractZipCodeFromS3(fn, s3Bucket, s3Key);
            }
        }

        functionStore.save(region, fn);
        LOG.infov("Created Lambda function: {0} in region {1}", functionName, region);
        return fn;
    }

    public LambdaFunction getFunction(String region, String functionName) {
        String canonical = canonicalFunctionName(region, functionName);
        return functionStore.get(region, canonical)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Function not found: " + functionName, 404));
    }

    /**
     * Resolves a {@code FunctionName} path parameter (bare name, partial ARN,
     * or full ARN, with optional {@code :qualifier}) to its canonical short
     * name, enforcing a region match when the input is a full ARN.
     */
    String canonicalFunctionName(String region, String functionName) {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(functionName);
        enforceRegion(region, ref);
        return ref.name();
    }

    /**
     * Resolves a {@code FunctionName} path parameter and reconciles any
     * embedded qualifier with an explicit {@code ?Qualifier=} query-string
     * value, enforcing region match when the input is a full ARN.
     */
    LambdaArnUtils.ResolvedFunctionRef resolveWithRegion(String region, String functionName, String queryQualifier) {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier(functionName, queryQualifier);
        enforceRegion(region, ref);
        return ref;
    }

    private void enforceRegion(String region, LambdaArnUtils.ResolvedFunctionRef ref) {
        if (ref.region() != null && !ref.region().equals(region)) {
            throw new AwsException("InvalidParameterValueException",
                    "Region '" + ref.region() + "' in ARN does not match request region '" + region + "'", 400);
        }
    }

    public List<LambdaFunction> listFunctions(String region) {
        return functionStore.list(region);
    }

    public LambdaFunction updateFunctionCode(String region, String functionName, Map<String, Object> request) {
        LambdaFunction fn = getFunction(region, functionName);
        functionName = fn.getFunctionName();

        String zipFileBase64 = (String) request.get("ZipFile");
        String imageUri = (String) request.get("ImageUri");
        String s3Bucket = (String) request.get("S3Bucket");
        String s3Key = (String) request.get("S3Key");

        if (zipFileBase64 != null) {
            fn.setS3Bucket(null);
            fn.setS3Key(null);
            extractZipCode(fn, zipFileBase64);
        }
        if (imageUri != null) {
            fn.setImageUri(imageUri);
        }
        if (s3Bucket != null && s3Key != null) {
            extractZipCodeFromS3(fn, s3Bucket, s3Key);
        }

        fn.setLastModified(System.currentTimeMillis());
        fn.setRevisionId(UUID.randomUUID().toString());

        // Drain warm containers — they have stale code mounted
        warmPool.drainFunction(functionName);

        functionStore.save(region, fn);
        LOG.infov("Updated code for function: {0}", functionName);
        return fn;
    }

    public LambdaFunction updateFunctionConfiguration(String region, String functionName, Map<String, Object> request) {
        LambdaFunction fn = getFunction(region, functionName);

        if (request.containsKey("Description")) {
            fn.setDescription((String) request.get("Description"));
        }
        if (request.containsKey("Handler")) {
            fn.setHandler((String) request.get("Handler"));
        }
        if (request.containsKey("MemorySize")) {
            fn.setMemorySize(((Number) request.get("MemorySize")).intValue());
        }
        if (request.containsKey("Role")) {
            fn.setRole((String) request.get("Role"));
        }
        if (request.containsKey("Runtime")) {
            fn.setRuntime((String) request.get("Runtime"));
        }
        if (request.containsKey("Timeout")) {
            fn.setTimeout(((Number) request.get("Timeout")).intValue());
        }
        if (request.containsKey("Environment")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> envBlock = (Map<String, Object>) request.get("Environment");
            if (envBlock != null && envBlock.containsKey("Variables")) {
                @SuppressWarnings("unchecked")
                Map<String, String> vars = (Map<String, String>) envBlock.get("Variables");
                fn.setEnvironment(vars != null ? vars : new java.util.HashMap<>());
            }
        }

        // RevisionId optimistic locking
        if (request.containsKey("RevisionId")) {
            String incomingRevision = (String) request.get("RevisionId");
            if (incomingRevision != null && !incomingRevision.equals(fn.getRevisionId())) {
                throw new AwsException("PreconditionFailedException",
                        "The Revision Id provided does not match the latest Revision Id. "
                        + "Call the GetFunction or the GetFunctionConfiguration API to retrieve "
                        + "the latest Revision Id for your resource.", 412);
            }
        }

        if (request.containsKey("Architectures")) {
            @SuppressWarnings("unchecked")
            List<String> archs = request.get("Architectures") instanceof List
                    ? (List<String>) request.get("Architectures") : null;
            if (archs != null && !archs.isEmpty()) {
                fn.setArchitectures(new ArrayList<>(archs));
            }
        }

        if (request.containsKey("EphemeralStorage")) {
            if (request.get("EphemeralStorage") instanceof Map<?, ?> es) {
                fn.setEphemeralStorageSize(toInt(es.get("Size"), 512));
            }
        }

        if (request.containsKey("TracingConfig")) {
            if (request.get("TracingConfig") instanceof Map<?, ?> tc) {
                Object mode = tc.get("Mode");
                fn.setTracingMode(mode != null ? mode.toString() : "PassThrough");
            }
        }

        if (request.containsKey("DeadLetterConfig")) {
            if (request.get("DeadLetterConfig") instanceof Map<?, ?> dlq) {
                fn.setDeadLetterTargetArn((String) dlq.get("TargetArn"));
            }
        }

        if (request.containsKey("Layers")) {
            @SuppressWarnings("unchecked")
            List<String> layerList = request.get("Layers") instanceof List
                    ? (List<String>) request.get("Layers") : null;
            fn.setLayers(layerList != null ? new ArrayList<>(layerList) : new ArrayList<>());
        }

        if (request.containsKey("KMSKeyArn")) {
            fn.setKmsKeyArn((String) request.get("KMSKeyArn"));
        }

        if (request.containsKey("VpcConfig")) {
            if (request.get("VpcConfig") instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> vpc = (Map<String, Object>) request.get("VpcConfig");
                fn.setVpcConfig(vpc);
            }
        }

        fn.setLastModified(System.currentTimeMillis());
        fn.setRevisionId(UUID.randomUUID().toString());

        // Drain warm containers so the next invocation picks up the new configuration
        warmPool.drainFunction(functionName);

        functionStore.save(region, fn);
        LOG.infov("Updated configuration for function: {0}", functionName);
        return fn;
    }

    public void deleteFunction(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName); // throws 404 if not found
        functionName = fn.getFunctionName();
        String arn = fn.getFunctionArn();
        warmPool.drainFunction(functionName);
        // Take the same per-function lock used by Put/DeleteFunctionConcurrency
        // so a concurrent concurrency mutation cannot interleave with the
        // limiter reset and store delete and leave the two views out of sync.
        // The lock entry itself stays in the map after the delete: removing it
        // could race with another thread already synchronized on the same
        // object, letting a follow-up request allocate a fresh lock and run
        // in parallel — the very serialization this map exists to prevent.
        synchronized (lockForConcurrencyOp(arn)) {
            if (concurrencyLimiter != null) {
                concurrencyLimiter.reset(arn);
            }
            codeStore.delete(functionName);
            functionStore.delete(region, functionName);
        }
        LOG.infov("Deleted Lambda function: {0}", functionName);
    }

    public InvokeResult invoke(String region, String functionName, byte[] payload, InvocationType type) {
        LambdaFunction fn = getFunction(region, functionName);
        return executorService.invoke(fn, payload, type);
    }

    // ──────────────────────────── Event Source Mapping (SQS) ────────────────────────────

    public EventSourceMapping createEventSourceMapping(String region, Map<String, Object> request) {
        String functionName = (String) request.get("FunctionName");
        String eventSourceArn = (String) request.get("EventSourceArn");

        if (functionName == null || functionName.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "FunctionName is required", 400);
        }
        if (eventSourceArn == null || eventSourceArn.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "EventSourceArn is required", 400);
        }
        if (!eventSourceArn.contains(":sqs:") && !eventSourceArn.contains(":kinesis:")
                && !eventSourceArn.contains(":dynamodb:")) {
            throw new AwsException("InvalidParameterValueException",
                    "Only SQS, Kinesis, and DynamoDB Streams event sources are supported.", 400);
        }

        // Resolve function — supports bare name, partial ARN, or full ARN
        LambdaArnUtils.ResolvedFunctionRef fnRef = LambdaArnUtils.resolve(functionName);
        String resolvedName = fnRef.name();

        // Extract region from the event source ARN (parts[3] for all supported ARN formats)
        String resolvedRegion;
        if (eventSourceArn.contains(":sqs:")) {
            resolvedRegion = SqsEventSourcePoller.regionFromArn(eventSourceArn);
        } else {
            // arn:aws:kinesis:region:... or arn:aws:dynamodb:region:...
            String[] parts = eventSourceArn.split(":");
            resolvedRegion = parts.length > 3 ? parts[3] : region;
        }

        // If the caller supplied a full function ARN, its region must agree
        // with the region derived from the event source ARN. Otherwise we'd
        // silently bind a different-region function of the same name.
        if (fnRef.region() != null && !fnRef.region().equals(resolvedRegion)) {
            throw new AwsException("InvalidParameterValueException",
                    "Function ARN region '" + fnRef.region() + "' does not match event source region '" + resolvedRegion + "'", 400);
        }

        LambdaFunction fn = getFunction(resolvedRegion, resolvedName);

        int batchSize = toInt(request.get("BatchSize"), 10);
        boolean enabled = !Boolean.FALSE.equals(request.get("Enabled"));

        @SuppressWarnings("unchecked")
        List<String> functionResponseTypes = request.get("FunctionResponseTypes") instanceof List
                ? (List<String>) request.get("FunctionResponseTypes")
                : new ArrayList<>();

        ScalingConfig scalingConfig = parseScalingConfig(request, eventSourceArn);

        String queueUrl = eventSourceArn.contains(":sqs:") ? AwsArnUtils.arnToQueueUrl(eventSourceArn, config.effectiveBaseUrl()) : null;

        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid(UUID.randomUUID().toString());
        esm.setFunctionArn(fn.getFunctionArn());
        esm.setFunctionName(resolvedName);
        esm.setEventSourceArn(eventSourceArn);
        esm.setQueueUrl(queueUrl);
        esm.setRegion(resolvedRegion);
        esm.setBatchSize(batchSize);
        esm.setEnabled(enabled);
        esm.setState(enabled ? "Enabled" : "Disabled");
        esm.setScalingConfig(scalingConfig);
        esm.setFunctionResponseTypes(functionResponseTypes);
        esm.setLastModified(System.currentTimeMillis());

        esmStore.save(esm);
        if (enabled) {
            startPollingHelper(esm);
        }
        LOG.infov("Created ESM {0}: {1} → {2}", esm.getUuid(), eventSourceArn, resolvedName);
        return esm;
    }

    /**
     * Parses {@code ScalingConfig} out of a create/update request and applies
     * AWS-level validation: {@code MaximumConcurrency} must be in [2, 1000]
     * and is only valid on SQS event sources. Returns {@code null} when no
     * config was supplied or when the supplied config has no cap (AWS treats
     * an empty ScalingConfig as "clear the cap").
     */
    private ScalingConfig parseScalingConfig(Map<String, Object> request, String eventSourceArn) {
        Object raw = request.get("ScalingConfig");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw new AwsException("InvalidParameterValueException",
                    "ScalingConfig must be a JSON object", 400);
        }
        boolean isSqs = eventSourceArn != null && eventSourceArn.contains(":sqs:");
        Map<?, ?> map = (Map<?, ?>) raw;
        Object mc = map.get("MaximumConcurrency");
        if (mc == null) {
            if (!isSqs) {
                throw new AwsException("InvalidParameterValueException",
                        "ScalingConfig is only supported for Amazon SQS event source mappings", 400);
            }
            return null;
        }
        if (!(mc instanceof Number)) {
            throw new AwsException("InvalidParameterValueException",
                    "ScalingConfig.MaximumConcurrency must be a numeric value", 400);
        }
        double d = ((Number) mc).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
            throw new AwsException("InvalidParameterValueException",
                    "ScalingConfig.MaximumConcurrency must be an integer", 400);
        }
        long longValue = ((Number) mc).longValue();
        if (longValue < 2 || longValue > 1000) {
            throw new AwsException("InvalidParameterValueException",
                    "ScalingConfig.MaximumConcurrency must be between 2 and 1000 (got " + longValue + ")", 400);
        }
        if (!isSqs) {
            throw new AwsException("InvalidParameterValueException",
                    "ScalingConfig is only supported for Amazon SQS event source mappings", 400);
        }
        return new ScalingConfig((int) longValue);
    }

    private void startPollingHelper(EventSourceMapping esm) {
        if (esm.getEventSourceArn().contains(":sqs:")) {
            poller.startPolling(esm);
        } else if (esm.getEventSourceArn().contains(":kinesis:")) {
            kinesisPoller.startPolling(esm);
        } else if (esm.getEventSourceArn().contains(":dynamodb:")) {
            dynamodbStreamsPoller.startPolling(esm);
        }
    }

    private void stopPollingHelper(EventSourceMapping esm) {
        if (esm.getEventSourceArn().contains(":sqs:")) {
            poller.stopPolling(esm.getUuid());
        } else if (esm.getEventSourceArn().contains(":kinesis:")) {
            kinesisPoller.stopPolling(esm.getUuid());
        } else if (esm.getEventSourceArn().contains(":dynamodb:")) {
            dynamodbStreamsPoller.stopPolling(esm.getUuid());
        }
    }

    public EventSourceMapping getEventSourceMapping(String uuid) {
        return esmStore.get(uuid)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "EventSourceMapping not found: " + uuid, 404));
    }

    public List<EventSourceMapping> listEventSourceMappings(String functionArn) {
        if (functionArn != null && !functionArn.isBlank()) {
            // Accept bare name, partial ARN, or full ARN. The store matches
            // entries by their canonical short name, so normalize first.
            String shortName = LambdaArnUtils.resolve(functionArn).name();
            return esmStore.listByFunction(shortName);
        }
        return esmStore.list();
    }

    public EventSourceMapping updateEventSourceMapping(String uuid, Map<String, Object> request) {
        EventSourceMapping esm = getEventSourceMapping(uuid);

        boolean wasEnabled = esm.isEnabled();

        if (request.containsKey("BatchSize")) {
            esm.setBatchSize(toInt(request.get("BatchSize"), esm.getBatchSize()));
        }
        if (request.containsKey("Enabled")) {
            boolean nowEnabled = !Boolean.FALSE.equals(request.get("Enabled"));
            esm.setEnabled(nowEnabled);
            esm.setState(nowEnabled ? "Enabled" : "Disabled");
        }
        if (request.containsKey("ScalingConfig")) {
            // AWS: passing ScalingConfig resets it. An empty object or one
            // with MaximumConcurrency=null clears the cap.
            esm.setScalingConfig(parseScalingConfig(request, esm.getEventSourceArn()));
        }

        esm.setLastModified(System.currentTimeMillis());
        esmStore.save(esm);

        // Start/stop polling if enabled state changed
        if (!wasEnabled && esm.isEnabled()) {
            startPollingHelper(esm);
        } else if (wasEnabled && !esm.isEnabled()) {
            stopPollingHelper(esm);
        }

        LOG.infov("Updated ESM {0}: batchSize={1} enabled={2}", uuid, esm.getBatchSize(), esm.isEnabled());
        return esm;
    }

    public void deleteEventSourceMapping(String uuid) {
        EventSourceMapping esm = getEventSourceMapping(uuid); // throws 404 if not found
        stopPollingHelper(esm);
        esmStore.delete(uuid);
        LOG.infov("Deleted ESM {0}", uuid);
    }

    // ──────────────────────────── Versions ────────────────────────────

    public LambdaFunction publishVersion(String region, String functionName, String description) {
        LambdaFunction fn = getFunction(region, functionName);
        functionName = fn.getFunctionName();
        int version = versionCounters.merge(region + "::" + functionName, 1, Integer::sum);
        LambdaFunction snapshot = new LambdaFunction();
        snapshot.setFunctionName(fn.getFunctionName());
        snapshot.setVersion(String.valueOf(version));
        snapshot.setFunctionArn(fn.getFunctionArn().replace(":$LATEST", "") + ":" + version);
        snapshot.setRuntime(fn.getRuntime());
        snapshot.setRole(fn.getRole());
        snapshot.setHandler(fn.getHandler());
        snapshot.setDescription(description != null ? description : fn.getDescription());
        snapshot.setTimeout(fn.getTimeout());
        snapshot.setMemorySize(fn.getMemorySize());
        snapshot.setPackageType(fn.getPackageType());
        snapshot.setState(fn.getState());
        snapshot.setCodeSizeBytes(fn.getCodeSizeBytes());
        snapshot.setEnvironment(fn.getEnvironment());
        snapshot.setLastModified(System.currentTimeMillis());
        snapshot.setRevisionId(UUID.randomUUID().toString());
        
        functionStore.save(region, snapshot);
        LOG.infov("Published version {0} for function {1}", version, functionName);
        return snapshot;
    }

    public List<LambdaFunction> listVersionsByFunction(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName); // verify function exists
        return functionStore.listVersions(region, fn.getFunctionName());
    }

    // ──────────────────────────── Aliases ────────────────────────────

    public LambdaAlias createAlias(String region, String functionName, String aliasName,
                                   String functionVersion, String description) {
        LambdaFunction fn = getFunction(region, functionName);
        functionName = fn.getFunctionName();
        if (aliasStore != null && aliasStore.get(region, functionName, aliasName).isPresent()) {
            throw new AwsException("ResourceConflictException", "Alias already exists: " + aliasName, 409);
        }
        LambdaAlias alias = new LambdaAlias();
        alias.setName(aliasName);
        alias.setFunctionName(functionName);
        alias.setFunctionVersion(functionVersion != null ? functionVersion : "$LATEST");
        alias.setDescription(description);
        alias.setAliasArn(fn.getFunctionArn() + ":" + aliasName);
        long now = System.currentTimeMillis() / 1000L;
        alias.setCreatedDate(now);
        alias.setLastModifiedDate(now);
        alias.setRevisionId(UUID.randomUUID().toString());
        if (aliasStore != null) aliasStore.save(region, alias);
        LOG.infov("Created alias {0} for function {1} in {2}", aliasName, functionName, region);
        return alias;
    }

    public LambdaAlias getAlias(String region, String functionName, String aliasName) {
        if (aliasStore == null) {
            throw new AwsException("ResourceNotFoundException", "Alias not found: " + aliasName, 404);
        }
        String canonical = canonicalFunctionName(region, functionName);
        return aliasStore.get(region, canonical, aliasName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Alias not found: " + aliasName, 404));
    }

    public List<LambdaAlias> listAliases(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName); // verify function exists
        if (aliasStore == null) return List.of();
        return aliasStore.list(region, fn.getFunctionName());
    }

    public LambdaAlias updateAlias(String region, String functionName, String aliasName,
                                   String functionVersion, String description) {
        LambdaAlias alias = getAlias(region, functionName, aliasName);
        if (functionVersion != null) alias.setFunctionVersion(functionVersion);
        if (description != null) alias.setDescription(description);
        alias.setLastModifiedDate(System.currentTimeMillis() / 1000L);
        alias.setRevisionId(UUID.randomUUID().toString());
        if (aliasStore != null) aliasStore.save(region, alias);
        return alias;
    }

    public void deleteAlias(String region, String functionName, String aliasName) {
        String canonical = canonicalFunctionName(region, functionName);
        getAlias(region, canonical, aliasName); // verify it exists
        if (aliasStore != null) aliasStore.delete(region, canonical, aliasName);
        LOG.infov("Deleted alias {0} for function {1}", aliasName, canonical);
    }

    // ──────────────────────────── Function URL Config ────────────────────────────

    public LambdaUrlConfig createFunctionUrlConfig(String region, String functionName, String qualifier, Map<String, Object> request) {
        LambdaArnUtils.ResolvedFunctionRef ref = resolveWithRegion(region, functionName, qualifier);
        functionName = ref.name();
        qualifier = ref.qualifier();
        LambdaUrlConfig urlConfig = new LambdaUrlConfig();
        urlConfig.setAuthType((String) request.getOrDefault("AuthType", "NONE"));
        if (request.containsKey("InvokeMode")) {
            urlConfig.setInvokeMode((String) request.get("InvokeMode"));
        }

        String urlId = UUID.nameUUIDFromBytes((region + functionName + (qualifier != null ? qualifier : "")).getBytes()).toString().replace("-", "").substring(0, 32);
        String baseHost = config.effectiveBaseUrl().replaceFirst("https?://", "");
        String url = String.format("http://%s.lambda-url.%s.%s/", urlId, region, baseHost);
        urlConfig.setFunctionUrl(url);

        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC));
        urlConfig.setCreationTime(now);
        urlConfig.setLastModifiedTime(now);

        // Handle CORS
        @SuppressWarnings("unchecked")
        Map<String, Object> corsMap = (Map<String, Object>) request.get("Cors");
        if (corsMap != null) {
            LambdaUrlConfig.Cors cors = new LambdaUrlConfig.Cors();
            cors.setAllowCredentials(Boolean.TRUE.equals(corsMap.get("AllowCredentials")));
            cors.setAllowHeaders(toStringArray(corsMap.get("AllowHeaders")));
            cors.setAllowMethods(toStringArray(corsMap.get("AllowMethods")));
            cors.setAllowOrigins(toStringArray(corsMap.get("AllowOrigins")));
            cors.setExposeHeaders(toStringArray(corsMap.get("ExposeHeaders")));
            cors.setMaxAge(toInt(corsMap.get("MaxAge"), 0));
            urlConfig.setCors(cors);
        }

        if (qualifier != null && !qualifier.equals("$LATEST")) {
            LambdaAlias alias = getAlias(region, functionName, qualifier);
            if (alias.getUrlConfig() != null) {
                throw new AwsException("ResourceConflictException", "Function URL config already exists for alias: " + qualifier, 409);
            }
            urlConfig.setFunctionArn(alias.getAliasArn());
            alias.setUrlConfig(urlConfig);
            if (aliasStore != null) aliasStore.save(region, alias);
        } else {
            LambdaFunction fn = getFunction(region, functionName);
            if (fn.getUrlConfig() != null) {
                throw new AwsException("ResourceConflictException", "Function URL config already exists for function: " + functionName, 409);
            }
            urlConfig.setFunctionArn(fn.getFunctionArn());
            fn.setUrlConfig(urlConfig);
            functionStore.save(region, fn);
        }

        LOG.infov("Created Function URL for {0} (qualifier: {1}): {2}", functionName, qualifier, url);
        return urlConfig;
    }

    public LambdaUrlConfig getFunctionUrlConfig(String region, String functionName, String qualifier) {
        LambdaArnUtils.ResolvedFunctionRef ref = resolveWithRegion(region, functionName, qualifier);
        functionName = ref.name();
        qualifier = ref.qualifier();
        LambdaUrlConfig urlConfig;
        if (qualifier != null && !qualifier.equals("$LATEST")) {
            urlConfig = getAlias(region, functionName, qualifier).getUrlConfig();
        } else {
            urlConfig = getFunction(region, functionName).getUrlConfig();
        }

        if (urlConfig == null) {
            throw new AwsException("ResourceNotFoundException", "Function URL config not found", 404);
        }
        return urlConfig;
    }

    public LambdaUrlConfig updateFunctionUrlConfig(String region, String functionName, String qualifier, Map<String, Object> request) {
        LambdaArnUtils.ResolvedFunctionRef ref = resolveWithRegion(region, functionName, qualifier);
        functionName = ref.name();
        qualifier = ref.qualifier();
        LambdaUrlConfig urlConfig = getFunctionUrlConfig(region, functionName, qualifier);
        
        if (request.containsKey("AuthType")) {
            urlConfig.setAuthType((String) request.get("AuthType"));
        }
        if (request.containsKey("InvokeMode")) {
            urlConfig.setInvokeMode((String) request.get("InvokeMode"));
        }

        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC));
        urlConfig.setLastModifiedTime(now);

        // Update CORS
        if (request.containsKey("Cors")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> corsMap = (Map<String, Object>) request.get("Cors");
            if (corsMap != null) {
                LambdaUrlConfig.Cors cors = urlConfig.getCors();
                if (cors == null) cors = new LambdaUrlConfig.Cors();
                cors.setAllowCredentials(Boolean.TRUE.equals(corsMap.get("AllowCredentials")));
                cors.setAllowHeaders(toStringArray(corsMap.get("AllowHeaders")));
                cors.setAllowMethods(toStringArray(corsMap.get("AllowMethods")));
                cors.setAllowOrigins(toStringArray(corsMap.get("AllowOrigins")));
                cors.setExposeHeaders(toStringArray(corsMap.get("ExposeHeaders")));
                cors.setMaxAge(toInt(corsMap.get("MaxAge"), cors.getMaxAge()));
                urlConfig.setCors(cors);
            } else {
                urlConfig.setCors(null);
            }
        }

        if (qualifier != null && !qualifier.equals("$LATEST")) {
            LambdaAlias alias = getAlias(region, functionName, qualifier);
            aliasStore.save(region, alias);
        } else {
            LambdaFunction fn = getFunction(region, functionName);
            functionStore.save(region, fn);
        }

        return urlConfig;
    }

    public void deleteFunctionUrlConfig(String region, String functionName, String qualifier) {
        LambdaArnUtils.ResolvedFunctionRef ref = resolveWithRegion(region, functionName, qualifier);
        functionName = ref.name();
        qualifier = ref.qualifier();
        if (qualifier != null && !qualifier.equals("$LATEST")) {
            LambdaAlias alias = getAlias(region, functionName, qualifier);
            if (alias.getUrlConfig() == null) {
                throw new AwsException("ResourceNotFoundException", "Function URL config not found", 404);
            }
            alias.setUrlConfig(null);
            aliasStore.save(region, alias);
        } else {
            LambdaFunction fn = getFunction(region, functionName);
            if (fn.getUrlConfig() == null) {
                throw new AwsException("ResourceNotFoundException", "Function URL config not found", 404);
            }
            fn.setUrlConfig(null);
            functionStore.save(region, fn);
        }
    }

    public LambdaFunction putFunctionConcurrency(String region, String functionName, Integer reservedConcurrentExecutions) {
        if (reservedConcurrentExecutions == null || reservedConcurrentExecutions < 0) {
            throw new AwsException("InvalidParameterValueException",
                    "ReservedConcurrentExecutions must be a non-negative integer", 400);
        }
        LambdaFunction fn = getFunction(region, functionName);
        String arn = fn.getFunctionArn();
        // Serialize limiter update + store save for this function so that two
        // concurrent Puts cannot leave the limiter and persisted state out of
        // sync, regardless of which call acquires the reservedLock first.
        synchronized (lockForConcurrencyOp(arn)) {
            Integer previousReserved = null;
            boolean limiterUpdated = false;
            if (concurrencyLimiter != null) {
                previousReserved = concurrencyLimiter.validateAndSetReserved(
                        arn, reservedConcurrentExecutions);
                limiterUpdated = true;
            }
            fn.setReservedConcurrentExecutions(reservedConcurrentExecutions);
            try {
                functionStore.save(region, fn);
            } catch (RuntimeException e) {
                if (limiterUpdated) {
                    concurrencyLimiter.rollbackReservedIfExpected(
                            arn, reservedConcurrentExecutions, previousReserved);
                }
                throw e;
            }
        }
        return fn;
    }

    public Integer getFunctionConcurrency(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName);
        return fn.getReservedConcurrentExecutions();
    }

    public void deleteFunctionConcurrency(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName);
        String arn = fn.getFunctionArn();
        synchronized (lockForConcurrencyOp(arn)) {
            Integer previousReserved = null;
            boolean limiterCleared = false;
            if (concurrencyLimiter != null) {
                previousReserved = concurrencyLimiter.clearReserved(arn);
                limiterCleared = true;
            }
            fn.setReservedConcurrentExecutions(null);
            try {
                functionStore.save(region, fn);
            } catch (RuntimeException e) {
                if (limiterCleared && previousReserved != null) {
                    concurrencyLimiter.rollbackReservedIfExpected(
                            arn, null, previousReserved);
                }
                throw e;
            }
        }
    }

    private Object lockForConcurrencyOp(String functionArn) {
        return concurrencyOpLocks.computeIfAbsent(functionArn, k -> new Object());
    }

    public LambdaFunction getFunctionByUrlId(String urlId) {
        return functionStore.getByUrlId(urlId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Function not found for URL ID: " + urlId, 404));
    }

    public Object getTargetByUrlId(String urlId) {
        Optional<LambdaFunction> fn = functionStore.getByUrlId(urlId);
        if (fn.isPresent()) {
            return fn.get();
        }
        if (aliasStore != null) {
            Optional<LambdaAlias> alias = aliasStore.getByUrlId(urlId);
            if (alias.isPresent()) {
                return alias.get();
            }
        }
        throw new AwsException("ResourceNotFoundException", "No Lambda found for URL ID: " + urlId, 404);
    }

    private String[] toStringArray(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(Object::toString).toArray(String[]::new);
        }
        return null;
    }

    private void extractZipCode(LambdaFunction fn, String zipFileBase64) {
        byte[] zipBytes = Base64.getDecoder().decode(zipFileBase64);
        Path codePath = codeStore.getCodePath(fn.getFunctionName());
        try {
            zipExtractor.extractTo(zipBytes, codePath);
            fn.setCodeLocalPath(codePath.toAbsolutePath().normalize().toString());
            fn.setCodeSizeBytes(zipBytes.length);
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(zipBytes);
                fn.setCodeSha256(Base64.getEncoder().encodeToString(digest));
            } catch (java.security.NoSuchAlgorithmException ignored) {}

            // For file-based runtimes, verify handler file exists (skip Java and .NET which use different handler formats)
            if (fn.getRuntime() != null && !fn.getRuntime().startsWith("java") && !fn.getRuntime().startsWith("dotnet")) {
                String handlerFile = resolveHandlerFilePath(fn);
                boolean found = Files.walk(codePath)
                        .filter(Files::isRegularFile)
                        .anyMatch(p -> {
                            String relative = codePath.relativize(p).toString();
                            String withoutExt = relative.contains(".")
                                    ? relative.substring(0, relative.lastIndexOf('.'))
                                    : relative;
                            return withoutExt.replace('\\', '/').equals(handlerFile);
                        });
                if (!found) {
                    throw new AwsException("InvalidParameterValueException",
                            "Handler file '" + handlerFile + "' not found in deployment package", 400);
                }
            }
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("InvalidParameterValueException",
                    "Failed to extract deployment package: " + e.getMessage(), 400);
        }
    }

    private void extractZipCodeFromS3(LambdaFunction fn, String s3Bucket, String s3Key) {
        if (s3Service == null) {
            throw new AwsException("ServiceUnavailableException", "S3 service not available", 503);
        }

        fn.setS3Bucket(s3Bucket);
        fn.setS3Key(s3Key);
        S3Object obj;
        try {
            obj = s3Service.getObject(s3Bucket, s3Key);
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException",
                    "Unable to fetch code from s3://" + s3Bucket + "/" + s3Key + ": " + e.getMessage(), 400);
        }
        extractZipCode(fn, Base64.getEncoder().encodeToString(obj.getData()));
    }

    private String resolveHandlerFilePath(LambdaFunction fn) {
        String handler = fn.getHandler();
        int lastDot = handler.lastIndexOf('.');
        String modulePath = lastDot >= 0 ? handler.substring(0, lastDot) : handler;
        if (fn.getRuntime().startsWith("python")) {
            return modulePath.replace('.', '/');
        }
        return modulePath;
    }

    // ──────────────────────────── Permissions (Policy) ────────────────────────────

    public Map<String, Object> addPermission(String region, String functionName, Map<String, Object> request) {
        LambdaFunction fn = getFunction(region, functionName);
        String statementId = (String) request.get("StatementId");
        if (statementId == null || statementId.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "StatementId is required", 400);
        }
        fn.getPolicies().stream()
                .filter(s -> statementId.equals(s.get("Sid")))
                .findFirst()
                .ifPresent(s -> {
                    throw new AwsException("ResourceConflictException",
                            "The statement id (" + statementId + ") already exists. Please try again with a new Statement Id.", 409);
                });

        String principal = (String) request.get("Principal");
        String action = (String) request.get("Action");
        String sourceArn = (String) request.get("SourceArn");
        String sourceAccount = (String) request.get("SourceAccount");

        Map<String, Object> statement = new java.util.LinkedHashMap<>();
        statement.put("Sid", statementId);
        statement.put("Effect", "Allow");
        if (principal != null && principal.contains(".")) {
            statement.put("Principal", Map.of("Service", principal));
        } else if (principal != null && principal.startsWith("arn:")) {
            statement.put("Principal", Map.of("AWS", principal));
        } else {
            statement.put("Principal", principal);
        }
        statement.put("Action", action);
        statement.put("Resource", fn.getFunctionArn());
        if (sourceArn != null) {
            statement.put("Condition", Map.of("ArnLike", Map.of("AWS:SourceArn", sourceArn)));
        } else if (sourceAccount != null) {
            statement.put("Condition", Map.of("StringEquals", Map.of("AWS:SourceAccount", sourceAccount)));
        }

        fn.getPolicies().add(statement);
        functionStore.save(region, fn);
        LOG.infov("Added permission {0} to function {1}", statementId, functionName);
        return statement;
    }

    public Map<String, Object> getPolicy(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName);
        if (fn.getPolicies().isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Function not found: " + functionName, 404);
        }
        Map<String, Object> policy = new java.util.LinkedHashMap<>();
        policy.put("Version", "2012-10-17");
        policy.put("Id", "default");
        policy.put("Statement", fn.getPolicies());
        return Map.of("policy", policy, "revisionId", fn.getRevisionId());
    }

    public void removePermission(String region, String functionName, String statementId) {
        LambdaFunction fn = getFunction(region, functionName);
        boolean removed = fn.getPolicies().removeIf(s -> statementId.equals(s.get("Sid")));
        if (!removed) {
            throw new AwsException("ResourceNotFoundException",
                    "Statement " + statementId + " not found in function " + functionName, 404);
        }
        functionStore.save(region, fn);
        LOG.infov("Removed permission {0} from function {1}", statementId, functionName);
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String functionArn) {
        TagTarget target = resolveTagTarget(functionArn);
        LambdaFunction fn = getFunction(target.region, target.name);
        return fn.getTags() != null ? fn.getTags() : Map.of();
    }

    public void tagResource(String functionArn, Map<String, String> tags) {
        TagTarget target = resolveTagTarget(functionArn);
        LambdaFunction fn = getFunction(target.region, target.name);
        if (fn.getTags() == null) fn.setTags(new java.util.HashMap<>());
        fn.getTags().putAll(tags);
        functionStore.save(target.region, fn);
    }

    public void untagResource(String functionArn, List<String> tagKeys) {
        TagTarget target = resolveTagTarget(functionArn);
        LambdaFunction fn = getFunction(target.region, target.name);
        if (fn.getTags() != null) {
            tagKeys.forEach(fn.getTags()::remove);
        }
        functionStore.save(target.region, fn);
    }

    private record TagTarget(String region, String name) {}

    /**
     * Resolves a tag-endpoint ARN to a (region, shortName) pair. The Lambda
     * tag APIs only accept an unqualified full function ARN; reject partial
     * ARNs, bare names, and qualified ARNs.
     */
    private TagTarget resolveTagTarget(String functionArn) {
        if (functionArn == null || functionArn.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Resource ARN is required", 400);
        }
        if (!functionArn.startsWith("arn:")) {
            throw new AwsException("InvalidParameterValueException",
                    "Resource ARN must be a full Lambda function ARN: " + functionArn, 400);
        }
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(functionArn);
        if (ref.qualifier() != null) {
            throw new AwsException("InvalidParameterValueException",
                    "Tag operations require an unqualified function ARN: " + functionArn, 400);
        }
        return new TagTarget(ref.region(), ref.name());
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Observes S3 object updates and triggers reactive sync for any Lambda
     * functions linked to the updated object.
     */
    public void onS3ObjectUpdated(@Observes S3ObjectUpdatedEvent event) {
        LOG.debugv("Observing S3 update: {0}/{1}", event.bucketName(), event.key());
        // For simplicity, we scan all functions in the default region
        // Most local dev setups use a single region
        String region = regionResolver.getDefaultRegion();
        List<LambdaFunction> functions = functionStore.list(region);
        for (LambdaFunction fn : functions) {
            if (event.bucketName().equals(fn.getS3Bucket()) && event.key().equals(fn.getS3Key())) {
                LOG.infov("Reactive S3 Sync: updating function {0} from s3://{1}/{2}",
                        fn.getFunctionName(), event.bucketName(), event.key());
                try {
                    S3Object obj = s3Service.getObject(event.bucketName(), event.key());
                    extractZipCode(fn, Base64.getEncoder().encodeToString(obj.getData()));
                    fn.setLastModified(Instant.now().toEpochMilli());
                    fn.setRevisionId(UUID.randomUUID().toString());
                    functionStore.save(region, fn);

                    // Push to warm workers
                    warmPool.pushCodeUpdate(fn);
                } catch (Exception e) {
                    LOG.warnv("Failed reactive sync for function {0}: {1}", fn.getFunctionName(), e.getMessage());
                }
            }
        }
    }
}
