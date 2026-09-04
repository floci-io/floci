package io.github.hectorvent.floci.services.controlcatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ControlCatalogService {
    private static final Pattern CONTROL_ARN = Pattern.compile(
            "^arn:(aws(?:[-a-z]*)?):(controlcatalog|controltower):([a-zA-Z0-9-]*)::control/([0-9a-zA-Z_\\-]+)$");
    private static final String SCP = "AWS::Organizations::Policy::SERVICE_CONTROL_POLICY";
    private static final String RCP = "AWS::Organizations::Policy::RESOURCE_CONTROL_POLICY";
    private static final String CONFIG_RULE = "AWS::Config::ConfigRule";

    private static final Map<String, ControlDefinition> CONTROLS = controls();

    private final ObjectMapper objectMapper;

    @Inject
    public ControlCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode getControl(JsonNode request, String requestRegion) {
        String controlArn = requireControlArn(request);
        Matcher matcher = CONTROL_ARN.matcher(controlArn);
        if (!matcher.matches()) {
            throw validation("ControlArn must be a valid AWS Control Tower or Control Catalog control ARN.");
        }

        String partition = matcher.group(1);
        String identifier = matcher.group(4);
        ControlDefinition definition = CONTROLS.get(identifier);
        if (definition == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified control was not found in the Control Catalog.", 404);
        }

        ObjectNode response = objectMapper.createObjectNode();
        String responseArn = definition.globalIdentifier() == null
                ? controlArn
                : "arn:" + partition + ":controlcatalog:::control/" + definition.globalIdentifier();
        response.put("Arn", responseArn);
        response.set("Aliases", stringArray(definition.aliases()));
        response.put("Name", definition.name());
        response.put("Description", definition.description());
        response.put("Behavior", definition.behavior());
        response.put("Severity", definition.severity());

        ObjectNode regionConfiguration = response.putObject("RegionConfiguration");
        regionConfiguration.put("Scope", definition.scope());
        if ("REGIONAL".equals(definition.scope())) {
            String region = requestRegion == null || requestRegion.isBlank() ? "us-east-1" : requestRegion;
            regionConfiguration.set("DeployableRegions", stringArray(List.of(region)));
        }

        response.putObject("Implementation").put("Type", definition.implementationType());
        response.put("ParameterRequirementSummary", definition.parameters().isEmpty() ? "NONE" : "OPTIONAL");
        ArrayNode parameters = response.putArray("Parameters");
        for (String parameter : definition.parameters()) {
            ObjectNode item = parameters.addObject();
            item.put("Name", parameter);
            item.put("Requirement", "OPTIONAL");
        }
        response.set("GovernedResources", objectMapper.createArrayNode());
        response.set("GovernedProviders", stringArray(List.of("AWS")));
        return response;
    }

    public ObjectNode listControls(JsonNode request, String maxResultsRaw, String nextToken) {
        int maxResults = parseMaxResults(maxResultsRaw);
        int offset = parseNextToken(nextToken);
        String implementationType = null;
        JsonNode filter = request == null ? null : request.get("Filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject()) throw validation("Filter must be a JSON object.");
            JsonNode implementations = filter.get("Implementations");
            if (implementations != null && !implementations.isNull()) {
                if (!implementations.isObject()) throw validation("Filter.Implementations must be an object.");
                JsonNode types = implementations.get("Types");
                if (types != null && !types.isNull()) {
                    if (!types.isArray() || types.size() != 1 || !types.get(0).isTextual()) {
                        throw validation("Filter.Implementations.Types must contain exactly one implementation type.");
                    }
                    implementationType = types.get(0).asText();
                }
            }
        }

        final String typeFilter = implementationType;
        List<ControlDefinition> definitions = CONTROLS.values().stream()
                .filter(definition -> definition.globalIdentifier() != null)
                .filter(definition -> typeFilter == null || typeFilter.equals(definition.implementationType()))
                .distinct()
                .sorted(java.util.Comparator.comparing(ControlDefinition::globalIdentifier))
                .toList();
        if (offset > definitions.size()) throw validation("nextToken is invalid.");
        int end = Math.min(definitions.size(), offset + maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode controls = response.putArray("Controls");
        for (ControlDefinition definition : definitions.subList(offset, end)) {
            ObjectNode item = controls.addObject();
            item.put("Arn", "arn:aws:controlcatalog:::control/" + definition.globalIdentifier());
            item.set("Aliases", stringArray(definition.aliases()));
            item.put("Name", definition.name());
            item.put("Description", definition.description());
            item.put("Behavior", definition.behavior());
            item.put("Severity", definition.severity());
            item.putObject("Implementation").put("Type", definition.implementationType());
            item.set("GovernedResources", objectMapper.createArrayNode());
        }
        if (end < definitions.size()) response.put("NextToken", Integer.toString(end));
        return response;
    }

    private static int parseMaxResults(String raw) {
        if (raw == null || raw.isBlank()) return 100;
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 100) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be between 1 and 100.");
        }
    }

    private static int parseNextToken(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int value = Integer.parseInt(raw);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private String requireControlArn(JsonNode request) {
        JsonNode value = request == null ? null : request.get("ControlArn");
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation("ControlArn is required.");
        }
        String controlArn = value.textValue();
        if (controlArn.length() < 34 || controlArn.length() > 2048) {
            throw validation("ControlArn must be between 34 and 2048 characters.");
        }
        return controlArn;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static Map<String, ControlDefinition> controls() {
        Map<String, ControlDefinition> controls = new LinkedHashMap<>();

        register(controls, new ControlDefinition(
                "7oo9e9bcs8ilm6ekoxu322yb3", List.of("CT.S3.PV.4"),
                "Require organization-only access to Amazon S3 resources",
                "Requires Amazon S3 resources in the organization to be accessed only by principals in the organization or AWS services.",
                "PREVENTIVE", "HIGH", "GLOBAL", RCP, List.of("ExemptedPrincipalArns")));
        register(controls, new ControlDefinition(
                "7mo7a2h2ebsq71l8k6uzr96ou", List.of("CT.S3.PV.5"),
                "Require encryption in transit for Amazon S3",
                "Requires calls to Amazon S3 resources to use secure transport.",
                "PREVENTIVE", "HIGH", "GLOBAL", RCP, List.of("ExemptedPrincipalArns")));
        register(controls, new ControlDefinition(
                "dvhe47fxg5o6lryqrq9g6sxg4", List.of("CT.SECRETSMANAGER.PV.1"),
                "Require organization-only access to AWS Secrets Manager resources",
                "Requires AWS Secrets Manager resources to be accessed only by principals in the organization or AWS services.",
                "PREVENTIVE", "HIGH", "GLOBAL", RCP, List.of("ExemptedPrincipalArns")));
        register(controls, new ControlDefinition(
                "eolw7feyvr8b4l2lfhp3bneou", List.of("CT.KMS.PV.7"),
                "Require organization-only access to AWS KMS resources",
                "Requires AWS KMS resources to be accessed only by principals in the organization or AWS services.",
                "PREVENTIVE", "HIGH", "GLOBAL", RCP, List.of("ExemptedPrincipalArns")));
        register(controls, new ControlDefinition(
                "aqnqv7jjgi2dtl6r1v12xglio", List.of("CT.STS.PV.1"),
                "Require organization-only access to AWS STS resources",
                "Requires supported AWS STS resources to be accessed only by principals in the organization or AWS services.",
                "PREVENTIVE", "HIGH", "GLOBAL", RCP, List.of("ExemptedPrincipalArns")));

        registerLegacy(controls, "AWS-GR_RESTRICT_ROOT_USER_ACCESS_KEYS",
                "Disallow creation of access keys for the root user", "PREVENTIVE", SCP,
                List.of("ExemptedPrincipalArns"));
        registerLegacy(controls, "AWS-GR_RESTRICT_ROOT_USER",
                "Disallow actions as the root user", "PREVENTIVE", SCP,
                List.of("ExemptedPrincipalArns", "ExemptAssumeRoot"));

        registerLegacyConfig(controls, "AWS-GR_ENCRYPTED_VOLUMES", "Detect whether EBS volumes are encrypted");
        controls.put("503uicglhjkokaajywfpt6ros", new ControlDefinition(
                "503uicglhjkokaajywfpt6ros", List.of("AWS-GR_ENCRYPTED_VOLUMES"),
                "Detect whether EBS volumes are encrypted", "Detects unencrypted Amazon EBS volumes.",
                "DETECTIVE", "HIGH", "REGIONAL", CONFIG_RULE, List.of()));
        registerLegacyConfig(controls, "AWS-GR_RESTRICTED_COMMON_PORTS", "Detect unrestricted access to common ports");
        registerLegacyConfig(controls, "AWS-GR_RESTRICTED_SSH", "Detect unrestricted SSH access");
        registerLegacyConfig(controls, "AWS-GR_ROOT_ACCOUNT_MFA_ENABLED", "Detect whether root user MFA is enabled");
        registerLegacyConfig(controls, "AWS-GR_S3_BUCKET_PUBLIC_READ_PROHIBITED", "Detect public read access to Amazon S3 buckets");
        registerLegacyConfig(controls, "AWS-GR_S3_BUCKET_PUBLIC_WRITE_PROHIBITED", "Detect public write access to Amazon S3 buckets");
        registerLegacyConfig(controls, "AWS-GR_EC2_VOLUME_INUSE_CHECK", "Detect unattached Amazon EBS volumes");
        registerLegacyConfig(controls, "AWS-GR_EBS_OPTIMIZED_INSTANCE", "Detect EC2 instances that are not EBS optimized");
        registerLegacyConfig(controls, "AWS-GR_RDS_INSTANCE_PUBLIC_ACCESS_CHECK", "Detect publicly accessible Amazon RDS instances");
        registerLegacyConfig(controls, "AWS-GR_RDS_SNAPSHOTS_PUBLIC_PROHIBITED", "Detect public Amazon RDS snapshots");
        registerLegacyConfig(controls, "AWS-GR_RDS_STORAGE_ENCRYPTED", "Detect unencrypted Amazon RDS storage");
        registerLegacyConfig(controls, "AWS-GR_DETECT_CLOUDTRAIL_ENABLED_ON_MEMBER_ACCOUNTS", "Detect whether CloudTrail is enabled in member accounts");

        return Map.copyOf(controls);
    }

    private static void register(Map<String, ControlDefinition> controls, ControlDefinition definition) {
        controls.put(definition.globalIdentifier(), definition);
    }

    private static void registerLegacy(Map<String, ControlDefinition> controls, String alias, String name,
                                       String behavior, String implementationType, List<String> parameters) {
        controls.put(alias, new ControlDefinition(null, List.of(alias), name, name + ".",
                behavior, "HIGH", "GLOBAL", implementationType, parameters));
    }

    private static void registerLegacyConfig(Map<String, ControlDefinition> controls, String alias, String name) {
        controls.put(alias, new ControlDefinition(null, List.of(alias), name, name + ".",
                "DETECTIVE", "HIGH", "REGIONAL", CONFIG_RULE, List.of()));
    }

    private record ControlDefinition(
            String globalIdentifier,
            List<String> aliases,
            String name,
            String description,
            String behavior,
            String severity,
            String scope,
            String implementationType,
            List<String> parameters) {
    }
}
