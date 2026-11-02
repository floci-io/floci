package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Payment Cryptography control plane.
 *
 * <p>The service's control plane is JSON 1.1: the operation is named in the
 * {@code X-Amz-Target} header and the body is a JSON object.
 */
@ApplicationScoped
public class PaymentCryptographyJsonHandler {

    /** The error a bad page size or a bad token is refused with. */
    private static final String PAGINATION_ERROR = "ValidationException";

    private final PaymentCryptographyService service;

    @Inject
    public PaymentCryptographyJsonHandler(PaymentCryptographyService service) {
        this.service = service;
    }

    /** The actions whose requests are checked against the vendored model constraints first. */
    private static final Set<String> MODEL_CHECKED = Set.of(
            "CreateKey", "GetKey", "ListKeys", "DeleteKey",
            "CreateAlias", "GetAlias", "ListAliases", "UpdateAlias", "DeleteAlias",
            "TagResource", "UntagResource", "ListTagsForResource");

    public Response handle(String action, JsonNode request, String region) {
        if (MODEL_CHECKED.contains(action)) {
            PaymentCryptographyRequestShapes.check(action, request);
        }
        return switch (action) {
            case "CreateKey" -> Response.ok(createKey(request, region)).build();
            case "GetKey" -> Response.ok(getKey(request, region)).build();
            case "ListKeys" -> Response.ok(listKeys(request, region)).build();
            case "DeleteKey" -> Response.ok(deleteKey(request, region)).build();
            case "CreateAlias" -> Response.ok(createAlias(request, region)).build();
            case "GetAlias" -> Response.ok(getAlias(request, region)).build();
            case "ListAliases" -> Response.ok(listAliases(request, region)).build();
            case "UpdateAlias" -> Response.ok(updateAlias(request, region)).build();
            case "DeleteAlias" -> Response.ok(deleteAlias(request, region)).build();
            case "TagResource" -> Response.ok(tagResource(request, region)).build();
            case "UntagResource" -> Response.ok(untagResource(request, region)).build();
            case "ListTagsForResource" -> Response.ok(listTagsForResource(request, region)).build();
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    // ---- keys ---------------------------------------------------------------

    private Map<String, Object> createKey(JsonNode request, String region) {
        JsonNode attributes = request.get("KeyAttributes");
        if (attributes == null || !attributes.isObject()) {
            throw new AwsException("ValidationException", "KeyAttributes is required", 400);
        }
        // The service requires the usage and the modes of use of a key, as the
        // reference does: a key without them says nothing about what it is for.
        if (!attributes.hasNonNull("KeyUsage")) {
            throw new AwsException("ValidationException", "KeyAttributes.KeyUsage is required", 400);
        }
        if (!attributes.hasNonNull("KeyModesOfUse")) {
            throw new AwsException("ValidationException", "KeyAttributes.KeyModesOfUse is required", 400);
        }
        Map<String, Object> declared = new LinkedHashMap<>();
        attributes.fields().forEachRemaining(entry -> {
            // KeyModesOfUse is a nested structure, not a name: it is read below
            // as its own map, and stringifying it here would lose the modes.
            if (!"KeyModesOfUse".equals(entry.getKey())) {
                declared.put(entry.getKey(), entry.getValue().asText());
            }
        });
        // The check value of an AES key is a CMAC, which is the only algorithm this
        // service computes, so a request naming another one is refused rather than
        // answered with a check value of another kind.
        if (request.hasNonNull("KeyCheckValueAlgorithm")
                && !"CMAC".equals(request.get("KeyCheckValueAlgorithm").asText())) {
            throw new AwsException("ValidationException",
                    "KeyCheckValueAlgorithm must be CMAC", 400);
        }
        // A key may be created disabled, and it may be created with tags.
        boolean enabled = request.has("Enabled") ? request.path("Enabled").asBoolean(true) : true;
        PaymentCryptographyKey key = service.createKey(region, declared, modesOfUse(attributes),
                request.path("Exportable").asBoolean(false), enabled, tagsOf(request));
        return Map.of("Key", keyObject(key));
    }

    /** The key operations name their key in {@code KeyIdentifier}. */
    private static final String KEY_IDENTIFIER = "KeyIdentifier";

    /** The alias operations name the key they point at in {@code KeyArn}. */
    private static final String KEY_ARN = "KeyArn";

    /** The tagging operations name their key in {@code ResourceArn}. */
    private static final String RESOURCE_ARN = "ResourceArn";

    /** The modes of use a key has, as the reference names them; the ones not asked for are false. */
    private static final List<String> MODES_OF_USE = List.of("Encrypt", "Decrypt", "Wrap", "Unwrap", "Generate",
            "Sign", "Verify", "DeriveKey", "NoRestrictions");

    private Map<String, Object> getKey(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, KEY_IDENTIFIER);
        return Map.of("Key", keyObject(key));
    }

    private Map<String, Object> listKeys(JsonNode request, String region) {
        String state = request.hasNonNull("KeyState") ? request.get("KeyState").asText() : null;
        PaginatedResult<PaymentCryptographyKey> page = service.listKeys(region, state,
                Pagination.parseMaxResults(request, "MaxResults", PAGINATION_ERROR),
                Pagination.parseNextToken(request, "NextToken", PAGINATION_ERROR));
        List<Map<String, Object>> keys = new ArrayList<>();
        for (PaymentCryptographyKey key : page.items()) {
            keys.add(keyObject(key));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Keys", keys);
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return out;
    }

    /**
     * Deletes a key and answers with the key, as the reference does.
     *
     * <p>The waiting period the reference offers is read, and the key is
     * deleted at once all the same: the service deletes a key the moment it
     * is asked to.
     */
    private Map<String, Object> deleteKey(JsonNode request, String region) {
        PaymentCryptographyKey key = service.deleteKey(requiredKey(request, region, KEY_IDENTIFIER));
        return Map.of("Key", keyObject(key));
    }

    // ---- aliases ------------------------------------------------------------

    private Map<String, Object> createAlias(JsonNode request, String region) {
        String aliasName = aliasName(request);
        // The model requires only the name of an alias, but the reference refuses one
        // without a key, so the request is refused the way it refuses it.
        if (!request.hasNonNull(KEY_ARN)) {
            throw new AwsException("ValidationException",
                    "You can't create an alias without a key.", 400);
        }
        PaymentCryptographyKey key = requiredKey(request, region, KEY_ARN);
        PaymentCryptographyAlias alias = service.createAlias(aliasName, key);
        return Map.of("Alias", aliasObject(alias));
    }

    private Map<String, Object> getAlias(JsonNode request, String region) {
        String aliasName = aliasName(request);
        PaymentCryptographyAlias alias = service.findAlias(region, aliasName);
        if (alias == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Alias " + aliasName + " is not found", 400);
        }
        return Map.of("Alias", aliasObject(alias));
    }

    private Map<String, Object> listAliases(JsonNode request, String region) {
        String keyArn = request.hasNonNull("KeyArn") ? request.get("KeyArn").asText() : null;
        PaginatedResult<PaymentCryptographyAlias> page = service.listAliases(region,
                keyArn, Pagination.parseMaxResults(request, "MaxResults", PAGINATION_ERROR),
                Pagination.parseNextToken(request, "NextToken", PAGINATION_ERROR));
        List<Map<String, Object>> aliases = new ArrayList<>();
        for (PaymentCryptographyAlias alias : page.items()) {
            aliases.add(aliasObject(alias));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Aliases", aliases);
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return out;
    }

    /**
     * Points an alias at a key, or removes the key it names when the request
     * names no key, as the reference does: the key of an alias is optional.
     */
    private Map<String, Object> updateAlias(JsonNode request, String region) {
        String aliasName = aliasName(request);
        if (!request.hasNonNull(KEY_ARN)) {
            return Map.of("Alias", aliasObject(service.detachAlias(region, aliasName)));
        }
        PaymentCryptographyKey key = requiredKey(request, region, KEY_ARN);
        PaymentCryptographyAlias alias = service.updateAlias(aliasName, key);
        return Map.of("Alias", aliasObject(alias));
    }

    private Map<String, Object> deleteAlias(JsonNode request, String region) {
        service.deleteAlias(region, aliasName(request));
        return Map.of();
    }

    // ---- tags ---------------------------------------------------------------

    private Map<String, Object> tagResource(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, RESOURCE_ARN);
        service.tag(key, tagsOf(request));
        return Map.of();
    }

    private Map<String, Object> untagResource(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, RESOURCE_ARN);
        List<String> tagKeys = new ArrayList<>();
        JsonNode declared = request.get("TagKeys");
        if (declared != null && declared.isArray()) {
            for (JsonNode tagKey : declared) {
                tagKeys.add(tagKey.asText());
            }
        }
        service.untag(key, tagKeys);
        return Map.of();
    }

    private Map<String, Object> listTagsForResource(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, RESOURCE_ARN);
        PaginatedResult<Map.Entry<String, String>> page = service.listTags(key,
                Pagination.parseMaxResults(request, "MaxResults", PAGINATION_ERROR),
                Pagination.parseNextToken(request, "NextToken", PAGINATION_ERROR));
        List<Map<String, Object>> tags = new ArrayList<>();
        for (Map.Entry<String, String> tag : page.items()) {
            tags.add(Map.of("Key", tag.getKey(), "Value", tag.getValue()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Tags", tags);
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return out;
    }

    /** The tags of a request, as the name and the value of each. */
    private static Map<String, String> tagsOf(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode declared = request.get("Tags");
        if (declared != null && declared.isArray()) {
            for (JsonNode tag : declared) {
                String tagKey = tag.path("Key").asText();
                if (tagKey.isEmpty()) {
                    throw new AwsException("ValidationException", "each Tag requires Key", 400);
                }
                tags.put(tagKey, tag.path("Value").asText());
            }
        }
        return tags;
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * The key's modes of use, as the request states them.
     *
     * <p>The attributes are read as text, and a mode is a boolean, so the
     * modes are read from the request itself rather than from the flattened
     * attributes: a boolean read as text is a string, and the key would come
     * back with no modes at all.
     */
    private static Map<String, Boolean> modesOfUse(JsonNode attributes) {
        Map<String, Boolean> modes = new LinkedHashMap<>();
        JsonNode declared = attributes.get("KeyModesOfUse");
        if (declared != null && declared.isObject()) {
            declared.fields().forEachRemaining(entry -> {
                if (MODES_OF_USE.contains(entry.getKey())) {
                    modes.put(entry.getKey(), entry.getValue().asBoolean());
                }
            });
        }
        return modes;
    }

    /**
     * The modes of use a key answers with: every mode the reference gives a key, the ones the key does not
     * have as false, so a caller reading them finds a boolean for each of them and none of them is null.
     */
    private static Map<String, Boolean> answeredModes(PaymentCryptographyKey key) {
        Map<String, Boolean> modes = new LinkedHashMap<>();
        for (String mode : MODES_OF_USE) {
            modes.put(mode, Boolean.FALSE);
        }
        modes.putAll(key.getKeyModesOfUse());
        return modes;
    }

    /**
     * The key an operation names, in the member that operation names it in.
     *
     * <p>The key is named in a different member by each operation:
     * {@code KeyIdentifier} by the key operations, {@code KeyArn} by the alias ones, and
     * {@code ResourceArn} by the tagging ones. Only that member is read, so a request cannot
     * name one key for the permission check and another for the change: the IAM check reads
     * the same member this reads.
     */
    private PaymentCryptographyKey requiredKey(JsonNode request, String region, String member) {
        String identifier = request.path(member).asText();
        if (identifier.isEmpty()) {
            throw new AwsException("ValidationException", member + " is required", 400);
        }
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Key not found: " + identifier, 400);
        }
        return key;
    }

    private static String aliasName(JsonNode request) {
        return request.path("AliasName").asText();
    }

    private static Map<String, Object> keyObject(PaymentCryptographyKey key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", key.getKeyAlgorithm());
        attributes.put("KeyClass", key.getKeyClass());
        attributes.put("KeyUsage", key.getKeyUsage());
        attributes.put("KeyModesOfUse", answeredModes(key));
        out.put("KeyAttributes", attributes);
        out.put("KeyCheckValue", key.getKeyCheckValue());
        out.put("KeyCheckValueAlgorithm", key.getKeyCheckValueAlgorithm());
        out.put("KeyState", key.getKeyState());
        out.put("KeyOrigin", key.getKeyOrigin());
        out.put("Enabled", key.isEnabled());
        out.put("Exportable", key.isExportable());
        out.put("CreateTimestamp", key.getCreateTimestamp());
        out.put("UsageStartTimestamp", key.getUsageStartTimestamp());
        if (key.getDeletePendingTimestamp() > 0) {
            out.put("DeletePendingTimestamp", key.getDeletePendingTimestamp());
        }
        if (key.getDeleteTimestamp() > 0) {
            out.put("DeleteTimestamp", key.getDeleteTimestamp());
        }
        return out;
    }

    private static Map<String, Object> aliasObject(PaymentCryptographyAlias alias) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("AliasName", alias.getAliasName());
        if (alias.getKeyArn() != null) {
            out.put("KeyArn", alias.getKeyArn());
        }
        return out;
    }
}
