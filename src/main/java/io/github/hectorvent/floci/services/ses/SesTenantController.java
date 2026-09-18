package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.Tenant;
import io.github.hectorvent.floci.services.ses.model.TenantResourceAssociation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.intMemberOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringArrayOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * SES V2 tenant endpoints ({@code /v2/email/tenants}, {@code /v2/email/tenant} and
 * {@code /v2/email/resources/tenants}). Tenant create, get, list and suppression attributes
 * call {@link SesTenantService} directly; the resource
 * associations and tenant delete go through the {@link SesService} facade, which checks the
 * associated identity, configuration set or template exists and cascades the tenant's
 * suppression entries, work that spans several domains.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesTenantController {

    private static final Logger LOG = Logger.getLogger(SesTenantController.class);

    private final SesTenantService tenantService;
    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesTenantController(SesTenantService tenantService, SesService sesService,
                               RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/tenants")
    public Response createTenant(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            List<String> suppressedReasons = null;
            String suppressionScope = null;
            JsonNode attrs = request.path("SuppressionAttributes");
            if (!attrs.isMissingNode() && !attrs.isNull()) {
                if (!attrs.isObject()) {
                    throw new AwsException("SerializationException", null, 400);
                }
                suppressedReasons = stringArrayOrAbsent(attrs, "SuppressedReasons");
                suppressionScope = stringMemberOrAbsent(attrs, "SuppressionScope");
            }
            String accountId = regionResolver.getAccountId();
            Tenant tenant = tenantService.createTenant(tenantName, tags, suppressedReasons,
                    suppressionScope, accountId, region);
            return Response.ok(tenantJson(tenant)).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    @POST
    @Path("/tenants/get")
    public Response getTenant(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            Tenant tenant = tenantService.getTenant(tenantName, region);
            ObjectNode result = objectMapper.createObjectNode();
            result.set("Tenant", tenantJson(tenant));
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    @POST
    @Path("/tenants/list")
    public Response listTenants(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            // Parse the body so a malformed request is rejected rather than silently accepted. Phase 1
            // returns every tenant in one page; PageSize/NextToken pagination is a follow-up.
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tenants = result.putArray("Tenants");
        for (Tenant t : tenantService.listTenants(region)) {
            // ListTenants returns the TenantInfo subset (no Tags / SendingStatus).
            ObjectNode item = tenants.addObject();
            item.put("TenantName", t.tenantName());
            item.put("TenantId", t.tenantId());
            item.put("TenantArn", t.tenantArn());
            if (t.createdTimestamp() != null) {
                item.put("CreatedTimestamp", t.createdTimestamp().toEpochMilli() / 1000.0);
            }
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/tenants/delete")
    public Response deleteTenant(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            sesService.deleteTenant(tenantName, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    // AWS's wire format for ResourceType, in responses and as the RESOURCE_TYPE filter value, is the
    // ARN segment (identity / configuration-set / template), not the SDK's EMAIL_IDENTITY-style enum
    // spelling: real AWS rejects the enum spelling.
    @POST
    @Path("/tenants/resources")
    public Response createTenantResourceAssociation(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            String resourceArn = stringMemberOrAbsent(request, "ResourceArn");
            sesService.createTenantResourceAssociation(tenantName, resourceArn,
                    regionResolver.getAccountId(), region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    @POST
    @Path("/tenants/resources/delete")
    public Response deleteTenantResourceAssociation(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            String resourceArn = stringMemberOrAbsent(request, "ResourceArn");
            sesService.deleteTenantResourceAssociation(tenantName, resourceArn,
                    regionResolver.getAccountId(), region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    @POST
    @Path("/tenants/resources/list")
    public Response listTenantResources(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            String resourceTypeFilter = null;
            JsonNode filter = request.path("Filter");
            if (!filter.isMissingNode() && !filter.isNull()) {
                if (!filter.isObject()) {
                    throw new AwsException("SerializationException", null, 400);
                }
                resourceTypeFilter = stringMemberOrAbsent(filter, "RESOURCE_TYPE");
            }
            Integer pageSize = intMemberOrAbsent(request, "PageSize");
            String nextToken = stringMemberOrAbsent(request, "NextToken");
            List<TenantResourceAssociation> associations = sesService.listTenantResources(
                    tenantName, resourceTypeFilter, pageSize, nextToken, region);
            ObjectNode result = objectMapper.createObjectNode();
            // AWS renders NextToken as an explicit null on the last (here: only) page.
            result.putNull("NextToken");
            ArrayNode resources = result.putArray("TenantResources");
            for (TenantResourceAssociation a : associations) {
                ObjectNode item = resources.addObject();
                item.put("ResourceArn", a.resourceArn());
                item.put("ResourceType", a.resourceType());
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    @POST
    @Path("/resources/tenants/list")
    public Response listResourceTenants(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String resourceArn = stringMemberOrAbsent(request, "ResourceArn");
            Integer pageSize = intMemberOrAbsent(request, "PageSize");
            String nextToken = stringMemberOrAbsent(request, "NextToken");
            List<TenantResourceAssociation> associations = sesService.listResourceTenants(
                    resourceArn, pageSize, nextToken, regionResolver.getAccountId(), region);
            ObjectNode result = objectMapper.createObjectNode();
            result.putNull("NextToken");
            ArrayNode tenants = result.putArray("ResourceTenants");
            for (TenantResourceAssociation a : associations) {
                // ResourceTenantMetadata has no TenantArn (probe-confirmed).
                ObjectNode item = tenants.addObject();
                item.put("TenantName", a.tenantName());
                item.put("TenantId", a.tenantId());
                item.put("ResourceArn", a.resourceArn());
                if (a.associatedTimestamp() != null) {
                    item.put("AssociatedTimestamp", a.associatedTimestamp().toEpochMilli() / 1000.0);
                }
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    // Phase 3: PutTenantSuppressionAttributes. The route really is the singular "tenant", unlike
    // every other tenant route (verified against real AWS and the SDK marshaller).
    @POST
    @Path("/tenant/suppression")
    public Response putTenantSuppressionAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            List<String> suppressedReasons = stringArrayOrAbsent(request, "SuppressedReasons");
            String suppressionScope = stringMemberOrAbsent(request, "SuppressionScope");
            tenantService.putSuppressionAttributes(tenantName, suppressedReasons,
                    suppressionScope, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    private ObjectNode tenantJson(Tenant tenant) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("TenantName", tenant.tenantName());
        node.put("TenantId", tenant.tenantId());
        node.put("TenantArn", tenant.tenantArn());
        if (tenant.createdTimestamp() != null) {
            node.put("CreatedTimestamp", tenant.createdTimestamp().toEpochMilli() / 1000.0);
        }
        if (tenant.tags() != null && !tenant.tags().isEmpty()) {
            ArrayNode tags = node.putArray("Tags");
            for (Tag t : tenant.tags()) {
                ObjectNode tagNode = tags.addObject();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
            }
        }
        node.put("SendingStatus", tenant.sendingStatus());
        // AWS renders the block as an explicit null when the tenant has none.
        if (tenant.suppressionAttributes() == null) {
            node.putNull("SuppressionAttributes");
        } else {
            ObjectNode attrs = node.putObject("SuppressionAttributes");
            ArrayNode reasons = attrs.putArray("SuppressedReasons");
            for (String reason : tenant.suppressionAttributes().suppressedReasons()) {
                reasons.add(reason);
            }
            attrs.put("SuppressionScope", tenant.suppressionAttributes().suppressionScope());
        }
        return node;
    }
}
