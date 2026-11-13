package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    /** The forms the model's export union holds. */
    private static final List<String> EXPORT_UNION = List.of("Tr31KeyBlock", "Tr34KeyBlock",
            "KeyCryptogram", "DiffieHellmanTr31KeyBlock", "As2805KeyCryptogram");

    /** The forms the model's import union holds. */
    private static final List<String> IMPORT_UNION = List.of("RootCertificatePublicKey",
            "TrustedCertificatePublicKey", "Tr31KeyBlock", "Tr34KeyBlock", "KeyCryptogram",
            "DiffieHellmanTr31KeyBlock", "As2805KeyCryptogram");

    public Response handle(String action, JsonNode request, String region) {
        // The switch names the operation and nothing else, so an operation this class serves
        // later cannot go without its model check: the check reads the name from the action.
        Supplier<Object> answer = switch (action) {
            case "CreateKey" -> () -> createKey(request, region);
            case "GetKey" -> () -> getKey(request, region);
            case "ListKeys" -> () -> listKeys(request, region);
            case "DeleteKey" -> () -> deleteKey(request, region);
            case "CreateAlias" -> () -> createAlias(request, region);
            case "GetAlias" -> () -> getAlias(request, region);
            case "ListAliases" -> () -> listAliases(request, region);
            case "UpdateAlias" -> () -> updateAlias(request, region);
            case "DeleteAlias" -> () -> deleteAlias(request, region);
            case "ExportKey" -> () -> exportKey(request, region);
            case "ImportKey" -> () -> importKey(request, region);
            case "GetPublicKeyCertificate" -> () -> getPublicKeyCertificate(request, region);
            case "GetParametersForImport" -> () -> getParametersForImport(request, region);
            case "GetParametersForExport" -> () -> getParametersForExport(request, region);
            case "TagResource" -> () -> tagResource(request, region);
            case "UntagResource" -> () -> untagResource(request, region);
            case "ListTagsForResource" -> () -> listTagsForResource(request, region);
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
        // The vendored model holds every operation it names, so the request is checked against
        // the model's own rules without this class listing the operations it serves. The check
        // runs after the switch, so an operation the model names and this service does not
        // serve is refused as the unknown operation it is, whatever its body says, and it
        // runs before the operation does, so a request that breaks the model's rules is
        // refused before the operation does any work.
        PaymentCryptographyRequestShapes.check(action, request);
        return Response.ok(answer.get()).build();
    }

    // ---- keys ---------------------------------------------------------------

    private Map<String, Object> createKey(JsonNode request, String region) {
        // The shape check settles the attributes and the members they must carry, so they are
        // there and they carry a usage and the modes of use, as the reference requires.
        JsonNode attributes = request.get("KeyAttributes");
        Map<String, Object> declared = new LinkedHashMap<>();
        attributes.fields().forEachRemaining(entry -> {
            // KeyModesOfUse is a nested structure, not a name: it is read below
            // as its own map, and stringifying it here would lose the modes.
            if (!"KeyModesOfUse".equals(entry.getKey())) {
                declared.put(entry.getKey(), entry.getValue().asText());
            }
        });
        // The check value follows the key's algorithm: an AES key's is a CMAC and a
        // TDES key's is the ANSI X9.24 one. A request that names another is refused
        // rather than answered with a value of another kind.
        if (request.hasNonNull("KeyCheckValueAlgorithm")) {
            String wanted = request.get("KeyCheckValueAlgorithm").asText();
            String algorithmName = declared.get("KeyAlgorithm") == null ? ""
                    : String.valueOf(declared.get("KeyAlgorithm"));
            // The kind the service will answer with, so a request is never refused for a
            // kind the service would have given.
            PaymentCryptographyKeyAlgorithm algorithm =
                    PaymentCryptographyKeyAlgorithm.from(algorithmName);
            String expected = algorithm == null ? null : algorithm.checkValueAlgorithm();
            if (expected != null && !expected.equals(wanted)) {
                throw new AwsException("ValidationException",
                        "KeyCheckValueAlgorithm must be " + expected + " for a " + algorithmName
                                + " key", 400);
            }
        }
        // A key may be created disabled, and it may be created with tags.
        boolean enabled = request.has("Enabled") ? request.path("Enabled").asBoolean(true) : true;
        if (request.hasNonNull("DeriveKeyUsage")) {
            // The usage a derivation key derives for sits beside the attributes, not in them.
            declared.put("DeriveKeyUsage", request.path("DeriveKeyUsage").asText());
        }
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
            keys.add(keySummary(key));
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

    /**
     * A key as a list answers it.
     *
     * <p>A list carries the key's summary and not the whole key: its identifier, its state,
     * its attributes, its check value and the two flags, which is what the model says a
     * summary holds.
     */
    private static Map<String, Object> keySummary(PaymentCryptographyKey key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        out.put("KeyState", key.getKeyState());
        out.put("KeyAttributes", keyAttributes(key));
        out.put("KeyCheckValue", key.getKeyCheckValue());
        out.put("Enabled", key.isEnabled());
        out.put("Exportable", key.isExportable());
        return out;
    }

    private static Map<String, Object> keyObject(PaymentCryptographyKey key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        Map<String, Object> attributes = keyAttributes(key);
        out.put("KeyAttributes", attributes);
        if (key.getDeriveKeyUsage() != null && !key.getDeriveKeyUsage().isEmpty()) {
            out.put("DeriveKeyUsage", key.getDeriveKeyUsage());
        }
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

    /** The attributes of a key, as the answers carry them. */
    private static Map<String, Object> keyAttributes(PaymentCryptographyKey key) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", key.getKeyAlgorithm());
        attributes.put("KeyClass", key.getKeyClass());
        attributes.put("KeyUsage", key.getKeyUsage());
        attributes.put("KeyModesOfUse", answeredModes(key));
        return attributes;
    }

    private static Map<String, Object> aliasObject(PaymentCryptographyAlias alias) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("AliasName", alias.getAliasName());
        if (alias.getKeyArn() != null) {
            out.put("KeyArn", alias.getKeyArn());
        }
        return out;
    }

    /**
     * The key as it leaves the service, in the form the request names.
     *
     * <p>The {@code KeyMaterial} union names one form, and the form says how the key is
     * protected: a TR-31 block under a key the service holds, an RSA-OAEP cryptogram under
     * the receiver's certificate, a TR-34 block the service signs, a block under a key the
     * two parties agreed, or an AS2805 cryptogram.
     */
    private Map<String, Object> exportKey(JsonNode request, String region) {
        // The required members are settled by the shape check before the handler runs, so
        // the identifier is read and not checked again.
        String keyIdentifier = request.path("ExportKeyIdentifier").asText();
        JsonNode material = request.path("KeyMaterial");
        String form = soleForm(material, EXPORT_UNION, PaymentCryptographyKeyExchange.EXPORT_FORMS,
                "exports");
        JsonNode attributes = request.path("ExportAttributes");
        return service.exportKey(region, keyIdentifier, form, members(material.path(form)),
                initialKeySerialNumber(attributes),
                attributes.path("KeyCheckValueAlgorithm").asText());
    }

    /** The certificate of an asymmetric key. */
    private Map<String, Object> getPublicKeyCertificate(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        return service.publicKeyCertificate(key);
    }

    /**
     * The parameters a counterparty needs to import a key into this service: the token the
     * import quotes back and the certificate the wrapping key is under.
     */
    private Map<String, Object> getParametersForImport(JsonNode request, String region) {
        return service.parametersForImport(request.path("KeyMaterialType").asText(),
                request.path("WrappingKeyAlgorithm").asText(),
                request.path("ReuseLastGeneratedToken").asBoolean(false));
    }

    /**
     * The parameters a counterparty needs to import a key this service exported: the token
     * the export quotes back and the certificate the signing key is under.
     */
    private Map<String, Object> getParametersForExport(JsonNode request, String region) {
        return service.parametersForExport(request.path("KeyMaterialType").asText(),
                request.path("SigningKeyAlgorithm").asText(),
                request.path("ReuseLastGeneratedToken").asBoolean(false));
    }

    /**
     * The key that comes into the service, in the form the request names.
     */
    private Map<String, Object> importKey(JsonNode request, String region) {
        JsonNode material = request.path("KeyMaterial");
        String form = soleForm(material, IMPORT_UNION, PaymentCryptographyKeyExchange.IMPORT_FORMS,
                "imports");
        PaymentCryptographyKey key = service.importKey(region, form, members(material.path(form)),
                request.path("KeyCheckValueAlgorithm").asText(),
                request.path("Enabled").asBoolean(true), tagsOf(request));
        return Map.of("Key", keyObject(key));
    }

    /**
     * The serial number of the initial key the request asks to be derived.
     *
     * <p>The export attributes name the kind of key that leaves: an initial key for the
     * DUKPT derivation is derived from the base derivation key and the serial number the
     * request gives, and it is that derived key which leaves.
     *
     * @param attributes the export attributes of the request
     * @return the serial number, or an empty string when the request names no initial key
     */
    private static String initialKeySerialNumber(JsonNode attributes) {
        JsonNode initial = attributes.path("ExportDukptInitialKey");
        if (initial.isMissingNode() || initial.isNull()) {
            return "";
        }
        // The serial number is required by the model, so the shape check has settled it.
        return initial.path("KeySerialNumber").asText();
    }

    /**
     * The one form of the key material union the request names.
     *
     * <p>The union holds a member per form, and a request names exactly one of them. Several
     * at once is not the same as none: the request says which one it meant, and it cannot be
     * more than one.
     */
    private static String soleForm(JsonNode material, List<String> union, List<String> served,
                                    String what) {
        List<String> named = new ArrayList<>();
        for (String form : union) {
            if (material.has(form) && !material.path(form).isNull()) {
                named.add(form);
            }
        }
        if (named.size() != 1) {
            throw new AwsException("ValidationException",
                    "KeyMaterial must specify exactly one variant (" + String.join(", ", union) + ")",
                    400);
        }
        String form = named.get(0);
        if (!served.contains(form)) {
            throw new AwsException("ValidationException",
                    "KeyMaterial." + form + " is not a form the service " + what + " ("
                            + String.join(", ", served) + ")", 400);
        }
        return form;
    }

    /** The members of a form, as the service reads them. */
    private static Map<String, Object> members(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.properties().forEach(entry -> out.put(entry.getKey(), value(entry.getValue())));
        return out;
    }

    /** One member of a form, as the service reads it. */
    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return members(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(child -> values.add(value(child)));
            return values;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asText();
        }
        return node.asText();
    }
}
