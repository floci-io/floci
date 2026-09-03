package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.AuthorizerConfig;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.ClientCertificateConfig;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.ServerCertificateConfig;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.ServerCertificateSummary;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.TlsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS IoT Core domain configurations: the control-plane records behind a custom domain. A new
 * configuration is ENABLED and CUSTOMER_MANAGED, exactly as AWS creates it; nothing here changes
 * where the emulator's broker listens.
 */
@ApplicationScoped
public class IotDomainConfigurationService {

    static final String DEFAULT_SECURITY_POLICY = "IoTSecurityPolicy_TLS13_1_2_2022_10";

    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w.-]{1,128}");
    private static final Set<String> SERVICE_TYPES = Set.of("DATA", "CREDENTIAL_PROVIDER", "JOBS");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> AUTHENTICATION_TYPES =
            Set.of("CUSTOM_AUTH_X509", "CUSTOM_AUTH", "AWS_X509", "AWS_SIGV4", "DEFAULT");
    private static final Set<String> APPLICATION_PROTOCOLS = Set.of("SECURE_MQTT", "MQTT_WSS", "HTTPS", "DEFAULT");

    private final StorageBackend<String, IotDomainConfiguration> store;
    private final RegionResolver regionResolver;

    @Inject
    public IotDomainConfigurationService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("iot", "iot-domain-configurations.json",
                new TypeReference<Map<String, IotDomainConfiguration>>() {}), regionResolver);
    }

    IotDomainConfigurationService(StorageBackend<String, IotDomainConfiguration> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public IotDomainConfiguration createDomainConfiguration(String name, JsonNode request, String region) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw invalid("Invalid domain configuration name: " + name);
        }
        String key = key(region, name);
        if (store.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Domain configuration already exists: " + name, 409);
        }
        JsonNode body = request == null ? JsonNodeFactory.instance.objectNode() : request;
        String domainName = text(body, "domainName");
        List<String> certificateArns = textList(body.path("serverCertificateArns"));
        if (certificateArns.size() > 1) {
            throw invalid("serverCertificateArns can hold at most one certificate");
        }
        if (domainName != null && certificateArns.isEmpty()) {
            throw invalid("A server certificate is required for a customer-managed domain");
        }

        IotDomainConfiguration configuration = new IotDomainConfiguration();
        configuration.setDomainConfigurationName(name);
        configuration.setDomainConfigurationArn(regionResolver.buildArn("iot", region,
                "domainconfiguration/" + name + "/" + UUID.randomUUID().toString().replace("-", "").substring(0, 5)));
        configuration.setDomainName(domainName);
        configuration.setServiceType(enumValue(body, "serviceType", SERVICE_TYPES, "DATA"));
        configuration.setDomainConfigurationStatus("ENABLED");
        // A configuration without a domain name describes the account's default endpoint on AWS.
        configuration.setDomainType(domainName == null ? "ENDPOINT" : "CUSTOMER_MANAGED");
        configuration.setServerCertificates(certificateArns.stream()
                .map(arn -> new ServerCertificateSummary(arn, "VALID", null))
                .toList());
        configuration.setValidationCertificateArn(text(body, "validationCertificateArn"));
        configuration.setAuthorizerConfig(parseAuthorizerConfig(body.path("authorizerConfig")));
        configuration.setTlsConfig(parseTlsConfig(body.path("tlsConfig")));
        configuration.setServerCertificateConfig(parseServerCertificateConfig(body.path("serverCertificateConfig")));
        configuration.setAuthenticationType(enumValue(body, "authenticationType", AUTHENTICATION_TYPES, null));
        configuration.setApplicationProtocol(enumValue(body, "applicationProtocol", APPLICATION_PROTOCOLS, null));
        configuration.setClientCertificateConfig(parseClientCertificateConfig(body.path("clientCertificateConfig")));
        configuration.setTags(parseTags(body.path("tags")));
        configuration.setLastStatusChangeDate(Instant.now());
        store.put(key, configuration);
        return configuration;
    }

    public IotDomainConfiguration describeDomainConfiguration(String name, String region) {
        return store.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Domain configuration not found: " + name, 404));
    }

    public IotDomainConfiguration updateDomainConfiguration(String name, JsonNode request, String region) {
        IotDomainConfiguration configuration = describeDomainConfiguration(name, region);
        JsonNode body = request == null ? JsonNodeFactory.instance.objectNode() : request;
        // Validate everything before touching the stored record so a bad value changes nothing.
        String status = enumValue(body, "domainConfigurationStatus", STATUSES, null);
        String authenticationType = enumValue(body, "authenticationType", AUTHENTICATION_TYPES, null);
        String applicationProtocol = enumValue(body, "applicationProtocol", APPLICATION_PROTOCOLS, null);

        if (body.hasNonNull("authorizerConfig")) {
            configuration.setAuthorizerConfig(parseAuthorizerConfig(body.get("authorizerConfig")));
        }
        if (body.path("removeAuthorizerConfig").asBoolean(false)) {
            configuration.setAuthorizerConfig(null);
        }
        if (status != null && !status.equals(configuration.getDomainConfigurationStatus())) {
            configuration.setDomainConfigurationStatus(status);
            configuration.setLastStatusChangeDate(Instant.now());
        }
        if (body.hasNonNull("tlsConfig")) {
            configuration.setTlsConfig(parseTlsConfig(body.get("tlsConfig")));
        }
        if (body.hasNonNull("serverCertificateConfig")) {
            configuration.setServerCertificateConfig(parseServerCertificateConfig(body.get("serverCertificateConfig")));
        }
        if (authenticationType != null) {
            configuration.setAuthenticationType(authenticationType);
        }
        if (applicationProtocol != null) {
            configuration.setApplicationProtocol(applicationProtocol);
        }
        if (body.hasNonNull("clientCertificateConfig")) {
            configuration.setClientCertificateConfig(parseClientCertificateConfig(body.get("clientCertificateConfig")));
        }
        store.put(key(region, name), configuration);
        return configuration;
    }

    /** AWS refuses to delete an ENABLED configuration; callers disable it first. */
    public void deleteDomainConfiguration(String name, String region) {
        IotDomainConfiguration configuration = describeDomainConfiguration(name, region);
        if ("ENABLED".equals(configuration.getDomainConfigurationStatus())) {
            throw invalid("Domain configuration " + name + " must be DISABLED before it can be deleted");
        }
        store.delete(key(region, name));
    }

    public IotService.Page<IotDomainConfiguration> listDomainConfigurations(String region, String serviceType,
                                                                             String marker, Integer pageSize) {
        if (serviceType != null && !SERVICE_TYPES.contains(serviceType)) {
            throw invalid("Unsupported serviceType: " + serviceType);
        }
        if (pageSize != null && (pageSize < 1 || pageSize > 250)) {
            throw invalid("pageSize must be between 1 and 250");
        }
        String prefix = key(region, "");
        List<IotDomainConfiguration> items = store.scan(storeKey -> storeKey.startsWith(prefix)).stream()
                .filter(configuration -> serviceType == null || serviceType.equals(configuration.getServiceType()))
                .sorted(Comparator.comparing(IotDomainConfiguration::getDomainConfigurationName))
                .toList();
        int start = parseMarker(marker, items.size());
        int end = pageSize == null ? items.size() : Math.min(items.size(), start + pageSize);
        return new IotService.Page<>(items.subList(start, end), end < items.size() ? Integer.toString(end) : null);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return new TreeMap<>(storedByArn(resourceArn).getTags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        IotDomainConfiguration configuration = storedByArn(resourceArn);
        Map<String, String> updated = new TreeMap<>(configuration.getTags());
        updated.putAll(tags);
        configuration.setTags(updated);
        store.put(keyForArn(resourceArn, configuration), configuration);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        IotDomainConfiguration configuration = storedByArn(resourceArn);
        Map<String, String> updated = new TreeMap<>(configuration.getTags());
        tagKeys.forEach(updated::remove);
        configuration.setTags(updated);
        store.put(keyForArn(resourceArn, configuration), configuration);
    }

    /** The configuration an ARN names; the random suffix has to match too, as it does on AWS. */
    private IotDomainConfiguration storedByArn(String resourceArn) {
        AwsArnUtils.Arn arn = parseDomainConfigurationArn(resourceArn);
        String rest = arn.resource().substring("domainconfiguration/".length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        return store.get(key(arn.region(), name))
                .filter(configuration -> resourceArn.equals(configuration.getDomainConfigurationArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));
    }

    private static String keyForArn(String resourceArn, IotDomainConfiguration configuration) {
        return key(parseDomainConfigurationArn(resourceArn).region(), configuration.getDomainConfigurationName());
    }

    private static AwsArnUtils.Arn parseDomainConfigurationArn(String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid resource ARN: " + resourceArn);
        }
        if (!"iot".equals(arn.service()) || arn.region().isBlank()
                || !arn.resource().startsWith("domainconfiguration/")) {
            throw invalid("Invalid resource ARN: " + resourceArn);
        }
        return arn;
    }

    private static int parseMarker(String marker, int size) {
        if (marker == null || marker.isBlank()) {
            return 0;
        }
        try {
            return Math.min(size, Math.max(0, Integer.parseInt(marker)));
        } catch (NumberFormatException e) {
            throw invalid("Invalid marker: " + marker);
        }
    }

    private static AuthorizerConfig parseAuthorizerConfig(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new AuthorizerConfig(text(node, "defaultAuthorizerName"), bool(node, "allowAuthorizerOverride"));
    }

    private static TlsConfig parseTlsConfig(JsonNode node) {
        String securityPolicy = node.isObject() ? text(node, "securityPolicy") : null;
        return new TlsConfig(securityPolicy == null ? DEFAULT_SECURITY_POLICY : securityPolicy);
    }

    private static ServerCertificateConfig parseServerCertificateConfig(JsonNode node) {
        if (!node.isObject()) {
            return new ServerCertificateConfig(false, null, null);
        }
        Boolean enableOcspCheck = bool(node, "enableOCSPCheck");
        return new ServerCertificateConfig(enableOcspCheck != null && enableOcspCheck,
                text(node, "ocspLambdaArn"), text(node, "ocspAuthorizedResponderArn"));
    }

    private static ClientCertificateConfig parseClientCertificateConfig(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new ClientCertificateConfig(text(node, "clientCertificateCallbackArn"));
    }

    private static Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new TreeMap<>();
        if (node.isArray()) {
            for (JsonNode tag : node) {
                String tagKey = text(tag, "Key");
                if (tagKey == null || tagKey.isBlank()) {
                    throw invalid("Tag keys must not be blank");
                }
                String value = text(tag, "Value");
                tags.put(tagKey, value == null ? "" : value);
            }
        }
        return tags;
    }

    private static String enumValue(JsonNode node, String field, Set<String> allowed, String fallback) {
        String value = text(node, field);
        if (value == null) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw invalid("Unsupported " + field + ": " + value);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asBoolean() : null;
    }

    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static String key(String region, String name) {
        return "domain-configuration:" + region + ":" + name;
    }
}
