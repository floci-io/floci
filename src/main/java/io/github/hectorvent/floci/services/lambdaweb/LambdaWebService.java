package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages.PolicyType;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.PublicAccessConditions;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Control plane of Lambda Web Functions (apiVersion 2025-03-07). A revision is stored Active, or
 * Failed when its code is not a zip or unzips past the size limit. An endpoint is stored Active, or
 * Failed when CreateWebFunction gives it a Failed revision, and an endpoint update is stored
 * Successful. The create and update responses show the transitional state AWS reports. Invocation
 * through an endpoint domain is not part of this service.
 */
@ApplicationScoped
public class LambdaWebService implements Resettable {

    private static final Logger LOG = Logger.getLogger(LambdaWebService.class);

    static final String SUPPORTED_RUNTIME = "nodejs24.x";
    static final int MAX_ENDPOINTS_PER_FUNCTION = 10;
    /** Reported as the quota. AWS deletes unused revisions past it instead of rejecting a create. */
    static final int MAX_REVISIONS_PER_FUNCTION = 50;
    static final int ACCOUNT_MAX_ARM_VCPUS = 2000;
    static final int ACCOUNT_MAX_RATE_LIMIT = 100000;
    static final List<Integer> SUPPORTED_RATE_LIMITS = List.of(
            0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000,
            2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000);
    static final List<String> AUTH_TYPES = List.of("ApplicationManaged", "IamAuth");
    static final List<String> ENDPOINT_TYPES = List.of("HomeRegion", "MultiRegion", "PerRegion");
    static final List<String> AUTO_DEPLOYMENT_MODES = List.of("LatestRevision", "Disabled");
    static final List<String> APPLICATION_LOG_LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");
    static final List<String> SYSTEM_LOG_LEVELS = List.of("DEBUG", "INFO", "WARN");
    static final Set<String> RESERVED_ENVIRONMENT_KEYS = Set.of(
            "AWS_ACCESS_KEY", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SECRET_KEY",
            "AWS_SECURITY_TOKEN", "AWS_SESSION_TOKEN", "AWS_DEFAULT_REGION", "AWS_REGION",
            "AWS_LAMBDA_HTTP_ENDPOINT", "AWS_LAMBDA_RUNTIME_API", "AWS_LAMBDA_INITIALIZATION_TYPE",
            "AWS_LAMBDA_FUNCTION_MEMORY_SIZE", "AWS_LAMBDA_METADATA_API", "AWS_LAMBDA_METADATA_TOKEN");
    /** A wildcard principal pinned by one of these condition keys does not make a policy public. */
    static final Set<String> TRUSTED_CONDITION_KEYS =
            PublicAccessConditions.with(PublicAccessConditions.TRUSTED_KEYS, "lambda:SourceFunctionArn");
    /** The operators whose match pins the caller. Negated and IfExists forms do not. */
    private static final Set<String> PINNING_OPERATORS = Set.of(
            "StringEquals", "StringEqualsIgnoreCase", "StringLike", "ArnEquals", "ArnLike", "Bool");
    private static final Set<String> ARN_CONDITION_KEYS =
            PublicAccessConditions.with(PublicAccessConditions.ARN_KEYS, "lambda:SourceFunctionArn");
    private static final Set<String> STATEMENT_KEYS = Set.of(
            "Sid", "Effect", "Principal", "NotPrincipal", "Action", "NotAction", "Resource", "NotResource", "Condition");
    private static final Set<String> PRINCIPAL_KEYS = Set.of("AWS", "Service", "Federated", "CanonicalUser");
    private static final String POLICY_SYNTAX = "Policy has syntax errors.";
    private static final String INVALID_PRINCIPAL = "The provided principal was invalid. Please check the principal and try again.";
    private static final String NOT_A_ZIP = "Failed to read zip file: zip: not a valid zip file";
    private static final String INVALID_TAGS = "One or more of your tags are invalid. Please ensure none of your tag keys"
            + " start with \"aws:\", none are empty, and there are no duplicate tag keys.";

