package io.github.hectorvent.floci.services.cloudwatch.dashboards;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

/**
 * CloudWatch dashboards. Dashboards are pure metadata: the DashboardBody is an opaque JSON
 * document that AWS stores and hands back verbatim, so there is no backing behaviour here.
 */
@ApplicationScoped
public class CloudWatchDashboardsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchDashboardsService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

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
        if (dashboardName == null || dashboardName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "DashboardName is required.", 400);
        }
        if (dashboardBody == null) {
            throw new AwsException("InvalidParameterValue", "DashboardBody is required.", 400);
        }
        validateDashboardBody(dashboardBody);

        Dashboard dashboard = new Dashboard(dashboardName,
                regionResolver.buildArn("cloudwatch", region, "dashboard/" + dashboardName),
                dashboardBody);
        dashboard.setLastModified(Instant.now().getEpochSecond());

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
            parsed = JSON.readTree(dashboardBody);
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
     * Deletes every named dashboard, or none of them: AWS rejects the whole call when any
     * name is missing, so the existence check runs to completion before the first delete.
     */
    public void deleteDashboards(List<String> dashboardNames, String region) {
        if (dashboardNames == null || dashboardNames.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "DashboardNames is required.", 400);
        }
        for (String name : dashboardNames) {
            if (dashboardStore.get(key(region, name)).isEmpty()) {
                throw notFound(name);
            }
        }
        for (String name : dashboardNames) {
            dashboardStore.delete(key(region, name));
        }
        LOG.debugv("DeleteDashboards: {0} in {1}", dashboardNames, region);
    }

    private static String key(String region, String dashboardName) {
        return region + "::" + dashboardName;
    }

    private static AwsException notFound(String dashboardName) {
        return new AwsException("ResourceNotFound",
                "Dashboard does not exist: " + dashboardName, 404);
    }
}
