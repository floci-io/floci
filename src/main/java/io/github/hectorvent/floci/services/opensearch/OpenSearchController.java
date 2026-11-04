package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.opensearch.model.AdvancedSecurityOptions;
import io.github.hectorvent.floci.services.opensearch.model.ClusterConfig;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.DomainEndpointOptions;
import io.github.hectorvent.floci.services.opensearch.model.EbsOptions;
import io.github.hectorvent.floci.services.opensearch.model.EncryptionAtRestOptions;
import io.github.hectorvent.floci.services.opensearch.model.NodeToNodeEncryptionOptions;
import io.github.hectorvent.floci.services.opensearch.model.OffPeakWindowOptions;
import io.github.hectorvent.floci.services.opensearch.model.OpenSearchVpcEndpoint;
import io.github.hectorvent.floci.services.opensearch.model.SoftwareUpdateOptions;
import io.github.hectorvent.floci.services.opensearch.model.VpcOptions;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Path("/2021-01-01")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OpenSearchController {

    private static final Logger LOG = Logger.getLogger(OpenSearchController.class);

    private final OpenSearchService service;
    private final OpenSearchVpcEndpointService vpcEndpoints;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenSearchController(OpenSearchService service, OpenSearchVpcEndpointService vpcEndpoints,
                                RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.vpcEndpoints = vpcEndpoints;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /** For {@link ElasticsearchController}, which delegates the domain operations only. */
    OpenSearchController(OpenSearchService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(service, null, regionResolver, objectMapper);
    }

    @POST
    @Path("/opensearch/domain")
    public Response createDomain(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body);
            String domainName = req.path("DomainName").asText(null);
            String engineVersion = req.path("EngineVersion").asText(null);
            String accessPolicies = req.path("AccessPolicies").asText(null);
            ClusterConfig clusterConfig = parseClusterConfig(req.path("ClusterConfig"));
            EbsOptions ebsOptions = parseEbsOptions(req.path("EBSOptions"));
            Map<String, String> tags = parseTags(req.path("TagList"));

            OpenSearchService.DomainOptions options = new OpenSearchService.DomainOptions(
                    parseVpcOptions(req.path("VPCOptions")),
                    parseAdvancedSecurityOptions(req.path("AdvancedSecurityOptions")),
                    parseEncryptionAtRestOptions(req.path("EncryptionAtRestOptions")),
                    parseNodeToNodeEncryptionOptions(req.path("NodeToNodeEncryptionOptions")),
                    parseDomainEndpointOptions(req.path("DomainEndpointOptions")),
                    parseAdvancedOptions(req.path("AdvancedOptions")),
                    req.path("IPAddressType").asText(null),
                    parseOffPeakWindowOptions(req.path("OffPeakWindowOptions")),
                    parseSoftwareUpdateOptions(req.path("SoftwareUpdateOptions")));

            Domain domain = service.createDomain(domainName, engineVersion, clusterConfig,
                    ebsOptions, tags, accessPolicies, options, region);

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
        Domain domain = service.describeDomain(domainName, regionResolver.resolveRegion(headers));
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
            List<Domain> domains = service.describeDomains(names, regionResolver.resolveRegion(headers));

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
        List<Domain> domains = service.listDomainNames(engineType, regionResolver.resolveRegion(headers));
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
        Domain domain = service.describeDomain(domainName, regionResolver.resolveRegion(headers));
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

        attachDomainOptionConfigs(domainConfig, domain, epochSeconds);

        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/domain/{domainName}/config")
    public Response updateDomainConfig(@Context HttpHeaders headers,
                                        @PathParam("domainName") String domainName,
                                        String body) {
        try {
            JsonNode req = objectMapper.readTree(body);
            String engineVersion = req.path("EngineVersion").asText(null);
            String accessPolicies = req.path("AccessPolicies").asText(null);
            ClusterConfig clusterConfig = parseClusterConfig(req.path("ClusterConfig"));
            EbsOptions ebsOptions = parseEbsOptions(req.path("EBSOptions"));

            OpenSearchService.DomainOptions options = new OpenSearchService.DomainOptions(
                    parseVpcOptions(req.path("VPCOptions")),
                    parseAdvancedSecurityOptions(req.path("AdvancedSecurityOptions")),
                    parseEncryptionAtRestOptions(req.path("EncryptionAtRestOptions")),
                    parseNodeToNodeEncryptionOptions(req.path("NodeToNodeEncryptionOptions")),
                    parseDomainEndpointOptions(req.path("DomainEndpointOptions")),
                    parseAdvancedOptions(req.path("AdvancedOptions")),
                    req.path("IPAddressType").asText(null),
                    parseOffPeakWindowOptions(req.path("OffPeakWindowOptions")),
                    parseSoftwareUpdateOptions(req.path("SoftwareUpdateOptions")));

            Domain domain = service.updateDomainConfig(domainName, engineVersion, clusterConfig,
                    ebsOptions, accessPolicies, options, regionResolver.resolveRegion(headers));

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

            attachDomainOptionConfigs(domainConfig, domain, epochSeconds);

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
        Domain domain = service.deleteDomain(domainName, regionResolver.resolveRegion(headers));
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
        OpenSearchVersions.supportedVersions().forEach(versions::add);
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/compatibleVersions")
    public Response getCompatibleVersions(@Context HttpHeaders headers,
                                           @QueryParam("domainName") String domainName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode compatibleVersions = response.putArray("CompatibleVersions");

        // When domainName is set, narrow the response to that domain's source
        // version — matches AWS's GetCompatibleVersions behavior. Otherwise
        // return the full matrix so SDK clients can render upgrade pickers.
        if (domainName != null && !domainName.isBlank()) {
            String sourceVersion = service
                    .describeDomain(domainName, regionResolver.resolveRegion(headers))
                    .getEngineVersion();
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("SourceVersion", sourceVersion);
            ArrayNode targets = entry.putArray("TargetVersions");
            OpenSearchVersions.compatibleTargets(sourceVersion).forEach(targets::add);
            compatibleVersions.add(entry);
        } else {
            OpenSearchVersions.compatibilityMatrix().forEach((source, targetList) -> {
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("SourceVersion", source);
                ArrayNode targets = entry.putArray("TargetVersions");
                targetList.forEach(targets::add);
                compatibleVersions.add(entry);
            });
        }

        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/instanceTypeDetails/{engineVersion}")
    public Response listInstanceTypeDetails(@Context HttpHeaders headers,
                                             @PathParam("engineVersion") String engineVersion) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("InstanceTypeDetails");
        for (String instanceType : OpenSearchInstanceTypes.listAll()) {
            OpenSearchInstanceTypes.InstanceTypeSpec spec = OpenSearchInstanceTypes.specOf(instanceType);
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("InstanceType", spec.instanceType());
            detail.put("EncryptionEnabled", spec.encryptionEnabled());
            detail.put("CognitoEnabled", spec.cognitoEnabled());
            detail.put("AppLogsEnabled", spec.appLogsEnabled());
            detail.put("AdvancedSecurityEnabled", spec.advancedSecurityEnabled());
            ArrayNode roles = detail.putArray("InstanceRole");
            OpenSearchInstanceTypes.DATA_ROLE.forEach(roles::add);
            details.add(detail);
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/opensearch/instanceTypeLimits/{engineVersion}/{instanceType}")
    public Response describeInstanceTypeLimits(@Context HttpHeaders headers,
                                                @PathParam("engineVersion") String engineVersion,
                                                @PathParam("instanceType") String instanceType) {
        OpenSearchInstanceTypes.InstanceTypeSpec spec = OpenSearchInstanceTypes.specOf(instanceType);
        if (spec == null) {
            // Generic fallback so freshly announced types don't 404 here.
            spec = OpenSearchInstanceTypes.genericFallback(instanceType);
        }

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode limitsByRole = response.putObject("LimitsByRole");
        ObjectNode dataRole = limitsByRole.putObject("data");

        ArrayNode storageTypes = dataRole.putArray("StorageTypes");
        ObjectNode storageType = objectMapper.createObjectNode();
        storageType.put("StorageTypeName", spec.storageType());
        storageType.put("StorageSubTypeName", spec.ebsAttachable() ? "standard" : "managed");
        ArrayNode storageTypeLimits = storageType.putArray("StorageTypeLimits");
        ObjectNode minLimit = objectMapper.createObjectNode();
        minLimit.put("LimitName", "MinimumVolumeSize");
        minLimit.putArray("LimitValues").add(String.valueOf(spec.minVolumeSizeGib()));
        storageTypeLimits.add(minLimit);
        ObjectNode maxLimit = objectMapper.createObjectNode();
        maxLimit.put("LimitName", "MaximumVolumeSize");
        maxLimit.putArray("LimitValues").add(String.valueOf(spec.maxVolumeSizeGib()));
        storageTypeLimits.add(maxLimit);
        storageTypes.add(storageType);

        ObjectNode instanceLimits = dataRole.putObject("InstanceLimits");
        ObjectNode instanceCountLimits = instanceLimits.putObject("InstanceCountLimits");
        instanceCountLimits.put("MinimumInstanceCount", spec.minInstanceCount());
        instanceCountLimits.put("MaximumInstanceCount", spec.maxInstanceCount());

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
    public Response upgradeDomain(@Context HttpHeaders headers, String body) {
        try {
            JsonNode req = objectMapper.readTree(body);
            String domainName = req.path("DomainName").asText(null);
            String targetVersion = req.path("TargetVersion").asText(null);
            Domain domain = service.upgradeDomain(domainName, targetVersion,
                    regionResolver.resolveRegion(headers));

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

    // ── VPC endpoints ────────────────────────────────────────────────────────

    @POST
    @Path("/opensearch/vpcEndpoints")
    public Response createVpcEndpoint(@Context HttpHeaders headers, String body) {
        JsonNode req = readBody(body);
        OpenSearchVpcEndpoint endpoint = vpcEndpoints.create(req.path("DomainArn").asText(null),
                parseVpcOptions(req.path("VpcOptions")), egressEnabled(req), req.path("ClientToken").asText(null),
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("VpcEndpoint", toVpcEndpointNode(endpoint, "CREATING"));
        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/vpcEndpoints/describe")
    public Response describeVpcEndpoints(@Context HttpHeaders headers, String body) {
        JsonNode req = readBody(body);
        List<String> ids = null;
        if (req.path("VpcEndpointIds").isArray()) {
            ids = new ArrayList<>();
            for (JsonNode id : req.path("VpcEndpointIds")) {
                ids.add(id.asText());
            }
        }
        OpenSearchVpcEndpointService.DescribeResult result =
                vpcEndpoints.describe(ids, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode found = response.putArray("VpcEndpoints");
        result.endpoints().forEach(endpoint -> found.add(toVpcEndpointNode(endpoint, endpoint.getStatus())));
        ArrayNode errors = response.putArray("VpcEndpointErrors");
        for (OpenSearchVpcEndpointService.EndpointError error : result.errors()) {
            errors.addObject()
                    .put("VpcEndpointId", error.vpcEndpointId())
                    .put("ErrorCode", error.errorCode())
                    .put("ErrorMessage", error.errorMessage());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/opensearch/vpcEndpoints/update")
    public Response updateVpcEndpoint(@Context HttpHeaders headers, String body) {
        JsonNode req = readBody(body);
        OpenSearchVpcEndpoint endpoint = vpcEndpoints.update(req.path("VpcEndpointId").asText(null),
                parseVpcOptions(req.path("VpcOptions")), egressEnabled(req), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("VpcEndpoint", toVpcEndpointNode(endpoint, "UPDATING"));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/opensearch/vpcEndpoints/{vpcEndpointId}")
    public Response deleteVpcEndpoint(@Context HttpHeaders headers,
                                      @PathParam("vpcEndpointId") String vpcEndpointId) {
        OpenSearchVpcEndpoint endpoint = vpcEndpoints.delete(vpcEndpointId, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("VpcEndpointSummary", toVpcEndpointSummaryNode(endpoint, "DELETING"));
        return Response.ok(response).build();
    }

    /** One page holds every endpoint, so no {@code NextToken} is returned. */
    @GET
    @Path("/opensearch/vpcEndpoints")
    public Response listVpcEndpoints(@Context HttpHeaders headers) {
        return vpcEndpointSummaries(vpcEndpoints.list(regionResolver.resolveRegion(headers)));
    }

    @GET
    @Path("/opensearch/domain/{domainName}/vpcEndpoints")
    public Response listVpcEndpointsForDomain(@Context HttpHeaders headers,
                                              @PathParam("domainName") String domainName) {
        return vpcEndpointSummaries(vpcEndpoints.listForDomain(domainName, regionResolver.resolveRegion(headers)));
    }

    private Response vpcEndpointSummaries(List<OpenSearchVpcEndpoint> endpoints) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("VpcEndpointSummaryList");
        endpoints.forEach(endpoint -> list.add(toVpcEndpointSummaryNode(endpoint, endpoint.getStatus())));
        return Response.ok(response).build();
    }

    private ObjectNode toVpcEndpointNode(OpenSearchVpcEndpoint endpoint, String status) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("VpcEndpointId", endpoint.getVpcEndpointId());
        node.put("VpcEndpointOwner", endpoint.getVpcEndpointOwner());
        node.put("DomainArn", endpoint.getDomainArn());
        ObjectNode options = toVpcOptionsNode(endpoint.getVpcOptions());
        if (endpoint.getEgressEnabled() != null) {
            options.put("EgressEnabled", endpoint.getEgressEnabled());
        }
        node.set("VpcOptions", options);
        node.put("Status", status);
        vpcEndpoints.endpointAddress(endpoint).ifPresent(address -> node.put("Endpoint", address));
        return node;
    }

    private ObjectNode toVpcEndpointSummaryNode(OpenSearchVpcEndpoint endpoint, String status) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("VpcEndpointId", endpoint.getVpcEndpointId());
        node.put("VpcEndpointOwner", endpoint.getVpcEndpointOwner());
        node.put("DomainArn", endpoint.getDomainArn());
        node.put("Status", status);
        return node;
    }

    private static Boolean egressEnabled(JsonNode req) {
        JsonNode egress = req.path("VpcOptions").path("EgressEnabled");
        return egress.isBoolean() ? egress.asBoolean() : null;
    }

    private JsonNode readBody(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (IOException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
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
        String endpoint = domain.getEndpoint() != null ? domain.getEndpoint() : "";
        if (domain.getVpcOptions() == null) {
            node.put("Endpoint", endpoint);
        } else if (!endpoint.isEmpty()) {
            // AWS reports a domain in a VPC under Endpoints.vpc and leaves Endpoint out; the Terraform
            // AWS provider refuses a VPC domain that carries an Endpoint.
            node.putObject("Endpoints").put("vpc", endpoint);
        }
        if (domain.getAccessPolicies() != null) {
            node.put("AccessPolicies", domain.getAccessPolicies());
        }
        node.set("ClusterConfig", toClusterConfigNode(domain.getClusterConfig()));
        node.set("EBSOptions", toEbsOptionsNode(domain.getEbsOptions()));
        node.putObject("CognitoOptions").put("Enabled", false);
        if (domain.getVpcOptions() != null) {
            node.set("VPCOptions", toVpcOptionsNode(domain.getVpcOptions()));
        }
        if (domain.getAdvancedSecurityOptions() != null) {
            node.set("AdvancedSecurityOptions", toAdvancedSecurityNode(domain.getAdvancedSecurityOptions()));
        }
        if (domain.getEncryptionAtRestOptions() != null) {
            node.set("EncryptionAtRestOptions", toEncryptionAtRestNode(domain.getEncryptionAtRestOptions()));
        }
        if (domain.getNodeToNodeEncryptionOptions() != null) {
            node.set("NodeToNodeEncryptionOptions", toNodeToNodeEncryptionNode(domain.getNodeToNodeEncryptionOptions()));
        }
        if (domain.getDomainEndpointOptions() != null) {
            node.set("DomainEndpointOptions", toDomainEndpointOptionsNode(domain.getDomainEndpointOptions()));
        }
        node.set("AdvancedOptions", toAdvancedOptionsNode(domain));
        node.put("IPAddressType", ipAddressTypeOf(domain));
        if (domain.getOffPeakWindowOptions() != null) {
            node.set("OffPeakWindowOptions", toOffPeakWindowNode(domain.getOffPeakWindowOptions()));
        }
        if (domain.getSoftwareUpdateOptions() != null) {
            node.set("SoftwareUpdateOptions", toSoftwareUpdateNode(domain.getSoftwareUpdateOptions()));
        }
        return node;
    }

    /**
     * Attach optional config sections to {@code DescribeDomainConfig} /
     * {@code UpdateDomainConfig} responses. Only emit entries that the domain
     * actually carries — AWS omits the keys entirely when the feature is
     * unset, rather than emitting empty objects.
     */
    private void attachDomainOptionConfigs(ObjectNode domainConfig, Domain domain, long epochSeconds) {
        if (domain.getAccessPolicies() != null) {
            ObjectNode section = domainConfig.putObject("AccessPolicies");
            section.put("Options", domain.getAccessPolicies());
            section.set("Status", configStatusNode(epochSeconds));
        }
        if (domain.getVpcOptions() != null) {
            ObjectNode section = domainConfig.putObject("VPCOptions");
            section.set("Options", toVpcOptionsNode(domain.getVpcOptions()));
            section.set("Status", configStatusNode(epochSeconds));
        }
        if (domain.getAdvancedSecurityOptions() != null) {
            ObjectNode section = domainConfig.putObject("AdvancedSecurityOptions");
            section.set("Options", toAdvancedSecurityNode(domain.getAdvancedSecurityOptions()));
            section.set("Status", configStatusNode(epochSeconds));
        }
        if (domain.getEncryptionAtRestOptions() != null) {
            ObjectNode section = domainConfig.putObject("EncryptionAtRestOptions");
            section.set("Options", toEncryptionAtRestNode(domain.getEncryptionAtRestOptions()));
            section.set("Status", configStatusNode(epochSeconds));
        }
        if (domain.getNodeToNodeEncryptionOptions() != null) {
            ObjectNode section = domainConfig.putObject("NodeToNodeEncryptionOptions");
            section.set("Options", toNodeToNodeEncryptionNode(domain.getNodeToNodeEncryptionOptions()));
            section.set("Status", configStatusNode(epochSeconds));
        }
        if (domain.getDomainEndpointOptions() != null) {
            ObjectNode section = domainConfig.putObject("DomainEndpointOptions");
            section.set("Options", toDomainEndpointOptionsNode(domain.getDomainEndpointOptions()));
            section.set("Status", configStatusNode(epochSeconds));
        }
        ObjectNode advanced = domainConfig.putObject("AdvancedOptions");
        advanced.set("Options", toAdvancedOptionsNode(domain));
        advanced.set("Status", configStatusNode(epochSeconds));
        ObjectNode ipAddressType = domainConfig.putObject("IPAddressType");
        ipAddressType.put("Options", ipAddressTypeOf(domain));
        ipAddressType.set("Status", configStatusNode(epochSeconds));
        if (domain.getOffPeakWindowOptions() != null) {
            ObjectNode section = domainConfig.putObject("OffPeakWindowOptions");
            section.set("Options", toOffPeakWindowNode(domain.getOffPeakWindowOptions()));
            section.set("Status", configStatusNode(epochSeconds));
        }
        if (domain.getSoftwareUpdateOptions() != null) {
            ObjectNode section = domainConfig.putObject("SoftwareUpdateOptions");
            section.set("Options", toSoftwareUpdateNode(domain.getSoftwareUpdateOptions()));
            section.set("Status", configStatusNode(epochSeconds));
        }
    }

    private ObjectNode toAdvancedOptionsNode(Domain domain) {
        ObjectNode node = objectMapper.createObjectNode();
        new TreeMap<>(domain.getAdvancedOptions()).forEach(node::put);
        return node;
    }

    /** Records written before the address type was stored are reported as {@code ipv4}. */
    private static String ipAddressTypeOf(Domain domain) {
        return domain.getIpAddressType() != null
                ? domain.getIpAddressType() : OpenSearchService.DEFAULT_IP_ADDRESS_TYPE;
    }

    private ObjectNode toOffPeakWindowNode(OffPeakWindowOptions offPeak) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Enabled", Boolean.TRUE.equals(offPeak.getEnabled()));
        if (offPeak.getStartHours() != null) {
            ObjectNode start = node.putObject("OffPeakWindow").putObject("WindowStartTime");
            start.put("Hours", offPeak.getStartHours());
            start.put("Minutes", offPeak.getStartMinutes() != null ? offPeak.getStartMinutes() : 0L);
        }
        return node;
    }

    private ObjectNode toSoftwareUpdateNode(SoftwareUpdateOptions software) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AutoSoftwareUpdateEnabled", Boolean.TRUE.equals(software.getAutoSoftwareUpdateEnabled()));
        return node;
    }

    private ObjectNode toVpcOptionsNode(VpcOptions vpc) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode subnets = node.putArray("SubnetIds");
        vpc.getSubnetIds().forEach(subnets::add);
        ArrayNode sgs = node.putArray("SecurityGroupIds");
        vpc.getSecurityGroupIds().forEach(sgs::add);
        ArrayNode azs = node.putArray("AvailabilityZones");
        vpc.getAvailabilityZones().forEach(azs::add);
        if (vpc.getVpcId() != null) {
            node.put("VPCId", vpc.getVpcId());
        }
        return node;
    }

    private ObjectNode toAdvancedSecurityNode(AdvancedSecurityOptions adv) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Enabled", adv.isEnabled());
        node.put("InternalUserDatabaseEnabled", adv.isInternalUserDatabaseEnabled());
        node.put("AnonymousAuthEnabled", adv.isAnonymousAuthEnabled());
        if (adv.getAnonymousAuthDisableDate() != null) {
            node.put("AnonymousAuthDisableDate", adv.getAnonymousAuthDisableDate());
        }
        // Only echo username + ARN — never master password (AWS doesn't either).
        if (adv.getMasterUserOptions() != null) {
            ObjectNode mu = node.putObject("MasterUserOptions");
            if (adv.getMasterUserOptions().getMasterUserName() != null) {
                mu.put("MasterUserName", adv.getMasterUserOptions().getMasterUserName());
            }
            if (adv.getMasterUserOptions().getMasterUserArn() != null) {
                mu.put("MasterUserARN", adv.getMasterUserOptions().getMasterUserArn());
            }
        }
        return node;
    }

    private ObjectNode toEncryptionAtRestNode(EncryptionAtRestOptions enc) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Enabled", enc.isEnabled());
        if (enc.getKmsKeyId() != null) {
            node.put("KmsKeyId", enc.getKmsKeyId());
        }
        return node;
    }

    private ObjectNode toNodeToNodeEncryptionNode(NodeToNodeEncryptionOptions n2n) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Enabled", n2n.isEnabled());
        return node;
    }

    private ObjectNode toDomainEndpointOptionsNode(DomainEndpointOptions deo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("EnforceHTTPS", deo.isEnforceHttps());
        node.put("TLSSecurityPolicy", deo.getTlsSecurityPolicy());
        node.put("CustomEndpointEnabled", deo.isCustomEndpointEnabled());
        if (deo.getCustomEndpoint() != null) {
            node.put("CustomEndpoint", deo.getCustomEndpoint());
        }
        if (deo.getCustomEndpointCertificateArn() != null) {
            node.put("CustomEndpointCertificateArn", deo.getCustomEndpointCertificateArn());
        }
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
            if (cc.getAvailabilityZoneCount() != null) {
                node.putObject("ZoneAwarenessConfig").put("AvailabilityZoneCount", cc.getAvailabilityZoneCount());
            }
            if (cc.getDedicatedMasterType() != null) {
                node.put("DedicatedMasterType", cc.getDedicatedMasterType());
            }
            if (cc.getDedicatedMasterCount() != null) {
                node.put("DedicatedMasterCount", cc.getDedicatedMasterCount());
            }
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
            if (ebs.getIops() != null) {
                node.put("Iops", ebs.getIops());
            }
            if (ebs.getThroughput() != null) {
                node.put("Throughput", ebs.getThroughput());
            }
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
        // Omitted fields stay null so the service only applies what the request supplied.
        ClusterConfig cc = new ClusterConfig();
        cc.setInstanceType(node.has("InstanceType") ? node.get("InstanceType").asText() : null);
        cc.setInstanceCount(node.has("InstanceCount") ? node.get("InstanceCount").asInt() : null);
        cc.setDedicatedMasterEnabled(node.has("DedicatedMasterEnabled")
                ? node.get("DedicatedMasterEnabled").asBoolean() : null);
        cc.setZoneAwarenessEnabled(node.has("ZoneAwarenessEnabled")
                ? node.get("ZoneAwarenessEnabled").asBoolean() : null);
        JsonNode azCount = node.path("ZoneAwarenessConfig").path("AvailabilityZoneCount");
        cc.setAvailabilityZoneCount(azCount.isNumber() ? azCount.asInt() : null);
        cc.setDedicatedMasterType(node.has("DedicatedMasterType") ? node.get("DedicatedMasterType").asText() : null);
        cc.setDedicatedMasterCount(node.has("DedicatedMasterCount") ? node.get("DedicatedMasterCount").asInt() : null);
        return cc;
    }

    private EbsOptions parseEbsOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        // Omitted fields stay null so the service only applies what the request supplied.
        EbsOptions ebs = new EbsOptions();
        ebs.setEbsEnabled(node.has("EBSEnabled") ? node.get("EBSEnabled").asBoolean() : null);
        ebs.setVolumeType(node.has("VolumeType") ? node.get("VolumeType").asText() : null);
        ebs.setVolumeSize(node.has("VolumeSize") ? node.get("VolumeSize").asInt() : null);
        ebs.setIops(node.has("Iops") ? node.get("Iops").asInt() : null);
        ebs.setThroughput(node.has("Throughput") ? node.get("Throughput").asInt() : null);
        return ebs;
    }

    /** {@code AdvancedOptions} as given; null when the request leaves it out. */
    private static Map<String, String> parseAdvancedOptions(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> options = new LinkedHashMap<>();
        node.properties().forEach(entry -> options.put(entry.getKey(), entry.getValue().asText()));
        return options;
    }

    private static OffPeakWindowOptions parseOffPeakWindowOptions(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode start = node.path("OffPeakWindow").path("WindowStartTime");
        return new OffPeakWindowOptions(
                node.has("Enabled") ? node.get("Enabled").asBoolean() : null,
                start.path("Hours").isNumber() ? start.get("Hours").asLong() : null,
                start.path("Minutes").isNumber() ? start.get("Minutes").asLong() : null);
    }

    private static SoftwareUpdateOptions parseSoftwareUpdateOptions(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new SoftwareUpdateOptions(node.has("AutoSoftwareUpdateEnabled")
                ? node.get("AutoSoftwareUpdateEnabled").asBoolean() : null);
    }

    private VpcOptions parseVpcOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        VpcOptions opts = new VpcOptions();
        List<String> subnets = new ArrayList<>();
        node.path("SubnetIds").forEach(n -> subnets.add(n.asText()));
        opts.setSubnetIds(subnets);
        List<String> sgs = new ArrayList<>();
        node.path("SecurityGroupIds").forEach(n -> sgs.add(n.asText()));
        opts.setSecurityGroupIds(sgs);
        // AvailabilityZones / VPCId are AWS-derived in real life (placement
        // engine reads them from the subnets), but Terraform plans round-trip
        // whatever was last seen, so accept them on update too.
        List<String> azs = new ArrayList<>();
        node.path("AvailabilityZones").forEach(n -> azs.add(n.asText()));
        opts.setAvailabilityZones(azs);
        if (node.has("VPCId")) {
            opts.setVpcId(node.get("VPCId").asText());
        }
        return opts;
    }

    private AdvancedSecurityOptions parseAdvancedSecurityOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        AdvancedSecurityOptions opts = new AdvancedSecurityOptions();
        if (node.has("Enabled")) {
            opts.setEnabled(node.get("Enabled").asBoolean());
        }
        if (node.has("InternalUserDatabaseEnabled")) {
            opts.setInternalUserDatabaseEnabled(node.get("InternalUserDatabaseEnabled").asBoolean());
        }
        if (node.has("AnonymousAuthEnabled")) {
            opts.setAnonymousAuthEnabled(node.get("AnonymousAuthEnabled").asBoolean());
        }
        if (node.has("AnonymousAuthDisableDate")) {
            opts.setAnonymousAuthDisableDate(node.get("AnonymousAuthDisableDate").asText());
        }
        JsonNode mu = node.path("MasterUserOptions");
        if (!mu.isMissingNode() && !mu.isNull()) {
            AdvancedSecurityOptions.MasterUserOptions m = new AdvancedSecurityOptions.MasterUserOptions();
            if (mu.has("MasterUserName")) {
                m.setMasterUserName(mu.get("MasterUserName").asText());
            }
            if (mu.has("MasterUserARN")) {
                m.setMasterUserArn(mu.get("MasterUserARN").asText());
            }
            // Master password intentionally not stored — AWS never echoes it
            // back and we have no use for it without a real security plugin.
            opts.setMasterUserOptions(m);
        }
        return opts;
    }

    private EncryptionAtRestOptions parseEncryptionAtRestOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        EncryptionAtRestOptions opts = new EncryptionAtRestOptions();
        if (node.has("Enabled")) {
            opts.setEnabled(node.get("Enabled").asBoolean());
        }
        if (node.has("KmsKeyId")) {
            opts.setKmsKeyId(node.get("KmsKeyId").asText());
        }
        return opts;
    }

    private NodeToNodeEncryptionOptions parseNodeToNodeEncryptionOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        NodeToNodeEncryptionOptions opts = new NodeToNodeEncryptionOptions();
        if (node.has("Enabled")) {
            opts.setEnabled(node.get("Enabled").asBoolean());
        }
        return opts;
    }

    private DomainEndpointOptions parseDomainEndpointOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        DomainEndpointOptions opts = new DomainEndpointOptions();
        if (node.has("EnforceHTTPS")) {
            opts.setEnforceHttps(node.get("EnforceHTTPS").asBoolean());
        }
        if (node.has("TLSSecurityPolicy")) {
            opts.setTlsSecurityPolicy(node.get("TLSSecurityPolicy").asText());
        }
        if (node.has("CustomEndpointEnabled")) {
            opts.setCustomEndpointEnabled(node.get("CustomEndpointEnabled").asBoolean());
        }
        if (node.has("CustomEndpoint")) {
            opts.setCustomEndpoint(node.get("CustomEndpoint").asText());
        }
        if (node.has("CustomEndpointCertificateArn")) {
            opts.setCustomEndpointCertificateArn(node.get("CustomEndpointCertificateArn").asText());
        }
        return opts;
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
