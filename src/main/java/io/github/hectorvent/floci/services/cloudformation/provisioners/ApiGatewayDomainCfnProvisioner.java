package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.BasePathMapping;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::ApiGateway::DomainName} and {@code AWS::ApiGateway::BasePathMapping}.
 *
 * <p>A domain's physical id is its name, as in AWS, and its five read-only attributes come from the
 * stored domain. A regional domain has no CloudFront distribution, so for one the two distribution
 * attributes are empty rather than the literal {@code LogicalId.DistributionDomainName} an unset
 * attribute would resolve to. A mapping's physical id is the compound identifier CloudFormation
 * reports for the type, {@code <DomainName>|<BasePath>}, with {@code (none)} as the API's spelling
 * of an empty base path.
 *
 * <p>{@code MutualTlsAuthentication}, {@code OwnershipVerificationCertificateArn},
 * {@code EndpointAccessMode}, {@code RoutingMode} and {@code EndpointConfiguration.IpAddressType}
 * are accepted and ignored: nothing in the emulator reads them. The other {@code AWS::ApiGateway::*}
 * types still live in the legacy switch.
 */
@ApplicationScoped
public class ApiGatewayDomainCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ApiGatewayDomainCfnProvisioner.class);
    private static final String DOMAIN_NAME_TYPE = "AWS::ApiGateway::DomainName";
    private static final String BASE_PATH_MAPPING_TYPE = "AWS::ApiGateway::BasePathMapping";
    private static final String NOT_FOUND = "NotFoundException";
    private static final String MAPPING_ID_SEPARATOR = "|";

    private final ApiGatewayService apiGatewayService;

    public ApiGatewayDomainCfnProvisioner(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(DOMAIN_NAME_TYPE, BASE_PATH_MAPPING_TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case DOMAIN_NAME_TYPE -> provisionDomainName(r, props, ctx);
            case BASE_PATH_MAPPING_TYPE -> provisionBasePathMapping(r, props, ctx);
            default -> throw new IllegalStateException(
                    "ApiGatewayDomainCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case DOMAIN_NAME_TYPE -> deleteDomain(physicalId, region);
            case BASE_PATH_MAPPING_TYPE -> deleteMapping(physicalId, region);
            default -> throw new IllegalStateException(
                    "ApiGatewayDomainCfnProvisioner cannot handle " + resourceType);
        }
    }

    /** The template's view of a domain: the name, and everything that can change without a replacement. */
    private record DomainSpec(String endpointType, String certificateArn, String regionalCertificateArn,
                              String securityPolicy, Map<String, String> tags) {
    }

    private void provisionDomainName(StackResource r, JsonNode props, ProvisionContext ctx) {
        String domainName = ctx.resolveOptional(props, "DomainName");
        if (isBlank(domainName)) {
            throw new IllegalArgumentException("AWS::ApiGateway::DomainName requires DomainName");
        }
        DomainSpec desired = new DomainSpec(endpointType(props, ctx),
                ctx.resolveOptional(props, "CertificateArn"),
                ctx.resolveOptional(props, "RegionalCertificateArn"),
                ctx.resolveOptional(props, "SecurityPolicy"),
                ctx.resolveTags(props, "Tags"));

        // DomainName is the schema's only createOnly property: a new name replaces the domain, and
        // the replacement is created before the prior domain is removed, as CloudFormation does.
        // Everything else is patched in place, so the regional name a DNS record outside the stack
        // points at survives the update.
        CustomDomain existing = ctx.isUpdate() ? findDomain(ctx.priorPhysicalId(), ctx.region()) : null;
        CustomDomain provisioned;
        if (existing != null && ctx.reusesPriorEntity(domainName)) {
            provisioned = updateDomainInPlace(existing, desired, ctx.region());
        } else {
            provisioned = apiGatewayService.createDomainName(ctx.region(), createDomainRequest(domainName, desired));
            if (existing != null) {
                deleteDomain(ctx.priorPhysicalId(), ctx.region());
            }
        }
        r.setPhysicalId(domainName);
        r.getAttributes().put("DomainNameArn",
                "arn:aws:apigateway:" + ctx.region() + "::/domainnames/" + domainName);
        r.getAttributes().put("RegionalDomainName", orEmpty(provisioned.getRegionalDomainName()));
        r.getAttributes().put("RegionalHostedZoneId", orEmpty(provisioned.getRegionalHostedZoneId()));
        r.getAttributes().put("DistributionDomainName", orEmpty(provisioned.getDistributionDomainName()));
        r.getAttributes().put("DistributionHostedZoneId", orEmpty(provisioned.getDistributionHostedZoneId()));
    }

    private static String endpointType(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("EndpointConfiguration")) {
            return null;
        }
        JsonNode configuration = ctx.engine().resolveNode(props.get("EndpointConfiguration"));
        JsonNode types = configuration == null ? null : configuration.path("Types");
        if (types == null || !types.isArray() || types.size() == 0) {
            return null;
        }
        return ctx.engine().resolve(types.get(0));
    }

    private static Map<String, Object> createDomainRequest(String domainName, DomainSpec spec) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("domainName", domainName);
        if (spec.endpointType() != null) {
            request.put("endpointConfiguration", Map.of("types", List.of(spec.endpointType())));
        }
        putIfPresent(request, "certificateArn", spec.certificateArn());
        putIfPresent(request, "regionalCertificateArn", spec.regionalCertificateArn());
        putIfPresent(request, "securityPolicy", spec.securityPolicy());
        if (!spec.tags().isEmpty()) {
            request.put("tags", spec.tags());
        }
        return request;
    }

    private CustomDomain updateDomainInPlace(CustomDomain existing, DomainSpec desired, String region) {
        List<Map<String, String>> operations = new ArrayList<>();
        addReplace(operations, "/certificateArn", existing.getCertificateArn(), desired.certificateArn());
        addReplace(operations, "/regionalCertificateArn",
                existing.getRegionalCertificateArn(), desired.regionalCertificateArn());
        addReplace(operations, "/securityPolicy", existing.getSecurityPolicy(), desired.securityPolicy());
        if (desired.endpointType() != null
                && !desired.endpointType().equals(existing.getEndpointConfigurationType())) {
            // The patch path names the type the domain has now; the value names the one it should get.
            operations.add(replaceOperation(
                    "/endpointConfiguration/types/" + existing.getEndpointConfigurationType(),
                    desired.endpointType()));
        }
        // The patch carries the validation, so it goes first: a rejected update leaves the tags as
        // they were instead of half-applying the template.
        CustomDomain updated = operations.isEmpty()
                ? existing
                : apiGatewayService.updateDomainName(region, existing.getDomainName(), operations);
        reconcileTags(updated, desired.tags(), region);
        return updated;
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

    private void provisionBasePathMapping(StackResource r, JsonNode props, ProvisionContext ctx) {
        String domainName = ctx.resolveOptional(props, "DomainName");
        String restApiId = ctx.resolveOptional(props, "RestApiId");
        if (isBlank(domainName) || isBlank(restApiId)) {
            throw new IllegalArgumentException(
                    "AWS::ApiGateway::BasePathMapping requires DomainName and RestApiId");
        }
        String basePath = ApiGatewayService.canonicalBasePath(ctx.resolveOptional(props, "BasePath"));
        String stage = ctx.resolveOptional(props, "Stage");
        String physicalId = domainName + MAPPING_ID_SEPARATOR + basePath;

        // DomainName and BasePath are createOnly: a change to either replaces the mapping, created
        // before the prior one is removed. RestApiId and Stage are patched in place.
        BasePathMapping existing = ctx.isUpdate() ? findMapping(ctx.priorPhysicalId(), ctx.region()) : null;
        if (existing != null && ctx.reusesPriorEntity(physicalId)) {
            List<Map<String, String>> operations = new ArrayList<>();
            addReplace(operations, "/restApiId", existing.getRestApiId(), restApiId);
            addReplace(operations, "/stage", existing.getStage(), stage);
            if (!operations.isEmpty()) {
                apiGatewayService.updateBasePathMapping(ctx.region(), domainName, basePath, operations);
            }
        } else {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("basePath", basePath);
            request.put("restApiId", restApiId);
            putIfPresent(request, "stage", stage);
            apiGatewayService.createBasePathMapping(ctx.region(), domainName, request);
            if (existing != null) {
                deleteMapping(ctx.priorPhysicalId(), ctx.region());
            }
        }
        r.setPhysicalId(physicalId);
    }

    private BasePathMapping findMapping(String physicalId, String region) {
        try {
            MappingId id = MappingId.parse(physicalId);
            return apiGatewayService.getBasePathMapping(region, id.domainName(), id.basePath());
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Base path mapping {0} from the previous execution is gone, creating it again", physicalId);
            return null;
        }
    }

    private void deleteMapping(String physicalId, String region) {
        MappingId id = MappingId.parse(physicalId);
        CfnDeletes.safeDelete("base path mapping", physicalId,
                () -> apiGatewayService.deleteBasePathMapping(region, id.domainName(), id.basePath()), NOT_FOUND);
    }

    /** The compound identifier of a mapping, {@code <DomainName>|<BasePath>}; neither part can contain the bar. */
    private record MappingId(String domainName, String basePath) {

        static MappingId parse(String physicalId) {
            int separator = physicalId == null ? -1 : physicalId.indexOf(MAPPING_ID_SEPARATOR);
            if (separator <= 0 || separator == physicalId.length() - 1) {
                throw new AwsException(NOT_FOUND,
                        "Invalid base path mapping identifier specified, expected <DomainName>|<BasePath>: "
                                + physicalId, 404);
            }
            return new MappingId(physicalId.substring(0, separator), physicalId.substring(separator + 1));
        }
    }

    /** A property the template leaves out keeps its stored value: the PATCH API cannot unset these. */
    private static void addReplace(List<Map<String, String>> operations, String path,
                                   String current, String desired) {
        if (desired != null && !desired.equals(current)) {
            operations.add(replaceOperation(path, desired));
        }
    }

    private static Map<String, String> replaceOperation(String path, String value) {
        return Map.of("op", "replace", "path", path, "value", value);
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
