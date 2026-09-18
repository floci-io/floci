package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.DashboardOptions;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.GuardianOptions;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import io.github.hectorvent.floci.services.ses.model.VdmOptions;
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
import static io.github.hectorvent.floci.services.ses.SesV2Json.parseSendingEnabled;
import static io.github.hectorvent.floci.services.ses.SesV2Json.parseSuppressedReasons;
import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readOptionBody;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;

/**
 * SES V2 configuration-set endpoints ({@code /v2/email/configuration-sets}), including the event
 * destinations. Most operations call
 * {@link SesConfigurationSetService} directly; create, the tracking and delivery option setters
 * and delete go through the {@link SesService} facade, which supplies the verified-domain and
 * dedicated-pool probes those validations need and guards the delete against tenant associations.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesConfigurationSetController {

    private static final Logger LOG = Logger.getLogger(SesConfigurationSetController.class);

    private final SesConfigurationSetService configSetService;
    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesConfigurationSetController(SesConfigurationSetService configSetService,
                                         SesService sesService, RegionResolver regionResolver,
                                         ObjectMapper objectMapper) {
        this.configSetService = configSetService;
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/configuration-sets")
    public Response createConfigurationSet(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String name = request.path("ConfigurationSetName").asText(null);
            if (name == null || name.isBlank()) {
                throw new AwsException("BadRequestException", "ConfigurationSetName is required.", 400);
            }
            ConfigurationSet cs = new ConfigurationSet(name);
            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                cs.setTags(parsedTags);
            }
            JsonNode suppressionNode = request.path("SuppressionOptions");
            if (!suppressionNode.isMissingNode() && !suppressionNode.isNull()) {
                if (!suppressionNode.isObject()) {
                    throw new AwsException("SerializationException", "Expected null", 400);
                }
                JsonNode reasonsNode = suppressionNode.path("SuppressedReasons");
                if (reasonsNode.isMissingNode() || reasonsNode.isNull()) {
                    throw new AwsException("InternalFailure",
                            "An internal failure has occurred.", 500);
                }
                SuppressionOptions options = new SuppressionOptions();
                options.setSuppressedReasons(parseSuppressedReasons(reasonsNode));
                cs.setSuppressionOptions(options);
            }
            JsonNode sendingNode = request.path("SendingOptions");
            if (!sendingNode.isMissingNode() && !sendingNode.isNull()) {
                if (!sendingNode.isObject()) {
                    throw new AwsException("SerializationException", "Expected null", 400);
                }
                cs.setSendingEnabled(parseSendingEnabled(sendingNode.path("SendingEnabled")));
            }
            JsonNode reputationNode = request.path("ReputationOptions");
            if (!reputationNode.isMissingNode() && !reputationNode.isNull()) {
                requireOptionObject(reputationNode);
                Boolean rme = parseReputationMetricsEnabled(reputationNode.path("ReputationMetricsEnabled"));
                if (rme != null) {
                    cs.setReputationMetricsEnabled(rme);
                }
            }
            JsonNode trackingNode = request.path("TrackingOptions");
            if (!trackingNode.isMissingNode() && !trackingNode.isNull()) {
                cs.setTrackingOptions(parseTrackingOptions(trackingNode));
            }
            JsonNode deliveryNode = request.path("DeliveryOptions");
            if (!deliveryNode.isMissingNode() && !deliveryNode.isNull()) {
                cs.setDeliveryOptions(parseDeliveryOptions(deliveryNode));
            }
            JsonNode archivingNode = request.path("ArchivingOptions");
            if (!archivingNode.isMissingNode() && !archivingNode.isNull()) {
                cs.setArchivingOptions(parseArchivingOptions(archivingNode));
            }
            JsonNode vdmNode = request.path("VdmOptions");
            if (!vdmNode.isMissingNode() && !vdmNode.isNull()) {
                cs.setVdmOptions(parseVdmOptions(vdmNode));
            }
            sesService.createConfigurationSet(cs, region);
            LOG.infov("SES V2 CreateConfigurationSet: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/configuration-sets")
    public Response listConfigurationSets(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<ConfigurationSet> all = configSetService.list(region);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode arr = result.putArray("ConfigurationSets");
        for (ConfigurationSet cs : all) {
            arr.add(cs.getName());
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/configuration-sets/{configurationSetName}")
    public Response getConfigurationSet(@Context HttpHeaders headers,
                                         @PathParam("configurationSetName") String name) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ConfigurationSet cs = configSetService.get(name, region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("ConfigurationSetName", cs.getName());
            ArrayNode tags = result.putArray("Tags");
            for (Tag t : cs.getTags()) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
                tags.add(tagNode);
            }
            if (cs.getSuppressionOptions() != null) {
                ObjectNode suppressionNode = result.putObject("SuppressionOptions");
                ArrayNode reasons = suppressionNode.putArray("SuppressedReasons");
                for (String r : cs.getSuppressionOptions().getSuppressedReasons()) {
                    reasons.add(r);
                }
            }
            ObjectNode sendingNode = result.putObject("SendingOptions");
            sendingNode.put("SendingEnabled", cs.isSendingEnabledEffective());
            // AWS always returns ReputationOptions (true by default), like SendingOptions.
            ObjectNode reputationNode = result.putObject("ReputationOptions");
            reputationNode.put("ReputationMetricsEnabled", cs.isReputationMetricsEnabledEffective());
            // The option models carry @JsonProperty/@JsonInclude(NON_NULL), so let
            // Jackson shape the response and omit unset members.
            if (cs.getTrackingOptions() != null) {
                result.set("TrackingOptions", objectMapper.valueToTree(cs.getTrackingOptions()));
            }
            if (cs.getDeliveryOptions() != null) {
                result.set("DeliveryOptions", objectMapper.valueToTree(cs.getDeliveryOptions()));
            }
            if (cs.getArchivingOptions() != null) {
                result.set("ArchivingOptions", objectMapper.valueToTree(cs.getArchivingOptions()));
            }
            if (cs.getVdmOptions() != null) {
                result.set("VdmOptions", objectMapper.valueToTree(cs.getVdmOptions()));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/suppression-options")
    public Response putConfigurationSetSuppressionOptions(@Context HttpHeaders headers,
                                                          @PathParam("configurationSetName") String name,
                                                          String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            List<String> reasons = parseSuppressedReasons(request.path("SuppressedReasons"));
            configSetService.putSuppressionOptions(name, reasons, region);
            LOG.infov("SES V2 PutConfigurationSetSuppressionOptions: {0} on {1}", reasons, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/sending")
    public Response putConfigurationSetSendingOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            // Reuse the AWS-aligned SendingEnabled deserialization shared with CreateConfigurationSet:
            // absent -> false, string -> true, null/number -> SerializationException. An empty body
            // / {} therefore disables sending (200). Verified against real AWS.
            boolean enabled = parseSendingEnabled(
                    readOptionBody(objectMapper, body).path("SendingEnabled"));
            configSetService.setSendingEnabled(name, enabled, region);
            LOG.infov("SES V2 PutConfigurationSetSendingOptions: {0} on {1}", enabled, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/reputation-options")
    public Response putConfigurationSetReputationOptions(@Context HttpHeaders headers,
                                                         @PathParam("configurationSetName") String name,
                                                         String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            Boolean enabled = parseReputationMetricsEnabled(request.path("ReputationMetricsEnabled"));
            boolean effectiveEnabled = enabled != null && enabled;
            configSetService.setReputationMetricsEnabled(name, effectiveEnabled, region);
            LOG.infov("SES V2 PutConfigurationSetReputationOptions: {0} on {1}", effectiveEnabled, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/tracking-options")
    public Response putConfigurationSetTrackingOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            sesService.setConfigurationSetTrackingOptions(name, parseTrackingOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetTrackingOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/delivery-options")
    public Response putConfigurationSetDeliveryOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            sesService.setConfigurationSetDeliveryOptions(name, parseDeliveryOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetDeliveryOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/archiving-options")
    public Response putConfigurationSetArchivingOptions(@Context HttpHeaders headers,
                                                        @PathParam("configurationSetName") String name,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            configSetService.setArchivingOptions(name, parseArchivingOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetArchivingOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/vdm-options")
    public Response putConfigurationSetVdmOptions(@Context HttpHeaders headers,
                                                  @PathParam("configurationSetName") String name,
                                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            JsonNode vdmNode = request.path("VdmOptions");
            VdmOptions options = (vdmNode.isMissingNode() || vdmNode.isNull())
                    ? null : parseVdmOptions(vdmNode);
            configSetService.setVdmOptions(name, options, region);
            LOG.infov("SES V2 PutConfigurationSetVdmOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    /** Reject a non-object option block, mirroring the AWS deserialization layer. */
    private static void requireOptionObject(JsonNode node) {
        if (!node.isObject()) {
            throw new AwsException("SerializationException", "Expected null", 400);
        }
    }

    private static Boolean parseReputationMetricsEnabled(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new AwsException("BadRequestException",
                    "ReputationMetricsEnabled must be a boolean.", 400);
        }
        return node.booleanValue();
    }

    private static TrackingOptions parseTrackingOptions(JsonNode node) {
        requireOptionObject(node);
        TrackingOptions t = new TrackingOptions();
        t.setCustomRedirectDomain(parseOptionString(node.path("CustomRedirectDomain"), "CustomRedirectDomain"));
        t.setHttpsPolicy(parseOptionString(node.path("HttpsPolicy"), "HttpsPolicy"));
        // An all-null block (e.g. an empty PUT body) clears the options rather
        // than persisting an empty object that GetConfigurationSet would echo.
        if (t.getCustomRedirectDomain() == null && t.getHttpsPolicy() == null) {
            return null;
        }
        return t;
    }

    private static DeliveryOptions parseDeliveryOptions(JsonNode node) {
        requireOptionObject(node);
        DeliveryOptions d = new DeliveryOptions();
        d.setTlsPolicy(parseOptionString(node.path("TlsPolicy"), "TlsPolicy"));
        d.setSendingPoolName(parseOptionString(node.path("SendingPoolName"), "SendingPoolName"));
        JsonNode max = node.path("MaxDeliverySeconds");
        if (!max.isMissingNode() && !max.isNull()) {
            if (!max.isNumber()) {
                throw new AwsException("BadRequestException",
                        "MaxDeliverySeconds must be a number.", 400);
            }
            if (!max.isIntegralNumber()) {
                throw new AwsException("BadRequestException",
                        "MaxDeliverySeconds must be an integer.", 400);
            }
            d.setMaxDeliverySeconds(max.asLong());
        }
        if (d.getTlsPolicy() == null && d.getSendingPoolName() == null && d.getMaxDeliverySeconds() == null) {
            return null;
        }
        return d;
    }

    private static ArchivingOptions parseArchivingOptions(JsonNode node) {
        requireOptionObject(node);
        ArchivingOptions a = new ArchivingOptions();
        a.setArchiveArn(parseOptionString(node.path("ArchiveArn"), "ArchiveArn"));
        if (a.getArchiveArn() == null) {
            return null;
        }
        return a;
    }

    private static VdmOptions parseVdmOptions(JsonNode node) {
        requireOptionObject(node);
        VdmOptions v = new VdmOptions();
        JsonNode dashboard = node.path("DashboardOptions");
        if (!dashboard.isMissingNode() && !dashboard.isNull()) {
            requireOptionObject(dashboard);
            String engagementMetrics = parseOptionString(dashboard.path("EngagementMetrics"), "EngagementMetrics");
            if (engagementMetrics != null) {
                DashboardOptions d = new DashboardOptions();
                d.setEngagementMetrics(engagementMetrics);
                v.setDashboardOptions(d);
            }
        }
        JsonNode guardian = node.path("GuardianOptions");
        if (!guardian.isMissingNode() && !guardian.isNull()) {
            requireOptionObject(guardian);
            String optimized = parseOptionString(guardian.path("OptimizedSharedDelivery"), "OptimizedSharedDelivery");
            if (optimized != null) {
                GuardianOptions g = new GuardianOptions();
                g.setOptimizedSharedDelivery(optimized);
                v.setGuardianOptions(g);
            }
        }
        // An all-null block (e.g. an empty PUT body) clears the options rather
        // than persisting an empty object that GetConfigurationSet would echo.
        if (v.getDashboardOptions() == null && v.getGuardianOptions() == null) {
            return null;
        }
        return v;
    }

    @DELETE
    @Path("/configuration-sets/{configurationSetName}")
    public Response deleteConfigurationSet(@Context HttpHeaders headers,
                                            @PathParam("configurationSetName") String name) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteConfigurationSet(name, region);
            LOG.infov("SES V2 DeleteConfigurationSet: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @POST
    @Path("/configuration-sets/{configurationSetName}/event-destinations")
    public Response createConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String edName = request.path("EventDestinationName").asText(null);
            if (edName == null || edName.isBlank()) {
                throw new AwsException("BadRequestException", "EventDestinationName is required.", 400);
            }
            JsonNode edNode = request.path("EventDestination");
            if (!edNode.isObject()) {
                throw new AwsException("BadRequestException", "EventDestination is required.", 400);
            }
            EventDestination dest = objectMapper.treeToValue(edNode, EventDestination.class);
            configSetService.createEventDestination(configurationSetName, edName, dest, region);
            LOG.infov("SES V2 CreateConfigurationSetEventDestination: {0} on {1}", edName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/configuration-sets/{configurationSetName}/event-destinations")
    public Response getConfigurationSetEventDestinations(@Context HttpHeaders headers,
                                                         @PathParam("configurationSetName") String configurationSetName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            List<EventDestination> dests =
                    configSetService.getEventDestinations(configurationSetName, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("EventDestinations");
            for (EventDestination ed : dests) {
                arr.add(objectMapper.valueToTree(ed));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/event-destinations/{eventDestinationName}")
    public Response updateConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           @PathParam("eventDestinationName") String eventDestinationName,
                                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode edNode = request.path("EventDestination");
            if (!edNode.isObject()) {
                throw new AwsException("BadRequestException", "EventDestination is required.", 400);
            }
            EventDestination dest = objectMapper.treeToValue(edNode, EventDestination.class);
            configSetService.updateEventDestination(configurationSetName, eventDestinationName,
                    dest, region);
            LOG.infov("SES V2 UpdateConfigurationSetEventDestination: {0} on {1}",
                    eventDestinationName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/configuration-sets/{configurationSetName}/event-destinations/{eventDestinationName}")
    public Response deleteConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           @PathParam("eventDestinationName") String eventDestinationName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            configSetService.deleteEventDestination(configurationSetName, eventDestinationName,
                    region);
            LOG.infov("SES V2 DeleteConfigurationSetEventDestination: {0} on {1}",
                    eventDestinationName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }
}