    private static final Pattern FUNCTION_NAME = Pattern.compile("(?![-_])[a-zA-Z0-9_-]{2,64}(?<![-_])");
    private static final Pattern REVISION_ID = Pattern.compile("rev-[a-z0-9]{5}");
    private static final Pattern WEB_FUNCTION_ARN = Pattern.compile(
            "arn:[^:]+:lambda:([^:]*):([0-9]{12}):web-function/([^/]+)(?:/(revision|endpoint)/([^/]+))?");
    static final Pattern RESOURCE_ARN = Pattern.compile("arn:[^:]+:lambda:[^:]+:\\d{12}:web-function/[^/]+");
    static final Pattern ROLE_ARN = Pattern.compile(
            "arn:(" + AwsArnUtils.PARTITION_REGEX + "):iam::[0-9]{12}:role/[a-zA-Z0-9+=,.@_/-]+");
    static final Pattern KMS_KEY_ARN = Pattern.compile(
            "arn:(" + AwsArnUtils.PARTITION_REGEX + "):kms:[a-z0-9-]+:[0-9]{12}:key/[a-z0-9-]+");
    /** The model's pattern, which AWS quotes. An unknown partition passes it and fails a later check. */
    static final Pattern NETWORK_CONNECTOR_ARN = Pattern.compile(
            "arn:(aws[a-z-]*):lambda:[a-z0-9-]+:[0-9]{12}:network-connector:[a-zA-Z0-9_-]+:[0-9]+");
    private static final Pattern CONNECTOR_VERSION = Pattern.compile("[1-9][0-9]*");
    static final Pattern S3_BUCKET = Pattern.compile("([a-z0-9][a-z0-9\\.\\-]{1,61}[a-z0-9])");
    static final Pattern RUNTIME = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z0-9]+)*");
    static final Pattern ENVIRONMENT_KEY = Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*");
    static final Pattern LOG_GROUP = Pattern.compile("[a-zA-Z0-9\\.\\-_/#]+");
    static final Pattern REGION = Pattern.compile("([a-z]{2,}-){2,}[0-9]");
    static final Pattern TAG_KEY = Pattern.compile("[a-zA-Z0-9 _\\.:/=+\\-@]+");
    static final Pattern TAG_VALUE = Pattern.compile("[a-zA-Z0-9 _\\.:/=+\\-@]*");
    private static final String TOKEN_MARKER = "lambda-web|";
    static final Pattern POLICY_REVISION_ID = Pattern.compile("[0-9a-f-]{36}");
    private static final Pattern FILTER_NAME = Pattern.compile("[a-zA-Z0-9]+");
    private static final Pattern FILTER_VALUE = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final Pattern PRINCIPAL_AWS = Pattern.compile("[\\w+=,.@\\:-]*");
    private static final Pattern PRINCIPAL_AWS_ARN = Pattern.compile("[\\w+=/:,.@-]*");
    private static final Pattern PRINCIPAL_SERVICE = Pattern.compile("[\\w+=,.@-]*");

    /** AWS assigns one shard label per cell. */
    private static final String DOMAIN_SHARD = "001";
    private static final int DOMAIN_ID_LENGTH = 16;
    private static final int DEFAULT_PAGE_SIZE = 50;
    static final int REVISION_CLEANUP_TARGET = 50;
    private static final String LOWERCASE_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final RegionResolver regionResolver;
    private final StorageFactory storageFactory;
    private final EmulatorConfig config;
    private final S3Service s3Service;
    private final LambdaWebCode code;
    private final ObjectMapper objectMapper;
    private final Instance<RequestContext> requestContext;
    private final LambdaWebExecutionRole executionRole;
    private final ServiceCallAuthorizer serviceCalls;
    private final LambdaMicrovmsService microvmsService;

    /** Keyed {@code region/functionName}. The backend adds the caller's account to every key. */
    private StorageBackend<String, WebFunction> functions;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** Every endpoint domain, filled from storage at start and kept by the create and delete paths. */
    private final ConcurrentHashMap<String, EndpointLocator> domains = new ConcurrentHashMap<>();
    /** Each endpoint domain keyed by its host as a CloudFront origin names it. */
    private final ConcurrentHashMap<String, String> originHosts = new ConcurrentHashMap<>();

    @Inject
    public LambdaWebService(RegionResolver regionResolver, StorageFactory storageFactory, EmulatorConfig config,
                            S3Service s3Service, LambdaWebCode code, ObjectMapper objectMapper,
                            Instance<RequestContext> requestContext, LambdaWebExecutionRole executionRole,
                            ServiceCallAuthorizer serviceCalls, LambdaMicrovmsService microvmsService) {
        this.regionResolver = regionResolver;
        this.storageFactory = storageFactory;
        this.config = config;
        this.s3Service = s3Service;
        this.code = code;
        this.objectMapper = objectMapper;
        this.requestContext = requestContext;
        this.executionRole = executionRole;
        this.serviceCalls = serviceCalls;
        this.microvmsService = microvmsService;
    }

    @PostConstruct
    void init() {
        initializeStorage();
    }

    void initializeStorage() {
        this.functions = storageFactory.create("lambda", "lambda-web-functions.json",
                new TypeReference<Map<String, WebFunction>>() {});
        domains.clear();
        originHosts.clear();
        List<WebFunction> all = functions instanceof AccountAwareStorageBackend<WebFunction> aware
                ? aware.scanAllAccounts()
                : functions.scan(key -> true);
        for (WebFunction fn : all) {
            fn.endpoints.values().forEach(endpoint -> indexDomains(fn, endpoint));
        }
        code.retainOnly(all);
    }

    @Override
    public void clear() {
        functions.clear();
        domains.clear();
        originHosts.clear();
        code.clear();
    }

    /**
     * Where a request to an endpoint domain goes. Domains are unique across accounts and regions.
     * {@code region} is the function's home region and {@code servingRegion} the region of the domain.
     */
    public record WebFunctionTarget(String accountId, String region, WebFunction function, WebFunctionEndpoint endpoint,
                                    String servingRegion) {
    }

    private record EndpointLocator(String accountId, String region, String functionName, String endpointName,
                                   String servingRegion) {
    }

    public Optional<WebFunctionTarget> locate(String host) {
        EndpointLocator locator = domains.get(host);
        if (locator == null) {
            return Optional.empty();
        }
        return RequestScopes.callAs(locator.accountId(), locator.region(), () -> {
            synchronized (lockFor(locator.region(), locator.functionName())) {
                Optional<WebFunction> stored = functions.get(key(locator.region(), locator.functionName()));
                if (stored.isEmpty() || !stored.get().endpoints.containsKey(locator.endpointName())) {
                    domains.remove(host, locator);
                    originHosts.remove(originHost(host), host);
                    return Optional.empty();
                }
                WebFunction fn = copy(stored.get(), WebFunction.class);
                return Optional.of(new WebFunctionTarget(locator.accountId(), locator.region(), fn,
                        fn.endpoints.get(locator.endpointName()), locator.servingRegion()));
            }
        });
    }

    /**
     * The endpoint domain that a CloudFront origin names by {@code host}, which is lowercase with no
     * trailing dot or port. The domain Floci mints keeps the authority of its base URL.
     */
    public Optional<String> endpointDomain(String host) {
        return Optional.ofNullable(originHosts.get(host));
    }

    /**
     * AWS serves the MultiRegion global domain in the region nearest the caller. Floci takes the default region for
     * that when the endpoint has it, and the home region otherwise.
     */
    private void indexDomains(WebFunction fn, WebFunctionEndpoint endpoint) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(fn.functionArn);
        String defaultRegion = regionResolver.getDefaultRegion();
        String servingRegion = "MultiRegion".equals(endpoint.endpointType) && endpoint.regions.contains(defaultRegion)
                ? defaultRegion : arn.region();
        index(endpoint.domainName, new EndpointLocator(arn.accountId(), arn.region(), fn.functionName,
                endpoint.endpointName, servingRegion));
        endpoint.regionalDomainNames.forEach((region, domain) -> index(domain, new EndpointLocator(arn.accountId(),
                arn.region(), fn.functionName, endpoint.endpointName, region)));
    }

    private void index(String domain, EndpointLocator locator) {
        domains.put(domain, locator);
        originHosts.put(originHost(domain), domain);
    }

    private void unindexDomains(WebFunctionEndpoint endpoint) {
        unindex(endpoint.domainName);
        endpoint.regionalDomainNames.values().forEach(this::unindex);
    }

    private void unindex(String domain) {
        domains.remove(domain);
        originHosts.remove(originHost(domain), domain);
    }

    private static String originHost(String domain) {
        return domain.replaceFirst("\\.*(:\\d+)?$", "").toLowerCase(Locale.ROOT);
    }

    /** The revision a request goes to, drawn by the endpoint's weights. */
    public static WebFunctionRevision pickRevision(WebFunctionTarget target) {
        List<WebFunctionEndpoint.RevisionWeight> weights = target.endpoint().revisionWeights;
        if (weights.isEmpty()) {
            return null;
        }
        int draw = ThreadLocalRandom.current().nextInt(100);
        int cumulative = 0;
        for (WebFunctionEndpoint.RevisionWeight weight : weights) {
            cumulative += weight.weight;
            if (draw < cumulative) {
                return target.function().revisions.get(weight.revisionId);
            }
        }
        return target.function().revisions.get(weights.get(weights.size() - 1).revisionId);
    }

    /** An endpoint before and after an update. The update response shows both. */
    public record EndpointUpdate(WebFunctionEndpoint before, WebFunctionEndpoint after) {
    }

    public record ResourcePolicy(String policy, String revisionId) {
    }

    public record AccountSettings(int functionCount) {
    }

    // ---------------------------------------------------------------- functions

    public WebFunction createFunction(String region, JsonNode request) {
        String functionName = LambdaWebJson.text(request, "functionName");
        JsonNode revisionConfig = LambdaWebJson.structure(request, "revisionConfig");
        JsonNode endpointConfig = LambdaWebJson.structure(request, "endpointConfig");
        Map<String, String> tags = LambdaWebJson.stringMap(request, "tags", true);
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.required("functionName", functionName);
        if (revisionConfig != null) {
            validateRevisionShape(validation, revisionConfig, "revisionConfig.");
        }
        if (endpointConfig != null) {
            validateEndpointShape(validation, endpointConfig, "endpointConfig.", true);
        }
        validateTagsShape(validation, "tags", tags);
        validation.throwIfAny();

        requireFunctionName(functionName);
        if (revisionConfig == null || endpointConfig == null) {
            throw new AwsException("ValidationException",
                    "The revisionConfig and endpointConfig must be specified.", 400);
        }
        requireValidTags(tags);
        validateRevisionRules(region, revisionConfig);
        String accountId = regionResolver.getAccountId();
        WebFunctionEndpoint endpoint = draftEndpoint(region, accountId, functionName, endpointConfig, false);
        if (connectorArn(revisionConfig) != null && !"HomeRegion".equals(endpoint.endpointType)) {
            throw new AwsException("ValidationException", "The endpointType " + endpoint.endpointType
                    + " cannot be used with a revision that has an egressNetworkConnectorArn specified."
                    + " Only the endpointType HomeRegion supports such revisions.", 400);
        }
        return storeNewFunction(region, functionName, tags, fn -> {
            requireUsableConnector(region, revisionConfig);
            WebFunctionRevision revision = buildRevision(fn, revisionConfig);
            keepCode(region, fn, revision);
            attachEndpoint(region, fn, endpoint, revision);
            endpoint.failed = revision.failureReason != null;
            fn.revisions.put(revision.revisionId, revision);
            fn.endpoints.put(endpoint.endpointName, endpoint);
            indexDomains(fn, endpoint);
            LOG.infov("Created web function {0} with revision {1} and endpoint {2}",
                    functionName, revision.revisionId, endpoint.endpointName);
        });
    }

    /** A function with no revision and no endpoint, for AWS::Lambda::WebFunction. */
    public WebFunction createFunctionShell(String region, String functionName, Map<String, String> tags) {
        requireFunctionName(functionName);
        LambdaWebValidation validation = new LambdaWebValidation();
        validateTagsShape(validation, "tags", tags);
        validation.throwIfAny();
        requireValidTags(tags);
        return storeNewFunction(region, functionName, tags, fn -> { });
    }

    private static void requireFunctionName(String functionName) {
        if (functionName == null || !FUNCTION_NAME.matcher(functionName).matches()) {
            throw invalidFunctionName(functionName);
        }
    }

    /**
     * Stores a new function under its lock, refusing a name the account already has in any region.
     * Creates of one name in every region of the account share a lock, so two regions cannot both take it.
     */
    private WebFunction storeNewFunction(String region, String functionName, Map<String, String> tags,
                                         Consumer<WebFunction> populate) {
        String accountId = regionResolver.getAccountId();
        synchronized (locks.computeIfAbsent(accountId + "/" + functionName, k -> new Object())) {
            synchronized (lockFor(region, functionName)) {
                if (functions.get(key(region, functionName)).isPresent()) {
                    throw new AwsException("ConflictException",
                            "The function " + functionArn(region, accountId, functionName) + " already exists.", 409);
                }
                if (!functions.scan(k -> k.endsWith("/" + functionName)).isEmpty()) {
                    throw new AwsException("ConflictException", "A function named " + functionName
                            + " already exists in the account. Function names must be unique across all regions"
                            + " in an account.", 409);
                }
                WebFunction fn = newFunction(region, accountId, functionName, tags == null ? Map.of() : tags);
                populate.accept(fn);
                functions.put(key(region, functionName), fn);
                return copy(fn, WebFunction.class);
            }
        }
    }

    private WebFunction newFunction(String region, String accountId, String functionName, Map<String, String> tags) {
        WebFunction fn = new WebFunction();
        fn.functionName = functionName;
        fn.functionArn = functionArn(region, accountId, functionName);
        fn.createdAt = now();
        fn.updatedAt = fn.createdAt;
        fn.tags = new LinkedHashMap<>(tags);
        return fn;
    }

    public WebFunction getFunction(String region, String functionNameOrArn) {
        String functionName = resolveFunctionName(region, functionNameOrArn, "GetWebFunction");
        synchronized (lockFor(region, functionName)) {
            return copy(function(region, functionName), WebFunction.class);
        }
    }

    public PaginatedResult<WebFunction> listFunctions(String region, JsonNode request) {
        ListRequest list = parseListRequest(request);
        List<WebFunction> snapshots = in(region).stream()
                .map(fn -> {
                    synchronized (lockFor(region, fn.functionName)) {
                        return copy(fn, WebFunction.class);
                    }
                })
                .toList();
        return page(list.matchesState("Active") ? snapshots : List.of(), list,
                scope("ListWebFunctions", region, null, list), f -> f.functionName);
    }

    public void deleteFunction(String region, String functionNameOrArn) {
        String functionName = resolveFunctionName(region, functionNameOrArn, "DeleteWebFunction");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            fn.endpoints.values().forEach(this::unindexDomains);
            functions.delete(key(region, functionName));
            code.deleteFunction(AwsArnUtils.parse(fn.functionArn).accountId(), region, functionName);
        }
        LOG.infov("Deleted web function {0}", functionName);
    }

    public AccountSettings accountSettings(String region) {
        String prefix = region + "/";
        return new AccountSettings((int) functions.keys().stream().filter(k -> k.startsWith(prefix)).count());
    }

    // ---------------------------------------------------------------- revisions

    public WebFunctionRevision createRevision(String region, String functionNameOrArn, JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validateRevisionShape(validation, request, "");
        validation.throwIfAny();
        String functionName = resolveFunctionName(region, functionNameOrArn, "CreateWebFunctionRevision");
        validateRevisionRules(region, request);
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            requireUsableConnector(region, request);
            WebFunctionRevision revision = buildRevision(fn, request);
            keepCode(region, fn, revision);
            fn.revisions.put(revision.revisionId, revision);
            // Before the endpoints move, as AWS keeps the revision a LatestRevision endpoint leaves.
            List<String> cleanedUp = revision.failureReason == null ? cleanUpRevisions(fn, revision) : List.of();
            for (WebFunctionEndpoint endpoint : fn.endpoints.values()) {
                if ("LatestRevision".equals(endpoint.autoDeploymentMode) && revision.failureReason == null) {
                    endpoint.revisionWeights = List.of(weight(revision.revisionId, 100));
                    endpoint.updatedAt = now();
                }
            }
            fn.updatedAt = now();
            persist(region, fn);
            String accountId = AwsArnUtils.parse(fn.functionArn).accountId();
            cleanedUp.forEach(id -> code.deleteRevision(accountId, region, functionName, id));
            return copy(revision, WebFunctionRevision.class);
        }
    }

    /**
     * AWS deletes the oldest revisions that no endpoint uses, Failed ones too, once one becomes Active
     * while the function has more than 50.
     */
    private static List<String> cleanUpRevisions(WebFunction fn, WebFunctionRevision activated) {
        List<WebFunctionRevision> unused = fn.revisions.values().stream()
                .filter(r -> r != activated && endpointsUsing(fn, r.revisionId).isEmpty())
                .sorted(Comparator.comparing((WebFunctionRevision r) -> r.createdAt))
                .toList();
        List<String> deleted = new ArrayList<>();
        for (WebFunctionRevision old : unused) {
            if (fn.revisions.size() <= REVISION_CLEANUP_TARGET) {
                break;
            }
            fn.revisions.remove(old.revisionId);
            deleted.add(old.revisionId);
        }
        return deleted;
    }

    private static List<String> endpointsUsing(WebFunction fn, String revisionId) {
        return fn.endpoints.values().stream()
                .filter(e -> e.revisionWeights.stream().anyMatch(w -> w.revisionId.equals(revisionId)))
                .map(e -> e.endpointName)
                .sorted()
                .toList();
    }

    public WebFunctionRevision getRevision(String region, String functionNameOrArn, String revisionIdOrArn) {
        String functionName = resolveFunctionName(region, functionNameOrArn, "GetWebFunctionRevision");
        String revisionId = resolveRevisionId(region, revisionIdOrArn, "GetWebFunctionRevision");
        synchronized (lockFor(region, functionName)) {
            return copy(revision(function(region, functionName), revisionId), WebFunctionRevision.class);
        }
    }

    public PaginatedResult<WebFunctionRevision> listRevisions(String region, String functionNameOrArn,
                                                              JsonNode request) {
        ListRequest list = parseListRequest(request);
        String functionName = resolveFunctionName(region, functionNameOrArn, "ListWebFunctionRevisions");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            List<WebFunctionRevision> snapshots = fn.revisions.values().stream()
                    .filter(r -> list.matchesState(r.failureReason == null ? "Active" : "Failed"))
                    .map(r -> copy(r, WebFunctionRevision.class))
                    .toList();
            return page(snapshots, list,
                    scope("ListWebFunctionRevisions", region, functionName, list), LambdaWebService::newestFirst);
        }
    }

    /** A cursor that sorts revisions newest first. */
    private static String newestFirst(WebFunctionRevision revision) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, revision.createdAt);
        return String.format(Locale.ROOT, "%019d", Long.MAX_VALUE - micros) + " " + revision.revisionId;
    }

    public void deleteRevision(String region, String functionNameOrArn, String revisionIdOrArn) {
        String functionName = resolveFunctionName(region, functionNameOrArn, "DeleteWebFunctionRevision");
        String revisionId = resolveRevisionId(region, revisionIdOrArn, "DeleteWebFunctionRevision");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            WebFunctionRevision revision = revision(fn, revisionId);
            List<String> using = endpointsUsing(fn, revision.revisionId);
            if (!using.isEmpty()) {
                throw new AwsException("ConflictException", "The revision " + revision.revisionId
                        + " cannot be deleted because the endpoints " + using
                        + " are using it. Update the endpoints to use other revisions first.", 409);
            }
            fn.revisions.remove(revision.revisionId);
            fn.updatedAt = now();
            persist(region, fn);
            code.deleteRevision(AwsArnUtils.parse(fn.functionArn).accountId(), region, functionName, revisionId);
        }
    }

    /** Whether a revision already carries the configuration a request describes. */
    public boolean revisionMatches(WebFunctionRevision existing, JsonNode request) {
        WebFunctionRevision desired = new WebFunctionRevision();
        desired.description = LambdaWebJson.text(request, "description");
        desired.kmsKeyArn = LambdaWebJson.text(request, "kmsKeyArn");
        desired.buildConfig = buildConfigOf(LambdaWebJson.structure(request, "buildConfig"));
        String functionName = existing.functionArn.substring(existing.functionArn.lastIndexOf('/') + 1);
        desired.serviceConfig = serviceConfigOf(LambdaWebJson.structure(request, "serviceConfig"),
                defaultLogGroup(functionName));
        return objectMapper.valueToTree(configOnly(existing)).equals(objectMapper.valueToTree(configOnly(desired)));
    }

    private static String defaultLogGroup(String functionName) {
        return "/aws/lambda/web/" + functionName;
    }

    private static WebFunctionRevision configOnly(WebFunctionRevision revision) {
        WebFunctionRevision copy = new WebFunctionRevision();
        copy.description = revision.description;
        copy.kmsKeyArn = revision.kmsKeyArn;
        copy.buildConfig = revision.buildConfig;
        copy.serviceConfig = revision.serviceConfig;
        return copy;
    }

    private static WebFunctionRevision revision(WebFunction fn, String revisionId) {
        WebFunctionRevision revision = fn.revisions.get(revisionId);
        if (revision == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The revision " + revisionId + " does not exist.", 404);
        }
        return revision;
    }

    /** The rules a revision request must meet before the function is looked up. */
    private void validateRevisionRules(String region, JsonNode request) {
        JsonNode service = LambdaWebJson.structure(request, "serviceConfig");
        String role = LambdaWebJson.text(service, "executionRoleArn");
        if (!AwsArnUtils.parse(role).accountId().equals(regionResolver.getAccountId())) {
            throw new LambdaWebAccessDeniedException("Cross-account pass role is not allowed.");
        }
        String connectorArn = connectorArn(request);
        if (connectorArn != null) {
            requireValidConnectorArn(connectorArn);
            if (!AwsArnUtils.parse(connectorArn).accountId().equals(regionResolver.getAccountId())) {
                throw new LambdaWebAccessDeniedException("Cross-account pass lambda-network-connector is not allowed.");
            }
        }
        Map<String, String> environment = LambdaWebJson.stringMap(service, "environmentVariables", false);
        if (environment != null) {
            List<String> reserved = environment.keySet().stream().filter(RESERVED_ENVIRONMENT_KEYS::contains).toList();
            if (!reserved.isEmpty()) {
                throw new AwsException("ValidationException", "The environmentVariables cannot contain reserved keys."
                        + " The request specified the reserved keys " + reserved + ".", 400);
            }
        }
        String logGroup = LambdaWebJson.text(loggingConfig(service), "logGroup");
        if (logGroup != null && logGroup.toLowerCase(Locale.ROOT).startsWith("aws/")) {
            throw new AwsException("ValidationException", "The logGroup " + logGroup
                    + " is invalid. Log group names starting with aws/ (case-insensitive) are reserved for AWS.", 400);
        }
        executionRole.requireAssumable(region, role);
    }

    private static String connectorArn(JsonNode revisionRequest) {
        return LambdaWebJson.text(LambdaWebJson.structure(revisionRequest, "serviceConfig"), "egressNetworkConnectorArn");
    }

    /** AWS checks the region, then the partition, then the version. */
    private static void requireValidConnectorArn(String connectorArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(connectorArn);
        String version = connectorArn.substring(connectorArn.lastIndexOf(':') + 1);
        if (!AwsRegions.isRegionId(arn.region())) {
            throw invalidConnectorArn(connectorArn, "Invalid region.: " + arn.region());
        }
        if (!AwsRegions.partitionFor(arn.region()).equals(arn.partition())) {
            throw invalidConnectorArn(connectorArn, "Invalid arn partition.: " + arn.partition());
        }
        if (!CONNECTOR_VERSION.matcher(version).matches()) {
            throw invalidConnectorArn(connectorArn, "Invalid network connector version format - must be numeric: "
                    + version);
        }
    }

    private static AwsException invalidConnectorArn(String connectorArn, String reason) {
        return new AwsException("ValidationException",
                "The egress network connector ARN: '" + connectorArn + "' is not valid. " + reason, 400);
    }

    /** AWS looks the connector up once it knows the function, by its id in the function's region. */
    private void requireUsableConnector(String region, JsonNode revisionRequest) {
        String connectorArn = connectorArn(revisionRequest);
        if (connectorArn == null) {
            return;
        }
        int colon = connectorArn.lastIndexOf(':');
        long version;
        try {
            version = Long.parseLong(connectorArn.substring(colon + 1));
        } catch (NumberFormatException e) {
            // AWS fails internally on a version past the 64-bit range.
            throw new LambdaWebInternalServerException();
        }
        LambdaMicrovmsService.NetworkConnectorVersion named = microvmsService
                .connectorVersion(region, connectorArn.substring(0, colon), version)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The egress network connector " + connectorArn + " does not exist.", 404));
        if (!named.associatedComputeResourceTypes.contains("WebFunction")) {
            throw new AwsException("ValidationException", "The egress network connector " + connectorArn
                    + " cannot be used because its associatedComputeResourceTypes do not include WebFunction.", 400);
        }
    }

    private WebFunctionRevision buildRevision(WebFunction fn, JsonNode request) {
        WebFunctionRevision revision = new WebFunctionRevision();
        revision.buildConfig = buildConfigOf(LambdaWebJson.structure(request, "buildConfig"));
        if (!SUPPORTED_RUNTIME.equals(revision.buildConfig.runtime)) {
            throw new AwsException("ValidationException",
                    "The runtime " + revision.buildConfig.runtime + " is invalid.", 400);
        }
        revision.serviceConfig = serviceConfigOf(LambdaWebJson.structure(request, "serviceConfig"),
                defaultLogGroup(fn.functionName));
        revision.description = LambdaWebJson.text(request, "description");
        revision.kmsKeyArn = LambdaWebJson.text(request, "kmsKeyArn");
        String revisionId;
        do {
            revisionId = "rev-" + randomLowercase(5);
        } while (fn.revisions.containsKey(revisionId));
        revision.revisionId = revisionId;
        revision.functionArn = fn.functionArn;
        revision.revisionArn = fn.functionArn + "/revision/" + revisionId;
        revision.createdAt = now();
        return revision;
    }

    private static WebFunctionRevision.BuildConfig buildConfigOf(JsonNode build) {
        WebFunctionRevision.BuildConfig out = new WebFunctionRevision.BuildConfig();
        JsonNode s3Object = LambdaWebJson.structure(LambdaWebJson.structure(build, "codeConfig"), "s3Object");
        out.bucket = LambdaWebJson.text(s3Object, "bucket");
        out.key = LambdaWebJson.text(s3Object, "key");
        out.versionId = LambdaWebJson.text(s3Object, "versionId");
        out.runtime = LambdaWebJson.text(LambdaWebJson.structure(build, "runtimeConfig"), "runtime");
        return out;
    }

    private static WebFunctionRevision.ServiceConfig serviceConfigOf(JsonNode service, String defaultLogGroup) {
        WebFunctionRevision.ServiceConfig out = new WebFunctionRevision.ServiceConfig();
        out.executionRoleArn = LambdaWebJson.text(service, "executionRoleArn");
        Integer timeoutSeconds = LambdaWebJson.integer(service, "timeoutSeconds");
        if (timeoutSeconds != null) {
            out.timeoutSeconds = timeoutSeconds;
        }
        Integer maxConcurrency = LambdaWebJson.integer(service, "maxConcurrencyPerEnvironment");
        if (maxConcurrency != null) {
            out.maxConcurrencyPerEnvironment = maxConcurrency;
        }
        out.egressNetworkConnectorArn = LambdaWebJson.text(service, "egressNetworkConnectorArn");
        out.environmentVariables = LambdaWebJson.stringMap(service, "environmentVariables", false);
        JsonNode logging = loggingConfig(service);
        String logGroup = LambdaWebJson.text(logging, "logGroup");
        out.logGroup = logGroup != null ? logGroup : defaultLogGroup;
        String applicationLogLevel = LambdaWebJson.text(logging, "applicationLogLevel");
        if (applicationLogLevel != null) {
            out.applicationLogLevel = applicationLogLevel;
        }
        String systemLogLevel = LambdaWebJson.text(logging, "systemLogLevel");
        if (systemLogLevel != null) {
            out.systemLogLevel = systemLogLevel;
        }
        return out;
    }

    private static JsonNode loggingConfig(JsonNode service) {
        return LambdaWebJson.structure(LambdaWebJson.structure(service, "telemetryConfig"), "loggingConfig");
    }

    /** Copies the code a revision runs: the bucket's current version, or the version the request pins. */
    private void keepCode(String region, WebFunction fn, WebFunctionRevision revision) {
        if (s3Service == null) {
            return;
        }
        String accountId = AwsArnUtils.parse(fn.functionArn).accountId();
        WebFunctionRevision.BuildConfig build = revision.buildConfig;
        long size;
        try (S3Service.ObjectRead object = openCode(build)) {
            if (build.versionId == null && object.object().getVersionId() == null) {
                throw new AwsException("ValidationException", "You must enable S3 versioning for your S3 bucket"
                        + " when S3ObjectStorageMode is set to REFERENCE.", 400);
            }
            // AWS accepts an object of exactly this size, despite the wording.
            if (LambdaWebCode.MAX_BYTES < object.object().getSize()) {
                throw new AwsException("ValidationException", "Zipped size must be smaller than "
                        + LambdaWebCode.MAX_BYTES + " bytes", 400);
            }
            requireLambdaCanRead(fn, revision,
                    build.versionId != null ? build.versionId : object.object().getVersionId());
            size = code.store(accountId, region, fn.functionName, revision.revisionId, object.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not close the code object s3://" + build.bucket + "/" + build.key, e);
        }
        if (size == 0) {
            // An empty object is an internal error on AWS.
            code.deleteRevision(accountId, region, fn.functionName, revision.revisionId);
            throw new LambdaWebInternalServerException();
        }
        OptionalLong unzipped = code.unzippedSize(accountId, region, fn.functionName, revision.revisionId);
        if (unzipped.isEmpty()) {
            revision.failureReason = NOT_A_ZIP;
        } else if (Long.compareUnsigned(LambdaWebCode.MAX_BYTES, unzipped.getAsLong()) < 0) {
            revision.failureReason = "Uncompressed code size " + Long.toUnsignedString(unzipped.getAsLong())
                    + " bytes exceeds the maximum allowed size of " + LambdaWebCode.MAX_BYTES + " bytes";
        }
        if (revision.failureReason != null) {
            code.deleteRevision(accountId, region, fn.functionName, revision.revisionId);
        }
    }

    /** AWS evaluates only s3:GetObjectVersion, although its message names both actions. */
    private void requireLambdaCanRead(WebFunction fn, WebFunctionRevision revision, String versionId) {
        WebFunctionRevision.BuildConfig build = revision.buildConfig;
        AwsArnUtils.Arn function = AwsArnUtils.parse(fn.functionArn);
        String objectArn = AwsArnUtils.Arn.global(function.partition(), "s3", "", build.bucket + "/" + build.key)
                .toString();
        if (serviceCalls.authorize("lambda", "s3:GetObjectVersion", objectArn, revision.revisionArn,
                function.accountId(), Map.of(IamConditionContextResolver.VERSION_ID, List.of(versionId)))
                == Decision.DENY) {
            throw new AwsException("ValidationException", "Lambda service principal " + ServicePrincipals.of("lambda")
                    + " does not have s3:GetObject and s3:GetObjectVersion permission on s3://" + build.bucket + "/"
                    + build.key + ". Update the bucket policy to grant the Lambda service principal access and retry.",
                    400);
        }
    }

    private S3Service.ObjectRead openCode(WebFunctionRevision.BuildConfig build) {
        try {
            return s3Service.openObject(build.bucket, build.key, build.versionId);
        } catch (AwsException e) {
            LOG.debugv("Web function code object s3://{0}/{1} is not readable: {2}", build.bucket, build.key,
                    e.getMessage());
            String object = "s3://" + build.bucket + "/" + build.key;
            if (build.versionId == null) {
                throw new AwsException("ValidationException", "The specified S3 object does not exist: " + object
                        + ". Verify the object exists in the bucket.", 400);
            }
            if (e.getHttpStatus() == 404) {
                throw new AwsException("ValidationException", "The specified S3 object does not exist: " + object
                        + " (versionId=" + build.versionId + "). Verify the object exists and the versionId is correct.",
                        400);
            }
            throw new AwsException("ValidationException", "The S3 object " + object + " (versionId=" + build.versionId
                    + ") is inaccessible. Verify the request parameters and permissions are correct.", 400);
        }
    }

    private static void validateRevisionShape(LambdaWebValidation v, JsonNode request, String prefix) {
        JsonNode build = LambdaWebJson.structure(request, "buildConfig");
        v.required(prefix + "buildConfig", build);
        if (build != null) {
            JsonNode codeConfig = LambdaWebJson.structure(build, "codeConfig");
            v.required(prefix + "buildConfig.codeConfig", codeConfig);
            if (codeConfig != null) {
                JsonNode s3Object = LambdaWebJson.structure(codeConfig, "s3Object");
                v.required(prefix + "buildConfig.codeConfig.s3Object", s3Object);
                if (s3Object != null) {
                    String bucket = LambdaWebJson.text(s3Object, "bucket");
                    String key = LambdaWebJson.text(s3Object, "key");
                    v.required(prefix + "buildConfig.codeConfig.s3Object.bucket", bucket)
                            .length(prefix + "buildConfig.codeConfig.s3Object.bucket", bucket, 3, 63)
                            .pattern(prefix + "buildConfig.codeConfig.s3Object.bucket", bucket, S3_BUCKET)
                            .required(prefix + "buildConfig.codeConfig.s3Object.key", key)
                            .length(prefix + "buildConfig.codeConfig.s3Object.key", key, 1, 1024)
                            .length(prefix + "buildConfig.codeConfig.s3Object.versionId",
                                    LambdaWebJson.text(s3Object, "versionId"), 1, 1024);
                }
            }
            JsonNode runtimeConfig = LambdaWebJson.structure(build, "runtimeConfig");
            v.required(prefix + "buildConfig.runtimeConfig", runtimeConfig);
            if (runtimeConfig != null) {
                String runtime = LambdaWebJson.text(runtimeConfig, "runtime");
                v.required(prefix + "buildConfig.runtimeConfig.runtime", runtime)
                        .length(prefix + "buildConfig.runtimeConfig.runtime", runtime, 1, 30)
                        .pattern(prefix + "buildConfig.runtimeConfig.runtime", runtime, RUNTIME);
            }
        }
        JsonNode service = LambdaWebJson.structure(request, "serviceConfig");
        v.required(prefix + "serviceConfig", service);
        if (service != null) {
            String role = LambdaWebJson.text(service, "executionRoleArn");
            v.required(prefix + "serviceConfig.executionRoleArn", role)
                    .length(prefix + "serviceConfig.executionRoleArn", role, 20, 2048)
                    .pattern(prefix + "serviceConfig.executionRoleArn", role, ROLE_ARN)
                    .range(prefix + "serviceConfig.timeoutSeconds", LambdaWebJson.integer(service, "timeoutSeconds"), 3, 900)
                    .range(prefix + "serviceConfig.maxConcurrencyPerEnvironment",
                            LambdaWebJson.integer(service, "maxConcurrencyPerEnvironment"), 1, 128)
                    .pattern(prefix + "serviceConfig.egressNetworkConnectorArn",
                            LambdaWebJson.text(service, "egressNetworkConnectorArn"), NETWORK_CONNECTOR_ARN);
            Map<String, String> environment = LambdaWebJson.stringMap(service, "environmentVariables", false);
            if (environment != null) {
                v.sensitiveMapKeys(prefix + "serviceConfig.environmentVariables", environment, 1, ENVIRONMENT_KEY)
                        .sensitiveMapValues(prefix + "serviceConfig.environmentVariables", environment, 1);
            }
            JsonNode logging = loggingConfig(service);
            if (logging != null) {
                String loggingPath = prefix + "serviceConfig.telemetryConfig.loggingConfig.";
                String logGroup = LambdaWebJson.text(logging, "logGroup");
                v.length(loggingPath + "logGroup", logGroup, 1, 500)
                        .pattern(loggingPath + "logGroup", logGroup, LOG_GROUP)
                        .enumeration(loggingPath + "applicationLogLevel", LambdaWebJson.text(logging, "applicationLogLevel"),
                                APPLICATION_LOG_LEVELS)
                        .enumeration(loggingPath + "systemLogLevel", LambdaWebJson.text(logging, "systemLogLevel"),
                                SYSTEM_LOG_LEVELS);
            }
        }
        String kmsKeyArn = LambdaWebJson.text(request, "kmsKeyArn");
        v.length(prefix + "description", LambdaWebJson.text(request, "description"), 0, 256)
                .length(prefix + "kmsKeyArn", kmsKeyArn, 20, 2048)
                .pattern(prefix + "kmsKeyArn", kmsKeyArn, KMS_KEY_ARN);
    }

    // ---------------------------------------------------------------- endpoints

    public WebFunctionEndpoint createEndpoint(String region, String functionNameOrArn, JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validateEndpointShape(validation, request, "", true);
        validation.throwIfAny();
        String functionName = resolveFunctionName(region, functionNameOrArn, "CreateWebFunctionEndpoint");
        WebFunctionEndpoint endpoint = draftEndpoint(region, regionResolver.getAccountId(), functionName, request, true);
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            attachEndpoint(region, fn, endpoint, null);
            fn.endpoints.put(endpoint.endpointName, endpoint);
            indexDomains(fn, endpoint);
            fn.updatedAt = now();
            persist(region, fn);
        }
        LOG.infov("Created web function endpoint {0} on {1}: {2}", endpoint.endpointName, functionName,
                endpoint.domainName);
        return copy(endpoint, WebFunctionEndpoint.class);
    }

    public WebFunctionEndpoint getEndpoint(String region, String functionNameOrArn, String endpointNameOrArn) {
        String functionName = resolveFunctionName(region, functionNameOrArn, "GetWebFunctionEndpoint");
        String endpointName = resolveEndpointName(region, endpointNameOrArn, "GetWebFunctionEndpoint");
        synchronized (lockFor(region, functionName)) {
            return copy(endpoint(function(region, functionName), endpointName), WebFunctionEndpoint.class);
        }
    }

    public PaginatedResult<WebFunctionEndpoint> listEndpoints(String region, String functionNameOrArn,
                                                              JsonNode request) {
        ListRequest list = parseListRequest(request);
        String functionName = resolveFunctionName(region, functionNameOrArn, "ListWebFunctionEndpoints");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            List<WebFunctionEndpoint> snapshots = fn.endpoints.values().stream()
                    .filter(e -> list.matchesState(e.failed ? "Failed" : "Active"))
                    .map(e -> copy(e, WebFunctionEndpoint.class))
                    .toList();
            return page(snapshots, list,
                    scope("ListWebFunctionEndpoints", region, functionName, list), e -> e.endpointName);
        }
    }

    public EndpointUpdate updateEndpoint(String region, String functionNameOrArn, String endpointNameOrArn,
                                         JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validateEndpointShape(validation, request, "", false);
        validation.throwIfAny();
        String mode = LambdaWebJson.text(request, "autoDeploymentMode");
        JsonNode weights = LambdaWebJson.list(request, "revisionWeights");
        String authType = LambdaWebJson.text(request, "authType");
        String description = LambdaWebJson.text(request, "description");
        JsonNode scaling = LambdaWebJson.structure(request, "scalingConfig");
        JsonNode throttle = LambdaWebJson.structure(request, "throttleConfig");
        String functionName = resolveFunctionName(region, functionNameOrArn, "UpdateWebFunctionEndpoint");
        String endpointName = resolveEndpointName(region, endpointNameOrArn, "UpdateWebFunctionEndpoint");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            WebFunctionEndpoint endpoint = endpoint(fn, endpointName);
            if (endpoint.failed) {
                throw new AwsException("ConflictException", "The endpoint " + endpoint.endpointName
                        + " is in Failed state. The operation is allowed only when the endpoint is in Active state.", 409);
            }
            WebFunctionEndpoint before = copy(endpoint, WebFunctionEndpoint.class);

            String effectiveMode = mode != null ? mode : endpoint.autoDeploymentMode;
            rejectWeightsWithLatestRevision(effectiveMode, weights);
            List<WebFunctionEndpoint.RevisionWeight> newWeights = null;
            if (mode != null && !mode.equals(endpoint.autoDeploymentMode)) {
                requireModeAllowed(mode, endpoint.endpointType);
                if ("LatestRevision".equals(mode)) {
                    newWeights = latestRevision(fn).map(id -> List.of(weight(id, 100))).orElse(null);
                }
            }
            if (weights != null) {
                newWeights = resolveWeights(fn, endpoint.endpointType, requestedWeights(weights));
            }
            WebFunctionEndpoint.ScalingConfig newScaling = scaling != null ? scalingConfigOf(scaling) : null;
            WebFunctionEndpoint.ThrottleConfig newThrottle = throttle != null ? throttleConfigOf(throttle) : null;

            if (mode != null) {
                endpoint.autoDeploymentMode = mode;
            }
            if (newWeights != null) {
                endpoint.revisionWeights = newWeights;
            }
            if (authType != null) {
                endpoint.authType = authType;
            }
            if (description != null) {
                endpoint.description = description;
            }
            if (newScaling != null) {
                endpoint.scalingConfig = newScaling;
            }
            if (newThrottle != null) {
                endpoint.throttleConfig = newThrottle;
            }
            endpoint.updateStatus = "Successful";
            endpoint.updatedAt = now();
            fn.updatedAt = endpoint.updatedAt;
            persist(region, fn);
            return new EndpointUpdate(before, copy(endpoint, WebFunctionEndpoint.class));
        }
    }

    public void deleteEndpoint(String region, String functionNameOrArn, String endpointNameOrArn) {
        String functionName = resolveFunctionName(region, functionNameOrArn, "DeleteWebFunctionEndpoint");
        String endpointName = resolveEndpointName(region, endpointNameOrArn, "DeleteWebFunctionEndpoint");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            WebFunctionEndpoint endpoint = endpoint(fn, endpointName);
            fn.endpoints.remove(endpoint.endpointName);
            unindexDomains(endpoint);
            fn.updatedAt = now();
            persist(region, fn);
        }
    }

    private static WebFunctionEndpoint endpoint(WebFunction fn, String endpointName) {
        WebFunctionEndpoint endpoint = fn.endpoints.get(endpointName);
        if (endpoint == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The endpoint " + endpointName + " does not exist.", 404);
        }
        return endpoint;
    }

    /**
     * The endpoint a request describes, checked against every rule that does not need the function.
     * {@code revisionWeights} holds the requested weights until {@link #attachEndpoint} resolves them.
     */
    private static WebFunctionEndpoint draftEndpoint(String region, String accountId, String functionName,
                                                     JsonNode request, boolean standalone) {
        String endpointName = LambdaWebJson.text(request, "endpointName");
        if (!FUNCTION_NAME.matcher(endpointName).matches()) {
            throw new AwsException("ValidationException", "The endpoint ARN " + endpointName
                    + " with the function name " + functionName + ", the region " + region
                    + " and the account id " + accountId + " is invalid.", 400);
        }
        String endpointType = LambdaWebJson.text(request, "endpointType");
        String mode = LambdaWebJson.text(request, "autoDeploymentMode");
        if (mode == null) {
            mode = "Disabled";
        }
        requireModeAllowed(mode, endpointType);
        JsonNode weights = LambdaWebJson.list(request, "revisionWeights");
        rejectWeightsWithLatestRevision(mode, weights);
        if (standalone && "Disabled".equals(mode) && weights == null) {
            throw new AwsException("ValidationException",
                    "The revisionWeights must be specified when the autoDeploymentMode is Disabled.", 400);
        }
        WebFunctionEndpoint endpoint = new WebFunctionEndpoint();
        endpoint.endpointName = endpointName;
        endpoint.description = LambdaWebJson.text(request, "description");
        endpoint.endpointType = endpointType;
        endpoint.authType = LambdaWebJson.text(request, "authType");
        endpoint.autoDeploymentMode = mode;
        endpoint.regions = resolveRegions(region, endpointType, LambdaWebJson.list(request, "regions"));
        endpoint.revisionWeights = weights == null ? List.of() : requestedWeights(weights);
        JsonNode scaling = LambdaWebJson.structure(request, "scalingConfig");
        if (scaling != null) {
            endpoint.scalingConfig = scalingConfigOf(scaling);
        }
        JsonNode throttle = LambdaWebJson.structure(request, "throttleConfig");
        if (throttle != null) {
            endpoint.throttleConfig = throttleConfigOf(throttle);
        }
        return endpoint;
    }

    /**
     * Finishes a draft against its function. The first endpoint of CreateWebFunction routes to
     * {@code initialRevision}. A standalone endpoint routes to the requested weights, or to the
     * latest revision in LatestRevision mode.
     */
    private void attachEndpoint(String region, WebFunction fn, WebFunctionEndpoint endpoint,
                                WebFunctionRevision initialRevision) {
        if (fn.endpoints.containsKey(endpoint.endpointName)) {
            throw new AwsException("ConflictException", "The endpoint " + endpoint.endpointName + " already exists.", 409);
        }
        if (fn.endpoints.size() >= MAX_ENDPOINTS_PER_FUNCTION) {
            throw new AwsException("ServiceQuotaExceededException", "The function " + fn.functionArn
                    + " has reached the maxEndpointsPerFunction quota of " + MAX_ENDPOINTS_PER_FUNCTION + ".", 402);
        }
        if (initialRevision != null) {
            endpoint.revisionWeights = List.of(weight(initialRevision.revisionId, 100));
        } else if (!endpoint.revisionWeights.isEmpty()) {
            endpoint.revisionWeights = resolveWeights(fn, endpoint.endpointType, endpoint.revisionWeights);
        } else {
            endpoint.revisionWeights = latestRevision(fn).map(id -> List.of(weight(id, 100))).orElse(List.of());
        }
        endpoint.endpointArn = fn.functionArn + "/endpoint/" + endpoint.endpointName;
        endpoint.functionArn = fn.functionArn;
        String host = URI.create(config.effectiveBaseUrl()).getAuthority();
        if ("MultiRegion".equals(endpoint.endpointType)) {
            endpoint.domainName = randomLowercase(DOMAIN_ID_LENGTH) + ".lambda-web.global." + host;
        } else {
            String id = randomLowercase(DOMAIN_ID_LENGTH);
            for (String each : endpoint.regions) {
                endpoint.regionalDomainNames.put(each, regionalDomain(id, each, host));
            }
            endpoint.domainName = endpoint.regionalDomainNames.get(region);
        }
        endpoint.createdAt = now();
        endpoint.updatedAt = endpoint.createdAt;
    }

    private static String regionalDomain(String id, String region, String host) {
        return id + "." + DOMAIN_SHARD + ".lambda-web." + region + "." + host;
    }

    private static void requireModeAllowed(String mode, String endpointType) {
        if ("LatestRevision".equals(mode) && !"HomeRegion".equals(endpointType)) {
            throw new AwsException("ValidationException", "The autoDeploymentMode can only be Disabled when the"
                    + " endpointType is " + endpointType + ".", 400);
        }
    }

    private static void rejectWeightsWithLatestRevision(String mode, JsonNode weights) {
        if ("LatestRevision".equals(mode) && weights != null) {
            throw new AwsException("ValidationException",
                    "The revisionWeights cannot be specified when the autoDeploymentMode is LatestRevision.", 400);
        }
    }

    /** The home region leads the list and is added when absent. */
    private static List<String> resolveRegions(String home, String endpointType, JsonNode requested) {
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        regions.add(home);
        regions.addAll(LambdaWebJson.strings(requested));
        List<String> resolved = new ArrayList<>(regions);
        if ("HomeRegion".equals(endpointType) && resolved.size() > 1) {
            throw new AwsException("ValidationException", "The endpointType HomeRegion supports only the home region "
                    + home + ". The request results in the regions " + resolved + ".", 400);
        }
        if (!"HomeRegion".equals(endpointType) && resolved.size() < 2) {
            throw new AwsException("ValidationException", "The endpointType " + endpointType
                    + " requires at least 2 distinct regions. The home region " + home
                    + " is added automatically, so specify at least 1 additional region."
                    + " The request results in the regions " + resolved + ".", 400);
        }
        return resolved;
    }

    /** The weights of a request, unique and totalling 100. Whether the revisions exist is checked against the function. */
    private static List<WebFunctionEndpoint.RevisionWeight> requestedWeights(JsonNode weights) {
        List<WebFunctionEndpoint.RevisionWeight> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int total = 0;
        for (JsonNode node : weights) {
            WebFunctionEndpoint.RevisionWeight weight = weight(LambdaWebJson.text(node, "revisionId"),
                    LambdaWebJson.integer(node, "weight"));
            ids.add(weight.revisionId);
            total += weight.weight;
            out.add(weight);
        }
        if (ids.size() != out.size() || total != 100) {
            throw new AwsException("ValidationException",
                    "The revisionWeights must contain unique revisionIds, and the weights must total exactly 100.", 400);
        }
        return out;
    }

    private static List<WebFunctionEndpoint.RevisionWeight> resolveWeights(WebFunction fn, String endpointType,
                                                                           List<WebFunctionEndpoint.RevisionWeight> weights) {
        for (WebFunctionEndpoint.RevisionWeight weight : weights) {
            WebFunctionRevision revision = fn.revisions.get(weight.revisionId);
            if (revision == null) {
                throw new AwsException("ResourceNotFoundException",
                        "The revision " + weight.revisionId + " does not exist.", 404);
            }
            if (revision.failureReason != null) {
                throw new AwsException("ConflictException", "The revision " + weight.revisionId
                        + " is in Failed state and cannot be deployed. Only revisions in Active state can be deployed.", 409);
            }
        }
        // AWS finds every revision before it refuses one for its connector.
        if (!"HomeRegion".equals(endpointType)) {
            for (WebFunctionEndpoint.RevisionWeight weight : weights) {
                if (fn.revisions.get(weight.revisionId).serviceConfig.egressNetworkConnectorArn != null) {
                    throw new AwsException("ValidationException", "The revision " + weight.revisionId
                            + " has an egressNetworkConnectorArn configured and cannot be used by a " + endpointType
                            + " endpoint. Only HomeRegion endpoints support revisions with an egressNetworkConnectorArn"
                            + " configured.", 400);
                }
            }
        }
        return List.copyOf(weights);
    }

    /** The newest revision, which LatestRevision mode deploys. */
    private static Optional<String> latestRevision(WebFunction fn) {
        Optional<WebFunctionRevision> latest = fn.revisions.values().stream()
                .max(Comparator.comparing((WebFunctionRevision r) -> r.createdAt));
        if (latest.isPresent() && latest.get().failureReason != null) {
            throw new AwsException("ConflictException", "The latest revision " + latest.get().revisionId
                    + " is in Failed state and cannot be deployed. Only revisions in Active state can be deployed."
                    + " Create a new revision.", 409);
        }
        return latest.map(r -> r.revisionId);
    }

    private static WebFunctionEndpoint.RevisionWeight weight(String revisionId, int weight) {
        WebFunctionEndpoint.RevisionWeight out = new WebFunctionEndpoint.RevisionWeight();
        out.revisionId = revisionId;
        out.weight = weight;
        return out;
    }

    private static WebFunctionEndpoint.ScalingConfig scalingConfigOf(JsonNode node) {
        WebFunctionEndpoint.ScalingConfig out = new WebFunctionEndpoint.ScalingConfig();
        out.maxEnvironments = LambdaWebJson.integer(node, "maxEnvironments");
        return out;
    }

    private static WebFunctionEndpoint.ThrottleConfig throttleConfigOf(JsonNode node) {
        Integer rateLimit = LambdaWebJson.integer(node, "rateLimit");
        if (rateLimit != null && !SUPPORTED_RATE_LIMITS.contains(rateLimit)) {
            throw new AwsException("ValidationException", "The rateLimit " + rateLimit
                    + " is invalid. Supported rate limits are: " + SUPPORTED_RATE_LIMITS + ".", 400);
        }
        WebFunctionEndpoint.ThrottleConfig out = new WebFunctionEndpoint.ThrottleConfig();
        out.rateLimit = rateLimit;
        return out;
    }

    private static void validateEndpointShape(LambdaWebValidation v, JsonNode request, String prefix, boolean create) {
        String endpointName = LambdaWebJson.text(request, "endpointName");
        String endpointType = LambdaWebJson.text(request, "endpointType");
        String authType = LambdaWebJson.text(request, "authType");
        if (create) {
            v.required(prefix + "endpointType", endpointType)
                    .required(prefix + "endpointName", endpointName)
                    .required(prefix + "authType", authType);
        }
        v.length(prefix + "endpointName", endpointName, 1, 256)
                .enumeration(prefix + "endpointType", endpointType, ENDPOINT_TYPES)
                .enumeration(prefix + "authType", authType, AUTH_TYPES)
                .enumeration(prefix + "autoDeploymentMode", LambdaWebJson.text(request, "autoDeploymentMode"),
                        AUTO_DEPLOYMENT_MODES)
                .length(prefix + "description", LambdaWebJson.text(request, "description"), 0, 256);
        JsonNode regions = LambdaWebJson.list(request, "regions");
        if (regions != null) {
            v.listSize(prefix + "regions", regions.size(), 1, 100);
            int index = 1;
            for (String region : LambdaWebJson.strings(regions)) {
                v.length(prefix + "regions." + index + ".member", region, 1, 50)
                        .pattern(prefix + "regions." + index + ".member", region, REGION);
                index++;
            }
        }
        JsonNode weights = LambdaWebJson.list(request, "revisionWeights");
        if (weights != null) {
            v.listSize(prefix + "revisionWeights", weights.size(), 1, 2);
            int index = 1;
            for (JsonNode weight : weights) {
                String member = prefix + "revisionWeights." + index + ".member.";
                String revisionId = LambdaWebJson.text(weight, "revisionId");
                Integer value = LambdaWebJson.integer(weight, "weight");
                v.required(member + "revisionId", revisionId)
                        .length(member + "revisionId", revisionId, 1, 256)
                        .required(member + "weight", value)
                        .range(member + "weight", value, 1, 100);
                index++;
            }
        }
        JsonNode scaling = LambdaWebJson.structure(request, "scalingConfig");
        if (scaling != null) {
            v.range(prefix + "scalingConfig.maxEnvironments", LambdaWebJson.integer(scaling, "maxEnvironments"), 2, 10000);
        }
        JsonNode throttle = LambdaWebJson.structure(request, "throttleConfig");
        if (throttle != null) {
            v.range(prefix + "throttleConfig.rateLimit", LambdaWebJson.integer(throttle, "rateLimit"), 0, Integer.MAX_VALUE);
        }
    }

    // ---------------------------------------------------------------- tags

    public Map<String, String> listTags(String region, String resourceArn) {
        new LambdaWebValidation().pattern("resource", resourceArn, RESOURCE_ARN).throwIfAny();
        String functionName = resolveFunctionName(region, resourceArn, "ListTags");
        synchronized (lockFor(region, functionName)) {
            return new LinkedHashMap<>(function(region, functionName).tags);
        }
    }

    public void tagResource(String region, String resourceArn, JsonNode request) {
        Map<String, String> tags = LambdaWebJson.stringMap(request, "Tags", true);
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern("resource", resourceArn, RESOURCE_ARN).required("tags", tags);
        validateTagsShape(validation, "tags", tags);
        validation.throwIfAny();
        writeTags(region, resourceArn, tags, fn -> fn.tags.putAll(tags));
    }

    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern("resource", resourceArn, RESOURCE_ARN);
        if (tagKeys == null) {
            validation.required("tagKeys", null);
        } else {
            validation.listSize("tagKeys", tagKeys.size(), 1, 200).listMembers("tagKeys", tagKeys, 1, 128, TAG_KEY);
        }
        validation.throwIfAny();
        String functionName = resolveFunctionName(region, resourceArn, "UntagResource");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            tagKeys.forEach(fn.tags::remove);
            fn.updatedAt = now();
            persist(region, fn);
        }
    }

    /** Sets the stored tags to exactly {@code desired}, for AWS::Lambda::WebFunction. */
    public WebFunction replaceTags(String region, String functionNameOrArn, Map<String, String> desired) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validateTagsShape(validation, "tags", desired);
        validation.throwIfAny();
        return writeTags(region, functionNameOrArn, desired, fn -> fn.tags = new LinkedHashMap<>(desired));
    }

    private WebFunction writeTags(String region, String functionNameOrArn, Map<String, String> tags,
                                  Consumer<WebFunction> write) {
        requireValidTags(tags);
        String functionName = resolveFunctionName(region, functionNameOrArn, "TagResource");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            write.accept(fn);
            fn.updatedAt = now();
            persist(region, fn);
            return copy(fn, WebFunction.class);
        }
    }

    private static void validateTagsShape(LambdaWebValidation v, String path, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        Map<String, String> present = new LinkedHashMap<>();
        tags.forEach((key, value) -> present.put(key, value == null ? "" : value));
        v.mapKeys(path, present, 1, 128, TAG_KEY).mapValues(path, present, 0, 256, TAG_VALUE);
    }

    private static void requireValidTags(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        boolean invalid = tags.entrySet().stream()
                .anyMatch(e -> e.getValue() == null || e.getKey().toLowerCase(Locale.ROOT).startsWith("aws:"));
        if (invalid) {
            throw new AwsException("ValidationException", INVALID_TAGS, 400);
        }
    }

    // ---------------------------------------------------------------- resource policy

    public ResourcePolicy putResourcePolicy(String region, String resourceArn, JsonNode request) {
        String policy = LambdaWebJson.text(request, "Policy");
        String revisionId = LambdaWebJson.text(request, "RevisionId");
        new LambdaWebValidation().pattern("resourceArn", resourceArn, RESOURCE_ARN)
                .required("policy", policy)
                .length("policy", policy, 1, 20480)
                .pattern("revisionId", revisionId, POLICY_REVISION_ID)
                .throwIfAny();
        validatePolicy(policy, resourceArn);
        String functionName = resolveFunctionName(region, resourceArn, "PutResourcePolicy");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            if (revisionId != null && !revisionId.equals(fn.policyRevisionId)) {
                throw new AwsException("ConflictException", "The policy was updated or deleted by another request."
                        + " Try again with the latest policy revisionId.", 409);
            }
            fn.policy = policy;
            fn.policyRevisionId = UUID.randomUUID().toString();
            fn.updatedAt = now();
            persist(region, fn);
            return new ResourcePolicy(fn.policy, fn.policyRevisionId);
        }
    }

    public ResourcePolicy getResourcePolicy(String region, String resourceArn) {
        new LambdaWebValidation().pattern("resourceArn", resourceArn, RESOURCE_ARN).throwIfAny();
        WebFunction fn = function(region, resolveFunctionName(region, resourceArn, "GetResourcePolicy"));
        if (fn.policy == null) {
            throw noPolicy();
        }
        return new ResourcePolicy(fn.policy, fn.policyRevisionId);
    }

    public void deleteResourcePolicy(String region, String resourceArn, String revisionId) {
        new LambdaWebValidation().pattern("resourceArn", resourceArn, RESOURCE_ARN)
                .pattern("revisionId", revisionId, POLICY_REVISION_ID)
                .throwIfAny();
        String functionName = resolveFunctionName(region, resourceArn, "DeleteResourcePolicy");
        synchronized (lockFor(region, functionName)) {
            WebFunction fn = function(region, functionName);
            if (fn.policy == null) {
                throw noPolicy();
            }
            if (revisionId != null && !revisionId.equals(fn.policyRevisionId)) {
                throw new AwsException("ConflictException", "The policy was updated by another request. Try again.", 409);
            }
            fn.policy = null;
            fn.policyRevisionId = null;
            fn.updatedAt = now();
            persist(region, fn);
        }
    }

    private static AwsException noPolicy() {
        return new AwsException("ResourceNotFoundException", "The function does not have a policy.", 404);
    }

    /** The checks AWS runs on a policy document before it looks the function up. */
    private void validatePolicy(String policy, String functionArn) {
        JsonNode document;
        try {
            document = objectMapper.readTree(policy);
        } catch (Exception e) {
            throw policySyntax();
        }
        if (document == null || !document.isObject()) {
            throw policySyntax();
        }
        JsonNode statements = document.get("Statement");
        List<JsonNode> each = new ArrayList<>();
        if (statements != null && statements.isArray()) {
            statements.forEach(each::add);
        } else if (statements != null && statements.isObject()) {
            each.add(statements);
        }
        if (each.isEmpty()) {
            throw policySyntax();
        }
        for (JsonNode statement : each) {
            validateStatement(statement, functionArn);
        }
    }

    private static void validateStatement(JsonNode statement, String functionArn) {
        if (!statement.isObject()) {
            throw policySyntax();
        }
        if (!statement.has("Effect")) {
            throw new AwsException("ValidationException", "Effect must be explicitly available for a statement", 400);
        }
        Iterator<String> keys = statement.fieldNames();
        while (keys.hasNext()) {
            if (!STATEMENT_KEYS.contains(keys.next())) {
                throw new AwsException("ValidationException", "Syntax error at position (-1,-1)", 400);
            }
        }
        String effect = statement.path("Effect").asText();
        if (!effect.equalsIgnoreCase("Allow") && !effect.equalsIgnoreCase("Deny")) {
            throw policySyntax();
        }
        if (statement.has("NotPrincipal") || statement.has("NotAction") || statement.has("NotResource")) {
            throw policySyntax();
        }
        JsonNode principal = statement.get("Principal");
        validatePrincipal(principal);
        for (String action : policyValues(statement.get("Action"))) {
            if (!action.startsWith("lambda:")) {
                throw policySyntax();
            }
        }
        for (String resource : policyValues(statement.get("Resource"))) {
            if (!resource.equals(functionArn) && !resource.startsWith(functionArn + "/")) {
                throw policySyntax();
            }
        }
        if (effect.equalsIgnoreCase("Allow") && isWildcardPrincipal(principal)
                && !hasTrustedCondition(statement.path("Condition"))) {
            throw new AwsException("ValidationException", "Lambda blocked the creation of this policy"
                    + " because it would grant public access to your function.", 400);
        }
    }

    private static void validatePrincipal(JsonNode principal) {
        if (principal == null || principal.isNull() || (principal.isObject() && principal.isEmpty())) {
            throw new AwsException("ValidationException", INVALID_PRINCIPAL, 400);
        }
        if (principal.isTextual()) {
            if (!"*".equals(principal.textValue())) {
                throw policySyntax();
            }
            return;
        }
        if (!principal.isObject()) {
            throw policySyntax();
        }
        Iterator<Map.Entry<String, JsonNode>> fields = principal.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!PRINCIPAL_KEYS.contains(field.getKey())) {
                throw policySyntax();
            }
            if (field.getValue().isArray() && field.getValue().isEmpty()) {
                throw new AwsException("ValidationException", INVALID_PRINCIPAL, 400);
            }
            for (String value : policyValues(field.getValue())) {
                Pattern allowed = "Service".equals(field.getKey()) ? PRINCIPAL_SERVICE
                        : "AWS".equals(field.getKey()) && AwsArnUtils.isArn(value) ? PRINCIPAL_AWS_ARN : PRINCIPAL_AWS;
                if (!"*".equals(value) && !allowed.matcher(value).matches()) {
                    throw new AwsException("ValidationException", "The provided input'" + value
                            + "'was invalid. Member must satisfy expression: " + allowed.pattern()
                            + " Please check your input and try again.", 400);
                }
                if ("Service".equals(field.getKey()) && "*".equals(value)) {
                    throw new AwsException("ValidationException", "The provided input'*'was invalid."
                            + " Member must satisfy expression: " + PRINCIPAL_SERVICE.pattern()
                            + " Please check your input and try again.", 400);
                }
            }
        }
    }

    /** A string or a list of strings. Anything else is a syntax error, and so is an absent member. */
    private static List<String> policyValues(JsonNode node) {
        if (node == null || node.isNull()) {
            throw policySyntax();
        }
        if (node.isTextual()) {
            return List.of(node.textValue());
        }
        if (!node.isArray()) {
            throw policySyntax();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw policySyntax();
            }
            values.add(item.textValue());
        }
        if (values.isEmpty()) {
            throw policySyntax();
        }
        return values;
    }

    /** AWS counts a bare "*" and a "*" under AWS as public. Service and Federated wildcards are not. */
    private static boolean isWildcardPrincipal(JsonNode principal) {
        if (principal.isTextual()) {
            return "*".equals(principal.textValue());
        }
        JsonNode aws = principal.get("AWS");
        return aws != null && policyValues(aws).contains("*");
    }

    private static boolean hasTrustedCondition(JsonNode condition) {
        if (!condition.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> operators = condition.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operator = operators.next();
            if (!PINNING_OPERATORS.contains(operator.getKey())) {
                continue;
            }
            boolean pattern = operator.getKey().endsWith("Like") || "ArnEquals".equals(operator.getKey());
            Iterator<Map.Entry<String, JsonNode>> keys = operator.getValue().fields();
            while (keys.hasNext()) {
                Map.Entry<String, JsonNode> key = keys.next();
                if (TRUSTED_CONDITION_KEYS.contains(key.getKey()) && (!pattern || pinned(key.getKey(), key.getValue()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Under a pattern operator a value pins the caller only without a wildcard, or none in an ARN's account. */
    private static boolean pinned(String key, JsonNode values) {
        List<String> each = new ArrayList<>();
        if (values.isArray()) {
            values.forEach(value -> each.add(value.asText()));
        } else {
            each.add(values.asText());
        }
        for (String value : each) {
            boolean pins = ARN_CONDITION_KEYS.contains(key)
                    ? PublicAccessConditions.pinsAccount(value) : !PublicAccessConditions.hasWildcard(value);
            if (!pins) {
                return false;
            }
        }
        return !each.isEmpty();
    }

    private static AwsException policySyntax() {
        return new AwsException("ValidationException", POLICY_SYNTAX, 400);
    }

    // ---------------------------------------------------------------- identifiers

    private WebFunction function(String region, String functionName) {
        WebFunction fn = functions.get(key(region, functionName)).orElse(null);
        if (fn == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The function " + functionArn(region, regionResolver.getAccountId(), functionName)
                            + " does not exist.", 404);
        }
        return fn;
    }

    /** A function given as a name or as its ARN. A child ARN is not a function identifier. */
    String resolveFunctionName(String region, String functionNameOrArn, String action) {
        if (functionNameOrArn == null) {
            throw invalidFunctionName(null);
        }
        Matcher arn = WEB_FUNCTION_ARN.matcher(functionNameOrArn);
        String name = functionNameOrArn;
        if (arn.matches()) {
            if (arn.group(4) != null) {
                throw invalidFunctionName(functionNameOrArn);
            }
            requireOwn(region, arn, functionNameOrArn, action);
            name = arn.group(3);
        }
        if (!FUNCTION_NAME.matcher(name).matches()) {
            throw invalidFunctionName(functionNameOrArn);
        }
        return name;
    }

    /** A revision given as an id or as any revision ARN. AWS reads only the id off the ARN. */
    String resolveRevisionId(String region, String revisionIdOrArn, String action) {
        String id = revisionIdOrArn;
        Matcher arn = revisionIdOrArn == null ? null : WEB_FUNCTION_ARN.matcher(revisionIdOrArn);
        if (arn != null && arn.matches()) {
            if (!"revision".equals(arn.group(4))) {
                throw invalidRevisionId(revisionIdOrArn);
            }
            requireOwn(region, arn, revisionIdOrArn, action);
            id = arn.group(5);
        }
        if (id == null || !REVISION_ID.matcher(id).matches()) {
            throw invalidRevisionId(revisionIdOrArn);
        }
        return id;
    }

    /** An endpoint given as a name or as any endpoint ARN. AWS reads only the name off the ARN. */
    String resolveEndpointName(String region, String endpointNameOrArn, String action) {
        Matcher arn = endpointNameOrArn == null ? null : WEB_FUNCTION_ARN.matcher(endpointNameOrArn);
        if (arn == null || !arn.matches()) {
            return endpointNameOrArn;
        }
        if (!"endpoint".equals(arn.group(4))) {
            throw new AwsException("ValidationException", "Invalid endpoint name or ARN '" + endpointNameOrArn + "'", 400);
        }
        requireOwn(region, arn, endpointNameOrArn, action);
        return arn.group(5);
    }

    private void requireOwn(String region, Matcher arn, String givenArn, String action) {
        if (!region.equals(arn.group(1))) {
            throw new AwsException("ValidationException",
                    "The operation is only allowed in the function's home region " + arn.group(1) + ".", 400);
        }
        String callerAccount = regionResolver.getAccountId();
        if (!callerAccount.equals(arn.group(2))) {
            String user = regionResolver.buildGlobalArn("iam", callerAccount, "user/" + accessKeyId());
            throw new AwsException("AccessDeniedException", AccessDeniedMessages.notAuthorized(user, "lambda:" + action,
                    givenArn, PolicyType.RESOURCE_BASED_POLICY.noAllow("lambda:" + action)), 403);
        }
    }

    private String accessKeyId() {
        if (requestContext != null) {
            try {
                String akid = requestContext.get().getAccessKeyId();
                if (akid != null) {
                    return akid;
                }
            } catch (ContextNotActiveException ignored) {
                // Outside a request there is no caller key. The placeholder below names the account only.
            }
        }
        return "unknown";
    }

    private static AwsException invalidFunctionName(String functionName) {
        return new AwsException("ValidationException", "Invalid function name or ARN '" + functionName + "'", 400);
    }

    private static AwsException invalidRevisionId(String revisionId) {
        return new AwsException("ValidationException", "Invalid revision id or ARN '" + revisionId + "'", 400);
    }

    private String functionArn(String region, String accountId, String functionName) {
        return regionResolver.buildArn("lambda", region, accountId, "web-function/" + functionName);
    }

    /** A deep copy, so a caller never holds the stored object a later request mutates. */
    private <T> T copy(T value, Class<T> type) {
        return objectMapper.convertValue(objectMapper.valueToTree(value), type);
    }

    // ---------------------------------------------------------------- listing

    private record ListRequest(List<String> states, int maxResults, String nextToken) {
        boolean matchesState(String state) {
            return states == null || states.contains(state);
        }
    }

    private ListRequest parseListRequest(JsonNode request) {
        Integer maxResults = LambdaWebJson.integer(request, "maxResults");
        String nextToken = LambdaWebJson.text(request, "nextToken");
        JsonNode filters = LambdaWebJson.list(request, "filters");
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.range("maxResults", maxResults, 1, 50);
        List<String> badNames = new ArrayList<>();
        List<String> states = null;
        if (filters != null) {
            validation.listSize("filters", filters.size(), 1, 10);
            int index = 1;
            for (JsonNode filter : filters) {
                String member = "filters." + index + ".member.";
                String name = LambdaWebJson.text(filter, "name");
                JsonNode values = LambdaWebJson.list(filter, "values");
                validation.required(member + "name", name)
                        .length(member + "name", name, 1, 64)
                        .pattern(member + "name", name, FILTER_NAME)
                        .required(member + "values", values);
                List<String> filterValues = LambdaWebJson.strings(values);
                if (values != null) {
                    validation.listSize(member + "values", values.size(), 1, 10);
                    int valueIndex = 1;
                    for (String value : filterValues) {
                        validation.length(member + "values." + valueIndex + ".member", value, 1, 64)
                                .pattern(member + "values." + valueIndex + ".member", value, FILTER_VALUE);
                        valueIndex++;
                    }
                }
                if ("state".equals(name)) {
                    if (states == null) {
                        states = new ArrayList<>();
                    }
                    if (filterValues != null) {
                        states.addAll(filterValues);
                    }
                } else if (name != null) {
                    badNames.add(name);
                }
                index++;
            }
        }
        validation.throwIfAny();
        if (!badNames.isEmpty()) {
            throw new AwsException("ValidationException", "The filter names " + badNames
                    + " are invalid. Supported filter names are: [state].", 400);
        }
        return new ListRequest(states, maxResults == null ? DEFAULT_PAGE_SIZE : maxResults, nextToken);
    }

    private String scope(String operation, String region, String functionName, ListRequest list) {
        return TOKEN_MARKER + operation + "|" + region + "|" + regionResolver.getAccountId() + "|" + functionName
                + "|" + list.states() + "|" + list.maxResults();
    }

    /**
     * Pages a listing in {@code cursorOf} order. The token carries the request it was issued for and the
     * cursor of the item its page ended on, so the next page starts past that item even when it is gone,
     * as on AWS.
     */
    private static <T> PaginatedResult<T> page(List<T> items, ListRequest list, String scope,
                                               Function<T, String> cursorOf) {
        return Pagination.paginate(items, cursorOf, list.maxResults(), list.nextToken(), scope,
                LambdaWebService::tokenError);
    }

    private static AwsException tokenError(String token) {
        String decoded;
        try {
            decoded = Base64Url.decode(token);
        } catch (IllegalArgumentException expected) {
            // Not base64, so not a token Floci issued.
            decoded = "";
        }
        if (decoded.startsWith(TOKEN_MARKER)) {
            return new AwsException("ValidationException", "The nextToken " + token
                    + " does not match the request. Subsequent pagination requests must use the same"
                    + " parameters as the initial request.", 400);
        }
        return new AwsException("ValidationException", "The nextToken " + token + " is invalid or has expired.", 400);
    }

    // ---------------------------------------------------------------- storage

    private static String randomLowercase(int length) {
        StringBuilder out = new StringBuilder(length);
        while (out.length() < length) {
            UUID uuid = UUID.randomUUID();
            long bits = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            for (int i = 0; i < 12 && out.length() < length; i++) {
                out.append(LOWERCASE_ALNUM.charAt((int) Math.floorMod(bits, LOWERCASE_ALNUM.length())));
                bits = bits / LOWERCASE_ALNUM.length();
            }
        }
        return out.toString();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static String formatTimestamp(Instant instant) {
        return LambdaWebResponses.timestamp(instant);
    }

    private static String key(String region, String functionName) {
        return region + "/" + functionName;
    }

    private Object lockFor(String region, String functionName) {
        return locks.computeIfAbsent(key(region, functionName), k -> new Object());
    }

    private List<WebFunction> in(String region) {
        String prefix = region + "/";
        return functions.scan(k -> k.startsWith(prefix));
    }

    /** The backend only tracks put and delete, so a function mutated in place is written back. */
    private void persist(String region, WebFunction fn) {
        functions.put(key(region, fn.functionName), fn);
    }
}
