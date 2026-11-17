package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.AccessConfig;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EMR on EKS ({@code emr-containers}): virtual clusters, job runs, job templates, managed endpoints
 * and security configurations, as control-plane records. A job run follows the job run states
 * without running Spark on Kubernetes: it completes as soon as it is submitted, or after
 * {@code floci.services.emrcontainers.job-run-duration-seconds}, and fails as AWS documents when
 * Floci's EKS has no cluster of the virtual cluster's name.
 *
 * <p>Every resource is stored as the JSON AWS answers for it, under its ARN. Members whose name
 * starts with {@code _} are Floci's own and never leave the service.
 */
@ApplicationScoped
public class EmrContainersService implements Resettable {

    static final String SERVICE = "emr-containers";
    static final String VIRTUAL_CLUSTERS = "/virtualclusters/";
    static final String JOB_RUNS = "/jobruns/";
    static final String ENDPOINTS = "/endpoints/";
    static final String JOB_TEMPLATES = "/jobtemplates/";
    static final String SECURITY_CONFIGURATIONS = "/securityconfigurations/";

    static final int VIRTUAL_CLUSTER_ID_LENGTH = 25;
    static final int JOB_RUN_ID_LENGTH = 19;
    static final int ENDPOINT_ID_LENGTH = 13;

    private static final String CLIENT_TOKEN = "_clientToken";
    private static final String DUE_AT = "_dueAt";
    private static final String ACCESS_ENTRY_INTEGRATED = "_eksAccessEntryIntegrated";
    private static final Set<String> ACCESS_ENTRY_MODES = Set.of("API", "API_AND_CONFIG_MAP");
    private static final String RUNNING = "RUNNING";
    private static final String TERMINATED = "TERMINATED";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELLED = "CANCELLED";
    private static final String FAILED = "FAILED";
    private static final String ACTIVE = "ACTIVE";
    private static final Set<String> FINISHED_JOB_RUN_STATES = Set.of(COMPLETED, CANCELLED, FAILED);
    private static final Set<String> QUEUED_JOB_RUN_STATES = Set.of("PENDING", "SUBMITTED");
    private static final Set<String> FINISHED_ENDPOINT_STATES = Set.of(TERMINATED, "TERMINATED_WITH_ERRORS");
    private static final String TOKEN_CREDENTIAL = "TOKEN";
    private static final int DEFAULT_SESSION_SECONDS = 15 * 60;
    private static final int MAX_SESSION_SECONDS = 12 * 60 * 60;
    private static final int MAX_TAGS = 50;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final String ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final Pattern TEMPLATE_PARAMETER = Pattern.compile("\\$\\{([.\\-_#A-Za-z0-9]+)}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern VIRTUAL_CLUSTER_ARN = Pattern.compile("/virtualclusters/([0-9a-z]+)");
    private static final Pattern NESTED_ARN =
            Pattern.compile("/virtualclusters/([0-9a-z]+)/(jobruns|endpoints)/([0-9a-z]+)");

    private final AccountAwareStorageBackend<ObjectNode> virtualClusters;
    private final AccountAwareStorageBackend<ObjectNode> jobRuns;
    private final AccountAwareStorageBackend<ObjectNode> endpoints;
    private final AccountAwareStorageBackend<ObjectNode> jobTemplates;
    private final AccountAwareStorageBackend<ObjectNode> securityConfigurations;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final EksService eksService;
    private final IamService iamService;
    private final RequestContext requestContext;
    private final Clock clock;
    private final int jobRunDurationSeconds;

    @Inject
    public EmrContainersService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver,
                                EksService eksService, IamService iamService, RequestContext requestContext,
                                Clock clock, EmulatorConfig config) {
        this(storage(storageFactory, "emr-containers-virtual-clusters.json"),
                storage(storageFactory, "emr-containers-job-runs.json"),
                storage(storageFactory, "emr-containers-endpoints.json"),
                storage(storageFactory, "emr-containers-job-templates.json"),
                storage(storageFactory, "emr-containers-security-configurations.json"),
                mapper, regionResolver, eksService, iamService, requestContext, clock,
                config.services().emrcontainers().jobRunDurationSeconds());
    }

    EmrContainersService(AccountAwareStorageBackend<ObjectNode> virtualClusters,
                         AccountAwareStorageBackend<ObjectNode> jobRuns,
                         AccountAwareStorageBackend<ObjectNode> endpoints,
                         AccountAwareStorageBackend<ObjectNode> jobTemplates,
                         AccountAwareStorageBackend<ObjectNode> securityConfigurations,
                         ObjectMapper mapper, RegionResolver regionResolver, EksService eksService,
                         IamService iamService, RequestContext requestContext, Clock clock,
                         int jobRunDurationSeconds) {
        this.virtualClusters = virtualClusters;
        this.jobRuns = jobRuns;
        this.endpoints = endpoints;
        this.jobTemplates = jobTemplates;
        this.securityConfigurations = securityConfigurations;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.eksService = eksService;
        this.iamService = iamService;
        this.requestContext = requestContext;
        this.clock = clock;
        this.jobRunDurationSeconds = jobRunDurationSeconds;
    }

