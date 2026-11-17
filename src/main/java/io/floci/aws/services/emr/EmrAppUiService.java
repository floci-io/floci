package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.LinkSigner;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrPersistentAppUi;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A cluster's application user interfaces: the persistent ones "hosted off-cluster" (Amazon EMR Management Guide, "View
 * persistent application user interfaces in Amazon EMR") and the on-cluster ones, both reached through presigned URLs.
 * Floci keeps a persistent application UI's record, but serves no Spark History Server, Tez UI, YARN timeline server
 * or on-cluster UI: a presigned URL opens a page of Floci's own about the cluster ({@link EmrAppUiController}), signed
 * with a key of this process and valid for {@link #VALIDITY}.
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

    private final AccountAwareStorageBackend<EmrPersistentAppUi> store;
    private final EmrService clusters;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final String baseUrl;
    private final LinkSigner signer;

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

    /**
     * A presigned URL to one of the cluster's on-cluster UIs, by default its ResourceManager, which there is only while
     * the cluster runs or waits; for a dry run the request is checked and no URL is made.
     */
    public String onClusterUrl(String clusterId, String type, String applicationId, boolean dryRun) {
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
        return dryRun ? null : url(clusterId, type != null ? type : "ResourceManager", applicationId);
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
