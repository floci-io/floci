package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.LinkSigner;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrPersistentAppUi;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cluster's application user interfaces: the persistent ones "hosted off-cluster" (Amazon EMR Management Guide, "View
 * persistent application user interfaces in Amazon EMR") and the on-cluster ones, both reached through presigned URLs.
 * In the docker runner mode, an on-cluster UI that runs on the cluster's primary node, its ResourceManager, an
 * application master through it, its MapReduce JobHistory server, the NodeManager of a primary node that works, or the
 * Spark History Server of a cluster with Spark, opens for real through {@link EmrAppUiProxy}, on a host of its own
 * under {@link #UI_HOST}, as AWS's are under "emrappui-prod.us-east-1.amazonaws.com" (Management Guide, "View Amazon
 * EMR application history"). Floci keeps a persistent application UI's record, but serves no persistent UI, Tez UI or
 * YARN timeline server: a link to one of those, and to any on-cluster UI elsewhere, opens a page of Floci's own about
 * the cluster
 * ({@link EmrAppUiController}). Either link is valid for {@link #VALIDITY}: the page's is signed with a key of this
 * process, and a proxied interface's host holds a secret this process gave it.
 */
@ApplicationScoped
public class EmrAppUiService {

    /** Where Floci serves the page a presigned URL opens. */
    static final String PATH = "/_aws/emr/app-ui";
    // No source gives how long AWS's presigned URLs last; Floci's open its page for an hour, as its other links do.
    static final Duration VALIDITY = Duration.ofHours(1);
    static final List<String> PROFILER_TYPES = List.of("SHS", "TEZUI", "YTS");
    static final List<String> PERSISTENT_TYPES = List.of("SHS", "TEZ", "YTS");
    static final List<String> ON_CLUSTER_TYPES = List.of("SparkHistoryServer", "YarnTimelineService", "TezUI",
            "ApplicationMaster", "JobHistoryServer", "ResourceManager");
    // "For each AWS account, the default limit for active application UIs is 200."
    static final int MAX_PERSISTENT_APP_UIS = 200;
    // "The logs are available for 30 days after the application ends."
    static final Duration LOGS_KEPT = Duration.ofDays(30);
    // No source gives AWS's status values; Floci reports this one for every persistent application UI.
    static final String ACTIVE = "ACTIVE";
    /** The label under Floci's host name an on-cluster UI's own host is in. */
    static final String UI_HOST = "emrappui";
    // A link's secret: 26 characters of base32, 130 random bits, in lower case, as a host name is read without case.
    private static final int SECRET_LENGTH = 26;
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    static final String RESOURCE_MANAGER = "ResourceManager";
    static final String APPLICATION_MASTER = "ApplicationMaster";
    static final String JOB_HISTORY_SERVER = "JobHistoryServer";
    static final String SPARK_HISTORY_SERVER = "SparkHistoryServer";
    // No source gives AWS's wording for these; they are Floci's.
    static final String NO_SUCH_INTERFACE = "This link opens none of the cluster's interfaces on that port.";
    static final String CLUSTER_ENDED = "Cluster %s is no longer running, and its on-cluster application UIs are"
            + " there only while it is RUNNING or WAITING.";
    // How long a port found on the cluster's primary node is used before it is asked for again.
    private static final Duration ENDPOINT_RECHECK = Duration.ofSeconds(10);
    static final String NOT_READY = "The cluster's primary node is not running yet: it starts once a step runs, or a"
            + " link to one of its interfaces is asked for. Try again in a moment.";
    private static final Pattern APPLICATION_ID = Pattern.compile("application_(\\d+_\\d+)");

    private final AccountAwareStorageBackend<EmrPersistentAppUi> store;
    private final EmrService clusters;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final String baseUrl;
    private final LinkSigner signer;
    // The links to proxied interfaces this process has given, by their secret, until they expire.
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Inject
    public EmrAppUiService(StorageFactory storageFactory, EmrService clusters, RegionResolver regionResolver,
                           EmulatorConfig config) {
        this(storageFactory.create("emr", "emr-persistent-app-uis.json",
                        new TypeReference<Map<String, EmrPersistentAppUi>>() {}),
                clusters, regionResolver, Clock.systemUTC(), config.effectiveBaseUrl(), LinkSigner.withNewKey());
    }

    EmrAppUiService(AccountAwareStorageBackend<EmrPersistentAppUi> store, EmrService clusters,
                    RegionResolver regionResolver, Clock clock, String baseUrl, LinkSigner signer) {
        this.store = store;
        this.clusters = clusters;
        this.regionResolver = regionResolver;
        this.clock = clock;
        String base = baseUrl == null ? "" : baseUrl;
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.signer = signer;
    }

    /** CreatePersistentAppUI's answer: the new UI, and whether its cluster is a runtime role enabled one. */
    public record Created(EmrPersistentAppUi appUi, boolean runtimeRoleEnabledCluster) {
    }

    /**
     * Creates a persistent application UI for an EMR cluster of the account in the Region, which its
     * {@code TargetResourceArn} names. Floci keeps none for an EMR on EKS virtual cluster. The guide's rules apply:
     * the persistent Spark History Server is there "Starting with Amazon EMR version 5.25.0", and the "Tez UI and
     * YARN timeline server persistent application interfaces are available starting with Amazon EMR version 5.30.1",
     * each for a cluster that installs Spark, Tez or Hadoop; the feature "is currently not available for EMR clusters
     * with multiple master nodes"; and an account has at most {@link #MAX_PERSISTENT_APP_UIS}. Each refusal is
     * InvalidRequestException in Floci's own wording, as AWS's is not recorded.
     */
    public synchronized Created create(String targetResourceArn, String profilerType, Map<String, String> tags) {
        List<String> violations = new ArrayList<>();
        if (targetResourceArn == null) {
            violations.add(SmithyValidationErrors.notNull("targetResourceArn"));
        }
        EmrService.checkEnum(violations, "profilerType", profilerType, PROFILER_TYPES);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        EmrCluster cluster = clusters.describeCluster(clusterIdOf(targetResourceArn));
        if (!EmrService.releaseAtLeast(cluster.getReleaseLabel(), 5, 25, 0)) {
            throw EmrService.invalid("Persistent application UIs need a cluster of release emr-5.25.0 or later.");
        }
        if (EmrSessions.highlyAvailable(cluster)) {
            throw EmrService.invalid("Persistent application UIs are not available for a cluster with multiple"
                    + " primary nodes.");
        }
        List<String> types = typesOf(cluster);
        if (types.isEmpty()) {
            throw EmrService.invalid("Cluster " + cluster.getId() + " installs none of Spark, Tez and Hadoop, whose"
                    + " application UIs persist.");
        }
        if (profilerType != null) {
            // The profiler asked for is the one interface the UI has: TEZUI is the Tez UI, as PersistentAppUIType's
            // TEZ names it.
            String type = "TEZUI".equals(profilerType) ? "TEZ" : profilerType;
            if (!types.contains(type)) {
                throw EmrService.invalid("Cluster " + cluster.getId() + " has no " + profilerType
                        + " interface to persist; it has " + String.join(", ", types) + ".");
            }
            types = List.of(type);
        }
        if (activeAppUis() >= MAX_PERSISTENT_APP_UIS) {
            throw EmrService.invalid("The account already has " + MAX_PERSISTENT_APP_UIS
                    + " persistent application UIs.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        EmrPersistentAppUi appUi = new EmrPersistentAppUi();
        appUi.setId("p-" + EmrService.randomId(13));
        appUi.setClusterId(cluster.getId());
        appUi.setTypes(types);
        appUi.setStatus(ACTIVE);
        appUi.setAuthorId(regionResolver.getAccountId());
        appUi.setCreationTime(now);
        appUi.setLastModifiedTime(now);
        appUi.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        appUi.setRegion(regionResolver.getRegion());
        store.put(appUi.getId(), appUi);
        return new Created(copy(appUi), clusters.runtimeRoleEnabled(cluster));
    }

    /**
     * The account's active persistent application UIs: those whose cluster runs, or ended within the 30 days for which
     * "The logs are available ... after the application ends"; one whose cluster has gone, or ended earlier, frees its
     * place.
     */
    private long activeAppUis() {
        Instant cutoff = clock.instant().minus(LOGS_KEPT);
        return store.scan(key -> true).stream()
                .filter(appUi -> {
                    EmrCluster cluster = RequestScopes.callAs(regionResolver.getAccountId(), appUi.getRegion(),
                            () -> clusters.findDescribedCluster(appUi.getClusterId()).orElse(null));
                    return cluster != null && (cluster.getEndDateTime() == null
                            || cluster.getEndDateTime().isAfter(cutoff));
                })
                .count();
    }

    public synchronized EmrPersistentAppUi describe(String appUiId) {
        return copy(require(appUiId));
    }

    /**
     * A presigned URL to one of the persistent UI's types, by default its first, for the application asked for. It is
     * ready at once, as Floci's page has nothing to prepare.
     */
    public synchronized String persistentUrl(String appUiId, String type, String applicationId) {
        List<String> violations = new ArrayList<>();
        EmrService.checkEnum(violations, "persistentAppUIType", type, PERSISTENT_TYPES);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        EmrPersistentAppUi appUi = require(appUiId);
        if (type != null && !appUi.getTypes().contains(type)) {
            throw EmrService.invalid("Persistent application UI " + appUiId + " has no " + type + " interface.");
        }
        return url(appUi.getClusterId(), type != null ? type : appUi.getTypes().get(0), applicationId);
    }

    /** A presigned URL, and whether what it opens is ready: none for a dry run. */
    public record Presigned(String url, boolean ready) {
    }

    /**
     * A presigned URL to one of the cluster's on-cluster UIs, by default its ResourceManager, which there is only while
     * the cluster runs or waits; for a dry run the request is checked and no URL is made. One Floci proxies is ready
     * once the cluster's primary node is: until then, as on a cluster no step has run on yet, the node starts, and the
     * URL is not ready, as "Used to determine if the presigned URL is ready" says of {@code PresignedURLReady}.
     */
    public Presigned onClusterUrl(String clusterId, String type, String applicationId, boolean dryRun) {
        List<String> violations = new ArrayList<>();
        if (clusterId == null) {
            violations.add(SmithyValidationErrors.notNull("clusterId"));
        }
        EmrService.checkEnum(violations, "onClusterAppUIType", type, ON_CLUSTER_TYPES);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        EmrCluster cluster = clusters.describeCluster(clusterId);
        if (!EmrClusterLifecycle.RUNNING.equals(cluster.getState())
                && !EmrClusterLifecycle.WAITING.equals(cluster.getState())) {
            throw EmrService.invalid("Cluster " + clusterId + " is " + cluster.getState()
                    + ", and its on-cluster application UIs are there only while it is RUNNING or WAITING.");
        }
        if (dryRun) {
            return new Presigned(null, true);
        }
        String asked = type != null ? type : RESOURCE_MANAGER;
        Optional<EmrService.WebInterfaces> served = clusters.webInterfaces(cluster);
        Integer port = served.map(interfaces -> portOf(asked, interfaces)).orElse(null);
        if (port == null) {
            return new Presigned(url(clusterId, asked, applicationId), true);
        }
        Instant now = clock.instant();
        grants.values().removeIf(grant -> !now.isBefore(grant.expires()));
        String secret = secret();
        grants.put(secret, new Grant(regionResolver.getAccountId(), regionResolver.getRegion(), clusterId,
                served.get().primaryHost(), served.get().ports(), now.plus(VALIDITY), new ConcurrentHashMap<>()));
        boolean ready = clusters.webInterface(clusterId, port).isPresent();
        if (!ready) {
            clusters.startPrimaryNode(clusterId);
        }
        URI base = URI.create(baseUrl);
        return new Presigned(base.getScheme() + "://" + secret + "-" + port + "." + UI_HOST + "."
                + base.getRawAuthority() + landing(asked, applicationId), ready);
    }

    // The interface's port on the primary node, where Floci serves it: an application master's UI through the
    // ResourceManager's web proxy.
    private static Integer portOf(String type, EmrService.WebInterfaces interfaces) {
        int port = switch (type) {
            case RESOURCE_MANAGER, APPLICATION_MASTER -> EmrClusterStorage.RESOURCE_MANAGER_WEB_PORT;
            case JOB_HISTORY_SERVER -> EmrServerlessSparkRunner.JOB_HISTORY_WEB_PORT;
            case SPARK_HISTORY_SERVER -> EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT;
            default -> 0;
        };
        return interfaces.ports().contains(port) ? port : null;
    }

    // Where the link lands: the interface's page of the application asked for, else its first. A MapReduce job's id
    // is its application's, job_ in place of application_.
    private static String landing(String type, String applicationId) {
        String application = applicationId != null ? pathSegment(applicationId) : null;
        return switch (type) {
            case APPLICATION_MASTER -> application != null ? "/proxy/" + application + "/" : "/cluster/apps/RUNNING";
            case JOB_HISTORY_SERVER -> {
                Matcher matcher = applicationId != null ? APPLICATION_ID.matcher(applicationId) : null;
                yield matcher != null && matcher.matches() ? "/jobhistory/job/job_" + matcher.group(1) : "/jobhistory";
            }
            case SPARK_HISTORY_SERVER -> application != null ? "/history/" + application + "/" : "/";
            default -> application != null ? "/cluster/app/" + application : "/cluster";
        };
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String secret() {
        StringBuilder secret = new StringBuilder(SECRET_LENGTH);
        for (int i = 0; i < SECRET_LENGTH; i++) {
            secret.append(BASE32.charAt(random.nextInt(BASE32.length())));
        }
        return secret.toString();
    }

    /**
     * A link to a proxied interface: the cluster's, as the account in the Region; its primary node's name and web
     * interfaces' ports; when it expires; and where Floci reaches each port, once found.
     */
    private record Grant(String accountId, String region, String clusterId, String primaryHost, Set<Integer> ports,
                         Instant expires, Map<Integer, Found> endpoints) {
    }

    // Where Floci found a port of the cluster's own primary node's container, and when, which is asked again once
    // ENDPOINT_RECHECK has passed: a port the node no longer has may be another container's by then.
    private record Found(ContainerLifecycleManager.EndpointInfo endpoint, Instant at) {
    }

    /**
     * Where a request to a proxied interface's host goes: the interface's address on the primary node, and each link
     * to one of the node's interfaces its pages hold, by the node's name, as the browser reaches that interface; else
     * why it is refused, with its HTTP status.
     */
    public record UiRoute(String host, int port, Map<String, String> rewrites, Runnable unreachable, int status,
                          String refusal) {

        static UiRoute refused(int status, String refusal) {
            return new UiRoute(null, 0, Map.of(), () -> { }, status, refusal);
        }
    }

    /**
     * The route of a request whose {@code Host} is {@code hostHeader}, over {@code scheme}; null where the host is not
     * a proxied interface's, {@code <secret>-<port>.}{@value #UI_HOST}{@code .<Floci's host>}.
     */
    public UiRoute route(String hostHeader, String scheme) {
        String host = hostHeader.toLowerCase(Locale.ROOT);
        int dot = host.indexOf('.');
        int dash = dot > 0 ? host.lastIndexOf('-', dot) : -1;
        if (dash != SECRET_LENGTH || !host.startsWith(UI_HOST + ".", dot + 1)) {
            return null;
        }
        String secret = host.substring(0, dash);
        int port;
        try {
            port = Integer.parseInt(host.substring(dash + 1, dot));
        } catch (NumberFormatException e) {
            return null;
        }
        Grant grant = grants.get(secret);
        if (grant == null || !clock.instant().isBefore(grant.expires())) {
            return UiRoute.refused(403, EmrAppUiController.INVALID);
        }
        if (!grant.ports().contains(port)) {
            return UiRoute.refused(404, NO_SUCH_INTERFACE);
        }
        // The interface is there only while its cluster runs or waits: once it ends, its node's ports are no longer
        // its own.
        EmrCluster cluster = RequestScopes.callAs(grant.accountId(), grant.region(),
                () -> clusters.findDescribedCluster(grant.clusterId()).orElse(null));
        if (cluster == null || !EmrClusterLifecycle.RUNNING.equals(cluster.getState())
                && !EmrClusterLifecycle.WAITING.equals(cluster.getState())) {
            return UiRoute.refused(404, String.format(CLUSTER_ENDED, grant.clusterId()));
        }
        Instant now = clock.instant();
        Found found = grant.endpoints().get(port);
        if (found == null || !now.isBefore(found.at().plus(ENDPOINT_RECHECK))) {
            ContainerLifecycleManager.EndpointInfo endpoint = RequestScopes.callAs(grant.accountId(), grant.region(),
                    () -> clusters.webInterface(grant.clusterId(), port)).orElse(null);
            if (endpoint == null) {
                grant.endpoints().remove(port);
                return UiRoute.refused(503, NOT_READY);
            }
            found = new Found(endpoint, now);
            grant.endpoints().put(port, found);
        }
        // The node's daemons write links to one another by the node's name, which the browser reaches as each
        // interface's own host, under this one's Floci host and port.
        String rest = host.substring(dot);
        Map<String, String> rewrites = new LinkedHashMap<>();
        for (int other : grant.ports()) {
            rewrites.put("http://" + grant.primaryHost() + ":" + other, scheme + "://" + secret + "-" + other + rest);
        }
        Found routed = found;
        return new UiRoute(found.endpoint().host(), found.endpoint().port(), rewrites,
                () -> grant.endpoints().remove(port, routed), 0, null);
    }

    /** What a page link names: an interface of a cluster, for an application or none, and when it expires. */
    public record Link(String accountId, String region, String clusterId, String type, String applicationId,
                       long expires) {
    }

    /**
     * The link a page request's parameters name, or null when one is missing or malformed, the signature is not this
     * process's for them, or the link has expired.
     */
    public Link verified(String accountId, String region, String clusterId, String type, String applicationId,
                         String expires, String signature) {
        if (accountId == null || region == null || clusterId == null || type == null) {
            return null;
        }
        Link link;
        try {
            link = new Link(accountId, region, clusterId, type, applicationId, Long.parseLong(expires));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!signer.verifies(signature, signedFields(link)) || clock.instant().getEpochSecond() >= link.expires()) {
            return null;
        }
        return link;
    }

    /** The cluster a page link names, as it now stands, read in its account and Region. */
    public EmrCluster clusterOf(Link link) {
        return clusters.describeCluster(link.clusterId());
    }

    private String url(String clusterId, String type, String applicationId) {
        Link link = new Link(regionResolver.getAccountId(), regionResolver.getRegion(), clusterId, type,
                applicationId, clock.instant().plus(VALIDITY).getEpochSecond());
        return baseUrl + PATH + "?account=" + encoded(link.accountId()) + "&region=" + encoded(link.region())
                + "&cluster=" + encoded(clusterId) + "&type=" + encoded(type)
                + (applicationId != null ? "&application=" + encoded(applicationId) : "")
                + "&expires=" + link.expires() + "&signature=" + signer.sign(signedFields(link));
    }

    // Over what the link names, a link with no application told apart from one for an application named "null".
    private static String[] signedFields(Link link) {
        return new String[]{link.accountId(), link.region(), link.clusterId(), link.type(),
                link.applicationId() != null ? "application" : "none",
                link.applicationId() != null ? link.applicationId() : "", String.valueOf(link.expires())};
    }

    // The persistent UIs a cluster's release and applications give it.
    private static List<String> typesOf(EmrCluster cluster) {
        String release = cluster.getReleaseLabel();
        List<String> types = new ArrayList<>();
        if (EmrSparkSteps.names(cluster.getApplications(), EmrService.SNAPSHOT_MAPPER, "Spark")) {
            types.add("SHS");
        }
        if (EmrService.releaseAtLeast(release, 5, 30, 1)
                && !(release != null && EmrServerlessSparkRunner.sparkRuntime(release))) {
            if (EmrSparkSteps.names(cluster.getApplications(), EmrService.SNAPSHOT_MAPPER, "Tez")) {
                types.add("TEZ");
            }
            if (EmrSparkSteps.names(cluster.getApplications(), EmrService.SNAPSHOT_MAPPER, "Hadoop")) {
                types.add("YTS");
            }
        }
        return types;
    }

    // The id of the EMR cluster of this account and Region an ARN names; InvalidRequestException for anything else.
    private String clusterIdOf(String targetResourceArn) {
        String clusterId = null;
        if (AwsArnUtils.isArnFor(targetResourceArn, "elasticmapreduce")) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(targetResourceArn);
            if (regionResolver.getAccountId().equals(arn.accountId())
                    && regionResolver.getRegion().equals(arn.region())) {
                clusterId = EmrService.resourceIdOf(arn, EmrService.CLUSTER_RESOURCE);
            }
        }
        if (clusterId == null) {
            throw EmrService.invalid("TargetResourceArn " + targetResourceArn + " is not an EMR cluster of the"
                    + " account in the Region; Floci keeps persistent application UIs for EMR clusters only.");
        }
        return clusterId;
    }

    private EmrPersistentAppUi require(String appUiId) {
        if (appUiId == null) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("persistentAppUIId"));
        }
        return store.get(appUiId)
                .filter(appUi -> regionResolver.getRegion().equals(appUi.getRegion()))
                .orElseThrow(() -> EmrService.invalid("Persistent application UI " + appUiId + " does not exist."));
    }

    private static EmrPersistentAppUi copy(EmrPersistentAppUi appUi) {
        return EmrService.SNAPSHOT_MAPPER.convertValue(appUi, EmrPersistentAppUi.class);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
