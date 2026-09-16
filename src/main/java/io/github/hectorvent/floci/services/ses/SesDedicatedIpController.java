package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.parseOptionString;
import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readOptionBody;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readRequiredStringField;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;

/**
 * SES V2 dedicated IP pool and dedicated IP endpoints ({@code /v2/email/dedicated-ip-pools} and
 * {@code /v2/email/dedicated-ips}), split out of {@link SesController}. Every operation is a
 * single-domain call on {@link SesDedicatedIpService}, so this controller does not touch the
 * {@link SesService} facade; the account-level auto-warmup setting stays under {@code /account}.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesDedicatedIpController {

    private static final Logger LOG = Logger.getLogger(SesDedicatedIpController.class);

    private final SesDedicatedIpService dedicatedIpService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesDedicatedIpController(SesDedicatedIpService dedicatedIpService,
                                    RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.dedicatedIpService = dedicatedIpService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/dedicated-ip-pools")
    public Response createDedicatedIpPool(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String poolName = readRequiredStringField(request, "PoolName");
            JsonNode scalingNode = request.path("ScalingMode");
            String scalingMode;
            if (scalingNode.isMissingNode() || scalingNode.isNull()) {
                scalingMode = null;
            } else if (!scalingNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "The ScalingMode parameter is invalid.", 400);
            } else {
                scalingMode = scalingNode.asText();
            }
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            dedicatedIpService.createDedicatedIpPool(poolName, scalingMode, tags, region);
            LOG.infov("SES V2 CreateDedicatedIpPool: {0}", poolName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/dedicated-ip-pools")
    public Response listDedicatedIpPools(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode pools = result.putArray("DedicatedIpPools");
        dedicatedIpService.listDedicatedIpPools(region).forEach(pools::add);
        return Response.ok(result).build();
    }

    @GET
    @Path("/dedicated-ip-pools/{poolName}")
    public Response getDedicatedIpPool(@Context HttpHeaders headers,
                                       @PathParam("poolName") String poolName) {
        String region = regionResolver.resolveRegion(headers);
        DedicatedIpPool pool = dedicatedIpService.getDedicatedIpPool(poolName, region);
        ObjectNode result = objectMapper.createObjectNode();
        // Built explicitly: the AWS DedicatedIpPool shape carries only PoolName and ScalingMode;
        // the model's tags are exposed via ListTagsForResource, not here.
        ObjectNode poolNode = result.putObject("DedicatedIpPool");
        poolNode.put("PoolName", pool.getPoolName());
        poolNode.put("ScalingMode", pool.getScalingMode());
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/dedicated-ip-pools/{poolName}")
    public Response deleteDedicatedIpPool(@Context HttpHeaders headers,
                                          @PathParam("poolName") String poolName) {
        String region = regionResolver.resolveRegion(headers);
        dedicatedIpService.deleteDedicatedIpPool(poolName, region);
        LOG.infov("SES V2 DeleteDedicatedIpPool: {0}", poolName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/dedicated-ip-pools/{poolName}/scaling")
    public Response putDedicatedIpPoolScalingAttributes(@Context HttpHeaders headers,
                                                        @PathParam("poolName") String poolName,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            String scalingMode = parseOptionString(request.path("ScalingMode"), "ScalingMode");
            dedicatedIpService.putDedicatedIpPoolScalingAttributes(poolName, scalingMode, region);
            LOG.infov("SES V2 PutDedicatedIpPoolScalingAttributes on {0}", poolName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @GET
    @Path("/dedicated-ips")
    public Response getDedicatedIps(@Context HttpHeaders headers) {
        regionResolver.resolveRegion(headers);
        // Floci does not model leased dedicated IPs, so the account has none.
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("DedicatedIps");
        result.putNull("NextToken");
        return Response.ok(result).build();
    }

    @GET
    @Path("/dedicated-ips/{ip}")
    public Response getDedicatedIp(@Context HttpHeaders headers, @PathParam("ip") String ip) {
        String region = regionResolver.resolveRegion(headers);
        dedicatedIpService.getDedicatedIp(ip, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/dedicated-ips/{ip}/pool")
    public Response putDedicatedIpInPool(@Context HttpHeaders headers,
                                         @PathParam("ip") String ip, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            String destinationPoolName = parseOptionString(
                    request.path("DestinationPoolName"), "DestinationPoolName");
            dedicatedIpService.putDedicatedIpInPool(ip, destinationPoolName, region);
            LOG.infov("SES V2 PutDedicatedIpInPool: {0}", ip);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/dedicated-ips/{ip}/warmup")
    public Response putDedicatedIpWarmupAttributes(@Context HttpHeaders headers,
                                                   @PathParam("ip") String ip, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            dedicatedIpService.putDedicatedIpWarmupAttributes(ip,
                    parseWarmupPercentage(request.path("WarmupPercentage")), region);
            LOG.infov("SES V2 PutDedicatedIpWarmupAttributes: {0}", ip);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    // JSON-layer concern only: a non-integer WarmupPercentage is a SerializationException,
    // matching AWS. Required/range validation lives in the service.
    private static Integer parseWarmupPercentage(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isInt()) {
            throw new AwsException("SerializationException", null, 400);
        }
        return node.intValue();
    }
}
