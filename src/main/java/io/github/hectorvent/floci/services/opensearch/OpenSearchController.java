package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.opensearch.model.ClusterConfig;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.EbsOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/2021-01-01")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenSearchController {

    private static final Logger LOG = Logger.getLogger(OpenSearchController.class);

    private static final List<String> SUPPORTED_VERSIONS = List.of(
            "OpenSearch_2.13", "OpenSearch_2.11", "OpenSearch_2.9", "OpenSearch_2.7",
            "OpenSearch_2.5", "OpenSearch_2.3", "OpenSearch_1.3", "OpenSearch_1.2",
            "Elasticsearch_7.10", "Elasticsearch_7.9", "Elasticsearch_7.8"
    );

    private static final List<String> INSTANCE_TYPES = List.of(
            "t3.small.search", "t3.medium.search",
            "m5.large.search", "m5.xlarge.search", "m5.2xlarge.search",
            "r5.large.search", "r5.xlarge.search", "r5.2xlarge.search",
            "c5.large.search", "c5.xlarge.search", "c5.2xlarge.search"
    );

    private final OpenSearchService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenSearchController(OpenSearchService service, RegionResolver regionResolver,
                                 ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/opensearch/domain")
    public Response createDomain(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body);
            String domainName = req.path("DomainName").asText(null);
            String engineVersion = req.path("EngineVersion").asText(null);
            ClusterConfig clusterConfig = parseClusterConfig(req.path("ClusterConfig"));
            EbsOptions ebsOptions = parseEbsOptions(req.path("EBSOptions"));
            Map<String, String> tags = parseTags(req.path("TagList"));

            Domain domain = service.createDomain(domainName, engineVersion, clusterConfig,
                    ebsOptions, tags, region);

            ObjectNode response = objectMapper.createObjectNode();
            response.set("DomainStatus", toDomainStatusNode(domain));
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/opensearch/domain/{domainName}")
    public Response describeDomain(@Context HttpHeaders headers,
                                    @PathParam("domainName") String domainName) {
        Domain domain = service.describeDomain(domainName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("DomainStatus", toDomainStatusNode(domain));
        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/domain-info")
    public Response describeDomains(@Context HttpHeaders headers, String body) {
        try {
            JsonNode req = objectMapper.readTree(body);
            List<String> names = new ArrayList<>();
            req.path("DomainNames").forEach(n -> names.add(n.asText()));
            List<Domain> domains = service.describeDomains(names);

            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("DomainStatusList");
            domains.forEach(d -> list.add(toDomainStatusNode(d)));
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/domain")
    public Response listDomainNames(@Context HttpHeaders headers,
                                     @QueryParam("engineType") String engineType) {
        List<Domain> domains = service.listDomainNames(engineType);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DomainNames");
        for (Domain d : domains) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("DomainName", d.getDomainName());
            String ev = d.getEngineVersion();
            entry.put("EngineType", (ev != null && ev.startsWith("Elasticsearch")) ? "Elasticsearch" : "OpenSearch");
            list.add(entry);
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/domain/{domainName}/config")
    public Response describeDomainConfig(@Context HttpHeaders headers,
                                          @PathParam("domainName") String domainName) {
        Domain domain = service.describeDomain(domainName);
        long epochSeconds = domain.getCreatedAt() != null ? domain.getCreatedAt().getEpochSecond() : 0;

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode domainConfig = response.putObject("DomainConfig");

        ObjectNode clusterSection = domainConfig.putObject("ClusterConfig");
        clusterSection.set("Options", toClusterConfigNode(domain.getClusterConfig()));
        clusterSection.set("Status", configStatusNode(epochSeconds));

        ObjectNode ebsSection = domainConfig.putObject("EBSOptions");
        ebsSection.set("Options", toEbsOptionsNode(domain.getEbsOptions()));
        ebsSection.set("Status", configStatusNode(epochSeconds));

        ObjectNode versionSection = domainConfig.putObject("EngineVersion");
        versionSection.put("Options", domain.getEngineVersion());
        versionSection.set("Status", configStatusNode(epochSeconds));

        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/domain/{domainName}/config")
    public Response updateDomainConfig(@Context HttpHeaders headers,
                                        @PathParam("domainName") String domainName,
                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body);
            String engineVersion = req.path("EngineVersion").asText(null);
            ClusterConfig clusterConfig = parseClusterConfig(req.path("ClusterConfig"));
            EbsOptions ebsOptions = parseEbsOptions(req.path("EBSOptions"));

            Domain domain = service.updateDomainConfig(domainName, engineVersion, clusterConfig,
                    ebsOptions, region);

            long epochSeconds = domain.getCreatedAt() != null ? domain.getCreatedAt().getEpochSecond() : 0;
            ObjectNode response = objectMapper.createObjectNode();
            ObjectNode domainConfig = response.putObject("DomainConfig");

            ObjectNode clusterSection = domainConfig.putObject("ClusterConfig");
            clusterSection.set("Options", toClusterConfigNode(domain.getClusterConfig()));
            clusterSection.set("Status", configStatusNode(epochSeconds));

            ObjectNode ebsSection = domainConfig.putObject("EBSOptions");
            ebsSection.set("Options", toEbsOptionsNode(domain.getEbsOptions()));
            ebsSection.set("Status", configStatusNode(epochSeconds));

            ObjectNode versionSection = domainConfig.putObject("EngineVersion");
            versionSection.put("Options", domain.getEngineVersion());
            versionSection.set("Status", configStatusNode(epochSeconds));

            return Response.ok(response).build();
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/opensearch/domain/{domainName}")
    public Response deleteDomain(@Context HttpHeaders headers,
                                  @PathParam("domainName") String domainName) {
        Domain domain = service.deleteDomain(domainName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("DomainStatus", toDomainStatusNode(domain));
        return Response.ok(response).build();
    }

    // ── Tags ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/tags")
    public Response addTags(@Context HttpHeaders headers, String body) {
        try {
            JsonNode req = objectMapper.readTree(body);
            String arn = req.path("ARN").asText(null);
            if (arn == null || arn.isBlank()) {
                throw new AwsException("ValidationException", "ARN is required.", 400);
            }
            Map<String, String> tags = parseTags(req.path("TagList"));
            service.addTags(arn, tags);
            return Response.ok("{}").build();
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/tags/")
    public Response listTags(@Context HttpHeaders headers, @QueryParam("arn") String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationException", "ARN query parameter is required.", 400);
        }
        Map<String, String> tags = service.listTags(arn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagList = response.putArray("TagList");
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            tagList.add(tag);
        });
        return Response.ok(response).build();
    }

    @POST
    @Path("/tags-removal")
    public Response removeTags(@Context HttpHeaders headers, String body) {
        try {
            JsonNode req = objectMapper.readTree(body);
            String arn = req.path("ARN").asText(null);
            if (arn == null || arn.isBlank()) {
                throw new AwsException("ValidationException", "ARN is required.", 400);
            }
            List<String> tagKeys = new ArrayList<>();
            req.path("TagKeys").forEach(n -> tagKeys.add(n.asText()));
            service.removeTags(arn, tagKeys);
            return Response.ok("{}").build();
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/opensearch/versions")
    public Response listVersions(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode versions = response.putArray("Versions");
        SUPPORTED_VERSIONS.forEach(versions::add);
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/compatibleVersions")
    public Response getCompatibleVersions(@Context HttpHeaders headers,
                                           @QueryParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode compatibleVersions = response.putArray("CompatibleVersions");

        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("SourceVersion", "OpenSearch_2.9");
        ArrayNode targets = entry.putArray("TargetVersions");
        targets.add("OpenSearch_2.11");
        targets.add("OpenSearch_2.13");
        compatibleVersions.add(entry);

        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/instanceTypeDetails/{engineVersion}")
    public Response listInstanceTypeDetails(@Context HttpHeaders headers,
                                             @PathParam("engineVersion") String engineVersion) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("InstanceTypeDetails");
        for (String instanceType : INSTANCE_TYPES) {
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("InstanceType", instanceType);
            detail.put("EncryptionEnabled", true);
            detail.put("CognitoEnabled", false);
            detail.put("AppLogsEnabled", true);
            detail.put("AdvancedSecurityEnabled", false);
            ArrayNode roles = detail.putArray("InstanceRole");
            roles.add("Data");
            details.add(detail);
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/instanceTypeLimits/{engineVersion}/{instanceType}")
    public Response describeInstanceTypeLimits(@Context HttpHeaders headers,
                                                @PathParam("engineVersion") String engineVersion,
                                                @PathParam("instanceType") String instanceType) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode limitsByRole = response.putObject("LimitsByRole");
        ObjectNode dataRole = limitsByRole.putObject("data");

        ArrayNode storageTypes = dataRole.putArray("StorageTypes");
        ObjectNode storageType = objectMapper.createObjectNode();
        storageType.put("StorageTypeName", "ebs");
        storageType.put("StorageSubTypeName", "standard");
        ArrayNode storageTypeLimits = storageType.putArray("StorageTypeLimits");
        ObjectNode minLimit = objectMapper.createObjectNode();
        minLimit.put("LimitName", "MinimumVolumeSize");
        minLimit.putArray("LimitValues").add("10");
        storageTypeLimits.add(minLimit);
        ObjectNode maxLimit = objectMapper.createObjectNode();
        maxLimit.put("LimitName", "MaximumVolumeSize");
        maxLimit.putArray("LimitValues").add("3584");
        storageTypeLimits.add(maxLimit);
        storageTypes.add(storageType);

        ObjectNode instanceLimits = dataRole.putObject("InstanceLimits");
        ObjectNode instanceCountLimits = instanceLimits.putObject("InstanceCountLimits");
        instanceCountLimits.put("MinimumInstanceCount", 1);
        instanceCountLimits.put("MaximumInstanceCount", 20);

        dataRole.putArray("AdditionalLimits");

        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/domain/{domainName}/progress")
    public Response describeDomainChangeProgress(@PathParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("ChangeProgressStatus");
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/domain/{domainName}/autoTunes")
    public Response describeDomainAutoTunes(@PathParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("AutoTunes");
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/domain/{domainName}/dryRun")
    public Response describeDryRunProgress(@PathParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("DryRunProgressStatus");
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/domain/{domainName}/health")
    public Response describeDomainHealth(@PathParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterHealth", "Green");
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/upgradeDomain/{domainName}/history")
    public Response getUpgradeHistory(@PathParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("UpgradeHistories");
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/upgradeDomain/{domainName}/status")
    public Response getUpgradeStatus(@PathParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("UpgradeStep", "UPGRADE");
        response.put("StepStatus", "SUCCEEDED");
        response.put("UpgradeName", "");
        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/upgradeDomain")
    public Response upgradeDomain(String body) {
        try {
            JsonNode req = objectMapper.readTree(body);
            String domainName = req.path("DomainName").asText(null);
            String targetVersion = req.path("TargetVersion").asText(null);
            Domain domain = service.upgradeDomain(domainName, targetVersion);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("UpgradeId", UUID.randomUUID().toString());
            response.put("DomainName", domain.getDomainName());
            response.put("TargetVersion", domain.getEngineVersion());
            response.put("PerformCheckOnly", false);
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/opensearch/domain/{domainName}/config/cancel")
    public Response cancelDomainConfigChange(@PathParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DryRun", false);
        response.putArray("CancelledChangeIds");
        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/serviceSoftwareUpdate/start")
    public Response startServiceSoftwareUpdate(String body) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode options = response.putObject("ServiceSoftwareOptions");
        options.put("UpdateAvailable", false);
        options.put("Cancellable", false);
        options.put("UpdateStatus", "COMPLETED");
        options.put("Description", "There is no software update available for this domain.");
        options.put("AutomatedUpdateDate", 0);
        options.put("OptionalDeployment", false);
        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/serviceSoftwareUpdate/cancel")
    public Response cancelServiceSoftwareUpdate(String body) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode options = response.putObject("ServiceSoftwareOptions");
        options.put("UpdateAvailable", false);
        options.put("Cancellable", false);
        options.put("UpdateStatus", "COMPLETED");
        options.put("Description", "There is no software update available for this domain.");
        options.put("AutomatedUpdateDate", 0);
        options.put("OptionalDeployment", false);
        return Response.ok(response).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode toDomainStatusNode(Domain domain) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ARN", domain.getArn());
        node.put("DomainId", domain.getDomainId());
        node.put("DomainName", domain.getDomainName());
        node.put("EngineVersion", domain.getEngineVersion());
        node.put("Created", !domain.isProcessing());
        node.put("Processing", domain.isProcessing());
        node.put("Deleted", domain.isDeleted());
        node.put("Endpoint", domain.getEndpoint() != null ? domain.getEndpoint() : "");
        node.set("ClusterConfig", toClusterConfigNode(domain.getClusterConfig()));
        node.set("EBSOptions", toEbsOptionsNode(domain.getEbsOptions()));
        return node;
    }

    private ObjectNode toClusterConfigNode(ClusterConfig cc) {
        ObjectNode node = objectMapper.createObjectNode();
        if (cc == null) {
            node.put("InstanceType", "m5.large.search");
            node.put("InstanceCount", 1);
            node.put("DedicatedMasterEnabled", false);
            node.put("ZoneAwarenessEnabled", false);
        } else {
            node.put("InstanceType", cc.getInstanceType());
            node.put("InstanceCount", cc.getInstanceCount());
            node.put("DedicatedMasterEnabled", cc.isDedicatedMasterEnabled());
            node.put("ZoneAwarenessEnabled", cc.isZoneAwarenessEnabled());
        }
        return node;
    }

    private ObjectNode toEbsOptionsNode(EbsOptions ebs) {
        ObjectNode node = objectMapper.createObjectNode();
        if (ebs == null) {
            node.put("EBSEnabled", true);
            node.put("VolumeType", "gp2");
            node.put("VolumeSize", 10);
        } else {
            node.put("EBSEnabled", ebs.isEbsEnabled());
            node.put("VolumeType", ebs.getVolumeType());
            node.put("VolumeSize", ebs.getVolumeSize());
        }
        return node;
    }

    private ObjectNode configStatusNode(long epochSeconds) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("CreationDate", epochSeconds);
        status.put("UpdateDate", epochSeconds);
        status.put("State", "Active");
        return status;
    }

    private ClusterConfig parseClusterConfig(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        ClusterConfig cc = new ClusterConfig();
        if (node.has("InstanceType")) {
            cc.setInstanceType(node.get("InstanceType").asText());
        }
        if (node.has("InstanceCount")) {
            cc.setInstanceCount(node.get("InstanceCount").asInt());
        }
        if (node.has("DedicatedMasterEnabled")) {
            cc.setDedicatedMasterEnabled(node.get("DedicatedMasterEnabled").asBoolean());
        }
        if (node.has("ZoneAwarenessEnabled")) {
            cc.setZoneAwarenessEnabled(node.get("ZoneAwarenessEnabled").asBoolean());
        }
        return cc;
    }

    private EbsOptions parseEbsOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        EbsOptions ebs = new EbsOptions();
        if (node.has("EBSEnabled")) {
            ebs.setEbsEnabled(node.get("EBSEnabled").asBoolean());
        }
        if (node.has("VolumeType")) {
            ebs.setVolumeType(node.get("VolumeType").asText());
        }
        if (node.has("VolumeSize")) {
            ebs.setVolumeSize(node.get("VolumeSize").asInt());
        }
        return ebs;
    }

    private Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new HashMap<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return tags;
        }
        node.forEach(tag -> {
            String key = tag.path("Key").asText(null);
            String value = tag.path("Value").asText(null);
            if (key != null && value != null) {
                tags.put(key, value);
            }
        });
        return tags;
    }
}
