package io.floci.aws.services.cloudwatch.dashboards;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.dashboards.model.Dashboard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * CloudWatch dashboards. Dashboards are pure metadata: the DashboardBody is an opaque JSON
 * document that AWS stores and hands back verbatim, so there is no backing behaviour here.
 *
 * <p>Dashboards are global per account, as PutDashboard documents ("All dashboards in your account
 * are global, not region-specific"), so they are keyed by name alone in the account's store and
 * every Region of the account sees the same set. The Region a call arrives through only picks
 * the partition of a new dashboard's ARN.
 */
@ApplicationScoped
public class CloudWatchDashboardsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchDashboardsService.class);

    // FAIL_ON_TRAILING_TOKENS matters here: without it a body of "{} garbage" parses as the
    // leading object and the rest is silently dropped, so a document AWS rejects would be stored.
    /** AWS's documented ceiling for PutDashboard's Tags. */
    private static final int MAX_TAGS = 50;

    /** Separates the Region from the name in the keys dashboards were stored under before. */
    private static final String LEGACY_SEPARATOR = "::";

    /**
     * Orders the legacy copies of one name so the last is the one kept: the most recently
     * modified, and on a tie the one whose key, so whose Region, sorts first.
     */
    private static final Comparator<Map.Entry<String, Dashboard>> LEGACY_WINNER_ORDER =
            Comparator.<Map.Entry<String, Dashboard>>comparingLong(entry -> entry.getValue().getLastModified())
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder());

    private static final ObjectReader JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readerFor(JsonNode.class);

    private final StorageBackend<String, Dashboard> dashboardStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchDashboardsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.dashboardStore = storageFactory.create("cloudwatchmetrics", "cwdashboards.json",
                new TypeReference<Map<String, Dashboard>>() {});
        this.regionResolver = regionResolver;
        foldLegacyRegionKeys();
    }

    // Public rather than package-private so handler tests in the metrics package can build
    // the service over an InMemoryStorage without standing up Quarkus.
    public CloudWatchDashboardsService(StorageBackend<String, Dashboard> dashboardStore,
                                       RegionResolver regionResolver) {
        this.dashboardStore = dashboardStore;
        this.regionResolver = regionResolver;
        foldLegacyRegionKeys();
    }

    /**
     * Moves dashboards stored under the old {@code region::name} keys onto their name, in every
     * account that holds one. Dashboard names allow only letters, digits, dashes and underscores,
     * so a key holding the separator is always a legacy one. When one account stored the same
     * name in several Regions, only one dashboard can survive: the most recently modified copy is
     * kept, a tie going to the Region that sorts first, and the other copies are dropped.
     */
    private void foldLegacyRegionKeys() {
        if (dashboardStore instanceof AccountAwareStorageBackend<Dashboard> accounts) {
            Map<String, Map<String, Dashboard>> legacyByAccount = new TreeMap<>();
            for (AccountEntry<Dashboard> entry : accounts.scanAllAccountEntries(CloudWatchDashboardsService::isLegacyKey)) {
                legacyByAccount.computeIfAbsent(entry.accountId(), ignored -> new LinkedHashMap<>())
                        .put(entry.key(), entry.value());
            }
            legacyByAccount.forEach((accountId, legacy) -> foldLegacyEntries(legacy,
                    key -> accounts.getForAccount(accountId, key),
                    (key, dashboard) -> accounts.putForAccount(accountId, key, dashboard),
                    key -> accounts.deleteForAccount(accountId, key)));
            return;
        }
        Map<String, Dashboard> legacy = new LinkedHashMap<>();
        for (String key : dashboardStore.keys()) {
            if (isLegacyKey(key)) {
                dashboardStore.get(key).ifPresent(dashboard -> legacy.put(key, dashboard));
            }
        }
        foldLegacyEntries(legacy, dashboardStore::get, dashboardStore::put, dashboardStore::delete);
    }

    private static void foldLegacyEntries(Map<String, Dashboard> legacy,
                                          Function<String, Optional<Dashboard>> get,
                                          BiConsumer<String, Dashboard> put,
                                          Consumer<String> delete) {
        if (legacy.isEmpty()) {
            return;
        }
        Map<String, Map.Entry<String, Dashboard>> winners = new TreeMap<>();
        for (Map.Entry<String, Dashboard> entry : legacy.entrySet()) {
            String name = entry.getKey().substring(entry.getKey().indexOf(LEGACY_SEPARATOR) + LEGACY_SEPARATOR.length());
            winners.merge(name, entry, (kept, other) -> LEGACY_WINNER_ORDER.compare(kept, other) >= 0 ? kept : other);
        }
        winners.forEach((name, winner) -> {
            if (get.apply(name).isEmpty()) {
                put.accept(name, withRegionlessArn(winner.getValue()));
            }
        });
        legacy.keySet().forEach(delete);
        LOG.infov("Moved {0} dashboard(s) stored per Region onto account-wide keys ({1} name(s))",
                legacy.size(), winners.size());
    }

    private static boolean isLegacyKey(String key) {
        return key.contains(LEGACY_SEPARATOR);
    }

    /** Creates the dashboard, or replaces it wholesale when the name is already taken. */
    public Dashboard putDashboard(String dashboardName, String dashboardBody, String region) {
        return putDashboard(dashboardName, dashboardBody, Map.of(), region);
    }

    /**
     * Creates the dashboard, or replaces it wholesale when the name is already taken.
     *
     * <p>Tags apply on create only, as AWS documents: a Put that replaces an existing dashboard
     * keeps the tags that dashboard already had rather than taking the ones on this request, so
     * an update cannot quietly retag a resource through an operation that is not a tag operation.
     */
    public Dashboard putDashboard(String dashboardName, String dashboardBody,
                                  Map<String, String> tags, String region) {
        if (dashboardName == null || dashboardName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "DashboardName is required.", 400);
        }
        if (dashboardBody == null) {
            throw new AwsException("InvalidParameterValue", "DashboardBody is required.", 400);
        }
        validateDashboardBody(dashboardBody);

        Dashboard existing = dashboardStore.get(dashboardName).orElse(null);
        Dashboard dashboard = new Dashboard(dashboardName, dashboardArn(region, dashboardName), dashboardBody);
        dashboard.setLastModified(Instant.now().getEpochSecond());
        if (existing != null) {
            dashboard.setTags(new LinkedHashMap<>(existing.getTags()));
        } else if (tags != null && !tags.isEmpty()) {
            if (tags.size() > MAX_TAGS) {
                throw new AwsException("InvalidParameterInput",
                        "A dashboard can have at most " + MAX_TAGS + " tags.", 400);
            }
            dashboard.setTags(new LinkedHashMap<>(tags));
        }

        // put() overwrites, which is exactly PutDashboard's semantics: an existing name is
        // replaced in full rather than merged.
        dashboardStore.put(dashboardName, dashboard);
        LOG.debugv("PutDashboard: {0} in {1}", dashboardName, region);
        return dashboard;
    }

    /**
     * The body is stored opaquely, but it is not accepted blindly: AWS parses it and answers
     * InvalidParameterInput when it is not a JSON object, so a client that sends a malformed
     * document gets an error rather than a success and a dashboard that renders as nothing.
     */
    private static void validateDashboardBody(String dashboardBody) {
        JsonNode parsed;
        try {
            parsed = JSON.readValue(dashboardBody);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidParameterInput",
                    "The dashboard body is invalid: " + e.getOriginalMessage(), 400);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new AwsException("InvalidParameterInput",
                    "The dashboard body is invalid: it must be a JSON object.", 400);
        }
    }

    public Dashboard getDashboard(String dashboardName, String region) {
        return dashboardStore.get(dashboardName)
                .map(CloudWatchDashboardsService::withRegionlessArn)
                .orElseThrow(() -> notFound(dashboardName));
    }

    public List<Dashboard> listDashboards(String dashboardNamePrefix, String region) {
        List<Dashboard> all = new ArrayList<>(dashboardStore.scan(k -> true).stream()
                .map(CloudWatchDashboardsService::withRegionlessArn)
                .toList());
        if (dashboardNamePrefix != null && !dashboardNamePrefix.isBlank()) {
            all = new ArrayList<>(all.stream()
                    .filter(d -> d.getDashboardName().startsWith(dashboardNamePrefix))
                    .toList());
        }
        all.sort(Comparator.comparing(Dashboard::getDashboardName));
        return all;
    }

    public PaginatedResult<Dashboard> listDashboardsPage(String dashboardNamePrefix,
                                                         String nextToken, String region) {
        if (nextToken != null && nextToken.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "Invalid NextToken.", 400);
        }
        String prefix = dashboardNamePrefix == null || dashboardNamePrefix.isBlank()
                ? "" : dashboardNamePrefix;
        String scope = "cloudwatch-dashboards:" + regionResolver.getAccountId() + ":" + prefix;
        return Pagination.paginate(listDashboards(prefix, region), Dashboard::getDashboardName,
                1000, nextToken, scope,
                token -> new AwsException("InvalidParameterValue", "Invalid NextToken.", 400));
    }

    /**
     * Deletes as many of the named dashboards as it can, then reports the first name that was not
     * there. AWS documents this as best effort, verbatim: "If there is an error during this call,
     * the operation attempts to delete as many dashboards as possible." So a batch naming one
     * missing dashboard still deletes the others, rather than leaving every one of them in place.
     *
     * <p>Whether a missing name errors at all is the part that is not settled: DeleteDashboards'
     * own Errors list does not name ResourceNotFound the way GetDashboard's does, which hints at a
     * silent skip but does not establish one. Reporting it is the conservative reading, since a
     * caller that names a dashboard which is not there has a problem worth hearing about, and it
     * is the smaller departure from what this already did.
     */
    public void deleteDashboards(List<String> dashboardNames, String region) {
        if (dashboardNames == null || dashboardNames.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "DashboardNames is required.", 400);
        }
        String firstMissing = null;
        for (String name : dashboardNames) {
            if (dashboardStore.get(name).isEmpty()) {
                if (firstMissing == null) {
                    firstMissing = name;
                }
                continue;
            }
            dashboardStore.delete(name);
        }
        LOG.debugv("DeleteDashboards: {0} in {1}", dashboardNames, region);
        if (firstMissing != null) {
            throw notFound(firstMissing);
        }
    }

    /**
     * Whether an ARN names a dashboard. CloudWatch's tag operations take one ARN for every
     * taggable resource it owns, so the handler needs to know which service should answer.
     */
    public static boolean isDashboardArn(String resourceArn) {
        return resourceArn != null && resourceArn.contains(":dashboard/");
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return requireDashboard(resourceArn).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        Dashboard dashboard = requireDashboard(resourceArn);
        dashboard.getTags().putAll(tags);
        dashboardStore.put(dashboard.getDashboardName(), dashboard);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        Dashboard dashboard = requireDashboard(resourceArn);
        tagKeys.forEach(dashboard.getTags()::remove);
        dashboardStore.put(dashboard.getDashboardName(), dashboard);
    }

    /**
     * Resolves the dashboard an ARN names, or reports that nothing does. The tag operations
     * declare {@code ResourceNotFoundException}, which is a different shape from the
     * {@code ResourceNotFound} that {@code GetDashboard} declares,
     * so this is not the {@link #notFound(String)} used by the rest of this service.
     */
    private Dashboard requireDashboard(String resourceArn) {
        return findByArn(resourceArn).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "Dashboard does not exist: " + resourceArn, 404));
    }

    private Optional<Dashboard> findByArn(String resourceArn) {
        return dashboardStore.scan(k -> true).stream()
                .map(CloudWatchDashboardsService::withRegionlessArn)
                .filter(d -> namesDashboard(resourceArn, d.getDashboardArn()))
                .findFirst();
    }

    /**
     * Whether {@code candidate} is an ARN for the dashboard whose regionless ARN is {@code arn}.
     *
     * <p>AWS's dashboard ARN has no Region ({@code arn:<partition>:cloudwatch::<account>:dashboard/<name>}),
     * which is what Floci mints. Floci used to mint one with the Region of the request, and callers
     * may still hold those, so that form resolves too. A dashboard is global per account, so the
     * Region such an ARN names does not matter.
     */
    private static boolean namesDashboard(String candidate, String arn) {
        if (candidate == null || arn == null) {
            return false;
        }
        return candidate.equals(arn) || withoutRegion(candidate).equals(arn);
    }

    /** The dashboard's ARN: regionless, in the partition of the Region it was put through. */
    private String dashboardArn(String region, String dashboardName) {
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "cloudwatch",
                regionResolver.getAccountId(), "dashboard/" + dashboardName).toString();
    }

    /** A dashboard stored before the ARN lost its Region reads back with the regionless one. */
    private static Dashboard withRegionlessArn(Dashboard dashboard) {
        if (dashboard.getDashboardArn() != null) {
            dashboard.setDashboardArn(withoutRegion(dashboard.getDashboardArn()));
        }
        return dashboard;
    }

    /**
     * Blanks the region field of an ARN, turning
     * {@code arn:aws:cloudwatch:us-east-1:123:dashboard/x} into
     * {@code arn:aws:cloudwatch::123:dashboard/x}. An ARN with fewer than six fields is
     * returned unchanged, since there is no region field to blank.
     */
    private static String withoutRegion(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return arn;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        return new AwsArnUtils.Arn(parsed.partition(), parsed.service(), "", parsed.accountId(), parsed.resource())
                .toString();
    }

    private static AwsException notFound(String dashboardName) {
        return new AwsException("ResourceNotFound",
                "Dashboard does not exist: " + dashboardName, 404);
    }
}
