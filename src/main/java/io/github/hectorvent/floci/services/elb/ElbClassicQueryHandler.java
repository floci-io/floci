package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query-protocol handler for Classic (2012-06-01) Elastic Load Balancing.
 *
 * <p>Every response is emitted in the 2012-06-01 namespace with the wrapper element names the
 * 2012-06-01 model declares. A Classic {@code CreateLoadBalancer} answer carries {@code DNSName}
 * and nothing else — no ARN and no {@code LoadBalancers} member list, both of which belong to the
 * 2015-12-01 API.
 *
 * <p><b>Scope.</b> Implemented are the operations the AWS provider drives for {@code aws_elb} and
 * for an Auto Scaling group attached to one. The stickiness- and backend-policy operations are not
 * implemented and are answered with {@code UnsupportedOperation}, which is honest and lets a caller
 * see the boundary, rather than a fabricated success.
 */
@ApplicationScoped
public class ElbClassicQueryHandler {

    private static final Logger LOG = Logger.getLogger(ElbClassicQueryHandler.class);

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * Actions that exist only in the Classic API. Used by the dispatcher to route a request that
     * arrived without a {@code Version} parameter, and never overlapping {@link #SHARED_ACTIONS}.
     */
    public static final Set<String> CLASSIC_ONLY_ACTIONS = Set.of(
            "ConfigureHealthCheck",
            "RegisterInstancesWithLoadBalancer", "DeregisterInstancesFromLoadBalancer",
            "DescribeInstanceHealth",
            "CreateLoadBalancerListeners", "DeleteLoadBalancerListeners",
            "SetLoadBalancerListenerSSLCertificate",
            "ApplySecurityGroupsToLoadBalancer",
            "AttachLoadBalancerToSubnets", "DetachLoadBalancerFromSubnets",
            "EnableAvailabilityZonesForLoadBalancer", "DisableAvailabilityZonesForLoadBalancer",
            "CreateAppCookieStickinessPolicy", "CreateLBCookieStickinessPolicy",
            "CreateLoadBalancerPolicy", "DeleteLoadBalancerPolicy",
            "DescribeLoadBalancerPolicies", "DescribeLoadBalancerPolicyTypes",
            "SetLoadBalancerPoliciesForBackendServer", "SetLoadBalancerPoliciesOfListener"
    );

    /** Action names that both API versions define, and so cannot route on their own. */
    public static final Set<String> SHARED_ACTIONS = Set.of(
            "CreateLoadBalancer", "DeleteLoadBalancer", "DescribeLoadBalancers",
            "ModifyLoadBalancerAttributes", "DescribeLoadBalancerAttributes",
            "AddTags", "RemoveTags", "DescribeTags", "DescribeAccountLimits"
    );

    private static final Map<String, String> ACCOUNT_LIMITS = new LinkedHashMap<>();

    static {
        ACCOUNT_LIMITS.put("classic-load-balancers", "20");
        ACCOUNT_LIMITS.put("classic-listeners", "100");
        ACCOUNT_LIMITS.put("classic-registered-instances", "1000");
    }

    private final ElbClassicService service;
    private final EmulatorConfig config;

    @Inject
    public ElbClassicQueryHandler(ElbClassicService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("ELB Classic action: {0}", action);
        if (!config.services().elb().enabled()) {
            return xmlError("ServiceUnavailable",
                    "The Elastic Load Balancing 2012-06-01 API is disabled "
                            + "(floci.services.elb.enabled=false).", 503);
        }
        try {
            return switch (action) {
                case "CreateLoadBalancer"        -> createLoadBalancer(params, region);
                case "DeleteLoadBalancer"        -> deleteLoadBalancer(params, region);
                case "DescribeLoadBalancers"     -> describeLoadBalancers(params, region);
                case "CreateLoadBalancerListeners" -> createListeners(params, region);
                case "DeleteLoadBalancerListeners" -> deleteListeners(params, region);
                case "ConfigureHealthCheck"      -> configureHealthCheck(params, region);
                case "RegisterInstancesWithLoadBalancer"    -> registerInstances(params, region);
                case "DeregisterInstancesFromLoadBalancer"  -> deregisterInstances(params, region);
                case "DescribeInstanceHealth"    -> describeInstanceHealth(params, region);
                case "ModifyLoadBalancerAttributes"   -> modifyAttributes(params, region);
                case "DescribeLoadBalancerAttributes" -> describeAttributes(params, region);
                case "ApplySecurityGroupsToLoadBalancer" -> applySecurityGroups(params, region);
                case "AttachLoadBalancerToSubnets"    -> attachSubnets(params, region);
                case "DetachLoadBalancerFromSubnets"  -> detachSubnets(params, region);
                case "EnableAvailabilityZonesForLoadBalancer"  -> enableZones(params, region);
                case "DisableAvailabilityZonesForLoadBalancer" -> disableZones(params, region);
                case "AddTags"                   -> addTags(params, region);
                case "RemoveTags"                -> removeTags(params, region);
                case "DescribeTags"              -> describeTags(params, region);
                case "DescribeAccountLimits"     -> describeAccountLimits();
                default -> xmlError("UnsupportedOperation",
                        "Action " + action + " is not supported by the Elastic Load Balancing "
                                + "2012-06-01 API in Floci.", 400);
            };
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // ── Load balancers ────────────────────────────────────────────────────────

    private Response createLoadBalancer(MultivaluedMap<String, String> p, String region) {
        ClassicLoadBalancer lb = service.createLoadBalancer(
                region,
                p.getFirst("LoadBalancerName"),
                parseListeners(p, "Listeners"),
                memberList(p, "AvailabilityZones"),
                memberList(p, "Subnets"),
                memberList(p, "SecurityGroups"),
                p.getFirst("Scheme"),
                parseTags(p));

        // The 2012-06-01 model gives CreateAccessPointOutput exactly one member: DNSName.
        return ok(new XmlBuilder()
                .start("CreateLoadBalancerResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("CreateLoadBalancerResult")
                    .elem("DNSName", lb.getDnsName())
                  .end("CreateLoadBalancerResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("CreateLoadBalancerResponse")
                .build());
    }

    private Response deleteLoadBalancer(MultivaluedMap<String, String> p, String region) {
        service.deleteLoadBalancer(region, requireName(p));
        return emptyResult("DeleteLoadBalancer");
    }

    private Response describeLoadBalancers(MultivaluedMap<String, String> p, String region) {
        List<ClassicLoadBalancer> lbs = service.describeLoadBalancers(region, memberList(p, "LoadBalancerNames"));

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLoadBalancersResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("DescribeLoadBalancersResult")
                    .start("LoadBalancerDescriptions");
        for (ClassicLoadBalancer lb : lbs) {
            xml.start("member").raw(loadBalancerDescriptionXml(lb)).end("member");
        }
        xml.end("LoadBalancerDescriptions")
           .end("DescribeLoadBalancersResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeLoadBalancersResponse");
        return ok(xml.build());
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private Response createListeners(MultivaluedMap<String, String> p, String region) {
        service.createLoadBalancerListeners(region, requireName(p), parseListeners(p, "Listeners"));
        return emptyResult("CreateLoadBalancerListeners");
    }

    private Response deleteListeners(MultivaluedMap<String, String> p, String region) {
        List<Integer> ports = new ArrayList<>();
        for (String port : memberList(p, "LoadBalancerPorts")) {
            ports.add(Integer.parseInt(port));
        }
        service.deleteLoadBalancerListeners(region, requireName(p), ports);
        return emptyResult("DeleteLoadBalancerListeners");
    }

    // ── Health ────────────────────────────────────────────────────────────────

    private Response configureHealthCheck(MultivaluedMap<String, String> p, String region) {
        ClassicHealthCheck hc = new ClassicHealthCheck();
        hc.setTarget(p.getFirst("HealthCheck.Target"));
        hc.setInterval(intOrNull(p.getFirst("HealthCheck.Interval")));
        hc.setTimeout(intOrNull(p.getFirst("HealthCheck.Timeout")));
        hc.setUnhealthyThreshold(intOrNull(p.getFirst("HealthCheck.UnhealthyThreshold")));
        hc.setHealthyThreshold(intOrNull(p.getFirst("HealthCheck.HealthyThreshold")));

        ClassicHealthCheck stored = service.configureHealthCheck(region, requireName(p), hc);
        return ok(new XmlBuilder()
                .start("ConfigureHealthCheckResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("ConfigureHealthCheckResult")
                    .raw(healthCheckXml(stored))
                  .end("ConfigureHealthCheckResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("ConfigureHealthCheckResponse")
                .build());
    }

    private Response registerInstances(MultivaluedMap<String, String> p, String region) {
        List<String> registered = service.registerInstances(region, requireName(p), parseInstanceIds(p));
        return ok(instanceListResponse("RegisterInstancesWithLoadBalancer", registered));
    }

    private Response deregisterInstances(MultivaluedMap<String, String> p, String region) {
        List<String> remaining = service.deregisterInstances(region, requireName(p), parseInstanceIds(p));
        return ok(instanceListResponse("DeregisterInstancesFromLoadBalancer", remaining));
    }

    private Response describeInstanceHealth(MultivaluedMap<String, String> p, String region) {
        Map<String, ElbClassicHealthChecker.InstanceHealth> health =
                service.describeInstanceHealth(region, requireName(p), parseInstanceIds(p));

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInstanceHealthResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("DescribeInstanceHealthResult")
                    .start("InstanceStates");
        for (Map.Entry<String, ElbClassicHealthChecker.InstanceHealth> e : health.entrySet()) {
            xml.start("member")
                 .elem("InstanceId", e.getKey())
                 .elem("State", e.getValue().state())
                 .elem("ReasonCode", e.getValue().reasonCode())
                 .elem("Description", e.getValue().description())
               .end("member");
        }
        xml.end("InstanceStates")
           .end("DescribeInstanceHealthResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeInstanceHealthResponse");
        return ok(xml.build());
    }

    // ── Attributes ────────────────────────────────────────────────────────────

    private Response modifyAttributes(MultivaluedMap<String, String> p, String region) {
        String prefix = "LoadBalancerAttributes.";
        Map<String, String> additional = new LinkedHashMap<>();
        int i = 1;
        while (true) {
            String key = p.getFirst(prefix + "AdditionalAttributes.member." + i + ".Key");
            if (key == null) break;
            additional.put(key, p.getFirst(prefix + "AdditionalAttributes.member." + i + ".Value"));
            i++;
        }
        ElbClassicService.AttributeUpdate update = new ElbClassicService.AttributeUpdate(
                boolOrNull(p.getFirst(prefix + "CrossZoneLoadBalancing.Enabled")),
                boolOrNull(p.getFirst(prefix + "AccessLog.Enabled")),
                p.getFirst(prefix + "AccessLog.S3BucketName"),
                p.getFirst(prefix + "AccessLog.S3BucketPrefix"),
                intOrNull(p.getFirst(prefix + "AccessLog.EmitInterval")),
                boolOrNull(p.getFirst(prefix + "ConnectionDraining.Enabled")),
                intOrNull(p.getFirst(prefix + "ConnectionDraining.Timeout")),
                intOrNull(p.getFirst(prefix + "ConnectionSettings.IdleTimeout")),
                additional);

        ClassicLoadBalancer lb = service.modifyLoadBalancerAttributes(region, requireName(p), update);
        return ok(new XmlBuilder()
                .start("ModifyLoadBalancerAttributesResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("ModifyLoadBalancerAttributesResult")
                    .elem("LoadBalancerName", lb.getLoadBalancerName())
                    .raw(attributesXml(lb.getAttributes()))
                  .end("ModifyLoadBalancerAttributesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("ModifyLoadBalancerAttributesResponse")
                .build());
    }

    private Response describeAttributes(MultivaluedMap<String, String> p, String region) {
        ClassicLoadBalancer lb = service.requireLoadBalancer(region, requireName(p));
        return ok(new XmlBuilder()
                .start("DescribeLoadBalancerAttributesResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("DescribeLoadBalancerAttributesResult")
                    .raw(attributesXml(lb.getAttributes()))
                  .end("DescribeLoadBalancerAttributesResult")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("DescribeLoadBalancerAttributesResponse")
                .build());
    }

    // ── Zones, subnets, security groups ───────────────────────────────────────

    private Response applySecurityGroups(MultivaluedMap<String, String> p, String region) {
        List<String> sgs = service.applySecurityGroups(region, requireName(p), memberList(p, "SecurityGroups"));
        return ok(stringListResponse("ApplySecurityGroupsToLoadBalancer", "SecurityGroups", sgs));
    }

    private Response attachSubnets(MultivaluedMap<String, String> p, String region) {
        List<String> subnets = service.attachToSubnets(region, requireName(p), memberList(p, "Subnets"));
        return ok(stringListResponse("AttachLoadBalancerToSubnets", "Subnets", subnets));
    }

    private Response detachSubnets(MultivaluedMap<String, String> p, String region) {
        List<String> subnets = service.detachFromSubnets(region, requireName(p), memberList(p, "Subnets"));
        return ok(stringListResponse("DetachLoadBalancerFromSubnets", "Subnets", subnets));
    }

    private Response enableZones(MultivaluedMap<String, String> p, String region) {
        List<String> zones = service.enableAvailabilityZones(region, requireName(p),
                memberList(p, "AvailabilityZones"));
        return ok(stringListResponse("EnableAvailabilityZonesForLoadBalancer", "AvailabilityZones", zones));
    }

    private Response disableZones(MultivaluedMap<String, String> p, String region) {
        List<String> zones = service.disableAvailabilityZones(region, requireName(p),
                memberList(p, "AvailabilityZones"));
        return ok(stringListResponse("DisableAvailabilityZonesForLoadBalancer", "AvailabilityZones", zones));
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private Response addTags(MultivaluedMap<String, String> p, String region) {
        service.addTags(region, memberList(p, "LoadBalancerNames"), parseTags(p));
        return emptyResult("AddTags");
    }

    private Response removeTags(MultivaluedMap<String, String> p, String region) {
        // RemoveTags carries TagKeyOnly members — Key without Value.
        List<String> keys = new ArrayList<>();
        int i = 1;
        while (true) {
            String key = p.getFirst("Tags.member." + i + ".Key");
            if (key == null) break;
            keys.add(key);
            i++;
        }
        service.removeTags(region, memberList(p, "LoadBalancerNames"), keys);
        return emptyResult("RemoveTags");
    }

    private Response describeTags(MultivaluedMap<String, String> p, String region) {
        Map<String, Map<String, String>> tags =
                service.describeTags(region, memberList(p, "LoadBalancerNames"));

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTagsResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("DescribeTagsResult")
                    .start("TagDescriptions");
        for (Map.Entry<String, Map<String, String>> lbTags : tags.entrySet()) {
            xml.start("member")
                 .elem("LoadBalancerName", lbTags.getKey())
                 .start("Tags");
            for (Map.Entry<String, String> tag : lbTags.getValue().entrySet()) {
                xml.start("member").elem("Key", tag.getKey()).elem("Value", tag.getValue()).end("member");
            }
            xml.end("Tags").end("member");
        }
        xml.end("TagDescriptions")
           .end("DescribeTagsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeTagsResponse");
        return ok(xml.build());
    }

    private Response describeAccountLimits() {
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAccountLimitsResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("DescribeAccountLimitsResult")
                    .start("Limits");
        for (Map.Entry<String, String> limit : ACCOUNT_LIMITS.entrySet()) {
            xml.start("member").elem("Name", limit.getKey()).elem("Max", limit.getValue()).end("member");
        }
        xml.end("Limits")
           .end("DescribeAccountLimitsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeAccountLimitsResponse");
        return ok(xml.build());
    }

    // ── XML fragments ─────────────────────────────────────────────────────────

    private String loadBalancerDescriptionXml(ClassicLoadBalancer lb) {
        XmlBuilder xml = new XmlBuilder()
                .elem("LoadBalancerName", lb.getLoadBalancerName())
                .elem("DNSName", lb.getDnsName())
                .elem("CanonicalHostedZoneName", lb.getCanonicalHostedZoneName())
                .elem("CanonicalHostedZoneNameID", lb.getCanonicalHostedZoneNameId())
                .start("ListenerDescriptions");
        for (ClassicListener listener : lb.getListeners()) {
            xml.start("member")
                 .start("Listener")
                   .elem("Protocol", listener.getProtocol())
                   .elem("LoadBalancerPort", String.valueOf(listener.getLoadBalancerPort()))
                   .elem("InstanceProtocol", listener.getInstanceProtocol())
                   .elem("InstancePort", String.valueOf(listener.getInstancePort()))
                   .elem("SSLCertificateId", listener.getSslCertificateId())
                 .end("Listener")
                 .start("PolicyNames").end("PolicyNames")
               .end("member");
        }
        xml.end("ListenerDescriptions")
           // Floci implements no Classic policies; the element is present and empty, as it is on a
           // live load balancer that has none.
           .start("Policies")
             .start("AppCookieStickinessPolicies").end("AppCookieStickinessPolicies")
             .start("LBCookieStickinessPolicies").end("LBCookieStickinessPolicies")
             .start("OtherPolicies").end("OtherPolicies")
           .end("Policies")
           .start("BackendServerDescriptions").end("BackendServerDescriptions")
           .start("AvailabilityZones");
        for (String zone : lb.getAvailabilityZones()) {
            xml.elem("member", zone);
        }
        xml.end("AvailabilityZones").start("Subnets");
        for (String subnet : lb.getSubnets()) {
            xml.elem("member", subnet);
        }
        xml.end("Subnets")
           .elem("VPCId", lb.getVpcId())
           .start("Instances");
        for (String instanceId : lb.getInstanceIds()) {
            xml.start("member").elem("InstanceId", instanceId).end("member");
        }
        xml.end("Instances")
           .raw(healthCheckXml(lb.getHealthCheck()))
           .start("SourceSecurityGroup")
             .elem("OwnerAlias", lb.getSourceSecurityGroupOwnerAlias())
             .elem("GroupName", lb.getSourceSecurityGroupName())
           .end("SourceSecurityGroup")
           .start("SecurityGroups");
        for (String sg : lb.getSecurityGroups()) {
            xml.elem("member", sg);
        }
        xml.end("SecurityGroups")
           .elem("CreatedTime", lb.getCreatedTime() != null ? ISO_FMT.format(lb.getCreatedTime()) : null)
           .elem("Scheme", lb.getScheme());
        return xml.build();
    }

    private static String healthCheckXml(ClassicHealthCheck hc) {
        return new XmlBuilder()
                .start("HealthCheck")
                  .elem("Target", hc.getTarget())
                  .elem("Interval", String.valueOf(hc.getInterval()))
                  .elem("Timeout", String.valueOf(hc.getTimeout()))
                  .elem("UnhealthyThreshold", String.valueOf(hc.getUnhealthyThreshold()))
                  .elem("HealthyThreshold", String.valueOf(hc.getHealthyThreshold()))
                .end("HealthCheck")
                .build();
    }

    private static String attributesXml(ClassicLoadBalancerAttributes a) {
        XmlBuilder xml = new XmlBuilder()
                .start("LoadBalancerAttributes")
                  .start("CrossZoneLoadBalancing")
                    .elem("Enabled", a.isCrossZoneLoadBalancingEnabled())
                  .end("CrossZoneLoadBalancing")
                  .start("AccessLog")
                    .elem("Enabled", a.isAccessLogEnabled())
                    .elem("S3BucketName", a.getAccessLogS3BucketName())
                    .elem("S3BucketPrefix", a.getAccessLogS3BucketPrefix())
                    .elem("EmitInterval", a.getAccessLogEmitInterval() != null
                            ? String.valueOf(a.getAccessLogEmitInterval()) : null)
                  .end("AccessLog")
                  .start("ConnectionDraining")
                    .elem("Enabled", a.isConnectionDrainingEnabled())
                    .elem("Timeout", a.getConnectionDrainingTimeout() != null
                            ? String.valueOf(a.getConnectionDrainingTimeout()) : null)
                  .end("ConnectionDraining")
                  .start("ConnectionSettings")
                    .elem("IdleTimeout", a.getIdleTimeout() != null
                            ? String.valueOf(a.getIdleTimeout()) : null)
                  .end("ConnectionSettings")
                  .start("AdditionalAttributes");
        for (Map.Entry<String, String> e : a.getAdditionalAttributes().entrySet()) {
            xml.start("member").elem("Key", e.getKey()).elem("Value", e.getValue()).end("member");
        }
        xml.end("AdditionalAttributes").end("LoadBalancerAttributes");
        return xml.build();
    }

    private static String instanceListResponse(String action, List<String> instanceIds) {
        XmlBuilder xml = new XmlBuilder()
                .start(action + "Response", AwsNamespaces.ELB_CLASSIC)
                  .start(action + "Result")
                    .start("Instances");
        for (String id : instanceIds) {
            xml.start("member").elem("InstanceId", id).end("member");
        }
        xml.end("Instances")
           .end(action + "Result")
           .raw(AwsQueryResponse.responseMetadata())
           .end(action + "Response");
        return xml.build();
    }

    private static String stringListResponse(String action, String listElement, List<String> values) {
        XmlBuilder xml = new XmlBuilder()
                .start(action + "Response", AwsNamespaces.ELB_CLASSIC)
                  .start(action + "Result")
                    .start(listElement);
        for (String value : values) {
            xml.elem("member", value);
        }
        xml.end(listElement)
           .end(action + "Result")
           .raw(AwsQueryResponse.responseMetadata())
           .end(action + "Response");
        return xml.build();
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private static String requireName(MultivaluedMap<String, String> p) {
        String name = p.getFirst("LoadBalancerName");
        if (name == null) {
            throw new AwsException("ValidationError",
                    "LoadBalancerName is required for load balancer.", 400);
        }
        return name;
    }

    private static List<String> memberList(MultivaluedMap<String, String> p, String prefix) {
        List<String> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String value = p.getFirst(prefix + ".member." + i);
            if (value == null) break;
            result.add(value);
            i++;
        }
        return result;
    }

    private static List<String> parseInstanceIds(MultivaluedMap<String, String> p) {
        List<String> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String id = p.getFirst("Instances.member." + i + ".InstanceId");
            if (id == null) break;
            result.add(id);
            i++;
        }
        return result;
    }

    private static List<ClassicListener> parseListeners(MultivaluedMap<String, String> p, String prefix) {
        List<ClassicListener> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String base = prefix + ".member." + i + ".";
            String protocol = p.getFirst(base + "Protocol");
            String lbPort = p.getFirst(base + "LoadBalancerPort");
            if (protocol == null && lbPort == null) break;
            ClassicListener listener = new ClassicListener();
            listener.setProtocol(protocol);
            listener.setLoadBalancerPort(intOrNull(lbPort));
            listener.setInstanceProtocol(p.getFirst(base + "InstanceProtocol"));
            listener.setInstancePort(intOrNull(p.getFirst(base + "InstancePort")));
            listener.setSslCertificateId(p.getFirst(base + "SSLCertificateId"));
            result.add(listener);
            i++;
        }
        return result;
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> p) {
        Map<String, String> tags = new LinkedHashMap<>();
        int i = 1;
        while (true) {
            String key = p.getFirst("Tags.member." + i + ".Key");
            if (key == null) break;
            String value = p.getFirst("Tags.member." + i + ".Value");
            tags.put(key, value != null ? value : "");
            i++;
        }
        return tags;
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", "'" + s + "' is not a valid integer.", 400);
        }
    }

    private static Boolean boolOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        return Boolean.valueOf(s);
    }

    private static Response ok(String xml) {
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private static Response emptyResult(String action) {
        return ok(new XmlBuilder()
                .start(action + "Response", AwsNamespaces.ELB_CLASSIC)
                  .start(action + "Result").end(action + "Result")
                  .raw(AwsQueryResponse.responseMetadata())
                .end(action + "Response")
                .build());
    }

    private static Response xmlError(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", AwsNamespaces.ELB_CLASSIC)
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .raw(AwsQueryResponse.responseMetadata())
                .end("ErrorResponse")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
