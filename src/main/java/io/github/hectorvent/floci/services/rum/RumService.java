package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** CloudWatch RUM app-monitor lifecycle backed by the configured Floci storage mode. */
@ApplicationScoped
public class RumService {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "rum:v1:";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern NAME_PATTERN = Pattern.compile("(?!\\.)[.\\-_#A-Za-z0-9]+");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(localhost)$|^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}"
                    + "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$|"
                    + "(?=^[a-zA-Z0-9.\\*-]{4,253}$)(?!.*\\.-)(?!.*-\\.)(?!.*\\.\\.)"
                    + "(?!.*[^.]{64,})^(\\*\\.)?(?![-.\\*])[^\\*]{1,}\\."
                    + "(\\*|(?!.*--)(?=.*[a-zA-Z])[^\\*]{1,}[^\\*-])$");
    private static final Pattern TAG_KEY_PATTERN = Pattern.compile("(?!aws:)[a-zA-Z+\\-=._:/]+");
    private static final Pattern S3_URI_PATTERN = Pattern.compile(
            "s3://[a-z0-9][-.a-z0-9]{1,62}(?:/[-!_*'().a-z0-9A-Z]+(?:/[-!_*'().a-z0-9A-Z]+)*)?/?");
    private static final Set<String> TELEMETRIES = Set.of("errors", "performance", "http");
    private static final Set<String> ENABLED_DISABLED = Set.of("ENABLED", "DISABLED");
    private static final Set<String> PLATFORMS = Set.of("Web", "Android", "iOS");

    private final StorageBackend<String, AppMonitor> monitorStore;

    @Inject
    public RumService(StorageFactory storageFactory) {
        this(storageFactory.create(
                "rum",
                "rum-app-monitors.json",
                new TypeReference<Map<String, AppMonitor>>() {
                }));
    }

    RumService(StorageBackend<String, AppMonitor> monitorStore) {
        this.monitorStore = monitorStore;
    }

    public synchronized AppMonitor createAppMonitor(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        validateName(name);
        DomainSelection domains = readDomains(request);
        JsonNode configuration = readConfiguration(request);
        JsonNode customEvents = readCustomEvents(request);
        JsonNode deobfuscation = readDeobfuscationConfiguration(request);
        boolean cwLogEnabled = request.has("CwLogEnabled")
                ? requireBoolean(request, "CwLogEnabled")
                : false;
        Map<String, String> tags = readTags(request);
        String platform = readPlatform(request);

        String key = storageKey(region, name);
        if (monitorStore.get(key).isPresent()) {
            throw resourceConflict(name);
        }

        String now = timestamp();
        AppMonitor monitor = new AppMonitor(
                UUID.randomUUID().toString(),
                name,
                domains.domain(),
                domains.domainList(),
                "CREATED",
                platform,
                now,
                now,
                tags,
                configuration,
                dataStorage(cwLogEnabled),
                customEvents,
                deobfuscation);
        monitorStore.put(key, monitor);
        return monitor;
    }

    public AppMonitor getAppMonitor(String region, String name) {
        validateName(name);
        return monitorStore.get(storageKey(region, name)).orElseThrow(() -> resourceNotFound(name));
    }

    public synchronized void updateAppMonitor(String region, String name, JsonNode request) {
        validateName(name);
        requireObject(request, "Request body");
        String key = storageKey(region, name);
        AppMonitor current = monitorStore.get(key).orElseThrow(() -> resourceNotFound(name));

        boolean domainChanged = request.has("Domain") || request.has("DomainList");
        DomainSelection domains = domainChanged
                ? readDomains(request)
                : new DomainSelection(current.getDomain(), current.getDomainList());
        JsonNode configuration = request.has("AppMonitorConfiguration")
                ? mergeObjects(current.getAppMonitorConfiguration(), readConfiguration(request))
                : current.getAppMonitorConfiguration();
        JsonNode customEvents = request.has("CustomEvents")
                ? mergeObjects(current.getCustomEvents(), readCustomEvents(request))
                : current.getCustomEvents();
        JsonNode deobfuscation = request.has("DeobfuscationConfiguration")
                ? mergeObjects(current.getDeobfuscationConfiguration(), readDeobfuscationConfiguration(request))
                : current.getDeobfuscationConfiguration();
        JsonNode storage = request.has("CwLogEnabled")
                ? dataStorage(requireBoolean(request, "CwLogEnabled"))
                : current.getDataStorage();

        boolean changed = domainChanged
                || request.has("AppMonitorConfiguration")
                || request.has("CustomEvents")
                || request.has("DeobfuscationConfiguration")
                || request.has("CwLogEnabled");
        if (!changed) {
            return;
        }

        AppMonitor updated = new AppMonitor(
                current.getId(),
                current.getName(),
                domains.domain(),
                domains.domainList(),
                current.getState(),
                current.getPlatform(),
                current.getCreated(),
                timestamp(),
                current.getTags(),
                configuration,
                storage,
                customEvents,
                deobfuscation);
        monitorStore.put(key, updated);
    }

    public synchronized void deleteAppMonitor(String region, String name) {
        validateName(name);
        String key = storageKey(region, name);
        if (monitorStore.get(key).isEmpty()) {
            throw resourceNotFound(name);
        }
        monitorStore.delete(key);
    }

    public Page listAppMonitors(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<AppMonitor> monitors = monitorStore.scan(key -> key.startsWith(region + "::"));
        monitors.sort(Comparator.comparing(AppMonitor::getName));

        int offset = decodeOffset(nextToken, monitors.size());
        int end = Math.min(offset + maxResults, monitors.size());
        String responseToken = end < monitors.size() ? encodeOffset(end) : null;
        return new Page(monitors.subList(offset, end), responseToken);
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String timestamp() {
        return TIMESTAMP_FORMATTER.format(Instant.now());
    }

    private static DomainSelection readDomains(JsonNode request) {
        boolean hasDomain = request.has("Domain");
        boolean hasDomainList = request.has("DomainList");
        if (hasDomain == hasDomainList) {
            throw validation("Specify exactly one of Domain or DomainList.");
        }
        if (hasDomain) {
            String domain = requireText(request, "Domain");
            validateDomain(domain, "Domain");
            return new DomainSelection(domain, null);
        }

        JsonNode node = request.get("DomainList");
        if (node == null || !node.isArray() || node.size() < 1 || node.size() > 5) {
            throw validation("DomainList must contain between 1 and 5 domains.");
        }
        List<String> domains = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode domainNode = node.get(i);
            if (!domainNode.isTextual()) {
                throw validation("DomainList members must be strings.");
            }
            String domain = domainNode.textValue();
            validateDomain(domain, "DomainList");
            domains.add(domain);
        }
        return new DomainSelection(null, domains);
    }

    private static void validateName(String name) {
        if (name == null || name.length() < 1 || name.length() > 255 || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("Name must match (?!\\.)[.\\-_#A-Za-z0-9]+ and contain at most 255 characters.");
        }
    }

    private static void validateDomain(String domain, String field) {
        if (domain == null || domain.length() < 1 || domain.length() > 253
                || !DOMAIN_PATTERN.matcher(domain).matches()) {
            throw validation(field + " contains an invalid domain.");
        }
    }

    private static JsonNode readConfiguration(JsonNode request) {
        JsonNode configuration = optionalObject(request, "AppMonitorConfiguration");
        if (configuration == null) {
            return null;
        }
        requireOptionalBoolean(configuration, "AllowCookies");
        requireOptionalBoolean(configuration, "EnableXRay");
        requireOptionalText(configuration, "GuestRoleArn");
        requireOptionalText(configuration, "IdentityPoolId");
        if (configuration.has("SessionSampleRate")) {
            JsonNode rate = configuration.get("SessionSampleRate");
            if (!rate.isNumber() || rate.doubleValue() < 0 || rate.doubleValue() > 1) {
                throw validation("SessionSampleRate must be between 0 and 1.");
            }
        }
        if (configuration.has("ExcludedPages") && configuration.has("IncludedPages")) {
            throw validation("ExcludedPages and IncludedPages cannot both be specified.");
        }
        validateStringArray(configuration, "ExcludedPages", 50, null);
        validateStringArray(configuration, "IncludedPages", 50, null);
        validateStringArray(configuration, "FavoritePages", 50, null);
        validateStringArray(configuration, "Telemetries", Integer.MAX_VALUE, TELEMETRIES);
        return configuration.deepCopy();
    }

    private static JsonNode readCustomEvents(JsonNode request) {
        JsonNode customEvents = optionalObject(request, "CustomEvents");
        if (customEvents == null) {
            return null;
        }
        if (customEvents.has("Status")) {
            String status = requireText(customEvents, "Status");
            if (!ENABLED_DISABLED.contains(status)) {
                throw validation("CustomEvents.Status must be ENABLED or DISABLED.");
            }
        }
        return customEvents.deepCopy();
    }

    private static JsonNode readDeobfuscationConfiguration(JsonNode request) {
        JsonNode configuration = optionalObject(request, "DeobfuscationConfiguration");
        if (configuration == null) {
            return null;
        }
        if (configuration.has("JavaScriptSourceMaps")) {
            JsonNode sourceMaps = configuration.get("JavaScriptSourceMaps");
            requireObject(sourceMaps, "JavaScriptSourceMaps");
            String status = requireText(sourceMaps, "Status");
            if (!ENABLED_DISABLED.contains(status)) {
                throw validation("JavaScriptSourceMaps.Status must be ENABLED or DISABLED.");
            }
            if (sourceMaps.has("S3Uri")) {
                String s3Uri = requireText(sourceMaps, "S3Uri");
                if (s3Uri.length() > 1024 || !S3_URI_PATTERN.matcher(s3Uri).matches()) {
                    throw validation("JavaScriptSourceMaps.S3Uri is invalid.");
                }
            } else if ("ENABLED".equals(status)) {
                throw validation("JavaScriptSourceMaps.S3Uri is required when Status is ENABLED.");
            }
        }
        return configuration.deepCopy();
    }

    private static String readPlatform(JsonNode request) {
        if (!request.has("Platform")) {
            return "Web";
        }
        String platform = requireText(request, "Platform");
        if (!PLATFORMS.contains(platform)) {
            throw validation("Platform must be Web, Android, or iOS.");
        }
        return platform;
    }

    private static JsonNode mergeObjects(JsonNode current, JsonNode update) {
        ObjectNode merged = current != null && current.isObject()
                ? (ObjectNode) current.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        update.fields().forEachRemaining(entry -> {
            JsonNode existingValue = merged.get(entry.getKey());
            JsonNode updateValue = entry.getValue();
            if (existingValue != null && existingValue.isObject() && updateValue.isObject()) {
                merged.set(entry.getKey(), mergeObjects(existingValue, updateValue));
            } else {
                merged.set(entry.getKey(), updateValue.deepCopy());
            }
        });
        return merged;
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (!request.has("Tags")) {
            return null;
        }
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || !tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("Tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode valueNode = entry.getValue();
            if (key.length() < 1 || key.length() > 128 || !TAG_KEY_PATTERN.matcher(key).matches()
                    || !valueNode.isTextual() || valueNode.textValue().length() > 256) {
                throw validation("Tags contains an invalid key or value.");
            }
            tags.put(key, valueNode.textValue());
        });
        return tags;
    }

    private static ObjectNode dataStorage(boolean enabled) {
        ObjectNode cwLog = JsonNodeFactory.instance.objectNode();
        cwLog.put("CwLogEnabled", enabled);
        ObjectNode dataStorage = JsonNodeFactory.instance.objectNode();
        dataStorage.set("CwLog", cwLog);
        return dataStorage;
    }

    private static JsonNode optionalObject(JsonNode parent, String field) {
        if (!parent.has(field)) {
            return null;
        }
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static void requireOptionalBoolean(JsonNode parent, String field) {
        if (parent.has(field)) {
            requireBoolean(parent, field);
        }
    }

    private static void requireOptionalText(JsonNode parent, String field) {
        if (parent.has(field)) {
            requireText(parent, field);
        }
    }

    private static void validateStringArray(JsonNode parent, String field, int maxSize, Set<String> values) {
        if (!parent.has(field)) {
            return;
        }
        JsonNode array = parent.get(field);
        if (!array.isArray() || array.size() > maxSize) {
            throw validation(field + " must be an array with at most " + maxSize + " entries.");
        }
        for (JsonNode value : array) {
            if (!value.isTextual() || (values != null && !values.contains(value.textValue()))) {
                throw validation(field + " contains an invalid value.");
            }
        }
    }

    private static int parseMaxResults(String value) {
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException resourceConflict(String name) {
        return new AwsException(
                "ConflictException",
                "App monitor " + name + " already exists.",
                409,
                Map.of("resourceName", name, "resourceType", "AppMonitor"));
    }

    private static AwsException resourceNotFound(String name) {
        return new AwsException(
                "ResourceNotFoundException",
                "App monitor " + name + " does not exist.",
                404,
                Map.of("resourceName", name, "resourceType", "AppMonitor"));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page(List<AppMonitor> monitors, String nextToken) {
        public Page {
            monitors = List.copyOf(monitors);
        }
    }

    private record DomainSelection(String domain, List<String> domainList) {
    }
}
