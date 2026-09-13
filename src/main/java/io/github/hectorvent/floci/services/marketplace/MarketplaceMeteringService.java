package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.marketplace.model.MarketplaceMeteringRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class MarketplaceMeteringService implements Resettable {
    private static final Pattern PRODUCT_CODE = Pattern.compile("[-a-zA-Z0-9/=:_.@]{1,255}");
    private static final KeyPair SIGNING_KEY = createSigningKey();
    private final ObjectMapper mapper;
    private final RequestContext context;
    private final AccountAwareStorageBackend<JsonNode> entities;
    private final AccountAwareStorageBackend<JsonNode> meterRecords;
    private final AccountAwareStorageBackend<JsonNode> clientTokens;
    private final AccountAwareStorageBackend<JsonNode> customerMappings;

    @Inject
    public MarketplaceMeteringService(StorageFactory factory, ObjectMapper mapper,
                                      RequestContext context) {
        this(mapper, context,
                factory.create("marketplace", "marketplace-entities.json", type()),
                factory.create("marketplace", "marketplace-metering-records.json", type()),
                factory.create("marketplace", "marketplace-metering-client-tokens.json", type()),
                factory.create("marketplace", "marketplace-metering-customers.json", type()));
    }

    MarketplaceMeteringService(ObjectMapper mapper, RequestContext context,
                               AccountAwareStorageBackend<JsonNode> entities,
                               AccountAwareStorageBackend<JsonNode> meterRecords,
                               AccountAwareStorageBackend<JsonNode> clientTokens,
                               AccountAwareStorageBackend<JsonNode> customerMappings) {
        this.mapper = mapper;
        this.context = context;
        this.entities = entities;
        this.meterRecords = meterRecords;
        this.clientTokens = clientTokens;
        this.customerMappings = customerMappings;
    }

    private static TypeReference<Map<String, JsonNode>> type() { return new TypeReference<>() {}; }

    public JsonNode handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "BatchMeterUsage" -> batchMeterUsage(request, region);
            case "MeterUsage" -> meterUsage(request, region);
            case "RegisterUsage" -> registerUsage(request, region);
            case "ResolveCustomer" -> resolveCustomer(request, region);
            default -> null;
        };
    }

    private synchronized ObjectNode meterUsage(JsonNode request, String region) {
        String productCode = productCode(request, true);
        Instant timestamp = timestamp(request, "Timestamp", 6);
        String dimension = text(request, "UsageDimension", 1, 255);
        int quantity = integer(request, "UsageQuantity", 0, Integer.MAX_VALUE, 0);
        validateAllocations(request.get("UsageAllocations"), quantity);
        if (request.path("DryRun").asBoolean(false)) {
            throw new AwsException("DryRunOperation", "Request would have succeeded, but DryRun flag is set.", 400);
        }

        String clientToken = optionalValidationText(request, "ClientToken");
        if (clientToken != null) {
            if (clientToken.length() > 64) {
                throw validation("ClientToken must be at most 64 characters.");
            }
            JsonNode prior = clientTokens.get(region + "/meter/" + clientToken).orElse(null);
            if (prior != null) {
                if (!prior.path("request").equals(request)) {
                    throw new AwsException("IdempotencyConflictException", "ClientToken is being used for multiple requests.", 400);
                }
                return mapper.createObjectNode().put("MeteringRecordId", prior.path("recordId").asText());
            }
        }

        Instant hour = timestamp.truncatedTo(ChronoUnit.HOURS);
        String key = region + "/meter/" + productCode + "/" + accountId() + "/" + dimension + "/" + hour;
        JsonNode prior = meterRecords.get(key).orElse(null);
        if (prior != null) {
            if (prior.path("quantity").asInt() != quantity) {
                throw new AwsException("DuplicateRequestException",
                        "A metering record already exists for this usage dimension and hour with a different quantity.", 400);
            }
            return mapper.createObjectNode().put("MeteringRecordId", prior.path("recordId").asText());
        }
        String recordId = "mr-" + compactId();
        MarketplaceMeteringRecord meteringRecord = new MarketplaceMeteringRecord(
                recordId, region, productCode, accountId(), dimension, quantity, hour);
        ObjectNode stored = mapper.createObjectNode()
                .put("recordId", meteringRecord.recordId())
                .put("quantity", meteringRecord.quantity());
        stored.set("request", request.deepCopy());
        meterRecords.put(key, stored);
        if (clientToken != null) {
            ObjectNode token = mapper.createObjectNode().put("recordId", recordId);
            token.set("request", request.deepCopy());
            clientTokens.put(region + "/meter/" + clientToken, token);
        }
        return mapper.createObjectNode().put("MeteringRecordId", recordId);
    }

    private synchronized ObjectNode batchMeterUsage(JsonNode request, String region) {
        JsonNode recordsNode = request.get("UsageRecords");
        if (recordsNode == null || !recordsNode.isArray() || recordsNode.size() > 25) {
            throw validation("UsageRecords must be an array with at most 25 records.");
        }
        String requestProduct = productCode(request, false);
        ArrayNode results = mapper.createArrayNode();
        for (JsonNode record : recordsNode) {
            Instant timestamp = timestamp(record, "Timestamp", 24);
            String dimension = text(record, "Dimension", 1, 255);
            int quantity = integer(record, "Quantity", 0, Integer.MAX_VALUE, 0);
            validateAllocations(record.get("UsageAllocations"), quantity);
            String customerAccount = optionalMeteringText(record, "CustomerAWSAccountId", "InvalidCustomerIdentifierException");
            String customerId = optionalMeteringText(record, "CustomerIdentifier", "InvalidCustomerIdentifierException");
            String licenseArn = optionalMeteringText(record, "LicenseArn", "InvalidLicenseException");
            if ((customerAccount == null || customerAccount.isBlank()) && (customerId == null || customerId.isBlank())) {
                throw new AwsException("InvalidCustomerIdentifierException",
                        "A usage record requires CustomerAWSAccountId or CustomerIdentifier.", 400);
            }
            if (requestProduct == null && licenseArn == null) {
                throw new AwsException("InvalidLicenseException",
                        "LicenseArn is required when ProductCode is not supplied.", 400);
            }
            String productIdentity = licenseArn != null ? licenseArn : requestProduct;
            String customerIdentity = customerAccount != null ? customerAccount : customerId;
            String key = region + "/batch/" + digest(productIdentity + "|" + customerIdentity + "|" + dimension + "|"
                    + timestamp.truncatedTo(ChronoUnit.HOURS));
            JsonNode prior = meterRecords.get(key).orElse(null);
            String status = "Success";
            String recordId;
            if (prior != null) {
                recordId = prior.path("recordId").asText();
                if (prior.path("quantity").asInt() != quantity) {
                    status = "DuplicateRecord";
                }
            } else {
                recordId = "mr-" + compactId();
                ObjectNode stored = mapper.createObjectNode().put("recordId", recordId).put("quantity", quantity);
                stored.set("request", record.deepCopy());
                meterRecords.put(key, stored);
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("UsageRecord", record.deepCopy()); result.put("MeteringRecordId", recordId); result.put("Status", status);
            results.add(result);
        }
        ObjectNode response = mapper.createObjectNode(); response.set("Results", results); response.set("UnprocessedRecords", mapper.createArrayNode());
        return response;
    }

    private ObjectNode registerUsage(JsonNode request, String region) {
        String productCode = productCode(request, true);
        JsonNode publicKeyNode = request.get("PublicKeyVersion");
        if (publicKeyNode == null || !publicKeyNode.isIntegralNumber() || publicKeyNode.asInt() < 1) {
            throw new AwsException("InvalidPublicKeyVersionException", "PublicKeyVersion must be at least 1.", 400);
        }
        int publicKeyVersion = publicKeyNode.asInt();
        String nonce = optionalValidationText(request, "Nonce");
        if (nonce != null && nonce.length() > 255) {
            throw validation("Nonce must be at most 255 characters.");
        }
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "PS256");
        header.put("typ", "JWT");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("ProductCode", productCode);
        payload.put("PublicKeyVersion", publicKeyVersion);
        if (nonce != null) {
            payload.put("Nonce", nonce);
        }
        String encodedHeader = base64Url(header.toString());
        String encodedPayload = base64Url(payload.toString());
        String signingInput = encodedHeader + "." + encodedPayload;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signPs256(signingInput));
        ObjectNode response = mapper.createObjectNode();
        response.put("Signature", signingInput + "." + signature);
        return response;
    }

    private synchronized ObjectNode resolveCustomer(JsonNode request, String region) {
        JsonNode tokenNode = request.get("RegistrationToken");
        if (tokenNode == null || !tokenNode.isTextual() || tokenNode.asText().isEmpty()) {
            throw new AwsException("InvalidTokenException", "RegistrationToken is required.", 400);
        }
        String token = tokenNode.asText();
        JsonNode existing = customerMappings.get(region + "/" + token).orElse(null);
        if (existing != null) {
            return (ObjectNode) existing.deepCopy();
        }

        byte[] hash = sha256(token);
        long number = 0;
        for (int i = 0; i < 6; i++) {
            number = (number << 8) | (hash[i] & 0xffL);
        }
        String customerAccount = String.format(Locale.ROOT, "%012d", number % 1_000_000_000_000L);
        String productCode = localProductCode(region);
        String licenseId = toHex(hash).substring(0, 24);
        ObjectNode response = mapper.createObjectNode();
        response.put("ProductCode", productCode);
        response.put("CustomerAWSAccountId", customerAccount);
        response.put("LicenseArn", "arn:aws:license-manager:" + region + ":" + customerAccount + ":license:l-" + licenseId);
        customerMappings.put(region + "/" + token, response);
        return response.deepCopy();
    }

    private String localProductCode(String region) {
        for (JsonNode entity : entities.scan(key -> true)) {
            if (entity.path("EntityType").asText().contains("Product")) {
                JsonNode details = entity.path("DetailsDocument");
                if (details.hasNonNull("ProductCode")) {
                    return details.path("ProductCode").asText();
                }
                return entity.path("EntityId").asText();
            }
        }
        return "local-product";
    }

    private void validateAllocations(JsonNode allocations, int quantity) {
        if (allocations == null || allocations.isNull()) {
            return;
        }
        if (!allocations.isArray() || allocations.isEmpty() || allocations.size() > 2500) {
            throw new AwsException("InvalidUsageAllocationsException", "UsageAllocations must contain between 1 and 2500 items.", 400);
        }
        long sum = 0;
        Set<String> tagSets = new HashSet<>();
        for (JsonNode allocation : allocations) {
            int allocated = integer(allocation, "AllocatedUsageQuantity", 0, Integer.MAX_VALUE, null);
            sum += allocated;
            JsonNode tags = allocation.get("Tags");
            if (tags != null) {
                if (!tags.isArray() || tags.size() > 5) {
                    throw new AwsException("InvalidTagException", "Each usage allocation supports at most 5 tags.", 400);
                }
                List<String> canonical = new ArrayList<>();
                for (JsonNode tag : tags) {
                    String key = tagText(tag, "Key", 1, 100);
                    String value = tagText(tag, "Value", 1, 256);
                    canonical.add(key + "=" + value);
                }
                canonical.sort(String::compareTo);
                String signature = String.join("&", canonical);
                if (!tagSets.add(signature)) {
                    throw new AwsException("InvalidUsageAllocationsException", "Usage allocations must have unique tag sets.", 400);
                }
            }
        }
        if (sum != quantity) {
            throw new AwsException("InvalidUsageAllocationsException", "Allocated usage quantity must equal UsageQuantity.", 400);
        }
    }

    private static Instant timestamp(JsonNode node, String field, int maxAgeHours) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new AwsException("TimestampOutOfBoundsException", field + " is required.", 400);
        }
        long millis = Math.round(value.asDouble() * 1000d);
        Instant instant = Instant.ofEpochMilli(millis);
        Instant now = Instant.now();
        if (instant.isBefore(now.minus(maxAgeHours, ChronoUnit.HOURS)) || instant.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new AwsException("TimestampOutOfBoundsException", "Timestamp is outside the accepted metering window.", 400);
        }
        if (maxAgeHours == 24) {
            ZonedDateTime z = now.atZone(ZoneOffset.UTC);
            if (z.getDayOfMonth() == 1 && z.getHour() >= 6 && instant.atZone(ZoneOffset.UTC).getMonth() != z.getMonth()) {
                throw new AwsException("TimestampOutOfBoundsException", "Previous-month usage is closed after 06:00 UTC on the first day of the month.", 400);
            }
        }
        return instant;
    }

    private static String productCode(JsonNode node, boolean required) {
        String value = optionalText(node, "ProductCode");
        if (value == null) {
            if (required) {
                throw invalidProduct("ProductCode is required.");
            }
            return null;
        }
        if (!PRODUCT_CODE.matcher(value).matches()) {
            throw invalidProduct("ProductCode is invalid.");
        }
        return value;
    }

    private static int integer(JsonNode node, String field, int min, int max, Integer fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (fallback != null) {
                return fallback;
            }
            throw validation(field + " is required.");
        }
        if (!value.isIntegralNumber()) {
            throw validation(field + " must be an integer.");
        }
        if (!value.canConvertToInt()) {
            throw validation(field + " is out of range.");
        }
        int result = value.asInt();
        if (result < min || result > max) {
            throw validation(field + " is out of range.");
        }
        return result;
    }

    private static String text(JsonNode node, String field, int min, int max) {
        String value = optionalText(node, field);
        if (value == null || value.length() < min || value.length() > max) {
            throw new AwsException("InvalidUsageDimensionException", field + " is required or out of range.", 400);
        }
        return value;
    }
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException("InvalidUsageDimensionException", field + " must be a string.", 400);
        }
        return value.asText();
    }
    private static String optionalValidationText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.asText();
    }

    private static String optionalMeteringText(JsonNode node, String field, String errorCode) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new AwsException(errorCode, field + " must be a non-empty string.", 400);
        }
        return value.asText();
    }
    private static String tagText(JsonNode node, String field, int min, int max) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().length() < min || value.asText().length() > max) {
            throw new AwsException("InvalidTagException", field + " is invalid.", 400);
        }
        return value.asText();
    }
    private String accountId() { return context == null || context.getAccountId() == null ? "000000000000" : context.getAccountId(); }
    private static AwsException invalidProduct(String message) { return new AwsException("InvalidProductCodeException", message, 400); }
    private static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }
    private static String compactId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private static KeyPair createSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize Marketplace metering signing key", e);
        }
    }

    private static byte[] signPs256(String value) {
        try {
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, 32, 1));
            signature.initSign(SIGNING_KEY.getPrivate());
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign Marketplace metering JWT", e);
        }
    }

    private static byte[] sha256(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String digest(String value) { return toHex(sha256(value)); }
    private static String base64Url(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String toHex(byte[] bytes) { StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format(Locale.ROOT,"%02x",b)); return out.toString(); }

    @Override public void clear() { meterRecords.clear(); clientTokens.clear(); customerMappings.clear(); }
}
