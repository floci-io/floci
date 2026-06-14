package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.github.hectorvent.floci.services.iot.model.IotJob;
import io.github.hectorvent.floci.services.iot.model.IotJobExecution;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.IotThingGroup;
import io.github.hectorvent.floci.services.iot.model.IotThingType;
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
        Thing thing = iotService.createThing(thingName, parseAttributes(body), parseThingTypeName(body), region);
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
    public Response listThings(@Context HttpHeaders headers,
                               @QueryParam("maxResults") Integer maxResults,
                               @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        IotService.Page<Thing> page = iotService.listThings(region, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode things = response.putArray("things");
        for (Thing thing : page.items()) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("thingName", thing.getThingName());
            summary.put("thingArn", thing.getThingArn());
            things.add(summary);
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/things/{thingName}")
    public Response updateThing(@PathParam("thingName") String thingName,
                                @Context HttpHeaders headers,
                                String body) {
        String region = regionResolver.resolveRegion(headers);
        Thing thing = iotService.updateThing(thingName, parseAttributes(body), parseExpectedVersion(body), region);
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

    @POST
    @Path("/certificates")
    public Response createCertificateFromCsr(@Context HttpHeaders headers,
                                             @QueryParam("setAsActive") boolean setAsActive,
                                             String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            IotCertificate certificate = iotService.createCertificateFromCsr(
                    request.path("certificateSigningRequest").asText(null), setAsActive, regionResolver.resolveRegion(headers));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("certificateArn", certificate.getCertificateArn());
            response.put("certificateId", certificate.getCertificateId());
            response.put("certificatePem", certificate.getCertificatePem());
            return Response.ok(response).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
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

    @DELETE
    @Path("/certificates/{certificateId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCertificate(@Context HttpHeaders headers,
                                      @PathParam("certificateId") String certificateId) {
        iotService.deleteCertificate(certificateId, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
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

    @DELETE
    @Path("/policies/{policyName}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePolicy(@Context HttpHeaders headers,
                                 @PathParam("policyName") String policyName) {
        iotService.deletePolicy(policyName, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/policies/{policyName}/version")
    public Response createPolicyVersion(@Context HttpHeaders headers,
                                        @PathParam("policyName") String policyName,
                                        @QueryParam("setAsDefault") boolean setAsDefault,
                                        String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            IotPolicy policy = iotService.getPolicy(policyName, regionResolver.resolveRegion(headers));
            IotPolicy.PolicyVersion version = iotService.createPolicyVersion(policyName,
                    request.path("policyDocument").asText(), setAsDefault, regionResolver.resolveRegion(headers));
            return Response.ok(buildPolicyVersionResponse(policy, version, setAsDefault)).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/policies/{policyName}/version")
    public Response listPolicyVersions(@Context HttpHeaders headers,
                                       @PathParam("policyName") String policyName) {
        String region = regionResolver.resolveRegion(headers);
        IotPolicy policy = iotService.getPolicy(policyName, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode versions = response.putArray("policyVersions");
        for (IotPolicy.PolicyVersion version : iotService.listPolicyVersions(policyName, region)) {
            versions.add(buildPolicyVersionSummary(policy, version));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/policies/{policyName}/version/{policyVersionId}")
    public Response getPolicyVersion(@Context HttpHeaders headers,
                                     @PathParam("policyName") String policyName,
                                     @PathParam("policyVersionId") String policyVersionId) {
        String region = regionResolver.resolveRegion(headers);
        IotPolicy policy = iotService.getPolicy(policyName, region);
        IotPolicy.PolicyVersion version = iotService.getPolicyVersion(policyName, policyVersionId, region);
        return Response.ok(buildPolicyVersionResponse(policy, version, version.getVersionId().equals(policy.getDefaultVersionId()))).build();
    }

    @PATCH
    @Path("/policies/{policyName}/version/{policyVersionId}")
    @Consumes(MediaType.WILDCARD)
    public Response setDefaultPolicyVersion(@Context HttpHeaders headers,
                                            @PathParam("policyName") String policyName,
                                            @PathParam("policyVersionId") String policyVersionId) {
        iotService.setDefaultPolicyVersion(policyName, policyVersionId, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/policies/{policyName}/version/{policyVersionId}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePolicyVersion(@Context HttpHeaders headers,
                                        @PathParam("policyName") String policyName,
                                        @PathParam("policyVersionId") String policyVersionId) {
        iotService.deletePolicyVersion(policyName, policyVersionId, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
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

    @GET
    @Path("/attached-policies/{target: .+}")
    public Response listAttachedPolicies(@Context HttpHeaders headers,
                                         @PathParam("target") String target) {
        return listAttachedPoliciesResponse(headers, target);
    }

    @POST
    @Path("/attached-policies/{target: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response listAttachedPoliciesPost(@Context HttpHeaders headers,
                                             @PathParam("target") String target) {
        return listAttachedPoliciesResponse(headers, target);
    }

    private Response listAttachedPoliciesResponse(HttpHeaders headers, String target) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode policies = response.putArray("policies");
        for (IotPolicy policy : iotService.listAttachedPolicies(target, regionResolver.resolveRegion(headers))) {
            ObjectNode item = policies.addObject();
            item.put("policyName", policy.getPolicyName());
            item.put("policyArn", policy.getPolicyArn());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/policy-targets/{policyName}")
    public Response listTargetsForPolicy(@Context HttpHeaders headers,
                                         @PathParam("policyName") String policyName) {
        return listTargetsForPolicyResponse(headers, policyName);
    }

    @POST
    @Path("/policy-targets/{policyName}")
    @Consumes(MediaType.WILDCARD)
    public Response listTargetsForPolicyPost(@Context HttpHeaders headers,
                                             @PathParam("policyName") String policyName) {
        return listTargetsForPolicyResponse(headers, policyName);
    }

    private Response listTargetsForPolicyResponse(HttpHeaders headers, String policyName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode targets = response.putArray("targets");
        iotService.listTargetsForPolicy(policyName, regionResolver.resolveRegion(headers)).forEach(targets::add);
        return Response.ok(response).build();
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

    @GET
    @Path("/principals/things")
    public Response listPrincipalThings(@Context HttpHeaders headers,
                                        @HeaderParam("x-amzn-principal") String principalHeader,
                                        @QueryParam("principal") String principal) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode things = response.putArray("things");
        iotService.listPrincipalThings(principalHeader != null ? principalHeader : principal, regionResolver.resolveRegion(headers)).forEach(things::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/thing-types/{thingTypeName}")
    public Response createThingType(@Context HttpHeaders headers,
                                    @PathParam("thingTypeName") String thingTypeName,
                                    String body) {
        IotThingType type = iotService.createThingType(thingTypeName, parseProperties(body, "thingTypeProperties"), regionResolver.resolveRegion(headers));
        return Response.ok(buildThingTypeCreateResponse(type)).build();
    }

    @GET
    @Path("/thing-types/{thingTypeName}")
    public Response describeThingType(@Context HttpHeaders headers,
                                      @PathParam("thingTypeName") String thingTypeName) {
        return Response.ok(buildThingTypeDescription(iotService.describeThingType(thingTypeName, regionResolver.resolveRegion(headers)))).build();
    }

    @GET
    @Path("/thing-types")
    public Response listThingTypes(@Context HttpHeaders headers,
                                   @QueryParam("maxResults") Integer maxResults,
                                   @QueryParam("nextToken") String nextToken) {
        IotService.Page<IotThingType> page = iotService.listThingTypes(regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode types = response.putArray("thingTypes");
        page.items().forEach(type -> types.add(buildThingTypeSummary(type)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/thing-types/{thingTypeName}")
    public Response updateThingType(@Context HttpHeaders headers,
                                    @PathParam("thingTypeName") String thingTypeName,
                                    String body) {
        iotService.updateThingType(thingTypeName, parseProperties(body, "thingTypeProperties"), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/thing-types/{thingTypeName}/deprecate")
    @Consumes(MediaType.WILDCARD)
    public Response deprecateThingType(@Context HttpHeaders headers,
                                       @PathParam("thingTypeName") String thingTypeName) {
        iotService.deprecateThingType(thingTypeName, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/thing-types/{thingTypeName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteThingType(@Context HttpHeaders headers,
                                    @PathParam("thingTypeName") String thingTypeName) {
        iotService.deleteThingType(thingTypeName, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/thing-groups/{thingGroupName}")
    public Response createThingGroup(@Context HttpHeaders headers,
                                     @PathParam("thingGroupName") String thingGroupName,
                                     String body) {
        IotThingGroup group = iotService.createThingGroup(thingGroupName, parseProperties(body, "thingGroupProperties"), regionResolver.resolveRegion(headers));
        return Response.ok(buildThingGroupCreateResponse(group)).build();
    }

    @GET
    @Path("/thing-groups/{thingGroupName}")
    public Response describeThingGroup(@Context HttpHeaders headers,
                                       @PathParam("thingGroupName") String thingGroupName) {
        return Response.ok(buildThingGroupDescription(iotService.describeThingGroup(thingGroupName, regionResolver.resolveRegion(headers)))).build();
    }

    @GET
    @Path("/thing-groups")
    public Response listThingGroups(@Context HttpHeaders headers,
                                    @QueryParam("maxResults") Integer maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        IotService.Page<IotThingGroup> page = iotService.listThingGroups(regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("thingGroups");
        page.items().forEach(group -> groups.add(buildThingGroupSummary(group)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/thing-groups/{thingGroupName}")
    public Response updateThingGroup(@Context HttpHeaders headers,
                                     @PathParam("thingGroupName") String thingGroupName,
                                     String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            Long expectedVersion = request.hasNonNull("expectedVersion") ? request.path("expectedVersion").asLong() : null;
            IotThingGroup group = iotService.updateThingGroup(thingGroupName, request.path("thingGroupProperties"), expectedVersion,
                    regionResolver.resolveRegion(headers));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("version", group.getVersion());
            return Response.ok(response).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/thing-groups/{thingGroupName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteThingGroup(@Context HttpHeaders headers,
                                     @PathParam("thingGroupName") String thingGroupName) {
        iotService.deleteThingGroup(thingGroupName, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/thing-groups/addThingToThingGroup")
    public Response addThingToThingGroup(@Context HttpHeaders headers,
                                         String body) {
        GroupMembershipRequest request = parseGroupMembershipRequest(body);
        iotService.addThingToThingGroup(request.thingGroupName(), request.thingName(), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/thing-groups/removeThingFromThingGroup")
    public Response removeThingFromThingGroup(@Context HttpHeaders headers,
                                              String body) {
        GroupMembershipRequest request = parseGroupMembershipRequest(body);
        iotService.removeThingFromThingGroup(request.thingGroupName(), request.thingName(), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/thing-groups/{thingGroupName}/things")
    public Response listThingsInThingGroup(@Context HttpHeaders headers,
                                           @PathParam("thingGroupName") String thingGroupName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode things = response.putArray("things");
        iotService.listThingsInThingGroup(thingGroupName, regionResolver.resolveRegion(headers)).forEach(things::add);
        return Response.ok(response).build();
    }

    @GET
    @Path("/things/{thingName}/thing-groups")
    public Response listThingGroupsForThing(@Context HttpHeaders headers,
                                            @PathParam("thingName") String thingName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("thingGroups");
        iotService.listThingGroupsForThing(thingName, regionResolver.resolveRegion(headers)).forEach(group -> groups.add(buildThingGroupSummary(group)));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/jobs/{jobId}")
    public Response createJob(@Context HttpHeaders headers,
                              @PathParam("jobId") String jobId,
                              String body) {
        try {
            IotJob job = iotService.createJob(jobId, objectMapper.readTree(body == null || body.isBlank() ? "{}" : body),
                    regionResolver.resolveRegion(headers));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jobArn", job.getJobArn());
            response.put("jobId", job.getJobId());
            response.put("description", job.getDescription());
            return Response.ok(response).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/jobs/{jobId}")
    public Response describeJob(@Context HttpHeaders headers,
                                @PathParam("jobId") String jobId) {
        IotJob job = iotService.describeJob(jobId, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        if (job.getDocumentSource() != null) {
            response.put("documentSource", job.getDocumentSource());
        }
        response.set("job", buildJobResponse(job));
        return Response.ok(response).build();
    }

    @GET
    @Path("/jobs")
    public Response listJobs(@Context HttpHeaders headers,
                             @QueryParam("maxResults") Integer maxResults,
                             @QueryParam("nextToken") String nextToken) {
        IotService.Page<IotJob> page = iotService.listJobs(regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode jobs = response.putArray("jobs");
        page.items().forEach(job -> jobs.add(buildJobSummary(job)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/things/{thingName}/jobs")
    public Response listJobExecutionsForThing(@Context HttpHeaders headers,
                                              @PathParam("thingName") String thingName,
                                              @QueryParam("maxResults") Integer maxResults,
                                              @QueryParam("nextToken") String nextToken) {
        IotService.Page<IotJobExecution> page = iotService.listJobExecutionsForThing(thingName,
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode executionSummaries = response.putArray("executionSummaries");
        ArrayNode queuedJobs = response.putArray("queuedJobs");
        ArrayNode inProgressJobs = response.putArray("inProgressJobs");
        page.items().forEach(execution -> {
            ObjectNode controlSummary = executionSummaries.addObject();
            controlSummary.put("jobId", execution.getJobId());
            controlSummary.set("jobExecutionSummary", buildJobExecutionSummary(execution));
            if ("IN_PROGRESS".equals(execution.getStatus())) {
                inProgressJobs.add(buildJobsDataExecutionSummary(execution));
            } else if ("QUEUED".equals(execution.getStatus())) {
                queuedJobs.add(buildJobsDataExecutionSummary(execution));
            }
        });
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/things/{thingName}/jobs/{jobId}")
    public Response describeJobExecution(@Context HttpHeaders headers,
                                         @PathParam("thingName") String thingName,
                                         @PathParam("jobId") String jobId) {
        String region = regionResolver.resolveRegion(headers);
        IotJobExecution execution = iotService.describeJobExecution(thingName, jobId, region);
        return Response.ok(jobExecutionResponse(execution, iotService.describeJob(jobId, region), true)).build();
    }

    @PUT
    @Path("/things/{thingName}/jobs/{jobId}")
    public Response startNextPendingJobExecution(@Context HttpHeaders headers,
                                                 @PathParam("thingName") String thingName,
                                                 @PathParam("jobId") String jobId,
                                                 String body) {
        if (!"$next".equals(jobId)) {
            throw new AwsException("InvalidRequestException", "Unsupported job execution PUT path: " + jobId, 400);
        }
        String region = regionResolver.resolveRegion(headers);
        IotJobExecution execution = iotService.startNextPendingJobExecution(thingName, parseStatusDetails(body), region);
        return Response.ok(jobExecutionResponse(execution, iotService.describeJob(execution.getJobId(), region), true)).build();
    }

    @POST
    @Path("/things/{thingName}/jobs/{jobId}")
    public Response updateJobExecution(@Context HttpHeaders headers,
                                       @PathParam("thingName") String thingName,
                                       @PathParam("jobId") String jobId,
                                       String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            Long expectedVersion = request.hasNonNull("expectedVersion") ? request.path("expectedVersion").asLong() : null;
            String region = regionResolver.resolveRegion(headers);
            IotJobExecution execution = iotService.updateJobExecution(thingName, jobId, request.path("status").asText(null),
                    parseStatusDetails(request.path("statusDetails")), expectedVersion, region);
            ObjectNode response = objectMapper.createObjectNode();
            if (request.path("includeJobExecutionState").asBoolean(false)) {
                ObjectNode state = response.putObject("executionState");
                state.put("status", execution.getStatus());
                state.put("versionNumber", execution.getVersionNumber());
                state.set("statusDetails", objectMapper.valueToTree(execution.getStatusDetails()));
            }
            if (request.path("includeJobDocument").asBoolean(false)) {
                response.put("jobDocument", iotService.describeJob(jobId, region).getDocument());
            }
            return Response.ok(response).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
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

    @PATCH
    @Path("/rules/{ruleName}")
    public Response replaceTopicRule(@Context HttpHeaders headers,
                                     @PathParam("ruleName") String ruleName,
                                     String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode payload = request.has("topicRulePayload") ? request.path("topicRulePayload") : request;
            IotTopicRule rule = iotService.replaceTopicRule(ruleName, payload, regionResolver.resolveRegion(headers));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("ruleArn", rule.getRuleArn());
            response.put("ruleName", rule.getRuleName());
            return Response.ok(response).build();
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
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

    private Long parseExpectedVersion(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            return request.hasNonNull("expectedVersion") ? request.path("expectedVersion").asLong() : null;
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private String parseThingTypeName(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            return request.path("thingTypeName").asText(null);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private JsonNode parseProperties(String body, String field) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            return request.path(field);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private GroupMembershipRequest parseGroupMembershipRequest(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            return new GroupMembershipRequest(request.path("thingGroupName").asText(null), request.path("thingName").asText(null));
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

    private Map<String, String> parseStatusDetails(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            return parseStatusDetails(request.path("statusDetails"));
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private Map<String, String> parseStatusDetails(JsonNode node) {
        Map<String, String> details = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> details.put(entry.getKey(), entry.getValue().asText()));
        }
        return details;
    }

    private ObjectNode buildThingResponse(Thing thing) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("thingName", thing.getThingName());
        response.put("thingArn", thing.getThingArn());
        response.put("thingId", thing.getThingId());
        response.put("thingTypeName", thing.getThingTypeName());
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

    private ObjectNode buildPolicyVersionResponse(IotPolicy policy, IotPolicy.PolicyVersion version, boolean isDefault) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("policyArn", policy.getPolicyArn());
        response.put("policyName", policy.getPolicyName());
        response.put("policyDocument", version.getDocument());
        response.put("policyVersionId", version.getVersionId());
        response.put("isDefaultVersion", isDefault);
        putEpoch(response, "creationDate", version.getCreateDate());
        return response;
    }

    private ObjectNode buildPolicyVersionSummary(IotPolicy policy, IotPolicy.PolicyVersion version) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("versionId", version.getVersionId());
        response.put("isDefaultVersion", version.getVersionId().equals(policy.getDefaultVersionId()));
        putEpoch(response, "createDate", version.getCreateDate());
        return response;
    }

    private ObjectNode buildJobResponse(IotJob job) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobArn", job.getJobArn());
        response.put("jobId", job.getJobId());
        response.put("targetSelection", job.getTargetSelection());
        response.put("status", job.getStatus());
        ArrayNode targets = response.putArray("targets");
        job.getTargets().forEach(targets::add);
        response.put("description", job.getDescription());
        putEpoch(response, "createdAt", job.getCreatedAt());
        putEpoch(response, "lastUpdatedAt", job.getLastUpdatedAt());
        return response;
    }

    private ObjectNode buildThingTypeCreateResponse(IotThingType type) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("thingTypeName", type.getThingTypeName());
        response.put("thingTypeArn", type.getThingTypeArn());
        response.put("thingTypeId", type.getThingTypeId());
        return response;
    }

    private ObjectNode buildThingTypeDescription(IotThingType type) {
        ObjectNode response = buildThingTypeCreateResponse(type);
        response.set("thingTypeProperties", buildThingTypeProperties(type));
        ObjectNode metadata = response.putObject("thingTypeMetadata");
        metadata.put("deprecated", type.isDeprecated());
        putEpoch(metadata, "creationDate", type.getCreationDate());
        putEpoch(metadata, "deprecatedDate", type.getDeprecatedDate());
        return response;
    }

    private ObjectNode buildThingTypeSummary(IotThingType type) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("thingTypeName", type.getThingTypeName());
        response.put("thingTypeArn", type.getThingTypeArn());
        response.put("thingTypeId", type.getThingTypeId());
        return response;
    }

    private ObjectNode buildThingTypeProperties(IotThingType type) {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("thingTypeDescription", type.getDescription());
        ArrayNode attrs = properties.putArray("searchableAttributes");
        type.getSearchableAttributes().forEach(attrs::add);
        return properties;
    }

    private ObjectNode buildThingGroupCreateResponse(IotThingGroup group) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("thingGroupName", group.getThingGroupName());
        response.put("thingGroupArn", group.getThingGroupArn());
        response.put("thingGroupId", group.getThingGroupId());
        return response;
    }

    private ObjectNode buildThingGroupDescription(IotThingGroup group) {
        ObjectNode response = buildThingGroupCreateResponse(group);
        response.set("thingGroupProperties", buildThingGroupProperties(group));
        response.put("version", group.getVersion());
        ObjectNode metadata = response.putObject("thingGroupMetadata");
        putEpoch(metadata, "creationDate", group.getCreationDate());
        return response;
    }

    private ObjectNode buildThingGroupSummary(IotThingGroup group) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("groupName", group.getThingGroupName());
        response.put("groupArn", group.getThingGroupArn());
        return response;
    }

    private ObjectNode buildThingGroupProperties(IotThingGroup group) {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("thingGroupDescription", group.getDescription());
        ObjectNode attributePayload = properties.putObject("attributePayload");
        attributePayload.set("attributes", objectMapper.valueToTree(group.getAttributes()));
        return properties;
    }

    private ObjectNode buildJobSummary(IotJob job) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobArn", job.getJobArn());
        response.put("jobId", job.getJobId());
        response.put("targetSelection", job.getTargetSelection());
        response.put("status", job.getStatus());
        putEpoch(response, "createdAt", job.getCreatedAt());
        putEpoch(response, "lastUpdatedAt", job.getLastUpdatedAt());
        return response;
    }

    private ObjectNode jobExecutionResponse(IotJobExecution execution, IotJob job, boolean includeDocument) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode executionNode = buildJobExecution(execution);
        if (includeDocument && job.getDocument() != null) {
            executionNode.put("jobDocument", job.getDocument());
        }
        response.set("execution", executionNode);
        return response;
    }

    private ObjectNode buildJobExecution(IotJobExecution execution) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", execution.getJobId());
        node.put("thingName", execution.getThingName());
        node.put("thingArn", execution.getThingArn());
        node.put("status", execution.getStatus());
        node.set("statusDetails", objectMapper.valueToTree(execution.getStatusDetails()));
        putEpochSeconds(node, "queuedAt", execution.getQueuedAt());
        putEpochSeconds(node, "startedAt", execution.getStartedAt());
        putEpochSeconds(node, "lastUpdatedAt", execution.getLastUpdatedAt());
        node.put("executionNumber", execution.getExecutionNumber());
        node.put("versionNumber", execution.getVersionNumber());
        return node;
    }

    private ObjectNode buildJobExecutionSummary(IotJobExecution execution) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("status", execution.getStatus());
        putEpochSeconds(summary, "queuedAt", execution.getQueuedAt());
        putEpochSeconds(summary, "startedAt", execution.getStartedAt());
        putEpochSeconds(summary, "lastUpdatedAt", execution.getLastUpdatedAt());
        summary.put("executionNumber", execution.getExecutionNumber());
        return summary;
    }

    private ObjectNode buildJobsDataExecutionSummary(IotJobExecution execution) {
        ObjectNode summary = buildJobExecutionSummary(execution);
        summary.put("jobId", execution.getJobId());
        summary.put("versionNumber", execution.getVersionNumber());
        return summary;
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

    private void putEpochSeconds(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, instant.getEpochSecond());
        }
    }

    private record GroupMembershipRequest(String thingGroupName, String thingName) {
    }
}