    private static AccountAwareStorageBackend<ObjectNode> storage(StorageFactory storageFactory, String file) {
        return storageFactory.create("emrcontainers", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    // ── Virtual clusters ─────────────────────────────────────────────────────────

    public synchronized ObjectNode createVirtualCluster(ObjectNode request) {
        EmrContainersRequestShapes.check("CreateVirtualCluster", request);
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(virtualClusters, arn(VIRTUAL_CLUSTERS), clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn");
        }
        if (request.hasNonNull("securityConfigurationId")) {
            String securityConfigurationId = request.get("securityConfigurationId").textValue();
            if (securityConfigurations.get(arn(SECURITY_CONFIGURATIONS + securityConfigurationId)).isEmpty()) {
                throw notFound("Security configuration " + securityConfigurationId + " doesn't exist.");
            }
        }
        String id = newId(VIRTUAL_CLUSTER_ID_LENGTH);
        String arn = arn(VIRTUAL_CLUSTERS + id);
        ObjectNode cluster = mapper.createObjectNode();
        cluster.put("id", id);
        cluster.set("name", request.get("name"));
        cluster.put("arn", arn);
        cluster.put("state", RUNNING);
        cluster.set("containerProvider", request.get("containerProvider").deepCopy());
        cluster.put("createdAt", timestamp(now()));
        cluster.set("tags", tags(request.get("tags")));
        copy(request, cluster, "securityConfigurationId", "sessionEnabled", "schedulerConfiguration");
        cluster.put(CLIENT_TOKEN, clientToken);
        cluster.put(ACCESS_ENTRY_INTEGRATED, accessEntryIntegrated(request.get("containerProvider")));
        virtualClusters.put(arn, cluster);
        return pick(cluster, "id", "name", "arn");
    }

    public synchronized ObjectNode describeVirtualCluster(String id) {
        EmrContainersRequestShapes.check("DescribeVirtualCluster", object("id", id));
        ObjectNode cluster = virtualCluster(id).orElseThrow(() -> notFound(virtualClusterMissing(id)));
        return wrap("virtualCluster", withSchedulerStatus(cluster));
    }

    public synchronized ObjectNode listVirtualClusters(ListQuery query, String containerProviderId,
                                                       String containerProviderType,
                                                       Boolean eksAccessEntryIntegrated) {
        ObjectNode shape = query.shape();
        putText(shape, "containerProviderId", containerProviderId);
        putText(shape, "containerProviderType", containerProviderType);
        if (eksAccessEntryIntegrated != null) {
            shape.put("eksAccessEntryIntegrated", eksAccessEntryIntegrated);
        }
        EmrContainersRequestShapes.check("ListVirtualClusters", shape);
        List<ObjectNode> matching = virtualClusters.scan(key -> key.startsWith(arn(VIRTUAL_CLUSTERS))).stream()
                .filter(query::matches)
                .filter(cluster -> containerProviderId == null
                        || containerProviderId.equals(cluster.path("containerProvider").path("id").textValue()))
                .filter(cluster -> containerProviderType == null
                        || containerProviderType.equals(cluster.path("containerProvider").path("type").textValue()))
                .filter(cluster -> eksAccessEntryIntegrated == null
                        || eksAccessEntryIntegrated == cluster.path(ACCESS_ENTRY_INTEGRATED).asBoolean())
                .map(this::withSchedulerStatus)
                .toList();
        return page(matching, query, "virtualClusters");
    }

    public synchronized ObjectNode deleteVirtualCluster(String id) {
        EmrContainersRequestShapes.check("DeleteVirtualCluster", object("id", id));
        ObjectNode cluster = virtualCluster(id).orElseThrow(() -> invalid(virtualClusterMissing(id)));
        if (!TERMINATED.equals(cluster.path("state").textValue())) {
            ObjectNode terminated = cluster.deepCopy();
            terminated.put("state", TERMINATED);
            // AWS: "When a virtual cluster is terminated, tags are cleaned and no longer accessible."
            terminated.set("tags", mapper.createObjectNode());
            virtualClusters.put(terminated.get("arn").textValue(), terminated);
        }
        return object("id", id);
    }

    public synchronized ObjectNode updateVirtualCluster(String id, ObjectNode request) {
        request.put("id", id);
        EmrContainersRequestShapes.check("UpdateVirtualCluster", request);
        ObjectNode cluster = virtualCluster(id).orElseThrow(() -> notFound(virtualClusterMissing(id)));
        requireState(cluster, RUNNING, "VirtualCluster", "update");
        ObjectNode updated = cluster.deepCopy();
        JsonNode scheduler = request.get("schedulerConfiguration");
        if (scheduler != null && !scheduler.isNull()) {
            // An empty object clears the limits; leaving the member out keeps them.
            if (scheduler.isEmpty()) {
                updated.remove("schedulerConfiguration");
            } else {
                updated.set("schedulerConfiguration", scheduler.deepCopy());
            }
        }
        virtualClusters.put(updated.get("arn").textValue(), updated);
        return wrap("virtualCluster", withSchedulerStatus(updated));
    }

    /**
     * Whether a new virtual cluster is EKS access entry integrated, "the virtual cluster created using access entry
     * API operations from Amazon EKS": the development guide has EMR configure its access that way when the EKS
     * cluster's authenticationMode admits access entries, and only for virtual clusters created from then on. A
     * cluster Floci's EKS does not have reads as not integrated.
     */
    private boolean accessEntryIntegrated(JsonNode containerProvider) {
        String eksCluster = containerProvider.path("id").textValue();
        return eksService.findAuthenticationCluster(regionResolver.getAccountId(), regionResolver.getRegion(),
                        eksCluster)
                .map(Cluster::getAccessConfig)
                .map(AccessConfig::authenticationMode)
                .filter(ACCESS_ENTRY_MODES::contains)
                .isPresent();
    }

    // ── Job runs ─────────────────────────────────────────────────────────────────

    public synchronized ObjectNode startJobRun(String virtualClusterId, ObjectNode request) {
        request.put("virtualClusterId", virtualClusterId);
        EmrContainersRequestShapes.check("StartJobRun", request);
        ObjectNode cluster = virtualCluster(virtualClusterId)
                .orElseThrow(() -> notFound(virtualClusterMissing(virtualClusterId)));
        requireState(cluster, RUNNING, "JobRun", "create");
        String clusterArn = cluster.get("arn").textValue();
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = jobRuns.scan(key -> key.startsWith(clusterArn + JOB_RUNS)).stream()
                .filter(run -> clientToken.equals(run.path("clientToken").textValue()))
                .findFirst();
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn", "virtualClusterId");
        }

        ObjectNode template = mapper.createObjectNode();
        if (request.hasNonNull("jobTemplateId")) {
            template = jobTemplateData(request.get("jobTemplateId").textValue(), request.get("jobTemplateParameters"));
        }
        List<String> missing = new ArrayList<>();
        for (String member : List.of("executionRoleArn", "releaseLabel", "jobDriver")) {
            if (!request.hasNonNull(member) && !template.hasNonNull(member)) {
                missing.add(SmithyValidationErrors.notNull(member));
            }
        }
        if (!missing.isEmpty()) {
            throw SmithyValidationErrors.validationException(missing);
        }

        String id = newId(JOB_RUN_ID_LENGTH);
        String arn = clusterArn + JOB_RUNS + id;
        Instant now = now();
        ObjectNode run = mapper.createObjectNode();
        run.put("id", id);
        copy(request, run, "name");
        run.put("virtualClusterId", virtualClusterId);
        run.put("arn", arn);
        run.put("clientToken", clientToken);
        for (String member : List.of("executionRoleArn", "releaseLabel", "configurationOverrides", "jobDriver")) {
            JsonNode value = request.hasNonNull(member) ? request.get(member) : template.get(member);
            if (value != null && !value.isNull()) {
                run.set(member, value.deepCopy());
            }
        }
        run.put("createdAt", timestamp(now));
        run.put("createdBy", createdBy());
        ObjectNode tags = tags(template.get("jobTags"));
        tags.setAll(tags(request.get("tags")));
        if (tags.size() > MAX_TAGS) {
            throw tooManyTags();
        }
        run.set("tags", tags);
        if (request.hasNonNull("retryPolicyConfiguration")) {
            run.set("retryPolicyConfiguration", request.get("retryPolicyConfiguration").deepCopy());
            run.set("retryPolicyExecution", object("currentAttemptCount", 1));
        }

        String eksCluster = cluster.path("containerProvider").path("id").textValue();
        if (eksService.findAuthenticationCluster(regionResolver.getAccountId(), regionResolver.getRegion(), eksCluster)
                .isEmpty()) {
            // AWS's "Common errors when running jobs": a job run on a cluster EKS does not have.
            finish(run, FAILED, now);
            run.put("stateDetails", "Cluster " + eksCluster + " does not exist.");
            run.put("failureReason", "CLUSTER_UNAVAILABLE");
        } else if (jobRunDurationSeconds <= 0) {
            finish(run, COMPLETED, now);
        } else {
            run.put("state", RUNNING);
            run.put(DUE_AT, now.plusSeconds(jobRunDurationSeconds).toEpochMilli());
        }
        jobRuns.put(arn, run);
        return pick(run, "id", "name", "arn", "virtualClusterId");
    }

    public synchronized ObjectNode describeJobRun(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("DescribeJobRun", object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode run = jobRun(virtualClusterId, id).orElseThrow(() -> notFound(jobRunMissing(id)));
        return wrap("jobRun", run);
    }

    public synchronized ObjectNode listJobRuns(String virtualClusterId, ListQuery query, String name) {
        ObjectNode shape = query.shape().put("virtualClusterId", virtualClusterId);
        putText(shape, "name", name);
        EmrContainersRequestShapes.check("ListJobRuns", shape);
        String prefix = arn(VIRTUAL_CLUSTERS + virtualClusterId + JOB_RUNS);
        List<ObjectNode> matching = jobRuns.scan(key -> key.startsWith(prefix)).stream()
                .map(this::advance)
                .filter(query::matches)
                .filter(run -> name == null || name.equals(run.path("name").textValue()))
                .toList();
        return page(matching, query, "jobRuns");
    }

    public synchronized ObjectNode cancelJobRun(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("CancelJobRun", object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode run = jobRun(virtualClusterId, id).orElseThrow(() -> invalid(jobRunMissing(id)));
        String state = run.path("state").textValue();
        if (FINISHED_JOB_RUN_STATES.contains(state)) {
            throw invalid("Job run " + id + " is " + state + " and can't be cancelled.");
        }
        ObjectNode cancelled = run.deepCopy();
        finish(cancelled, CANCELLED, now());
        jobRuns.put(cancelled.get("arn").textValue(), cancelled);
        return object("id", id).put("virtualClusterId", virtualClusterId);
    }

    // ── Job templates ────────────────────────────────────────────────────────────

    public synchronized ObjectNode createJobTemplate(ObjectNode request) {
        EmrContainersRequestShapes.check("CreateJobTemplate", request);
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(jobTemplates, arn(JOB_TEMPLATES), clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn", "createdAt");
        }
        String id = newId(JOB_RUN_ID_LENGTH);
        String arn = arn(JOB_TEMPLATES + id);
        ObjectNode template = mapper.createObjectNode();
        template.set("name", request.get("name"));
        template.put("id", id);
        template.put("arn", arn);
        template.put("createdAt", timestamp(now()));
        template.put("createdBy", createdBy());
        template.set("tags", tags(request.get("tags")));
        template.set("jobTemplateData", request.get("jobTemplateData").deepCopy());
        copy(request, template, "kmsKeyArn");
        template.put(CLIENT_TOKEN, clientToken);
        jobTemplates.put(arn, template);
        return pick(template, "id", "name", "arn", "createdAt");
    }

    public synchronized ObjectNode describeJobTemplate(String id) {
        EmrContainersRequestShapes.check("DescribeJobTemplate", object("id", id));
        ObjectNode template = jobTemplates.get(arn(JOB_TEMPLATES + id))
                .orElseThrow(() -> notFound(jobTemplateMissing(id)));
        return wrap("jobTemplate", template);
    }

    public synchronized ObjectNode listJobTemplates(ListQuery query) {
        EmrContainersRequestShapes.check("ListJobTemplates", query.shape());
        List<ObjectNode> matching = jobTemplates.scan(key -> key.startsWith(arn(JOB_TEMPLATES))).stream()
                .filter(query::matches)
                .toList();
        return page(matching, query, "templates");
    }

    public synchronized ObjectNode deleteJobTemplate(String id) {
        EmrContainersRequestShapes.check("DeleteJobTemplate", object("id", id));
        String arn = arn(JOB_TEMPLATES + id);
        if (jobTemplates.get(arn).isEmpty()) {
            throw invalid(jobTemplateMissing(id));
        }
        jobTemplates.delete(arn);
        return object("id", id);
    }

    // ── Managed endpoints ────────────────────────────────────────────────────────

    public synchronized ObjectNode createManagedEndpoint(String virtualClusterId, ObjectNode request) {
        request.put("virtualClusterId", virtualClusterId);
        EmrContainersRequestShapes.check("CreateManagedEndpoint", request);
        ObjectNode cluster = virtualCluster(virtualClusterId)
                .orElseThrow(() -> notFound(virtualClusterMissing(virtualClusterId)));
        requireState(cluster, RUNNING, "Endpoint", "create");
        String prefix = cluster.get("arn").textValue() + ENDPOINTS;
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(endpoints, prefix, clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn", "virtualClusterId");
        }
        String id = newId(ENDPOINT_ID_LENGTH);
        String arn = prefix + id;
        ObjectNode endpoint = mapper.createObjectNode();
        endpoint.put("id", id);
        endpoint.set("name", request.get("name"));
        endpoint.put("arn", arn);
        endpoint.put("virtualClusterId", virtualClusterId);
        endpoint.set("type", request.get("type"));
        endpoint.put("state", ACTIVE);
        endpoint.set("releaseLabel", request.get("releaseLabel"));
        endpoint.set("executionRoleArn", request.get("executionRoleArn"));
        copy(request, endpoint, "certificateArn", "configurationOverrides");
        endpoint.put("createdAt", timestamp(now()));
        endpoint.set("tags", tags(request.get("tags")));
        endpoint.put(CLIENT_TOKEN, clientToken);
        endpoints.put(arn, endpoint);
        return pick(endpoint, "id", "name", "arn", "virtualClusterId");
    }

    public synchronized ObjectNode describeManagedEndpoint(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("DescribeManagedEndpoint",
                object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode endpoint = endpoint(virtualClusterId, id).orElseThrow(() -> notFound(endpointMissing(id)));
        return wrap("endpoint", endpoint);
    }

    public synchronized ObjectNode listManagedEndpoints(String virtualClusterId, ListQuery query, List<String> types) {
        ObjectNode shape = query.shape().put("virtualClusterId", virtualClusterId);
        putList(shape, "types", types);
        EmrContainersRequestShapes.check("ListManagedEndpoints", shape);
        String prefix = arn(VIRTUAL_CLUSTERS + virtualClusterId + ENDPOINTS);
        List<ObjectNode> matching = endpoints.scan(key -> key.startsWith(prefix)).stream()
                .filter(query::matches)
                .filter(endpoint -> types == null || types.isEmpty()
                        || types.contains(endpoint.path("type").textValue()))
                .toList();
        return page(matching, query, "endpoints");
    }

    public synchronized ObjectNode deleteManagedEndpoint(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("DeleteManagedEndpoint",
                object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode endpoint = endpoint(virtualClusterId, id).orElseThrow(() -> invalid(endpointMissing(id)));
        if (!FINISHED_ENDPOINT_STATES.contains(endpoint.path("state").textValue())) {
            ObjectNode terminated = endpoint.deepCopy();
            terminated.put("state", TERMINATED);
            endpoints.put(terminated.get("arn").textValue(), terminated);
        }
        return object("id", id).put("virtualClusterId", virtualClusterId);
    }

    public synchronized ObjectNode getManagedEndpointSessionCredentials(String virtualClusterId, String endpointId,
                                                                        ObjectNode request) {
        request.put("virtualClusterIdentifier", virtualClusterId);
        request.put("endpointIdentifier", endpointId);
        EmrContainersRequestShapes.check("GetManagedEndpointSessionCredentials", request);
        ObjectNode endpoint = endpoint(virtualClusterId, endpointId)
                .orElseThrow(() -> notFound(endpointMissing(endpointId)));
        if (!ACTIVE.equals(endpoint.path("state").textValue())) {
            throw invalid("Endpoint " + endpointId + " is " + endpoint.path("state").textValue()
                    + " and can't issue session credentials.");
        }
        // The API reference: TOKEN is the only credential type, and the default.
        if (!TOKEN_CREDENTIAL.equals(request.get("credentialType").textValue())) {
            throw invalid("Credential type " + request.get("credentialType").textValue() + " is not supported.");
        }
        // The API reference: "The default duration is 15 minutes and the maximum is 12 hours."
        int seconds = request.hasNonNull("durationInSeconds")
                ? request.get("durationInSeconds").intValue() : DEFAULT_SESSION_SECONDS;
        if (seconds < 1 || seconds > MAX_SESSION_SECONDS) {
            throw invalid("durationInSeconds must be between 1 and " + MAX_SESSION_SECONDS + ".");
        }
        ObjectNode credentials = object("token", sessionToken());
        ObjectNode response = object("id", newId(JOB_RUN_ID_LENGTH));
        response.set("credentials", credentials);
        response.set("endpointCredentials", credentials.deepCopy());
        response.put("expiresAt", timestamp(now().plusSeconds(seconds)));
        return response;
    }

    // ── Security configurations ──────────────────────────────────────────────────

    public synchronized ObjectNode createSecurityConfiguration(ObjectNode request) {
        EmrContainersRequestShapes.check("CreateSecurityConfiguration", request);
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(securityConfigurations, arn(SECURITY_CONFIGURATIONS),
                clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn");
        }
        String id = newId(JOB_RUN_ID_LENGTH);
        String arn = arn(SECURITY_CONFIGURATIONS + id);
        ObjectNode configuration = mapper.createObjectNode();
        configuration.put("id", id);
        configuration.set("name", request.get("name"));
        configuration.put("arn", arn);
        configuration.put("createdAt", timestamp(now()));
        configuration.put("createdBy", createdBy());
        configuration.set("securityConfigurationData", request.get("securityConfigurationData").deepCopy());
        configuration.set("tags", tags(request.get("tags")));
        configuration.put(CLIENT_TOKEN, clientToken);
        securityConfigurations.put(arn, configuration);
        return pick(configuration, "id", "name", "arn");
    }

    public synchronized ObjectNode describeSecurityConfiguration(String id) {
        EmrContainersRequestShapes.check("DescribeSecurityConfiguration", object("id", id));
        ObjectNode configuration = securityConfigurations.get(arn(SECURITY_CONFIGURATIONS + id))
                .orElseThrow(() -> notFound(securityConfigurationMissing(id)));
        return wrap("securityConfiguration", configuration);
    }

    public synchronized ObjectNode listSecurityConfigurations(ListQuery query) {
        EmrContainersRequestShapes.check("ListSecurityConfigurations", query.shape());
        List<ObjectNode> matching = securityConfigurations.scan(key -> key.startsWith(arn(SECURITY_CONFIGURATIONS)))
                .stream()
                .filter(query::matches)
                .toList();
        return page(matching, query, "securityConfigurations");
    }

    public synchronized ObjectNode deleteSecurityConfiguration(String id) {
        EmrContainersRequestShapes.check("DeleteSecurityConfiguration", object("id", id));
        String arn = arn(SECURITY_CONFIGURATIONS + id);
        if (securityConfigurations.get(arn).isEmpty()) {
            throw invalid(securityConfigurationMissing(id));
        }
        securityConfigurations.delete(arn);
        return object("id", id);
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────

    public synchronized Map<String, String> listTags(String resourceArn) {
        EmrContainersRequestShapes.check("ListTagsForResource", object("resourceArn", resourceArn));
        return tagMap(taggable(resourceArn).resource());
    }

    public synchronized void tagResource(String resourceArn, Map<String, String> tags) {
        ObjectNode request = object("resourceArn", resourceArn);
        request.set("tags", mapper.valueToTree(tags));
        EmrContainersRequestShapes.check("TagResource", request);
        Taggable target = activeTaggable(resourceArn);
        ObjectNode updated = target.resource().deepCopy();
        ObjectNode merged = tags(updated.get("tags"));
        merged.setAll(tags(request.get("tags")));
        if (merged.size() > MAX_TAGS) {
            throw tooManyTags();
        }
        updated.set("tags", merged);
        target.storage().put(resourceArn, updated);
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys) {
        ObjectNode request = object("resourceArn", resourceArn);
        putList(request, "tagKeys", tagKeys == null ? List.of() : tagKeys);
        EmrContainersRequestShapes.check("UntagResource", request);
        Taggable target = activeTaggable(resourceArn);
        ObjectNode updated = target.resource().deepCopy();
        ObjectNode remaining = tags(updated.get("tags"));
        if (tagKeys != null) {
            remaining.remove(tagKeys);
        }
        updated.set("tags", remaining);
        target.storage().put(resourceArn, updated);
    }

    @Override
    public synchronized void clear() {
        virtualClusters.clear();
        jobRuns.clear();
        endpoints.clear();
        jobTemplates.clear();
        securityConfigurations.clear();
    }

    // ── Paging and filtering ─────────────────────────────────────────────────────

    /** The query members every List operation shares, parsed from the query string. */
    public record ListQuery(Instant createdAfter, Instant createdBefore, List<String> states, Integer maxResults,
                            String nextToken) {

        public static ListQuery of(String createdAfter, String createdBefore, List<String> states,
                                   String maxResults, String nextToken) {
            return new ListQuery(instant("createdAfter", createdAfter), instant("createdBefore", createdBefore),
                    states == null || states.isEmpty() ? null : List.copyOf(states),
                    Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        }

        /** The members the request shapes check, as the request carries them; timestamps are parsed above. */
        ObjectNode shape() {
            ObjectNode shape = JsonNodeFactory.instance.objectNode();
            putList(shape, "states", states);
            if (maxResults != null) {
                shape.put("maxResults", maxResults);
            }
            putText(shape, "nextToken", nextToken);
            return shape;
        }

        boolean matches(ObjectNode resource) {
            Instant createdAt = Instant.parse(resource.get("createdAt").textValue());
            return (createdAfter == null || !createdAt.isBefore(createdAfter))
                    && (createdBefore == null || !createdAt.isAfter(createdBefore))
                    && (states == null || states.contains(resource.path("state").textValue()));
        }

        private static Instant instant(String member, String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException notIso) {
                try {
                    BigDecimal seconds = new BigDecimal(value);
                    return Instant.ofEpochMilli(seconds.movePointRight(3).longValue());
                } catch (NumberFormatException notEpoch) {
                    throw new AwsException("ValidationException", "Invalid value for " + member + ": " + value, 400);
                }
            }
        }
    }

    private ObjectNode page(List<ObjectNode> resources, ListQuery query, String member) {
        if (query.maxResults() != null && query.maxResults() < 1) {
            throw invalid("maxResults must be at least 1.");
        }
        int limit = query.maxResults() != null ? query.maxResults() : DEFAULT_PAGE_SIZE;
        PaginatedResult<ObjectNode> page = Pagination.paginate(resources, resource -> resource.get("id").textValue(),
                limit, query.nextToken(), member, token -> invalid("Invalid nextToken."));
        ArrayNode items = mapper.createArrayNode();
        for (ObjectNode resource : page.items()) {
            items.add(view(resource));
        }
        ObjectNode response = mapper.createObjectNode();
        response.set(member, items);
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    // ── Lookups ──────────────────────────────────────────────────────────────────

    private Optional<ObjectNode> virtualCluster(String id) {
        return virtualClusters.get(arn(VIRTUAL_CLUSTERS + id));
    }

    private Optional<ObjectNode> jobRun(String virtualClusterId, String id) {
        return jobRuns.get(arn(VIRTUAL_CLUSTERS + virtualClusterId + JOB_RUNS + id)).map(this::advance);
    }

    private Optional<ObjectNode> endpoint(String virtualClusterId, String id) {
        return endpoints.get(arn(VIRTUAL_CLUSTERS + virtualClusterId + ENDPOINTS + id));
    }

    private Optional<ObjectNode> byClientToken(AccountAwareStorageBackend<ObjectNode> storage, String prefix,
                                               String clientToken) {
        return storage.scan(key -> key.startsWith(prefix)).stream()
                .filter(resource -> clientToken.equals(resource.path(CLIENT_TOKEN).textValue()))
                .findFirst();
    }

    /**
     * A resource TagResource can name, as the model's ARN pattern limits it to: a virtual cluster, or
     * one of its job runs or managed endpoints.
     */
    private record Taggable(AccountAwareStorageBackend<ObjectNode> storage, ObjectNode resource, boolean active) {}

    private Taggable taggable(String resourceArn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException malformed) {
            throw invalid("Invalid resourceArn: " + resourceArn);
        }
        // A resource is the request's Region's, as every other operation finds it: another Region's ARN names none.
        if (!regionResolver.getRegion().equals(parsed.region())) {
            throw resourceMissing(resourceArn);
        }
        String resource = parsed.resource();
        if (VIRTUAL_CLUSTER_ARN.matcher(resource).matches()) {
            ObjectNode cluster = virtualClusters.get(resourceArn).orElseThrow(() -> resourceMissing(resourceArn));
            return new Taggable(virtualClusters, cluster, !TERMINATED.equals(cluster.path("state").textValue()));
        }
        Matcher nested = NESTED_ARN.matcher(resource);
        if (nested.matches() && "jobruns".equals(nested.group(2))) {
            ObjectNode run = jobRuns.get(resourceArn).map(this::advance)
                    .orElseThrow(() -> resourceMissing(resourceArn));
            return new Taggable(jobRuns, run, !FINISHED_JOB_RUN_STATES.contains(run.path("state").textValue()));
        }
        if (nested.matches()) {
            ObjectNode endpoint = endpoints.get(resourceArn).orElseThrow(() -> resourceMissing(resourceArn));
            return new Taggable(endpoints, endpoint,
                    !FINISHED_ENDPOINT_STATES.contains(endpoint.path("state").textValue()));
        }
        throw resourceMissing(resourceArn);
    }

    /** AWS: "You can tag new or existing virtual clusters and job runs that are in active states." */
    private Taggable activeTaggable(String resourceArn) {
        Taggable target = taggable(resourceArn);
        if (!target.active()) {
            throw invalid("Resource " + resourceArn + " is " + target.resource().path("state").textValue()
                    + " and can't be tagged.");
        }
        return target;
    }

    // ── Job templates in a job run ───────────────────────────────────────────────

    /**
     * The template's job data with every {@code ${name}} replaced by the parameter's value in the
     * request, or else its default. A declared parameter with neither is refused, as is a value a
     * {@code NUMBER} parameter cannot read as a number; a placeholder no parameter declares stays.
     */
    private ObjectNode jobTemplateData(String templateId, JsonNode parameters) {
        ObjectNode template = jobTemplates.get(arn(JOB_TEMPLATES + templateId))
                .orElseThrow(() -> notFound(jobTemplateMissing(templateId)));
        ObjectNode data = template.get("jobTemplateData").deepCopy();
        JsonNode declared = data.remove("parameterConfiguration");
        Map<String, String> values = new HashMap<>();
        if (declared != null) {
            for (Map.Entry<String, JsonNode> parameter : declared.properties()) {
                String name = parameter.getKey();
                JsonNode given = parameters == null ? null : parameters.get(name);
                String value = given != null && !given.isNull()
                        ? given.asText() : parameter.getValue().path("defaultValue").textValue();
                if (value == null) {
                    throw invalid("Job template parameter " + name + " requires a value.");
                }
                boolean number = "NUMBER".equals(parameter.getValue().path("type").textValue());
                if (number && !NUMBER.matcher(value).matches()) {
                    throw invalid("Job template parameter " + name + " must be a number.");
                }
                values.put(name, value);
            }
        }
        return (ObjectNode) substitute(data, values);
    }

    private JsonNode substitute(JsonNode node, Map<String, String> values) {
        if (node.isTextual()) {
            Matcher matcher = TEMPLATE_PARAMETER.matcher(node.textValue());
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(replaced,
                        Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), matcher.group())));
            }
            matcher.appendTail(replaced);
            return TextNode.valueOf(replaced.toString());
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(field.getKey(), substitute(field.getValue(), values));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            for (JsonNode item : node) {
                copy.add(substitute(item, values));
            }
            return copy;
        }
        return node;
    }

    // ── States ───────────────────────────────────────────────────────────────────

    /** Completes a RUNNING job run whose duration has passed, and stores the change. */
    private ObjectNode advance(ObjectNode run) {
        if (!RUNNING.equals(run.path("state").textValue()) || !run.has(DUE_AT)) {
            return run;
        }
        Instant dueAt = Instant.ofEpochMilli(run.get(DUE_AT).longValue());
        if (now().isBefore(dueAt)) {
            return run;
        }
        ObjectNode completed = run.deepCopy();
        finish(completed, COMPLETED, dueAt);
        jobRuns.put(completed.get("arn").textValue(), completed);
        return completed;
    }

    private void finish(ObjectNode run, String state, Instant at) {
        run.put("state", state);
        run.put("finishedAt", timestamp(at));
        run.remove(DUE_AT);
    }

    /**
     * AWS's "Common errors when running jobs": "Virtual cluster state {state} is not valid to create
     * resource JobRun."; the same wording names the resource and the action for the others.
     */
    private void requireState(ObjectNode cluster, String state, String resource, String action) {
        String current = cluster.path("state").textValue();
        if (!state.equals(current)) {
            throw invalid("Virtual cluster state " + current + " is not valid to " + action + " resource "
                    + resource + ".");
        }
    }

    private ObjectNode withSchedulerStatus(ObjectNode cluster) {
        JsonNode scheduler = cluster.get("schedulerConfiguration");
        if (scheduler == null || scheduler.isEmpty()) {
            return cluster;
        }
        String prefix = cluster.get("arn").textValue() + JOB_RUNS;
        List<ObjectNode> runs = jobRuns.scan(key -> key.startsWith(prefix)).stream().map(this::advance).toList();
        ObjectNode status = mapper.createObjectNode();
        status.put("currentInQueueJobRuns", count(runs, QUEUED_JOB_RUN_STATES::contains));
        status.put("currentConcurrentJobRuns", count(runs, RUNNING::equals));
        ObjectNode withStatus = cluster.deepCopy();
        withStatus.set("schedulerStatus", status);
        return withStatus;
    }

    private static long count(Collection<ObjectNode> runs, Predicate<String> state) {
        return runs.stream().filter(run -> state.test(run.path("state").textValue())).count();
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────────

    private ObjectNode wrap(String member, ObjectNode resource) {
        ObjectNode response = mapper.createObjectNode();
        response.set(member, view(resource));
        return response;
    }

    /** The resource as AWS answers it: without Floci's own members. */
    private static ObjectNode view(ObjectNode resource) {
        ObjectNode copy = resource.deepCopy();
        List<String> internal = new ArrayList<>();
        copy.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("_")) {
                internal.add(name);
            }
        });
        copy.remove(internal);
        return copy;
    }

