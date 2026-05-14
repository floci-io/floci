package io.github.hectorvent.floci.services.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Pricing API emulation backed by a bundled static snapshot.
 * <p>
 * Snapshots ship on the classpath under {@code pricing-snapshots/} and can be
 * overridden at runtime by setting {@code floci.services.pricing.snapshot-path}
 * (env: {@code FLOCI_SERVICES_PRICING_SNAPSHOT_PATH}) to a filesystem directory
 * with the same layout.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Price_List_Service.html">AWS Price List Service API</a>
 */
@ApplicationScoped
public class PricingService {

    private static final Logger LOG = Logger.getLogger(PricingService.class);

    static final String DEFAULT_FORMAT_VERSION = "aws_v1";
    private static final String SERVICES_FILE = "services.json";
    private static final String ATTRIBUTE_VALUES_DIR = "attribute-values";
    private static final String PRODUCTS_DIR = "products";
    private static final String PRICE_LISTS_DIR = "price-lists";

    private final ObjectMapper objectMapper;
    private final SnapshotLoader loader;

    private final Map<String, ServiceEntry> servicesByCode = new HashMap<>();
    private final List<ServiceEntry> servicesOrdered = new ArrayList<>();

    @Inject
    public PricingService(ObjectMapper objectMapper, EmulatorConfig config) {
        this(objectMapper, new SnapshotLoader(config.services().pricing().snapshotPath().orElse(null)));
    }

    PricingService(ObjectMapper objectMapper, SnapshotLoader loader) {
        this.objectMapper = objectMapper;
        this.loader = loader;
    }

    @PostConstruct
    void load() {
        try {
            JsonNode servicesNode = loader.readJson(SERVICES_FILE, objectMapper);
            if (servicesNode == null || !servicesNode.isArray()) {
                LOG.warnv("Pricing snapshot {0} not found or malformed; service will respond with empty results.", SERVICES_FILE);
                return;
            }
            for (JsonNode entry : servicesNode) {
                String code = entry.path("ServiceCode").asText(null);
                if (code == null || code.isEmpty()) {
                    continue;
                }
                List<String> attrs = new ArrayList<>();
                for (JsonNode a : entry.path("AttributeNames")) {
                    attrs.add(a.asText());
                }
                ServiceEntry se = new ServiceEntry(code, attrs);
                servicesByCode.put(code, se);
                servicesOrdered.add(se);
            }
            LOG.infov("Pricing snapshot loaded: {0} services.", servicesOrdered.size());
        } catch (IOException e) {
            LOG.errorv(e, "Failed to load pricing snapshot from {0}", loader.describe());
        }
    }

