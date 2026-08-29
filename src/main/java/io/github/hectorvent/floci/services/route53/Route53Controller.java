package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.route53.Route53Service.CreateZoneResult;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ChangeInfo;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/2013-04-01")
public class Route53Controller {

    private static final String NS = AwsNamespaces.ROUTE53;
    private static final String XML = "application/xml";

    /**
     * The {@code VPCRegion} enum, verbatim from the Route 53 model. Deliberately not
     * {@code AwsRegions.KNOWN_IDS}: the enum admits the ISO and European Sovereign
     * partitions that this emulator never advertises, and rejecting those would refuse
     * requests AWS accepts.
     */
    private static final Set<String> VPC_REGIONS = Set.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1", "eu-central-2",
            "ap-east-1", "ap-east-2", "me-south-1", "me-central-1",
            "us-gov-west-1", "us-gov-east-1",
            "us-iso-east-1", "us-iso-west-1", "us-isob-east-1", "us-isob-west-1",
            "us-isof-south-1", "us-isof-east-1", "eu-isoe-west-1", "eusc-de-east-1",
            "ap-southeast-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-4",
            "ap-southeast-5", "ap-southeast-6", "ap-southeast-7",
            "ap-south-1", "ap-south-2",
            "ap-northeast-1", "ap-northeast-2", "ap-northeast-3",
            "eu-north-1", "sa-east-1", "ca-central-1", "ca-west-1",
            "cn-north-1", "cn-northwest-1",
            "af-south-1", "eu-south-1", "eu-south-2",
            "il-central-1", "mx-central-1");

    private static final XMLInputFactory XML_FACTORY;

    static {
        XML_FACTORY = XMLInputFactory.newInstance();
        XML_FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    @Inject
    Route53Service service;

    // ── Hosted Zones ──────────────────────────────────────────────────────────

    @POST
    @Path("/hostedzone")
    public Response createHostedZone(String body) {
        try {
            String name = XmlParser.extractFirst(body, "Name", null);
            String callerRef = XmlParser.extractFirst(body, "CallerReference", null);
            String comment = XmlParser.extractFirst(body, "Comment", null);
            VpcAssociation vpcAssociation = parseVpcAssociation(body);
            if (vpcAssociation != null) {
                requireModelledVpcRegion(vpcAssociation.getVpcRegion());
            }

            if (name == null || callerRef == null) {
                throw new AwsException("InvalidInput", "Name and CallerReference are required.", 400);
            }

            CreateZoneResult result = service.createHostedZone(name, callerRef, comment, vpcAssociation);
            XmlBuilder xml = new XmlBuilder()
                    .start("CreateHostedZoneResponse", NS)
                    .raw(xmlHostedZone(result.zone()))
                    .raw(xmlChangeInfo(result.change()))
                    .raw(xmlDelegationSet());
            if (!result.zone().getVpcAssociations().isEmpty()) {
                xml.raw(xmlVpcAssociation(result.zone().getVpcAssociations().getFirst()));
            }
            xml.end("CreateHostedZoneResponse");

            return Response.created(URI.create("/2013-04-01/hostedzone/" + result.zone().getId()))
                    .type(XML)
                    .entity(xml.build())
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone/{Id}")
    public Response getHostedZone(@PathParam("Id") String id) {
        try {
            HostedZone zone = service.getHostedZone(id);
            String xml = new XmlBuilder()
                    .start("GetHostedZoneResponse", NS)
                    .raw(xmlHostedZone(zone))
                    .raw(xmlDelegationSet())
                    .raw(xmlVpcAssociations(zone.getVpcAssociations()))
                    .end("GetHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/hostedzone/{Id}")
    public Response deleteHostedZone(@PathParam("Id") String id) {
        try {
            ChangeInfo change = service.deleteHostedZone(id);
            String xml = new XmlBuilder()
                    .start("DeleteHostedZoneResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("DeleteHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone")
    public Response listHostedZones(@QueryParam("marker") String marker,
                                     @QueryParam("maxitems") @DefaultValue("100") int maxItems) {
        try {
            List<HostedZone> zones = service.listHostedZones(marker, maxItems);
            long total = service.getHostedZoneCount();
            boolean truncated = zones.size() == maxItems && zones.size() < total;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListHostedZonesResponse", NS)
                    .start("HostedZones");
            for (HostedZone zone : zones) {
                xml.raw(xmlHostedZone(zone));
            }
            xml.end("HostedZones")
               .elem("Marker", marker != null ? marker : "")
               .elem("IsTruncated", String.valueOf(truncated));
            if (truncated && !zones.isEmpty()) {
                xml.elem("NextMarker", zones.get(zones.size() - 1).getId());
            }
            xml.elem("MaxItems", String.valueOf(maxItems))
               .end("ListHostedZonesResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonesbyname")
    public Response listHostedZonesByName(@QueryParam("dnsname") String dnsName,
                                           @QueryParam("maxitems") @DefaultValue("100") int maxItems) {
        try {
            List<HostedZone> zones = service.listHostedZonesByName(dnsName, maxItems);
            long total = service.getHostedZoneCount();
            boolean truncated = zones.size() == maxItems && zones.size() < total;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListHostedZonesByNameResponse", NS)
                    .start("HostedZones");
            for (HostedZone zone : zones) {
                xml.raw(xmlHostedZone(zone));
            }
            xml.end("HostedZones")
               .elem("IsTruncated", String.valueOf(truncated))
               .elem("MaxItems", String.valueOf(maxItems));
            if (dnsName != null && !dnsName.isEmpty()) {
                xml.elem("DNSName", dnsName);
            }
            xml.end("ListHostedZonesByNameResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── VPC Associations ──────────────────────────────────────────────────────

    @POST
    @Path("/hostedzone/{Id}/associatevpc")
    public Response associateVpcWithHostedZone(@PathParam("Id") String id, String body) {
        try {
            VpcAssociation vpc = requireVpcAssociation(body);
            String comment = XmlParser.extractFirst(body, "Comment", null);
            ChangeInfo change = service.associateVpcWithHostedZone(id, vpc, comment);
            String xml = new XmlBuilder()
                    .start("AssociateVPCWithHostedZoneResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("AssociateVPCWithHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/hostedzone/{Id}/disassociatevpc")
    public Response disassociateVpcFromHostedZone(@PathParam("Id") String id, String body) {
        try {
            VpcAssociation vpc = requireVpcAssociationWithoutRegionCheck(body);
            String comment = XmlParser.extractFirst(body, "Comment", null);
            ChangeInfo change = service.disassociateVpcFromHostedZone(id, vpc, comment);
            String xml = new XmlBuilder()
                    .start("DisassociateVPCFromHostedZoneResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("DisassociateVPCFromHostedZoneResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * Authorization is only meaningful when the zone and the VPC belong to different
     * accounts. Zones are not account-scoped here, so this validates the request and
     * echoes the authorized VPC back without gating a later associate call.
     */
    @POST
    @Path("/hostedzone/{Id}/authorizevpcassociation")
    public Response createVpcAssociationAuthorization(@PathParam("Id") String id, String body) {
        try {
            VpcAssociation vpc = requireVpcAssociation(body);
            service.getHostedZone(id);
            String xml = new XmlBuilder()
                    .start("CreateVPCAssociationAuthorizationResponse", NS)
                    .elem("HostedZoneId", id)
                    .raw(xmlVpcAssociation(vpc))
                    .end("CreateVPCAssociationAuthorizationResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * Counterpart to {@link #createVpcAssociationAuthorization}; the response has no members,
     * so this returns the response's root element with nothing inside it.
     */
    @POST
    @Path("/hostedzone/{Id}/deauthorizevpcassociation")
    public Response deleteVpcAssociationAuthorization(@PathParam("Id") String id, String body) {
        try {
            requireVpcAssociation(body);
            service.getHostedZone(id);
            String xml = new XmlBuilder()
                    .start("DeleteVPCAssociationAuthorizationResponse", NS)
                    .end("DeleteVPCAssociationAuthorizationResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonesbyvpc")
    public Response listHostedZonesByVpc(@QueryParam("vpcid") String vpcId,
                                         @QueryParam("vpcregion") String vpcRegion,
                                         @QueryParam("maxitems") @DefaultValue("100") int maxItems,
                                         @QueryParam("nexttoken") String nextToken) {
        try {
            if (vpcId == null || vpcId.isEmpty()) {
                throw new AwsException("InvalidInput", "VPCId is required.", 400);
            }
            if (vpcRegion == null || vpcRegion.isEmpty()) {
                throw new AwsException("InvalidInput", "VPCRegion is required.", 400);
            }
            // Deliberately not enum-gated: an association persisted under a region since
            // retired from VPC_REGIONS (or from before this check existed) must remain listable.

            if (maxItems <= 0) {
                throw new AwsException("InvalidInput", "MaxItems must be a positive integer.", 400);
            }

            List<HostedZone> zones = service.listHostedZonesByVpc(vpcId, vpcRegion);
            if (nextToken != null && !nextToken.isEmpty()) {
                int idx = -1;
                for (int i = 0; i < zones.size(); i++) {
                    if (zones.get(i).getId().equals(nextToken)) {
                        idx = i + 1;
                        break;
                    }
                }
                if (idx < 0) {
                    throw new AwsException("InvalidPaginationToken",
                            "Invalid value for NextToken: " + nextToken, 400);
                }
                zones = zones.subList(idx, zones.size());
            }
            boolean truncated = zones.size() > maxItems;
            if (truncated) {
                zones = zones.subList(0, maxItems);
            }

            XmlBuilder xml = new XmlBuilder()
                    .start("ListHostedZonesByVPCResponse", NS)
                    .start("HostedZoneSummaries");
            for (HostedZone zone : zones) {
                xml.raw(xmlHostedZoneSummary(zone));
            }
            xml.end("HostedZoneSummaries")
               .elem("MaxItems", String.valueOf(maxItems));
            if (truncated) {
                xml.elem("NextToken", zones.get(zones.size() - 1).getId());
            }
            xml.end("ListHostedZonesByVPCResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonecount")
    public Response getHostedZoneCount() {
        String xml = new XmlBuilder()
                .start("GetHostedZoneCountResponse", NS)
                .elem("HostedZoneCount", service.getHostedZoneCount())
                .end("GetHostedZoneCountResponse")
                .build();
        return Response.ok(xml, XML).build();
    }

    // ── Resource Record Sets ──────────────────────────────────────────────────

    @POST
    @Path("/hostedzone/{Id}/rrset")
    public Response changeResourceRecordSets(@PathParam("Id") String id, String body) {
        try {
            String comment = XmlParser.extractFirst(body, "Comment", null);
            List<Map<String, Object>> changes = parseChangeBatch(body);
            ChangeInfo change = service.changeResourceRecordSets(id, changes, comment);
            String xml = new XmlBuilder()
                    .start("ChangeResourceRecordSetsResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("ChangeResourceRecordSetsResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone/{Id}/rrset")
    public Response listResourceRecordSets(@PathParam("Id") String id,
                                            @QueryParam("name") String startName,
                                            @QueryParam("type") String startType,
                                            @QueryParam("maxitems") @DefaultValue("300") int maxItems) {
        try {
            List<ResourceRecordSet> fetched = service.listResourceRecordSets(id, startName, startType, maxItems + 1);
            boolean truncated = fetched.size() > maxItems;
            List<ResourceRecordSet> records = truncated ? fetched.subList(0, maxItems) : fetched;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListResourceRecordSetsResponse", NS)
                    .start("ResourceRecordSets");
            for (ResourceRecordSet rrs : records) {
                xml.raw(xmlResourceRecordSet(rrs));
            }
            xml.end("ResourceRecordSets")
               .elem("IsTruncated", String.valueOf(truncated));
            if (truncated) {
                ResourceRecordSet next = fetched.get(maxItems);
                xml.elem("NextRecordName", next.getName())
                   .elem("NextRecordType", next.getType());
            }
            xml.elem("MaxItems", String.valueOf(maxItems))
               .end("ListResourceRecordSetsResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Changes ───────────────────────────────────────────────────────────────

    @GET
    @Path("/change/{Id}")
    public Response getChange(@PathParam("Id") String id) {
        try {
            ChangeInfo change = service.getChange(id);
            String xml = new XmlBuilder()
                    .start("GetChangeResponse", NS)
                    .raw(xmlChangeInfo(change))
                    .end("GetChangeResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Health Checks ─────────────────────────────────────────────────────────

    @POST
    @Path("/healthcheck")
    public Response createHealthCheck(String body) {
        try {
            String callerRef = XmlParser.extractFirst(body, "CallerReference", null);
            if (callerRef == null) {
                throw new AwsException("InvalidInput", "CallerReference is required.", 400);
            }
            HealthCheckConfig cfg = parseHealthCheckConfig(body);
            HealthCheck hc = service.createHealthCheck(callerRef, cfg);
            String xml = new XmlBuilder()
                    .start("CreateHealthCheckResponse", NS)
                    .raw(xmlHealthCheck(hc))
                    .end("CreateHealthCheckResponse")
                    .build();
            return Response.created(URI.create("/2013-04-01/healthcheck/" + hc.getId()))
                    .type(XML)
                    .entity(xml)
                    .build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/healthcheck/{HealthCheckId}")
    public Response getHealthCheck(@PathParam("HealthCheckId") String id) {
        try {
            HealthCheck hc = service.getHealthCheck(id);
            String xml = new XmlBuilder()
                    .start("GetHealthCheckResponse", NS)
                    .raw(xmlHealthCheck(hc))
                    .end("GetHealthCheckResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @DELETE
    @Path("/healthcheck/{HealthCheckId}")
    public Response deleteHealthCheck(@PathParam("HealthCheckId") String id) {
        try {
            service.deleteHealthCheck(id);
            return Response.ok("", XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/healthcheck")
    public Response listHealthChecks(@QueryParam("marker") String marker,
                                      @QueryParam("maxitems") @DefaultValue("100") int maxItems) {
        try {
            List<HealthCheck> checks = service.listHealthChecks(marker, maxItems);
            boolean truncated = checks.size() == maxItems;

            XmlBuilder xml = new XmlBuilder()
                    .start("ListHealthChecksResponse", NS)
                    .start("HealthChecks");
            for (HealthCheck hc : checks) {
                xml.raw(xmlHealthCheck(hc));
            }
            xml.end("HealthChecks")
               .elem("Marker", marker != null ? marker : "")
               .elem("IsTruncated", String.valueOf(truncated))
               .elem("MaxItems", String.valueOf(maxItems))
               .end("ListHealthChecksResponse");

            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/healthcheck/{HealthCheckId}")
    public Response updateHealthCheck(@PathParam("HealthCheckId") String id, String body) {
        try {
            HealthCheckConfig cfg = parseHealthCheckConfig(body);
            HealthCheck hc = service.updateHealthCheck(id, cfg);
            String xml = new XmlBuilder()
                    .start("UpdateHealthCheckResponse", NS)
                    .raw(xmlHealthCheck(hc))
                    .end("UpdateHealthCheckResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    @GET
    @Path("/tags/{ResourceType}/{ResourceId}")
    public Response listTagsForResource(@PathParam("ResourceType") String type,
                                         @PathParam("ResourceId") String resourceId) {
        try {
            Map<String, String> tags = service.listTagsForResource(type, resourceId);
            XmlBuilder xml = new XmlBuilder()
                    .start("ListTagsForResourceResponse", NS)
                    .start("ResourceTagSet")
                    .elem("ResourceType", type)
                    .elem("ResourceId", resourceId)
                    .start("Tags");
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                xml.start("Tag")
                   .elem("Key", entry.getKey())
                   .elem("Value", entry.getValue())
                   .end("Tag");
            }
            xml.end("Tags").end("ResourceTagSet").end("ListTagsForResourceResponse");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @POST
    @Path("/tags/{ResourceType}/{ResourceId}")
    public Response changeTagsForResource(@PathParam("ResourceType") String type,
                                           @PathParam("ResourceId") String resourceId,
                                           String body) {
        try {
            List<Map<String, String>> addTags = XmlParser.extractGroups(body, "Tag").stream()
                    .filter(g -> g.containsKey("Key"))
                    .map(g -> Map.of("Key", g.get("Key"), "Value", g.getOrDefault("Value", "")))
                    .toList();
            List<String> removeTagKeys = parseRemoveTagKeys(body);
            service.changeTagsForResource(type, resourceId, addTags, removeTagKeys);
            String xml = new XmlBuilder()
                    .start("ChangeTagsForResourceResponse", NS)
                    .end("ChangeTagsForResourceResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    // ── Limits ────────────────────────────────────────────────────────────────

    @GET
    @Path("/accountlimit/{Type}")
    public Response getAccountLimit(@PathParam("Type") String type) {
        long value = switch (type) {
            case "MAX_HEALTH_CHECKS_BY_OWNER" -> 200L;
            case "MAX_HOSTED_ZONES_BY_OWNER" -> 500L;
            case "MAX_REUSABLE_DELEGATION_SETS_BY_OWNER" -> 100L;
            case "MAX_TRAFFIC_POLICY_INSTANCES_BY_OWNER" -> 5L;
            case "MAX_TRAFFIC_POLICIES_BY_OWNER" -> 50L;
            default -> 100L;
        };
        String xml = new XmlBuilder()
                .start("GetAccountLimitResponse", NS)
                .start("Limit")
                .elem("Type", type)
                .elem("Value", value)
                .end("Limit")
                .elem("Count", 0L)
                .end("GetAccountLimitResponse")
                .build();
        return Response.ok(xml, XML).build();
    }

    @GET
    @Path("/healthcheck/{HealthCheckId}/status")
    public Response getHealthCheckStatus(@PathParam("HealthCheckId") String id) {
        try {
            service.getHealthCheck(id);
            String now = Instant.now().toString();
            String xml = new XmlBuilder()
                    .start("GetHealthCheckStatusResponse", NS)
                    .start("HealthCheckObservations")
                    .start("HealthCheckObservation")
                    .elem("IPAddress", "1.2.3.4")
                    .elem("Region", "us-east-1")
                    .start("StatusReport")
                    .elem("Status", "Success: HTTP Status Code 200, OK")
                    .elem("CheckedTime", now)
                    .end("StatusReport")
                    .end("HealthCheckObservation")
                    .end("HealthCheckObservations")
                    .end("GetHealthCheckStatusResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzone/{Id}/dnssec")
    public Response getDnssec(@PathParam("Id") String id) {
        try {
            service.getHostedZone(id);
            String xml = new XmlBuilder()
                    .start("GetDNSSECResponse", NS)
                    .start("Status")
                    .elem("ServeSignature", "NOT_SIGNING")
                    .elem("StatusMessage", "Zone is not signing")
                    .end("Status")
                    .start("KeySigningKeys")
                    .end("KeySigningKeys")
                    .end("GetDNSSECResponse")
                    .build();
            return Response.ok(xml, XML).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    @GET
    @Path("/hostedzonelimit/{HostedZoneId}/{Type}")
    public Response getHostedZoneLimit(@PathParam("HostedZoneId") String zoneId,
                                        @PathParam("Type") String type) {
        try {
            service.getHostedZone(zoneId);
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
        long value = switch (type) {
            case "MAX_RRSETS_BY_ZONE" -> 10000L;
            case "MAX_VPCS_ASSOCIATED_BY_ZONE" -> 100L;
            default -> 100L;
        };
        String xml = new XmlBuilder()
                .start("GetHostedZoneLimitResponse", NS)
                .start("Limit")
                .elem("Type", type)
                .elem("Value", value)
                .end("Limit")
                .elem("Count", 0L)
                .end("GetHostedZoneLimitResponse")
                .build();
        return Response.ok(xml, XML).build();
    }

    // ── XML builders ──────────────────────────────────────────────────────────

    private String xmlHostedZone(HostedZone zone) {
        return new XmlBuilder()
                .start("HostedZone")
                .elem("Id", "/hostedzone/" + zone.getId())
                .elem("Name", zone.getName())
                .elem("CallerReference", zone.getCallerReference())
                .start("Config")
                .elem("Comment", zone.getComment())
                .elem("PrivateZone", String.valueOf(zone.isPrivateZone()))
                .end("Config")
                .elem("ResourceRecordSetCount", zone.getResourceRecordSetCount())
                .end("HostedZone")
                .build();
    }

    private String xmlChangeInfo(ChangeInfo change) {
        return new XmlBuilder()
                .start("ChangeInfo")
                .elem("Id", "/change/" + change.getId())
                .elem("Status", change.getStatus())
                .elem("SubmittedAt", change.getSubmittedAt())
                .elem("Comment", change.getComment())
                .end("ChangeInfo")
                .build();
    }

    private String xmlDelegationSet() {
        XmlBuilder xml = new XmlBuilder()
                .start("DelegationSet")
                .start("NameServers");
        for (String ns : service.getNameServers()) {
            xml.elem("NameServer", ns);
        }
        xml.end("NameServers").end("DelegationSet");
        return xml.build();
    }

    private String xmlVpcAssociation(VpcAssociation association) {
        return new XmlBuilder()
                .start("VPC")
                .elem("VPCRegion", association.getVpcRegion())
                .elem("VPCId", association.getVpcId())
                .end("VPC")
                .build();
    }

    private String xmlVpcAssociations(List<VpcAssociation> associations) {
        if (associations == null || associations.isEmpty()) {
            return "";
        }
        XmlBuilder xml = new XmlBuilder().start("VPCs");
        for (VpcAssociation association : associations) {
            xml.raw(xmlVpcAssociation(association));
        }
        return xml.end("VPCs").build();
    }

    private String xmlHostedZoneSummary(HostedZone zone) {
        return new XmlBuilder()
                .start("HostedZoneSummary")
                .elem("HostedZoneId", zone.getId())
                .elem("Name", zone.getName())
                .start("Owner")
                .elem("OwningAccount", service.getDefaultAccountId())
                .end("Owner")
                .end("HostedZoneSummary")
                .build();
    }

    private String xmlResourceRecordSet(ResourceRecordSet rrs) {
        XmlBuilder xml = new XmlBuilder()
                .start("ResourceRecordSet")
                .elem("Name", rrs.getName())
                .elem("Type", rrs.getType());
        if (rrs.getSetIdentifier() != null) xml.elem("SetIdentifier", rrs.getSetIdentifier());
        if (rrs.getWeight() != null) xml.elem("Weight", rrs.getWeight());
        if (rrs.getRegion() != null) xml.elem("Region", rrs.getRegion());
        if (rrs.getFailover() != null) xml.elem("Failover", rrs.getFailover());
        if (rrs.getTtl() != null) xml.elem("TTL", rrs.getTtl());
        if (rrs.getRecords() != null && !rrs.getRecords().isEmpty()) {
            xml.start("ResourceRecords");
            for (ResourceRecord r : rrs.getRecords()) {
                xml.start("ResourceRecord").elem("Value", r.getValue()).end("ResourceRecord");
            }
            xml.end("ResourceRecords");
        }
        if (rrs.getAliasTarget() != null) {
            AliasTarget at = rrs.getAliasTarget();
            xml.start("AliasTarget")
               .elem("HostedZoneId", at.getHostedZoneId())
               .elem("DNSName", at.getDnsName())
               .elem("EvaluateTargetHealth", String.valueOf(at.isEvaluateTargetHealth()))
               .end("AliasTarget");
        }
        if (rrs.getHealthCheckId() != null) xml.elem("HealthCheckId", rrs.getHealthCheckId());
        xml.end("ResourceRecordSet");
        return xml.build();
    }

    private String xmlHealthCheck(HealthCheck hc) {
        XmlBuilder xml = new XmlBuilder()
                .start("HealthCheck")
                .elem("Id", hc.getId())
                .elem("CallerReference", hc.getCallerReference());
        if (hc.getConfig() != null) {
            HealthCheckConfig cfg = hc.getConfig();
            xml.start("HealthCheckConfig")
               .elem("Type", cfg.getType())
               .elem("IPAddress", cfg.getIpAddress())
               .elem("Port", cfg.getPort() != null ? String.valueOf(cfg.getPort()) : null)
               .elem("ResourcePath", cfg.getResourcePath())
               .elem("FullyQualifiedDomainName", cfg.getFullyQualifiedDomainName())
               .elem("RequestInterval",
                       cfg.getRequestInterval() != null ? String.valueOf(cfg.getRequestInterval()) : null)
               .elem("FailureThreshold",
                       cfg.getFailureThreshold() != null ? String.valueOf(cfg.getFailureThreshold()) : null)
               .end("HealthCheckConfig");
        }
        xml.elem("HealthCheckVersion", hc.getHealthCheckVersion())
           .end("HealthCheck");
        return xml.build();
    }

    private Response xmlErrorResponse(AwsException e) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", NS)
                .start("Error")
                .elem("Type", "Sender")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .end("Error")
                .elem("RequestId", "00000000-0000-0000-0000-000000000000")
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus()).type(XML).entity(xml).build();
    }

    // ── Request parsers ───────────────────────────────────────────────────────

    private VpcAssociation parseVpcAssociation(String body) {
        List<Map<String, String>> vpcs = XmlParser.extractGroups(body, "VPC");
        if (vpcs.isEmpty()) {
            return null;
        }
        Map<String, String> vpc = vpcs.get(0);
        String vpcId = vpc.get("VPCId");
        String vpcRegion = vpc.get("VPCRegion");
        // AWS requires VPCId and VPCRegion together whenever VPC is present; a
        // half-populated element must not mark the zone private.
        if (vpcId == null || vpcId.isEmpty() || vpcRegion == null || vpcRegion.isEmpty()) {
            throw new AwsException(
                    "InvalidInput", "VPCId and VPCRegion are both required when VPC is specified.", 400);
        }
        return new VpcAssociation(vpcId, vpcRegion);
    }

    /**
     * Parses the VPC element for the association operations, where AWS marks VPC as a
     * required member — unlike CreateHostedZone, where its absence just means a public zone.
     */
    private VpcAssociation requireVpcAssociation(String body) {
        VpcAssociation vpc = requireVpcAssociationWithoutRegionCheck(body);
        requireModelledVpcRegion(vpc.getVpcRegion());
        return vpc;
    }

    /**
     * Same as {@link #requireVpcAssociation}, but skips the enum check: used by disassociate,
     * where the target may be a legacy-persisted association whose region has since left
     * VPC_REGIONS (or predates the enum check entirely). Rejecting the lookup here would strand
     * that association — impossible to remove without deleting the whole hosted zone.
     */
    private VpcAssociation requireVpcAssociationWithoutRegionCheck(String body) {
        VpcAssociation vpc = parseVpcAssociation(body);
        if (vpc == null) {
            throw new AwsException("InvalidInput", "VPC is required.", 400);
        }
        return vpc;
    }

    /**
     * Rejects a VPCRegion outside the modelled enum. Without this the association is stored
     * under a region that can never be matched again, so the associate reports success while
     * the later disassociate and ListHostedZonesByVPC silently miss it.
     */
    private static void requireModelledVpcRegion(String vpcRegion) {
        if (!VPC_REGIONS.contains(vpcRegion)) {
            throw new AwsException("InvalidInput",
                    "Invalid value '" + vpcRegion + "' at 'VPCRegion' failed to satisfy constraint: "
                            + "Member must satisfy enum value set.", 400);
        }
    }

    /**
     * Parses the ChangeBatch XML using StAX to correctly handle multiple Change elements,
     * each containing a ResourceRecordSet with its own set of ResourceRecord/Value children.
     */
    private List<Map<String, Object>> parseChangeBatch(String body) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (body == null || body.isEmpty()) return result;

        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            String currentAction = null;
            ResourceRecordSet currentRrs = null;
            List<ResourceRecord> currentRecords = null;
            AliasTarget currentAlias = null;
            int depth = 0;
            String currentElement = null;
            boolean inChangeBatch = false;
            boolean inChange = false;
            boolean inRrs = false;
            boolean inResourceRecords = false;
            boolean inAlias = false;
            boolean inHealthCheckConfig = false;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentElement = r.getLocalName();
                    switch (currentElement) {
                        case "ChangeBatch" -> inChangeBatch = true;
                        case "Change" -> {
                            if (inChangeBatch) {
                                inChange = true;
                                currentAction = null;
                                currentRrs = null;
                            }
                        }
                        case "Action" -> {
                            if (inChange && !inRrs) currentAction = r.getElementText();
                        }
                        case "ResourceRecordSet" -> {
                            if (inChange) {
                                inRrs = true;
                                currentRrs = new ResourceRecordSet();
                                currentRecords = new ArrayList<>();
                            }
                        }
                        case "ResourceRecords" -> { if (inRrs) inResourceRecords = true; }
                        case "AliasTarget" -> {
                            if (inRrs) {
                                inAlias = true;
                                currentAlias = new AliasTarget();
                            }
                        }
                        case "Name" -> {
                            if (inRrs && !inAlias) {
                                String n = r.getElementText();
                                if (n != null && !n.endsWith(".")) n = n + ".";
                                if (currentRrs != null) currentRrs.setName(n);
                            }
                        }
                        case "Type" -> {
                            if (inRrs && !inAlias && !inHealthCheckConfig && currentRrs != null) {
                                currentRrs.setType(r.getElementText());
                            }
                        }
                        case "TTL" -> {
                            if (inRrs && currentRrs != null) {
                                try { currentRrs.setTtl(Long.parseLong(r.getElementText())); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                        case "Value" -> {
                            if (inResourceRecords && currentRecords != null) {
                                currentRecords.add(new ResourceRecord(r.getElementText()));
                            }
                        }
                        case "SetIdentifier" -> {
                            if (inRrs && currentRrs != null) currentRrs.setSetIdentifier(r.getElementText());
                        }
                        case "Weight" -> {
                            if (inRrs && currentRrs != null) {
                                try { currentRrs.setWeight(Long.parseLong(r.getElementText())); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                        case "Region" -> {
                            if (inRrs && !inAlias && currentRrs != null) currentRrs.setRegion(r.getElementText());
                        }
                        case "Failover" -> {
                            if (inRrs && currentRrs != null) currentRrs.setFailover(r.getElementText());
                        }
                        case "HealthCheckId" -> {
                            if (inRrs && !inHealthCheckConfig && currentRrs != null) {
                                currentRrs.setHealthCheckId(r.getElementText());
                            }
                        }
                        case "HostedZoneId" -> {
                            if (inAlias && currentAlias != null) currentAlias.setHostedZoneId(r.getElementText());
                        }
                        case "DNSName" -> {
                            if (inAlias && currentAlias != null) currentAlias.setDnsName(r.getElementText());
                        }
                        case "EvaluateTargetHealth" -> {
                            if (inAlias && currentAlias != null) {
                                currentAlias.setEvaluateTargetHealth(
                                        "true".equalsIgnoreCase(r.getElementText()));
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "ResourceRecords" -> inResourceRecords = false;
                        case "AliasTarget" -> {
                            if (inAlias && currentRrs != null && currentAlias != null) {
                                currentRrs.setAliasTarget(currentAlias);
                            }
                            inAlias = false;
                            currentAlias = null;
                        }
                        case "ResourceRecordSet" -> {
                            if (inRrs && currentRrs != null && currentRecords != null) {
                                if (!currentRecords.isEmpty()) currentRrs.setRecords(currentRecords);
                            }
                            inRrs = false;
                        }
                        case "Change" -> {
                            if (inChange && currentAction != null && currentRrs != null) {
                                Map<String, Object> change = new HashMap<>();
                                change.put("action", currentAction);
                                change.put("rrs", currentRrs);
                                result.add(change);
                            }
                            inChange = false;
                            currentAction = null;
                            currentRrs = null;
                            currentRecords = null;
                        }
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }

    private HealthCheckConfig parseHealthCheckConfig(String body) {
        HealthCheckConfig cfg = new HealthCheckConfig();
        cfg.setType(XmlParser.extractFirst(body, "Type", null));
        cfg.setIpAddress(XmlParser.extractFirst(body, "IPAddress", null));
        String portStr = XmlParser.extractFirst(body, "Port", null);
        if (portStr != null) {
            try { cfg.setPort(Integer.parseInt(portStr)); } catch (NumberFormatException ignored) {}
        }
        cfg.setResourcePath(XmlParser.extractFirst(body, "ResourcePath", null));
        cfg.setFullyQualifiedDomainName(XmlParser.extractFirst(body, "FullyQualifiedDomainName", null));
        String riStr = XmlParser.extractFirst(body, "RequestInterval", null);
        if (riStr != null) {
            try { cfg.setRequestInterval(Integer.parseInt(riStr)); } catch (NumberFormatException ignored) {}
        }
        String ftStr = XmlParser.extractFirst(body, "FailureThreshold", null);
        if (ftStr != null) {
            try { cfg.setFailureThreshold(Integer.parseInt(ftStr)); } catch (NumberFormatException ignored) {}
        }
        return cfg;
    }

    /**
     * Parses Key elements that appear inside a RemoveTagKeys block only.
     * Uses StAX to avoid matching Key elements from AddTags.
     */
    private List<String> parseRemoveTagKeys(String body) {
        List<String> keys = new ArrayList<>();
        if (body == null || body.isEmpty()) return keys;
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(body));
            boolean inRemove = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("RemoveTagKeys".equals(r.getLocalName())) {
                        inRemove = true;
                    } else if (inRemove && "Key".equals(r.getLocalName())) {
                        keys.add(r.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("RemoveTagKeys".equals(r.getLocalName())) inRemove = false;
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return keys;
    }
}