    private ObjectNode pick(ObjectNode resource, String... members) {
        ObjectNode picked = mapper.createObjectNode();
        copy(resource, picked, members);
        return picked;
    }

    private static void copy(JsonNode from, ObjectNode to, String... members) {
        for (String member : members) {
            JsonNode value = from.get(member);
            if (value != null && !value.isNull()) {
                to.set(member, value.deepCopy());
            }
        }
    }

    private ObjectNode object(String member, String value) {
        return mapper.createObjectNode().put(member, value);
    }

    private ObjectNode object(String member, int value) {
        return mapper.createObjectNode().put(member, value);
    }

    private ObjectNode tags(JsonNode tags) {
        return tags != null && tags.isObject() ? ((ObjectNode) tags).deepCopy() : mapper.createObjectNode();
    }

    private static Map<String, String> tagMap(ObjectNode resource) {
        Map<String, String> tags = new HashMap<>();
        resource.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        return tags;
    }

    private static void putText(ObjectNode node, String member, String value) {
        if (value != null) {
            node.put(member, value);
        }
    }

    private static void putList(ObjectNode node, String member, List<String> values) {
        if (values != null) {
            ArrayNode array = node.putArray(member);
            values.forEach(array::add);
        }
    }

    // ── Identity, time and errors ────────────────────────────────────────────────

