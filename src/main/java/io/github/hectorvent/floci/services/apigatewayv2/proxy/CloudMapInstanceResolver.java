package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks one instance of a Cloud Map service for an HTTP API private integration. The pick is random:
 * DiscoverInstances returns a randomized list (AWS Cloud Map API reference, DiscoverInstances).
 */
@ApplicationScoped
public class CloudMapInstanceResolver {

    // group 1 = service id, group 2 = optional attribute filter query
    static final Pattern CLOUD_MAP_SERVICE_ARN = Pattern.compile(
            "^arn:" + AwsArnUtils.PARTITION_REGEX + ":servicediscovery:[^:]+:[^:]*:service/([^/?]+)(?:\\?(.*))?$");

    private static final String ATTRIBUTE_IPV4 = "AWS_INSTANCE_IPV4";
    private static final String ATTRIBUTE_PORT = "AWS_INSTANCE_PORT";
    private static final String HEALTHY = "HEALTHY";
    private static final String ALL = "ALL";
    private static final Pattern IPV4 = Pattern.compile("^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)$");
    private static final int MAX_PORT = 65_535;

    public record Endpoint(String address, int port) {}

    private final CloudMapService cloudMapService;

    @Inject
    public CloudMapInstanceResolver(CloudMapService cloudMapService) {
        this.cloudMapService = cloudMapService;
    }

    public static boolean isServiceArn(String integrationUri) {
        return integrationUri != null && CLOUD_MAP_SERVICE_ARN.matcher(integrationUri).matches();
    }

    public Optional<Endpoint> pickInstance(String serviceArn) {
        Matcher arn = CLOUD_MAP_SERVICE_ARN.matcher(serviceArn);
        if (!arn.matches()) {
            throw new IllegalArgumentException("Not a Cloud Map service ARN: " + serviceArn);
        }
        Service service = cloudMapService.getService(arn.group(1));
        // DiscoverInstances HealthStatus: a service without a health check reports none, so all instances count
        boolean healthChecked = isPresent(service.getHealthCheckConfig())
                || isPresent(service.getHealthCheckCustomConfig());
        String healthFilter = healthChecked ? HEALTHY : ALL;

        List<Endpoint> candidates = new ArrayList<>();
        for (Instance instance : cloudMapService.filterInstances(service.getId(), healthFilter, parseQuery(arn.group(2)))) {
            toEndpoint(instance.getAttributes() != null ? instance.getAttributes() : Map.of()).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }

    private static Optional<Endpoint> toEndpoint(Map<String, String> attributes) {
        String address = attributes.get(ATTRIBUTE_IPV4);
        String portText = attributes.get(ATTRIBUTE_PORT);
        if (address == null || !IPV4.matcher(address).matches() || portText == null) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(portText);
            return port >= 1 && port <= MAX_PORT ? Optional.of(new Endpoint(address, port)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return filters;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            filters.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return filters;
    }

    private static boolean isPresent(String rawJson) {
        return rawJson != null && !rawJson.isBlank();
    }
}
