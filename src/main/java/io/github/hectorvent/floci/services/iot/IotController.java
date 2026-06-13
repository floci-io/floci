package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.IotTopicRule;
import io.github.hectorvent.floci.services.iot.model.Thing;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotController {

    private final IotService iotService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotController(IotService iotService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.iotService = iotService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/endpoint")
    public Response describeEndpoint(@QueryParam("endpointType") String endpointType) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("endpointAddress", iotService.describeEndpoint(endpointType));
        return Response.ok(response).build();
    }

    @POST
    @Path("/things/{thingName}")
    public Response createThing(@PathParam("thingName") String thingName,
                                @Context HttpHeaders headers,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        Thing thing = iotService.createThing(thingName, parseAttributes(body), region);
        return Response.ok(buildThingResponse(thing)).build();
    }

    @GET
    @Path("/things/{thingName}")
    public Response describeThing(@PathParam("thingName") String thingName,
                                  @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(buildThingResponse(iotService.describeThing(thingName, region))).build();
    }

    @GET
    @Path("/things")
    public Response listThings(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode things = response.putArray("things");
        for (Thing thing : iotService.listThings(region)) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("thingName", thing.getThingName());
            summary.put("thingArn", thing.getThingArn());
            things.add(summary);
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/things/{thingName}")
    public Response updateThing(@PathParam("thingName") String thingName,
                                @Context HttpHeaders headers,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        Thing thing = iotService.updateThing(thingName, parseAttributes(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("thingName", thing.getThingName());
        response.put("thingArn", thing.getThingArn());
        response.put("version", thing.getVersion());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/things/{thingName}")
    public Response deleteThing(@PathParam("thingName") String thingName,
                                @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        iotService.deleteThing(thingName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/untag")
    public Response untagResource(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            iotService.untagResource(request.path("resourceArn").asText(null), parseTagKeys(request.path("tagKeys")));
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/keys-and-certificate")
    @Consumes(MediaType.WILDCARD)
    public Response createKeysAndCertificate(@Context HttpHeaders headers,
                                             @QueryParam("setAsActive") boolean setAsActive) {
        IotCertificate certificate = iotService.createKeysAndCertificate(setAsActive, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("certificateArn", certificate.getCertificateArn());
        response.put("certificateId", certificate.getCertificateId());
        response.put("certificatePem", certificate.getCertificatePem());
        ObjectNode keyPair = response.putObject("keyPair");
        keyPair.put("PublicKey", certificate.getPublicKey());
        keyPair.put("PrivateKey", certificate.getPrivateKey());
        return Response.ok(response).build();
    }

    @GET
    @Path("/certificates/{certificateId}")
    public Response describeCertificate(@Context HttpHeaders headers,
                                        @PathParam("certificateId") String certificateId) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("certificateDescription", buildCertificateDescription(
                iotService.describeCertificate(certificateId, regionResolver.resolveRegion(headers))));
        return Response.ok(response).build();
    }

    @GET
    @Path("/certificates")
    public Response listCertificates(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode certificates = response.putArray("certificates");
        for (IotCertificate certificate : iotService.listCertificates(regionResolver.resolveRegion(headers))) {
            ObjectNode item = certificates.addObject();
            item.put("certificateArn", certificate.getCertificateArn());
            item.put("certificateId", certificate.getCertificateId());
            item.put("status", certificate.getStatus());
            putEpoch(item, "creationDate", certificate.getCreationDate());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/certificates/{certificateId}")
    public Response updateCertificate(@Context HttpHeaders headers,
                                      @PathParam("certificateId") String certificateId,
                                      @QueryParam("newStatus") String newStatus,
                                      String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            String status = newStatus != null ? newStatus
                    : request.hasNonNull("newStatus") ? request.path("newStatus").asText()
                    : request.path("status").asText();
            iotService.updateCertificate(certificateId, status, regionResolver.resolveRegion(headers));
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/policies/{policyName}")
    public Response createPolicy(@Context HttpHeaders headers,
                                 @PathParam("policyName") String policyName,
                                 String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            IotPolicy policy = iotService.createPolicy(policyName, request.path("policyDocument").asText(), regionResolver.resolveRegion(headers));
            return Response.ok(buildPolicyResponse(policy)).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/policies/{policyName}")
    public Response getPolicy(@Context HttpHeaders headers,
                              @PathParam("policyName") String policyName) {
        return Response.ok(buildPolicyResponse(iotService.getPolicy(policyName, regionResolver.resolveRegion(headers)))).build();
    }

    @GET
    @Path("/policies")
    public Response listPolicies(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode policies = response.putArray("policies");
        for (IotPolicy policy : iotService.listPolicies(regionResolver.resolveRegion(headers))) {
            ObjectNode item = policies.addObject();
            item.put("policyName", policy.getPolicyName());
            item.put("policyArn", policy.getPolicyArn());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/target-policies/{policyName}")
    @Consumes(MediaType.WILDCARD)
    public Response attachPolicy(@Context HttpHeaders headers,
                                 @PathParam("policyName") String policyName,
                                 @QueryParam("target") String target,
                                 String body) {
        iotService.attachPolicy(policyName, parseTarget(target, body), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/target-policies/{policyName}")
    @Consumes(MediaType.WILDCARD)
    public Response detachPolicy(@Context HttpHeaders headers,
                                 @PathParam("policyName") String policyName,
                                 @QueryParam("target") String target,
                                 String body) {
        iotService.detachPolicy(policyName, parseTarget(target, body), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/things/{thingName}/principals")
    @Consumes(MediaType.WILDCARD)
    public Response attachThingPrincipal(@Context HttpHeaders headers,
                                         @PathParam("thingName") String thingName,
                                         @HeaderParam("x-amzn-principal") String principalHeader,
                                         @QueryParam("principal") String principal) {
        iotService.attachThingPrincipal(thingName, principalHeader != null ? principalHeader : principal, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/things/{thingName}/principals")
    @Consumes(MediaType.WILDCARD)
    public Response detachThingPrincipal(@Context HttpHeaders headers,
                                         @PathParam("thingName") String thingName,
                                         @HeaderParam("x-amzn-principal") String principalHeader,
                                         @QueryParam("principal") String principal) {
        iotService.detachThingPrincipal(thingName, principalHeader != null ? principalHeader : principal, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/things/{thingName}/principals")
    public Response listThingPrincipals(@Context HttpHeaders headers,
                                        @PathParam("thingName") String thingName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode principals = response.putArray("principals");
        iotService.listThingPrincipals(thingName, regionResolver.resolveRegion(headers)).forEach(principals::add);
        return Response.ok(response).build();
    }

    @PUT
    @Path("/rules/{ruleName}")
    public Response createTopicRule(@Context HttpHeaders headers,
                                    @PathParam("ruleName") String ruleName,
                                    String body) {
        return createTopicRuleResponse(headers, ruleName, body);
    }

    @POST
    @Path("/rules/{ruleName}")
    public Response createTopicRulePost(@Context HttpHeaders headers,
                                        @PathParam("ruleName") String ruleName,
                                        String body) {
        return createTopicRuleResponse(headers, ruleName, body);
    }

    private Response createTopicRuleResponse(HttpHeaders headers, String ruleName, String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode payload = request.has("topicRulePayload") ? request.path("topicRulePayload") : request;
            IotTopicRule rule = iotService.createTopicRule(ruleName, payload, regionResolver.resolveRegion(headers));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("ruleArn", rule.getRuleArn());
            response.put("ruleName", rule.getRuleName());
            return Response.ok(response).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/rules/{ruleName}")
    public Response getTopicRule(@Context HttpHeaders headers,
                                 @PathParam("ruleName") String ruleName) {
        IotTopicRule rule = iotService.getTopicRule(ruleName, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ruleArn", rule.getRuleArn());
        response.set("rule", buildTopicRuleResponse(rule));
        return Response.ok(response).build();
    }

    @GET
    @Path("/rules")
    public Response listTopicRules(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rules = response.putArray("rules");
        for (IotTopicRule rule : iotService.listTopicRules(regionResolver.resolveRegion(headers))) {
            ObjectNode item = rules.addObject();
            item.put("ruleArn", rule.getRuleArn());
            item.put("ruleName", rule.getRuleName());
            item.put("topicPattern", topicPattern(rule.getSql()));
            item.put("ruleDisabled", rule.isRuleDisabled());
            putEpoch(item, "createdAt", rule.getCreatedAt());
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/rules/{ruleName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTopicRule(@Context HttpHeaders headers,
                                    @PathParam("ruleName") String ruleName) {
        iotService.deleteTopicRule(ruleName, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/rules/{ruleName}/enable")
    @Consumes(MediaType.WILDCARD)
    public Response enableTopicRule(@Context HttpHeaders headers,
                                    @PathParam("ruleName") String ruleName) {
        iotService.setTopicRuleEnabled(ruleName, true, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/rules/{ruleName}/disable")
    @Consumes(MediaType.WILDCARD)
    public Response disableTopicRule(@Context HttpHeaders headers,
                                     @PathParam("ruleName") String ruleName) {
        iotService.setTopicRuleEnabled(ruleName, false, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Map<String, String> parseAttributes(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode attributesNode = request.get("attributePayload");
            if (attributesNode == null || !attributesNode.has("attributes")) {
                attributesNode = request.get("AttributePayload");
            }
            if (attributesNode != null && attributesNode.has("attributes")) {
                attributesNode = attributesNode.get("attributes");
            } else if (attributesNode != null && attributesNode.has("Attributes")) {
                attributesNode = attributesNode.get("Attributes");
            }
            Map<String, String> attributes = new HashMap<>();
            if (attributesNode != null && attributesNode.isObject()) {
                attributesNode.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), entry.getValue().asText()));
            }
            return attributes;
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private List<String> parseTagKeys(JsonNode tagKeysNode) {
        List<String> tagKeys = new ArrayList<>();
        if (tagKeysNode != null && tagKeysNode.isArray()) {
            tagKeysNode.forEach(tagKey -> tagKeys.add(tagKey.asText()));
        }
        return tagKeys;
    }

    private String parseTarget(String queryTarget, String body) {
        if (queryTarget != null && !queryTarget.isBlank()) {
            return queryTarget;
        }
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            return request.path("target").asText(null);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private ObjectNode buildThingResponse(Thing thing) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("thingName", thing.getThingName());
        response.put("thingArn", thing.getThingArn());
        response.put("thingId", thing.getThingId());
        response.set("attributes", objectMapper.valueToTree(thing.getAttributes()));
        response.put("version", thing.getVersion());
        putEpoch(response, "creationDate", thing.getCreationDate());
        putEpoch(response, "lastModifiedDate", thing.getLastModifiedDate());
        return response;
    }

    private ObjectNode buildCertificateDescription(IotCertificate certificate) {
        ObjectNode description = objectMapper.createObjectNode();
        description.put("certificateArn", certificate.getCertificateArn());
        description.put("certificateId", certificate.getCertificateId());
        description.put("certificatePem", certificate.getCertificatePem());
        description.put("status", certificate.getStatus());
        putEpoch(description, "creationDate", certificate.getCreationDate());
        return description;
    }

    private ObjectNode buildPolicyResponse(IotPolicy policy) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyName", policy.getPolicyName());
        response.put("policyArn", policy.getPolicyArn());
        response.put("policyDocument", policy.getPolicyDocument());
        response.put("defaultVersionId", policy.getDefaultVersionId());
        return response;
    }

    private ObjectNode buildTopicRuleResponse(IotTopicRule rule) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ruleName", rule.getRuleName());
        response.put("sql", rule.getSql());
        response.put("description", rule.getDescription());
        response.put("ruleDisabled", rule.isRuleDisabled());
        putEpoch(response, "createdAt", rule.getCreatedAt());
        try {
            response.set("actions", objectMapper.readTree(rule.getActionsJson()));
        } catch (JsonProcessingException e) {
            response.putArray("actions");
        }
        return response;
    }

    private String topicPattern(String sql) {
        if (sql == null) {
            return null;
        }
        int from = sql.toUpperCase().indexOf(" FROM ");
        if (from < 0) {
            return null;
        }
        String tail = sql.substring(from + " FROM ".length()).trim();
        if (tail.length() >= 2 && (tail.charAt(0) == '\'' || tail.charAt(0) == '"')) {
            char quote = tail.charAt(0);
            int end = tail.indexOf(quote, 1);
            if (end > 1) {
                return tail.substring(1, end);
            }
        }
        return null;
    }

    private void putEpoch(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, instant.toEpochMilli() / 1000.0);
        }
    }
}