    private String arn(String resource) {
        return regionResolver.buildArn(SERVICE, regionResolver.getRegion(), regionResolver.getAccountId(), resource);
    }

    private String createdBy() {
        String accessKeyId = requestContext != null ? requestContext.getAccessKeyId() : null;
        return iamService.resolveCallerArn(accessKeyId)
                .orElseGet(() -> regionResolver.buildGlobalArn("iam", regionResolver.getAccountId(), "root"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String timestamp(Instant instant) {
        return instant.toString();
    }

    /** An id of {@code length} lowercase letters and digits, as the model's {@code [0-9a-z]+} allows. */
    static String newId(int length) {
        StringBuilder id = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String sessionToken() {
        byte[] bytes = new byte[48];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String virtualClusterMissing(String id) {
        // AWS's "Common errors when running jobs" for StartJobRun.
        return "Virtual cluster " + id + " doesn't exist.";
    }

    private static String jobRunMissing(String id) {
        return "Job run " + id + " doesn't exist.";
    }

    private static String endpointMissing(String id) {
        return "Endpoint " + id + " doesn't exist.";
    }

    private static String jobTemplateMissing(String id) {
        return "Job template " + id + " doesn't exist.";
    }

    private static String securityConfigurationMissing(String id) {
        return "Security configuration " + id + " doesn't exist.";
    }

    private static AwsException tooManyTags() {
        // The development guide's tag restrictions: "Maximum number of tags per resource – 50".
        return invalid("A resource can have at most " + MAX_TAGS + " tags.");
    }

    private static AwsException resourceMissing(String resourceArn) {
        return notFound("Resource " + resourceArn + " doesn't exist.");
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
