package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class TimestreamInfluxDbValidation {

    static final Pattern RESOURCE_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*");
    static final Pattern CLUSTER_NAME = Pattern.compile("[a-zA-z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*");
    static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9]+");
    static final Pattern ARN = Pattern.compile(
            "arn:aws[a-z\\-]*:timestream\\-influxdb:([a-z0-9\\-]+):([0-9]{12}):(db\\-instance|db\\-cluster|db\\-parameter\\-group|db\\-backup)/([a-zA-Z0-9]{3,64})");

    static final List<String> INSTANCE_TYPES = List.of("db.influx.medium", "db.influx.large", "db.influx.xlarge",
            "db.influx.2xlarge", "db.influx.4xlarge", "db.influx.8xlarge", "db.influx.12xlarge",
            "db.influx.16xlarge", "db.influx.24xlarge");
    static final List<String> STORAGE_TYPES = List.of("InfluxIOIncludedT1", "InfluxIOIncludedT2", "InfluxIOIncludedT3");
    static final List<String> INSTANCE_DEPLOYMENT_TYPES = List.of("SINGLE_AZ", "WITH_MULTIAZ_STANDBY");
    static final List<String> CLUSTER_DEPLOYMENT_TYPES = List.of("MULTI_NODE_READ_REPLICAS");
    static final List<String> RESOURCE_DEPLOYMENT_TYPES = List.of("SINGLE_AZ", "WITH_MULTIAZ_STANDBY", "MULTI_NODE_READ_REPLICAS");
    static final List<String> NETWORK_TYPES = List.of("IPV4", "DUAL");
    static final List<String> FAILOVER_MODES = List.of("AUTOMATIC", "NO_FAILOVER");
    static final List<String> RESTORE_MODES = List.of("NEW_RESOURCE", "REPLACE_EXISTING");

    private static final List<String> AUTOMATED_BACKUP_TYPES = List.of("HOURLY", "DAILY", "WEEKLY", "MONTHLY",
            "CUSTOM_SCHEDULE", "CONTINUOUS");
    private static final List<String> DURATION_TYPES = List.of("hours", "minutes", "seconds", "milliseconds", "days");
    private static final Pattern SUBNET_ID = Pattern.compile("subnet-[a-z0-9]+");
    private static final Pattern SECURITY_GROUP_ID = Pattern.compile("sg-[a-z0-9]+");
    private static final Pattern PASSWORD = Pattern.compile("[a-zA-Z0-9]+");
    private static final Pattern BUCKET = Pattern.compile("[^_\"][^\"]*");
    private static final Pattern KMS_KEY_ID = Pattern.compile("[a-zA-Z0-9:/_\\-]+");
    private static final Pattern S3_BUCKET = Pattern.compile("[0-9a-z]+[0-9a-z\\.\\-]*[0-9a-z]+");
    private static final Pattern TIMEZONE = Pattern.compile("(UTC|[A-Za-z_]+/[A-Za-z0-9_]+(/[A-Za-z0-9_]+)?)");
    private static final Pattern MAINTENANCE_WINDOW = Pattern.compile(
            "|(Mon|Tue|Wed|Thu|Fri|Sat|Sun):([01]\\d|2[0-3]):[0-5]\\d-(Mon|Tue|Wed|Thu|Fri|Sat|Sun):([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern CRON = Pattern.compile("cron\\(\\S+ \\S+ \\S+ \\S+ \\S+ \\S+\\)");
    private static final Set<String> ENGINE_PARAMETER_KEYS = Set.of("InfluxDBv2", "InfluxDBv3Core", "InfluxDBv3Enterprise");
    private static final Map<String, long[]> V2_NUMERIC_RANGES = Map.ofEntries(
            Map.entry("queryConcurrency", new long[]{0, 256}),
            Map.entry("queryQueueSize", new long[]{0, 256}),
            Map.entry("sessionLength", new long[]{1, 2880}),
            Map.entry("storageMaxConcurrentCompactions", new long[]{0, 64}),
            Map.entry("storageSeriesFileMaxConcurrentSnapshotCompactions", new long[]{0, 64}),
            Map.entry("storageWalMaxConcurrentWrites", new long[]{0, 256}),
            Map.entry("influxqlMaxSelectBuckets", new long[]{0, 1_000_000_000_000L}),
            Map.entry("influxqlMaxSelectPoint", new long[]{0, 1_000_000_000_000L}),
            Map.entry("influxqlMaxSelectSeries", new long[]{0, 1_000_000_000_000L}),
            Map.entry("queryInitialMemoryBytes", new long[]{0, 1_000_000_000_000L}),
            Map.entry("queryMaxMemoryBytes", new long[]{0, 1_000_000_000_000L}),
            Map.entry("queryMemoryBytes", new long[]{0, 1_000_000_000_000L}),
            Map.entry("storageCacheMaxMemorySize", new long[]{0, 1_000_000_000_000L}),
            Map.entry("storageCacheSnapshotMemorySize", new long[]{0, 1_000_000_000_000L}),
            Map.entry("storageCompactThroughputBurst", new long[]{0, 1_000_000_000_000L}),
            Map.entry("storageMaxIndexLogFileSize", new long[]{0, 1_000_000_000_000L}),
            Map.entry("storageSeriesIdSetCacheSize", new long[]{0, 1_000_000_000_000L}));
    private static final Map<String, List<String>> PARAMETER_ENUMS = Map.of(
            "logLevel", List.of("debug", "info", "error"),
            "tracingType", List.of("log", "jaeger", "disabled"),
            "logFormat", List.of("full"),
            "dataFusionRuntimeType", List.of("multi-thread", "multi-thread-alt"));
    private static final Set<Integer> RESERVED_PORTS = Set.of(2375, 2376, 7788, 7789, 7790, 7791, 7792, 7793, 7794,
            7795, 7796, 7797, 7798, 7799, 8090, 51678, 51679, 51680);

    private TimestreamInfluxDbValidation() {
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400, Map.of("reason", "FIELD_VALIDATION_FAILED"));
    }

    static AwsException notFound(String resourceType, String resourceId) {
        return new AwsException("ResourceNotFoundException",
                "The requested resource " + resourceId + " was not found or does not exist.", 400,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static AwsException conflict(String resourceType, String resourceId, String message) {
        return new AwsException("ConflictException", message, 400,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    static String requiredString(JsonNode request, String field, int min, int max, Pattern pattern) {
        String value = optionalString(request, field, min, max, pattern);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    static String optionalString(JsonNode request, String field, int min, int max, Pattern pattern) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String value = node.asText();
        if (value.length() < min || value.length() > max) {
            throw validation(field + " must have length between " + min + " and " + max + ".");
        }
        if (pattern != null && !pattern.matcher(value).matches()) {
            throw validation(field + " does not match the required pattern.");
        }
        return value;
    }

    static String requiredEnum(JsonNode request, String field, List<String> allowed) {
        String value = optionalEnum(request, field, allowed);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    static String optionalEnum(JsonNode request, String field, List<String> allowed) {
        String value = optionalString(request, field, 0, Integer.MAX_VALUE, null);
        if (value != null && !allowed.contains(value)) {
            throw validation(field + " must be one of " + allowed + ".");
        }
        return value;
    }

    static Integer optionalInt(JsonNode request, String field, long min, long max) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.asLong() < min || node.asLong() > max) {
            throw validation(field + " must be an integer between " + min + " and " + max + ".");
        }
        return node.asInt();
    }

    static Integer requiredInt(JsonNode request, String field, long min, long max) {
        Integer value = optionalInt(request, field, min, max);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    static Boolean optionalBoolean(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return node.asBoolean();
    }

    static Integer port(JsonNode request) {
        Integer port = optionalInt(request, "port", 1024, 65535);
        if (port != null && RESERVED_PORTS.contains(port)) {
            throw validation("port can't be 2375-2376, 7788-7799, 8090, or 51678-51680.");
        }
        return port;
    }

    static String password(JsonNode request, boolean required) {
        return required
                ? requiredString(request, "password", 8, 64, PASSWORD)
                : optionalString(request, "password", 8, 64, PASSWORD);
    }

    static String bucket(JsonNode request) {
        return optionalString(request, "bucket", 2, 64, BUCKET);
    }

    static String kmsKeyId(JsonNode request) {
        return optionalString(request, "kmsKeyId", 1, 2048, KMS_KEY_ID);
    }

    static List<String> subnetIds(JsonNode request, boolean required) {
        return stringList(request, "vpcSubnetIds", required, 1, 6, SUBNET_ID);
    }

    static List<String> securityGroupIds(JsonNode request, boolean required) {
        return stringList(request, "vpcSecurityGroupIds", required, 1, 5, SECURITY_GROUP_ID);
    }

    static List<String> stringList(JsonNode request, String field, boolean required, int min, int max, Pattern member) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw validation(field + " is required.");
            }
            return null;
        }
        if (!node.isArray() || node.size() < min || node.size() > max) {
            throw validation(field + " must contain between " + min + " and " + max + " items.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().length() > 64
                    || (member != null && !member.matcher(item.asText()).matches())) {
                throw validation(field + " contains an invalid value.");
            }
            values.add(item.asText());
        }
        return values;
    }

    static void storageForType(String storageType, Integer allocatedStorage) {
        if (allocatedStorage != null && !"InfluxIOIncludedT1".equals(storageType) && allocatedStorage < 400) {
            throw validation(storageType + " requires at least 400 GiB of allocatedStorage.");
        }
    }

    static Map<String, String> tags(JsonNode request, String field, boolean required) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw validation(field + " is required.");
            }
            return new LinkedHashMap<>();
        }
        if (!node.isObject() || node.size() < 1 || node.size() > 200) {
            throw validation(field + " must contain between 1 and 200 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String value = entry.getValue().isTextual() ? entry.getValue().asText() : null;
            if (entry.getKey().isEmpty() || entry.getKey().length() > 128 || value == null || value.length() > 256) {
                throw validation(field + " contains an invalid tag.");
            }
            tags.put(entry.getKey(), value);
        }
        return tags;
    }

    static List<String> tagKeys(JsonNode request) {
        List<String> keys = stringList(request, "tagKeys", true, 1, 200, null);
        for (String key : keys) {
            if (key.isEmpty() || key.length() > 128) {
                throw validation("tagKeys contains an invalid key.");
            }
        }
        return keys;
    }

    static JsonNode logDeliveryConfiguration(JsonNode request) {
        JsonNode node = optionalObject(request, "logDeliveryConfiguration");
        if (node == null) {
            return null;
        }
        JsonNode s3 = node.get("s3Configuration");
        if (s3 == null || !s3.isObject()) {
            throw validation("logDeliveryConfiguration.s3Configuration is required.");
        }
        requiredString(s3, "bucketName", 3, 63, S3_BUCKET);
        if (optionalBoolean(s3, "enabled") == null) {
            throw validation("logDeliveryConfiguration.s3Configuration.enabled is required.");
        }
        return node;
    }

    static JsonNode maintenanceSchedule(JsonNode request) {
        JsonNode node = optionalObject(request, "maintenanceSchedule");
        if (node == null) {
            return null;
        }
        requiredString(node, "timezone", 1, 64, TIMEZONE);
        requiredString(node, "preferredMaintenanceWindow", 0, 19, MAINTENANCE_WINDOW);
        return node;
    }

    static JsonNode dbBackupConfigurations(JsonNode request) {
        JsonNode node = request.get("dbBackupConfigurations");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.isEmpty() || node.size() > 4) {
            throw validation("dbBackupConfigurations must contain between 1 and 4 items.");
        }
        for (JsonNode configuration : node) {
            if (!configuration.isObject()) {
                throw validation("dbBackupConfigurations contains an invalid item.");
            }
            String type = requiredEnum(configuration, "type", AUTOMATED_BACKUP_TYPES);
            requiredInt(configuration, "retentionDays", 1, 365);
            if (optionalBoolean(configuration, "enabled") == null) {
                throw validation("dbBackupConfigurations.enabled is required.");
            }
            String schedule = optionalString(configuration, "customSchedule", 9, 256, CRON);
            if ("CUSTOM_SCHEDULE".equals(type) && schedule == null) {
                throw validation("customSchedule is required when type is CUSTOM_SCHEDULE.");
            }
        }
        return node;
    }

    static String parameters(JsonNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return TimestreamInfluxDbService.ENGINE_V2;
        }
        if (!parameters.isObject() || parameters.size() > 1) {
            throw validation("parameters must specify exactly one engine parameter set.");
        }
        Iterator<String> names = parameters.fieldNames();
        if (!names.hasNext()) {
            return TimestreamInfluxDbService.ENGINE_V2;
        }
        String engineKey = names.next();
        if (!ENGINE_PARAMETER_KEYS.contains(engineKey) || !parameters.get(engineKey).isObject()) {
            throw validation("parameters contains an unsupported engine parameter set: " + engineKey + ".");
        }
        JsonNode values = parameters.get(engineKey);
        validateParameterValues(values);
        return switch (engineKey) {
            case "InfluxDBv3Core" -> TimestreamInfluxDbService.ENGINE_V3_CORE;
            case "InfluxDBv3Enterprise" -> {
                requiredInt(values, "ingestQueryInstances", 1, 4);
                requiredInt(values, "queryOnlyInstances", 0, 10);
                if (optionalBoolean(values, "dedicatedCompactor") == null) {
                    throw validation("dedicatedCompactor is required for InfluxDBv3Enterprise parameters.");
                }
                yield TimestreamInfluxDbService.ENGINE_V3_ENTERPRISE;
            }
            default -> {
                for (Map.Entry<String, long[]> range : V2_NUMERIC_RANGES.entrySet()) {
                    optionalInt(values, range.getKey(), range.getValue()[0], range.getValue()[1]);
                }
                yield TimestreamInfluxDbService.ENGINE_V2;
            }
        };
    }

    private static void validateParameterValues(JsonNode values) {
        Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            List<String> allowed = PARAMETER_ENUMS.get(entry.getKey());
            if (allowed != null) {
                optionalEnum(values, entry.getKey(), allowed);
            }
            JsonNode value = entry.getValue();
            if (value.isObject() && value.has("durationType")) {
                requiredEnum(value, "durationType", DURATION_TYPES);
                JsonNode amount = value.get("value");
                if (amount == null || !amount.isIntegralNumber() || amount.asLong() < 0) {
                    throw validation(entry.getKey() + ".value must be a non-negative integer.");
                }
            }
        }
    }

    static Integer maxResults(JsonNode request) {
        return optionalInt(request, "maxResults", 1, 100);
    }

    static String nextToken(JsonNode request) {
        return optionalString(request, "nextToken", 1, Integer.MAX_VALUE, null);
    }

    private static JsonNode optionalObject(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw validation(field + " must be an object.");
        }
        return node;
    }
}
