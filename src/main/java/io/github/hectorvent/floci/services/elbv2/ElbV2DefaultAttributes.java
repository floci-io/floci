package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The attribute defaults AWS reports for a load balancer or target group that has never been
 * modified. DescribeLoadBalancerAttributes and DescribeTargetGroupAttributes always answer with
 * the full set: a key is absent only because the resource type does not have it, never because
 * nobody set it. That matters beyond tidiness, because the Terraform provider reads these on
 * every refresh to populate aws_lb's idle_timeout, enable_http2, enable_deletion_protection and
 * access_logs, and aws_lb_target_group's stickiness, deregistration_delay and
 * load_balancing_algorithm_type. An empty list leaves each of them a permanent diff.
 *
 * <p>Which keys apply is decided by the load balancer's type and the target group's protocol,
 * the same way AWS decides it: an NLB has no idle timeout, an ALB has no proxy protocol.
 */
final class ElbV2DefaultAttributes {

    /** Target group protocols that belong to an Application Load Balancer. */
    private static final Set<String> APPLICATION_PROTOCOLS = Set.of("HTTP", "HTTPS");

    private ElbV2DefaultAttributes() {}

    static Map<String, String> forLoadBalancer(LoadBalancer lb) {
        String type = lb.getType() == null ? "application" : lb.getType().toLowerCase();
        Map<String, String> defaults = new LinkedHashMap<>();
        // Supported by every type: deletion protection and cross-zone load balancing, and nothing
        // else. Cross-zone is on for an ALB and off for the others, which is the one default that
        // differs by type rather than the key simply not existing.
        defaults.put("deletion_protection.enabled", "false");
        defaults.put("load_balancing.cross_zone.enabled", "application".equals(type) ? "true" : "false");

        // Access logs are an Application and Network Load Balancer attribute. A Gateway Load
        // Balancer does not have them, and reporting them for one would hand a client a schema
        // AWS never returns.
        if ("application".equals(type) || "network".equals(type)) {
            defaults.put("access_logs.s3.enabled", "false");
            defaults.put("access_logs.s3.bucket", "");
            defaults.put("access_logs.s3.prefix", "");
        }

        if ("application".equals(type)) {
            defaults.put("idle_timeout.timeout_seconds", "60");
            defaults.put("client_keep_alive.seconds", "3600");
            defaults.put("routing.http2.enabled", "true");
            defaults.put("routing.http.desync_mitigation_mode", "defensive");
            defaults.put("routing.http.drop_invalid_header_fields.enabled", "false");
            defaults.put("routing.http.preserve_host_header.enabled", "false");
            defaults.put("routing.http.x_amzn_tls_version_and_cipher_suite.enabled", "false");
            defaults.put("routing.http.xff_client_port.enabled", "false");
            defaults.put("routing.http.xff_header_processing.mode", "append");
            defaults.put("waf.fail_open.enabled", "false");
            defaults.put("connection_logs.s3.enabled", "false");
            defaults.put("connection_logs.s3.bucket", "");
            defaults.put("connection_logs.s3.prefix", "");
        } else if ("network".equals(type)) {
            defaults.put("dns_record.client_routing_policy", "any_availability_zone");
        }
        return defaults;
    }

    static Map<String, String> forTargetGroup(TargetGroup tg) {
        Map<String, String> defaults = new LinkedHashMap<>();
        if ("lambda".equalsIgnoreCase(tg.getTargetType())) {
            // A Lambda target group has one attribute and none of the connection-oriented ones:
            // there is no instance to drain, no cookie to pin and no algorithm to choose.
            defaults.put("lambda.multi_value_headers.enabled", "false");
            return defaults;
        }

        boolean application = tg.getProtocol() != null
                && APPLICATION_PROTOCOLS.contains(tg.getProtocol().toUpperCase());
        defaults.put("deregistration_delay.timeout_seconds", "300");
        defaults.put("stickiness.enabled", "false");
        defaults.put("load_balancing.cross_zone.enabled", "use_load_balancer_configuration");

        if (application) {
            defaults.put("stickiness.type", "lb_cookie");
            defaults.put("stickiness.lb_cookie.duration_seconds", "86400");
            defaults.put("stickiness.app_cookie.duration_seconds", "86400");
            defaults.put("stickiness.app_cookie.cookie_name", "");
            defaults.put("load_balancing.algorithm.type", "round_robin");
            defaults.put("slow_start.duration_seconds", "0");
        } else {
            defaults.put("stickiness.type", "source_ip");
            defaults.put("proxy_protocol_v2.enabled", "false");
        }
        return defaults;
    }

    /**
     * The stored values win over the defaults, so a key that was modified reports what it was set
     * to and every other key still reports the value AWS would.
     */
    static Map<String, String> merge(Map<String, String> defaults, Map<String, String> stored) {
        Map<String, String> merged = new LinkedHashMap<>(defaults);
        merged.putAll(stored);
        return merged;
    }
}
