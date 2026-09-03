package io.github.hectorvent.floci.services.cloudwatch.dashboards;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.model.Dashboard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CloudWatch dashboards. Dashboards are pure metadata: the DashboardBody is an opaque JSON
 * document that AWS stores and hands back verbatim, so there is no backing behaviour here.
 */
@ApplicationScoped
public class CloudWatchDashboardsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchDashboardsService.class);

    // FAIL_ON_TRAILING_TOKENS matters here: without it a body of "{} garbage" parses as the
    // leading object and the rest is silently dropped, so a document AWS rejects would be stored.
    /** AWS's documented ceiling for PutDashboard's Tags. */
    private static final int MAX_TAGS = 50;

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
    }

    // Public rather than package-private so handler tests in the metrics package can build
    // the service over an InMemoryStorage without standing up Quarkus.
    public CloudWatchDashboardsService(StorageBackend<String, Dashboard> dashboardStore,
                                       RegionResolver regionResolver) {
        this.dashboardStore = dashboardStore;
        this.regionResolver = regionResolver;
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

        Dashboard existing = dashboardStore.get(key(region, dashboardName)).orElse(null);
        Dashboard dashboard = new Dashboard(dashboardName,
                regionResolver.buildArn("cloudwatch", region, "dashboard/" + dashboardName),
                dashboardBody);
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
        dashboardStore.put(key(region, dashboardName), dashboard);
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
        return dashboardStore.get(key(region, dashboardName))
                .orElseThrow(() -> notFound(dashboardName));
    }

    public List<Dashboard> listDashboards(String dashboardNamePrefix, String region) {
        String keyPrefix = region + "::";
        List<Dashboard> all = dashboardStore.scan(k -> k.startsWith(keyPrefix));
        if (dashboardNamePrefix != null && !dashboardNamePrefix.isBlank()) {
            all = new ArrayList<>(all.stream()
                    .filter(d -> d.getDashboardName().startsWith(dashboardNamePrefix))
                    .toList());
        }
        all.sort(Comparator.comparing(Dashboard::getDashboardName));
        return all;
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
            if (dashboardStore.get(key(region, name)).isEmpty()) {
                if (firstMissing == null) {
                    firstMissing = name;
                }
                continue;
            }
            dashboardStore.delete(key(region, name));
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
        return findByArn(resourceArn, region).map(Dashboard::getTags).orElse(Map.of());
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        findByArn(resourceArn, region).ifPresent(dashboard -> {
            dashboard.getTags().putAll(tags);
            dashboardStore.put(key(region, dashboard.getDashboardName()), dashboard);
        });
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        findByArn(resourceArn, region).ifPresent(dashboard -> {
            tagKeys.forEach(dashboard.getTags()::remove);
            dashboardStore.put(key(region, dashboard.getDashboardName()), dashboard);
        });
    }

    private java.util.Optional<Dashboard> findByArn(String resourceArn, String region) {
        return dashboardStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(d -> d.getDashboardArn() != null && d.getDashboardArn().equals(resourceArn))
                .findFirst();
    }

    private static String key(String region, String dashboardName) {
        return region + "::" + dashboardName;
    }

    private static AwsException notFound(String dashboardName) {
        return new AwsException("ResourceNotFound",
                "Dashboard does not exist: " + dashboardName, 404);
    }
}
