package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elbv2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
public class ElbV2QueryHandler {

    private static final Logger LOG = Logger.getLogger(ElbV2QueryHandler.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    // Pre-seeded SSL policies
    private static final List<String> SSL_POLICIES = List.of(
            "ELBSecurityPolicy-TLS13-1-2-2021-06",
            "ELBSecurityPolicy-TLS13-1-2-Res-2021-06",
            "ELBSecurityPolicy-TLS13-1-2-Ext1-2021-06",
            "ELBSecurityPolicy-TLS13-1-2-Ext2-2021-06",
            "ELBSecurityPolicy-TLS13-1-1-2021-06",
            "ELBSecurityPolicy-TLS13-1-0-2021-06",
            "ELBSecurityPolicy-2016-08",
            "ELBSecurityPolicy-FS-1-2-Res-2020-10",
            "ELBSecurityPolicy-FS-1-2-2019-08"
    );

    private static final Map<String, String> ACCOUNT_LIMITS = new LinkedHashMap<>();

    static {
        ACCOUNT_LIMITS.put("application-load-balancers", "50");
        ACCOUNT_LIMITS.put("network-load-balancers", "50");
        ACCOUNT_LIMITS.put("gateway-load-balancers", "100");
        ACCOUNT_LIMITS.put("target-groups", "3000");
        ACCOUNT_LIMITS.put("listeners-per-application-load-balancer", "50");
        ACCOUNT_LIMITS.put("rules-per-application-load-balancer", "100");
        ACCOUNT_LIMITS.put("target-groups-per-application-load-balancer", "100");
        ACCOUNT_LIMITS.put("targets-per-application-load-balancer", "1000");
        ACCOUNT_LIMITS.put("certificates-per-application-load-balancer", "25");
        ACCOUNT_LIMITS.put("condition-values-per-alb-rule", "5");
        ACCOUNT_LIMITS.put("condition-wildcards-per-alb-rule", "5");
    }

    private final ElbV2Service service;

    @Inject
    public ElbV2QueryHandler(ElbV2Service service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("ELBv2 action: {0}", action);
        try {
            return switch (action) {
                // Load Balancers
                case "CreateLoadBalancer"            -> handleCreateLoadBalancer(params, region);
                case "DescribeLoadBalancers"         -> handleDescribeLoadBalancers(params, region);
                case "DeleteLoadBalancer"            -> handleDeleteLoadBalancer(params, region);
                case "ModifyLoadBalancerAttributes"  -> handleModifyLoadBalancerAttributes(params, region);
                case "DescribeLoadBalancerAttributes"-> handleDescribeLoadBalancerAttributes(params, region);
                case "DescribeCapacityReservation"   -> handleDescribeCapacityReservation(params, region);
                case "SetSecurityGroups"             -> handleSetSecurityGroups(params, region);
                case "SetSubnets"                    -> handleSetSubnets(params, region);
                case "SetIpAddressType"              -> handleSetIpAddressType(params, region);
                // Target Groups
                case "CreateTargetGroup"             -> handleCreateTargetGroup(params, region);
                case "DescribeTargetGroups"          -> handleDescribeTargetGroups(params, region);
                case "DeleteTargetGroup"             -> handleDeleteTargetGroup(params, region);
                case "ModifyTargetGroup"             -> handleModifyTargetGroup(params, region);
                case "ModifyTargetGroupAttributes"   -> handleModifyTargetGroupAttributes(params, region);
                case "DescribeTargetGroupAttributes" -> handleDescribeTargetGroupAttributes(params, region);
                // Listeners
                case "CreateListener"                -> handleCreateListener(params, region);
                case "DescribeListeners"             -> handleDescribeListeners(params, region);
                case "DeleteListener"                -> handleDeleteListener(params, region);
                case "ModifyListener"                -> handleModifyListener(params, region);
                case "ModifyListenerAttributes"      -> handleModifyListenerAttributes(params, region);
                case "DescribeListenerAttributes"    -> handleDescribeListenerAttributes(params, region);
                // Rules
                case "CreateRule"                    -> handleCreateRule(params, region);
                case "DescribeRules"                 -> handleDescribeRules(params, region);
                case "DeleteRule"                    -> handleDeleteRule(params, region);
                case "ModifyRule"                    -> handleModifyRule(params, region);
                case "SetRulePriorities"             -> handleSetRulePriorities(params, region);
                // Targets
                case "RegisterTargets"               -> handleRegisterTargets(params, region);
                case "DeregisterTargets"             -> handleDeregisterTargets(params, region);
                case "DescribeTargetHealth"          -> handleDescribeTargetHealth(params, region);
                // Tags
                case "AddTags"                       -> handleAddTags(params);
                case "RemoveTags"                    -> handleRemoveTags(params);
                case "DescribeTags"                  -> handleDescribeTags(params);
                // Meta
                case "DescribeAccountLimits"         -> handleDescribeAccountLimits();
                case "DescribeSSLPolicies"           -> handleDescribeSSLPolicies(params);
                // Listener certs
                case "AddListenerCertificates"       -> handleAddListenerCertificates(params, region);
                case "RemoveListenerCertificates"    -> handleRemoveListenerCertificates(params, region);
                case "DescribeListenerCertificates"  -> handleDescribeListenerCertificates(params, region);
                default -> xmlError("UnsupportedOperation",
                        "Action " + action + " is not supported.", 400);
            };
        } catch (AwsException e) {
            return xmlError(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // ── Load Balancers ────────────────────────────────────────────────────────

    private Response handleCreateLoadBalancer(MultivaluedMap<String, String> p, String region) {
        String name = p.getFirst("Name");
        String scheme = p.getFirst("Scheme");
        String type = p.getFirst("Type");
        String ipAddressType = p.getFirst("IpAddressType");
        List<String> subnets = memberList(p, "Subnets");
        List<String> securityGroups = memberList(p, "SecurityGroups");
        Map<String, String> tags = parseTags(p);

        LoadBalancer lb = service.createLoadBalancer(region, name, scheme, type, ipAddressType,
                subnets, securityGroups, tags);

        // return provisioning state in create response only
        LoadBalancer provisioning = shallowCopy(lb);
        provisioning.setState("provisioning");

        String xml = new XmlBuilder()
                .start("CreateLoadBalancerResponse", AwsNamespaces.ELB_V2)
                .start("CreateLoadBalancerResult")
                  .start("LoadBalancers")
                    .start("member").raw(loadBalancerXml(provisioning)).end("member")
                  .end("LoadBalancers")
                .end("CreateLoadBalancerResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("CreateLoadBalancerResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeLoadBalancers(MultivaluedMap<String, String> p, String region) {
        List<String> arns = memberList(p, "LoadBalancerArns");
        List<String> names = memberList(p, "Names");

        List<LoadBalancer> lbs = service.describeLoadBalancers(region, arns, names, null, null);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLoadBalancersResponse", AwsNamespaces.ELB_V2)
                .start("DescribeLoadBalancersResult")
                  .start("LoadBalancers");
        for (LoadBalancer lb : lbs) {
            xml.start("member").raw(loadBalancerXml(lb)).end("member");
        }
        xml.end("LoadBalancers")
           .elem("NextMarker", "")
           .end("DescribeLoadBalancersResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeLoadBalancersResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDeleteLoadBalancer(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("LoadBalancerArn");
        service.deleteLoadBalancer(region, arn);
        return voidResponse("DeleteLoadBalancerResponse");
    }

    private Response handleModifyLoadBalancerAttributes(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("LoadBalancerArn");
        Map<String, String> attrs = parseAttributes(p, "Attributes");
        service.modifyLoadBalancerAttributes(region, arn, attrs);

        XmlBuilder xml = new XmlBuilder()
                .start("ModifyLoadBalancerAttributesResponse", AwsNamespaces.ELB_V2)
                .start("ModifyLoadBalancerAttributesResult")
                  .start("Attributes");
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            xml.start("member").elem("Key", e.getKey()).elem("Value", e.getValue()).end("member");
        }
        xml.end("Attributes")
           .end("ModifyLoadBalancerAttributesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("ModifyLoadBalancerAttributesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeLoadBalancerAttributes(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("LoadBalancerArn");
        Map<String, String> attrs = service.describeLoadBalancerAttributes(region, arn);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLoadBalancerAttributesResponse", AwsNamespaces.ELB_V2)
                .start("DescribeLoadBalancerAttributesResult")
                  .start("Attributes");
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            xml.start("member").elem("Key", e.getKey()).elem("Value", e.getValue()).end("member");
        }
        xml.end("Attributes")
           .end("DescribeLoadBalancerAttributesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeLoadBalancerAttributesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeCapacityReservation(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("LoadBalancerArn");
        ElbV2Service.CapacityReservation cr = service.describeCapacityReservation(region, arn);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeCapacityReservationResponse", AwsNamespaces.ELB_V2)
                .start("DescribeCapacityReservationResult");
        if (cr.lastModifiedTime() != null) {
            xml.elem("LastModifiedTime", ISO_FMT.format(cr.lastModifiedTime()));
        }
        if (cr.decreaseRequestsRemaining() != null) {
            xml.elem("DecreaseRequestsRemaining", cr.decreaseRequestsRemaining());
        }
        if (cr.minimumCapacityUnits() != null) {
            xml.start("MinimumLoadBalancerCapacity")
                 .elem("CapacityUnits", cr.minimumCapacityUnits())
               .end("MinimumLoadBalancerCapacity");
        }
        xml.start("CapacityReservationState").end("CapacityReservationState")
           .end("DescribeCapacityReservationResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeCapacityReservationResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleSetSecurityGroups(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("LoadBalancerArn");
        List<String> sgs = memberList(p, "SecurityGroups");
        service.setSecurityGroups(region, arn, sgs);

        XmlBuilder xml = new XmlBuilder()
                .start("SetSecurityGroupsResponse", AwsNamespaces.ELB_V2)
                .start("SetSecurityGroupsResult")
                  .start("SecurityGroupIds");
        for (String sg : sgs) xml.start("member").elem("member", sg).end("member");
        xml.end("SecurityGroupIds")
           .end("SetSecurityGroupsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("SetSecurityGroupsResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleSetSubnets(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("LoadBalancerArn");
        List<String> subnets = memberList(p, "Subnets");
        service.setSubnets(region, arn, subnets);
        return voidResponse("SetSubnetsResponse");
    }

    private Response handleSetIpAddressType(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("LoadBalancerArn");
        String ipAddressType = p.getFirst("IpAddressType");
        service.setIpAddressType(region, arn, ipAddressType);

        String xml = new XmlBuilder()
                .start("SetIpAddressTypeResponse", AwsNamespaces.ELB_V2)
                .start("SetIpAddressTypeResult")
                  .elem("IpAddressType", ipAddressType)
                .end("SetIpAddressTypeResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("SetIpAddressTypeResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    // ── Target Groups ─────────────────────────────────────────────────────────

    private Response handleCreateTargetGroup(MultivaluedMap<String, String> p, String region) {
        String name = p.getFirst("Name");
        String protocol = p.getFirst("Protocol");
        String protocolVersion = p.getFirst("ProtocolVersion");
        Integer port = parseIntOrNull(p.getFirst("Port"));
        String vpcId = p.getFirst("VpcId");
        String targetType = p.getFirst("TargetType");
        String hcProtocol = p.getFirst("HealthCheckProtocol");
        String hcPort = p.getFirst("HealthCheckPort");
        Boolean hcEnabled = parseBoolOrNull(p.getFirst("HealthCheckEnabled"));
        String hcPath = p.getFirst("HealthCheckPath");
        Integer hcInterval = parseIntOrNull(p.getFirst("HealthCheckIntervalSeconds"));
        Integer hcTimeout = parseIntOrNull(p.getFirst("HealthCheckTimeoutSeconds"));
        Integer healthyThreshold = parseIntOrNull(p.getFirst("HealthyThresholdCount"));
        Integer unhealthyThreshold = parseIntOrNull(p.getFirst("UnhealthyThresholdCount"));
        String matcher = p.getFirst("Matcher.HttpCode");
        String ipAddressType = p.getFirst("IpAddressType");
        Map<String, String> tags = parseTags(p);

        TargetGroup tg = service.createTargetGroup(region, name, protocol, protocolVersion, port, vpcId,
                targetType, hcProtocol, hcPort, hcEnabled, hcPath, hcInterval, hcTimeout,
                healthyThreshold, unhealthyThreshold, matcher, ipAddressType, tags);

        String xml = new XmlBuilder()
                .start("CreateTargetGroupResponse", AwsNamespaces.ELB_V2)
                .start("CreateTargetGroupResult")
                  .start("TargetGroups")
                    .start("member").raw(targetGroupXml(tg)).end("member")
                  .end("TargetGroups")
                .end("CreateTargetGroupResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("CreateTargetGroupResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeTargetGroups(MultivaluedMap<String, String> p, String region) {
        String lbArn = p.getFirst("LoadBalancerArn");
        List<String> tgArns = memberList(p, "TargetGroupArns");
        List<String> names = memberList(p, "Names");

        List<TargetGroup> tgs = service.describeTargetGroups(region, lbArn, tgArns, names);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTargetGroupsResponse", AwsNamespaces.ELB_V2)
                .start("DescribeTargetGroupsResult")
                  .start("TargetGroups");
        for (TargetGroup tg : tgs) {
            xml.start("member").raw(targetGroupXml(tg)).end("member");
        }
        xml.end("TargetGroups")
           .elem("NextMarker", "")
           .end("DescribeTargetGroupsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeTargetGroupsResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDeleteTargetGroup(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("TargetGroupArn");
        service.deleteTargetGroup(region, arn);
        String xml = AwsQueryResponse.envelopeEmptyResult("DeleteTargetGroup", AwsNamespaces.ELB_V2);
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleModifyTargetGroup(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("TargetGroupArn");
        String hcProtocol = p.getFirst("HealthCheckProtocol");
        String hcPort = p.getFirst("HealthCheckPort");
        Boolean hcEnabled = parseBoolOrNull(p.getFirst("HealthCheckEnabled"));
        String hcPath = p.getFirst("HealthCheckPath");
        Integer hcInterval = parseIntOrNull(p.getFirst("HealthCheckIntervalSeconds"));
        Integer hcTimeout = parseIntOrNull(p.getFirst("HealthCheckTimeoutSeconds"));
        Integer healthyThreshold = parseIntOrNull(p.getFirst("HealthyThresholdCount"));
        Integer unhealthyThreshold = parseIntOrNull(p.getFirst("UnhealthyThresholdCount"));
        String matcher = p.getFirst("Matcher.HttpCode");

        service.modifyTargetGroup(region, arn, hcProtocol, hcPort, hcEnabled, hcPath,
                hcInterval, hcTimeout, healthyThreshold, unhealthyThreshold, matcher);

        TargetGroup tg = service.describeTargetGroups(region, null, List.of(arn), null).get(0);
        String xml = new XmlBuilder()
                .start("ModifyTargetGroupResponse", AwsNamespaces.ELB_V2)
                .start("ModifyTargetGroupResult")
                  .start("TargetGroups")
                    .start("member").raw(targetGroupXml(tg)).end("member")
                  .end("TargetGroups")
                .end("ModifyTargetGroupResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("ModifyTargetGroupResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleModifyTargetGroupAttributes(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("TargetGroupArn");
        Map<String, String> attrs = parseAttributes(p, "Attributes");
        service.modifyTargetGroupAttributes(region, arn, attrs);

        XmlBuilder xml = new XmlBuilder()
                .start("ModifyTargetGroupAttributesResponse", AwsNamespaces.ELB_V2)
                .start("ModifyTargetGroupAttributesResult")
                  .start("Attributes");
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            xml.start("member").elem("Key", e.getKey()).elem("Value", e.getValue()).end("member");
        }
        xml.end("Attributes")
           .end("ModifyTargetGroupAttributesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("ModifyTargetGroupAttributesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeTargetGroupAttributes(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("TargetGroupArn");
        Map<String, String> attrs = service.describeTargetGroupAttributes(region, arn);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTargetGroupAttributesResponse", AwsNamespaces.ELB_V2)
                .start("DescribeTargetGroupAttributesResult")
                  .start("Attributes");
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            xml.start("member").elem("Key", e.getKey()).elem("Value", e.getValue()).end("member");
        }
        xml.end("Attributes")
           .end("DescribeTargetGroupAttributesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeTargetGroupAttributesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private Response handleCreateListener(MultivaluedMap<String, String> p, String region) {
        String lbArn = p.getFirst("LoadBalancerArn");
        String protocol = p.getFirst("Protocol");
        Integer port = parseIntOrNull(p.getFirst("Port"));
        String sslPolicy = p.getFirst("SslPolicy");
        List<String> certs = parseCertificateList(p, "Certificates");
        List<Action> defaultActions = parseActions(p, "DefaultActions");
        List<String> alpnPolicy = memberList(p, "AlpnPolicy");
        Map<String, String> tags = parseTags(p);

        Listener listener = service.createListener(region, lbArn, protocol, port, sslPolicy,
                certs, defaultActions, alpnPolicy, tags);

        String xml = new XmlBuilder()
                .start("CreateListenerResponse", AwsNamespaces.ELB_V2)
                .start("CreateListenerResult")
                  .start("Listeners")
                    .start("member").raw(listenerXml(listener)).end("member")
                  .end("Listeners")
                .end("CreateListenerResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("CreateListenerResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeListeners(MultivaluedMap<String, String> p, String region) {
        String lbArn = p.getFirst("LoadBalancerArn");
        List<String> listenerArns = memberList(p, "ListenerArns");

        List<Listener> result = service.describeListeners(region, lbArn, listenerArns);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeListenersResponse", AwsNamespaces.ELB_V2)
                .start("DescribeListenersResult")
                  .start("Listeners");
        for (Listener l : result) {
            xml.start("member").raw(listenerXml(l)).end("member");
        }
        xml.end("Listeners")
           .elem("NextMarker", "")
           .end("DescribeListenersResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeListenersResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDeleteListener(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("ListenerArn");
        service.deleteListener(region, arn);
        return voidResponse("DeleteListenerResponse");
    }

    private Response handleModifyListener(MultivaluedMap<String, String> p, String region) {
        String listenerArn = p.getFirst("ListenerArn");
        String protocol = p.getFirst("Protocol");
        Integer port = parseIntOrNull(p.getFirst("Port"));
        String sslPolicy = p.getFirst("SslPolicy");
        List<String> certs = parseCertificateList(p, "Certificates");
        List<Action> defaultActions = parseActions(p, "DefaultActions");
        List<String> alpnPolicy = memberList(p, "AlpnPolicy");

        Listener listener = service.modifyListener(region, listenerArn, protocol, port, sslPolicy,
                certs.isEmpty() ? null : certs,
                defaultActions.isEmpty() ? null : defaultActions,
                alpnPolicy.isEmpty() ? null : alpnPolicy);

        String xml = new XmlBuilder()
                .start("ModifyListenerResponse", AwsNamespaces.ELB_V2)
                .start("ModifyListenerResult")
                  .start("Listeners")
                    .start("member").raw(listenerXml(listener)).end("member")
                  .end("Listeners")
                .end("ModifyListenerResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("ModifyListenerResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleModifyListenerAttributes(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("ListenerArn");
        Map<String, String> attrs = parseAttributes(p, "Attributes");
        service.modifyListenerAttributes(region, arn, attrs);

        XmlBuilder xml = new XmlBuilder()
                .start("ModifyListenerAttributesResponse", AwsNamespaces.ELB_V2)
                .start("ModifyListenerAttributesResult")
                  .start("Attributes");
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            xml.start("member").elem("Key", e.getKey()).elem("Value", e.getValue()).end("member");
        }
        xml.end("Attributes")
           .end("ModifyListenerAttributesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("ModifyListenerAttributesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeListenerAttributes(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("ListenerArn");
        Map<String, String> attrs = service.describeListenerAttributes(region, arn);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeListenerAttributesResponse", AwsNamespaces.ELB_V2)
                .start("DescribeListenerAttributesResult")
                  .start("Attributes");
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            xml.start("member").elem("Key", e.getKey()).elem("Value", e.getValue()).end("member");
        }
        xml.end("Attributes")
           .end("DescribeListenerAttributesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeListenerAttributesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    private Response handleCreateRule(MultivaluedMap<String, String> p, String region) {
        String listenerArn = p.getFirst("ListenerArn");
        Integer priority = parseIntOrNull(p.getFirst("Priority"));
        if (priority == null) {
            throw new AwsException("ValidationError", "Priority is required.", 400);
        }
        List<RuleCondition> conditions = parseConditions(p);
        List<Action> actions = parseActions(p, "Actions");
        Map<String, String> tags = parseTags(p);

        Rule rule = service.createRule(region, listenerArn, conditions, priority, actions, tags);

        String xml = new XmlBuilder()
                .start("CreateRuleResponse", AwsNamespaces.ELB_V2)
                .start("CreateRuleResult")
                  .start("Rules")
                    .start("member").raw(ruleXml(rule)).end("member")
                  .end("Rules")
                .end("CreateRuleResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("CreateRuleResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeRules(MultivaluedMap<String, String> p, String region) {
        String listenerArn = p.getFirst("ListenerArn");
        List<String> ruleArns = memberList(p, "RuleArns");

        List<Rule> result = service.describeRules(region, listenerArn, ruleArns);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeRulesResponse", AwsNamespaces.ELB_V2)
                .start("DescribeRulesResult")
                  .start("Rules");
        for (Rule r : result) {
            xml.start("member").raw(ruleXml(r)).end("member");
        }
        xml.end("Rules")
           .elem("NextMarker", "")
           .end("DescribeRulesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeRulesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDeleteRule(MultivaluedMap<String, String> p, String region) {
        String arn = p.getFirst("RuleArn");
        service.deleteRule(region, arn);
        return voidResponse("DeleteRuleResponse");
    }

    private Response handleModifyRule(MultivaluedMap<String, String> p, String region) {
        String ruleArn = p.getFirst("RuleArn");
        List<RuleCondition> conditions = parseConditions(p);
        List<Action> actions = parseActions(p, "Actions");

        Rule rule = service.modifyRule(region, ruleArn,
                conditions.isEmpty() ? null : conditions,
                actions.isEmpty() ? null : actions);

        String xml = new XmlBuilder()
                .start("ModifyRuleResponse", AwsNamespaces.ELB_V2)
                .start("ModifyRuleResult")
                  .start("Rules")
                    .start("member").raw(ruleXml(rule)).end("member")
                  .end("Rules")
                .end("ModifyRuleResult")
                .raw(AwsQueryResponse.responseMetadata())
                .end("ModifyRuleResponse")
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleSetRulePriorities(MultivaluedMap<String, String> p, String region) {
        Map<String, Integer> arnToPriority = new LinkedHashMap<>();
        int i = 1;
        while (true) {
            String arn = p.getFirst("RulePriorities.member." + i + ".RuleArn");
            String priorityStr = p.getFirst("RulePriorities.member." + i + ".Priority");
            if (arn == null) break;
            arnToPriority.put(arn, Integer.parseInt(priorityStr));
            i++;
        }
        service.setRulePriorities(region, arnToPriority);

        // return the updated rules
        List<Rule> updated = service.describeRules(region, null, new ArrayList<>(arnToPriority.keySet()));

        XmlBuilder xml = new XmlBuilder()
                .start("SetRulePrioritiesResponse", AwsNamespaces.ELB_V2)
                .start("SetRulePrioritiesResult")
                  .start("Rules");
        for (Rule r : updated) {
            xml.start("member").raw(ruleXml(r)).end("member");
        }
        xml.end("Rules")
           .end("SetRulePrioritiesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("SetRulePrioritiesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    private Response handleRegisterTargets(MultivaluedMap<String, String> p, String region) {
        String tgArn = p.getFirst("TargetGroupArn");
        List<TargetDescription> targets = parseTargets(p);
        service.registerTargets(region, tgArn, targets);
        return voidResponse("RegisterTargetsResponse");
    }

    private Response handleDeregisterTargets(MultivaluedMap<String, String> p, String region) {
        String tgArn = p.getFirst("TargetGroupArn");
        List<TargetDescription> targets = parseTargets(p);
        service.deregisterTargets(region, tgArn, targets);
        return voidResponse("DeregisterTargetsResponse");
    }

    private Response handleDescribeTargetHealth(MultivaluedMap<String, String> p, String region) {
        String tgArn = p.getFirst("TargetGroupArn");
        List<TargetDescription> filterTargets = parseTargets(p);

        List<TargetHealth> healthList = service.describeTargetHealth(region, tgArn, filterTargets);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTargetHealthResponse", AwsNamespaces.ELB_V2)
                .start("DescribeTargetHealthResult")
                  .start("TargetHealthDescriptions");
        for (TargetHealth th : healthList) {
            xml.start("member");
            xml.start("Target");
            xml.elem("Id", th.getTarget().getId());
            if (th.getTarget().getPort() != null) xml.elem("Port", String.valueOf(th.getTarget().getPort()));
            if (th.getTarget().getAvailabilityZone() != null) xml.elem("AvailabilityZone", th.getTarget().getAvailabilityZone());
            xml.end("Target");
            xml.elem("HealthCheckPort", th.getHealthCheckPort());
            xml.start("TargetHealth");
            xml.elem("State", th.getState());
            if (th.getReason() != null) xml.elem("Reason", th.getReason());
            if (th.getDescription() != null) xml.elem("Description", th.getDescription());
            xml.end("TargetHealth");
            xml.end("member");
        }
        xml.end("TargetHealthDescriptions")
           .end("DescribeTargetHealthResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeTargetHealthResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private Response handleAddTags(MultivaluedMap<String, String> p) {
        List<String> arns = memberList(p, "ResourceArns");
        Map<String, String> tags = parseTags(p);
        service.addTags(arns, tags);
        return voidResponse("AddTagsResponse");
    }

    private Response handleRemoveTags(MultivaluedMap<String, String> p) {
        List<String> arns = memberList(p, "ResourceArns");
        List<String> keys = memberList(p, "TagKeys");
        service.removeTags(arns, keys);
        return voidResponse("RemoveTagsResponse");
    }

    private Response handleDescribeTags(MultivaluedMap<String, String> p) {
        List<String> arns = memberList(p, "ResourceArns");
        Map<String, Map<String, String>> result = service.describeTags(arns);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTagsResponse", AwsNamespaces.ELB_V2)
                .start("DescribeTagsResult")
                  .start("TagDescriptions");
        for (Map.Entry<String, Map<String, String>> e : result.entrySet()) {
            xml.start("member");
            xml.elem("ResourceArn", e.getKey());
            xml.start("Tags");
            for (Map.Entry<String, String> tag : e.getValue().entrySet()) {
                xml.start("member").elem("Key", tag.getKey()).elem("Value", tag.getValue()).end("member");
            }
            xml.end("Tags");
            xml.end("member");
        }
        xml.end("TagDescriptions")
           .end("DescribeTagsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeTagsResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // ── Meta ──────────────────────────────────────────────────────────────────

    private Response handleDescribeAccountLimits() {
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAccountLimitsResponse", AwsNamespaces.ELB_V2)
                .start("DescribeAccountLimitsResult")
                  .start("Limits");
        for (Map.Entry<String, String> e : ACCOUNT_LIMITS.entrySet()) {
            xml.start("member").elem("Name", e.getKey()).elem("Max", e.getValue()).end("member");
        }
        xml.end("Limits")
           .elem("NextMarker", "")
           .end("DescribeAccountLimitsResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeAccountLimitsResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleDescribeSSLPolicies(MultivaluedMap<String, String> p) {
        List<String> requested = memberList(p, "Names");
        List<String> toReturn = requested.isEmpty() ? SSL_POLICIES
                : SSL_POLICIES.stream().filter(requested::contains).toList();

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeSSLPoliciesResponse", AwsNamespaces.ELB_V2)
                .start("DescribeSSLPoliciesResult")
                  .start("SslPolicies");
        for (String name : toReturn) {
            xml.start("member")
               .elem("Name", name)
               .start("SslProtocols").end("SslProtocols")
               .start("Ciphers").end("Ciphers")
               .start("SupportedLoadBalancerTypes")
                 .start("member").raw("application").end("member")
               .end("SupportedLoadBalancerTypes")
               .end("member");
        }
        xml.end("SslPolicies")
           .end("DescribeSSLPoliciesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeSSLPoliciesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // ── Listener Certificates ─────────────────────────────────────────────────

    private Response handleAddListenerCertificates(MultivaluedMap<String, String> p, String region) {
        String listenerArn = p.getFirst("ListenerArn");
        List<String> certs = parseCertificateList(p, "Certificates");
        service.addListenerCertificates(region, listenerArn, certs);

        XmlBuilder xml = new XmlBuilder()
                .start("AddListenerCertificatesResponse", AwsNamespaces.ELB_V2)
                .start("AddListenerCertificatesResult")
                  .start("Certificates");
        for (String c : certs) {
            xml.start("member").elem("CertificateArn", c).end("member");
        }
        xml.end("Certificates")
           .end("AddListenerCertificatesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("AddListenerCertificatesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    private Response handleRemoveListenerCertificates(MultivaluedMap<String, String> p, String region) {
        String listenerArn = p.getFirst("ListenerArn");
        List<String> certs = parseCertificateList(p, "Certificates");
        service.removeListenerCertificates(region, listenerArn, certs);
        return voidResponse("RemoveListenerCertificatesResponse");
    }

    private Response handleDescribeListenerCertificates(MultivaluedMap<String, String> p, String region) {
        String listenerArn = p.getFirst("ListenerArn");
        List<String> certs = service.describeListenerCertificates(region, listenerArn);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeListenerCertificatesResponse", AwsNamespaces.ELB_V2)
                .start("DescribeListenerCertificatesResult")
                  .start("Certificates");
        for (String c : certs) {
            xml.start("member").elem("CertificateArn", c).end("member");
        }
        xml.end("Certificates")
           .elem("NextMarker", "")
           .end("DescribeListenerCertificatesResult")
           .raw(AwsQueryResponse.responseMetadata())
           .end("DescribeListenerCertificatesResponse");
        return Response.ok(xml.build()).type(MediaType.APPLICATION_XML).build();
    }

    // ── XML builders ─────────────────────────────────────────────────────────

    private String loadBalancerXml(LoadBalancer lb) {
        XmlBuilder xml = new XmlBuilder();
        xml.elem("LoadBalancerArn", lb.getLoadBalancerArn());
        xml.elem("DNSName", lb.getDnsName());
        xml.elem("CanonicalHostedZoneId", lb.getCanonicalHostedZoneId());
        if (lb.getCreatedTime() != null) {
            xml.elem("CreatedTime", ISO_FMT.format(lb.getCreatedTime()));
        }
        xml.elem("LoadBalancerName", lb.getLoadBalancerName());
        xml.elem("Scheme", lb.getScheme());
        xml.elem("VpcId", safe(lb.getVpcId()));
        xml.start("State").elem("Code", safe(lb.getState())).end("State");
        xml.elem("Type", safe(lb.getType()));
        xml.start("AvailabilityZones");
        for (AvailabilityZone az : lb.getAvailabilityZones()) {
            xml.start("member")
                    .elem("SubnetId", safe(az.getSubnetId()))
                    .elem("ZoneName", safe(az.getZoneName()))
                    .end("member");
        }
        xml.end("AvailabilityZones");
        xml.start("SecurityGroups");
        for (String sg : lb.getSecurityGroups()) {
            xml.start("member").raw(sg).end("member");
        }
        xml.end("SecurityGroups");
        xml.elem("IpAddressType", safe(lb.getIpAddressType()));
        return xml.build();
    }

    private String targetGroupXml(TargetGroup tg) {
        XmlBuilder xml = new XmlBuilder();
        xml.elem("TargetGroupArn", tg.getTargetGroupArn());
        xml.elem("TargetGroupName", tg.getTargetGroupName());
        xml.elem("Protocol", safe(tg.getProtocol()));
        xml.elem("ProtocolVersion", safe(tg.getProtocolVersion()));
        if (tg.getPort() != null) xml.elem("Port", String.valueOf(tg.getPort()));
        xml.elem("VpcId", safe(tg.getVpcId()));
        xml.elem("HealthCheckProtocol", safe(tg.getHealthCheckProtocol()));
        xml.elem("HealthCheckPort", safe(tg.getHealthCheckPort()));
        xml.elem("HealthCheckEnabled", String.valueOf(tg.getHealthCheckEnabled() != null ? tg.getHealthCheckEnabled() : true));
        if (tg.getHealthCheckIntervalSeconds() != null) xml.elem("HealthCheckIntervalSeconds", String.valueOf(tg.getHealthCheckIntervalSeconds()));
        if (tg.getHealthCheckTimeoutSeconds() != null) xml.elem("HealthCheckTimeoutSeconds", String.valueOf(tg.getHealthCheckTimeoutSeconds()));
        if (tg.getHealthyThresholdCount() != null) xml.elem("HealthyThresholdCount", String.valueOf(tg.getHealthyThresholdCount()));
        if (tg.getUnhealthyThresholdCount() != null) xml.elem("UnhealthyThresholdCount", String.valueOf(tg.getUnhealthyThresholdCount()));
        xml.elem("HealthCheckPath", safe(tg.getHealthCheckPath()));
        xml.start("Matcher").elem("HttpCode", safe(tg.getMatcher())).end("Matcher");
        xml.start("LoadBalancerArns");
        for (String lbArn : tg.getLoadBalancerArns()) {
            xml.start("member").raw(lbArn).end("member");
        }
        xml.end("LoadBalancerArns");
        xml.elem("TargetType", safe(tg.getTargetType()));
        xml.elem("IpAddressType", safe(tg.getIpAddressType()));
        return xml.build();
    }

    private String listenerXml(Listener l) {
        XmlBuilder xml = new XmlBuilder();
        xml.elem("ListenerArn", l.getListenerArn());
        xml.elem("LoadBalancerArn", l.getLoadBalancerArn());
        if (l.getPort() != null) xml.elem("Port", String.valueOf(l.getPort()));
        xml.elem("Protocol", safe(l.getProtocol()));
        xml.elem("SslPolicy", safe(l.getSslPolicy()));
        xml.start("Certificates");
        for (String c : l.getCertificates()) {
            xml.start("member").elem("CertificateArn", c).end("member");
        }
        xml.end("Certificates");
        xml.start("DefaultActions");
        for (Action a : l.getDefaultActions()) {
            xml.start("member").raw(actionXml(a)).end("member");
        }
        xml.end("DefaultActions");
        xml.start("AlpnPolicy");
        for (String ap : l.getAlpnPolicy()) {
            xml.start("member").raw(ap).end("member");
        }
        xml.end("AlpnPolicy");
        return xml.build();
    }

    private String ruleXml(Rule r) {
        XmlBuilder xml = new XmlBuilder();
        xml.elem("RuleArn", r.getRuleArn());
        xml.elem("Priority", r.getPriority());
        xml.start("Conditions");
        for (RuleCondition c : r.getConditions()) {
            xml.start("member").raw(conditionXml(c)).end("member");
        }
        xml.end("Conditions");
        xml.start("Actions");
        for (Action a : r.getActions()) {
            xml.start("member").raw(actionXml(a)).end("member");
        }
        xml.end("Actions");
        xml.elem("IsDefault", String.valueOf(r.isDefault()));
        return xml.build();
    }

    private String actionXml(Action a) {
        XmlBuilder xml = new XmlBuilder();
        xml.elem("Type", safe(a.getType()));
        if (a.getOrder() != null) xml.elem("Order", String.valueOf(a.getOrder()));
        if (a.getTargetGroupArn() != null) xml.elem("TargetGroupArn", a.getTargetGroupArn());
        if (!a.getTargetGroups().isEmpty() || a.getTargetGroupArn() == null && "forward".equals(a.getType())) {
            xml.start("ForwardConfig");
            xml.start("TargetGroups");
            for (Action.TargetGroupTuple t : a.getTargetGroups()) {
                xml.start("member");
                xml.elem("TargetGroupArn", safe(t.getTargetGroupArn()));
                if (t.getWeight() != null) xml.elem("Weight", String.valueOf(t.getWeight()));
                xml.end("member");
            }
            xml.end("TargetGroups");
            if (a.getStickinessEnabled() != null) {
                xml.start("TargetGroupStickinessConfig")
                   .elem("Enabled", String.valueOf(a.getStickinessEnabled()));
                if (a.getStickinessDurationSeconds() != null) {
                    xml.elem("DurationSeconds", String.valueOf(a.getStickinessDurationSeconds()));
                }
                xml.end("TargetGroupStickinessConfig");
            }
            xml.end("ForwardConfig");
        }
        if ("redirect".equals(a.getType())) {
            xml.start("RedirectConfig");
            if (a.getRedirectProtocol() != null) xml.elem("Protocol", a.getRedirectProtocol());
            if (a.getRedirectPort() != null) xml.elem("Port", a.getRedirectPort());
            if (a.getRedirectHost() != null) xml.elem("Host", a.getRedirectHost());
            if (a.getRedirectPath() != null) xml.elem("Path", a.getRedirectPath());
            if (a.getRedirectQuery() != null) xml.elem("Query", a.getRedirectQuery());
            xml.elem("StatusCode", safe(a.getRedirectStatusCode()));
            xml.end("RedirectConfig");
        }
        if ("fixed-response".equals(a.getType())) {
            xml.start("FixedResponseConfig");
            xml.elem("StatusCode", safe(a.getFixedResponseStatusCode()));
            if (a.getFixedResponseContentType() != null) xml.elem("ContentType", a.getFixedResponseContentType());
            if (a.getFixedResponseMessageBody() != null) xml.elem("MessageBody", a.getFixedResponseMessageBody());
            xml.end("FixedResponseConfig");
        }
        return xml.build();
    }

    private String conditionXml(RuleCondition c) {
        XmlBuilder xml = new XmlBuilder();
        xml.elem("Field", safe(c.getField()));
        xml.start("Values");
        for (String v : c.getValues()) xml.start("member").raw(v).end("member");
        xml.end("Values");
        if (!c.getHostHeaderValues().isEmpty()) {
            xml.start("HostHeaderConfig").start("Values");
            for (String v : c.getHostHeaderValues()) xml.start("member").raw(v).end("member");
            xml.end("Values").end("HostHeaderConfig");
        }
        if (!c.getPathPatternValues().isEmpty()) {
            xml.start("PathPatternConfig").start("Values");
            for (String v : c.getPathPatternValues()) xml.start("member").raw(v).end("member");
            xml.end("Values").end("PathPatternConfig");
        }
        if (c.getHttpHeaderName() != null) {
            xml.start("HttpHeaderConfig")
               .elem("HttpHeaderName", c.getHttpHeaderName())
               .start("Values");
            for (String v : c.getHttpHeaderValues()) xml.start("member").raw(v).end("member");
            xml.end("Values").end("HttpHeaderConfig");
        }
        if (!c.getHttpMethodValues().isEmpty()) {
            xml.start("HttpRequestMethodConfig").start("Values");
            for (String v : c.getHttpMethodValues()) xml.start("member").raw(v).end("member");
            xml.end("Values").end("HttpRequestMethodConfig");
        }
        if (!c.getSourceIpValues().isEmpty()) {
            xml.start("SourceIpConfig").start("Values");
            for (String v : c.getSourceIpValues()) xml.start("member").raw(v).end("member");
            xml.end("Values").end("SourceIpConfig");
        }
        if (!c.getQueryStringValues().isEmpty()) {
            xml.start("QueryStringConfig").start("Values");
            for (RuleCondition.QueryStringPair kv : c.getQueryStringValues()) {
                xml.start("member");
                if (kv.getKey() != null) xml.elem("Key", kv.getKey());
                xml.elem("Value", safe(kv.getValue()));
                xml.end("member");
            }
            xml.end("Values").end("QueryStringConfig");
        }
        return xml.build();
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private List<String> memberList(MultivaluedMap<String, String> p, String prefix) {
        List<String> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String val = p.getFirst(prefix + ".member." + i);
            if (val == null) break;
            result.add(val);
            i++;
        }
        return result;
    }

    private Map<String, String> parseTags(MultivaluedMap<String, String> p) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 1;
        while (true) {
            String key = p.getFirst("Tags.member." + i + ".Key");
            if (key == null) break;
            String value = p.getFirst("Tags.member." + i + ".Value");
            result.put(key, value != null ? value : "");
            i++;
        }
        return result;
    }

    private Map<String, String> parseAttributes(MultivaluedMap<String, String> p, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 1;
        while (true) {
            String key = p.getFirst(prefix + ".member." + i + ".Key");
            if (key == null) break;
            String value = p.getFirst(prefix + ".member." + i + ".Value");
            result.put(key, value != null ? value : "");
            i++;
        }
        return result;
    }

    private List<String> parseCertificateList(MultivaluedMap<String, String> p, String prefix) {
        List<String> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String arn = p.getFirst(prefix + ".member." + i + ".CertificateArn");
            if (arn == null) break;
            result.add(arn);
            i++;
        }
        return result;
    }

    private List<Action> parseActions(MultivaluedMap<String, String> p, String prefix) {
        List<Action> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String type = p.getFirst(prefix + ".member." + i + ".Type");
            if (type == null) break;
            Action a = new Action();
            a.setType(type);
            String orderStr = p.getFirst(prefix + ".member." + i + ".Order");
            if (orderStr != null) a.setOrder(Integer.parseInt(orderStr));

            switch (type) {
                case "forward" -> {
                    String tgArn = p.getFirst(prefix + ".member." + i + ".TargetGroupArn");
                    a.setTargetGroupArn(tgArn);
                    // weighted forward via ForwardConfig
                    List<Action.TargetGroupTuple> tuples = new ArrayList<>();
                    int j = 1;
                    while (true) {
                        String tgArnW = p.getFirst(prefix + ".member." + i + ".ForwardConfig.TargetGroups.member." + j + ".TargetGroupArn");
                        if (tgArnW == null) break;
                        Action.TargetGroupTuple t = new Action.TargetGroupTuple();
                        t.setTargetGroupArn(tgArnW);
                        String wStr = p.getFirst(prefix + ".member." + i + ".ForwardConfig.TargetGroups.member." + j + ".Weight");
                        if (wStr != null) t.setWeight(Integer.parseInt(wStr));
                        tuples.add(t);
                        j++;
                    }
                    a.setTargetGroups(tuples);
                    String stickyEnabled = p.getFirst(prefix + ".member." + i + ".ForwardConfig.TargetGroupStickinessConfig.Enabled");
                    if (stickyEnabled != null) a.setStickinessEnabled(Boolean.parseBoolean(stickyEnabled));
                    String stickyDuration = p.getFirst(prefix + ".member." + i + ".ForwardConfig.TargetGroupStickinessConfig.DurationSeconds");
                    if (stickyDuration != null) a.setStickinessDurationSeconds(Integer.parseInt(stickyDuration));
                }
                case "redirect" -> {
                    a.setRedirectProtocol(p.getFirst(prefix + ".member." + i + ".RedirectConfig.Protocol"));
                    a.setRedirectPort(p.getFirst(prefix + ".member." + i + ".RedirectConfig.Port"));
                    a.setRedirectHost(p.getFirst(prefix + ".member." + i + ".RedirectConfig.Host"));
                    a.setRedirectPath(p.getFirst(prefix + ".member." + i + ".RedirectConfig.Path"));
                    a.setRedirectQuery(p.getFirst(prefix + ".member." + i + ".RedirectConfig.Query"));
                    a.setRedirectStatusCode(p.getFirst(prefix + ".member." + i + ".RedirectConfig.StatusCode"));
                }
                case "fixed-response" -> {
                    a.setFixedResponseStatusCode(p.getFirst(prefix + ".member." + i + ".FixedResponseConfig.StatusCode"));
                    a.setFixedResponseContentType(p.getFirst(prefix + ".member." + i + ".FixedResponseConfig.ContentType"));
                    a.setFixedResponseMessageBody(p.getFirst(prefix + ".member." + i + ".FixedResponseConfig.MessageBody"));
                }
            }
            result.add(a);
            i++;
        }
        return result;
    }

    private List<RuleCondition> parseConditions(MultivaluedMap<String, String> p) {
        List<RuleCondition> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String field = p.getFirst("Conditions.member." + i + ".Field");
            if (field == null) break;
            RuleCondition c = new RuleCondition();
            c.setField(field);

            // legacy flat values
            List<String> legacyValues = new ArrayList<>();
            int v = 1;
            while (true) {
                String val = p.getFirst("Conditions.member." + i + ".Values.member." + v);
                if (val == null) break;
                legacyValues.add(val);
                v++;
            }
            c.setValues(legacyValues);

            switch (field) {
                case "host-header" -> {
                    List<String> vals = new ArrayList<>();
                    int j = 1;
                    while (true) {
                        String val = p.getFirst("Conditions.member." + i + ".HostHeaderConfig.Values.member." + j);
                        if (val == null) break;
                        vals.add(val);
                        j++;
                    }
                    if (!vals.isEmpty()) c.setHostHeaderValues(vals);
                    else c.setHostHeaderValues(new ArrayList<>(legacyValues));
                }
                case "path-pattern" -> {
                    List<String> vals = new ArrayList<>();
                    int j = 1;
                    while (true) {
                        String val = p.getFirst("Conditions.member." + i + ".PathPatternConfig.Values.member." + j);
                        if (val == null) break;
                        vals.add(val);
                        j++;
                    }
                    if (!vals.isEmpty()) c.setPathPatternValues(vals);
                    else c.setPathPatternValues(new ArrayList<>(legacyValues));
                }
                case "http-header" -> {
                    c.setHttpHeaderName(p.getFirst("Conditions.member." + i + ".HttpHeaderConfig.HttpHeaderName"));
                    List<String> vals = new ArrayList<>();
                    int j = 1;
                    while (true) {
                        String val = p.getFirst("Conditions.member." + i + ".HttpHeaderConfig.Values.member." + j);
                        if (val == null) break;
                        vals.add(val);
                        j++;
                    }
                    c.setHttpHeaderValues(vals);
                }
                case "http-request-method" -> {
                    List<String> vals = new ArrayList<>();
                    int j = 1;
                    while (true) {
                        String val = p.getFirst("Conditions.member." + i + ".HttpRequestMethodConfig.Values.member." + j);
                        if (val == null) break;
                        vals.add(val);
                        j++;
                    }
                    c.setHttpMethodValues(vals);
                }
                case "source-ip" -> {
                    List<String> vals = new ArrayList<>();
                    int j = 1;
                    while (true) {
                        String val = p.getFirst("Conditions.member." + i + ".SourceIpConfig.Values.member." + j);
                        if (val == null) break;
                        vals.add(val);
                        j++;
                    }
                    c.setSourceIpValues(vals);
                }
                case "query-string" -> {
                    List<RuleCondition.QueryStringPair> pairs = new ArrayList<>();
                    int j = 1;
                    while (true) {
                        String qKey = p.getFirst("Conditions.member." + i + ".QueryStringConfig.Values.member." + j + ".Key");
                        String qVal = p.getFirst("Conditions.member." + i + ".QueryStringConfig.Values.member." + j + ".Value");
                        if (qKey == null && qVal == null) break;
                        RuleCondition.QueryStringPair pair = new RuleCondition.QueryStringPair();
                        pair.setKey(qKey);
                        pair.setValue(qVal);
                        pairs.add(pair);
                        j++;
                    }
                    c.setQueryStringValues(pairs);
                }
            }
            result.add(c);
            i++;
        }
        return result;
    }

    private List<TargetDescription> parseTargets(MultivaluedMap<String, String> p) {
        List<TargetDescription> result = new ArrayList<>();
        int i = 1;
        while (true) {
            String id = p.getFirst("Targets.member." + i + ".Id");
            if (id == null) break;
            TargetDescription t = new TargetDescription();
            t.setId(id);
            String portStr = p.getFirst("Targets.member." + i + ".Port");
            if (portStr != null) t.setPort(Integer.parseInt(portStr));
            t.setAvailabilityZone(p.getFirst("Targets.member." + i + ".AvailabilityZone"));
            result.add(t);
            i++;
        }
        return result;
    }

    // ── Misc helpers ─────────────────────────────────────────────────────────

    private Response voidResponse(String responseName) {
        String actionName = responseName.substring(0, responseName.length() - "Response".length());
        String resultName = actionName + "Result";
        String xml = new XmlBuilder()
                .start(responseName, AwsNamespaces.ELB_V2)
                .start(resultName)
                .end(resultName)
                .raw(AwsQueryResponse.responseMetadata())
                .end(responseName)
                .build();
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response xmlError(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
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

    private static String safe(String s) { return s != null ? s : ""; }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        return Integer.parseInt(s);
    }

    private static Boolean parseBoolOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        return Boolean.parseBoolean(s);
    }

    private static LoadBalancer shallowCopy(LoadBalancer lb) {
        LoadBalancer copy = new LoadBalancer();
        copy.setLoadBalancerArn(lb.getLoadBalancerArn());
        copy.setDnsName(lb.getDnsName());
        copy.setCanonicalHostedZoneId(lb.getCanonicalHostedZoneId());
        copy.setCreatedTime(lb.getCreatedTime());
        copy.setLoadBalancerName(lb.getLoadBalancerName());
        copy.setScheme(lb.getScheme());
        copy.setVpcId(lb.getVpcId());
        copy.setState(lb.getState());
        copy.setType(lb.getType());
        copy.setAvailabilityZones(new ArrayList<>(lb.getAvailabilityZones()));
        copy.setSecurityGroups(new ArrayList<>(lb.getSecurityGroups()));
        copy.setIpAddressType(lb.getIpAddressType());
        copy.setRegion(lb.getRegion());
        return copy;
    }
}
