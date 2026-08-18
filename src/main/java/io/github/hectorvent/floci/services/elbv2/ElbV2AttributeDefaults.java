package io.github.hectorvent.floci.services.elbv2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The attribute values Elastic Load Balancing reports for a load balancer, target group or
 * listener that has never had ModifyXAttributes called on it.
 *
 * <p>Describe returning an empty attribute set is not the same answer as AWS's. A client that
 * reads {@code idle_timeout.timeout_seconds} off an untouched load balancer gets 60 from AWS and
 * nothing from an empty set, so a configuration that leaves the argument at its default reads as
 * drifted on the very next plan and never converges. The defaults are overlaid underneath
 * whatever has actually been stored, so a modified attribute still wins and objects created
 * before this existed pick the defaults up on read rather than needing a migration.
 *
 * <p>Values are AWS's documented defaults. Where a default depends on the object's own shape,
 * the shape is a parameter here rather than a branch at the call site.
 */
final class ElbV2AttributeDefaults {

    private ElbV2AttributeDefaults() {}

    private static final Map<String, String> LOAD_BALANCER_COMMON = Map.ofEntries(
            Map.entry("deletion_protection.enabled", "false"),
            Map.entry("load_balancing.cross_zone.enabled", "true"),
            Map.entry("access_logs.s3.enabled", "false"),
            Map.entry("access_logs.s3.bucket", ""),
            Map.entry("access_logs.s3.prefix", ""),
            Map.entry("ipv6.deny_all_igw_traffic", "false"),
            Map.entry("zonal_shift.config.enabled", "false"));

    private static final Map<String, String> APPLICATION_ONLY = Map.ofEntries(
            Map.entry("idle_timeout.timeout_seconds", "60"),
            Map.entry("client_keep_alive.seconds", "3600"),
            Map.entry("routing.http.desync_mitigation_mode", "defensive"),
            Map.entry("routing.http.drop_invalid_header_fields.enabled", "false"),
            Map.entry("routing.http.preserve_host_header.enabled", "false"),
            Map.entry("routing.http.x_amzn_tls_version_and_cipher_suite.enabled", "false"),
            Map.entry("routing.http.xff_client_port.enabled", "false"),
            Map.entry("routing.http.xff_header_processing.mode", "append"),
            Map.entry("routing.http2.enabled", "true"),
            Map.entry("waf.fail_open.enabled", "false"),
            Map.entry("connection_logs.s3.enabled", "false"),
            Map.entry("connection_logs.s3.bucket", ""),
            Map.entry("connection_logs.s3.prefix", ""));

    private static final Map<String, String> NETWORK_ONLY = Map.of(
            "dns_record.client_routing_policy", "any_availability_zone",
            "secondary_ips.auto_assigned.per_subnet", "0");

    private static final Map<String, String> TARGET_GROUP_COMMON = Map.ofEntries(
            Map.entry("deregistration_delay.timeout_seconds", "300"),
            Map.entry("stickiness.enabled", "false"),
            Map.entry("load_balancing.cross_zone.enabled", "use_load_balancer_configuration"),
            Map.entry("load_balancing.algorithm.type", "round_robin"),
            Map.entry("target_group_health.dns_failover.minimum_healthy_targets.count", "off"),
            Map.entry("target_group_health.dns_failover.minimum_healthy_targets.percentage", "off"),
            Map.entry("target_group_health.unhealthy_state_routing.minimum_healthy_targets.count", "1"),
            Map.entry("target_group_health.unhealthy_state_routing.minimum_healthy_targets.percentage", "off"));

    private static final Map<String, String> TARGET_GROUP_HTTP = Map.of(
            "stickiness.type", "lb_cookie",
            "stickiness.lb_cookie.duration_seconds", "86400",
            "slow_start.duration_seconds", "0",
            "load_balancing.algorithm.anomaly_mitigation", "off");

    private static final Map<String, String> TARGET_GROUP_TCP = Map.of(
            "stickiness.type", "source_ip",
            "deregistration_delay.connection_termination.enabled", "false",
            "preserve_client_ip.enabled", "true",
            "proxy_protocol_v2.enabled", "false");

    private static final Map<String, String> LISTENER_COMMON = Map.of(
            "tcp.idle_timeout.seconds", "350");

    /**
     * @param type the load balancer's {@code Type}: {@code application}, {@code network} or
     *             {@code gateway}. Cross-zone balancing is on by default only for
     *             application load balancers.
     */
    static Map<String, String> forLoadBalancer(String type) {
        String kind = type == null ? "application" : type;
        Map<String, String> defaults = new LinkedHashMap<>(LOAD_BALANCER_COMMON);
        if (!"application".equals(kind)) {
            defaults.put("load_balancing.cross_zone.enabled", "false");
        }
        if ("application".equals(kind)) {
            defaults.putAll(APPLICATION_ONLY);
        } else if ("network".equals(kind)) {
            defaults.putAll(NETWORK_ONLY);
        }
        return defaults;
    }

    /** @param protocol the target group's {@code Protocol}, which selects the stickiness family. */
    static Map<String, String> forTargetGroup(String protocol) {
        Map<String, String> defaults = new LinkedHashMap<>(TARGET_GROUP_COMMON);
        if (isHttp(protocol)) {
            defaults.putAll(TARGET_GROUP_HTTP);
        } else {
            defaults.putAll(TARGET_GROUP_TCP);
        }
        return defaults;
    }

    static Map<String, String> forListener() {
        return new LinkedHashMap<>(LISTENER_COMMON);
    }

    /** Stored values win; the defaults only fill what nobody has set. */
    static Map<String, String> overlay(Map<String, String> defaults, Map<String, String> stored) {
        Map<String, String> merged = new LinkedHashMap<>(defaults);
        if (stored != null) {
            merged.putAll(stored);
        }
        return merged;
    }

    private static boolean isHttp(String protocol) {
        return "HTTP".equals(protocol) || "HTTPS".equals(protocol);
    }
}
