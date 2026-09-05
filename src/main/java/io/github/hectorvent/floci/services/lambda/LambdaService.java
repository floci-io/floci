package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CustomResourceLiveness;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFileSystemConfig;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Business logic for Lambda function management and invocation.
 */
@ApplicationScoped
public class LambdaService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(LambdaService.class);

    /**
     * Qualifier constraint the live service enforces, taken from its own ValidationException
     * rather than the API reference, which publishes a laxer pattern. Notably it is ASCII-only,
     * so a non-ASCII digit is rejected here rather than being read as a version number.
     */
    private static final String QUALIFIER_PATTERN = "\\$(LATEST(\\.PUBLISHED)?)|[a-zA-Z0-9-_$]+";
    private static final Pattern QUALIFIER = Pattern.compile(QUALIFIER_PATTERN);
    private static final Pattern EFS_ACCESS_POINT_ARN = Pattern.compile(
            "^arn:aws[a-zA-Z-]*:elasticfilesystem:(?:eusc-)?[a-z]{2}"
                    + "(?:(?:-gov)|(?:-iso(?:b)?))?-[a-z]+-\\d:"
                    + "\\d{12}:access-point/fsap-[a-f0-9]{17}$");
    private static final Pattern FILE_SYSTEM_LOCAL_MOUNT_PATH = Pattern.compile("^/mnt/[A-Za-z0-9._-]+$");
    private static final Pattern LOG_GROUP_PATTERN = Pattern.compile("[.\\-_/#A-Za-z0-9]+");
    private static final Pattern ROLE_ARN_PATTERN = Pattern.compile(
            "arn:(aws[a-zA-Z-]*)?:iam::\\d{12}:role/?[a-zA-Z_0-9+=,.@\\-_/]+");
    private static final Pattern HANDLER_PATTERN = Pattern.compile("\\S+");
    private static final int MAX_HANDLER_LENGTH = 128;
    private static final List<String> FUNCTION_ARCHITECTURES = List.of("x86_64", "arm64");

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
    private final StorageFactory storageFactory;
    private final LambdaLayerService layerService;
    private final Ec2Service ec2Service;
    /** Null in the constructors tests use, exactly as the other optional collaborators above are. */
    private final CustomResourceLiveness customResourceLiveness;
    private Map<String, Integer> versionCounters = new ConcurrentHashMap<>();
    private Map<String, FunctionEventInvokeConfig> eventInvokeConfigs = new ConcurrentHashMap<>();
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
    private final ConcurrentHashMap<String, Object> policyMutationLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> versionCounterLocks = new ConcurrentHashMap<>();

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
        this(functionStore, warmPool, codeStore, zipExtractor, null, regionResolver);
    }

    /** Package-private constructor for testing with a supplied config (e.g. for hot-reload tests). */
    LambdaService(LambdaFunctionStore functionStore,
                  WarmPool warmPool,
                  CodeStore codeStore,
                  ZipExtractor zipExtractor,
                  EmulatorConfig config,
                  RegionResolver regionResolver) {
        this(functionStore, warmPool, codeStore, zipExtractor, config, regionResolver, null);
    }

    /** Package-private constructor for persistence tests: supplies a real storage factory. */
    LambdaService(LambdaFunctionStore functionStore,
                  WarmPool warmPool,
                  CodeStore codeStore,
                  ZipExtractor zipExtractor,
                  EmulatorConfig config,
                  RegionResolver regionResolver,
                  StorageFactory storageFactory) {
        this.functionStore = functionStore;
        this.executorService = null;
        this.concurrencyLimiter = new LambdaConcurrencyLimiter();
        this.warmPool = warmPool;
        this.codeStore = codeStore;
        this.zipExtractor = zipExtractor;
        this.config = config;
        this.regionResolver = regionResolver;
        this.esmStore = null;
        this.aliasStore = null;
        this.s3Service = null;
        this.sqsService = null;
        this.poller = null;
        this.kinesisPoller = null;
        this.dynamodbStreamsPoller = null;
        this.storageFactory = storageFactory;
        this.layerService = null;
        this.ec2Service = null;
        this.customResourceLiveness = null;
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
                          DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller,
                          StorageFactory storageFactory,
                          LambdaLayerService layerService,
                          Ec2Service ec2Service,
                          CustomResourceLiveness customResourceLiveness) {
        this.customResourceLiveness = customResourceLiveness;
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
        this.storageFactory = storageFactory;
        this.layerService = layerService;
        this.ec2Service = ec2Service;
    }

    // Real AWS validates a function's Layers eagerly at CreateFunction/UpdateFunctionConfiguration
    // time with InvalidParameterValueException, not lazily at invoke time - resolveLayerByArn's
    // caller in ContainerLauncher only logs a warning and silently launches without the layer's
    // content mounted, which is correct AWS-parity behavior for a layer deleted *after* being
    // attached (AWS doesn't re-validate on every invoke either), but was previously the only
    // signal at all for a bad ARN, even a typo caught at attach time on real AWS.
    private void validateLayersResolvable(List<String> layerArns) {
        if (layerArns == null || layerService == null) return;
        for (String arn : layerArns) {
            if (layerService.resolveLayerByArn(arn) == null) {
                throw new AwsException("InvalidParameterValueException",
                        "Layer version " + arn + " does not exist.", 400);
            }
        }
    }

    /** Package-private accessor for tests that want to assert limiter state directly. */
    LambdaConcurrencyLimiter concurrencyLimiter() {
        return concurrencyLimiter;
    }

    @PostConstruct
    void init() {
        initializeStorage();
        rehydrateConcurrency();
    }

    /**
     * Version counters and event-invoke configs are durable Lambda state: a counter
     * lost on restart re-issues already-used version numbers from PublishVersion.
     * Wrapped through the same "lambda" storage key as the function store.
     */
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.versionCounters = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-version-counters.json", new TypeReference<Map<String, Integer>>() {}));
        this.eventInvokeConfigs = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-event-invoke-configs.json",
                new TypeReference<Map<String, FunctionEventInvokeConfig>>() {}));
    }

    /**
     * Rehydrates reserved concurrency into the limiter from persisted function state.
     * Without this, restarts leave {@code totalReserved()=0} and allow validatePut /
     * unreserved-pool sizing to drift until each function is re-Put.
     */
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

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (LambdaFunction fn : functionStore.listAll()) {
            if (!"$LATEST".equals(fn.getVersion())) {
                continue;
            }
            String arn = fn.getFunctionArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "lambda:function", "lambda",
                    parsed.region(), parsed.accountId(),
                    fn.getLastModified() > 0 ? Instant.ofEpochMilli(fn.getLastModified()) : Instant.now(),
                    fn.getTags() != null ? fn.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("lambda:function", "lambda", true));
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
        List<String> architectures = validateArchitectures(request.get("Architectures"));

        LambdaFunction fn = new LambdaFunction();
        fn.setAccountId(regionResolver.getAccountId());
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
            validateLayersResolvable(layers);
            fn.setLayers(new ArrayList<>(layers));
        }

        if (request.containsKey("KMSKeyArn")) {
            fn.setKmsKeyArn((String) request.get("KMSKeyArn"));
        }

        if (request.get("VpcConfig") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vpc = (Map<String, Object>) request.get("VpcConfig");
            fn.setVpcConfig(vpc);
            fn.setVpcId(resolveVpcId(region, vpc));
        }

        applySnapStart(fn, request.get("SnapStart"));
        applyLoggingConfig(fn, request.get("LoggingConfig"));

        List<LambdaFileSystemConfig> fileSystemConfigs =
                parseFileSystemConfigs(request.get("FileSystemConfigs"));
        validateFileSystemVpcConfig(fileSystemConfigs, fn.getVpcConfig());
        fn.setFileSystemConfigs(fileSystemConfigs);

        // ImageConfig (PackageType=Image overrides)
        if (request.get("ImageConfig") instanceof Map<?, ?> ic) {
            @SuppressWarnings("unchecked")
            Map<String, Object> imageConfig = (Map<String, Object>) ic;
            if (imageConfig.get("Command") instanceof List<?> cmd) {
                fn.setImageConfigCommand(cmd.stream().map(Object::toString).toList());
            }
            if (imageConfig.get("EntryPoint") instanceof List<?> ep) {
                fn.setImageConfigEntryPoint(ep.stream().map(Object::toString).toList());
            }
            if (imageConfig.get("WorkingDirectory") instanceof String wd) {
                fn.setImageConfigWorkingDirectory(wd);
            }
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
                extractZipCode(fn, zipFileBase64, region);
            }
            String s3Bucket = (String) code.get("S3Bucket");
            String s3Key = (String) code.get("S3Key");
            if (s3Bucket != null && s3Key != null) {
                if ("hot-reload".equals(s3Bucket)) {
                    applyHotReload(fn, s3Key);
                } else {
                    extractZipCodeFromS3(fn, s3Bucket, s3Key, region);
                }
            }
        }

        functionStore.save(region, fn);
        LOG.infov("Created Lambda function: {0} in region {1}", functionName, region);
        if (Boolean.TRUE.equals(request.get("Publish"))) {
            return publishVersion(region, functionName, null);
        }
        return fn;
    }

    public LambdaFunction getFunction(String region, String functionName) {
        String canonical = canonicalFunctionName(region, functionName);
        return functionStore.get(region, canonical)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Function not found: " + functionName, 404));
    }

    /**
     * Reads a function, honouring a {@code Qualifier} that selects a published version or an alias.
     *
     * <p>The read paths used to ignore the qualifier entirely and answer with {@code $LATEST} for
     * everything, including versions that were never published (issue #2821). That defeats the
     * point of publishing: a caller pinning version 1 silently got whatever {@code $LATEST} held at
     * the time of the call, and a typo or a stale alias read back as a live function instead of
     * failing. Resolution is delegated to the same target resolver the invoke path uses, so a
     * qualifier means the same thing whether you read it or run it.
     *
     * <p>A qualifier may also be carried on the name itself, as {@code fn:1} or a qualified ARN. An
     * explicit {@code Qualifier} and one embedded in the name must agree; AWS rejects the
     * combination when they do not.
     */
    public LambdaFunction getFunction(String region, String functionName, String qualifier) {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(functionName);
        enforceRegion(region, ref);
        String effective = resolveQualifier(ref.qualifier(), qualifier);
        if (effective == null) {
            return getFunction(region, functionName);
        }
        if (functionName.startsWith("arn:")) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(functionName);
            return resolveReadTargetForAccount(arn.accountId(), region, ref.name(), effective);
        }
        return resolveReadTarget(region, ref.name(), effective);
    }

    /**
     * Reconciles a qualifier carried on the function name with an explicit {@code Qualifier}
     * parameter. Either alone wins; both must agree.
     */
    private static String resolveQualifier(String onName, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            return onName;
        }
        if (onName != null && !onName.equals(explicit)) {
            throw new AwsException("InvalidParameterValueException",
                    "Cannot provide both a qualified function name and a Qualifier: "
                            + onName + " and " + explicit, 400);
        }
        return explicit;
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
        List<String> architectures = validateArchitectures(request.get("Architectures"));

        String zipFileBase64 = (String) request.get("ZipFile");
        String imageUri = (String) request.get("ImageUri");
        String s3Bucket = (String) request.get("S3Bucket");
        String s3Key = (String) request.get("S3Key");

        if (zipFileBase64 != null) {
            fn.setS3Bucket(null);
            fn.setS3Key(null);
            extractZipCode(fn, zipFileBase64, region);
        }
        if (imageUri != null) {
            fn.setImageUri(imageUri);
        }
        if (s3Bucket != null && s3Key != null) {
            if ("hot-reload".equals(s3Bucket)) {
                applyHotReload(fn, s3Key);
            } else {
                extractZipCodeFromS3(fn, s3Bucket, s3Key, region);
            }
        }

        if (architectures != null) {
            fn.setArchitectures(new ArrayList<>(architectures));
        }

        fn.setLastModified(System.currentTimeMillis());
        fn.setRevisionId(UUID.randomUUID().toString());

        // Drain warm containers — they have stale code mounted
        warmPool.drainEnvironment(fn);

        functionStore.save(region, fn);
        LOG.infov("Updated code for function: {0}", functionName);
        if (Boolean.TRUE.equals(request.get("Publish"))) {
            return publishVersion(region, functionName, null);
        }
        return fn;
    }

    public LambdaFunction updateFunctionConfiguration(String region, String functionName, Map<String, Object> request) {
        LambdaFunction fn = getFunction(region, functionName);
        List<String> architectures = validateArchitectures(request.get("Architectures"));

        // Validated before any field mutation below, not inline where Layers is applied further
        // down - fn is the live object backing this store entry (InMemoryStorage#get returns the
        // same reference, not a copy), so validating this late would leave every
        // already-applied field (Description, Timeout, ...) live on a rejected update, since
        // nothing here is transactional and there's a single save() at the very end.
        @SuppressWarnings("unchecked")
        List<String> layerList = request.containsKey("Layers") && request.get("Layers") instanceof List
                ? (List<String>) request.get("Layers") : null;
        if (request.containsKey("Layers")) {
            validateLayersResolvable(layerList);
        }
        if (request.containsKey("SnapStart")) {
            validateSnapStart(request.get("SnapStart"));
        }
        if (request.containsKey("LoggingConfig")) {
            validateLoggingConfig(request.get("LoggingConfig"));
        }
        if (request.containsKey("Role")) {
            validateRoleArn((String) request.get("Role"));
        }
        if (request.containsKey("Handler")) {
            validateHandler((String) request.get("Handler"));
        }

        Map<String, Object> requestedVpcConfig = fn.getVpcConfig();
        if (request.get("VpcConfig") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vpc = (Map<String, Object>) request.get("VpcConfig");
            requestedVpcConfig = new java.util.HashMap<>(vpc);
        }
        List<LambdaFileSystemConfig> requestedFileSystemConfigs = fn.getFileSystemConfigs();
        if (request.containsKey("FileSystemConfigs")) {
            requestedFileSystemConfigs = parseFileSystemConfigs(request.get("FileSystemConfigs"));
        }
        if (request.containsKey("VpcConfig") || request.containsKey("FileSystemConfigs")) {
            validateFileSystemVpcConfig(requestedFileSystemConfigs, requestedVpcConfig);
        }

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

        if (architectures != null) {
            fn.setArchitectures(new ArrayList<>(architectures));
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
            fn.setLayers(layerList != null ? new ArrayList<>(layerList) : new ArrayList<>());
        }

        if (request.containsKey("KMSKeyArn")) {
            fn.setKmsKeyArn((String) request.get("KMSKeyArn"));
        }

        if (request.containsKey("VpcConfig")) {
            if (request.get("VpcConfig") instanceof Map<?, ?>) {
                fn.setVpcConfig(requestedVpcConfig);
                fn.setVpcId(resolveVpcId(region, requestedVpcConfig));
            }
        }

        if (request.containsKey("SnapStart")) {
            applySnapStart(fn, request.get("SnapStart"));
        }

        if (request.containsKey("LoggingConfig")) {
            applyLoggingConfig(fn, request.get("LoggingConfig"));
        }

        if (request.containsKey("FileSystemConfigs")) {
            fn.setFileSystemConfigs(requestedFileSystemConfigs);
        }

        if (request.containsKey("ImageConfig")) {
            if (request.get("ImageConfig") instanceof Map<?, ?> ic) {
                @SuppressWarnings("unchecked")
                Map<String, Object> imageConfig = (Map<String, Object>) ic;
                if (imageConfig.containsKey("Command")) {
                    List<String> cmd = imageConfig.get("Command") instanceof List<?>
                            ? ((List<?>) imageConfig.get("Command")).stream().map(Object::toString).toList() : null;
                    fn.setImageConfigCommand(cmd);
                }
                if (imageConfig.containsKey("EntryPoint")) {
                    List<String> ep = imageConfig.get("EntryPoint") instanceof List<?>
                            ? ((List<?>) imageConfig.get("EntryPoint")).stream().map(Object::toString).toList() : null;
                    fn.setImageConfigEntryPoint(ep);
                }
                if (imageConfig.containsKey("WorkingDirectory")) {
                    fn.setImageConfigWorkingDirectory(
                            imageConfig.get("WorkingDirectory") instanceof String wd ? wd : null);
                }
            }
        }

        fn.setLastModified(System.currentTimeMillis());
        fn.setRevisionId(UUID.randomUUID().toString());

        // Drain warm containers so the next invocation picks up the new configuration
        warmPool.drainEnvironment(fn);

        functionStore.save(region, fn);
        LOG.infov("Updated configuration for function: {0}", functionName);
        return fn;
    }

    /**
     * DeleteFunction without a Qualifier: removes the function and every version of it.
     */
    public void deleteFunction(String region, String functionName) {
        deleteFunction(region, functionName, null);
    }

    /**
     * DeleteFunction. A Qualifier names a single published version to remove, leaving the
     * function and its other versions in place; without one the whole function goes.
     */
    public void deleteFunction(String region, String functionName, String qualifier) {
        if (qualifier != null && !qualifier.isEmpty()) {
            deleteFunctionVersion(region, functionName, qualifier);
            return;
        }
        deleteWholeFunction(region, functionName);
    }

    /**
     * Removes one published version. Error behaviour follows the live service: deleting
     * {@code $LATEST} or naming an alias is rejected, a version an alias points at is a
     * conflict, and a version that does not exist is a silent success rather than a 404.
     */
    private void deleteFunctionVersion(String region, String functionName, String qualifier) {
        if (!QUALIFIER.matcher(qualifier).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + qualifier + "' at 'qualifier' failed"
                            + " to satisfy constraint: Member must satisfy regular expression"
                            + " pattern: " + QUALIFIER_PATTERN, 400);
        }
        LambdaFunction fn = getFunction(region, functionName);
        if ("$LATEST".equals(qualifier)) {
            throw new AwsException("InvalidParameterValueException",
                    "$LATEST version cannot be deleted without deleting the function.", 400);
        }
        if (!qualifier.chars().allMatch(c -> c >= '0' && c <= '9')) {
            // A non-numeric qualifier names an alias, which the live service refuses to
            // resolve here rather than deleting the version behind it.
            throw new AwsException("InvalidParameterValueException",
                    "Deletion of aliases is not currently supported.", 400);
        }
        String name = fn.getFunctionName();
        List<String> referencing = aliasStore == null ? List.of()
                : aliasStore.list(region, name).stream()
                        .filter(alias -> qualifier.equals(alias.getFunctionVersion()))
                        .map(LambdaAlias::getName)
                        .toList();
        if (!referencing.isEmpty()) {
            throw new AwsException("ResourceConflictException",
                    "Unable to delete version because the following aliases reference it: "
                            + referencing, 409);
        }
        Optional<LambdaFunction> version = functionStore.get(region, name, qualifier);
        if (version.isEmpty()) {
            return;
        }
        synchronized (lockForConcurrencyOp(fn.getFunctionArn())) {
            warmPool.drainEnvironment(version.get());
            functionStore.deleteVersion(region, name, qualifier);
            // The snapshot shares $LATEST's code directory, so this only reclaims once no
            // remaining version references it.
            reclaimLegacyCodeDirectoryIfUnused(name);
        }
        LOG.infov("Deleted version {0} of Lambda function: {1}", qualifier, name);
    }

    private void deleteWholeFunction(String region, String functionName) {
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
            codeStore.delete(ownerAccount(fn), functionName);
            functionStore.delete(region, functionName);
            reclaimLegacyCodeDirectoryIfUnused(functionName);
            versionCounters.remove(versionCounterKey(region, fn));
            versionCounters.remove(legacyVersionCounterKey(region, functionName));
            if (aliasStore != null) {
                for (LambdaAlias alias : aliasStore.list(region, functionName)) {
                    aliasStore.delete(region, functionName, alias.getName());
                }
            }
        }
        // Best-effort: drop the stored deployment package.
        if (s3Service != null) {
            try {
                s3Service.deleteObject(tasksBucketName(region), codeObjectKey(fn));
            } catch (Exception e) {
                LOG.warnv("Could not delete deployment package for {0}: {1}",
                        functionName, e.getMessage());
            }
        }
        LOG.infov("Deleted Lambda function: {0}", functionName);
    }

    /**
     * The pre-account-scoped code layout gave every account's same-named function the exact
     * same on-disk directory (CodeStore never scoped it by region either), so it is only safe
     * to reclaim once nothing at all still has {@code codeLocalPath} pointing at it. That
     * includes published versions, not just {@code $LATEST}: publishVersion snapshots
     * codeLocalPath verbatim, so a version published while $LATEST was still on the legacy path
     * keeps that directory alive on its own even after $LATEST itself migrates. Checking only
     * $LATEST (via listAllAccounts) missed this and let a later delete or code update reclaim
     * the directory out from under that version's own future invokes. listAll() has no region
     * or $LATEST filter, matching the fact that the legacy path itself carries neither.
     */
    private void reclaimLegacyCodeDirectoryIfUnused(String functionName) {
        String legacyPath = codeStore.getLegacyCodePath(functionName).toAbsolutePath().normalize().toString();
        boolean stillLive = functionStore.listAll().stream()
                .anyMatch(other -> functionName.equals(other.getFunctionName())
                        && legacyPath.equals(other.getCodeLocalPath()));
        if (!stillLive) {
            codeStore.deleteLegacy(functionName);
        }
    }

    public InvokeResult invoke(String region, String functionName, byte[] payload, InvocationType type) {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(functionName);
        enforceRegion(region, ref);
        String name = ref.name();
        String qualifier = ref.qualifier();
        LambdaFunction fn;
        if (functionName.startsWith("arn:")) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(functionName);
            fn = resolveInvokeTargetForAccount(arn.accountId(), region, name, qualifier);
        } else {
            fn = resolveInvokeTarget(region, name, qualifier);
        }
        reportCustomResourceLiveness(payload);
        InvokeResult result = executorService.invoke(fn, payload, type);
        result.setExecutedVersion(fn.getVersion());
        return result;
    }

    /** Invokes a Lambda target ARN using the account encoded in that ARN. */
    public InvokeResult invokeArn(String functionArn, byte[] payload, InvocationType type) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(functionArn);
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(functionArn);
        LambdaFunction fn = resolveInvokeTargetForAccount(
                arn.accountId(), arn.region(), ref.name(), ref.qualifier());
        InvokeResult result = executorService.invoke(fn, payload, type);
        result.setExecutedVersion(fn.getVersion());
        return result;
    }

    /**
     * Reports that a pending custom resource is still making progress, if this payload belongs to
     * one. A CDK provider-framework waiter re-invokes {@code framework.isComplete} on a cadence and
     * echoes the original event -- including its ResponseURL -- into every poll, so a poll landing
     * here is proof of liveness for that resource's callback token. Resetting the idle budget on it
     * keeps a long-but-progressing resource (12 org accounts at one per poll) from being cut off
     * mid-success, without CloudFormation needing to know how much work is left.
     */
    private void reportCustomResourceLiveness(byte[] payload) {
        if (customResourceLiveness == null) {
            return;
        }
        CustomResourceLiveness.tokenIn(payload).ifPresent(customResourceLiveness::touch);
    }

    private LambdaFunction resolveInvokeTarget(String region, String name, String qualifier) {
        return resolveTarget(region, name, qualifier, this::pickAliasVersion);
    }

    /**
     * Resolves a qualifier for a <em>read</em>. Identical to the invoke path except for aliases:
     * an alias with {@code AdditionalVersionWeights} shifts traffic, so {@link #pickAliasVersion}
     * chooses randomly among the weighted versions, which is right for running the function and
     * wrong for describing it. Two reads of one alias must not disagree, so a read follows the
     * alias's primary {@code FunctionVersion}, which is what AWS reports.
     */
    private LambdaFunction resolveReadTarget(String region, String name, String qualifier) {
        return resolveTarget(region, name, qualifier, LambdaAlias::getFunctionVersion);
    }

    private LambdaFunction resolveTarget(String region, String name, String qualifier,
                                         java.util.function.Function<LambdaAlias, String> aliasVersion) {
        if (qualifier == null || qualifier.equals("$LATEST")) {
            return functionStore.get(region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Function not found: " + name, 404));
        }
        if (qualifier.chars().allMatch(Character::isDigit)) {
            return functionStore.get(region, name, qualifier)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function version not found: " + name + ":" + qualifier, 404));
        }
        // qualifier is an alias name
        LambdaAlias alias = getAlias(region, name, qualifier);
        String version = aliasVersion.apply(alias);
        if (version == null || version.equals("$LATEST")) {
            return functionStore.get(region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Function not found: " + name, 404));
        }
        return functionStore.get(region, name, version)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Function version not found: " + name + ":" + version, 404));
    }

    private LambdaFunction resolveInvokeTargetForAccount(
            String accountId, String region, String name, String qualifier) {
        return resolveTargetForAccount(accountId, region, name, qualifier, this::pickAliasVersion);
    }

    /** The read counterpart of {@link #resolveInvokeTargetForAccount}; see {@link #resolveReadTarget}. */
    private LambdaFunction resolveReadTargetForAccount(
            String accountId, String region, String name, String qualifier) {
        return resolveTargetForAccount(accountId, region, name, qualifier, LambdaAlias::getFunctionVersion);
    }

    private LambdaFunction resolveTargetForAccount(
            String accountId, String region, String name, String qualifier,
            java.util.function.Function<LambdaAlias, String> aliasVersion) {
        if (qualifier == null || qualifier.equals("$LATEST")) {
            return functionStore.getForAccount(accountId, region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function not found: " + name, 404));
        }
        if (qualifier.chars().allMatch(Character::isDigit)) {
            return functionStore.getForAccount(accountId, region, name, qualifier)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function version not found: " + name + ":" + qualifier, 404));
        }
        LambdaAlias alias = aliasStore != null
                ? aliasStore.getForAccount(accountId, region, name, qualifier)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Alias not found: " + qualifier, 404))
                : null;
        if (alias == null) {
            throw new AwsException("ResourceNotFoundException", "Alias not found: " + qualifier, 404);
        }
        String version = aliasVersion.apply(alias);
        if (version == null || version.equals("$LATEST")) {
            return functionStore.getForAccount(accountId, region, name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Function not found: " + name, 404));
        }
        return functionStore.getForAccount(accountId, region, name, version)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Function version not found: " + name + ":" + version, 404));
    }

    private String pickAliasVersion(LambdaAlias alias) {
        java.util.Map<String, Double> weights = alias.getRoutingConfig();
        if (weights == null || weights.isEmpty()) {
            return alias.getFunctionVersion();
        }
        double rand = java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        double additionalTotal = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double primaryWeight = Math.max(0.0, 1.0 - additionalTotal);
        if (rand < primaryWeight) {
            return alias.getFunctionVersion();
        }
        double cumulative = primaryWeight;
        for (java.util.Map.Entry<String, Double> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (rand < cumulative) {
                return entry.getKey();
            }
        }
        return alias.getFunctionVersion();
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
            resolvedRegion = AwsArnUtils.regionOrDefault(eventSourceArn, region);
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

        Boolean bisectBatchOnFunctionError = request.get("BisectBatchOnFunctionError") instanceof Boolean b
                ? b
                : null;

        EventSourceMapping.DestinationConfig destinationConfig = parseDestinationConfig(request);

        StartingPositionSpec startingPosition = parseStartingPosition(request, eventSourceArn);

        String queueUrl = eventSourceArn.contains(":sqs:") ? AwsArnUtils.arnToQueueUrl(eventSourceArn, config.effectiveBaseUrl()) : null;

        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid(UUID.randomUUID().toString());
        esm.setAccountId(regionResolver.getAccountId());
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
        esm.setBisectBatchOnFunctionError(bisectBatchOnFunctionError);
        esm.setDestinationConfig(destinationConfig);
        esm.setStartingPosition(startingPosition.position());
        esm.setStartingPositionTimestamp(startingPosition.timestampMillis());
        esm.setLastModified(System.currentTimeMillis());

        esmStore.save(esm);
        if (enabled) {
            startPollingHelper(esm);
        }
        LOG.infov("Created ESM {0}: {1} → {2}", esm.getUuid(), eventSourceArn, resolvedName);
        return esm;
    }

    private EventSourceMapping.DestinationConfig parseDestinationConfig(Map<String, Object> request) {
        Object raw = request.get("DestinationConfig");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> destMap)) {
            throw new AwsException("InvalidParameterValueException",
                    "DestinationConfig must be a JSON object", 400);
        }

        Object rawOnFailure = destMap.get("OnFailure");
        if (rawOnFailure == null) {
            return null;
        }
        if (!(rawOnFailure instanceof Map<?, ?> onFailureMap)) {
            throw new AwsException("InvalidParameterValueException",
                    "DestinationConfig.OnFailure must be a JSON object", 400);
        }

        Object destination = onFailureMap.get("Destination");
        if (destination == null) {
            return null;
        }

        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(String.valueOf(destination));

        EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
        destinationConfig.setOnFailure(onFailure);
        return destinationConfig;
    }

    /** A validated {@code StartingPosition} plus, for {@code AT_TIMESTAMP}, its epoch-millis instant. */
    private record StartingPositionSpec(String position, Long timestampMillis) {}

    /**
     * Parses {@code StartingPosition}/{@code StartingPositionTimestamp} out of a create request.
     *
     * <p>Absent means absent: AWS requires a starting position for stream event sources, but Floci
     * has always accepted mappings without one (its pollers default to reading from the trim
     * horizon), so this validates only what a caller actually sent rather than newly rejecting
     * requests that used to succeed. There is no update counterpart because AWS's
     * UpdateEventSourceMapping does not accept the field at all - it is replace-only.
     */
    private StartingPositionSpec parseStartingPosition(Map<String, Object> request, String eventSourceArn) {
        Object raw = request.get("StartingPosition");
        if (raw == null) {
            return new StartingPositionSpec(null, null);
        }
        if (!(raw instanceof String position) || position.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "StartingPosition must be one of TRIM_HORIZON, LATEST, AT_TIMESTAMP", 400);
        }
        if (!"TRIM_HORIZON".equals(position) && !"LATEST".equals(position) && !"AT_TIMESTAMP".equals(position)) {
            throw new AwsException("InvalidParameterValueException",
                    "StartingPosition must be one of TRIM_HORIZON, LATEST, AT_TIMESTAMP (got " + position + ")", 400);
        }
        if (!"AT_TIMESTAMP".equals(position)) {
            return new StartingPositionSpec(position, null);
        }
        // AT_TIMESTAMP is Kinesis-only among the event sources Floci supports - DynamoDB Streams
        // shard iterators have no timestamp form at all, so silently accepting it there would
        // promise a starting point that can never be honoured.
        if (eventSourceArn != null && !eventSourceArn.contains(":kinesis:")) {
            throw new AwsException("InvalidParameterValueException",
                    "AT_TIMESTAMP is only supported for Amazon Kinesis event sources", 400);
        }
        Object rawTimestamp = request.get("StartingPositionTimestamp");
        if (rawTimestamp == null) {
            throw new AwsException("InvalidParameterValueException",
                    "StartingPositionTimestamp is required when StartingPosition is AT_TIMESTAMP", 400);
        }
        if (!(rawTimestamp instanceof Number timestamp)) {
            throw new AwsException("InvalidParameterValueException",
                    "StartingPositionTimestamp must be a numeric epoch-seconds value", 400);
        }
        // Lambda speaks restJson1, whose timestamps are (possibly fractional) epoch seconds - the
        // same convention buildEsmResponse already uses for LastModified on the way back out.
        return new StartingPositionSpec(position, Math.round(timestamp.doubleValue() * 1000.0));
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

        if (request.containsKey("BisectBatchOnFunctionError")) {
            Object raw = request.get("BisectBatchOnFunctionError");
            esm.setBisectBatchOnFunctionError(raw instanceof Boolean b ? b : null);
        }

        if (request.containsKey("DestinationConfig")) {
            esm.setDestinationConfig(parseDestinationConfig(request));
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

    /**
     * Locked get-increment-put: StorageBackedMap has no atomic merge, so the
     * sequence must not interleave or concurrent PublishVersion calls could issue
     * duplicate version numbers. The lock is per counter key (same pattern as
     * {@code concurrencyOpLocks}) so publishes of unrelated functions do not
     * serialize on the instance monitor for the storage round-trip.
     */
    /**
     * The already-published version cut from {@code $LATEST}'s current revision, if there is one.
     *
     * <p>Versions published before {@code sourceRevisionId} was recorded carry null and never match,
     * so existing state keeps its previous behaviour rather than deduplicating against a revision
     * nobody wrote down.
     */
    private LambdaFunction latestPublishedFrom(String region, LambdaFunction fn, String description) {
        String current = fn.getRevisionId();
        if (current == null) {
            return null;
        }
        // A hot-reload function's code lives in a bind-mounted directory that changes without any
        // API call, so revisionId cannot witness it. Deduplicating would hand back a version whose
        // identity no longer describes what will actually run, so these always publish.
        if (fn.getHotReloadHostPath() != null) {
            return null;
        }
        // Description is supplied at publish time rather than carried on $LATEST, so a publish that
        // names a new one is a real publish even when nothing else moved. LambdaVersionIntegrationTest
        // asserts exactly that: two publishes differing only in Description produce versions 1 and 2.
        String effective = description != null ? description : fn.getDescription();
        // Only the most recent version counts. AWS compares against the last version, so a
        // description going A then B then A must publish again rather than matching the older A:
        // scanning every version would return version 1 and leave the caller with a version whose
        // place in the history is wrong.
        return newestVersion(region, fn.getFunctionName())
                .filter(v -> current.equals(v.getSourceRevisionId()))
                .filter(v -> Objects.equals(effective, v.getDescription()))
                .orElse(null);
    }

    /** The highest-numbered published version of a function, if any. */
    private java.util.Optional<LambdaFunction> newestVersion(String region, String functionName) {
        return functionStore.listVersions(region, functionName).stream()
                .filter(v -> v.getVersion() != null && !"$LATEST".equals(v.getVersion()))
                .filter(v -> v.getVersion().chars().allMatch(Character::isDigit))
                .max(java.util.Comparator.comparingLong(v -> Long.parseLong(v.getVersion())));
    }

    private int nextVersionNumber(String counterKey, String legacyCounterKey) {
        synchronized (versionCounterLocks.computeIfAbsent(counterKey, k -> new Object())) {
            Integer current = versionCounters.get(counterKey);
            if (current == null && legacyCounterKey != null) {
                // Counters persisted before the key carried the owning account. Starting over
                // from 0 here would re-issue a version number that already names a snapshot.
                current = versionCounters.remove(legacyCounterKey);
            }
            int next = (current != null ? current : 0) + 1;
            versionCounters.put(counterKey, next);
            return next;
        }
    }

    /**
     * Version numbering belongs to the owning account, not to whichever account's
     * partition the counter map happens to be reached through.
     */
    static String versionCounterKey(String region, LambdaFunction fn) {
        return region + "::" + ownerAccount(fn) + "::" + fn.getFunctionName();
    }

    /** The pre-account counter key, still present in persisted state from before this change. */
    private static String legacyVersionCounterKey(String region, String functionName) {
        return region + "::" + functionName;
    }

    /** Package-private accessor for tests that need to seed or inspect counter state. */
    Map<String, Integer> versionCounters() {
        return versionCounters;
    }

    public LambdaFunction publishVersion(String region, String functionName, String description) {
        return publishVersion(region, functionName, description, null);
    }

    /**
     * Publishes a version of {@code $LATEST}.
     *
     * <p>An unchanged publish does not create a version: "AWS Lambda doesn't publish a version if
     * the function's configuration and code haven't changed since the last version". Publishing was
     * unconditional, so repeated publishes accumulated identical versions. "Unchanged" is decided by
     * {@code $LATEST}'s {@code revisionId}, which is regenerated on every code and configuration
     * update, so a version records the revision it was cut from and a later publish that finds it
     * unmoved returns that version.
     *
     * <p>{@code CodeSha256} is a precondition: "only publish a version if the hash value matches the
     * value that's specified". It was never parsed out of the request, so a caller that raced
     * someone else's deploy published code it had not authorised, silently (issue #2822).
     */
    public LambdaFunction publishVersion(String region, String functionName, String description,
                                         String expectedCodeSha256) {
        LambdaFunction fn = getFunction(region, functionName);
        functionName = fn.getFunctionName();
        // Shares the per-function lock deleteFunction and extractZipCodeBytes take around their
        // own codeLocalPath-affecting work: without it, a version could be published in the
        // narrow window between reclaimLegacyCodeDirectoryIfUnused's check and its actual
        // delete, persisting a snapshot.codeLocalPath (below) that names a directory about to
        // be removed as unreferenced.
        synchronized (lockForConcurrencyOp(fn.getFunctionArn())) {
            // Inside the lock UpdateFunctionCode takes, so the hash cannot be checked against one
            // version of $LATEST and the snapshot then taken from another. Checking it outside
            // would let an overlapping deploy publish code the caller never authorised.
            // No isBlank() exclusion here. A present but empty value was previously treated as
            // absent, so it skipped the comparison entirely and published without checking
            // anything, which is the failure this precondition exists to prevent. It is simply
            // compared like any other value and fails as a mismatch, which avoids inventing an
            // error shape for the empty case that has not been measured against the live service.
            if (expectedCodeSha256 != null && !expectedCodeSha256.equals(fn.getCodeSha256())) {
                throw new AwsException("InvalidParameterValueException",
                        "CodeSHA256 (" + expectedCodeSha256 + ") is different from current CodeSHA256 in $LATEST",
                        400);
            }

            LambdaFunction unchanged = latestPublishedFrom(region, fn, description);
            if (unchanged != null) {
                LOG.debugv("PublishVersion for {0} found nothing changed since version {1}; "
                        + "returning it rather than creating a duplicate", functionName, unchanged.getVersion());
                return unchanged;
            }
            int version = nextVersionNumber(versionCounterKey(region, fn),
                    legacyVersionCounterKey(region, functionName));
            LambdaFunction snapshot = new LambdaFunction();
            snapshot.setAccountId(fn.getAccountId());
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
            snapshot.setVpcConfig(fn.getVpcConfig() == null
                    ? null
                    : new java.util.HashMap<>(fn.getVpcConfig()));
            snapshot.setVpcId(fn.getVpcId());
            snapshot.setSnapStartApplyOn(fn.getSnapStartApplyOn());
            snapshot.setLogFormat(fn.getLogFormat());
            snapshot.setApplicationLogLevel(fn.getApplicationLogLevel());
            snapshot.setSystemLogLevel(fn.getSystemLogLevel());
            snapshot.setLogGroup(fn.getLogGroup());
            snapshot.setFileSystemConfigs(new ArrayList<>(fn.getFileSystemConfigs()));
            snapshot.setLastModified(System.currentTimeMillis());
            snapshot.setRevisionId(UUID.randomUUID().toString());
            snapshot.setSourceRevisionId(fn.getRevisionId());

            // Everything that determines what actually runs. Without these the snapshot describes a
            // function with no code: a version-qualified invoke resolves to it, launches a container
            // with nothing in it, and hangs to the function timeout instead of failing (#1987). A
            // published version is an immutable snapshot of code plus configuration, so it carries the
            // code location for every package type, not only Zip.
            snapshot.setCodeLocalPath(fn.getCodeLocalPath());
            snapshot.setCodeSha256(fn.getCodeSha256());
            snapshot.setS3Bucket(fn.getS3Bucket());
            snapshot.setS3Key(fn.getS3Key());
            snapshot.setHotReloadHostPath(fn.getHotReloadHostPath());
            snapshot.setImageUri(fn.getImageUri());
            snapshot.setImageConfigCommand(fn.getImageConfigCommand());
            snapshot.setImageConfigEntryPoint(fn.getImageConfigEntryPoint());
            snapshot.setImageConfigWorkingDirectory(fn.getImageConfigWorkingDirectory());
            snapshot.setLayers(fn.getLayers());
            snapshot.setArchitectures(fn.getArchitectures());
            snapshot.setEphemeralStorageSize(fn.getEphemeralStorageSize());
            snapshot.setTracingMode(fn.getTracingMode());
            snapshot.setDeadLetterTargetArn(fn.getDeadLetterTargetArn());
            snapshot.setKmsKeyArn(fn.getKmsKeyArn());

            functionStore.save(region, snapshot);
            LOG.infov("Published version {0} for function {1}", version, functionName);
            return snapshot;
        }
    }

    private static List<LambdaFileSystemConfig> parseFileSystemConfigs(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> configs)) {
            throw new AwsException("InvalidParameterValueException",
                    "FileSystemConfigs must be a list", 400);
        }
        if (configs.size() > 1) {
            throw new AwsException("InvalidParameterValueException",
                    "A Lambda function supports at most one file system configuration", 400);
        }

        List<LambdaFileSystemConfig> parsed = new ArrayList<>();
        for (Object rawConfig : configs) {
            if (!(rawConfig instanceof Map<?, ?> config)) {
                throw new AwsException("InvalidParameterValueException",
                        "Each FileSystemConfigs entry must be an object", 400);
            }
            Object arnValue = config.get("Arn");
            Object mountPathValue = config.get("LocalMountPath");
            String arn = arnValue instanceof String string ? string : null;
            String localMountPath = mountPathValue instanceof String string ? string : null;
            if (arn == null || arn.isBlank() || arn.length() > 256) {
                throw new AwsException("InvalidParameterValueException",
                        "File system Arn must be 1 to 256 characters", 400);
            }
            if (!EFS_ACCESS_POINT_ARN.matcher(arn).matches()) {
                throw new AwsException("InvalidParameterValueException",
                        "File system Arn must identify an EFS access point", 400);
            }
            if (localMountPath == null || localMountPath.isBlank() || localMountPath.length() > 160
                    || !FILE_SYSTEM_LOCAL_MOUNT_PATH.matcher(localMountPath).matches()) {
                throw new AwsException("InvalidParameterValueException",
                        "LocalMountPath must be a directory directly under /mnt", 400);
            }
            parsed.add(new LambdaFileSystemConfig(arn, localMountPath));
        }
        return parsed;
    }

    private static List<String> validateArchitectures(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> architectures)) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value '" + value + "' at 'architectures' "
                            + "failed to satisfy constraint: Member must be a list",
                    400);
        }
        if (architectures.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value '[]' at 'architectures' "
                            + "failed to satisfy constraint: Member must have length greater than or equal to 1",
                    400);
        }
        if (architectures.size() > 1) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value '" + architectures + "' at 'architectures' "
                            + "failed to satisfy constraint: Member must have length less than or equal to 1",
                    400);
        }

        Object architecture = architectures.getFirst();
        if (!FUNCTION_ARCHITECTURES.contains(architecture)) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value '" + architecture
                            + "' at 'architectures.1.member' "
                            + "failed to satisfy constraint: Member must satisfy enum value set: "
                            + FUNCTION_ARCHITECTURES,
                    400);
        }
        return List.of((String) architecture);
    }

    private static void validateFileSystemVpcConfig(List<LambdaFileSystemConfig> fileSystemConfigs,
                                                    Map<String, Object> vpcConfig) {
        if (fileSystemConfigs == null || fileSystemConfigs.isEmpty()) {
            return;
        }
        if (!hasValues(vpcConfig, "SubnetIds") || !hasValues(vpcConfig, "SecurityGroupIds")) {
            throw new AwsException("InvalidParameterValueException",
                    "EFS file system access requires VpcConfig with subnets and security groups", 400);
        }
    }

    /**
     * The VpcConfig request shape carries no VpcId; VpcConfigResponse does. AWS derives it from
     * the subnets the function is attached to, so resolve it once at attach time and store it.
     * A subnet EC2 has never heard of resolves to null rather than failing the call - Floci's
     * Lambda accepts unmanaged subnet ids, and CreateFunction must not start rejecting them.
     */
    private String resolveVpcId(String region, Map<String, Object> vpcConfig) {
        if (ec2Service == null || !hasValues(vpcConfig, "SubnetIds")) {
            return null;
        }
        for (Object subnetId : (List<?>) vpcConfig.get("SubnetIds")) {
            if (subnetId == null) {
                continue;
            }
            try {
                List<Subnet> found = ec2Service.describeSubnets(region, List.of(subnetId.toString()), Map.of());
                if (!found.isEmpty() && found.get(0).getVpcId() != null) {
                    return found.get(0).getVpcId();
                }
            } catch (RuntimeException e) {
                LOG.debugv(e, "Could not resolve VpcId for subnet {0} in {1}", subnetId, region);
            }
        }
        return null;
    }

    private static void validateSnapStart(Object value) {
        if (!(value instanceof Map<?, ?> snapStart)) {
            return;
        }
        Object applyOn = snapStart.get("ApplyOn");
        if (applyOn != null && !"None".equals(applyOn) && !"PublishedVersions".equals(applyOn)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + applyOn + "' at 'snapStart.applyOn' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: "
                            + "[PublishedVersions, None]", 400);
        }
    }

    private static void applySnapStart(LambdaFunction fn, Object value) {
        if (!(value instanceof Map<?, ?> snapStart)) {
            return;
        }
        validateSnapStart(value);
        Object applyOn = snapStart.get("ApplyOn");
        fn.setSnapStartApplyOn(applyOn instanceof String s && !s.isBlank() ? s : "None");
    }

    private static void validateLoggingConfig(Object value) {
        if (!(value instanceof Map<?, ?> logging)) {
            return;
        }
        validateEnum(logging.get("LogFormat"), "loggingConfig.logFormat", List.of("JSON", "Text"));
        validateEnum(logging.get("ApplicationLogLevel"), "loggingConfig.applicationLogLevel",
                List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"));
        validateEnum(logging.get("SystemLogLevel"), "loggingConfig.systemLogLevel",
                List.of("DEBUG", "INFO", "WARN"));
        validateLogGroup(logging.get("LogGroup"));
    }

    private static void validateEnum(Object value, String field, List<String> allowed) {
        if (value == null || allowed.contains(value)) {
            return;
        }
        throw new AwsException("ValidationException",
                "1 validation error detected: Value '" + value + "' at '" + field + "' failed to satisfy "
                        + "constraint: Member must satisfy enum value set: ["
                        + String.join(", ", allowed) + "]", 400);
    }

    /**
     * LogGroup is the one LoggingConfig member with a documented length and character
     * constraint rather than an enum: 1-512 characters, {@code [.\-_/#A-Za-z0-9]+}.
     */
    private static void validateLogGroup(Object value) {
        if (!(value instanceof String group) || group.isBlank()) {
            return;
        }
        if (group.length() > 512 || !LOG_GROUP_PATTERN.matcher(group).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + group + "' at 'loggingConfig.logGroup' failed to "
                            + "satisfy constraint: Member must satisfy regular expression pattern: "
                            + "[.\\-_/#A-Za-z0-9]+ or member must have length less than or equal to 512", 400);
        }
    }

    /**
     * Role must be an IAM role ARN ({@code arn:aws*:iam::ACCOUNT:role/NAME}); blank/absent is
     * only valid because callers gate this on {@code containsKey("Role")} beforehand.
     */
    private static void validateRoleArn(String role) {
        if (role == null || !ROLE_ARN_PATTERN.matcher(role).matches()) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value '" + role + "' at 'role' failed to satisfy "
                            + "constraint: Member must satisfy regular expression pattern: "
                            + ROLE_ARN_PATTERN.pattern(), 400);
        }
    }

    /**
     * Handler must be a 0-128 character string with no whitespace; unlike {@code Description},
     * a caller-supplied {@code null} is rejected rather than treated as "clear it" — {@code null}
     * is not a string at all, and Handler names the file AWS invokes, so silently nulling it out
     * would break every subsequent invocation instead of failing the request that caused it.
     */
    private static void validateHandler(String handler) {
        if (handler == null) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value 'null' at 'handler' failed to satisfy "
                            + "constraint: Member must not be null", 400);
        }
        if (handler.isEmpty()) {
            return;
        }
        if (handler.length() > MAX_HANDLER_LENGTH || !HANDLER_PATTERN.matcher(handler).matches()) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value '" + handler + "' at 'handler' failed to satisfy "
                            + "constraint: Member must satisfy regular expression pattern: "
                            + HANDLER_PATTERN.pattern() + " or member must have length less than or equal to "
                            + MAX_HANDLER_LENGTH, 400);
        }
    }

    /**
     * LoggingConfig is replaced wholesale, not merged: AWS resets any member the request omits
     * back to its default, so an update that names only LogFormat drops a previously set LogGroup.
     *
     * <p>ApplicationLogLevel and SystemLogLevel are JSON-format-only: {@link #putLoggingConfig}
     * never surfaces them for a Text-format function. Storing them anyway would leave state
     * that is accepted, persisted and then never observable through any read path — the same
     * accepted-then-forgotten shape this fix removes elsewhere — so they are only kept when the
     * resolved format is JSON.
     */
    private static void applyLoggingConfig(LambdaFunction fn, Object value) {
        if (!(value instanceof Map<?, ?> logging)) {
            return;
        }
        validateLoggingConfig(value);
        String format = logging.get("LogFormat") instanceof String f && !f.isBlank() ? f : "Text";
        boolean json = "JSON".equals(format);
        fn.setLogFormat(format);
        fn.setApplicationLogLevel(json && logging.get("ApplicationLogLevel") instanceof String level
                && !level.isBlank() ? level : null);
        fn.setSystemLogLevel(json && logging.get("SystemLogLevel") instanceof String level
                && !level.isBlank() ? level : null);
        fn.setLogGroup(logging.get("LogGroup") instanceof String group && !group.isBlank()
                ? group : null);
    }

    private static boolean hasValues(Map<String, Object> config, String key) {
        return config != null
                && config.get(key) instanceof List<?> values
                && !values.isEmpty();
    }

    public List<LambdaFunction> listVersionsByFunction(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName); // verify function exists
        return functionStore.listVersions(region, fn.getFunctionName());
    }

    /**
     * Deletes a single published version of a function (DeleteFunction with a numeric qualifier),
     * leaving {@code $LATEST} and every other version untouched. Deleting an already-gone version
     * is a no-op; a missing function is a 404.
     */
    public void deleteVersion(String region, String functionName, String version) {
        if (version == null || version.isBlank() || "$LATEST".equals(version)) {
            throw new AwsException("InvalidParameterValueException",
                    "Version must be a published version number, got: " + version, 400);
        }
        LambdaFunction fn = getFunction(region, functionName); // throws 404 if not found
        functionStore.deleteVersion(region, fn.getFunctionName(), version);
        LOG.infov("Deleted version {0} of function {1}", version, fn.getFunctionName());
    }

    // ──────────────────────────── Aliases ────────────────────────────

    public LambdaAlias createAlias(String region, String functionName, String aliasName,
                                   String functionVersion, String description,
                                   java.util.Map<String, Double> routingConfig) {
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
        alias.setRoutingConfig(routingConfig);
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
                                   String functionVersion, String description,
                                   java.util.Map<String, Double> routingConfig) {
        LambdaAlias alias = getAlias(region, functionName, aliasName);
        if (functionVersion != null) alias.setFunctionVersion(functionVersion);
        if (description != null) alias.setDescription(description);
        if (routingConfig != null) alias.setRoutingConfig(routingConfig.isEmpty() ? null : routingConfig);
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

        String accountId = regionResolver.getAccountId();
        String urlId = UUID.nameUUIDFromBytes(
                        (accountId + region + functionName + (qualifier != null ? qualifier : "")).getBytes())
                .toString()
                .replace("-", "")
                .substring(0, 32);
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

    /**
     * Aggregates the caller's stored {@code $LATEST} functions in a region: code-size usage,
     * function count, and the unreserved share of the configured concurrency limit.
     */
    public AccountSettings getAccountSettings(String region) {
        List<LambdaFunction> functions = functionStore.list(region);
        long totalCodeSize = 0;
        long reserved = 0;
        for (LambdaFunction fn : functions) {
            totalCodeSize += fn.getCodeSizeBytes();
            if (fn.getReservedConcurrentExecutions() != null) {
                reserved += fn.getReservedConcurrentExecutions();
            }
        }
        int concurrencyLimit = config != null
                ? config.services().lambda().regionConcurrencyLimit()
                : 1000;
        long unreserved = Math.max(0, concurrencyLimit - reserved);
        return new AccountSettings(totalCodeSize, functions.size(), concurrencyLimit, unreserved);
    }

    public record AccountSettings(long totalCodeSize, int functionCount,
                                  int concurrentExecutions, long unreservedConcurrentExecutions) {
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

    /** Package-private (not private) so tests can hold this lock to prove a critical section waits for it. */
    Object lockForConcurrencyOp(String functionArn) {
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

    /** Per-region bucket that mirrors AWS's Lambda code bucket naming. */
    public static String tasksBucketName(String region) {
        String r = (region == null || region.isBlank()) ? "us-east-1" : region;
        return "awslambda-" + r + "-tasks";
    }

    /**
     * The account that owns a function, for keying account-scoped state (its S3 code key,
     * its on-disk extraction directory, its PublishVersion counter). All three must agree,
     * so they all derive the account here.
     */
    static String ownerAccount(LambdaFunction fn) {
        return fn.getAccountId() != null ? fn.getAccountId() : "000000000000";
    }

    /** Stable, account-scoped S3 key for a function's current deployment package. */
    public static String codeObjectKey(LambdaFunction fn) {
        return "snapshots/" + ownerAccount(fn) + "/" + fn.getFunctionName();
    }

    /** Stable, account-scoped S3 key for a published layer version's archive. */
    public static String layerObjectKey(String accountId, String layerName, long version) {
        var account = accountId != null ? accountId : "000000000000";
        return "layers/" + account + "/" + layerName + "/" + version;
    }

    /** Percent-encodes each path segment of a bucket path or object key for use in a URL. */
    public static String encodeObjectPath(String path) {
        var encoded = new StringBuilder();
        for (var segment : path.split("/", -1)) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return encoded.toString();
    }

    private void extractZipCode(LambdaFunction fn, String zipFileBase64, String region) {
        extractZipCodeBytes(fn, Base64.getDecoder().decode(zipFileBase64), region);
    }

    private void extractZipCodeBytes(LambdaFunction fn, byte[] zipBytes, String region) {
        Path codePath = codeStore.getCodePath(ownerAccount(fn), fn.getFunctionName());
        try {
            zipExtractor.extractTo(zipBytes, codePath);
            // Publish the new code identity under the same per-function lock publishVersion holds.
            // PublishVersion's CodeSha256 precondition is a check-then-act: it compares the hash and
            // then snapshots the code. Mutating these fields without the lock lets an overlapping
            // deploy move $LATEST between those two steps, so a version would carry code whose hash
            // the caller never authorised even though the check passed. Extraction to disk stays
            // outside the lock; only the fields that decide what a snapshot copies are inside it.
            String newSha256 = null;
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(zipBytes);
                newSha256 = Base64.getEncoder().encodeToString(digest);
            } catch (java.security.NoSuchAlgorithmException ignored) {}
            synchronized (lockForConcurrencyOp(fn.getFunctionArn())) {
                fn.setCodeLocalPath(codePath.toAbsolutePath().normalize().toString());
                fn.setCodeSizeBytes(zipBytes.length);
                if (newSha256 != null) {
                    fn.setCodeSha256(newSha256);
                }
            }

            // For file-based runtimes, verify handler file exists (skip Java and .NET which use different handler formats)
            if (fn.getRuntime() != null && !fn.getRuntime().startsWith("java") && !fn.getRuntime().startsWith("dotnet")) {
                String handlerFile = resolveHandlerFilePath(fn);
                boolean pythonRuntime = fn.getRuntime().startsWith("python");
                boolean found;
                try (var walk = Files.walk(codePath)) {
                    found = walk
                            .filter(Files::isRegularFile)
                            .anyMatch(p -> {
                                String relative = codePath.relativize(p).toString();
                                String withoutExt = relative.contains(".")
                                        ? relative.substring(0, relative.lastIndexOf('.'))
                                        : relative;
                                String normalized = withoutExt.replace('\\', '/');
                                return normalized.equals(handlerFile)
                                        || (pythonRuntime && normalized.equals(handlerFile + "/__init__"));
                            });
                }
                if (!found) {
                    throw new AwsException("InvalidParameterValueException",
                            "Handler file '" + handlerFile + "' not found in deployment package", 400);
                }
            }

            // Deploy succeeded: keep the exact package so GetFunction can serve
            // a real Code.Location.
            storeDeploymentPackage(fn, zipBytes, region);
            // Reclaim a directory left behind by a function created before account-scoping;
            // codePath above is already the new location, so this is a no-op once migrated.
            // Locked against publishVersion (see its own comment) so a version cannot be
            // persisted, referencing this legacy path, in the window between the check below
            // and the actual delete.
            synchronized (lockForConcurrencyOp(fn.getFunctionArn())) {
                reclaimLegacyCodeDirectoryIfUnused(fn.getFunctionName());
            }
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("InvalidParameterValueException",
                    "Failed to extract deployment package: " + e.getMessage(), 400);
        }
    }

    private void storeDeploymentPackage(LambdaFunction fn, byte[] zipBytes, String region) {
        boolean stored = putTasksObjectQuietly(s3Service, region, codeObjectKey(fn), zipBytes,
                "deployment package for " + fn.getFunctionName());
        if (!stored && requiresStoredTasksObject(config)) {
            throw new AwsException("ServiceException",
                    "Could not store the deployment package for '" + fn.getFunctionName()
                            + "' in Floci's S3, which the kubernetes Lambda executor needs to "
                            + "launch pods. The function was not deployed.", 500);
        }
    }

    /**
     * Best-effort put into the per-region tasks bucket, shared by function-code and
     * layer-archive storage. Returns whether the object was stored so a caller whose
     * executor depends on it can fail loudly instead of silently succeeding.
     */
    static boolean putTasksObjectQuietly(S3Service s3Service, String region, String key,
                                         byte[] zipBytes, String what) {
        if (s3Service == null) {
            return false;
        }
        String bucket = tasksBucketName(region);
        try {
            try {
                s3Service.createBucket(bucket, region);
            } catch (AwsException e) {
                // createBucket is idempotent only in us-east-1; elsewhere it 409s if it exists.
                if (!"BucketAlreadyOwnedByYou".equals(e.getErrorCode())) {
                    throw e;
                }
            }
            s3Service.putObject(bucket, key, zipBytes, "application/zip", java.util.Map.of());
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not store {0}: {1}", what, e.getMessage());
            return false;
        }
    }

    /**
     * Whether the tasks-bucket copy is load-bearing for the active executor. The
     * kubernetes executor's pods download code and layers from it, so a store failure
     * must fail the deploy rather than surface later as broken cold starts.
     */
    static boolean requiresStoredTasksObject(EmulatorConfig config) {
        return config != null
                && "kubernetes".equalsIgnoreCase(config.services().lambda().executor().trim());
    }

    private void extractZipCodeFromS3(LambdaFunction fn, String s3Bucket, String s3Key, String region) {
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
        extractZipCodeBytes(fn, obj.getData(), region);
    }

    private String resolveHandlerFilePath(LambdaFunction fn) {
        String handler = fn.getHandler();
        int lastDot = handler.lastIndexOf('.');
        String modulePath = lastDot >= 0 ? handler.substring(0, lastDot) : handler;
        if (fn.getRuntime().startsWith("python")) {
            return modulePath.replace('.', '/');
        }
        // A file-based handler may be given with a leading "./" (e.g.
        // "./v1/lambda-handlers/entry.handler"); deployment-package entries are stored without
        // it, so normalize the prefix away before matching.
        if (modulePath.startsWith("./")) {
            modulePath = modulePath.substring(2);
        }
        return modulePath;
    }

    private void applyHotReload(LambdaFunction fn, String hostPath) {
        if (config == null || !config.services().lambda().hotReload().enabled()) {
            throw new AwsException("InvalidParameterValueException",
                    "Hot-reload is disabled. Set FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED=true to enable it.", 400);
        }
        if (hostPath == null || !hostPath.startsWith("/")) {
            throw new AwsException("InvalidParameterValueException",
                    "Hot-reload S3Key must be an absolute path on the Docker host, got: " + hostPath, 400);
        }
        config.services().lambda().hotReload().allowedPaths().ifPresent(allowed -> {
            if (allowed.stream().noneMatch(hostPath::startsWith)) {
                throw new AwsException("InvalidParameterValueException",
                        "Path '" + hostPath + "' is not under an allowed hot-reload mount prefix.", 400);
            }
        });
        fn.setHotReloadHostPath(hostPath);
        fn.setCodeLocalPath(null);
        fn.setS3Bucket(null);
        fn.setS3Key(null);
        fn.setCodeSizeBytes(0);
        fn.setCodeSha256("");
        LOG.infov("Hot-reload configured for function {0}: bind-mounting {1}", fn.getFunctionName(), hostPath);
    }

    // ──────────────────────────── Permissions (Policy) ────────────────────────────

    /**
     * The ARN a resource-policy statement is scoped to: the bare function ARN, or
     * {@code <functionArn>:<qualifier>} for an alias/version-scoped permission.
     *
     * <p>AWS keeps a SEPARATE resource policy per qualifier, so the qualifier is part of a
     * statement's identity — the same {@code StatementId} may exist on the function and on
     * each alias. We derive that identity from the statement's own {@code Resource} rather
     * than storing a parallel field, which keeps already-persisted (always unqualified)
     * statements readable as the function's own policy (#2124).
     */
    private static String policyResourceArn(LambdaFunction fn, String qualifier) {
        return qualifier == null ? fn.getFunctionArn() : fn.getFunctionArn() + ":" + qualifier;
    }

    private static boolean scopedTo(Map<String, Object> statement, String resourceArn) {
        return resourceArn.equals(statement.get("Resource"));
    }

    public Map<String, Object> addPermission(String region, String functionName, String qualifier, Map<String, Object> request) {
        LambdaArnUtils.ResolvedFunctionRef ref = resolveWithRegion(region, functionName, qualifier);
        LambdaFunction observed = getFunction(region, ref.name());
        synchronized (lockForPolicyMutation(observed.getFunctionArn())) {
            LambdaFunction fn = getFunction(region, ref.name());
            String resourceArn = policyResourceArn(fn, ref.qualifier());
            String statementId = (String) request.get("StatementId");
            if (statementId == null || statementId.isBlank()) {
                throw new AwsException("InvalidParameterValueException", "StatementId is required", 400);
            }
            fn.getPolicies().stream()
                    .filter(s -> scopedTo(s, resourceArn))
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
            statement.put("Resource", resourceArn);
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
    }

    public Map<String, Object> getPolicy(String region, String functionName, String qualifier) {
        LambdaArnUtils.ResolvedFunctionRef ref = resolveWithRegion(region, functionName, qualifier);
        LambdaFunction observed = getFunction(region, ref.name());
        synchronized (lockForPolicyMutation(observed.getFunctionArn())) {
            LambdaFunction fn = getFunction(region, ref.name());
            String resourceArn = policyResourceArn(fn, ref.qualifier());
            List<Map<String, Object>> statements = fn.getPolicies().stream()
                    .filter(statement -> scopedTo(statement, resourceArn))
                    .<Map<String, Object>>map(java.util.LinkedHashMap::new)
                    .toList();
            if (statements.isEmpty()) {
                throw new AwsException("ResourceNotFoundException",
                        "The resource you requested does not exist.", 404);
            }
            Map<String, Object> policy = new java.util.LinkedHashMap<>();
            policy.put("Version", "2012-10-17");
            policy.put("Id", "default");
            policy.put("Statement", statements);
            return Map.of("policy", policy, "revisionId", fn.getRevisionId());
        }
    }

    /**
     * Puts a previously captured policy statement back. Used to compensate when a replacement fails
     * after the original was removed: it takes the stored statement shape rather than an
     * AddPermission request, so what goes back is exactly what came out.
     *
     * <p>Scoped to the unqualified function, matching where the captured statement came from. A Sid
     * is unique only within one resource ARN, so matching on it alone would take out an
     * identically named statement on an alias or version and leave that one lost.
     */
    public void restorePermissionStatement(String region, String functionName, Map<String, Object> statement) {
        LambdaFunction observed = getFunction(region, functionName);
        synchronized (lockForPolicyMutation(observed.getFunctionArn())) {
            LambdaFunction fn = getFunction(region, functionName);
            String statementId = (String) statement.get("Sid");
            String resourceArn = policyResourceArn(fn, null);
            if (statementId != null) {
                fn.getPolicies().removeIf(existing -> statementId.equals(existing.get("Sid"))
                        && scopedTo(existing, resourceArn));
            }
            fn.getPolicies().add(statement);
            functionStore.save(region, fn);
            LOG.infov("Restored permission {0} on function {1}", statementId, functionName);
        }
    }

    public void removePermission(String region, String functionName, String qualifier, String statementId) {
        LambdaArnUtils.ResolvedFunctionRef ref = resolveWithRegion(region, functionName, qualifier);
        LambdaFunction observed = getFunction(region, ref.name());
        synchronized (lockForPolicyMutation(observed.getFunctionArn())) {
            LambdaFunction fn = getFunction(region, ref.name());
            String resourceArn = policyResourceArn(fn, ref.qualifier());
            boolean removed = fn.getPolicies()
                    .removeIf(statement -> statementId.equals(statement.get("Sid"))
                            && scopedTo(statement, resourceArn));
            if (!removed) {
                throw new AwsException("ResourceNotFoundException",
                        "Statement " + statementId + " not found in function " + functionName, 404);
            }
            functionStore.save(region, fn);
            LOG.infov("Removed permission {0} from function {1}", statementId, functionName);
        }
    }

    private Object lockForPolicyMutation(String functionArn) {
        return policyMutationLocks.computeIfAbsent(functionArn, key -> new Object());
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

    // -------------------------------------------------------------------------
    // EventInvokeConfig
    // -------------------------------------------------------------------------

    public FunctionEventInvokeConfig putEventInvokeConfig(String region, String functionName,
                                                          String qualifier, Map<String, Object> request) {
        LambdaFunction fn = getFunction(region, functionName);
        String key = eventInvokeKey(region, fn.getFunctionArn(), qualifier);
        FunctionEventInvokeConfig cfg = new FunctionEventInvokeConfig();
        cfg.setFunctionArn(qualifiedArn(fn.getFunctionArn(), qualifier));
        cfg.setLastModified(System.currentTimeMillis());
        applyEventInvokeRequest(cfg, request, true);
        eventInvokeConfigs.put(key, cfg);
        return cfg;
    }

    public FunctionEventInvokeConfig updateEventInvokeConfig(String region, String functionName,
                                                             String qualifier, Map<String, Object> request) {
        LambdaFunction fn = getFunction(region, functionName);
        String key = eventInvokeKey(region, fn.getFunctionArn(), qualifier);
        FunctionEventInvokeConfig existing = eventInvokeConfigs.get(key);
        if (existing == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The function " + fn.getFunctionArn() + " doesn't have an EventInvokeConfig", 404);
        }
        applyEventInvokeRequest(existing, request, false);
        existing.setLastModified(System.currentTimeMillis());
        // Re-put so StorageBackedMap routes the mutation through the backend
        eventInvokeConfigs.put(key, existing);
        return existing;
    }

    public FunctionEventInvokeConfig getEventInvokeConfig(String region, String functionName, String qualifier) {
        LambdaFunction fn = getFunction(region, functionName);
        String key = eventInvokeKey(region, fn.getFunctionArn(), qualifier);
        FunctionEventInvokeConfig cfg = eventInvokeConfigs.get(key);
        if (cfg == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The function " + fn.getFunctionArn() + " doesn't have an EventInvokeConfig", 404);
        }
        return cfg;
    }

    public void deleteEventInvokeConfig(String region, String functionName, String qualifier) {
        LambdaFunction fn = getFunction(region, functionName);
        String key = eventInvokeKey(region, fn.getFunctionArn(), qualifier);
        if (eventInvokeConfigs.remove(key) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The function " + fn.getFunctionArn() + " doesn't have an EventInvokeConfig", 404);
        }
    }

    public List<FunctionEventInvokeConfig> listEventInvokeConfigs(String region, String functionName) {
        LambdaFunction fn = getFunction(region, functionName);
        String prefix = region + ":" + fn.getFunctionArn() + ":";
        List<FunctionEventInvokeConfig> result = new ArrayList<>();
        for (Map.Entry<String, FunctionEventInvokeConfig> entry : eventInvokeConfigs.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    private String eventInvokeKey(String region, String functionArn, String qualifier) {
        return region + ":" + functionArn + ":" + (qualifier != null ? qualifier : "$LATEST");
    }

    private String qualifiedArn(String functionArn, String qualifier) {
        if (qualifier == null || qualifier.isBlank() || "$LATEST".equals(qualifier)) {
            return functionArn + ":$LATEST";
        }
        return functionArn + ":" + qualifier;
    }

    @SuppressWarnings("unchecked")
    private void applyEventInvokeRequest(FunctionEventInvokeConfig cfg, Map<String, Object> request, boolean replace) {
        if (replace || request.containsKey("MaximumRetryAttempts")) {
            Object raw = request.get("MaximumRetryAttempts");
            cfg.setMaximumRetryAttempts(raw instanceof Number ? ((Number) raw).intValue() : null);
        }
        if (replace || request.containsKey("MaximumEventAgeInSeconds")) {
            Object raw = request.get("MaximumEventAgeInSeconds");
            cfg.setMaximumEventAgeInSeconds(raw instanceof Number ? ((Number) raw).intValue() : null);
        }
        if (replace || request.containsKey("DestinationConfig")) {
            Map<String, Object> destMap = (Map<String, Object>) request.get("DestinationConfig");
            if (destMap != null) {
                FunctionEventInvokeConfig.DestinationConfig dest = new FunctionEventInvokeConfig.DestinationConfig();
                Map<String, Object> onSuccess = (Map<String, Object>) destMap.get("OnSuccess");
                if (onSuccess != null) {
                    dest.setOnSuccess(new FunctionEventInvokeConfig.Destination((String) onSuccess.get("Destination")));
                }
                Map<String, Object> onFailure = (Map<String, Object>) destMap.get("OnFailure");
                if (onFailure != null) {
                    dest.setOnFailure(new FunctionEventInvokeConfig.Destination((String) onFailure.get("Destination")));
                }
                cfg.setDestinationConfig(dest);
            } else if (replace) {
                cfg.setDestinationConfig(null);
            }
        }
    }

    /**
     * Observes S3 object updates and triggers reactive sync for any Lambda
     * functions linked to the updated object.
     */
    public void onS3ObjectUpdated(@Observes S3ObjectUpdatedEvent event) {
        LOG.debugv("Observing S3 update: {0}/{1}", event.bucketName(), event.key());
        // For simplicity, we scan all functions in the default region
        // Most local dev setups use a single region.
        // This runs on the CDI event thread with no RequestContext, so an ambient scan
        // would only ever see the default account's partition — a function owned by any
        // other account would silently never hot-reload from S3.
        String region = regionResolver.getDefaultRegion();
        List<LambdaFunction> functions = functionStore.listAllAccounts(region);
        for (LambdaFunction fn : functions) {
            if (fn.isHotReload()) {
                continue;
            }
            if (event.bucketName().equals(fn.getS3Bucket()) && event.key().equals(fn.getS3Key())) {
                LOG.infov("Reactive S3 Sync: updating function {0} from s3://{1}/{2}",
                        fn.getFunctionName(), event.bucketName(), event.key());
                try {
                    S3Object obj = s3Service.getObject(event.bucketName(), event.key());
                    extractZipCodeBytes(fn, obj.getData(), region);
                    fn.setLastModified(Instant.now().toEpochMilli());
                    fn.setRevisionId(UUID.randomUUID().toString());
                    functionStore.saveForAccount(ownerAccount(fn), region, fn);

                    // Push to warm workers
                    warmPool.pushCodeUpdate(fn);
                } catch (Exception e) {
                    LOG.warnv("Failed reactive sync for function {0}: {1}", fn.getFunctionName(), e.getMessage());
                }
            }
        }
    }
}
