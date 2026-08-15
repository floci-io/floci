package io.github.hectorvent.floci.services.amp;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amp.model.PrometheusScraper;
import io.github.hectorvent.floci.services.amp.model.PrometheusWorkspace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Amazon Managed Service for Prometheus (service id {@code amp}, signing scope
 * {@code aps}). Resources are provisioned instantly: statuses are ACTIVE as soon
 * as a create returns, so provider waiters complete on their first poll.
 */
@ApplicationScoped
public class AmpService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(AmpService.class);

    private final StorageBackend<String, PrometheusWorkspace> workspaces;
    private final StorageBackend<String, PrometheusScraper> scrapers;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;

    @Inject
    public AmpService(StorageFactory storageFactory, RegionResolver regionResolver, EmulatorConfig config) {
        this.workspaces = storageFactory.create("amp", "amp-workspaces.json",
                new TypeReference<Map<String, PrometheusWorkspace>>() {});
        this.scrapers = storageFactory.create("amp", "amp-scrapers.json",
                new TypeReference<Map<String, PrometheusScraper>>() {});
        this.regionResolver = regionResolver;
        this.config = config;
    }

    // ──────────────────────────── Workspaces ────────────────────────────

    public PrometheusWorkspace createWorkspace(String alias, String kmsKeyArn,
                                               Map<String, String> tags, String region) {
        String workspaceId = "ws-" + UUID.randomUUID();
        PrometheusWorkspace workspace = new PrometheusWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setAlias(alias);
        workspace.setArn(regionResolver.buildArn("aps", region, "workspace/" + workspaceId));
        workspace.setPrometheusEndpoint(config.effectiveBaseUrl() + "/workspaces/" + workspaceId + "/");
        workspace.setKmsKeyArn(kmsKeyArn);
        workspace.setCreatedAt(Instant.now());
        workspace.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        workspace.setAccountId(regionResolver.getAccountId());

        workspaces.put(region + "::" + workspaceId, workspace);
        LOG.infov("Created Prometheus workspace: {0}", workspaceId);
        return workspace;
    }

    public PrometheusWorkspace describeWorkspace(String workspaceId, String region) {
        return workspaces.get(region + "::" + workspaceId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Workspace " + workspaceId + " does not exist.", 404));
    }

    public void deleteWorkspace(String workspaceId, String region) {
        describeWorkspace(workspaceId, region);
        workspaces.delete(region + "::" + workspaceId);
        LOG.infov("Deleted Prometheus workspace: {0}", workspaceId);
    }

    public List<PrometheusWorkspace> listWorkspaces(String alias, String region) {
        String regionPrefix = region + "::";
        return workspaces.scan(key -> key.startsWith(regionPrefix)).stream()
                .filter(workspace -> alias == null || alias.equals(workspace.getAlias()))
                .toList();
    }

    // ─────────────────────── Alert manager definition ───────────────────────

    public PrometheusWorkspace createAlertManagerDefinition(String workspaceId, String data, String region) {
        if (data == null || data.isBlank()) {
            throw new AwsException("ValidationException", "data is required", 400);
        }
        PrometheusWorkspace workspace = describeWorkspace(workspaceId, region);
        Instant now = Instant.now();
        if (workspace.getAlertManagerCreatedAt() == null) {
            workspace.setAlertManagerCreatedAt(now);
        }
        workspace.setAlertManagerData(data);
        workspace.setAlertManagerModifiedAt(now);
        workspaces.put(region + "::" + workspaceId, workspace);
        return workspace;
    }

    public PrometheusWorkspace describeAlertManagerDefinition(String workspaceId, String region) {
        PrometheusWorkspace workspace = describeWorkspace(workspaceId, region);
        if (workspace.getAlertManagerData() == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Alert manager definition for workspace " + workspaceId + " does not exist.", 404);
        }
        return workspace;
    }

    public void deleteAlertManagerDefinition(String workspaceId, String region) {
        PrometheusWorkspace workspace = describeAlertManagerDefinition(workspaceId, region);
        workspace.setAlertManagerData(null);
        workspace.setAlertManagerCreatedAt(null);
        workspace.setAlertManagerModifiedAt(null);
        workspaces.put(region + "::" + workspaceId, workspace);
    }

    // ─────────────────────── Query logging configuration ───────────────────────

    public PrometheusWorkspace createQueryLoggingConfiguration(String workspaceId, JsonNode destinations,
                                                               String region) {
        if (destinations == null || !destinations.isArray() || destinations.isEmpty()) {
            throw new AwsException("ValidationException", "destinations is required", 400);
        }
        PrometheusWorkspace workspace = describeWorkspace(workspaceId, region);
        Instant now = Instant.now();
        if (workspace.getQueryLoggingCreatedAt() == null) {
            workspace.setQueryLoggingCreatedAt(now);
        }
        workspace.setQueryLoggingDestinations(destinations);
        workspace.setQueryLoggingModifiedAt(now);
        workspaces.put(region + "::" + workspaceId, workspace);
        return workspace;
    }

    public PrometheusWorkspace describeQueryLoggingConfiguration(String workspaceId, String region) {
        PrometheusWorkspace workspace = describeWorkspace(workspaceId, region);
        if (workspace.getQueryLoggingDestinations() == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Query logging configuration for workspace " + workspaceId + " does not exist.", 404);
        }
        return workspace;
    }

    public void deleteQueryLoggingConfiguration(String workspaceId, String region) {
        PrometheusWorkspace workspace = describeQueryLoggingConfiguration(workspaceId, region);
        workspace.setQueryLoggingDestinations(null);
        workspace.setQueryLoggingCreatedAt(null);
        workspace.setQueryLoggingModifiedAt(null);
        workspaces.put(region + "::" + workspaceId, workspace);
    }

    // ─────────────── Workspace logging configurations ───────────────
    //
    // The workspace-level logging configuration (CloudWatch log group for
    // rule evaluation logs) is distinct from the query logging above. The
    // Terraform provider's workspace read probes DescribeLoggingConfiguration
    // unconditionally, so the not-found case must be a proper
    // ResourceNotFoundException - the fallback UnknownOperationException
    // failed every aws_prometheus_workspace apply (choudoufu#124's aps
    // cohort).

    public PrometheusWorkspace createLoggingConfiguration(String workspaceId, String logGroupArn,
                                                          String region) {
        if (logGroupArn == null || logGroupArn.isBlank()) {
            throw new AwsException("ValidationException", "logGroupArn is required", 400);
        }
        PrometheusWorkspace workspace = describeWorkspace(workspaceId, region);
        Instant now = Instant.now();
        if (workspace.getLoggingCreatedAt() == null) {
            workspace.setLoggingCreatedAt(now);
        }
        workspace.setLoggingLogGroupArn(logGroupArn);
        workspace.setLoggingModifiedAt(now);
        workspaces.put(region + "::" + workspaceId, workspace);
        return workspace;
    }

    public PrometheusWorkspace describeLoggingConfiguration(String workspaceId, String region) {
        PrometheusWorkspace workspace = describeWorkspace(workspaceId, region);
        if (workspace.getLoggingLogGroupArn() == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Logging configuration for workspace " + workspaceId + " does not exist.", 404);
        }
        return workspace;
    }

    public void deleteLoggingConfiguration(String workspaceId, String region) {
        PrometheusWorkspace workspace = describeLoggingConfiguration(workspaceId, region);
        workspace.setLoggingLogGroupArn(null);
        workspace.setLoggingCreatedAt(null);
        workspace.setLoggingModifiedAt(null);
        workspaces.put(region + "::" + workspaceId, workspace);
    }

    // ──────────────────────────── Scrapers ────────────────────────────

    public PrometheusScraper createScraper(String alias, JsonNode scrapeConfiguration, JsonNode source,
                                           JsonNode destination, JsonNode roleConfiguration,
                                           Map<String, String> tags, String region) {
        if (scrapeConfiguration == null || scrapeConfiguration.isNull()) {
            throw new AwsException("ValidationException", "scrapeConfiguration is required", 400);
        }
        if (source == null || source.isNull()) {
            throw new AwsException("ValidationException", "source is required", 400);
        }
        if (destination == null || destination.isNull()) {
            throw new AwsException("ValidationException", "destination is required", 400);
        }
        String scraperId = "s-" + UUID.randomUUID();
        PrometheusScraper scraper = new PrometheusScraper();
        scraper.setScraperId(scraperId);
        scraper.setAlias(alias);
        scraper.setArn(regionResolver.buildArn("aps", region, "scraper/" + scraperId));
        scraper.setRoleArn("arn:aws:iam::" + regionResolver.getAccountId()
                + ":role/aws-service-role/scraper.aps.amazonaws.com/AWSServiceRoleForAmazonPrometheusScraper_" + scraperId);
        Instant now = Instant.now();
        scraper.setCreatedAt(now);
        scraper.setLastModifiedAt(now);
        scraper.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        scraper.setAccountId(regionResolver.getAccountId());
        scraper.setScrapeConfiguration(scrapeConfiguration);
        scraper.setSource(source);
        scraper.setDestination(destination);
        scraper.setRoleConfiguration(roleConfiguration);

        scrapers.put(region + "::" + scraperId, scraper);
        LOG.infov("Created Prometheus scraper: {0}", scraperId);
        return scraper;
    }

    public PrometheusScraper describeScraper(String scraperId, String region) {
        return scrapers.get(region + "::" + scraperId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Scraper " + scraperId + " does not exist.", 404));
    }

    public void deleteScraper(String scraperId, String region) {
        describeScraper(scraperId, region);
        scrapers.delete(region + "::" + scraperId);
        LOG.infov("Deleted Prometheus scraper: {0}", scraperId);
    }

    public List<PrometheusScraper> listScrapers(String region) {
        String regionPrefix = region + "::";
        return scrapers.scan(key -> key.startsWith(regionPrefix));
    }

    // ─────────────────── Scraper logging configuration ───────────────────

    public PrometheusScraper updateScraperLoggingConfiguration(String scraperId, JsonNode loggingDestination,
                                                               JsonNode scraperComponents, String region) {
        if (loggingDestination == null || loggingDestination.isNull()) {
            throw new AwsException("ValidationException", "loggingDestination is required", 400);
        }
        PrometheusScraper scraper = describeScraper(scraperId, region);
        scraper.setLoggingDestination(loggingDestination);
        scraper.setLoggingScraperComponents(scraperComponents);
        scraper.setLoggingModifiedAt(Instant.now());
        scrapers.put(region + "::" + scraperId, scraper);
        return scraper;
    }

    public PrometheusScraper describeScraperLoggingConfiguration(String scraperId, String region) {
        PrometheusScraper scraper = describeScraper(scraperId, region);
        if (scraper.getLoggingDestination() == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Logging configuration for scraper " + scraperId + " does not exist.", 404);
        }
        return scraper;
    }

    public void deleteScraperLoggingConfiguration(String scraperId, String region) {
        PrometheusScraper scraper = describeScraperLoggingConfiguration(scraperId, region);
        scraper.setLoggingDestination(null);
        scraper.setLoggingScraperComponents(null);
        scraper.setLoggingModifiedAt(null);
        scrapers.put(region + "::" + scraperId, scraper);
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "aps";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Object resource = findByArn(arn, region);
        Map<String, String> tags = resource instanceof PrometheusWorkspace workspace
                ? workspace.getTags()
                : ((PrometheusScraper) resource).getTags();
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        Object resource = findByArn(arn, region);
        if (resource instanceof PrometheusWorkspace workspace) {
            if (workspace.getTags() == null) {
                workspace.setTags(new HashMap<>());
            }
            workspace.getTags().putAll(tags);
            workspaces.put(region + "::" + workspace.getWorkspaceId(), workspace);
        } else {
            PrometheusScraper scraper = (PrometheusScraper) resource;
            if (scraper.getTags() == null) {
                scraper.setTags(new HashMap<>());
            }
            scraper.getTags().putAll(tags);
            scrapers.put(region + "::" + scraper.getScraperId(), scraper);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Object resource = findByArn(arn, region);
        if (resource instanceof PrometheusWorkspace workspace) {
            if (workspace.getTags() != null && tagKeys != null) {
                tagKeys.forEach(workspace.getTags()::remove);
            }
            workspaces.put(region + "::" + workspace.getWorkspaceId(), workspace);
        } else {
            PrometheusScraper scraper = (PrometheusScraper) resource;
            if (scraper.getTags() != null && tagKeys != null) {
                tagKeys.forEach(scraper.getTags()::remove);
            }
            scrapers.put(region + "::" + scraper.getScraperId(), scraper);
        }
    }

    private Object findByArn(String arn, String region) {
        String regionPrefix = region + "::";
        for (PrometheusWorkspace workspace : workspaces.scan(key -> key.startsWith(regionPrefix))) {
            if (arn.equals(workspace.getArn())) {
                return workspace;
            }
        }
        for (PrometheusScraper scraper : scrapers.scan(key -> key.startsWith(regionPrefix))) {
            if (arn.equals(scraper.getArn())) {
                return scraper;
            }
        }
        throw new AwsException("ResourceNotFoundException",
                "Resource " + arn + " does not exist.", 404);
    }
}
