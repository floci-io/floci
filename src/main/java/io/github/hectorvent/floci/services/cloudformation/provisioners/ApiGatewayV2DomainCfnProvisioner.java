package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService.StoredMapping;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::ApiGatewayV2::DomainName} and {@code AWS::ApiGatewayV2::ApiMapping}.
 *
 * <p>A custom domain is one resource in AWS, reachable through both APIs, so these write the same
 * domain and base path mapping records as {@link ApiGatewayDomainCfnProvisioner}, through the rules
 * the v2 API applies. A domain's physical id is its name and a mapping's is its {@code ApiMappingId},
 * as in AWS. {@code DomainName} is the only createOnly property of either type: a new one replaces
 * the resource, everything else is updated in place, and a mapping keeps its id when its key changes.
 *
 * <p>A replacement creates the new entity and leaves the displaced one to {@link ReplacementCleanup},
 * which deletes it once the stack update commits, keeps it under {@code UpdateReplacePolicy: Retain},
 * and puts it back when a later resource fails the update. An in-place update snapshots what it
 * changes first, so a failed update can restore it.
 */
@ApplicationScoped
public class ApiGatewayV2DomainCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ApiGatewayV2DomainCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOMAIN_NAME_TYPE = "AWS::ApiGatewayV2::DomainName";
    private static final String API_MAPPING_TYPE = "AWS::ApiGatewayV2::ApiMapping";
    private static final String NOT_FOUND = "NotFoundException";
    /** The domain a mapping belongs to: its delete needs it, and its physical id does not carry it. */
    private static final String MAPPING_DOMAIN_NAME_ATTR = "__FlociApiMappingDomainName";
    /**
     * The domain of each mapping this resource displaced and may still owe a delete, by id. A mapping
     * id is unique only within its domain, so the cleanup and the rollback need both.
     */
    private static final String MAPPING_DISPLACED_DOMAINS_ATTR = "__FlociApiMappingDisplacedDomains";
    /** {@code <ApiMappingId>|<DomainName>}, the type's primary identifier, which Cloud Control names a mapping by. */
    private static final String IDENTIFIER_SEPARATOR = "|";

    private final ApiGatewayService apiGatewayService;

    @Inject
    public ApiGatewayV2DomainCfnProvisioner(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(DOMAIN_NAME_TYPE, API_MAPPING_TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case DOMAIN_NAME_TYPE -> provisionDomainName(r, props, ctx);
            case API_MAPPING_TYPE -> provisionApiMapping(r, props, ctx);
            default -> throw new IllegalStateException(
                    "ApiGatewayV2DomainCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case DOMAIN_NAME_TYPE -> deleteDomain(physicalId, region);
            case API_MAPPING_TYPE -> deleteMappingByIdentifier(physicalId, region);
            default -> throw new IllegalStateException(
                    "ApiGatewayV2DomainCfnProvisioner cannot handle " + resourceType);
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        String physicalId = resource.getPhysicalId();
        if (API_MAPPING_TYPE.equals(resource.getResourceType()) && physicalId != null
                && physicalId.contains(IDENTIFIER_SEPARATOR)) {
            // What Cloud Control created, keyed by the primary identifier, <ApiMappingId>|<DomainName>.
            deleteMappingByIdentifier(physicalId, region);
            return;
        }
        if (API_MAPPING_TYPE.equals(resource.getResourceType()) && resource.getAttributes() != null) {
            String domainName = resource.getAttributes().get(MAPPING_DOMAIN_NAME_ATTR);
            if (!isBlank(domainName)) {
                deleteMapping(domainName, resource.getPhysicalId(), region);
                return;
            }
        }
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        // The update committed, so what an in-place change snapshotted for a rollback is spent.
        resource.getAttributes().remove(CfnRollback.API_GATEWAY_V2_DOMAIN_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(CfnRollback.API_MAPPING_UPDATE_SNAPSHOT_ATTR);
        if (DOMAIN_NAME_TYPE.equals(resource.getResourceType())) {
            return ReplacementCleanup.complete(resource, this::delete);
        }
        UpdateCleanupResult result = ReplacementCleanup.complete(resource, mappingDeleter(resource));
        forgetDisplacedDomainsOnceNothingIsOwed(resource);
        return result;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.API_GATEWAY_V2_DOMAIN_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(CfnRollback.API_MAPPING_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
        forgetDisplacedDomainsOnceNothingIsOwed(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return switch (resource.getResourceType()) {
            case DOMAIN_NAME_TYPE -> rollbackDomainUpdate(resource);
            case API_MAPPING_TYPE -> rollbackMappingUpdate(resource);
            default -> false;
        };
    }

    // ──────────────────────────── DomainName ────────────────────────────

    private void provisionDomainName(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String domainName = ctx.resolveOptional(props, "DomainName");
        if (isBlank(domainName)) {
            throw new IllegalArgumentException("AWS::ApiGatewayV2::DomainName requires DomainName");
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        Map<String, Object> request = domainRequest(domainName, props, tags, ctx);

        CustomDomain existing = ctx.isUpdate() ? findDomain(ctx.priorPhysicalId(), ctx.region()) : null;
        CustomDomain provisioned;
        if (existing != null && ctx.reusesPriorEntity(domainName)) {
            // Snapshotted before the first change, so a later resource failing the update can put the
            // domain back. The service validates before it writes, so a template the API would refuse
            // changes nothing.
            r.getAttributes().put(CfnRollback.API_GATEWAY_V2_DOMAIN_UPDATE_SNAPSHOT_ATTR,
                    domainSnapshot(existing, ctx.region()));
            provisioned = apiGatewayService.replaceV2DomainConfiguration(ctx.region(), domainName, request);
            reconcileTags(provisioned, tags, ctx.region());
        } else {
            r.getAttributes().remove(CfnRollback.API_GATEWAY_V2_DOMAIN_UPDATE_SNAPSHOT_ATTR);
            provisioned = apiGatewayService.createV2DomainName(ctx.region(), request);
        }
        r.setPhysicalId(domainName);
        r.getAttributes().put("DomainNameArn",
                AwsArnUtils.Arn.of("apigateway", ctx.region(), "", "/domainnames/" + domainName).toString());
        r.getAttributes().put("RegionalDomainName", orEmpty(provisioned.getRegionalDomainName()));
        r.getAttributes().put("RegionalHostedZoneId", orEmpty(provisioned.getRegionalHostedZoneId()));
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** The template's domain in the shape of a v2 {@code CreateDomainName} body. */
    private static Map<String, Object> domainRequest(String domainName, JsonNode props, Map<String, String> tags,
                                                     ProvisionContext ctx) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("domainName", domainName);
        JsonNode configurations = props.hasNonNull("DomainNameConfigurations")
                ? ctx.engine().resolveNode(props.get("DomainNameConfigurations"))
                : null;
        if (configurations != null && configurations.isArray()) {
            List<Map<String, Object>> resolved = new ArrayList<>();
            for (JsonNode configuration : configurations) {
                Map<String, Object> entry = new LinkedHashMap<>();
                putIfPresent(entry, "certificateArn", ctx.resolveOptional(configuration, "CertificateArn"));
                putIfPresent(entry, "certificateName", ctx.resolveOptional(configuration, "CertificateName"));
                putIfPresent(entry, "endpointType", ctx.resolveOptional(configuration, "EndpointType"));
                putIfPresent(entry, "securityPolicy", ctx.resolveOptional(configuration, "SecurityPolicy"));
                putIfPresent(entry, "ipAddressType", ctx.resolveOptional(configuration, "IpAddressType"));
                putIfPresent(entry, "ownershipVerificationCertificateArn",
                        ctx.resolveOptional(configuration, "OwnershipVerificationCertificateArn"));
                resolved.add(entry);
            }
            request.put("domainNameConfigurations", resolved);
        }
        JsonNode mutualTls = props.hasNonNull("MutualTlsAuthentication")
                ? ctx.engine().resolveNode(props.get("MutualTlsAuthentication"))
                : null;
        if (mutualTls != null && mutualTls.isObject()) {
            Map<String, Object> truststore = new LinkedHashMap<>();
            putIfPresent(truststore, "truststoreUri", ctx.resolveOptional(mutualTls, "TruststoreUri"));
            putIfPresent(truststore, "truststoreVersion", ctx.resolveOptional(mutualTls, "TruststoreVersion"));
            if (!truststore.isEmpty()) {
                request.put("mutualTlsAuthentication", truststore);
            }
        }
        putIfPresent(request, "routingMode", ctx.resolveOptional(props, "RoutingMode"));
        if (!tags.isEmpty()) {
            request.put("tags", tags);
        }
        return request;
    }

    private static String domainSnapshot(CustomDomain domain, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("domainName", domain.getDomainName());
        snapshot.put("certificateArn", domain.getCertificateArn());
        snapshot.put("certificateName", domain.getCertificateName());
        snapshot.put("endpointType", domain.getEndpointConfigurationType());
        snapshot.put("securityPolicy", domain.getSecurityPolicy());
        ObjectNode tags = snapshot.putObject("tags");
        if (domain.getTags() != null) {
            domain.getTags().forEach(tags::put);
        }
        return snapshot.toString();
    }

    private boolean rollbackDomainUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String rawSnapshot = resource.getAttributes().remove(CfnRollback.API_GATEWAY_V2_DOMAIN_UPDATE_SNAPSHOT_ATTR);
        JsonNode snapshot = rawSnapshot == null ? null : readSnapshot(resource, rawSnapshot);
        if (snapshot == null) {
            return false;
        }
        String region = snapshot.path("region").asText();
        String domainName = snapshot.path("domainName").asText();
        Map<String, Object> configuration = new LinkedHashMap<>();
        putIfPresent(configuration, "certificateArn", snapshot.path("certificateArn").asText(null));
        putIfPresent(configuration, "certificateName", snapshot.path("certificateName").asText(null));
        putIfPresent(configuration, "endpointType", snapshot.path("endpointType").asText(null));
        putIfPresent(configuration, "securityPolicy", snapshot.path("securityPolicy").asText(null));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("domainName", domainName);
        request.put("domainNameConfigurations", List.of(configuration));
        CustomDomain restored = apiGatewayService.replaceV2DomainConfiguration(region, domainName, request);
        Map<String, String> tags = new LinkedHashMap<>();
        snapshot.path("tags").fields().forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(restored, tags, region);
        return true;
    }

    /** Drives the domain's tags to the template's: a dropped key is untagged, an unchanged set is left alone. */
    private void reconcileTags(CustomDomain existing, Map<String, String> desired, String region) {
        Map<String, String> current = existing.getTags() == null ? Map.of() : existing.getTags();
        if (desired.equals(current)) {
            return;
        }
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            apiGatewayService.untagDomainName(region, existing.getDomainName(), stale);
        }
        if (!desired.isEmpty()) {
            apiGatewayService.tagDomainName(region, existing.getDomainName(), desired);
        }
    }

    private CustomDomain findDomain(String domainName, String region) {
        try {
            return apiGatewayService.getDomainName(region, domainName);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Custom domain {0} from the previous execution is gone, creating it again", domainName);
            return null;
        }
    }

    private void deleteDomain(String domainName, String region) {
        CfnDeletes.safeDelete("custom domain", domainName,
                () -> apiGatewayService.deleteDomainName(region, domainName), NOT_FOUND);
    }

    // ──────────────────────────── ApiMapping ────────────────────────────

    private void provisionApiMapping(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String domainName = ctx.resolveOptional(props, "DomainName");
        String apiId = ctx.resolveOptional(props, "ApiId");
        String stage = ctx.resolveOptional(props, "Stage");
        if (isBlank(domainName) || isBlank(apiId) || isBlank(stage)) {
            throw new IllegalArgumentException(
                    "AWS::ApiGatewayV2::ApiMapping requires DomainName, ApiId and Stage");
        }
        String apiMappingKey = ctx.resolveOptional(props, "ApiMappingKey");

        String priorDomainName = r.getAttributes().get(MAPPING_DOMAIN_NAME_ATTR);
        StoredMapping existing = ctx.isUpdate() && domainName.equals(priorDomainName)
                ? findMapping(priorDomainName, ctx.priorPhysicalId(), ctx.region())
                : null;
        StoredMapping provisioned;
        if (existing != null) {
            // ApiId, Stage and ApiMappingKey update in place, and the mapping keeps its id. The
            // snapshot comes first, so a later resource failing the update can put the mapping back.
            r.getAttributes().put(CfnRollback.API_MAPPING_UPDATE_SNAPSHOT_ATTR,
                    mappingSnapshot(existing, domainName, ctx.region()));
            provisioned = apiGatewayService.updateApiMapping(ctx.region(), domainName, existing.apiMappingId(),
                    apiMappingKey, apiId, stage);
        } else {
            r.getAttributes().remove(CfnRollback.API_MAPPING_UPDATE_SNAPSHOT_ATTR);
            Set<String> excludedIds = Set.of();
            if (ctx.isUpdate() && isBlank(priorDomainName)) {
                // The stub arm's record from before this provisioner existed: nothing was created for
                // it, so the cleanup and the rollback have no domain to delete it from.
                rememberDisplacedDomain(r, ctx.priorPhysicalId(), "");
            } else if (ctx.isUpdate() && !domainName.equals(priorDomainName)) {
                // A new DomainName replaces the mapping. On AWS the replacement gets an id of its own;
                // here it would derive the displaced mapping's id when the key is the same, and two
                // mappings under one physical id cannot be told apart by the cleanup or the rollback.
                excludedIds = Set.of(ctx.priorPhysicalId());
                rememberDisplacedDomain(r, ctx.priorPhysicalId(), priorDomainName);
            }
            provisioned = apiGatewayService.createApiMapping(ctx.region(), domainName, apiMappingKey, apiId, stage,
                    excludedIds);
        }
        String apiMappingId = provisioned.apiMappingId();
        r.setPhysicalId(apiMappingId);
        r.getAttributes().put("ApiMappingId", apiMappingId);
        r.getAttributes().put(MAPPING_DOMAIN_NAME_ATTR, domainName);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        forgetDisplacedDomainsOnceNothingIsOwed(r);
    }

    private static String mappingSnapshot(StoredMapping existing, String domainName, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("domainName", domainName);
        snapshot.put("apiMappingId", existing.apiMappingId());
        String key = ApiGatewayService.canonicalBasePath(existing.storedPath());
        snapshot.put("apiMappingKey", "(none)".equals(key) ? null : key);
        snapshot.put("apiId", existing.mapping().getRestApiId());
        snapshot.put("stage", existing.mapping().getStage());
        return snapshot.toString();
    }

    private boolean rollbackMappingUpdate(StackResource resource) {
        // Rolling back a replacement deletes it through the domain it was created on, and one whose
        // delete fails stays owed, so its domain is recorded before the attempt.
        String replacement = resource.getPhysicalId();
        String replacementDomain = resource.getAttributes().get(MAPPING_DOMAIN_NAME_ATTR);
        boolean replacementAlreadyKnown = displacedDomains(resource).containsKey(replacement);
        if (!replacementAlreadyKnown && !isBlank(replacementDomain)) {
            rememberDisplacedDomain(resource, replacement, replacementDomain);
        }
        boolean rolledBack;
        try {
            rolledBack = ReplacementCleanup.rollback(resource, mappingDeleter(resource));
        } finally {
            // The prior mapping stands again even when deleting the replacement failed, so the
            // resource names the prior mapping's domain from here on.
            Map<String, String> domains = displacedDomains(resource);
            if (!replacement.equals(resource.getPhysicalId()) && domains.containsKey(resource.getPhysicalId())) {
                String priorDomain = domains.remove(resource.getPhysicalId());
                if (isBlank(priorDomain)) {
                    resource.getAttributes().remove(MAPPING_DOMAIN_NAME_ATTR);
                } else {
                    resource.getAttributes().put(MAPPING_DOMAIN_NAME_ATTR, priorDomain);
                }
                writeDisplacedDomains(resource, domains);
            }
        }
        if (rolledBack) {
            forgetDisplacedDomainsOnceNothingIsOwed(resource);
            return true;
        }
        if (!replacementAlreadyKnown) {
            Map<String, String> domains = displacedDomains(resource);
            domains.remove(replacement);
            writeDisplacedDomains(resource, domains);
        }
        String rawSnapshot = resource.getAttributes().remove(CfnRollback.API_MAPPING_UPDATE_SNAPSHOT_ATTR);
        JsonNode snapshot = rawSnapshot == null ? null : readSnapshot(resource, rawSnapshot);
        if (snapshot == null) {
            return false;
        }
        apiGatewayService.updateApiMapping(snapshot.path("region").asText(), snapshot.path("domainName").asText(),
                snapshot.path("apiMappingId").asText(), snapshot.path("apiMappingKey").asText(null),
                snapshot.path("apiId").asText(), snapshot.path("stage").asText());
        return true;
    }

    private StoredMapping findMapping(String domainName, String apiMappingId, String region) {
        try {
            return apiGatewayService.getApiMapping(region, domainName, apiMappingId);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("API mapping {0} on {1} from the previous execution is gone, creating it again",
                    apiMappingId, domainName);
            return null;
        }
    }

    /** Deletes a displaced or rolled-back mapping through the domain it was created on. */
    private ReplacementCleanup.Deleter mappingDeleter(StackResource resource) {
        return (type, apiMappingId, region) -> {
            String domainName = displacedDomains(resource).getOrDefault(apiMappingId,
                    resource.getAttributes().get(MAPPING_DOMAIN_NAME_ATTR));
            if (isBlank(domainName)) {
                LOG.debugv("API mapping {0} has no domain, so nothing was created for it", apiMappingId);
                return;
            }
            deleteMapping(domainName, apiMappingId, region);
        };
    }

    /**
     * The id-only delete, which Cloud Control reaches with the type's primary identifier,
     * {@code <ApiMappingId>|<DomainName>}. A bare id names no domain to look in.
     */
    private void deleteMappingByIdentifier(String identifier, String region) {
        int separator = identifier == null ? -1 : identifier.indexOf(IDENTIFIER_SEPARATOR);
        if (separator <= 0 || separator == identifier.length() - 1) {
            throw new AwsException(NOT_FOUND, "Unable to find ApiMapping with ID " + identifier, 404);
        }
        deleteMapping(identifier.substring(separator + 1), identifier.substring(0, separator), region);
    }

    /** Tolerates a mapping already gone, including one removed together with its domain. */
    private void deleteMapping(String domainName, String apiMappingId, String region) {
        CfnDeletes.safeDelete("API mapping", apiMappingId,
                () -> apiGatewayService.deleteApiMapping(region, domainName, apiMappingId), NOT_FOUND);
    }

    private static void rememberDisplacedDomain(StackResource resource, String apiMappingId, String domainName) {
        Map<String, String> domains = displacedDomains(resource);
        domains.put(apiMappingId, domainName);
        writeDisplacedDomains(resource, domains);
    }

    private static void forgetDisplacedDomainsOnceNothingIsOwed(StackResource resource) {
        if (!ReplacementCleanup.hasReplacement(resource)) {
            resource.getAttributes().remove(MAPPING_DISPLACED_DOMAINS_ATTR);
        }
    }

    private static Map<String, String> displacedDomains(StackResource resource) {
        Map<String, String> domains = new LinkedHashMap<>();
        String raw = resource.getAttributes().get(MAPPING_DISPLACED_DOMAINS_ATTR);
        if (isBlank(raw)) {
            return domains;
        }
        try {
            MAPPER.readTree(raw).fields().forEachRemaining(e -> domains.put(e.getKey(), e.getValue().asText()));
        } catch (JsonProcessingException e) {
            LOG.warnv("Unreadable displaced API mapping domains on {0}, dropping them: {1}",
                    resource.getLogicalId(), e.getMessage());
        }
        return domains;
    }

    private static void writeDisplacedDomains(StackResource resource, Map<String, String> domains) {
        if (domains.isEmpty()) {
            resource.getAttributes().remove(MAPPING_DISPLACED_DOMAINS_ATTR);
            return;
        }
        ObjectNode node = MAPPER.createObjectNode();
        domains.forEach(node::put);
        resource.getAttributes().put(MAPPING_DISPLACED_DOMAINS_ATTR, node.toString());
    }

    // ──────────────────────────── Shared ────────────────────────────

    private static JsonNode readSnapshot(StackResource resource, String rawSnapshot) {
        try {
            return MAPPER.readTree(rawSnapshot);
        } catch (JsonProcessingException e) {
            LOG.errorv("Could not parse the update snapshot of {0}: {1}", resource.getLogicalId(), e.getMessage());
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> request, String key, String value) {
        if (value != null) {
            request.put(key, value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