    /** Returns response for {@code DescribeServices}. */
    public ObjectNode describeServices(String serviceCode, String formatVersion, String nextToken, Integer maxResults) {
        List<ServiceEntry> slice;
        if (serviceCode != null && !serviceCode.isEmpty()) {
            ServiceEntry entry = servicesByCode.get(serviceCode);
            if (entry == null) {
                throw new AwsException("InvalidParameterException",
                        "Invalid ServiceCode: " + serviceCode, 400);
            }
            slice = List.of(entry);
        } else {
            slice = servicesOrdered;
        }

        Page<ServiceEntry> page = paginate(slice, nextToken, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("FormatVersion", resolveFormatVersion(formatVersion));
        ArrayNode services = response.putArray("Services");
        for (ServiceEntry entry : page.items()) {
            ObjectNode node = services.addObject();
            node.put("ServiceCode", entry.serviceCode());
            ArrayNode attrs = node.putArray("AttributeNames");
            for (String attr : entry.attributeNames()) {
                attrs.add(attr);
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    /** Returns response for {@code GetAttributeValues}. */
    public ObjectNode getAttributeValues(String serviceCode, String attributeName, String nextToken, Integer maxResults) {
        requireNonEmpty(serviceCode, "ServiceCode");
        requireNonEmpty(attributeName, "AttributeName");
        requireSafePathSegment(serviceCode, "ServiceCode");
        requireSafePathSegment(attributeName, "AttributeName");

        if (!servicesByCode.containsKey(serviceCode)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid ServiceCode: " + serviceCode, 400);
        }

        String resource = ATTRIBUTE_VALUES_DIR + "/" + serviceCode + "/" + attributeName + ".json";
        JsonNode node;
        try {
            node = loader.readJson(resource, objectMapper);
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to read snapshot: " + resource, 500);
        }

        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode v : node) {
                JsonNode value = v.path("Value");
                if (!value.isMissingNode() && !value.isNull()) {
                    values.add(value.asText());
                }
            }
        }

        Page<String> page = paginate(values, nextToken, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("AttributeValues");
        for (String v : page.items()) {
            arr.addObject().put("Value", v);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    /**
     * Returns response for {@code GetProducts}.
     * <p>
     * The AWS wire format returns {@code PriceList} as an array of <em>JSON strings</em>
     * (each string is a serialized product offer). Each element is also valid JSON on its own.
     */
    public ObjectNode getProducts(String serviceCode, List<FilterSpec> filters, String formatVersion,
                                   String nextToken, Integer maxResults) {
        requireNonEmpty(serviceCode, "ServiceCode");
        requireSafePathSegment(serviceCode, "ServiceCode");
        if (!servicesByCode.containsKey(serviceCode)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid ServiceCode: " + serviceCode, 400);
        }

        String region = resolveRegionFromFilters(filters);
        if (region != null) {
            requireSafePathSegment(region, "regionCode");
        }
        List<JsonNode> products = loadProducts(serviceCode, region);
        List<JsonNode> matched = applyFilters(products, filters);

        Page<JsonNode> page = paginate(matched, nextToken, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("FormatVersion", resolveFormatVersion(formatVersion));
        ArrayNode priceList = response.putArray("PriceList");
        for (JsonNode product : page.items()) {
            try {
                priceList.add(objectMapper.writeValueAsString(product));
            } catch (Exception e) {
                throw new AwsException("InternalFailure", "Failed to serialize product", 500);
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    /** Returns response for {@code ListPriceLists}. */
    public ObjectNode listPriceLists(String serviceCode, Instant effectiveDate, String regionCode,
                                      String currencyCode, String nextToken, Integer maxResults) {
        requireNonEmpty(serviceCode, "ServiceCode");
        requireNonEmpty(currencyCode, "CurrencyCode");
        requireSafePathSegment(serviceCode, "ServiceCode");
        if (effectiveDate == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'EffectiveDate' failed to satisfy constraint: Member must not be null.", 400);
        }

        if (!servicesByCode.containsKey(serviceCode)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid ServiceCode: " + serviceCode, 400);
        }

        Instant effectiveInstant = effectiveDate;

        String resource = PRICE_LISTS_DIR + "/" + serviceCode + ".json";
        JsonNode node;
        try {
            node = loader.readJson(resource, objectMapper);
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to read snapshot: " + resource, 500);
        }

        // Group entries per (region, currency) and keep the one whose
        // effective window covers `effectiveDate`. AWS returns one price list
        // per service/region/currency pair, matched by effective date.
        Map<String, JsonNode> winningByKey = new java.util.LinkedHashMap<>();
        Map<String, Instant> winningEffective = new HashMap<>();
        if (node != null && node.isArray()) {
            for (JsonNode entry : node) {
                String entryRegion = entry.path("RegionCode").asText("");
                String entryCurrency = entry.path("CurrencyCode").asText("");
                if (regionCode != null && !regionCode.isEmpty() && !regionCode.equals(entryRegion)) {
                    continue;
                }
                if (!currencyCode.equals(entryCurrency)) {
                    continue;
                }
                Instant entryEffective = resolvePriceListEffective(entry);
                if (entryEffective == null || entryEffective.isAfter(effectiveInstant)) {
                    // Entry's window has not started yet.
                    continue;
                }
                String key = entryRegion + "|" + entryCurrency;
                Instant current = winningEffective.get(key);
                if (current == null || entryEffective.isAfter(current)) {
                    winningByKey.put(key, entry);
                    winningEffective.put(key, entryEffective);
                }
            }
        }
        List<JsonNode> entries = new ArrayList<>(winningByKey.values());

        Page<JsonNode> page = paginate(entries, nextToken, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("PriceLists");
        for (JsonNode entry : page.items()) {
            ObjectNode out = arr.addObject();
            out.put("PriceListArn", entry.path("PriceListArn").asText());
            out.put("RegionCode", entry.path("RegionCode").asText());
            out.put("CurrencyCode", entry.path("CurrencyCode").asText());
            ArrayNode formats = out.putArray("FileFormats");
            for (JsonNode f : entry.path("FileFormats")) {
                formats.add(f.asText());
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    /**
     * Returns response for {@code GetPriceListFileUrl}. Returns a stub HTTPS URL that
     * points back at the configured Pricing snapshot; sufficient for integration tests
     * that assert a URL is returned but do not download it.
     */
    public ObjectNode getPriceListFileUrl(String priceListArn, String fileFormat) {
        requireNonEmpty(priceListArn, "PriceListArn");
        requireNonEmpty(fileFormat, "FileFormat");

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Url", "https://pricing-snapshot.floci.local/"
                + java.net.URLEncoder.encode(priceListArn, java.nio.charset.StandardCharsets.UTF_8)
                + "/" + fileFormat.toLowerCase());
        return response;
    }

    private List<JsonNode> loadProducts(String serviceCode, String region) {
        String resolvedRegion = (region == null || region.isEmpty()) ? "us-east-1" : region;
        requireSafePathSegment(resolvedRegion, "regionCode");
        String resource = PRODUCTS_DIR + "/" + serviceCode + "/" + resolvedRegion + ".json";
        JsonNode node;
        try {
            node = loader.readJson(resource, objectMapper);
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to read snapshot: " + resource, 500);
        }
        List<JsonNode> products = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode p : node) {
                products.add(p);
            }
        }
        return products;
    }

    private static List<JsonNode> applyFilters(List<JsonNode> products, List<FilterSpec> filters) {
        if (filters == null || filters.isEmpty()) {
            return products;
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode product : products) {
            JsonNode attrs = product.path("product").path("attributes");
            boolean match = true;
            for (FilterSpec filter : filters) {
                JsonNode attrValue = attrs.path(filter.field());
                if (attrValue.isMissingNode() || attrValue.isNull()
                        || !filter.value().equals(attrValue.asText())) {
                    match = false;
                    break;
                }
            }
            if (match) {
                out.add(product);
            }
        }
        return out;
    }

    private static String resolveRegionFromFilters(List<FilterSpec> filters) {
        if (filters == null) {
            return null;
        }
        for (FilterSpec f : filters) {
            if ("regionCode".equals(f.field())) {
                return f.value();
            }
        }
        return null;
    }

    private static String resolveFormatVersion(String requested) {
        return (requested == null || requested.isEmpty()) ? DEFAULT_FORMAT_VERSION : requested;
    }

    private static <T> Page<T> paginate(List<T> items, String nextToken, Integer maxResults) {
        int start = decodeToken(nextToken);
        if (start < 0 || start > items.size()) {
            throw new AwsException("ExpiredNextTokenException", "Invalid NextToken.", 400);
        }
        int limit = (maxResults == null || maxResults <= 0) ? items.size() : Math.min(maxResults, items.size() - start);
        int end = Math.min(items.size(), start + limit);
        List<T> sliced = items.subList(start, end);
        String next = (end < items.size()) ? encodeToken(end) : null;
        return new Page<>(sliced, next);
    }

    private static String encodeToken(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return Integer.parseInt(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static void requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field + "' failed to satisfy constraint: Member must not be null.", 400);
        }
    }

    /**
     * Rejects any input used to build a snapshot filesystem path that contains
     * characters outside {@code [A-Za-z0-9._-]}, to prevent traversal of the
     * override directory via {@code ..} or absolute-path segments.
     * AWS service codes, attribute names, and region codes all satisfy this
     * charset; values that don't cannot match anything in the catalog anyway.
     */
    private static void requireSafePathSegment(String value, String field) {
        if (value == null || value.isEmpty() || !SAFE_PATH_SEGMENT.matcher(value).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Invalid value for '" + field + "': contains characters not permitted in a path segment.", 400);
        }
    }

    /**
     * Extracts the effective-date instant for a price-list snapshot entry.
     * Prefers the {@code EffectiveDate} field when present; otherwise parses the
     * 14-digit {@code yyyyMMddHHmmss} segment from the {@code PriceListArn}.
     */
    private static Instant resolvePriceListEffective(JsonNode entry) {
        JsonNode effective = entry.path("EffectiveDate");
        if (!effective.isMissingNode() && !effective.isNull() && !effective.asText().isEmpty()) {
            try {
                return java.time.OffsetDateTime.parse(effective.asText()).toInstant();
            } catch (java.time.format.DateTimeParseException ignored) {
                try {
                    return Instant.parse(effective.asText());
                } catch (java.time.format.DateTimeParseException ignored2) {
                    // fall through to ARN parsing
                }
            }
        }
        String arn = entry.path("PriceListArn").asText("");
        java.util.regex.Matcher m = ARN_TIMESTAMP.matcher(arn);
        if (m.find()) {
            String ts = m.group(1);
            try {
                return java.time.LocalDateTime.parse(ts, ARN_TIMESTAMP_FMT)
                        .toInstant(java.time.ZoneOffset.UTC);
            } catch (java.time.format.DateTimeParseException ignored) {
                // fall through
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern SAFE_PATH_SEGMENT =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");
    private static final java.util.regex.Pattern ARN_TIMESTAMP =
            java.util.regex.Pattern.compile("/(\\d{14})(?:/|$)");
    private static final java.time.format.DateTimeFormatter ARN_TIMESTAMP_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    record ServiceEntry(String serviceCode, List<String> attributeNames) {
    }

    /** Single filter clause from {@code GetProducts} input. */
    public record FilterSpec(String type, String field, String value) {
    }

    private record Page<T>(List<T> items, String nextToken) {
    }

    /**
     * Loads snapshot JSON from either a filesystem override directory or the classpath.
     * Package-private to permit substitution in tests.
     */
    static final class SnapshotLoader {
        private final Path overrideRoot;

        SnapshotLoader(String overridePath) {
            if (overridePath == null || overridePath.isEmpty()) {
                this.overrideRoot = null;
            } else {
                Path root = Path.of(overridePath).toAbsolutePath().normalize();
                try {
                    // Resolve symlinks when the directory exists so that the
                    // containment check below compares real paths.
                    if (Files.isDirectory(root)) {
                        root = root.toRealPath();
                    }
                } catch (IOException e) {
                    // Fall back to the normalized path; containment check will
                    // still apply string-level to the resolved candidate.
                }
                this.overrideRoot = root;
            }
        }

        JsonNode readJson(String relativePath, ObjectMapper mapper) throws IOException {
            if (overrideRoot != null) {
                Path candidate = overrideRoot.resolve(relativePath).normalize();
                if (!candidate.startsWith(overrideRoot)) {
                    // Path traversal attempt — refuse silently and fall through
                    // to the classpath fallback (which is read-only and safe).
                    candidate = null;
                }
                if (candidate != null && Files.isRegularFile(candidate)) {
                    try (InputStream in = Files.newInputStream(candidate)) {
                        return mapper.readTree(in);
                    }
                }
            }
            String resource = "pricing-snapshots/" + relativePath;
            // Use the class's own loader rather than the thread's context loader.
            // In a GraalVM native image the thread context loader can be the
            // bootstrap loader, which sees no application resources, while the
            // class's loader sees everything bundled into the image.
            ClassLoader cl = PricingService.class.getClassLoader();
            try (InputStream in = cl != null
                    ? cl.getResourceAsStream(resource)
                    : ClassLoader.getSystemResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                return mapper.readTree(in);
            }
        }

        String describe() {
            return overrideRoot != null ? overrideRoot.toString() : "classpath:pricing-snapshots/";
        }
    }
}
