package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    private final WarmPool warmPool;
    private final CodeStore codeStore;
    private final ZipExtractor zipExtractor;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EsmStore esmStore;
    private final LambdaAliasStore aliasStore;
    private final SqsService sqsService;
    private final SqsEventSourcePoller poller;
    private final KinesisEventSourcePoller kinesisPoller;
    private final DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller;
    private final ConcurrentHashMap<String, Integer> versionCounters = new ConcurrentHashMap<>();

    /** Package-private constructor for testing without CDI. Config defaults (timeout=3, memory=128) apply. */
    LambdaService(LambdaFunctionStore functionStore,
                  WarmPool warmPool,
                  CodeStore codeStore,
                  ZipExtractor zipExtractor,
                  RegionResolver regionResolver) {
        this.functionStore = functionStore;
        this.executorService = null;
        this.warmPool = warmPool;
        this.codeStore = codeStore;
        this.zipExtractor = zipExtractor;
        this.config = null;
        this.regionResolver = regionResolver;
        this.esmStore = null;
        this.aliasStore = null;
        this.sqsService = null;
        this.poller = null;
        this.kinesisPoller = null;
        this.dynamodbStreamsPoller = null;
    }

    @Inject
    public LambdaService(LambdaFunctionStore functionStore,
                          LambdaExecutorService executorService,
                          WarmPool warmPool,
                          CodeStore codeStore,
                          ZipExtractor zipExtractor,
                          EmulatorConfig config,
                          RegionResolver regionResolver,
                          EsmStore esmStore,
                          LambdaAliasStore aliasStore,
                          SqsService sqsService,
                          SqsEventSourcePoller poller,
                          KinesisEventSourcePoller kinesisPoller,
                          DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller) {
        this.functionStore = functionStore;
        this.executorService = executorService;
        this.warmPool = warmPool;
        this.codeStore = codeStore;
        this.zipExtractor = zipExtractor;
        this.config = config;
        this.regionResolver = regionResolver;
        this.esmStore = esmStore;
        this.aliasStore = aliasStore;
        this.sqsService = sqsService;
        this.poller = poller;
        this.kinesisPoller = kinesisPoller;
        this.dynamodbStreamsPoller = dynamodbStreamsPoller;
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
        if (role == null || role.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Role is required", 400);
        }
        if (handler == null || handler.isBlank()) {
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
                extractZipCode(fn, zipFileBase64);
            }
        }

        functionStore.save(region, fn);
        LOG.infov("Created Lambda function: {0} in region {1}", functionName, region);
        return fn;
    }

    public LambdaFunction getFunction(String region, String functionName) {
        return functionStore.get(region, functionName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Function not found: " + functionName, 404));
    }

    public List<LambdaFunction> listFunctions(String region) {
        return functionStore.list(region);
    }

    public LambdaFunction updateFunctionCode(String region, String functionName, Map<String, Object> request) {
        LambdaFunction fn = getFunction(region, functionName);

        String zipFileBase64 = (String) request.get("ZipFile");
        String imageUri = (String) request.get("ImageUri");

        if (zipFileBase64 != null) {
            extractZipCode(fn, zipFileBase64);
        }
        if (imageUri != null) {
            fn.setImageUri(imageUri);
        }

        fn.setLastModified(System.currentTimeMillis());
        fn.setRevisionId(UUID.randomUUID().toString());

        // Drain warm containers — they have stale code mounted
        warmPool.drainFunction(functionName);

        functionStore.save(region, fn);
        LOG.infov("Updated code for function: {0}", functionName);
        return fn;
    }

    public void deleteFunction(String region, String functionName) {
        getFunction(region, functionName); // throws 404 if not found
        warmPool.drainFunction(functionName);
        codeStore.delete(functionName);
        functionStore.delete(region, functionName);
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

        // Resolve function — supports both short name and ARN
        String resolvedName = functionName.contains(":") ?
                functionName.substring(functionName.lastIndexOf(':') + 1) : functionName;

        // Extract region from the event source ARN (parts[3] for all supported ARN formats)
        String resolvedRegion;
        if (eventSourceArn.contains(":sqs:")) {
            resolvedRegion = SqsEventSourcePoller.regionFromArn(eventSourceArn);
        } else {
            // arn:aws:kinesis:region:... or arn:aws:dynamodb:region:...
            String[] parts = eventSourceArn.split(":");
            resolvedRegion = parts.length > 3 ? parts[3] : region;
        }

        LambdaFunction fn = getFunction(resolvedRegion, resolvedName);

        int batchSize = toInt(request.get("BatchSize"), 10);
        boolean enabled = !Boolean.FALSE.equals(request.get("Enabled"));

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
        esm.setLastModified(System.currentTimeMillis());

        esmStore.save(esm);
        if (enabled) {
            startPollingHelper(esm);
        }
        LOG.infov("Created ESM {0}: {1} → {2}", esm.getUuid(), eventSourceArn, resolvedName);
        return esm;
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
            return esmStore.listByFunction(functionArn);
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
        getFunction(region, functionName); // verify function exists
        return functionStore.listVersions(region, functionName);
    }

    // ──────────────────────────── Aliases ────────────────────────────

    public LambdaAlias createAlias(String region, String functionName, String aliasName,
                                   String functionVersion, String description) {
        LambdaFunction fn = getFunction(region, functionName);
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
        return aliasStore.get(region, functionName, aliasName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Alias not found: " + aliasName, 404));
    }

    public List<LambdaAlias> listAliases(String region, String functionName) {
        getFunction(region, functionName); // verify function exists
        if (aliasStore == null) return List.of();
        return aliasStore.list(region, functionName);
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
        getAlias(region, functionName, aliasName); // verify it exists
        if (aliasStore != null) aliasStore.delete(region, functionName, aliasName);
        LOG.infov("Deleted alias {0} for function {1}", aliasName, functionName);
    }

    // ──────────────────────────── Function URL Config ────────────────────────────

    public LambdaUrlConfig createFunctionUrlConfig(String region, String functionName, String qualifier, Map<String, Object> request) {
        LambdaUrlConfig urlConfig = new LambdaUrlConfig();
        urlConfig.setAuthType((String) request.getOrDefault("AuthType", "NONE"));
        
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
            alias.setUrlConfig(urlConfig);
            if (aliasStore != null) aliasStore.save(region, alias);
        } else {
            LambdaFunction fn = getFunction(region, functionName);
            if (fn.getUrlConfig() != null) {
                throw new AwsException("ResourceConflictException", "Function URL config already exists for function: " + functionName, 409);
            }
            fn.setUrlConfig(urlConfig);
            functionStore.save(region, fn);
        }

        LOG.infov("Created Function URL for {0} (qualifier: {1}): {2}", functionName, qualifier, url);
        return urlConfig;
    }

    public LambdaUrlConfig getFunctionUrlConfig(String region, String functionName, String qualifier) {
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
        LambdaUrlConfig urlConfig = getFunctionUrlConfig(region, functionName, qualifier);
        
        if (request.containsKey("AuthType")) {
            urlConfig.setAuthType((String) request.get("AuthType"));
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

            // For non-Java runtimes, verify handler file exists
            if (fn.getRuntime() != null && !fn.getRuntime().startsWith("java")) {
                String handlerFile = fn.getHandler().split("\\.")[0];
                boolean found = Files.walk(codePath)
                        .anyMatch(p -> p.getFileName().toString().startsWith(handlerFile));
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

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
