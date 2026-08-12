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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/2020-05-31")
public class CloudFrontController {

    private static final String NS = AwsNamespaces.CLOUDFRONT;
    private static final String XML = "application/xml";

    private final CloudFrontService service;

    @Inject
    public CloudFrontController(CloudFrontService service) {
        this.service = service;
    }

    // ── Distributions ─────────────────────────────────────────────────────────

    @POST
    @Path("/distribution")
    public Response createDistribution(String body) {
        try {
            DistributionConfig config = parseDistributionConfig(body);
            // CreateDistributionWithTags is CreateDistribution with a ?WithTags marker and a
            // DistributionConfigWithTags body. The marker carries no value, so it arrives as a
            // null @QueryParam and cannot be tested for; the Tags block in the body is what
            // distinguishes the two, and a plain DistributionConfig has none.
            Map<String, String> tags = parseTags(body);
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
            List<Distribution> dists = service.listDistributions(marker, maxItems);
            long total = service.listDistributions(null, Integer.MAX_VALUE).size();
            boolean truncated = dists.size() == maxItems && dists.size() < total;

            XmlBuilder xml = new XmlBuilder()
                    .start("DistributionList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated);
            if (truncated && !dists.isEmpty()) {
                xml.elem("NextMarker", dists.get(dists.size() - 1).getId());
            }
            xml.elem("Quantity", dists.size())
                    .start("Items");
            for (Distribution d : dists) {
                xml.raw(xmlDistributionSummary(d));
            }
            xml.end("Items")
                    .end("DistributionList");
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
            List<Invalidation> invs = service.listInvalidations(id, marker, maxItems);
            boolean truncated = invs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("InvalidationList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", invs.size())
                    .start("Items");
            for (Invalidation inv : invs) {
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
            List<CachePolicy> policies = service.listCachePolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("CachePolicyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (CachePolicy p : policies) {
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
            List<OriginRequestPolicy> policies = service.listOriginRequestPolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("OriginRequestPolicyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (OriginRequestPolicy p : policies) {
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
            String xml = new XmlBuilder()
                    .start("ResponseHeadersPolicyConfig", NS)
                    .elem("Name", policy.getName())
                    .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                    .end("ResponseHeadersPolicyConfig")
                    .build();
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
            List<ResponseHeadersPolicy> policies = service.listResponseHeadersPolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ResponseHeadersPolicyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (ResponseHeadersPolicy p : policies) {
                xml.start("ResponseHeadersPolicySummary")
                        .elem("Type", "custom")
                        .raw(xmlResponseHeadersPolicyResponse(p))
                        .end("ResponseHeadersPolicySummary");
            }
            xml.end("Items").end("ResponseHeadersPolicyList");
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
            List<OriginAccessControl> oacs = service.listOriginAccessControls(marker, maxItems);
            boolean truncated = oacs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("OriginAccessControlList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", oacs.size())
                    .start("Items");
            for (OriginAccessControl o : oacs) {
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
            List<CloudFrontOriginAccessIdentity> oais =
                    service.listCloudFrontOriginAccessIdentities(marker, maxItems);
            boolean truncated = oais.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("CloudFrontOriginAccessIdentityList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", oais.size())
                    .start("Items");
            for (CloudFrontOriginAccessIdentity o : oais) {
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
            List<CloudFrontFunction> fns = service.listFunctions(stage);
            XmlBuilder xml = new XmlBuilder()
                    .start("FunctionList", NS)
                    .elem("MaxItems", maxItems)
                    .elem("Quantity", fns.size())
                    .start("Items");
            for (CloudFrontFunction fn : fns) {
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
            List<ContinuousDeploymentPolicy> policies =
                    service.listContinuousDeploymentPolicies(marker, maxItems);
            boolean truncated = policies.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ContinuousDeploymentPolicyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", policies.size())
                    .start("Items");
            for (ContinuousDeploymentPolicy p : policies) {
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
            List<PublicKey> keys = service.listPublicKeys(marker, maxItems);
            boolean truncated = keys.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("PublicKeyList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", keys.size())
                    .start("Items");
            for (PublicKey k : keys) {
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
                    .raw(xmlQuantityItems("Items", "PublicKey", items.size(),
                            items.stream().map(k -> "<PublicKey>" + XmlBuilder.escape(k) + "</PublicKey>").toList()))
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
            List<KeyGroup> groups = service.listKeyGroups(marker, maxItems);
            boolean truncated = groups.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("KeyGroupList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", groups.size())
                    .start("Items");
            for (KeyGroup g : groups) {
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
            List<RealtimeLogConfig> configs = service.listRealtimeLogConfigs(marker, maxItems);
            boolean truncated = configs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("RealtimeLogConfigs", NS)
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", configs.size())
                    .start("Items");
            for (RealtimeLogConfig c : configs) {
                xml.raw(xmlRealtimeLogConfigBody(c));
            }
            xml.end("Items").end("RealtimeLogConfigs");
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
            List<FieldLevelEncryptionConfig> configs =
                    service.listFieldLevelEncryptionConfigs(marker, maxItems);
            boolean truncated = configs.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("FieldLevelEncryptionList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", configs.size())
                    .start("Items");
            for (FieldLevelEncryptionConfig c : configs) {
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
            List<FieldLevelEncryptionProfile> profiles =
                    service.listFieldLevelEncryptionProfiles(marker, maxItems);
            boolean truncated = profiles.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("FieldLevelEncryptionProfileList", NS)
                    .elem("Marker", marker != null ? marker : "")
                    .elem("MaxItems", maxItems)
                    .elem("IsTruncated", truncated)
                    .elem("Quantity", profiles.size())
                    .start("Items");
            for (FieldLevelEncryptionProfile p : profiles) {
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
                .elem("LastModifiedTime",
                        dist.getLastModifiedTime() != null ? dist.getLastModifiedTime().toString() : "")
                .elem("InProgressInvalidationBatches", 0)
                .elem("DomainName", dist.getDomainName())
                .start("ActiveTrustedSigners")
                .elem("Enabled", false)
                .elem("Quantity", 0)
                .end("ActiveTrustedSigners")
                .start("ActiveTrustedKeyGroups")
                .elem("Enabled", false)
                .elem("Quantity", 0)
                .end("ActiveTrustedKeyGroups")
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
                .elem("CallerReference", cfg.getCallerReference() != null ? cfg.getCallerReference() : "");

        xml.raw(xmlStringItems("Aliases", "CNAME", cfg.getAliases()));
        xml.elem("DefaultRootObject", cfg.getDefaultRootObject() != null ? cfg.getDefaultRootObject() : "");

        List<Origin> origins = cfg.getOrigins();
        xml.raw(xmlQuantityItems("Origins", "Origin", origins != null ? origins.size() : 0,
                origins != null ? origins.stream().map(this::xmlOrigin).toList() : List.of()));

        // OriginGroups is always present in a CloudFront response, empty or not, and clients
        // read its Quantity without checking whether the element exists.
        xml.start("OriginGroups").elem("Quantity", 0).end("OriginGroups");

        if (cfg.getDefaultCacheBehavior() != null) {
            xml.start("DefaultCacheBehavior")
                    .raw(xmlCacheBehaviorBody(cfg.getDefaultCacheBehavior()))
                    .end("DefaultCacheBehavior");
        }

        List<CacheBehavior> cacheBehaviors = cfg.getCacheBehaviors();
        xml.raw(xmlQuantityItems("CacheBehaviors", "CacheBehavior",
                cacheBehaviors != null ? cacheBehaviors.size() : 0,
                cacheBehaviors != null ? cacheBehaviors.stream().map(this::xmlCacheBehavior).toList() : List.of()));

        xml.raw(xmlCustomErrorResponses(cfg.getCustomErrorResponses()));

        xml.elem("Comment", cfg.getComment() != null ? cfg.getComment() : "");
        xml.raw(xmlLogging(cfg.getLogging()));
        xml.elem("PriceClass", cfg.getPriceClass() != null ? cfg.getPriceClass() : "PriceClass_All");
        xml.elem("Enabled", cfg.isEnabled());
        xml.raw(xmlViewerCertificate(cfg.getViewerCertificate()));
        xml.raw(xmlRestrictions(cfg.getGeoRestriction()));
        xml.elem("WebACLId", cfg.getWebAclId() != null ? cfg.getWebAclId() : "");
        xml.elem("HttpVersion", cfg.getHttpVersion() != null ? cfg.getHttpVersion() : "http2");
        xml.elem("IsIPV6Enabled", cfg.isIPV6Enabled());
        xml.elem("ContinuousDeploymentPolicyId", cfg.getContinuousDeploymentPolicyId());
        xml.elem("Staging", cfg.isStaging());

        return xml.build();
    }

    /** {@code <Restrictions><GeoRestriction>…} — always emitted, as CloudFront does. */
    private String xmlRestrictions(Map<String, Object> geoRestriction) {
        String type = "none";
        List<String> locations = List.of();
        if (geoRestriction != null) {
            Object t = geoRestriction.get("RestrictionType");
            if (t != null) {
                type = t.toString();
            }
            locations = stringList(geoRestriction.get("Items"));
        }
        XmlBuilder xml = new XmlBuilder()
                .start("Restrictions")
                .start("GeoRestriction")
                .elem("RestrictionType", type)
                .elem("Quantity", locations.size());
        if (!locations.isEmpty()) {
            xml.start("Items");
            for (String location : locations) {
                xml.elem("Location", location);
            }
            xml.end("Items");
        }
        return xml.end("GeoRestriction").end("Restrictions").build();
    }

    private String xmlLogging(Map<String, Object> logging) {
        XmlBuilder xml = new XmlBuilder().start("Logging");
        if (logging != null) {
            xml.elem("Enabled", "true".equalsIgnoreCase(str(logging, "Enabled", "false")))
                    .elem("IncludeCookies", "true".equalsIgnoreCase(str(logging, "IncludeCookies", "false")))
                    .elem("Bucket", str(logging, "Bucket", ""))
                    .elem("Prefix", str(logging, "Prefix", ""));
        } else {
            xml.elem("Enabled", false)
                    .elem("IncludeCookies", false)
                    .elem("Bucket", "")
                    .elem("Prefix", "");
        }
        return xml.end("Logging").build();
    }

    private String xmlCustomErrorResponses(List<Map<String, Object>> responses) {
        int count = responses != null ? responses.size() : 0;
        XmlBuilder xml = new XmlBuilder().start("CustomErrorResponses").elem("Quantity", count);
        if (count > 0) {
            xml.start("Items");
            for (Map<String, Object> r : responses) {
                xml.start("CustomErrorResponse")
                        .elem("ErrorCode", str(r, "ErrorCode", "0"))
                        .elem("ResponsePagePath", str(r, "ResponsePagePath", ""))
                        .elem("ResponseCode", str(r, "ResponseCode", ""))
                        .elem("ErrorCachingMinTTL", str(r, "ErrorCachingMinTTL", "0"))
                        .end("CustomErrorResponse");
            }
            xml.end("Items");
        }
        return xml.end("CustomErrorResponses").build();
    }

    /** A {@code Quantity}/{@code Items} block whose items are plain text elements. */
    private String xmlStringItems(String wrapper, String itemTag, List<String> values) {
        int count = values != null ? values.size() : 0;
        XmlBuilder xml = new XmlBuilder().start(wrapper).elem("Quantity", count);
        if (count > 0) {
            xml.start("Items");
            for (String value : values) {
                xml.elem(itemTag, value);
            }
            xml.end("Items");
        }
        return xml.end(wrapper).build();
    }

    private String str(Map<String, Object> map, String key, String defaultValue) {
        Object value = map != null ? map.get(key) : null;
        return value != null ? value.toString() : defaultValue;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String xmlOrigin(Origin o) {
        XmlBuilder xml = new XmlBuilder()
                .start("Origin")
                .elem("Id", o.getId())
                .elem("DomainName", o.getDomainName())
                .elem("OriginPath", o.getOriginPath() != null ? o.getOriginPath() : "");

        List<Map<String, String>> headers = o.getCustomHeaders();
        int headerCount = headers != null ? headers.size() : 0;
        xml.start("CustomHeaders").elem("Quantity", headerCount);
        if (headerCount > 0) {
            xml.start("Items");
            for (Map<String, String> header : headers) {
                xml.start("OriginCustomHeader")
                        .elem("HeaderName", header.getOrDefault("HeaderName", ""))
                        .elem("HeaderValue", header.getOrDefault("HeaderValue", ""))
                        .end("OriginCustomHeader");
            }
            xml.end("Items");
        }
        xml.end("CustomHeaders");

        Map<String, String> s3Config = o.getS3OriginConfig();
        if (s3Config != null) {
            xml.start("S3OriginConfig")
                    .elem("OriginAccessIdentity", s3Config.getOrDefault("OriginAccessIdentity", ""))
                    .end("S3OriginConfig");
        } else if (o.getCustomOriginConfig() == null) {
            xml.start("S3OriginConfig").elem("OriginAccessIdentity", "").end("S3OriginConfig");
        }

        Map<String, Object> coc = o.getCustomOriginConfig();
        if (coc != null) {
            xml.start("CustomOriginConfig")
                    .elem("HTTPPort", str(coc, "HTTPPort", "80"))
                    .elem("HTTPSPort", str(coc, "HTTPSPort", "443"))
                    .elem("OriginProtocolPolicy", str(coc, "OriginProtocolPolicy", "https-only"))
                    .raw(xmlStringItems("OriginSslProtocols", "SslProtocol",
                            stringList(coc.get("OriginSslProtocols"))))
                    .elem("OriginReadTimeout", str(coc, "OriginReadTimeout", "30"))
                    .elem("OriginKeepaliveTimeout", str(coc, "OriginKeepaliveTimeout", "5"))
                    .end("CustomOriginConfig");
        }

        xml.elem("ConnectionAttempts", o.getConnectionAttempts())
                .elem("ConnectionTimeout", o.getConnectionTimeout())
                .elem("OriginAccessControlId",
                        o.getOriginAccessControlId() != null ? o.getOriginAccessControlId() : "");

        xml.end("Origin");
        return xml.build();
    }

    private String xmlCacheBehavior(CacheBehavior cb) {
        return new XmlBuilder()
                .start("CacheBehavior")
                .elem("PathPattern", cb.getPathPattern())
                .raw(xmlCacheBehaviorBody(cb))
                .end("CacheBehavior")
                .build();
    }

    /**
     * The members shared by DefaultCacheBehavior and CacheBehavior. AllowedMethods is always
     * written — including its nested CachedMethods — because clients reach through it without
     * checking whether it is present.
     */
    private String xmlCacheBehaviorBody(CacheBehaviorSettings b) {
        XmlBuilder xml = new XmlBuilder()
                .elem("TargetOriginId", b.getTargetOriginId())
                .start("TrustedSigners")
                .elem("Enabled", b.isTrustedSignersEnabled())
                .raw(xmlInlineItems(b.getTrustedSigners(), "AwsAccountNumber"))
                .end("TrustedSigners")
                .start("TrustedKeyGroups")
                .elem("Enabled", b.isTrustedKeyGroupsEnabled())
                .raw(xmlInlineItems(b.getTrustedKeyGroups(), "KeyGroup"))
                .end("TrustedKeyGroups")
                .elem("ViewerProtocolPolicy",
                        b.getViewerProtocolPolicy() != null ? b.getViewerProtocolPolicy() : "redirect-to-https");

        List<String> allowed = b.getAllowedMethods();
        if (allowed == null || allowed.isEmpty()) {
            allowed = List.of("GET", "HEAD");
        }
        List<String> cached = b.getCachedMethods();
        if (cached == null || cached.isEmpty()) {
            cached = List.of("GET", "HEAD");
        }
        xml.start("AllowedMethods").elem("Quantity", allowed.size()).start("Items");
        for (String method : allowed) {
            xml.elem("Method", method);
        }
        xml.end("Items")
                .raw(xmlStringItems("CachedMethods", "Method", cached))
                .end("AllowedMethods");

        xml.elem("SmoothStreaming", b.isSmoothStreaming())
                .elem("Compress", b.isCompress());

        List<Map<String, Object>> lambdas = b.getLambdaFunctionAssociations();
        int lambdaCount = lambdas != null ? lambdas.size() : 0;
        xml.start("LambdaFunctionAssociations").elem("Quantity", lambdaCount);
        if (lambdaCount > 0) {
            xml.start("Items");
            for (Map<String, Object> association : lambdas) {
                xml.start("LambdaFunctionAssociation")
                        .elem("LambdaFunctionARN", str(association, "LambdaFunctionARN", ""))
                        .elem("EventType", str(association, "EventType", ""))
                        .elem("IncludeBody", "true".equalsIgnoreCase(str(association, "IncludeBody", "false")))
                        .end("LambdaFunctionAssociation");
            }
            xml.end("Items");
        }
        xml.end("LambdaFunctionAssociations");

        List<Map<String, String>> functions = b.getFunctionAssociations();
        int functionCount = functions != null ? functions.size() : 0;
        xml.start("FunctionAssociations").elem("Quantity", functionCount);
        if (functionCount > 0) {
            xml.start("Items");
            for (Map<String, String> association : functions) {
                xml.start("FunctionAssociation")
                        .elem("FunctionARN", association.getOrDefault("FunctionARN", ""))
                        .elem("EventType", association.getOrDefault("EventType", ""))
                        .end("FunctionAssociation");
            }
            xml.end("Items");
        }
        xml.end("FunctionAssociations");

        xml.elem("FieldLevelEncryptionId", b.getFieldLevelEncryptionId())
                .elem("RealtimeLogConfigArn", b.getRealtimeLogConfigArn())
                .elem("CachePolicyId", b.getCachePolicyId())
                .elem("OriginRequestPolicyId", b.getOriginRequestPolicyId())
                .elem("ResponseHeadersPolicyId", b.getResponseHeadersPolicyId());

        xml.raw(xmlForwardedValues(b.getForwardedValues()));

        if (b.getMinTTL() != null) {
            xml.elem("MinTTL", b.getMinTTL());
        }
        if (b.getDefaultTTL() != null) {
            xml.elem("DefaultTTL", b.getDefaultTTL());
        }
        if (b.getMaxTTL() != null) {
            xml.elem("MaxTTL", b.getMaxTTL());
        }

        return xml.build();
    }

    /** The {@code Quantity}/{@code Items} pair of an already-open list element. */
    private String xmlInlineItems(List<String> values, String itemTag) {
        int count = values != null ? values.size() : 0;
        XmlBuilder xml = new XmlBuilder().elem("Quantity", count);
        if (count > 0) {
            xml.start("Items");
            for (String value : values) {
                xml.elem(itemTag, value);
            }
            xml.end("Items");
        }
        return xml.build();
    }

    /**
     * ForwardedValues is omitted entirely when the config uses a cache policy instead, which is
     * how CloudFront answers — echoing an empty structure would make clients see a legacy
     * cache configuration that was never requested.
     */
    private String xmlForwardedValues(Map<String, Object> fv) {
        if (fv == null) {
            return "";
        }
        XmlBuilder xml = new XmlBuilder()
                .start("ForwardedValues")
                .elem("QueryString", "true".equalsIgnoreCase(str(fv, "QueryString", "false")))
                .start("Cookies")
                .elem("Forward", str(fv, "Forward", "none"));
        List<String> whitelisted = stringList(fv.get("WhitelistedNames"));
        if (!whitelisted.isEmpty()) {
            xml.raw(xmlStringItems("WhitelistedNames", "Name", whitelisted));
        }
        xml.end("Cookies")
                .raw(xmlStringItems("Headers", "Name", stringList(fv.get("Headers"))))
                .raw(xmlStringItems("QueryStringCacheKeys", "Name", stringList(fv.get("QueryStringCacheKeys"))))
                .end("ForwardedValues");
        return xml.build();
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

    /**
     * DistributionSummary carries the same structures as the distribution itself — every member
     * except ETag and the tenant-only ones is required — so it is built from the stored config
     * rather than from a handful of scalars.
     */
    private String xmlDistributionSummary(Distribution d) {
        DistributionConfig cfg = d.getConfig();
        XmlBuilder xml = new XmlBuilder()
                .start("DistributionSummary")
                .elem("Id", d.getId())
                .elem("ARN", d.getArn())
                .elem("ETag", d.getEtag())
                .elem("Status", d.getStatus())
                .elem("LastModifiedTime",
                        d.getLastModifiedTime() != null ? d.getLastModifiedTime().toString() : "")
                .elem("DomainName", d.getDomainName());

        xml.raw(xmlStringItems("Aliases", "CNAME", cfg != null ? cfg.getAliases() : null));

        List<Origin> origins = cfg != null ? cfg.getOrigins() : null;
        xml.raw(xmlQuantityItems("Origins", "Origin", origins != null ? origins.size() : 0,
                origins != null ? origins.stream().map(this::xmlOrigin).toList() : List.of()));

        xml.start("OriginGroups").elem("Quantity", 0).end("OriginGroups");

        if (cfg != null && cfg.getDefaultCacheBehavior() != null) {
            xml.start("DefaultCacheBehavior")
                    .raw(xmlCacheBehaviorBody(cfg.getDefaultCacheBehavior()))
                    .end("DefaultCacheBehavior");
        }

        List<CacheBehavior> cacheBehaviors = cfg != null ? cfg.getCacheBehaviors() : null;
        xml.raw(xmlQuantityItems("CacheBehaviors", "CacheBehavior",
                cacheBehaviors != null ? cacheBehaviors.size() : 0,
                cacheBehaviors != null ? cacheBehaviors.stream().map(this::xmlCacheBehavior).toList() : List.of()));

        xml.raw(xmlCustomErrorResponses(cfg != null ? cfg.getCustomErrorResponses() : null));

        xml.elem("Comment", cfg != null && cfg.getComment() != null ? cfg.getComment() : "")
                .elem("PriceClass", cfg != null && cfg.getPriceClass() != null
                        ? cfg.getPriceClass() : "PriceClass_All")
                .elem("Enabled", cfg != null && cfg.isEnabled());

        xml.raw(xmlViewerCertificate(cfg != null ? cfg.getViewerCertificate() : null));
        xml.raw(xmlRestrictions(cfg != null ? cfg.getGeoRestriction() : null));

        xml.elem("WebACLId", cfg != null && cfg.getWebAclId() != null ? cfg.getWebAclId() : "")
                .elem("HttpVersion", cfg != null && cfg.getHttpVersion() != null
                        ? cfg.getHttpVersion() : "http2")
                .elem("IsIPV6Enabled", cfg != null && cfg.isIPV6Enabled())
                .elem("Staging", cfg != null && cfg.isStaging());

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
        return new XmlBuilder()
                .start("ResponseHeadersPolicy")
                .elem("Id", policy.getId())
                .elem("LastModifiedTime",
                        policy.getLastModifiedTime() != null ? policy.getLastModifiedTime().toString() : "")
                .start("ResponseHeadersPolicyConfig")
                .elem("Name", policy.getName())
                .elem("Comment", policy.getComment() != null ? policy.getComment() : "")
                .end("ResponseHeadersPolicyConfig")
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

    /**
     * Reads a DistributionConfig from a CreateDistribution, CreateDistributionWithTags or
     * UpdateDistribution body. The config is located in the tree rather than scanned for by
     * element name: CloudFront repeats names such as Enabled, Id and Quantity inside nested
     * structures, so a document-order scan reads the wrong element (a TrustedKeyGroups
     * {@code <Enabled>false</Enabled>} would decide whether the distribution is enabled).
     */
    private DistributionConfig parseDistributionConfig(String body) {
        CloudFrontXml.Node root = CloudFrontXml.parse(body);
        CloudFrontXml.Node node = "DistributionConfig".equals(root.name())
                ? root
                : root.child("DistributionConfig");
        DistributionConfig cfg = new DistributionConfig();
        if (node == null) {
            return cfg;
        }

        cfg.setCallerReference(node.text("CallerReference", null));
        cfg.setEnabled(node.bool("Enabled", true));
        cfg.setComment(node.text("Comment", ""));
        cfg.setDefaultRootObject(node.text("DefaultRootObject", ""));
        cfg.setHttpVersion(node.text("HttpVersion", "http2"));
        cfg.setPriceClass(node.text("PriceClass", "PriceClass_All"));
        cfg.setIPV6Enabled(node.bool("IsIPV6Enabled", true));
        cfg.setWebAclId(node.text("WebACLId", null));
        cfg.setContinuousDeploymentPolicyId(node.text("ContinuousDeploymentPolicyId", null));
        cfg.setStaging(node.bool("Staging", false));

        cfg.setOrigins(parseOrigins(node));
        CloudFrontXml.Node defaultBehavior = node.child("DefaultCacheBehavior");
        if (defaultBehavior != null) {
            DefaultCacheBehavior dcb = new DefaultCacheBehavior();
            applyCacheBehaviorSettings(defaultBehavior, dcb);
            cfg.setDefaultCacheBehavior(dcb);
        }
        cfg.setCacheBehaviors(parseCacheBehaviors(node));
        cfg.setAliases(node.items("Aliases", "CNAME"));
        cfg.setViewerCertificate(parseViewerCertificate(node));
        cfg.setGeoRestriction(parseGeoRestriction(node));
        cfg.setLogging(parseLogging(node));
        cfg.setCustomErrorResponses(parseCustomErrorResponses(node));

        return cfg;
    }

    private List<Origin> parseOrigins(CloudFrontXml.Node config) {
        List<Origin> result = new ArrayList<>();
        CloudFrontXml.Node items = config.path("Origins", "Items");
        if (items == null) {
            return result;
        }
        for (CloudFrontXml.Node node : items.children("Origin")) {
            Origin origin = new Origin();
            origin.setId(node.text("Id", null));
            origin.setDomainName(node.text("DomainName", null));
            origin.setOriginPath(node.text("OriginPath", ""));
            origin.setOriginAccessControlId(node.text("OriginAccessControlId", null));
            origin.setConnectionAttempts(node.integer("ConnectionAttempts", 3));
            origin.setConnectionTimeout(node.integer("ConnectionTimeout", 10));

            CloudFrontXml.Node s3 = node.child("S3OriginConfig");
            if (s3 != null) {
                Map<String, String> s3Config = new LinkedHashMap<>();
                s3Config.put("OriginAccessIdentity", s3.text("OriginAccessIdentity", ""));
                origin.setS3OriginConfig(s3Config);
            }

            CloudFrontXml.Node custom = node.child("CustomOriginConfig");
            if (custom != null) {
                Map<String, Object> customConfig = new LinkedHashMap<>();
                customConfig.put("HTTPPort", custom.text("HTTPPort", "80"));
                customConfig.put("HTTPSPort", custom.text("HTTPSPort", "443"));
                customConfig.put("OriginProtocolPolicy", custom.text("OriginProtocolPolicy", "https-only"));
                customConfig.put("OriginReadTimeout", custom.text("OriginReadTimeout", "30"));
                customConfig.put("OriginKeepaliveTimeout", custom.text("OriginKeepaliveTimeout", "5"));
                customConfig.put("OriginSslProtocols", custom.items("OriginSslProtocols", "SslProtocol"));
                origin.setCustomOriginConfig(customConfig);
            }

            CloudFrontXml.Node headers = node.path("CustomHeaders", "Items");
            if (headers != null) {
                List<Map<String, String>> customHeaders = new ArrayList<>();
                for (CloudFrontXml.Node header : headers.children("OriginCustomHeader")) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("HeaderName", header.text("HeaderName", ""));
                    entry.put("HeaderValue", header.text("HeaderValue", ""));
                    customHeaders.add(entry);
                }
                if (!customHeaders.isEmpty()) {
                    origin.setCustomHeaders(customHeaders);
                }
            }

            result.add(origin);
        }
        return result;
    }

    private List<CacheBehavior> parseCacheBehaviors(CloudFrontXml.Node config) {
        List<CacheBehavior> result = new ArrayList<>();
        CloudFrontXml.Node items = config.path("CacheBehaviors", "Items");
        if (items == null) {
            return result;
        }
        for (CloudFrontXml.Node node : items.children("CacheBehavior")) {
            CacheBehavior behavior = new CacheBehavior();
            behavior.setPathPattern(node.text("PathPattern", null));
            applyCacheBehaviorSettings(node, behavior);
            result.add(behavior);
        }
        return result;
    }

    /** Reads the members shared by DefaultCacheBehavior and CacheBehavior. */
    private void applyCacheBehaviorSettings(CloudFrontXml.Node node, CacheBehaviorSettings behavior) {
        behavior.setTargetOriginId(node.text("TargetOriginId", null));
        behavior.setViewerProtocolPolicy(node.text("ViewerProtocolPolicy", "redirect-to-https"));
        behavior.setCachePolicyId(node.text("CachePolicyId", null));
        behavior.setOriginRequestPolicyId(node.text("OriginRequestPolicyId", null));
        behavior.setResponseHeadersPolicyId(node.text("ResponseHeadersPolicyId", null));
        behavior.setFieldLevelEncryptionId(node.text("FieldLevelEncryptionId", null));
        behavior.setRealtimeLogConfigArn(node.text("RealtimeLogConfigArn", null));
        behavior.setCompress(node.bool("Compress", false));
        behavior.setSmoothStreaming(node.bool("SmoothStreaming", false));
        behavior.setMinTTL(node.longOrNull("MinTTL"));
        behavior.setDefaultTTL(node.longOrNull("DefaultTTL"));
        behavior.setMaxTTL(node.longOrNull("MaxTTL"));

        CloudFrontXml.Node allowed = node.child("AllowedMethods");
        if (allowed != null) {
            List<String> methods = new ArrayList<>();
            CloudFrontXml.Node allowedItems = allowed.child("Items");
            if (allowedItems != null) {
                for (CloudFrontXml.Node method : allowedItems.children("Method")) {
                    methods.add(method.text());
                }
            }
            if (!methods.isEmpty()) {
                behavior.setAllowedMethods(methods);
            }
            List<String> cached = allowed.items("CachedMethods", "Method");
            if (!cached.isEmpty()) {
                behavior.setCachedMethods(cached);
            }
        }

        CloudFrontXml.Node signers = node.child("TrustedSigners");
        if (signers != null) {
            behavior.setTrustedSignersEnabled(signers.bool("Enabled", false));
            behavior.setTrustedSigners(textsOfItems(signers, "AwsAccountNumber"));
        }

        CloudFrontXml.Node keyGroups = node.child("TrustedKeyGroups");
        if (keyGroups != null) {
            behavior.setTrustedKeyGroupsEnabled(keyGroups.bool("Enabled", false));
            behavior.setTrustedKeyGroups(textsOfItems(keyGroups, "KeyGroup"));
        }

        CloudFrontXml.Node forwarded = node.child("ForwardedValues");
        if (forwarded != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("QueryString", forwarded.text("QueryString", "false"));
            CloudFrontXml.Node cookies = forwarded.child("Cookies");
            if (cookies != null) {
                values.put("Forward", cookies.text("Forward", "none"));
                List<String> whitelisted = cookies.items("WhitelistedNames", "Name");
                if (!whitelisted.isEmpty()) {
                    values.put("WhitelistedNames", whitelisted);
                }
            }
            values.put("Headers", forwarded.items("Headers", "Name"));
            values.put("QueryStringCacheKeys", forwarded.items("QueryStringCacheKeys", "Name"));
            behavior.setForwardedValues(values);
        }

        CloudFrontXml.Node functions = node.path("FunctionAssociations", "Items");
        if (functions != null) {
            List<Map<String, String>> associations = new ArrayList<>();
            for (CloudFrontXml.Node association : functions.children("FunctionAssociation")) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("FunctionARN", association.text("FunctionARN", ""));
                entry.put("EventType", association.text("EventType", ""));
                associations.add(entry);
            }
            if (!associations.isEmpty()) {
                behavior.setFunctionAssociations(associations);
            }
        }

        CloudFrontXml.Node lambdas = node.path("LambdaFunctionAssociations", "Items");
        if (lambdas != null) {
            List<Map<String, Object>> associations = new ArrayList<>();
            for (CloudFrontXml.Node association : lambdas.children("LambdaFunctionAssociation")) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("LambdaFunctionARN", association.text("LambdaFunctionARN", ""));
                entry.put("EventType", association.text("EventType", ""));
                entry.put("IncludeBody", association.text("IncludeBody", "false"));
                associations.add(entry);
            }
            if (!associations.isEmpty()) {
                behavior.setLambdaFunctionAssociations(associations);
            }
        }
    }

    private List<String> textsOfItems(CloudFrontXml.Node wrapper, String itemName) {
        CloudFrontXml.Node items = wrapper.child("Items");
        if (items == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (CloudFrontXml.Node item : items.children(itemName)) {
            result.add(item.text());
        }
        return result;
    }

    private Map<String, String> parseViewerCertificate(CloudFrontXml.Node config) {
        Map<String, String> result = new LinkedHashMap<>();
        CloudFrontXml.Node node = config.child("ViewerCertificate");
        if (node == null) {
            return result;
        }
        for (CloudFrontXml.Node child : node.childNodes()) {
            result.put(child.name(), child.text());
        }
        return result;
    }

    private Map<String, Object> parseGeoRestriction(CloudFrontXml.Node config) {
        CloudFrontXml.Node node = config.path("Restrictions", "GeoRestriction");
        if (node == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("RestrictionType", node.text("RestrictionType", "none"));
        result.put("Items", textsOfItems(node, "Location"));
        return result;
    }

    private Map<String, Object> parseLogging(CloudFrontXml.Node config) {
        CloudFrontXml.Node node = config.child("Logging");
        if (node == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("Enabled", node.text("Enabled", "false"));
        result.put("IncludeCookies", node.text("IncludeCookies", "false"));
        result.put("Bucket", node.text("Bucket", ""));
        result.put("Prefix", node.text("Prefix", ""));
        return result;
    }

    private List<Map<String, Object>> parseCustomErrorResponses(CloudFrontXml.Node config) {
        CloudFrontXml.Node items = config.path("CustomErrorResponses", "Items");
        if (items == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (CloudFrontXml.Node node : items.children("CustomErrorResponse")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ErrorCode", node.text("ErrorCode", "0"));
            entry.put("ResponsePagePath", node.text("ResponsePagePath", ""));
            entry.put("ResponseCode", node.text("ResponseCode", ""));
            entry.put("ErrorCachingMinTTL", node.text("ErrorCachingMinTTL", "0"));
            result.add(entry);
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
        return policy;
    }

    private OriginAccessControl parseOriginAccessControl(String body) {
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName(XmlParser.extractFirst(body, "Name", null));
        oac.setDescription(XmlParser.extractFirst(body, "Description", null));
        oac.setSigningProtocol(XmlParser.extractFirst(body, "SigningProtocol", "sigv4"));
        oac.setSigningBehavior(XmlParser.extractFirst(body, "SigningBehavior", "always"));
        oac.setOriginAccessControlOriginType(XmlParser.extractFirst(body, "OriginAccessControlOriginType", "s3"));
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
                .raw(xmlQuantityItems("Items", "PublicKey", items.size(),
                        items.stream().map(k -> "<PublicKey>" + XmlBuilder.escape(k) + "</PublicKey>").toList()))
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
