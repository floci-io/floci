package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
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

import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;

/**
 * SES V2 email-template endpoints ({@code /v2/email/templates}). Talks to
 * {@link SesTemplateService} directly; only
 * {@code DeleteEmailTemplate} goes through the {@link SesService} facade, which wraps the delete in
 * the tenant-association guard.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesTemplateController {

    private static final Logger LOG = Logger.getLogger(SesTemplateController.class);

    private final SesTemplateService templateService;
    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesTemplateController(SesTemplateService templateService, SesService sesService,
                                 RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.templateService = templateService;
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/templates")
    public Response createEmailTemplate(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String templateName = request.path("TemplateName").asText(null);
            if (templateName == null || templateName.isBlank()) {
                throw new AwsException("BadRequestException", "TemplateName is required.", 400);
            }
            EmailTemplate template = parseTemplateContent(templateName, request.path("TemplateContent"));
            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                template.setTags(parsedTags);
            }
            templateService.createTemplate(template, region);
            LOG.infov("SES V2 CreateEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/templates")
    public Response listEmailTemplates(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<EmailTemplate> templates = templateService.listTemplates(region);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("TemplatesMetadata");
        for (EmailTemplate t : templates) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("TemplateName", t.getTemplateName());
            if (t.getCreatedTimestamp() != null) {
                item.put("CreatedTimestamp", t.getCreatedTimestamp().getEpochSecond());
            }
            items.add(item);
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/templates/{templateName}")
    public Response getEmailTemplate(@Context HttpHeaders headers,
                                      @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            EmailTemplate template = templateService.getTemplate(templateName, region);
            return Response.ok(buildTemplateResponse(template)).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/templates/{templateName}")
    public Response updateEmailTemplate(@Context HttpHeaders headers,
                                         @PathParam("templateName") String templateName,
                                         String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            EmailTemplate template = parseTemplateContent(templateName, request.path("TemplateContent"));
            templateService.updateTemplate(template, region);
            LOG.infov("SES V2 UpdateEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/templates/{templateName}")
    public Response deleteEmailTemplate(@Context HttpHeaders headers,
                                         @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteTemplate(templateName, region);
            LOG.infov("SES V2 DeleteEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @POST
    @Path("/templates/{templateName}/render")
    public Response testRenderEmailTemplate(@Context HttpHeaders headers,
                                             @PathParam("templateName") String templateName,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode templateDataNode = request.path("TemplateData");
            if (!templateDataNode.isMissingNode() && !templateDataNode.isNull()
                    && !templateDataNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "TemplateData must be a JSON-encoded string.", 400);
            }
            String templateDataRaw = templateDataNode.asText("");
            String rendered = templateService.renderTestTemplate(templateName, templateDataRaw,
                    region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("RenderedTemplate", rendered);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private EmailTemplate parseTemplateContent(String templateName, JsonNode content) {
        String subject = content.path("Subject").asText(null);
        String text = content.path("Text").asText(null);
        String html = content.path("Html").asText(null);
        return new EmailTemplate(templateName, subject, text, html);
    }

    private ObjectNode buildTemplateResponse(EmailTemplate template) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("TemplateName", template.getTemplateName());
        ObjectNode content = result.putObject("TemplateContent");
        if (template.getSubject() != null) {
            content.put("Subject", template.getSubject());
        }
        if (template.getTextPart() != null) {
            content.put("Text", template.getTextPart());
        }
        if (template.getHtmlPart() != null) {
            content.put("Html", template.getHtmlPart());
        }
        ArrayNode tags = result.putArray("Tags");
        for (Tag t : template.getTags()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", t.key());
            tagNode.put("Value", t.value());
            tags.add(tagNode);
        }
        return result;
    }
}
