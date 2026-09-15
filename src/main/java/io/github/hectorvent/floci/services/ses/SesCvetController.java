package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
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

import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;

/**
 * SES V2 custom verification email template endpoints
 * ({@code /v2/email/custom-verification-email-templates}), split out of {@link SesController}.
 * Get, list and delete call {@link SesCvetService} directly; create and update go through the
 * {@link SesService} facade, which validates the template (including the From-address verified
 * check against the identity domain) before the store write. Sending one of these templates stays
 * with the send endpoints.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesCvetController {

    private static final Logger LOG = Logger.getLogger(SesCvetController.class);

    private final SesCvetService cvetService;
    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesCvetController(SesCvetService cvetService, SesService sesService,
                             RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.cvetService = cvetService;
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/custom-verification-email-templates")
    public Response createCustomVerificationEmailTemplate(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            CustomVerificationEmailTemplate t = parseCvet(request);
            // Tags exist only on the create request; UpdateCustomVerificationEmailTemplate has no
            // Tags member and preserves the stored ones.
            t.setTags(parseTagsArray(request.path("Tags")));
            sesService.createCustomVerificationEmailTemplate(t, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/custom-verification-email-templates")
    public Response listCustomVerificationEmailTemplates(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("CustomVerificationEmailTemplates");
        List<CustomVerificationEmailTemplate> templates =
                cvetService.listCustomVerificationEmailTemplates(region);
        for (CustomVerificationEmailTemplate t : templates) {
            items.add(cvetJson(t, false));
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/custom-verification-email-templates/{templateName}")
    public Response getCustomVerificationEmailTemplate(@Context HttpHeaders headers,
                                                       @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(
                    cvetJson(cvetService.getCustomVerificationEmailTemplate(templateName, region),
                            true)).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/custom-verification-email-templates/{templateName}")
    public Response updateCustomVerificationEmailTemplate(@Context HttpHeaders headers,
                                                          @PathParam("templateName") String templateName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            CustomVerificationEmailTemplate t = parseCvet(objectMapper.readTree(body));
            t.setTemplateName(templateName);
            sesService.updateCustomVerificationEmailTemplate(t, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/custom-verification-email-templates/{templateName}")
    public Response deleteCustomVerificationEmailTemplate(@Context HttpHeaders headers,
                                                          @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            cvetService.deleteCustomVerificationEmailTemplate(templateName, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    private CustomVerificationEmailTemplate parseCvet(JsonNode request) {
        requireJsonObject(request);
        CustomVerificationEmailTemplate t = new CustomVerificationEmailTemplate();
        t.setTemplateName(request.path("TemplateName").asText(null));
        t.setFromEmailAddress(request.path("FromEmailAddress").asText(null));
        t.setTemplateSubject(request.path("TemplateSubject").asText(null));
        t.setTemplateContent(request.path("TemplateContent").asText(null));
        t.setSuccessRedirectionURL(request.path("SuccessRedirectionURL").asText(null));
        t.setFailureRedirectionURL(request.path("FailureRedirectionURL").asText(null));
        return t;
    }

    // List omits TemplateContent (matches AWS); Get includes it.
    private ObjectNode cvetJson(CustomVerificationEmailTemplate t, boolean includeContent) {
        ObjectNode o = objectMapper.createObjectNode();
        o.put("TemplateName", t.getTemplateName());
        o.put("FromEmailAddress", t.getFromEmailAddress());
        o.put("TemplateSubject", t.getTemplateSubject());
        if (includeContent) {
            o.put("TemplateContent", t.getTemplateContent());
        }
        o.put("SuccessRedirectionURL", t.getSuccessRedirectionURL());
        o.put("FailureRedirectionURL", t.getFailureRedirectionURL());
        return o;
    }
}
