package io.floci.aws.services.rolesanywhere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.rolesanywhere.model.AttributeMapping;
import io.floci.aws.services.rolesanywhere.model.CredentialSummary;
import io.floci.aws.services.rolesanywhere.model.Crl;
import io.floci.aws.services.rolesanywhere.model.InstanceProperty;
import io.floci.aws.services.rolesanywhere.model.NotificationSetting;
import io.floci.aws.services.rolesanywhere.model.Profile;
import io.floci.aws.services.rolesanywhere.model.Subject;
import io.floci.aws.services.rolesanywhere.model.TrustAnchor;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The REST JSON routes of the IAM Roles Anywhere control plane, mounted under
 * {@link RolesAnywhereRouteFilter#INTERNAL_PREFIX}.
 *
 * <p>Every public path is a literal first segment ({@code /trustanchors}, {@code /profile},
 * {@code /TagResource} and so on) that S3's path-style routes would otherwise claim as a bucket, so
 * {@link RolesAnywhereRouteFilter} rewrites a request signed for {@code rolesanywhere} onto the internal
 * prefix before route matching. An identifier label is greedy because it may carry a resource or alias
 * ARN, whose resource part has slashes. Every timestamp is ISO 8601, the format the model declares.
 */
@Path(RolesAnywhereRouteFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RolesAnywhereController {

    private final RolesAnywhereService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public RolesAnywhereController(RolesAnywhereService service, RegionResolver regionResolver,
                                   ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ trust anchors

    @POST
    @Path("/trustanchors")
    public Response createTrustAnchor(@Context HttpHeaders headers, String body) {
        TrustAnchor anchor = service.createTrustAnchor(readTree(body), region(headers));
        return Response.status(201).entity(trustAnchorResponse(anchor)).build();
    }

    @GET
    @Path("/trustanchors")
    public Response listTrustAnchors(@Context HttpHeaders headers, @QueryParam("pageSize") String pageSize,
                                     @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<TrustAnchor> page = service.listTrustAnchors(pageSize, nextToken, region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("trustAnchors");
        for (TrustAnchor anchor : page.items()) {
            items.add(trustAnchorNode(anchor));
        }
        return Response.ok(withToken(response, page.nextToken())).build();
    }

    @GET
    @Path("/trustanchor/{trustAnchorId:.+}")
    public Response getTrustAnchor(@PathParam("trustAnchorId") String trustAnchorId, @Context HttpHeaders headers) {
        return Response.ok(trustAnchorResponse(service.getTrustAnchor(trustAnchorId, region(headers)))).build();
    }

    @PATCH
    @Path("/trustanchor/{trustAnchorId:.+}")
    public Response updateTrustAnchor(@PathParam("trustAnchorId") String trustAnchorId,
                                      @Context HttpHeaders headers, String body) {
        TrustAnchor anchor = service.updateTrustAnchor(trustAnchorId, readTree(body), region(headers));
        return Response.ok(trustAnchorResponse(anchor)).build();
    }

    @DELETE
    @Path("/trustanchor/{trustAnchorId:.+}")
    public Response deleteTrustAnchor(@PathParam("trustAnchorId") String trustAnchorId,
                                      @Context HttpHeaders headers) {
        return Response.ok(trustAnchorResponse(service.deleteTrustAnchor(trustAnchorId, region(headers)))).build();
    }

    @POST
    @Path("/trustanchor/{trustAnchorId:.+}/enable")
    public Response enableTrustAnchor(@PathParam("trustAnchorId") String trustAnchorId,
                                      @Context HttpHeaders headers) {
        TrustAnchor anchor = service.setTrustAnchorEnabled(trustAnchorId, true, region(headers));
        return Response.ok(trustAnchorResponse(anchor)).build();
    }

    @POST
    @Path("/trustanchor/{trustAnchorId:.+}/disable")
    public Response disableTrustAnchor(@PathParam("trustAnchorId") String trustAnchorId,
                                       @Context HttpHeaders headers) {
        TrustAnchor anchor = service.setTrustAnchorEnabled(trustAnchorId, false, region(headers));
        return Response.ok(trustAnchorResponse(anchor)).build();
    }

    @PATCH
    @Path("/put-notifications-settings")
    public Response putNotificationSettings(@Context HttpHeaders headers, String body) {
        TrustAnchor anchor = service.putNotificationSettings(readTree(body), region(headers));
        return Response.ok(trustAnchorResponse(anchor)).build();
    }

    @PATCH
    @Path("/reset-notifications-settings")
    public Response resetNotificationSettings(@Context HttpHeaders headers, String body) {
        TrustAnchor anchor = service.resetNotificationSettings(readTree(body), region(headers));
        return Response.ok(trustAnchorResponse(anchor)).build();
    }

    // ----------------------------------------------------------------- profiles

    @POST
    @Path("/profiles")
    public Response createProfile(@Context HttpHeaders headers, String body) {
        Profile profile = service.createProfile(readTree(body), region(headers));
        return Response.status(201).entity(profileResponse(profile)).build();
    }

    @GET
    @Path("/profiles")
    public Response listProfiles(@Context HttpHeaders headers, @QueryParam("pageSize") String pageSize,
                                 @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<Profile> page = service.listProfiles(pageSize, nextToken, region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("profiles");
        for (Profile profile : page.items()) {
            items.add(profileNode(profile));
        }
        return Response.ok(withToken(response, page.nextToken())).build();
    }

    @GET
    @Path("/profile/{profileId:.+}")
    public Response getProfile(@PathParam("profileId") String profileId, @Context HttpHeaders headers) {
        return Response.ok(profileResponse(service.getProfile(profileId, region(headers)))).build();
    }

    @PATCH
    @Path("/profile/{profileId:.+}")
    public Response updateProfile(@PathParam("profileId") String profileId, @Context HttpHeaders headers,
                                  String body) {
        return Response.ok(profileResponse(service.updateProfile(profileId, readTree(body), region(headers))))
                .build();
    }

    @DELETE
    @Path("/profile/{profileId:.+}")
    public Response deleteProfile(@PathParam("profileId") String profileId, @Context HttpHeaders headers) {
        return Response.ok(profileResponse(service.deleteProfile(profileId, region(headers)))).build();
    }

    @POST
    @Path("/profile/{profileId:.+}/enable")
    public Response enableProfile(@PathParam("profileId") String profileId, @Context HttpHeaders headers) {
        return Response.ok(profileResponse(service.setProfileEnabled(profileId, true, region(headers)))).build();
    }

    @POST
    @Path("/profile/{profileId:.+}/disable")
    public Response disableProfile(@PathParam("profileId") String profileId, @Context HttpHeaders headers) {
        return Response.ok(profileResponse(service.setProfileEnabled(profileId, false, region(headers)))).build();
    }

    @PUT
    @Path("/profiles/{profileId:.+}/mappings")
    public Response putAttributeMapping(@PathParam("profileId") String profileId, @Context HttpHeaders headers,
                                        String body) {
        Profile profile = service.putAttributeMapping(profileId, readTree(body), region(headers));
        return Response.ok(profileResponse(profile)).build();
    }

    @DELETE
    @Path("/profiles/{profileId:.+}/mappings")
    public Response deleteAttributeMapping(@PathParam("profileId") String profileId,
                                           @QueryParam("certificateField") String certificateField,
                                           @QueryParam("specifiers") List<String> specifiers,
                                           @Context HttpHeaders headers) {
        Profile profile = service.deleteAttributeMapping(profileId, certificateField, specifiers, region(headers));
        return Response.ok(profileResponse(profile)).build();
    }

    // --------------------------------------------------------------------- CRLs

    @POST
    @Path("/crls")
    public Response importCrl(@Context HttpHeaders headers, String body) {
        Crl crl = service.importCrl(readTree(body), region(headers));
        return Response.status(201).entity(crlResponse(crl)).build();
    }

    @GET
    @Path("/crls")
    public Response listCrls(@Context HttpHeaders headers, @QueryParam("pageSize") String pageSize,
                             @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<Crl> page = service.listCrls(pageSize, nextToken, region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("crls");
        for (Crl crl : page.items()) {
            items.add(crlNode(crl));
        }
        return Response.ok(withToken(response, page.nextToken())).build();
    }

    @GET
    @Path("/crl/{crlId:.+}")
    public Response getCrl(@PathParam("crlId") String crlId, @Context HttpHeaders headers) {
        return Response.ok(crlResponse(service.getCrl(crlId, region(headers)))).build();
    }

    @PATCH
    @Path("/crl/{crlId:.+}")
    public Response updateCrl(@PathParam("crlId") String crlId, @Context HttpHeaders headers, String body) {
        return Response.ok(crlResponse(service.updateCrl(crlId, readTree(body), region(headers)))).build();
    }

    @DELETE
    @Path("/crl/{crlId:.+}")
    public Response deleteCrl(@PathParam("crlId") String crlId, @Context HttpHeaders headers) {
        return Response.ok(crlResponse(service.deleteCrl(crlId, region(headers)))).build();
    }

    @POST
    @Path("/crl/{crlId:.+}/enable")
    public Response enableCrl(@PathParam("crlId") String crlId, @Context HttpHeaders headers) {
        return Response.ok(crlResponse(service.setCrlEnabled(crlId, true, region(headers)))).build();
    }

    @POST
    @Path("/crl/{crlId:.+}/disable")
    public Response disableCrl(@PathParam("crlId") String crlId, @Context HttpHeaders headers) {
        return Response.ok(crlResponse(service.setCrlEnabled(crlId, false, region(headers)))).build();
    }

    // ----------------------------------------------------------------- subjects

    @GET
    @Path("/subjects")
    public Response listSubjects(@Context HttpHeaders headers, @QueryParam("pageSize") String pageSize,
                                 @QueryParam("nextToken") String nextToken) {
        RolesAnywhereService.Page<Subject> page = service.listSubjects(pageSize, nextToken, region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("subjects");
        for (Subject subject : page.items()) {
            items.add(subjectSummary(subject));
        }
        return Response.ok(withToken(response, page.nextToken())).build();
    }

    @GET
    @Path("/subject/{subjectId}")
    public Response getSubject(@PathParam("subjectId") String subjectId, @Context HttpHeaders headers) {
        Subject subject = service.getSubject(subjectId, region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("subject", subjectDetail(subject));
        return Response.ok(response).build();
    }

    // --------------------------------------------------------------------- tags

    @POST
    @Path("/TagResource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        service.tagResource(readTree(body), region(headers));
        return Response.status(201).entity(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/UntagResource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        service.untagResource(readTree(body), region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/ListTagsForResource")
    public Response listTagsForResource(@QueryParam("resourceArn") String resourceArn,
                                        @Context HttpHeaders headers) {
        Map<String, String> tags = service.listTagsForResource(resourceArn, region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tags", tagList(tags));
        return Response.ok(response).build();
    }

    // ---------------------------------------------------------------- rendering

    private ObjectNode trustAnchorResponse(TrustAnchor anchor) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("trustAnchor", trustAnchorNode(anchor));
        return response;
    }

    private ObjectNode trustAnchorNode(TrustAnchor anchor) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("trustAnchorId", anchor.getTrustAnchorId());
        node.put("trustAnchorArn", anchor.getTrustAnchorArn());
        node.put("name", anchor.getName());
        ObjectNode source = node.putObject("source");
        source.put("sourceType", anchor.getSourceType());
        ObjectNode sourceData = source.putObject("sourceData");
        if (anchor.getAcmPcaArn() != null) {
            sourceData.put("acmPcaArn", anchor.getAcmPcaArn());
        } else {
            sourceData.put("x509CertificateData", anchor.getX509CertificateData());
        }
        node.put("enabled", anchor.isEnabled());
        node.put("crlDpRevocationEnabled", Boolean.TRUE.equals(anchor.getCrlDpRevocationEnabled()));
        node.put("createdAt", timestamp(anchor.getCreatedAt()));
        node.put("updatedAt", timestamp(anchor.getUpdatedAt()));
        ArrayNode settings = node.putArray("notificationSettings");
        for (NotificationSetting setting : anchor.getNotificationSettings()) {
            ObjectNode entry = settings.addObject();
            entry.put("enabled", setting.isEnabled());
            entry.put("event", setting.getEvent());
            if (setting.getThreshold() != null) {
                entry.put("threshold", setting.getThreshold());
            }
            entry.put("channel", setting.getChannel());
            entry.put("configuredBy", setting.getConfiguredBy());
        }
        aliasMembers(node, anchor.getAliasName(), anchor.getAliasArn(), anchor.getPreviousAliasName());
        return node;
    }

    private ObjectNode profileResponse(Profile profile) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("profile", profileNode(profile));
        return response;
    }

    private ObjectNode profileNode(Profile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("profileId", profile.getProfileId());
        node.put("profileArn", profile.getProfileArn());
        node.put("name", profile.getName());
        node.put("requireInstanceProperties", Boolean.TRUE.equals(profile.getRequireInstanceProperties()));
        node.put("enabled", profile.isEnabled());
        node.put("createdBy", profile.getCreatedBy());
        if (profile.getSessionPolicy() != null) {
            node.put("sessionPolicy", profile.getSessionPolicy());
        }
        node.set("roleArns", objectMapper.valueToTree(profile.getRoleArns()));
        node.set("managedPolicyArns", objectMapper.valueToTree(profile.getManagedPolicyArns()));
        node.put("createdAt", timestamp(profile.getCreatedAt()));
        node.put("updatedAt", timestamp(profile.getUpdatedAt()));
        node.put("durationSeconds", profile.getDurationSeconds());
        node.put("acceptRoleSessionName", Boolean.TRUE.equals(profile.getAcceptRoleSessionName()));
        ArrayNode mappings = node.putArray("attributeMappings");
        for (AttributeMapping mapping : profile.getAttributeMappings()) {
            ObjectNode entry = mappings.addObject();
            entry.put("certificateField", mapping.getCertificateField());
            ArrayNode rules = entry.putArray("mappingRules");
            for (String specifier : mapping.getSpecifiers()) {
                rules.addObject().put("specifier", specifier);
            }
        }
        if (profile.getTipConfiguration() != null) {
            node.set("tipConfiguration", profile.getTipConfiguration());
        }
        aliasMembers(node, profile.getAliasName(), profile.getAliasArn(), profile.getPreviousAliasName());
        return node;
    }

    private ObjectNode crlResponse(Crl crl) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("crl", crlNode(crl));
        return response;
    }

    private ObjectNode crlNode(Crl crl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("crlId", crl.getCrlId());
        node.put("crlArn", crl.getCrlArn());
        node.put("name", crl.getName());
        node.put("enabled", crl.isEnabled());
        node.put("crlData", crl.getCrlData());
        node.put("trustAnchorArn", crl.getTrustAnchorArn());
        node.put("createdAt", timestamp(crl.getCreatedAt()));
        node.put("updatedAt", timestamp(crl.getUpdatedAt()));
        aliasMembers(node, crl.getAliasName(), crl.getAliasArn(), crl.getPreviousAliasName());
        return node;
    }

    private ObjectNode subjectSummary(Subject subject) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("subjectArn", subject.getSubjectArn());
        node.put("subjectId", subject.getSubjectId());
        node.put("enabled", subject.isEnabled());
        node.put("x509Subject", subject.getX509Subject());
        if (subject.getLastSeenAt() != null) {
            node.put("lastSeenAt", timestamp(subject.getLastSeenAt()));
        }
        node.put("createdAt", timestamp(subject.getCreatedAt()));
        node.put("updatedAt", timestamp(subject.getUpdatedAt()));
        return node;
    }

    private ObjectNode subjectDetail(Subject subject) {
        ObjectNode node = subjectSummary(subject);
        ArrayNode credentials = node.putArray("credentials");
        for (CredentialSummary credential : subject.getCredentials()) {
            ObjectNode entry = credentials.addObject();
            if (credential.getSeenAt() != null) {
                entry.put("seenAt", timestamp(credential.getSeenAt()));
            }
            entry.put("serialNumber", credential.getSerialNumber());
            entry.put("issuer", credential.getIssuer());
            entry.put("enabled", credential.isEnabled());
            entry.put("x509CertificateData", credential.getX509CertificateData());
            entry.put("failed", credential.isFailed());
        }
        ArrayNode properties = node.putArray("instanceProperties");
        for (InstanceProperty property : subject.getInstanceProperties()) {
            ObjectNode entry = properties.addObject();
            if (property.getSeenAt() != null) {
                entry.put("seenAt", timestamp(property.getSeenAt()));
            }
            entry.set("properties", objectMapper.valueToTree(property.getProperties()));
            entry.put("failed", property.isFailed());
        }
        return node;
    }

    private static void aliasMembers(ObjectNode node, String aliasName, String aliasArn, String previousAliasName) {
        if (aliasName != null) {
            node.put("aliasName", aliasName);
            node.put("aliasArn", aliasArn);
        }
        if (previousAliasName != null) {
            node.put("previousAliasName", previousAliasName);
        }
    }

    private ArrayNode tagList(Map<String, String> tags) {
        ArrayNode list = objectMapper.createArrayNode();
        tags.forEach((key, value) -> list.addObject().put("key", key).put("value", value));
        return list;
    }

    private static ObjectNode withToken(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("nextToken", nextToken);
        }
        return response;
    }

    /** The model's {@code iso8601} timestamp format, to the second. */
    private static String timestamp(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
