package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Path("/2020-05-31")
public class CloudFrontController {

    private static final Logger LOG = Logger.getLogger(CloudFrontController.class);

    private static final String NS = AwsNamespaces.CLOUDFRONT;
    private static final String XML = "application/xml";
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String GEO_RESTRICTION = "GeoRestriction";
    private static final String ORIGIN_GROUPS = "OriginGroups";
    private static final String ITEMS = "Items";
    private static final String LOCATION = "Location";
    private static final String LOCATIONS = "Locations";
    private static final String QUANTITY = "Quantity";
    private static final String RESTRICTION_TYPE = "RestrictionType";
    private static final String RESTRICTIONS = "Restrictions";
    private static final String DEFAULT_GEO_RESTRICTION_TYPE = "none";
    private static final int EMPTY_QUANTITY = 0;

    private static final XMLInputFactory XML_FACTORY;

    static {
        XML_FACTORY = XMLInputFactory.newInstance();
        XML_FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private final CloudFrontService service;

    @Inject
    public CloudFrontController(CloudFrontService service) {
        this.service = service;
    }

    // ── Distributions ─────────────────────────────────────────────────────────

    @POST
    @Path("/distribution")
    public Response createDistribution(@QueryParam("WithTags") String withTags, String body) {
        try {
            DistributionConfig config = parseDistributionConfig(body);
            Map<String, String> tags = new LinkedHashMap<>();
            if (withTags != null) {
                tags = parseTags(body);
            }
            Distribution dist = new Distribution();
            dist.setConfig(config);
            dist = service.createDistribution(dist, tags);
            String xml = xmlDistribution(dist);
            return Response.created(URI.create("/2020-05-31/distribution/" + dist.getId()))
                    .type(XML)
                    .header("ETag", dist.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}")
    public Response getDistribution(@PathParam("Id") String id) {
        try {
            Distribution dist = service.getDistribution(id);
            String xml = xmlDistribution(dist);
            return Response.ok(xml, XML).header("ETag", dist.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}/config")
    public Response getDistributionConfig(@PathParam("Id") String id) {
        try {
            Distribution dist = service.getDistribution(id);
            String xml = new XmlBuilder()
                    .start("DistributionConfig", NS)
                    .raw(xmlDistributionConfigBody(dist.getConfig()))
                    .end("DistributionConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", dist.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/distribution/{Id}/config")
    public Response updateDistribution(@PathParam("Id") String id,
                                       @HeaderParam("If-Match") String ifMatch,
                                       String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            DistributionConfig config = parseDistributionConfig(body);
            Distribution updated = new Distribution();
            updated.setConfig(config);
            updated = service.updateDistribution(id, ifMatch, updated);
            String xml = xmlDistribution(updated);
            return Response.ok(xml, XML).header("ETag", updated.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/distribution/{Id}")
    public Response deleteDistribution(@PathParam("Id") String id,
                                       @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteDistribution(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution")
    public Response listDistributions(@QueryParam("Marker") String marker,
                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<Distribution> page = page(
                    service.listDistributions(marker, paginationFetchLimit(maxItems)),
                    maxItems, Distribution::getId);
            int totalDistributions =
                    service.listDistributions(null, Integer.MAX_VALUE).size();

            XmlBuilder xml = new XmlBuilder()
                    .start("DistributionList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", page.truncated())
                    .elem("Quantity", totalDistributions)
                    .start("Items");
            for (Distribution d : page.items()) {
                xml.raw(xmlDistributionSummary(d));
            }
            xml.end("Items").end("DistributionList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/distribution/{TargetDistributionId}/associate-alias")
    public Response associateAlias(@PathParam("TargetDistributionId") String targetId,
                                   @QueryParam("Alias") String alias) {
        try {
            service.associateAlias(targetId, alias);
            return Response.ok("", XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Invalidations ─────────────────────────────────────────────────────────

    @POST
    @Path("/distribution/{Id}/invalidation")
    public Response createInvalidation(@PathParam("Id") String id, String body) {
        try {
            Invalidation inv = parseInvalidation(body);
            inv = service.createInvalidation(id, inv);
            String xml = new XmlBuilder()
                    .start("Invalidation", NS)
                    .raw(xmlInvalidationBody(inv))
                    .end("Invalidation")
                    .build();
            return Response.created(
                            URI.create("/2020-05-31/distribution/" + id + "/invalidation/" + inv.getId()))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}/invalidation/{InvId}")
    public Response getInvalidation(@PathParam("Id") String id,
                                    @PathParam("InvId") String invId) {
        try {
            Invalidation inv = service.getInvalidation(id, invId);
            String xml = new XmlBuilder()
                    .start("Invalidation", NS)
                    .raw(xmlInvalidationBody(inv))
                    .end("Invalidation")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distribution/{Id}/invalidation")
    public Response listInvalidations(@PathParam("Id") String id,
                                      @QueryParam("Marker") String marker,
                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<Invalidation> page = page(
                    service.listInvalidations(id, marker, paginationFetchLimit(maxItems)),
                    maxItems, Invalidation::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("InvalidationList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", page.truncated())
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (Invalidation inv : page.items()) {
                xml.start("InvalidationSummary")
                        .elem("Id", inv.getId())
                        .elem("Status", inv.getStatus())
                        .elem("CreateTime", inv.getCreateTime() != null ? inv.getCreateTime().toString() : "")
                        .end("InvalidationSummary");
            }
            xml.end("Items").end("InvalidationList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Cache Policies ────────────────────────────────────────────────────────

    @POST
    @Path("/cache-policy")
    public Response createCachePolicy(String body) {
        try {
            CachePolicy policy = parseCachePolicy(body);
            policy = service.createCachePolicy(policy);
            String xml = xmlCachePolicyResponse(policy);
            return Response.created(URI.create("/2020-05-31/cache-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/cache-policy/{Id}")
    public Response getCachePolicy(@PathParam("Id") String id) {
        try {
            CachePolicy policy = service.getCachePolicy(id);
            return Response.ok(xmlCachePolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/cache-policy/{Id}/config")
    public Response getCachePolicyConfig(@PathParam("Id") String id) {
        try {
            CachePolicy policy = service.getCachePolicy(id);
            String xml = new XmlBuilder()
                    .start("CachePolicyConfig", NS)
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("CachePolicyConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/cache-policy/{Id}")
    public Response updateCachePolicy(@PathParam("Id") String id,
                                      @HeaderParam("If-Match") String ifMatch,
                                      String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CachePolicy policy = parseCachePolicy(body);
            policy = service.updateCachePolicy(id, ifMatch, policy);
            return Response.ok(xmlCachePolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/cache-policy/{Id}")
    public Response deleteCachePolicy(@PathParam("Id") String id,
                                      @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteCachePolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/cache-policy")
    public Response listCachePolicies(@QueryParam("Marker") String marker,
                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems,
                                      @QueryParam("Type") String type) {
        try {
            Page<CachePolicy> page = page(
                    service.listCachePolicies(marker, paginationFetchLimit(maxItems)),
                    maxItems, CachePolicy::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("CachePolicyList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (CachePolicy p : page.items()) {
                xml.start("CachePolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlCachePolicyResponse(p))
                        .end("CachePolicySummary");
            }
            xml.end("Items").end("CachePolicyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Origin Request Policies ───────────────────────────────────────────────

    @POST
    @Path("/origin-request-policy")
    public Response createOriginRequestPolicy(String body) {
        try {
            OriginRequestPolicy policy = parseOriginRequestPolicy(body);
            policy = service.createOriginRequestPolicy(policy);
            String xml = xmlOriginRequestPolicyResponse(policy);
            return Response.created(URI.create("/2020-05-31/origin-request-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-request-policy/{Id}")
    public Response getOriginRequestPolicy(@PathParam("Id") String id) {
        try {
            OriginRequestPolicy policy = service.getOriginRequestPolicy(id);
            return Response.ok(xmlOriginRequestPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-request-policy/{Id}/config")
    public Response getOriginRequestPolicyConfig(@PathParam("Id") String id) {
        try {
            OriginRequestPolicy policy = service.getOriginRequestPolicy(id);
            String xml = new XmlBuilder()
                    .start("OriginRequestPolicyConfig", NS)
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("OriginRequestPolicyConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/origin-request-policy/{Id}")
    public Response updateOriginRequestPolicy(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch,
                                              String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            OriginRequestPolicy policy = parseOriginRequestPolicy(body);
            policy = service.updateOriginRequestPolicy(id, ifMatch, policy);
            return Response.ok(xmlOriginRequestPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/origin-request-policy/{Id}")
    public Response deleteOriginRequestPolicy(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteOriginRequestPolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-request-policy")
    public Response listOriginRequestPolicies(@QueryParam("Marker") String marker,
                                              @QueryParam("MaxItems") @DefaultValue("100") int maxItems,
                                              @QueryParam("Type") String type) {
        try {
            Page<OriginRequestPolicy> page = page(
                    service.listOriginRequestPolicies(marker, paginationFetchLimit(maxItems)),
                    maxItems, OriginRequestPolicy::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("OriginRequestPolicyList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (OriginRequestPolicy p : page.items()) {
                xml.start("OriginRequestPolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlOriginRequestPolicyResponse(p))
                        .end("OriginRequestPolicySummary");
            }
            xml.end("Items").end("OriginRequestPolicyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Response Headers Policies ─────────────────────────────────────────────

    @POST
    @Path("/response-headers-policy")
    public Response createResponseHeadersPolicy(String body) {
        try {
            ResponseHeadersPolicy policy = parseResponseHeadersPolicy(body);
            policy = service.createResponseHeadersPolicy(policy);
            String xml = xmlResponseHeadersPolicyResponse(policy);
            return Response.created(
                            URI.create("/2020-05-31/response-headers-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/response-headers-policy/{Id}")
    public Response getResponseHeadersPolicy(@PathParam("Id") String id) {
        try {
            ResponseHeadersPolicy policy = service.getResponseHeadersPolicy(id);
            return Response.ok(xmlResponseHeadersPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/response-headers-policy/{Id}/config")
    public Response getResponseHeadersPolicyConfig(@PathParam("Id") String id) {
        try {
            ResponseHeadersPolicy policy = service.getResponseHeadersPolicy(id);
            XmlBuilder builder = new XmlBuilder()
                    .start("ResponseHeadersPolicyConfig", NS)
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "");
            ResponseHeadersPolicyConfigCodec.serialize(builder, policy.getConfig());
            String xml = builder.end("ResponseHeadersPolicyConfig").build();
            return Response.ok(xml, XML).header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/response-headers-policy/{Id}")
    public Response updateResponseHeadersPolicy(@PathParam("Id") String id,
                                                @HeaderParam("If-Match") String ifMatch,
                                                String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            ResponseHeadersPolicy policy = parseResponseHeadersPolicy(body);
            policy = service.updateResponseHeadersPolicy(id, ifMatch, policy);
            return Response.ok(xmlResponseHeadersPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/response-headers-policy/{Id}")
    public Response deleteResponseHeadersPolicy(@PathParam("Id") String id,
                                                @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteResponseHeadersPolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/response-headers-policy")
    public Response listResponseHeadersPolicies(@QueryParam("Marker") String marker,
                                                @QueryParam("MaxItems") @DefaultValue("100") int maxItems,
                                                @QueryParam("Type") String type) {
        try {
            if (maxItems < 1 || maxItems > 100) {
                throw new AwsException("InvalidArgument",
                        "MaxItems must be between 1 and 100.", 400);
            }
            Page<ResponseHeadersPolicy> page = page(
                    service.listResponseHeadersPolicies(
                            marker, paginationFetchLimit(maxItems), type),
                    maxItems, ResponseHeadersPolicy::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("ResponseHeadersPolicyList", NS)
                    .start("Items");
            for (ResponseHeadersPolicy p : page.items()) {
                xml.start("ResponseHeadersPolicySummary")
                        .elem("Type", CloudFrontService.isManagedResponseHeadersPolicy(p.getId())
                                ? "managed" : "custom")
                        .raw(xmlResponseHeadersPolicyResponse(p))
                        .end("ResponseHeadersPolicySummary");
            }
            xml.end("Items")
                    .elem("MaxItems", maxItems);
            if (page.nextMarker() != null) {
                xml.elem("NextMarker", page.nextMarker());
            }
            xml.elem("Quantity", page.items().size())
                    .end("ResponseHeadersPolicyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Origin Access Control ─────────────────────────────────────────────────

    @POST
    @Path("/origin-access-control")
    public Response createOriginAccessControl(String body) {
        try {
            OriginAccessControl oac = parseOriginAccessControl(body);
            oac = service.createOriginAccessControl(oac);
            String xml = xmlOriginAccessControlResponse(oac);
            return Response.created(URI.create("/2020-05-31/origin-access-control/" + oac.getId()))
                    .type(XML)
                    .header("ETag", oac.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-control/{Id}")
    public Response getOriginAccessControl(@PathParam("Id") String id) {
        try {
            OriginAccessControl oac = service.getOriginAccessControl(id);
            return Response.ok(xmlOriginAccessControlResponse(oac), XML)
                    .header("ETag", oac.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-control/{Id}/config")
    public Response getOriginAccessControlConfig(@PathParam("Id") String id) {
        try {
            OriginAccessControl oac = service.getOriginAccessControl(id);
            String xml = new XmlBuilder()
                    .start("OriginAccessControlConfig", NS)
                    .elem("Name", oac.getName())
                    .elem("Description", oac.getDescription() != null ? oac.getDescription() : "")
                    .elem("SigningProtocol", oac.getSigningProtocol())
                    .elem("SigningBehavior", oac.getSigningBehavior())
                    .elem("OriginAccessControlOriginType", oac.getOriginAccessControlOriginType())
                    .end("OriginAccessControlConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", oac.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/origin-access-control/{Id}")
    public Response updateOriginAccessControl(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch,
                                              String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            OriginAccessControl oac = parseOriginAccessControl(body);
            oac = service.updateOriginAccessControl(id, ifMatch, oac);
            return Response.ok(xmlOriginAccessControlResponse(oac), XML)
                    .header("ETag", oac.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/origin-access-control/{Id}")
    public Response deleteOriginAccessControl(@PathParam("Id") String id,
                                              @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteOriginAccessControl(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-control")
    public Response listOriginAccessControls(@QueryParam("Marker") String marker,
                                             @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<OriginAccessControl> page = page(
                    service.listOriginAccessControls(marker, paginationFetchLimit(maxItems)),
                    maxItems, OriginAccessControl::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("OriginAccessControlList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", page.truncated())
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (OriginAccessControl o : page.items()) {
                xml.raw(xmlOriginAccessControlSummary(o));
            }
            xml.end("Items").end("OriginAccessControlList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Origin Access Identity ────────────────────────────────────────────────

    @POST
    @Path("/origin-access-identity/cloudfront")
    public Response createCloudFrontOriginAccessIdentity(String body) {
        try {
            CloudFrontOriginAccessIdentity oai = parseOai(body);
            oai = service.createCloudFrontOriginAccessIdentity(oai);
            String xml = xmlOaiResponse(oai);
            return Response.created(
                            URI.create("/2020-05-31/origin-access-identity/cloudfront/" + oai.getId()))
                    .type(XML)
                    .header("ETag", oai.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-identity/cloudfront/{Id}")
    public Response getCloudFrontOriginAccessIdentity(@PathParam("Id") String id) {
        try {
            CloudFrontOriginAccessIdentity oai = service.getCloudFrontOriginAccessIdentity(id);
            return Response.ok(xmlOaiResponse(oai), XML).header("ETag", oai.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-identity/cloudfront/{Id}/config")
    public Response getCloudFrontOriginAccessIdentityConfig(@PathParam("Id") String id) {
        try {
            CloudFrontOriginAccessIdentity oai = service.getCloudFrontOriginAccessIdentity(id);
            String xml = new XmlBuilder()
                    .start("CloudFrontOriginAccessIdentityConfig", NS)
                    .elem("CallerReference", oai.getCallerReference())
                    .elem("Comment", oai.getComment() != null ? oai.getComment() : "")
                    .end("CloudFrontOriginAccessIdentityConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", oai.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/origin-access-identity/cloudfront/{Id}/config")
    public Response updateCloudFrontOriginAccessIdentity(@PathParam("Id") String id,
                                                         @HeaderParam("If-Match") String ifMatch,
                                                         String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CloudFrontOriginAccessIdentity oai = parseOai(body);
            oai = service.updateCloudFrontOriginAccessIdentity(id, ifMatch, oai);
            return Response.ok(xmlOaiResponse(oai), XML).header("ETag", oai.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/origin-access-identity/cloudfront/{Id}")
    public Response deleteCloudFrontOriginAccessIdentity(@PathParam("Id") String id,
                                                         @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteCloudFrontOriginAccessIdentity(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/origin-access-identity/cloudfront")
    public Response listCloudFrontOriginAccessIdentities(
            @QueryParam("Marker") String marker,
            @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<CloudFrontOriginAccessIdentity> page = page(
                    service.listCloudFrontOriginAccessIdentities(
                            marker, paginationFetchLimit(maxItems)),
                    maxItems, CloudFrontOriginAccessIdentity::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("CloudFrontOriginAccessIdentityList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", page.truncated())
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (CloudFrontOriginAccessIdentity o : page.items()) {
                xml.start("CloudFrontOriginAccessIdentitySummary")
                        .elem("Id", o.getId())
                        .elem("S3CanonicalUserId", o.getS3CanonicalUserId())
                        .elem("Comment", o.getComment() != null ? o.getComment() : "")
                        .end("CloudFrontOriginAccessIdentitySummary");
            }
            xml.end("Items").end("CloudFrontOriginAccessIdentityList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── CloudFront Functions ──────────────────────────────────────────────────

    @POST
    @Path("/function")
    public Response createFunction(String body) {
        try {
            CloudFrontFunction fn = parseFunction(body);
            fn = service.createFunction(fn);
            String xml = xmlFunctionResponse(fn);
            return Response.created(URI.create("/2020-05-31/function/" + fn.getName()))
                    .type(XML)
                    .header("ETag", fn.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/function/{Name}")
    public Response getFunction(@PathParam("Name") String name,
                                @QueryParam("Stage") String stage) {
        try {
            CloudFrontFunction fn = service.describeFunction(name, stage);
            return Response.ok(fn.getFunctionCode() != null ? fn.getFunctionCode() : "", OCTET_STREAM)
                    .header("ETag", fn.getEtag())
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/function/{Name}/describe")
    public Response describeFunction(@PathParam("Name") String name,
                                     @QueryParam("Stage") String stage) {
        try {
            CloudFrontFunction fn = service.describeFunction(name, stage);
            return Response.ok(xmlFunctionResponse(fn), XML).header("ETag", fn.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/function/{Name}")
    public Response updateFunction(@PathParam("Name") String name,
                                   @HeaderParam("If-Match") String ifMatch,
                                   String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CloudFrontFunction fn = parseFunction(body);
            fn = service.updateFunction(name, ifMatch, fn);
            return Response.ok(xmlFunctionResponse(fn), XML).header("ETag", fn.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/function/{Name}/publish")
    public Response publishFunction(@PathParam("Name") String name,
                                    @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            CloudFrontFunction fn = service.publishFunction(name, ifMatch);
            return Response.ok(xmlFunctionResponse(fn), XML).header("ETag", fn.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/function/{Name}")
    public Response deleteFunction(@PathParam("Name") String name,
                                   @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteFunction(name, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/function")
    public Response listFunctions(@QueryParam("Stage") String stage,
                                  @QueryParam("Marker") String marker,
                                  @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<CloudFrontFunction> page = page(
                    service.listFunctions(stage, marker, paginationFetchLimit(maxItems)),
                    maxItems, CloudFrontFunction::getName);
            XmlBuilder xml = new XmlBuilder()
                    .start("FunctionList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (CloudFrontFunction fn : page.items()) {
                xml.start("FunctionSummary")
                        .elem("Name", fn.getName())
                        .elem("Status", fn.getStatus())
                        .start("FunctionConfig")
                        .elem("Comment", fn.getComment() != null ? fn.getComment() : "")
                        .elem("Runtime", fn.getRuntime() != null ? fn.getRuntime() : "cloudfront-js-2.0")
                        .end("FunctionConfig")
                        .start("FunctionMetadata")
                        .elem("FunctionARN", AwsArnUtils.Arn.of("cloudfront", "", service.getAccountId(),
                                "function/" + fn.getName()).toString())
                        .elem("Stage", fn.getStage())
                        .elem("CreatedTime", fn.getCreatedTime() != null ? fn.getCreatedTime().toString() : "")
                        .elem("LastModifiedTime",
                                fn.getLastModifiedTime() != null ? fn.getLastModifiedTime().toString() : "")
                        .end("FunctionMetadata")
                        .end("FunctionSummary");
            }
            xml.end("Items").end("FunctionList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Tagging ───────────────────────────────────────────────────────────────

    @GET
    @Path("/tagging")
    public Response listTagsForResource(@QueryParam("Resource") String resource) {
        try {
            Map<String, String> tags = service.listTagsForResource(resource);
            XmlBuilder xml = new XmlBuilder()
                    .start("Tags", NS)
                    .start("Items");
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                xml.start("Tag")
                        .elem("Key", entry.getKey())
                        .elem("Value", entry.getValue())
                        .end("Tag");
            }
            xml.end("Items").end("Tags");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/tagging")
    public Response tagging(@QueryParam("Operation") String operation,
                            @QueryParam("Resource") String resource,
                            String body) {
        try {
            if ("Tag".equals(operation)) {
                Map<String, String> tags = parseTags(body);
                service.tagResource(resource, tags);
            } else if ("Untag".equals(operation)) {
                List<String> keys = XmlParser.extractAll(body, "Key");
                service.untagResource(resource, keys);
            } else {
                throw new AwsException("InvalidArgument", "Unknown tagging operation.", 400);
            }
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Continuous Deployment Policies ───────────────────────────────────────

    @POST
    @Path("/continuous-deployment-policy")
    public Response createContinuousDeploymentPolicy(String body) {
        try {
            ContinuousDeploymentPolicy policy = parseContinuousDeploymentPolicy(body);
            policy = service.createContinuousDeploymentPolicy(policy);
            String xml = xmlContinuousDeploymentPolicyResponse(policy);
            return Response.created(URI.create("/2020-05-31/continuous-deployment-policy/" + policy.getId()))
                    .type(XML)
                    .header("ETag", policy.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/continuous-deployment-policy/{Id}")
    public Response getContinuousDeploymentPolicy(@PathParam("Id") String id) {
        try {
            ContinuousDeploymentPolicy policy = service.getContinuousDeploymentPolicy(id);
            return Response.ok(xmlContinuousDeploymentPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/continuous-deployment-policy/{Id}")
    public Response updateContinuousDeploymentPolicy(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch,
                                                      String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            ContinuousDeploymentPolicy policy = parseContinuousDeploymentPolicy(body);
            policy = service.updateContinuousDeploymentPolicy(id, ifMatch, policy);
            return Response.ok(xmlContinuousDeploymentPolicyResponse(policy), XML)
                    .header("ETag", policy.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/continuous-deployment-policy/{Id}")
    public Response deleteContinuousDeploymentPolicy(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteContinuousDeploymentPolicy(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/continuous-deployment-policy")
    public Response listContinuousDeploymentPolicies(@QueryParam("Marker") String marker,
                                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<ContinuousDeploymentPolicy> page = page(
                    service.listContinuousDeploymentPolicies(
                            marker, paginationFetchLimit(maxItems)),
                    maxItems, ContinuousDeploymentPolicy::getId);
            int totalPolicies = service.listContinuousDeploymentPolicies(
                    null, Integer.MAX_VALUE).size();

            XmlBuilder xml = new XmlBuilder()
                    .start("ContinuousDeploymentPolicyList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", totalPolicies)
                    .start("Items");
            for (ContinuousDeploymentPolicy p : page.items()) {
                xml.start("ContinuousDeploymentPolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlContinuousDeploymentPolicyResponse(p))
                        .end("ContinuousDeploymentPolicySummary");
            }
            xml.end("Items").end("ContinuousDeploymentPolicyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── CopyDistribution ──────────────────────────────────────────────────────

    @POST
    @Path("/distribution/{PrimaryDistributionId}/copy")
    public Response copyDistribution(@PathParam("PrimaryDistributionId") String primaryId,
                                     String body) {
        try {
            String callerReference = XmlParser.extractFirst(body, "CallerReference", null);
            Map<String, String> tags = parseTags(body);
            Distribution dist = service.copyDistribution(primaryId, callerReference, tags);
            String xml = xmlDistribution(dist);
            return Response.created(URI.create("/2020-05-31/distribution/" + dist.getId()))
                    .type(XML)
                    .header("ETag", dist.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Public Keys ───────────────────────────────────────────────────────────

    @POST
    @Path("/public-key")
    public Response createPublicKey(String body) {
        try {
            PublicKey key = parsePublicKey(body);
            key = service.createPublicKey(key);
            String xml = xmlPublicKeyResponse(key);
            return Response.created(URI.create("/2020-05-31/public-key/" + key.getId()))
                    .type(XML)
                    .header("ETag", key.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/public-key/{Id}")
    public Response getPublicKey(@PathParam("Id") String id) {
        try {
            PublicKey key = service.getPublicKey(id);
            return Response.ok(xmlPublicKeyResponse(key), XML).header("ETag", key.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/public-key/{Id}/config")
    public Response getPublicKeyConfig(@PathParam("Id") String id) {
        try {
            PublicKey key = service.getPublicKey(id);
            String xml = new XmlBuilder()
                    .start("PublicKeyConfig", NS)
                    .elem("CallerReference", key.getCallerReference() != null ? key.getCallerReference() : "")
                    .elem("Name", key.getName() != null ? key.getName() : "")
                    .elem("EncodedKey", key.getEncodedKey() != null ? key.getEncodedKey() : "")
                    .elem("Comment", key.getComment() != null ? key.getComment() : "")
                    .end("PublicKeyConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", key.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/public-key/{Id}/config")
    public Response updatePublicKey(@PathParam("Id") String id,
                                    @HeaderParam("If-Match") String ifMatch,
                                    String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            PublicKey key = parsePublicKey(body);
            key = service.updatePublicKey(id, ifMatch, key);
            return Response.ok(xmlPublicKeyResponse(key), XML).header("ETag", key.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/public-key/{Id}")
    public Response deletePublicKey(@PathParam("Id") String id,
                                    @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deletePublicKey(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/public-key")
    public Response listPublicKeys(@QueryParam("Marker") String marker,
                                   @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<PublicKey> page = page(
                    service.listPublicKeys(marker, paginationFetchLimit(maxItems)),
                    maxItems, PublicKey::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("PublicKeyList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (PublicKey k : page.items()) {
                xml.raw(xmlPublicKeySummary(k));
            }
            xml.end("Items").end("PublicKeyList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Key Groups ────────────────────────────────────────────────────────────

    @POST
    @Path("/key-group")
    public Response createKeyGroup(String body) {
        try {
            KeyGroup group = parseKeyGroup(body);
            group = service.createKeyGroup(group);
            String xml = xmlKeyGroupResponse(group);
            return Response.created(URI.create("/2020-05-31/key-group/" + group.getId()))
                    .type(XML)
                    .header("ETag", group.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-group/{Id}")
    public Response getKeyGroup(@PathParam("Id") String id) {
        try {
            KeyGroup group = service.getKeyGroup(id);
            return Response.ok(xmlKeyGroupResponse(group), XML).header("ETag", group.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-group/{Id}/config")
    public Response getKeyGroupConfig(@PathParam("Id") String id) {
        try {
            KeyGroup group = service.getKeyGroup(id);
            List<String> items = group.getItems() != null ? group.getItems() : List.of();
            String xml = new XmlBuilder()
                    .start("KeyGroupConfig", NS)
                    .elem("Name", group.getName() != null ? group.getName() : "")
                    .elem("Comment", group.getComment() != null ? group.getComment() : "")
                    .raw(xmlDirectItems("Items", "PublicKey", items))
                    .end("KeyGroupConfig")
                    .build();
            return Response.ok(xml, XML).header("ETag", group.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/key-group/{Id}")
    public Response updateKeyGroup(@PathParam("Id") String id,
                                   @HeaderParam("If-Match") String ifMatch,
                                   String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            KeyGroup group = parseKeyGroup(body);
            group = service.updateKeyGroup(id, ifMatch, group);
            return Response.ok(xmlKeyGroupResponse(group), XML).header("ETag", group.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/key-group/{Id}")
    public Response deleteKeyGroup(@PathParam("Id") String id,
                                   @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteKeyGroup(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/key-group")
    public Response listKeyGroups(@QueryParam("Marker") String marker,
                                  @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<KeyGroup> page = page(
                    service.listKeyGroups(marker, paginationFetchLimit(maxItems)),
                    maxItems, KeyGroup::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("KeyGroupList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (KeyGroup g : page.items()) {
                xml.start("KeyGroupSummary").raw(xmlKeyGroupResponse(g)).end("KeyGroupSummary");
            }
            xml.end("Items").end("KeyGroupList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Realtime Log Configs ──────────────────────────────────────────────────

    @POST
    @Path("/realtime-log-config")
    public Response createRealtimeLogConfig(String body) {
        try {
            RealtimeLogConfig cfg = parseRealtimeLogConfig(body);
            cfg = service.createRealtimeLogConfig(cfg);
            String xml = new XmlBuilder()
                    .start("CreateRealtimeLogConfigResult", NS)
                    .raw(xmlRealtimeLogConfigBody(cfg))
                    .end("CreateRealtimeLogConfigResult")
                    .build();
            return Response.created(URI.create("/2020-05-31/realtime-log-config"))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/get-realtime-log-config")
    public Response getRealtimeLogConfig(String body) {
        try {
            String name = XmlParser.extractFirst(body, "Name", null);
            String arn = XmlParser.extractFirst(body, "ARN", null);
            RealtimeLogConfig cfg = service.getRealtimeLogConfig(name != null ? name : arn);
            String xml = new XmlBuilder()
                    .start("GetRealtimeLogConfigResult", NS)
                    .raw(xmlRealtimeLogConfigBody(cfg))
                    .end("GetRealtimeLogConfigResult")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/realtime-log-config")
    public Response updateRealtimeLogConfig(String body) {
        try {
            RealtimeLogConfig cfg = parseRealtimeLogConfig(body);
            cfg = service.updateRealtimeLogConfig(cfg);
            String xml = new XmlBuilder()
                    .start("UpdateRealtimeLogConfigResult", NS)
                    .raw(xmlRealtimeLogConfigBody(cfg))
                    .end("UpdateRealtimeLogConfigResult")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/delete-realtime-log-config")
    public Response deleteRealtimeLogConfig(String body) {
        try {
            String name = XmlParser.extractFirst(body, "Name", null);
            String arn = XmlParser.extractFirst(body, "ARN", null);
            service.deleteRealtimeLogConfig(name != null ? name : arn);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/realtime-log-config")
    public Response listRealtimeLogConfigs(@QueryParam("Marker") String marker,
                                           @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<RealtimeLogConfig> page = page(
                    service.listRealtimeLogConfigs(marker, paginationFetchLimit(maxItems)),
                    maxItems, RealtimeLogConfig::getName);

            XmlBuilder xml = new XmlBuilder()
                    .start("RealtimeLogConfigs", NS)
                    .elem("MaxItems", maxItems)
                    .start("Items");
            for (RealtimeLogConfig c : page.items()) {
                xml.raw(xmlRealtimeLogConfigBody(c));
            }
            xml.end("Items")
                    .elem("IsTruncated", page.truncated())
                    .elem("Marker", marker != null ? marker : "")
                    .elem("NextMarker", page.nextMarker())
                    .end("RealtimeLogConfigs");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Streaming Distributions ───────────────────────────────────────────────

    @POST
    @Path("/streaming-distribution")
    public Response createStreamingDistribution(String body) {
        try {
            StreamingDistribution sd = parseStreamingDistribution(body);
            sd = service.createStreamingDistribution(sd);
            String xml = xmlStreamingDistributionResponse(sd);
            return Response.created(URI.create("/2020-05-31/streaming-distribution/" + sd.getId()))
                    .type(XML)
                    .header("ETag", sd.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/streaming-distribution/{Id}")
    public Response getStreamingDistribution(@PathParam("Id") String id) {
        try {
            StreamingDistribution sd = service.getStreamingDistribution(id);
            return Response.ok(xmlStreamingDistributionResponse(sd), XML)
                    .header("ETag", sd.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/streaming-distribution/{Id}/config")
    public Response getStreamingDistributionConfig(@PathParam("Id") String id) {
        try {
            StreamingDistribution sd = service.getStreamingDistribution(id);
            String xml = xmlStreamingDistributionConfigBody(sd);
            return Response.ok(xml, XML).header("ETag", sd.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/streaming-distribution/{Id}/config")
    public Response updateStreamingDistribution(@PathParam("Id") String id,
                                                 @HeaderParam("If-Match") String ifMatch,
                                                 String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            StreamingDistribution sd = parseStreamingDistribution(body);
            sd = service.updateStreamingDistribution(id, ifMatch, sd);
            return Response.ok(xmlStreamingDistributionResponse(sd), XML)
                    .header("ETag", sd.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/streaming-distribution/{Id}")
    public Response deleteStreamingDistribution(@PathParam("Id") String id,
                                                 @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteStreamingDistribution(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Field-Level Encryption Configs ────────────────────────────────────────

    @POST
    @Path("/field-level-encryption")
    public Response createFieldLevelEncryptionConfig(String body) {
        try {
            FieldLevelEncryptionConfig cfg = parseFieldLevelEncryptionConfig(body);
            cfg = service.createFieldLevelEncryptionConfig(cfg);
            String xml = xmlFieldLevelEncryptionConfigResponse(cfg);
            return Response.created(URI.create("/2020-05-31/field-level-encryption/" + cfg.getId()))
                    .type(XML)
                    .header("ETag", cfg.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption/{Id}/config")
    public Response getFieldLevelEncryptionConfig(@PathParam("Id") String id) {
        try {
            FieldLevelEncryptionConfig cfg = service.getFieldLevelEncryptionConfig(id);
            return Response.ok(xmlFieldLevelEncryptionConfigResponse(cfg), XML)
                    .header("ETag", cfg.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/field-level-encryption/{Id}/config")
    public Response updateFieldLevelEncryptionConfig(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch,
                                                      String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            FieldLevelEncryptionConfig cfg = parseFieldLevelEncryptionConfig(body);
            cfg = service.updateFieldLevelEncryptionConfig(id, ifMatch, cfg);
            return Response.ok(xmlFieldLevelEncryptionConfigResponse(cfg), XML)
                    .header("ETag", cfg.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/field-level-encryption/{Id}")
    public Response deleteFieldLevelEncryptionConfig(@PathParam("Id") String id,
                                                      @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteFieldLevelEncryptionConfig(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption")
    public Response listFieldLevelEncryptionConfigs(@QueryParam("Marker") String marker,
                                                     @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<FieldLevelEncryptionConfig> page = page(
                    service.listFieldLevelEncryptionConfigs(
                            marker, paginationFetchLimit(maxItems)),
                    maxItems, FieldLevelEncryptionConfig::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("FieldLevelEncryptionList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (FieldLevelEncryptionConfig c : page.items()) {
                xml.raw(xmlFieldLevelEncryptionConfigResponse(c));
            }
            xml.end("Items").end("FieldLevelEncryptionList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Field-Level Encryption Profiles ──────────────────────────────────────

    @POST
    @Path("/field-level-encryption-profile")
    public Response createFieldLevelEncryptionProfile(String body) {
        try {
            FieldLevelEncryptionProfile profile = parseFieldLevelEncryptionProfile(body);
            profile = service.createFieldLevelEncryptionProfile(profile);
            String xml = xmlFieldLevelEncryptionProfileResponse(profile);
            return Response.created(
                            URI.create("/2020-05-31/field-level-encryption-profile/" + profile.getId()))
                    .type(XML)
                    .header("ETag", profile.getEtag())
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption-profile/{Id}")
    public Response getFieldLevelEncryptionProfile(@PathParam("Id") String id) {
        try {
            FieldLevelEncryptionProfile profile = service.getFieldLevelEncryptionProfile(id);
            return Response.ok(xmlFieldLevelEncryptionProfileResponse(profile), XML)
                    .header("ETag", profile.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @PUT
    @Path("/field-level-encryption-profile/{Id}/config")
    public Response updateFieldLevelEncryptionProfile(@PathParam("Id") String id,
                                                       @HeaderParam("If-Match") String ifMatch,
                                                       String body) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            FieldLevelEncryptionProfile profile = parseFieldLevelEncryptionProfile(body);
            profile = service.updateFieldLevelEncryptionProfile(id, ifMatch, profile);
            return Response.ok(xmlFieldLevelEncryptionProfileResponse(profile), XML)
                    .header("ETag", profile.getEtag()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/field-level-encryption-profile/{Id}")
    public Response deleteFieldLevelEncryptionProfile(@PathParam("Id") String id,
                                                       @HeaderParam("If-Match") String ifMatch) {
        try {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new AwsException("InvalidIfMatchVersion",
                        "The If-Match version is missing or not valid for the resource.", 400);
            }
            service.deleteFieldLevelEncryptionProfile(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/field-level-encryption-profile")
    public Response listFieldLevelEncryptionProfiles(@QueryParam("Marker") String marker,
                                                      @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            Page<FieldLevelEncryptionProfile> page = page(
                    service.listFieldLevelEncryptionProfiles(
                            marker, paginationFetchLimit(maxItems)),
                    maxItems, FieldLevelEncryptionProfile::getId);

            XmlBuilder xml = new XmlBuilder()
                    .start("FieldLevelEncryptionProfileList", NS)
                    .elem("NextMarker", page.nextMarker())
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", page.items().size())
                    .start("Items");
            for (FieldLevelEncryptionProfile p : page.items()) {
                xml.raw(xmlFieldLevelEncryptionProfileResponse(p));
            }
            xml.end("Items").end("FieldLevelEncryptionProfileList");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Monitoring Subscriptions ──────────────────────────────────────────────

    @POST
    @Path("/distributions/{DistributionId}/monitoring-subscription")
    public Response createMonitoringSubscription(@PathParam("DistributionId") String distributionId,
                                                  String body) {
        try {
            MonitoringSubscription sub = parseMonitoringSubscription(body);
            sub = service.createMonitoringSubscription(distributionId, sub);
            String xml = xmlMonitoringSubscriptionResponse(sub);
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/distributions/{DistributionId}/monitoring-subscription")
    public Response getMonitoringSubscription(@PathParam("DistributionId") String distributionId) {
        try {
            MonitoringSubscription sub = service.getMonitoringSubscription(distributionId);
            return Response.ok(xmlMonitoringSubscriptionResponse(sub), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/distributions/{DistributionId}/monitoring-subscription")
    public Response deleteMonitoringSubscription(@PathParam("DistributionId") String distributionId) {
        try {
            service.deleteMonitoringSubscription(distributionId);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String xmlDistribution(Distribution dist) {
        return new XmlBuilder()
                .start("Distribution", NS)
                .elem("Id", dist.getId())
                .elem("ARN", dist.getArn())
                .elem("Status", dist.getStatus())
                .elem("DomainName", dist.getDomainName())
                .elem("LastModifiedTime",
                        dist.getLastModifiedTime() != null ? dist.getLastModifiedTime().toString() : "")
                .raw(xmlActiveTrustedKeyGroups(dist.getConfig()))
                .start("ActiveTrustedSigners")
                .elem("Enabled", false)
                .elem("Quantity", 0)
                .end("ActiveTrustedSigners")
                .start("DistributionConfig")
                .raw(xmlDistributionConfigBody(dist.getConfig()))
                .end("DistributionConfig")
                .end("Distribution")
                .build();
    }

    private String xmlDistributionConfigBody(DistributionConfig cfg) {
        if (cfg == null) {
            return "";
        }
        XmlBuilder xml = new XmlBuilder()
                .elem("CallerReference", cfg.getCallerReference() != null ? cfg.getCallerReference() : "")
                .elem("Enabled", cfg.isEnabled())
                .elem("Comment", cfg.getComment() != null ? cfg.getComment() : "")
                .elem("DefaultRootObject", cfg.getDefaultRootObject() != null ? cfg.getDefaultRootObject() : "")
                .elem("HttpVersion", cfg.getHttpVersion() != null ? cfg.getHttpVersion() : "http2")
                .elem("PriceClass", cfg.getPriceClass() != null ? cfg.getPriceClass() : "PriceClass_All")
                .elem("IsIPV6Enabled", cfg.isIPV6Enabled())
                .elem("WebAclId", cfg.getWebAclId() != null ? cfg.getWebAclId() : "");

        List<Origin> origins = cfg.getOrigins();
        xml.raw(xmlQuantityItems("Origins", "Origin", origins != null ? origins.size() : 0,
                origins != null ? origins.stream().map(this::xmlOrigin).toList() : List.of()));
        xml.raw(xmlEmptyOriginGroups());

        if (cfg.getDefaultCacheBehavior() != null) {
            xml.raw(xmlDefaultCacheBehavior(cfg.getDefaultCacheBehavior()));
        }

        List<CacheBehavior> cacheBehaviors = cfg.getCacheBehaviors();
        int cbCount = cacheBehaviors != null ? cacheBehaviors.size() : 0;
        xml.start("CacheBehaviors").elem("Quantity", cbCount);
        if (cbCount > 0) {
            xml.start("Items");
            for (CacheBehavior cb : cacheBehaviors) {
                xml.raw(xmlCacheBehavior(cb));
            }
            xml.end("Items");
        }
        xml.end("CacheBehaviors");

        List<Map<String, Object>> customErrors = cfg.getCustomErrorResponses();
        int cerCount = customErrors != null ? customErrors.size() : 0;
        xml.start("CustomErrorResponses").elem("Quantity", cerCount);
        if (cerCount > 0) {
            xml.start("Items");
            for (Map<String, Object> cer : customErrors) {
                xml.start("CustomErrorResponse").elem("ErrorCode", str(cer.get("ErrorCode")));
                // ResponsePagePath and ResponseCode are optional; CloudFront omits them when unset
                // rather than returning empty elements.
                String pagePath = str(cer.get("ResponsePagePath"));
                if (!pagePath.isEmpty()) {
                    xml.elem("ResponsePagePath", pagePath);
                }
                String responseCode = str(cer.get("ResponseCode"));
                if (!responseCode.isEmpty()) {
                    xml.elem("ResponseCode", responseCode);
                }
                xml.elem("ErrorCachingMinTTL", cer.get("ErrorCachingMinTTL") != null
                                ? str(cer.get("ErrorCachingMinTTL")) : "0")
                        .end("CustomErrorResponse");
            }
            xml.end("Items");
        }
        xml.end("CustomErrorResponses");

        List<String> aliases = cfg.getAliases();
        int aliasCount = aliases != null ? aliases.size() : 0;
        xml.start("Aliases").elem("Quantity", aliasCount);
        if (aliasCount > 0) {
            xml.start("Items");
            for (String a : aliases) {
                xml.elem("CNAME", a);
            }
            xml.end("Items");
        }
        xml.end("Aliases");

        xml.raw(xmlViewerCertificate(cfg.getViewerCertificate()));
        xml.raw(xmlRestrictions(cfg.getGeoRestriction()));

        return xml.build();
    }

    private String xmlEmptyOriginGroups() {
        // Presence-only OriginGroups is intentional for Terraform compatibility; round-tripping groups is deferred.
        return new XmlBuilder()
                .start(ORIGIN_GROUPS)
                .elem(QUANTITY, EMPTY_QUANTITY)
                .end(ORIGIN_GROUPS)
                .build();
    }

    private String xmlRestrictions(Map<String, Object> geoRestriction) {
        String restrictionType = DEFAULT_GEO_RESTRICTION_TYPE;
        int quantity = EMPTY_QUANTITY;
        List<String> locations = List.of();
        if (geoRestriction != null) {
            restrictionType = String.valueOf(
                    geoRestriction.getOrDefault(RESTRICTION_TYPE, DEFAULT_GEO_RESTRICTION_TYPE));
            locations = stringList(geoRestriction.get(LOCATIONS));
            quantity = parseInt(geoRestriction.get(QUANTITY), EMPTY_QUANTITY);
            if (quantity == EMPTY_QUANTITY && !locations.isEmpty()) {
                quantity = locations.size();
            }
        }

        XmlBuilder xml = new XmlBuilder()
                .start(RESTRICTIONS)
                .start(GEO_RESTRICTION)
                .elem(RESTRICTION_TYPE, restrictionType)
                .elem(QUANTITY, quantity);
        if (!locations.isEmpty()) {
            xml.start(ITEMS);
            for (String location : locations) {
                xml.elem(LOCATION, location);
            }
            xml.end(ITEMS);
        }
        return xml.end(GEO_RESTRICTION)
                .end(RESTRICTIONS)
                .build();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String xmlOrigin(Origin o) {
        XmlBuilder xml = new XmlBuilder()
                .start("Origin")
                .elem("Id", o.getId())
                .elem("DomainName", o.getDomainName())
                .elem("OriginPath", o.getOriginPath() != null ? o.getOriginPath() : "")
                .elem("ConnectionAttempts", o.getConnectionAttempts())
                .elem("ConnectionTimeout", o.getConnectionTimeout());

        if (o.getOriginAccessControlId() != null && !o.getOriginAccessControlId().isEmpty()) {
            xml.elem("OriginAccessControlId", o.getOriginAccessControlId());
        }

        Map<String, String> s3Config = o.getS3OriginConfig();
        if (s3Config != null) {
            xml.start("S3OriginConfig")
                    .elem("OriginAccessIdentity", s3Config.getOrDefault("OriginAccessIdentity", ""))
                    .end("S3OriginConfig");
        } else if (o.getCustomOriginConfig() == null) {
            xml.start("S3OriginConfig").elem("OriginAccessIdentity", "").end("S3OriginConfig");
        }

        if (o.getCustomOriginConfig() != null) {
            Map<String, Object> coc = o.getCustomOriginConfig();
            xml.start("CustomOriginConfig")
                    .elem("HTTPPort", coc.getOrDefault("HTTPPort", "80").toString())
                    .elem("HTTPSPort", coc.getOrDefault("HTTPSPort", "443").toString())
                    .elem("OriginProtocolPolicy",
                            coc.getOrDefault("OriginProtocolPolicy", "https-only").toString())
                    .end("CustomOriginConfig");
        }

        List<Map<String, String>> customHeaders = o.getCustomHeaders();
        int customHeaderCount = customHeaders == null ? 0 : customHeaders.size();
        xml.start("CustomHeaders")
                .elem("Quantity", customHeaderCount);
        if (customHeaderCount > 0) {
            xml.start("Items");
            for (Map<String, String> header : customHeaders) {
                xml.start("OriginCustomHeader")
                        .elem("HeaderName", header.getOrDefault("HeaderName", ""))
                        .elem("HeaderValue", header.getOrDefault("HeaderValue", ""))
                        .end("OriginCustomHeader");
            }
            xml.end("Items");
        }
        xml.end("CustomHeaders");

        xml.end("Origin");
        return xml.build();
    }

    private String xmlDefaultCacheBehavior(DefaultCacheBehavior dcb) {
        XmlBuilder xml = new XmlBuilder()
                .start("DefaultCacheBehavior")
                .elem("TargetOriginId", dcb.getTargetOriginId())
                .elem("ViewerProtocolPolicy",
                        dcb.getViewerProtocolPolicy() != null ? dcb.getViewerProtocolPolicy() : "redirect-to-https")
                .elem("CachePolicyId", dcb.getCachePolicyId())
                .elem("OriginRequestPolicyId", dcb.getOriginRequestPolicyId())
                .elem("ResponseHeadersPolicyId", dcb.getResponseHeadersPolicyId())
                .elem("Compress", dcb.isCompress());

        xml.raw(xmlTrustedKeyGroups(
                dcb.isTrustedKeyGroupsEnabled(), dcb.getTrustedKeyGroups()));

        List<String> allowed = dcb.getAllowedMethods();
        if (allowed == null || allowed.isEmpty()) {
            allowed = List.of("GET", "HEAD");
        }
        xml.raw(xmlQuantityItems("AllowedMethods", "Method", allowed.size(),
                allowed.stream().map(m -> "<Method>" + XmlBuilder.escape(m) + "</Method>").toList()));

        xml.raw(xmlFunctionAssociations(dcb.getFunctionAssociations()));
        xml.raw(xmlLambdaFunctionAssociations(dcb.getLambdaFunctionAssociations()));

        xml.end("DefaultCacheBehavior");
        return xml.build();
    }

    private String xmlCacheBehavior(CacheBehavior cb) {
        XmlBuilder xml = new XmlBuilder()
                .start("CacheBehavior")
                .elem("PathPattern", cb.getPathPattern())
                .elem("TargetOriginId", cb.getTargetOriginId())
                .elem("ViewerProtocolPolicy",
                        cb.getViewerProtocolPolicy() != null ? cb.getViewerProtocolPolicy() : "redirect-to-https")
                .elem("CachePolicyId", cb.getCachePolicyId())
                .elem("OriginRequestPolicyId", cb.getOriginRequestPolicyId())
                .elem("ResponseHeadersPolicyId", cb.getResponseHeadersPolicyId())
                .elem("Compress", cb.isCompress());

        xml.raw(xmlTrustedKeyGroups(
                cb.isTrustedKeyGroupsEnabled(), cb.getTrustedKeyGroups()));

        List<String> allowed = cb.getAllowedMethods();
        if (allowed == null || allowed.isEmpty()) {
            allowed = List.of("GET", "HEAD");
        }
        xml.raw(xmlQuantityItems("AllowedMethods", "Method", allowed.size(),
                allowed.stream().map(m -> "<Method>" + XmlBuilder.escape(m) + "</Method>").toList()));

        xml.raw(xmlFunctionAssociations(cb.getFunctionAssociations()));
        xml.raw(xmlLambdaFunctionAssociations(cb.getLambdaFunctionAssociations()));

        xml.end("CacheBehavior");
        return xml.build();
    }

    private String xmlLambdaFunctionAssociations(List<Map<String, Object>> associations) {
        List<Map<String, Object>> list = associations != null ? associations : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("LambdaFunctionAssociations")
                .elem("Quantity", list.size());
        if (!list.isEmpty()) {
            xml.start("Items");
            for (Map<String, Object> a : list) {
                xml.start("LambdaFunctionAssociation")
                        .elem("LambdaFunctionARN", str(a.get("LambdaFunctionARN")))
                        .elem("EventType", str(a.get("EventType")))
                        .elem("IncludeBody", Boolean.TRUE.equals(a.get("IncludeBody")))
                        .end("LambdaFunctionAssociation");
            }
            xml.end("Items");
        }
        return xml.end("LambdaFunctionAssociations").build();
    }

    private String xmlFunctionAssociations(List<Map<String, String>> associations) {
        List<Map<String, String>> list = associations != null ? associations : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("FunctionAssociations")
                .elem("Quantity", list.size());
        if (!list.isEmpty()) {
            xml.start("Items");
            for (Map<String, String> a : list) {
                xml.start("FunctionAssociation")
                        .elem("FunctionARN", str(a.get("FunctionARN")))
                        .elem("EventType", str(a.get("EventType")))
                        .end("FunctionAssociation");
            }
            xml.end("Items");
        }
        return xml.end("FunctionAssociations").build();
    }

    private String xmlTrustedKeyGroups(
            boolean enabled, List<String> trustedKeyGroups) {
        List<String> keyGroups = trustedKeyGroups != null ? trustedKeyGroups : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("TrustedKeyGroups")
                .elem("Enabled", enabled)
                .elem("Quantity", keyGroups.size());
        if (!keyGroups.isEmpty()) {
            xml.start("Items");
            for (String keyGroup : keyGroups) {
                xml.elem("KeyGroup", keyGroup);
            }
            xml.end("Items");
        }
        return xml.end("TrustedKeyGroups").build();
    }

    private String xmlActiveTrustedKeyGroups(DistributionConfig config) {
        List<KeyGroup> groups = service.activeTrustedKeyGroups(config);
        XmlBuilder xml = new XmlBuilder()
                .start("ActiveTrustedKeyGroups")
                .elem("Enabled", !groups.isEmpty())
                .elem("Quantity", groups.size());
        if (!groups.isEmpty()) {
            xml.start("Items");
            for (KeyGroup group : groups) {
                List<String> keyPairIds =
                        group.getItems() != null ? group.getItems() : List.of();
                xml.start("KeyGroup")
                        .elem("KeyGroupId", group.getId())
                        .start("KeyPairIds")
                        .elem("Quantity", keyPairIds.size());
                if (!keyPairIds.isEmpty()) {
                    xml.start("Items");
                    for (String keyPairId : keyPairIds) {
                        xml.elem("KeyPairId", keyPairId);
                    }
                    xml.end("Items");
                }
                xml.end("KeyPairIds").end("KeyGroup");
            }
            xml.end("Items");
        }
        return xml.end("ActiveTrustedKeyGroups").build();
    }

    private String xmlViewerCertificate(Map<String, String> vc) {
        XmlBuilder xml = new XmlBuilder().start("ViewerCertificate");
        if (vc != null && !vc.isEmpty()) {
            for (Map.Entry<String, String> entry : vc.entrySet()) {
                xml.elem(entry.getKey(), entry.getValue());
            }
        } else {
            xml.elem("CloudFrontDefaultCertificate", "true")
                    .elem("MinimumProtocolVersion", "TLSv1.2_2021");
        }
        xml.end("ViewerCertificate");
        return xml.build();
    }

    private String xmlDistributionSummary(Distribution d) {
        XmlBuilder xml = new XmlBuilder()
                .start("DistributionSummary")
                .elem("Id", d.getId())
                .elem("ARN", d.getArn())
                .elem("Status", d.getStatus())
                .elem("DomainName", d.getDomainName())
                .elem("LastModifiedTime",
                        d.getLastModifiedTime() != null ? d.getLastModifiedTime().toString() : "")
                .elem("Comment", d.getConfig() != null && d.getConfig().getComment() != null
                        ? d.getConfig().getComment() : "")
                .elem("Enabled", d.getConfig() != null && d.getConfig().isEnabled())
                .elem("HttpVersion", d.getConfig() != null && d.getConfig().getHttpVersion() != null
                        ? d.getConfig().getHttpVersion() : "http2")
                .elem("PriceClass", d.getConfig() != null && d.getConfig().getPriceClass() != null
                        ? d.getConfig().getPriceClass() : "PriceClass_All")
                .elem("IsIPV6Enabled", d.getConfig() != null && d.getConfig().isIPV6Enabled())
                .elem("WebAclId", "");

        DistributionConfig cfg = d.getConfig();
        List<Origin> origins = cfg != null ? cfg.getOrigins() : null;
        xml.raw(xmlQuantityItems("Origins", "Origin",
                origins != null ? origins.size() : 0,
                origins != null ? origins.stream().map(this::xmlOrigin).toList()
                        : List.of()));
        xml.raw(xmlEmptyOriginGroups());

        List<String> aliases = cfg != null ? cfg.getAliases() : null;
        int aliasCount = aliases != null ? aliases.size() : 0;
        xml.start("Aliases").elem("Quantity", aliasCount);
        if (aliasCount > 0) {
            xml.start("Items");
            for (String a : aliases) {
                xml.elem("CNAME", a);
            }
            xml.end("Items");
        }
        xml.end("Aliases");

        xml.raw(xmlViewerCertificate(cfg != null ? cfg.getViewerCertificate() : null));
        xml.raw(xmlRestrictions(cfg != null ? cfg.getGeoRestriction() : null));

        xml.end("DistributionSummary");
        return xml.build();
    }

    private String xmlInvalidationBody(Invalidation inv) {
        XmlBuilder xml = new XmlBuilder()
                .elem("Id", inv.getId())
                .elem("Status", inv.getStatus())
                .elem("CreateTime", inv.getCreateTime() != null ? inv.getCreateTime().toString() : "")
                .start("InvalidationBatch");
        List<String> paths = inv.getPaths();
        int pathCount = paths != null ? paths.size() : 0;
        xml.start("Paths").elem("Quantity", pathCount);
        if (pathCount > 0) {
            xml.start("Items");
            for (String p : paths) {
                xml.elem("Path", p);
            }
            xml.end("Items");
        }
        xml.end("Paths")
                .elem("CallerReference", inv.getCallerReference() != null ? inv.getCallerReference() : "")
                .end("InvalidationBatch");
        return xml.build();
    }

    private String xmlCachePolicyResponse(CachePolicy policy) {
        return new XmlBuilder()
                .start("CachePolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "")
                .start("CachePolicyConfig")
                .elem("Name", policy.getName())
                .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                .end("CachePolicyConfig")
                .end("CachePolicy")
                .build();
    }

    private String xmlOriginRequestPolicyResponse(OriginRequestPolicy policy) {
        return new XmlBuilder()
                .start("OriginRequestPolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "")
                .start("OriginRequestPolicyConfig")
                .elem("Name", policy.getName())
                .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                .end("OriginRequestPolicyConfig")
                .end("OriginRequestPolicy")
                .build();
    }

    private String xmlResponseHeadersPolicyResponse(ResponseHeadersPolicy policy) {
        XmlBuilder xml = new XmlBuilder()
                .start("ResponseHeadersPolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "")
                .start("ResponseHeadersPolicyConfig")
                .elem("Name", policy.getName())
                .elem("Comment", policy.getComment() != null ? policy.getComment() : "");
        ResponseHeadersPolicyConfigCodec.serialize(xml, policy.getConfig());
        return xml.end("ResponseHeadersPolicyConfig")
                .end("ResponseHeadersPolicy")
                .build();
    }

    private String xmlOriginAccessControlResponse(OriginAccessControl oac) {
        return new XmlBuilder()
                .start("OriginAccessControl")
                .elem("Id", oac.getId())
                .start("OriginAccessControlConfig")
                .elem("Name", oac.getName())
                .elem("Description", oac.getDescription() != null ? oac.getDescription() : "")
                .elem("SigningProtocol", oac.getSigningProtocol())
                .elem("SigningBehavior", oac.getSigningBehavior())
                .elem("OriginAccessControlOriginType", oac.getOriginAccessControlOriginType())
                .end("OriginAccessControlConfig")
                .end("OriginAccessControl")
                .build();
    }

    private String xmlOriginAccessControlSummary(OriginAccessControl oac) {
        return new XmlBuilder()
                .start("OriginAccessControlSummary")
                .elem("Id", oac.getId())
                .elem("Name", oac.getName())
                .elem("Description", oac.getDescription() != null ? oac.getDescription() : "")
                .elem("SigningProtocol", oac.getSigningProtocol())
                .elem("SigningBehavior", oac.getSigningBehavior())
                .elem("OriginAccessControlOriginType", oac.getOriginAccessControlOriginType())
                .elem("LastModifiedTime",
                        oac.getLastModifiedTime() != null ? oac.getLastModifiedTime().toString() : "")
                .end("OriginAccessControlSummary")
                .build();
    }

    private String xmlOaiResponse(CloudFrontOriginAccessIdentity oai) {
        return new XmlBuilder()
                .start("CloudFrontOriginAccessIdentity")
                .elem("Id", oai.getId())
                .elem("S3CanonicalUserId", oai.getS3CanonicalUserId())
                .start("CloudFrontOriginAccessIdentityConfig")
                .elem("CallerReference", oai.getCallerReference())
                .elem("Comment", oai.getComment() != null ? oai.getComment() : "")
                .end("CloudFrontOriginAccessIdentityConfig")
                .end("CloudFrontOriginAccessIdentity")
                .build();
    }

    private String xmlFunctionResponse(CloudFrontFunction fn) {
        return new XmlBuilder()
                .start("FunctionSummary")
                .elem("Name", fn.getName())
                .elem("Status", fn.getStatus())
                .start("FunctionConfig")
                .elem("Comment", fn.getComment() != null ? fn.getComment() : "")
                .elem("Runtime", fn.getRuntime() != null ? fn.getRuntime() : "cloudfront-js-2.0")
                .end("FunctionConfig")
                .start("FunctionMetadata")
                .elem("FunctionARN",
                        AwsArnUtils.Arn.of("cloudfront", "", service.getAccountId(), "function/" + fn.getName()).toString())
                .elem("Stage", fn.getStage())
                .elem("CreatedTime", fn.getCreatedTime() != null ? fn.getCreatedTime().toString() : "")
                .elem("LastModifiedTime",
                        fn.getLastModifiedTime() != null ? fn.getLastModifiedTime().toString() : "")
                .end("FunctionMetadata")
                .end("FunctionSummary")
                .build();
    }

    /** Renders a possibly-null value as a string, using the empty string for {@code null}. */
    private static String str(Object value) {
        return value != null ? value.toString() : "";
    }

    private String xmlQuantityItems(String wrapper, String itemTag, int count, List<String> items) {
        XmlBuilder xml = new XmlBuilder().start(wrapper).elem("Quantity", count);
        if (count > 0 && items != null && !items.isEmpty()) {
            xml.start("Items");
            for (String item : items) {
                xml.raw(item);
            }
            xml.end("Items");
        }
        xml.end(wrapper);
        return xml.build();
    }

    private String xmlDirectItems(String wrapper, String itemTag, List<String> items) {
        XmlBuilder xml = new XmlBuilder().start(wrapper);
        for (String item : items) {
            xml.elem(itemTag, item);
        }
        return xml.end(wrapper).build();
    }

    private static int paginationFetchLimit(int maxItems) {
        return maxItems > 0 && maxItems < Integer.MAX_VALUE ? maxItems + 1 : maxItems;
    }

    private static <T> Page<T> page(List<T> candidates, int maxItems,
                                    Function<T, String> markerFunction) {
        boolean truncated = maxItems > 0 && candidates.size() > maxItems;
        List<T> items = truncated ? candidates.subList(0, maxItems) : candidates;
        String nextMarker = truncated && !items.isEmpty()
                ? markerFunction.apply(items.get(items.size() - 1)) : null;
        return new Page<>(items, truncated, nextMarker);
    }

    private record Page<T>(List<T> items, boolean truncated, String nextMarker) {}

    private Response xmlErrorResponse(AwsException e) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", NS)
                .start("Error")
                .elem("Type", "Client")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .end("Error")
                .elem("RequestId", "00000000-0000-0000-0000-000000000000")
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus()).type(XML).entity(xml).build();
    }

    // ── Request parsers ───────────────────────────────────────────────────────

    private DistributionConfig parseDistributionConfig(String body) {
        Map<String, String> values =
                parseDistributionConfigTopLevelValues(body);
        DistributionConfig cfg = new DistributionConfig();
        cfg.setCallerReference(values.get("CallerReference"));
        cfg.setEnabled("true".equalsIgnoreCase(
                values.getOrDefault("Enabled", "true")));
        cfg.setComment(values.getOrDefault("Comment", ""));
        cfg.setDefaultRootObject(
                values.getOrDefault("DefaultRootObject", ""));
        cfg.setHttpVersion(values.getOrDefault("HttpVersion", "http2"));
        cfg.setPriceClass(
                values.getOrDefault("PriceClass", "PriceClass_All"));
        cfg.setIPV6Enabled("true".equalsIgnoreCase(
                values.getOrDefault("IsIPV6Enabled", "true")));
        cfg.setWebAclId(values.get("WebAclId"));
        cfg.setContinuousDeploymentPolicyId(
                values.get("ContinuousDeploymentPolicyId"));
        cfg.setStaging("true".equalsIgnoreCase(
                values.getOrDefault("Staging", "false")));

        cfg.setOrigins(parseOrigins(body));
        cfg.setDefaultCacheBehavior(parseDefaultCacheBehavior(body));
        cfg.setCacheBehaviors(parseCacheBehaviors(body));
        cfg.setAliases(parseAliases(body));
        cfg.setViewerCertificate(parseViewerCertificate(body));
        cfg.setCustomErrorResponses(parseCustomErrorResponses(body));
        cfg.setGeoRestriction(parseGeoRestriction(body));

        return cfg;
    }

    private Map<String, String> parseDistributionConfigTopLevelValues(
            String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return values;
        }
        try {
            XMLStreamReader reader = XML_FACTORY.createXMLStreamReader(
                    new StringReader(body));
            boolean inDistributionConfig = false;
            int nestedDepth = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if (!inDistributionConfig
                            && "DistributionConfig".equals(local)) {
                        inDistributionConfig = true;
                        continue;
                    }
                    if (!inDistributionConfig) {
                        continue;
                    }
                    nestedDepth++;
                    if (nestedDepth == 1
                            && isDistributionConfigScalar(local)) {
                        values.put(local, reader.getElementText());
                        nestedDepth--;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && inDistributionConfig) {
                    if (nestedDepth == 0
                            && "DistributionConfig".equals(
                                    reader.getLocalName())) {
                        inDistributionConfig = false;
                    } else {
                        nestedDepth--;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            LOG.debugv(
                    "Invalid DistributionConfig XML: {0}",
                    e.getMessage());
            throw new AwsException(
                    "InvalidArgument",
                    "The DistributionConfig XML is invalid.",
                    400);
        }
        return values;
    }

    private static boolean isDistributionConfigScalar(String local) {
        return switch (local) {
            case "CallerReference",
                    "Enabled",
                    "Comment",
                    "DefaultRootObject",
                    "HttpVersion",
                    "PriceClass",
                    "IsIPV6Enabled",
                    "WebAclId",
                    "ContinuousDeploymentPolicyId",
                    "Staging" -> true;
            default -> false;
        };
    }

    /**
     * Parses the {@code CustomErrorResponses} block into a list of maps keyed by
     * {@code ErrorCode}, {@code ResponsePagePath}, {@code ResponseCode} and {@code ErrorCachingMinTTL}
     * (values kept as strings). CloudFront uses these to override an origin error with a custom
     * page/status — most notably the SPA fallback that turns a 403/404 into 200 {@code /index.html}.
     */
    private List<Map<String, Object>> parseCustomErrorResponses(String body) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inBlock = false;
            boolean inItem = false;
            Map<String, Object> current = null;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "CustomErrorResponses" -> inBlock = true;
                        case "CustomErrorResponse" -> {
                            if (inBlock) {
                                inItem = true;
                                current = new LinkedHashMap<>();
                            }
                        }
                        case "ErrorCode", "ResponsePagePath", "ResponseCode", "ErrorCachingMinTTL" -> {
                            if (inItem && current != null) {
                                current.put(local, r.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "CustomErrorResponse" -> {
                            if (inItem && current != null) {
                                result.add(current);
                            }
                            inItem = false;
                            current = null;
                        }
                        case "CustomErrorResponses" -> inBlock = false;
                        default -> {
                        }
                    }
                }
            }
            r.close();
        } catch (Exception e) {
            // A malformed CustomErrorResponses block yields the entries parsed so far rather than
            // failing the whole distribution create/update; log it so the cause is diagnosable.
            LOG.debugv("Ignoring malformed CustomErrorResponses during parse: {0}", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> parseGeoRestriction(String body) {
        List<Map<String, String>> groups = XmlParser.extractGroups(body, GEO_RESTRICTION);
        if (groups.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> geoRestriction = new LinkedHashMap<>(groups.getFirst());
        List<String> locations = XmlParser.extractAll(body, LOCATION);
        if (!locations.isEmpty()) {
            geoRestriction.put(LOCATIONS, locations);
        }
        return geoRestriction;
    }

    private List<Origin> parseOrigins(String body) {
        List<Origin> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inOrigins = false;
            boolean inOrigin = false;
            boolean inS3OriginConfig = false;
            boolean inCustomOriginConfig = false;
            Origin current = null;
            Map<String, String> s3Config = null;
            Map<String, Object> customConfig = null;
            boolean inCustomHeaders = false;
            boolean inCustomHeaderItems = false;
            boolean customHeaderItemsSeen = false;
            List<Map<String, String>> customHeaders = null;
            Map<String, String> currentHeader = null;
            Integer customHeadersQuantity = null;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (inCustomHeaders) {
                        boolean validElement = switch (local) {
                            case "Quantity" -> !inCustomHeaderItems
                                    && currentHeader == null
                                    && customHeadersQuantity == null;
                            case "Items" -> !inCustomHeaderItems
                                    && currentHeader == null
                                    && !customHeaderItemsSeen;
                            case "OriginCustomHeader" ->
                                    inCustomHeaderItems && currentHeader == null;
                            case "HeaderName" -> currentHeader != null
                                    && !currentHeader.containsKey("HeaderName");
                            case "HeaderValue" -> currentHeader != null
                                    && !currentHeader.containsKey("HeaderValue");
                            default -> false;
                        };
                        if (!validElement) {
                            throw invalidOriginCustomHeadersStructure();
                        }
                    }
                    switch (local) {
                        case "Origins" -> inOrigins = true;
                        case "Origin" -> {
                            if (inOrigins) {
                                inOrigin = true;
                                current = new Origin();
                            }
                        }
                        case "S3OriginConfig" -> {
                            if (inOrigin) {
                                inS3OriginConfig = true;
                                s3Config = new LinkedHashMap<>();
                            }
                        }
                        case "CustomOriginConfig" -> {
                            if (inOrigin) {
                                inCustomOriginConfig = true;
                                customConfig = new LinkedHashMap<>();
                            }
                        }
                        case "Id" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig && current != null) {
                                current.setId(r.getElementText());
                            }
                        }
                        case "DomainName" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig && current != null) {
                                current.setDomainName(r.getElementText());
                            }
                        }
                        case "OriginPath" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig && current != null) {
                                current.setOriginPath(r.getElementText());
                            }
                        }
                        case "OriginAccessControlId" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig && current != null) {
                                current.setOriginAccessControlId(r.getElementText());
                            }
                        }
                        case "ConnectionAttempts" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig && current != null) {
                                try {
                                    current.setConnectionAttempts(Integer.parseInt(r.getElementText()));
                                } catch (NumberFormatException e) {
                                    LOG.debugv("Ignoring malformed ConnectionAttempts during parse: {0}", e.getMessage());
                                }
                            }
                        }
                        case "ConnectionTimeout" -> {
                            if (inOrigin && !inS3OriginConfig && !inCustomOriginConfig && current != null) {
                                try {
                                    current.setConnectionTimeout(Integer.parseInt(r.getElementText()));
                                } catch (NumberFormatException e) {
                                    LOG.debugv("Ignoring malformed ConnectionTimeout during parse: {0}", e.getMessage());
                                }
                            }
                        }
                        case "OriginAccessIdentity" -> {
                            if (inS3OriginConfig && s3Config != null) {
                                s3Config.put("OriginAccessIdentity", r.getElementText());
                            }
                        }
                        case "HTTPPort" -> {
                            if (inCustomOriginConfig && customConfig != null) {
                                customConfig.put("HTTPPort", r.getElementText());
                            }
                        }
                        case "HTTPSPort" -> {
                            if (inCustomOriginConfig && customConfig != null) {
                                customConfig.put("HTTPSPort", r.getElementText());
                            }
                        }
                        case "OriginProtocolPolicy" -> {
                            if (inCustomOriginConfig && customConfig != null) {
                                customConfig.put("OriginProtocolPolicy", r.getElementText());
                            }
                        }
                        case "CustomHeaders" -> {
                            if (inOrigin) {
                                inCustomHeaders = true;
                                inCustomHeaderItems = false;
                                customHeaderItemsSeen = false;
                                customHeaders = new ArrayList<>();
                                customHeadersQuantity = null;
                            }
                        }
                        case "Quantity" -> {
                            if (inCustomHeaders && currentHeader == null) {
                                try {
                                    customHeadersQuantity = Integer.parseInt(r.getElementText());
                                } catch (NumberFormatException e) {
                                    throw inconsistentQuantities();
                                }
                            }
                        }
                        case "Items" -> {
                            if (inCustomHeaders) {
                                inCustomHeaderItems = true;
                                customHeaderItemsSeen = true;
                            }
                        }
                        case "OriginCustomHeader" -> {
                            if (inCustomHeaders) {
                                currentHeader = new LinkedHashMap<>();
                            }
                        }
                        case "HeaderName" -> {
                            if (inCustomHeaders && currentHeader != null) {
                                currentHeader.put("HeaderName", r.getElementText());
                            }
                        }
                        case "HeaderValue" -> {
                            if (inCustomHeaders && currentHeader != null) {
                                currentHeader.put("HeaderValue", r.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "S3OriginConfig" -> {
                            if (inS3OriginConfig && current != null) {
                                current.setS3OriginConfig(s3Config);
                            }
                            inS3OriginConfig = false;
                            s3Config = null;
                        }
                        case "CustomOriginConfig" -> {
                            if (inCustomOriginConfig && current != null) {
                                current.setCustomOriginConfig(customConfig);
                            }
                            inCustomOriginConfig = false;
                            customConfig = null;
                        }
                        case "OriginCustomHeader" -> {
                            if (inCustomHeaders && currentHeader != null && customHeaders != null) {
                                customHeaders.add(currentHeader);
                            }
                            currentHeader = null;
                        }
                        case "Items" -> {
                            if (inCustomHeaders) {
                                if (currentHeader != null) {
                                    throw invalidOriginCustomHeadersStructure();
                                }
                                inCustomHeaderItems = false;
                            }
                        }
                        case "CustomHeaders" -> {
                            if (inCustomHeaders && current != null) {
                                int itemCount = customHeaders == null ? 0 : customHeaders.size();
                                if (customHeadersQuantity == null || customHeadersQuantity != itemCount) {
                                    throw inconsistentQuantities();
                                }
                                if (itemCount > 0 && !customHeaderItemsSeen) {
                                    throw invalidOriginCustomHeadersStructure();
                                }
                                current.setCustomHeaders(customHeaders);
                            }
                            inCustomHeaders = false;
                            inCustomHeaderItems = false;
                            customHeaderItemsSeen = false;
                            customHeaders = null;
                            customHeadersQuantity = null;
                        }
                        case "Origin" -> {
                            if (inOrigin && current != null) {
                                result.add(current);
                            }
                            inOrigin = false;
                            current = null;
                        }
                        case "Origins" -> inOrigins = false;
                        default -> {
                        }
                    }
                }
            }
            r.close();
            return result;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv("Rejecting malformed Origins during parse: {0}", e.getMessage());
            throw new AwsException(
                    "InvalidArgument",
                    "The origin configuration is invalid.",
                    400);
        }
    }

    private static AwsException inconsistentQuantities() {
        return new AwsException(
                "InconsistentQuantities",
                "The value of Quantity and the size of Items do not match.",
                400);
    }

    private static AwsException invalidOriginCustomHeadersStructure() {
        return new AwsException(
                "InvalidArgument",
                "The origin custom headers structure is invalid.",
                400);
    }

    // Event types accepted by AWS for each association kind. Lambda@Edge runs at all
    // four points; CloudFront Functions only at the viewer edge.
    private static final Set<String> LAMBDA_EVENT_TYPES = Set.of(
            "viewer-request", "viewer-response", "origin-request", "origin-response");
    private static final Set<String> FUNCTION_EVENT_TYPES = Set.of(
            "viewer-request", "viewer-response");

    private void validateLambdaFunctionAssociations(List<Map<String, Object>> associations) {
        for (Map<String, Object> a : associations) {
            if (str(a.get("LambdaFunctionARN")).isEmpty()) {
                throw new AwsException("InvalidArgument",
                        "The Lambda function association must include a LambdaFunctionARN.", 400);
            }
            if (!LAMBDA_EVENT_TYPES.contains(str(a.get("EventType")))) {
                throw new AwsException("InvalidArgument",
                        "The event type for the Lambda function association is not valid.", 400);
            }
        }
    }

    private void validateFunctionAssociations(List<Map<String, String>> associations) {
        for (Map<String, String> a : associations) {
            if (str(a.get("FunctionARN")).isEmpty()) {
                throw new AwsException("InvalidArgument",
                        "The CloudFront function association must include a FunctionARN.", 400);
            }
            if (!FUNCTION_EVENT_TYPES.contains(str(a.get("EventType")))) {
                throw new AwsException("InvalidArgument",
                        "The event type for the CloudFront function association is not valid.", 400);
            }
        }
    }

    private static int parseAssociationsQuantity(String value) {
        try {
            int quantity = Integer.parseInt(value.trim());
            if (quantity < 0) {
                throw new NumberFormatException("negative quantity");
            }
            return quantity;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidArgument",
                    "The association Quantity must be a non-negative integer.", 400);
        }
    }

    private static void validateAssociationsQuantity(Integer declared, int itemCount) {
        if (declared != null && declared != itemCount) {
            throw inconsistentQuantities();
        }
    }

    private DefaultCacheBehavior parseDefaultCacheBehavior(String body) {
        DefaultCacheBehavior dcb = new DefaultCacheBehavior();
        if (body == null || body.isEmpty()) {
            return dcb;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inDcb = false;
            boolean inAllowedMethods = false;
            boolean inTrustedKeyGroups = false;
            boolean sawTrustedKeyGroups = false;
            Boolean trustedKeyGroupsEnabled = null;
            Integer trustedKeyGroupsQuantity = null;
            List<String> allowedMethods = new ArrayList<>();
            List<String> trustedKeyGroups = new ArrayList<>();
            boolean inLambdaAssociations = false;
            boolean inFunctionAssociations = false;
            Map<String, Object> currentLambdaAssociation = null;
            Map<String, String> currentFunctionAssociation = null;
            List<Map<String, Object>> lambdaAssociations = new ArrayList<>();
            List<Map<String, String>> functionAssociations = new ArrayList<>();
            Integer lambdaAssociationsQuantity = null;
            Integer functionAssociationsQuantity = null;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "DefaultCacheBehavior" -> inDcb = true;
                        case "LambdaFunctionAssociations" -> {
                            if (inDcb) {
                                inLambdaAssociations = true;
                            }
                        }
                        case "FunctionAssociations" -> {
                            if (inDcb) {
                                inFunctionAssociations = true;
                            }
                        }
                        case "LambdaFunctionAssociation" -> {
                            if (inLambdaAssociations) {
                                currentLambdaAssociation = new LinkedHashMap<>();
                            }
                        }
                        case "FunctionAssociation" -> {
                            if (inFunctionAssociations) {
                                currentFunctionAssociation = new LinkedHashMap<>();
                            }
                        }
                        case "LambdaFunctionARN" -> {
                            if (currentLambdaAssociation != null) {
                                currentLambdaAssociation.put("LambdaFunctionARN", r.getElementText());
                            }
                        }
                        case "FunctionARN" -> {
                            if (currentFunctionAssociation != null) {
                                currentFunctionAssociation.put("FunctionARN", r.getElementText());
                            }
                        }
                        case "IncludeBody" -> {
                            if (currentLambdaAssociation != null) {
                                currentLambdaAssociation.put(
                                        "IncludeBody", "true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                        case "EventType" -> {
                            if (currentLambdaAssociation != null) {
                                currentLambdaAssociation.put("EventType", r.getElementText());
                            } else if (currentFunctionAssociation != null) {
                                currentFunctionAssociation.put("EventType", r.getElementText());
                            }
                        }
                        case "TrustedKeyGroups" -> {
                            if (inDcb) {
                                inTrustedKeyGroups = true;
                                sawTrustedKeyGroups = true;
                            }
                        }
                        case "Enabled" -> {
                            if (inTrustedKeyGroups) {
                                trustedKeyGroupsEnabled =
                                        parseTrustedKeyGroupsEnabled(r.getElementText());
                            }
                        }
                        case "Quantity" -> {
                            if (inTrustedKeyGroups) {
                                trustedKeyGroupsQuantity =
                                        parseTrustedKeyGroupsQuantity(r.getElementText());
                            } else if (inLambdaAssociations && currentLambdaAssociation == null) {
                                lambdaAssociationsQuantity =
                                        parseAssociationsQuantity(r.getElementText());
                            } else if (inFunctionAssociations && currentFunctionAssociation == null) {
                                functionAssociationsQuantity =
                                        parseAssociationsQuantity(r.getElementText());
                            }
                        }
                        case "KeyGroup" -> {
                            if (inTrustedKeyGroups) {
                                trustedKeyGroups.add(r.getElementText());
                            }
                        }
                        case "AllowedMethods" -> {
                            if (inDcb) inAllowedMethods = true;
                        }
                        case "TargetOriginId" -> {
                            if (inDcb) dcb.setTargetOriginId(r.getElementText());
                        }
                        case "ViewerProtocolPolicy" -> {
                            if (inDcb) dcb.setViewerProtocolPolicy(r.getElementText());
                        }
                        case "CachePolicyId" -> {
                            if (inDcb) dcb.setCachePolicyId(r.getElementText());
                        }
                        case "OriginRequestPolicyId" -> {
                            if (inDcb) dcb.setOriginRequestPolicyId(r.getElementText());
                        }
                        case "ResponseHeadersPolicyId" -> {
                            if (inDcb) dcb.setResponseHeadersPolicyId(r.getElementText());
                        }
                        case "FieldLevelEncryptionId" -> {
                            if (inDcb) dcb.setFieldLevelEncryptionId(r.getElementText());
                        }
                        case "RealtimeLogConfigArn" -> {
                            if (inDcb) dcb.setRealtimeLogConfigArn(r.getElementText());
                        }
                        case "Compress" -> {
                            if (inDcb) dcb.setCompress("true".equalsIgnoreCase(r.getElementText()));
                        }
                        case "Method" -> {
                            if (inAllowedMethods) allowedMethods.add(r.getElementText());
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "AllowedMethods" -> inAllowedMethods = false;
                        case "TrustedKeyGroups" -> inTrustedKeyGroups = false;
                        case "DefaultCacheBehavior" -> inDcb = false;
                        case "LambdaFunctionAssociations" -> inLambdaAssociations = false;
                        case "FunctionAssociations" -> inFunctionAssociations = false;
                        case "LambdaFunctionAssociation" -> {
                            if (currentLambdaAssociation != null) {
                                lambdaAssociations.add(currentLambdaAssociation);
                                currentLambdaAssociation = null;
                            }
                        }
                        case "FunctionAssociation" -> {
                            if (currentFunctionAssociation != null) {
                                functionAssociations.add(currentFunctionAssociation);
                                currentFunctionAssociation = null;
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
            r.close();
            if (!allowedMethods.isEmpty()) {
                dcb.setAllowedMethods(allowedMethods);
            }
            if (!trustedKeyGroups.isEmpty()) {
                dcb.setTrustedKeyGroups(trustedKeyGroups);
            }
            validateAssociationsQuantity(lambdaAssociationsQuantity, lambdaAssociations.size());
            validateAssociationsQuantity(functionAssociationsQuantity, functionAssociations.size());
            validateLambdaFunctionAssociations(lambdaAssociations);
            validateFunctionAssociations(functionAssociations);
            if (!lambdaAssociations.isEmpty()) {
                dcb.setLambdaFunctionAssociations(lambdaAssociations);
            }
            if (!functionAssociations.isEmpty()) {
                dcb.setFunctionAssociations(functionAssociations);
            }
            if (sawTrustedKeyGroups) {
                if (trustedKeyGroupsEnabled == null) {
                    throw new AwsException(
                            "InvalidArgument",
                            "TrustedKeyGroups must include Enabled.",
                            400);
                }
                validateTrustedKeyGroupsQuantity(
                        trustedKeyGroupsQuantity, trustedKeyGroups.size());
                validateEnabledTrustedKeyGroups(
                        trustedKeyGroupsEnabled, trustedKeyGroups.size());
                dcb.setTrustedKeyGroupsEnabled(trustedKeyGroupsEnabled);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv("Invalid DefaultCacheBehavior XML: {0}", e.getMessage());
            throw new AwsException(
                    "InvalidArgument", "The DefaultCacheBehavior configuration is invalid.", 400);
        }
        return dcb;
    }

    private List<CacheBehavior> parseCacheBehaviors(String body) {
        List<CacheBehavior> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inCacheBehaviors = false;
            boolean inCacheBehavior = false;
            boolean inAllowedMethods = false;
            boolean inTrustedKeyGroups = false;
            CacheBehavior current = null;
            boolean sawTrustedKeyGroups = false;
            Boolean trustedKeyGroupsEnabled = null;
            Integer trustedKeyGroupsQuantity = null;
            List<String> allowedMethods = new ArrayList<>();
            List<String> trustedKeyGroups = new ArrayList<>();
            boolean inLambdaAssociations = false;
            boolean inFunctionAssociations = false;
            Map<String, Object> currentLambdaAssociation = null;
            Map<String, String> currentFunctionAssociation = null;
            List<Map<String, Object>> lambdaAssociations = new ArrayList<>();
            List<Map<String, String>> functionAssociations = new ArrayList<>();
            Integer lambdaAssociationsQuantity = null;
            Integer functionAssociationsQuantity = null;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "CacheBehaviors" -> inCacheBehaviors = true;
                        case "CacheBehavior" -> {
                            if (inCacheBehaviors) {
                                inCacheBehavior = true;
                                current = new CacheBehavior();
                                sawTrustedKeyGroups = false;
                                trustedKeyGroupsEnabled = null;
                                trustedKeyGroupsQuantity = null;
                                allowedMethods = new ArrayList<>();
                                trustedKeyGroups = new ArrayList<>();
                                lambdaAssociations = new ArrayList<>();
                                functionAssociations = new ArrayList<>();
                                lambdaAssociationsQuantity = null;
                                functionAssociationsQuantity = null;
                            }
                        }
                        case "LambdaFunctionAssociations" -> {
                            if (inCacheBehavior) {
                                inLambdaAssociations = true;
                            }
                        }
                        case "FunctionAssociations" -> {
                            if (inCacheBehavior) {
                                inFunctionAssociations = true;
                            }
                        }
                        case "LambdaFunctionAssociation" -> {
                            if (inLambdaAssociations) {
                                currentLambdaAssociation = new LinkedHashMap<>();
                            }
                        }
                        case "FunctionAssociation" -> {
                            if (inFunctionAssociations) {
                                currentFunctionAssociation = new LinkedHashMap<>();
                            }
                        }
                        case "LambdaFunctionARN" -> {
                            if (currentLambdaAssociation != null) {
                                currentLambdaAssociation.put("LambdaFunctionARN", r.getElementText());
                            }
                        }
                        case "FunctionARN" -> {
                            if (currentFunctionAssociation != null) {
                                currentFunctionAssociation.put("FunctionARN", r.getElementText());
                            }
                        }
                        case "IncludeBody" -> {
                            if (currentLambdaAssociation != null) {
                                currentLambdaAssociation.put(
                                        "IncludeBody", "true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                        case "EventType" -> {
                            if (currentLambdaAssociation != null) {
                                currentLambdaAssociation.put("EventType", r.getElementText());
                            } else if (currentFunctionAssociation != null) {
                                currentFunctionAssociation.put("EventType", r.getElementText());
                            }
                        }
                        case "TrustedKeyGroups" -> {
                            if (inCacheBehavior) {
                                inTrustedKeyGroups = true;
                                sawTrustedKeyGroups = true;
                            }
                        }
                        case "Enabled" -> {
                            if (inTrustedKeyGroups) {
                                trustedKeyGroupsEnabled =
                                        parseTrustedKeyGroupsEnabled(r.getElementText());
                            }
                        }
                        case "Quantity" -> {
                            if (inTrustedKeyGroups) {
                                trustedKeyGroupsQuantity =
                                        parseTrustedKeyGroupsQuantity(r.getElementText());
                            } else if (inLambdaAssociations && currentLambdaAssociation == null) {
                                lambdaAssociationsQuantity =
                                        parseAssociationsQuantity(r.getElementText());
                            } else if (inFunctionAssociations && currentFunctionAssociation == null) {
                                functionAssociationsQuantity =
                                        parseAssociationsQuantity(r.getElementText());
                            }
                        }
                        case "KeyGroup" -> {
                            if (inTrustedKeyGroups) {
                                trustedKeyGroups.add(r.getElementText());
                            }
                        }
                        case "AllowedMethods" -> {
                            if (inCacheBehavior) inAllowedMethods = true;
                        }
                        case "PathPattern" -> {
                            if (inCacheBehavior && current != null) current.setPathPattern(r.getElementText());
                        }
                        case "TargetOriginId" -> {
                            if (inCacheBehavior && current != null) current.setTargetOriginId(r.getElementText());
                        }
                        case "ViewerProtocolPolicy" -> {
                            if (inCacheBehavior && current != null) current.setViewerProtocolPolicy(r.getElementText());
                        }
                        case "CachePolicyId" -> {
                            if (inCacheBehavior && current != null) current.setCachePolicyId(r.getElementText());
                        }
                        case "OriginRequestPolicyId" -> {
                            if (inCacheBehavior && current != null)
                                current.setOriginRequestPolicyId(r.getElementText());
                        }
                        case "ResponseHeadersPolicyId" -> {
                            if (inCacheBehavior && current != null)
                                current.setResponseHeadersPolicyId(r.getElementText());
                        }
                        case "Compress" -> {
                            if (inCacheBehavior && current != null) {
                                current.setCompress("true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                        case "Method" -> {
                            if (inAllowedMethods) allowedMethods.add(r.getElementText());
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "AllowedMethods" -> inAllowedMethods = false;
                        case "TrustedKeyGroups" -> inTrustedKeyGroups = false;
                        case "LambdaFunctionAssociations" -> inLambdaAssociations = false;
                        case "FunctionAssociations" -> inFunctionAssociations = false;
                        case "LambdaFunctionAssociation" -> {
                            if (currentLambdaAssociation != null) {
                                lambdaAssociations.add(currentLambdaAssociation);
                                currentLambdaAssociation = null;
                            }
                        }
                        case "FunctionAssociation" -> {
                            if (currentFunctionAssociation != null) {
                                functionAssociations.add(currentFunctionAssociation);
                                currentFunctionAssociation = null;
                            }
                        }
                        case "CacheBehavior" -> {
                            if (inCacheBehavior && current != null) {
                                if (!allowedMethods.isEmpty()) {
                                    current.setAllowedMethods(allowedMethods);
                                }
                                if (!trustedKeyGroups.isEmpty()) {
                                    current.setTrustedKeyGroups(trustedKeyGroups);
                                }
                                validateAssociationsQuantity(
                                        lambdaAssociationsQuantity, lambdaAssociations.size());
                                validateAssociationsQuantity(
                                        functionAssociationsQuantity, functionAssociations.size());
                                validateLambdaFunctionAssociations(lambdaAssociations);
                                validateFunctionAssociations(functionAssociations);
                                if (!lambdaAssociations.isEmpty()) {
                                    current.setLambdaFunctionAssociations(lambdaAssociations);
                                }
                                if (!functionAssociations.isEmpty()) {
                                    current.setFunctionAssociations(functionAssociations);
                                }
                                if (sawTrustedKeyGroups) {
                                    if (trustedKeyGroupsEnabled == null) {
                                        throw new AwsException(
                                                "InvalidArgument",
                                            "TrustedKeyGroups must include Enabled.",
                                            400);
                                    }
                                    validateTrustedKeyGroupsQuantity(
                                            trustedKeyGroupsQuantity,
                                            trustedKeyGroups.size());
                                    validateEnabledTrustedKeyGroups(
                                            trustedKeyGroupsEnabled,
                                            trustedKeyGroups.size());
                                    current.setTrustedKeyGroupsEnabled(
                                            trustedKeyGroupsEnabled);
                                }
                                result.add(current);
                            }
                            inCacheBehavior = false;
                            current = null;
                        }
                        case "CacheBehaviors" -> inCacheBehaviors = false;
                        default -> {
                        }
                    }
                }
            }
            r.close();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv("Invalid CacheBehaviors XML: {0}", e.getMessage());
            throw new AwsException(
                    "InvalidArgument", "The CacheBehaviors configuration is invalid.", 400);
        }
        return result;
    }

    private static boolean parseTrustedKeyGroupsEnabled(String value) {
        if (!"true".equalsIgnoreCase(value)
                && !"false".equalsIgnoreCase(value)) {
            throw new AwsException(
                    "InvalidArgument",
                    "TrustedKeyGroups Enabled must be true or false.",
                    400);
        }
        return Boolean.parseBoolean(value);
    }

    private static int parseTrustedKeyGroupsQuantity(String value) {
        try {
            int quantity = Integer.parseInt(value);
            if (quantity < 0) {
                throw new NumberFormatException("negative quantity");
            }
            return quantity;
        } catch (NumberFormatException e) {
            throw new AwsException(
                    "InvalidArgument",
                    "TrustedKeyGroups Quantity must be a non-negative integer.",
                    400);
        }
    }

    private static void validateTrustedKeyGroupsQuantity(
            Integer quantity, int itemCount) {
        if (quantity == null) {
            throw new AwsException(
                    "InvalidArgument",
                    "TrustedKeyGroups must include Quantity.",
                    400);
        }
        if (quantity != itemCount) {
            throw new AwsException(
                    "InconsistentQuantities",
                    "The value of Quantity does not match the number of items.",
                    400);
        }
    }

    private static void validateEnabledTrustedKeyGroups(
            boolean enabled, int itemCount) {
        if (enabled && itemCount == 0) {
            throw new AwsException(
                    "InvalidArgument",
                    "TrustedKeyGroups cannot be enabled without a key group.",
                    400);
        }
    }

    private List<String> parseAliases(String body) {
        List<String> result = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inAliases = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if ("Aliases".equals(local)) {
                        inAliases = true;
                    } else if (inAliases && "CNAME".equals(local)) {
                        result.add(r.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("Aliases".equals(r.getLocalName())) {
                        inAliases = false;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, String> parseViewerCertificate(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inVc = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if ("ViewerCertificate".equals(local)) {
                        inVc = true;
                    } else if (inVc) {
                        String text = r.getElementText();
                        result.put(local, text);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("ViewerCertificate".equals(r.getLocalName())) {
                        inVc = false;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return result;
    }

    private Invalidation parseInvalidation(String body) {
        Invalidation inv = new Invalidation();
        inv.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        inv.setPaths(XmlParser.extractAll(body, "Path"));
        return inv;
    }

    private CachePolicy parseCachePolicy(String body) {
        CachePolicy policy = new CachePolicy();
        policy.setName(XmlParser.extractFirst(body, "Name", null));
        policy.setComment(XmlParser.extractFirst(body, "Comment", null));
        return policy;
    }

    private OriginRequestPolicy parseOriginRequestPolicy(String body) {
        OriginRequestPolicy policy = new OriginRequestPolicy();
        policy.setName(XmlParser.extractFirst(body, "Name", null));
        policy.setComment(XmlParser.extractFirst(body, "Comment", null));
        return policy;
    }

    private ResponseHeadersPolicy parseResponseHeadersPolicy(String body) {
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName(XmlParser.extractFirst(body, "Name", null));
        policy.setComment(XmlParser.extractFirst(body, "Comment", null));
        policy.setConfig(ResponseHeadersPolicyConfigCodec.parse(body));
        return policy;
    }

    private OriginAccessControl parseOriginAccessControl(String body) {
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName(XmlParser.extractFirst(body, "Name", null));
        oac.setDescription(XmlParser.extractFirst(body, "Description", null));
        oac.setSigningProtocol(XmlParser.extractFirst(body, "SigningProtocol", null));
        oac.setSigningBehavior(XmlParser.extractFirst(body, "SigningBehavior", null));
        oac.setOriginAccessControlOriginType(
                XmlParser.extractFirst(body, "OriginAccessControlOriginType", null));
        return oac;
    }

    private CloudFrontOriginAccessIdentity parseOai(String body) {
        CloudFrontOriginAccessIdentity oai = new CloudFrontOriginAccessIdentity();
        oai.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        oai.setComment(XmlParser.extractFirst(body, "Comment", null));
        return oai;
    }

    private CloudFrontFunction parseFunction(String body) {
        CloudFrontFunction fn = new CloudFrontFunction();
        fn.setName(XmlParser.extractFirst(body, "Name", null));
        fn.setComment(XmlParser.extractFirst(body, "Comment", null));
        fn.setRuntime(XmlParser.extractFirst(body, "Runtime", "cloudfront-js-2.0"));
        fn.setFunctionCode(XmlParser.extractFirst(body, "FunctionCode", null));
        return fn;
    }

    // ── Phase 2 XML builders ──────────────────────────────────────────────────

    private String xmlContinuousDeploymentPolicyResponse(ContinuousDeploymentPolicy policy) {
        XmlBuilder xml = new XmlBuilder()
                .start("ContinuousDeploymentPolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "")
                .start("ContinuousDeploymentPolicyConfig")
                .elem("Enabled", policy.isEnabled());
        List<String> dns = policy.getStagingDistributionDnsNames();
        int dnsCount = dns != null ? dns.size() : 0;
        xml.start("StagingDistributionDnsNames").elem("Quantity", dnsCount);
        if (dnsCount > 0) {
            xml.start("Items");
            for (String d : dns) {
                xml.elem("DnsName", d);
            }
            xml.end("Items");
        }
        xml.end("StagingDistributionDnsNames")
                .end("ContinuousDeploymentPolicyConfig")
                .end("ContinuousDeploymentPolicy");
        return xml.build();
    }

    private String xmlPublicKeyResponse(PublicKey key) {
        return new XmlBuilder()
                .start("PublicKey")
                .elem("Id", key.getId())
                .elem("CreatedTime", key.getCreatedTime() != null ? key.getCreatedTime().toString() : "")
                .start("PublicKeyConfig")
                .elem("CallerReference", key.getCallerReference() != null ? key.getCallerReference() : "")
                .elem("Name", key.getName() != null ? key.getName() : "")
                .elem("EncodedKey", key.getEncodedKey() != null ? key.getEncodedKey() : "")
                .elem("Comment", key.getComment() != null ? key.getComment() : "")
                .end("PublicKeyConfig")
                .end("PublicKey")
                .build();
    }

    private String xmlPublicKeySummary(PublicKey key) {
        return new XmlBuilder()
                .start("PublicKeySummary")
                .elem("Id", key.getId())
                .elem("Name", key.getName() != null ? key.getName() : "")
                .elem("CreatedTime", key.getCreatedTime() != null ? key.getCreatedTime().toString() : "")
                .elem("EncodedKey", key.getEncodedKey() != null ? key.getEncodedKey() : "")
                .elem("Comment", key.getComment() != null ? key.getComment() : "")
                .end("PublicKeySummary")
                .build();
    }

    private String xmlKeyGroupResponse(KeyGroup group) {
        List<String> items = group.getItems() != null ? group.getItems() : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("KeyGroup")
                .elem("Id", group.getId())
                .elem("LastModifiedTime",
                        group.getLastModifiedTime() != null ? group.getLastModifiedTime().toString() : "")
                .start("KeyGroupConfig")
                .elem("Name", group.getName() != null ? group.getName() : "")
                .elem("Comment", group.getComment() != null ? group.getComment() : "")
                .raw(xmlDirectItems("Items", "PublicKey", items))
                .end("KeyGroupConfig")
                .end("KeyGroup");
        return xml.build();
    }

    private String xmlRealtimeLogConfigBody(RealtimeLogConfig cfg) {
        List<String> fields = cfg.getFields() != null ? cfg.getFields() : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("RealtimeLogConfig")
                .elem("ARN", cfg.getArn() != null ? cfg.getArn() : "")
                .elem("Name", cfg.getName() != null ? cfg.getName() : "")
                .elem("SamplingRate", cfg.getSamplingRate())
                .start("Fields")
                .elem("Quantity", fields.size());
        if (!fields.isEmpty()) {
            xml.start("Items");
            for (String f : fields) {
                xml.elem("Field", f);
            }
            xml.end("Items");
        }
        xml.end("Fields");
        xml.start("EndPoints").elem("Quantity", 0).end("EndPoints");
        xml.end("RealtimeLogConfig");
        return xml.build();
    }

    private String xmlStreamingDistributionResponse(StreamingDistribution sd) {
        return new XmlBuilder()
                .start("StreamingDistribution", NS)
                .elem("Id", sd.getId())
                .elem("ARN", sd.getArn() != null ? sd.getArn() : "")
                .elem("Status", sd.getStatus())
                .elem("DomainName", sd.getDomainName() != null ? sd.getDomainName() : "")
                .elem("LastModifiedTime",
                        sd.getLastModifiedTime() != null ? sd.getLastModifiedTime().toString() : "")
                .start("ActiveTrustedSigners")
                .elem("Enabled", false)
                .elem("Quantity", 0)
                .end("ActiveTrustedSigners")
                .raw(xmlStreamingDistributionConfigBody(sd))
                .end("StreamingDistribution")
                .build();
    }

    private String xmlStreamingDistributionConfigBody(StreamingDistribution sd) {
        List<String> aliases = sd.getAliases() != null ? sd.getAliases() : List.of();
        XmlBuilder xml = new XmlBuilder()
                .start("StreamingDistributionConfig")
                .elem("CallerReference", sd.getCallerReference() != null ? sd.getCallerReference() : "")
                .elem("Comment", sd.getComment() != null ? sd.getComment() : "")
                .elem("Enabled", sd.isEnabled())
                .elem("PriceClass", sd.getPriceClass() != null ? sd.getPriceClass() : "PriceClass_All")
                .start("S3Origin")
                .elem("DomainName", sd.getS3Bucket() != null ? sd.getS3Bucket() : "")
                .elem("OriginAccessIdentity",
                        sd.getS3OriginAccessIdentity() != null ? sd.getS3OriginAccessIdentity() : "")
                .end("S3Origin")
                .start("Aliases").elem("Quantity", aliases.size());
        if (!aliases.isEmpty()) {
            xml.start("Items");
            for (String a : aliases) {
                xml.elem("CNAME", a);
            }
            xml.end("Items");
        }
        xml.end("Aliases")
                .start("TrustedSigners").elem("Enabled", false).elem("Quantity", 0).end("TrustedSigners")
                .end("StreamingDistributionConfig");
        return xml.build();
    }

    private String xmlFieldLevelEncryptionConfigResponse(FieldLevelEncryptionConfig cfg) {
        return new XmlBuilder()
                .start("FieldLevelEncryption")
                .elem("Id", cfg.getId())
                .elem("LastModifiedTime",
                        cfg.getLastModifiedTime() != null ? cfg.getLastModifiedTime().toString() : "")
                .start("FieldLevelEncryptionConfig")
                .elem("CallerReference", cfg.getCallerReference() != null ? cfg.getCallerReference() : "")
                .elem("Comment", cfg.getComment() != null ? cfg.getComment() : "")
                .end("FieldLevelEncryptionConfig")
                .end("FieldLevelEncryption")
                .build();
    }

    private String xmlFieldLevelEncryptionProfileResponse(FieldLevelEncryptionProfile profile) {
        return new XmlBuilder()
                .start("FieldLevelEncryptionProfile")
                .elem("Id", profile.getId())
                .elem("LastModifiedTime",
                        profile.getLastModifiedTime() != null ? profile.getLastModifiedTime().toString() : "")
                .start("FieldLevelEncryptionProfileConfig")
                .elem("Name", profile.getName() != null ? profile.getName() : "")
                .elem("CallerReference",
                        profile.getCallerReference() != null ? profile.getCallerReference() : "")
                .elem("Comment", profile.getComment() != null ? profile.getComment() : "")
                .start("EncryptionEntities").elem("Quantity", 0).end("EncryptionEntities")
                .end("FieldLevelEncryptionProfileConfig")
                .end("FieldLevelEncryptionProfile")
                .build();
    }

    private String xmlMonitoringSubscriptionResponse(MonitoringSubscription sub) {
        return new XmlBuilder()
                .start("MonitoringSubscription", NS)
                .start("RealtimeMetricsSubscriptionConfig")
                .elem("RealtimeMetricsSubscriptionStatus",
                        sub.getRealtimeMetricsSubscriptionStatus() != null
                                ? sub.getRealtimeMetricsSubscriptionStatus() : "Disabled")
                .end("RealtimeMetricsSubscriptionConfig")
                .end("MonitoringSubscription")
                .build();
    }

    // ── Phase 2 request parsers ───────────────────────────────────────────────

    private ContinuousDeploymentPolicy parseContinuousDeploymentPolicy(String body) {
        ContinuousDeploymentPolicy policy = new ContinuousDeploymentPolicy();
        policy.setEnabled("true".equalsIgnoreCase(XmlParser.extractFirst(body, "Enabled", "false")));
        policy.setStagingDistributionDnsNames(XmlParser.extractAll(body, "DnsName"));
        return policy;
    }

    private PublicKey parsePublicKey(String body) {
        PublicKey key = new PublicKey();
        key.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        key.setName(XmlParser.extractFirst(body, "Name", null));
        key.setEncodedKey(XmlParser.extractFirst(body, "EncodedKey", null));
        key.setComment(XmlParser.extractFirst(body, "Comment", null));
        return key;
    }

    private KeyGroup parseKeyGroup(String body) {
        KeyGroup group = new KeyGroup();
        group.setName(XmlParser.extractFirst(body, "Name", null));
        group.setComment(XmlParser.extractFirst(body, "Comment", null));
        group.setItems(XmlParser.extractAll(body, "PublicKey"));
        return group;
    }

    private RealtimeLogConfig parseRealtimeLogConfig(String body) {
        RealtimeLogConfig cfg = new RealtimeLogConfig();
        cfg.setName(XmlParser.extractFirst(body, "Name", null));
        String sr = XmlParser.extractFirst(body, "SamplingRate", "100");
        try {
            cfg.setSamplingRate(Long.parseLong(sr));
        } catch (NumberFormatException ignored) {
            cfg.setSamplingRate(100);
        }
        cfg.setFields(XmlParser.extractAll(body, "Field"));
        return cfg;
    }

    private StreamingDistribution parseStreamingDistribution(String body) {
        StreamingDistribution sd = new StreamingDistribution();
        sd.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        sd.setEnabled("true".equalsIgnoreCase(XmlParser.extractFirst(body, "Enabled", "false")));
        sd.setComment(XmlParser.extractFirst(body, "Comment", ""));
        sd.setPriceClass(XmlParser.extractFirst(body, "PriceClass", "PriceClass_All"));
        sd.setS3Bucket(XmlParser.extractFirst(body, "DomainName", null));
        sd.setS3OriginAccessIdentity(XmlParser.extractFirst(body, "OriginAccessIdentity", ""));
        sd.setAliases(XmlParser.extractAll(body, "CNAME"));
        return sd;
    }

    private FieldLevelEncryptionConfig parseFieldLevelEncryptionConfig(String body) {
        FieldLevelEncryptionConfig cfg = new FieldLevelEncryptionConfig();
        cfg.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        cfg.setComment(XmlParser.extractFirst(body, "Comment", null));
        return cfg;
    }

    private FieldLevelEncryptionProfile parseFieldLevelEncryptionProfile(String body) {
        FieldLevelEncryptionProfile profile = new FieldLevelEncryptionProfile();
        profile.setName(XmlParser.extractFirst(body, "Name", null));
        profile.setCallerReference(XmlParser.extractFirst(body, "CallerReference", null));
        profile.setComment(XmlParser.extractFirst(body, "Comment", null));
        return profile;
    }

    private MonitoringSubscription parseMonitoringSubscription(String body) {
        MonitoringSubscription sub = new MonitoringSubscription();
        sub.setRealtimeMetricsSubscriptionStatus(
                XmlParser.extractFirst(body, "RealtimeMetricsSubscriptionStatus", "Enabled"));
        return sub;
    }

    private Map<String, String> parseTags(String body) {
        Map<String, String> tags = new LinkedHashMap<>();
        List<Map<String, String>> groups = XmlParser.extractGroups(body, "Tag");
        for (Map<String, String> group : groups) {
            String key = group.get("Key");
            if (key != null) {
                tags.put(key, group.getOrDefault("Value", ""));
            }
        }
        return tags;
    }
}
