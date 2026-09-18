package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.readRequiredStringField;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * SES V2 suppression-list endpoints ({@code /v2/email/suppression/addresses}). Every operation
 * keeps going through the {@link SesService} facade,
 * because an optional {@code TenantName} routes it to that tenant's own list, which spans the
 * suppression and tenant domains; the account-level suppression attributes live under
 * {@code /account}.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesSuppressionController {

    private static final Logger LOG = Logger.getLogger(SesSuppressionController.class);

    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesSuppressionController(SesService sesService, RegionResolver regionResolver,
                                    ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/suppression/addresses")
    public Response putSuppressedDestination(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String emailAddress = readRequiredStringField(request, "EmailAddress");
            String reason = readRequiredStringField(request, "Reason");
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            sesService.putSuppressedDestination(region, emailAddress, reason, tenantName);
            LOG.infov("SES V2 PutSuppressedDestination: {0} ({1})", emailAddress, reason);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/suppression/addresses/{emailAddress}")
    public Response getSuppressedDestination(@Context HttpHeaders headers,
                                              @PathParam("emailAddress") String emailAddress,
                                              @QueryParam("TenantName") String tenantName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            SuppressedDestination suppressed =
                    sesService.getSuppressedDestination(region, emailAddress, tenantName);
            ObjectNode result = objectMapper.createObjectNode();
            ObjectNode entry = result.putObject("SuppressedDestination");
            entry.put("EmailAddress", suppressed.getEmailAddress());
            entry.put("Reason", suppressed.getReason());
            if (suppressed.getLastUpdateTime() != null) {
                entry.put("LastUpdateTime", suppressed.getLastUpdateTime().getEpochSecond());
            }
            // AWS renders TenantName on every entry: an explicit null for account-level ones.
            entry.put("TenantName", suppressed.getTenantName());
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @DELETE
    @Path("/suppression/addresses/{emailAddress}")
    public Response deleteSuppressedDestination(@Context HttpHeaders headers,
                                                 @PathParam("emailAddress") String emailAddress,
                                                 @QueryParam("TenantName") String tenantName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteSuppressedDestination(region, emailAddress, tenantName);
            LOG.infov("SES V2 DeleteSuppressedDestination: {0}", emailAddress);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @GET
    @Path("/suppression/addresses")
    public Response listSuppressedDestinations(@Context HttpHeaders headers,
                                                @QueryParam("Reason") List<String> reasons,
                                                @QueryParam("TenantName") String tenantName) {
        String region = regionResolver.resolveRegion(headers);
        List<SuppressedDestination> entries;
        try {
            entries = sesService.listSuppressedDestinations(region, reasons, tenantName);
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode summaries = result.putArray("SuppressedDestinationSummaries");
        for (SuppressedDestination s : entries) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("EmailAddress", s.getEmailAddress());
            item.put("Reason", s.getReason());
            if (s.getLastUpdateTime() != null) {
                item.put("LastUpdateTime", s.getLastUpdateTime().getEpochSecond());
            }
            summaries.add(item);
        }
        return Response.ok(result).build();
    }
}
