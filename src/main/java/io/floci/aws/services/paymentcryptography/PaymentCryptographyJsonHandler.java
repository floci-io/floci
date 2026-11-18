package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.iam.PolicyDocument;
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

    /**
     * The reader of a resource policy, which arrives as a JSON document in a string.
     *
     * <p>The whole string is what the key keeps, so a string that carries anything after
     * the document is not one policy and is refused.
     */
    private static final ObjectMapper POLICY_READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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
            case "RestoreKey" -> () -> restoreKey(request, region);
            case "StartKeyUsage" -> () -> startKeyUsage(request, region);
            case "StopKeyUsage" -> () -> stopKeyUsage(request, region);
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
            case "AddKeyReplicationRegions" -> () -> addKeyReplicationRegions(request, region);
            case "RemoveKeyReplicationRegions" -> () -> removeKeyReplicationRegions(request, region);
            case "GetDefaultKeyReplicationRegions" -> () -> getDefaultKeyReplicationRegions(request, region);
            case "EnableDefaultKeyReplicationRegions" -> () -> enableDefaultKeyReplicationRegions(request, region);
            case "DisableDefaultKeyReplicationRegions" -> () -> disableDefaultKeyReplicationRegions(request, region);
            case "TagResource" -> () -> tagResource(request, region);
            case "UntagResource" -> () -> untagResource(request, region);
            case "ListTagsForResource" -> () -> listTagsForResource(request, region);
            case "PutResourcePolicy" -> () -> putResourcePolicy(request, region);
            case "GetResourcePolicy" -> () -> getResourcePolicy(request, region);
            case "DeleteResourcePolicy" -> () -> deleteResourcePolicy(request, region);
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
                request.path("Exportable").asBoolean(false), enabled, tagsOf(request),
                stringsOf(request, "ReplicationRegions"));
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
     * Schedules a key for deletion and answers with the key, as the reference does.
     *
     * <p>The waiting period is the one the request names, or the reference's default of
     * seven days. The model settles the range, so a period outside it is refused before
     * the key is touched.
     */
    private Map<String, Object> deleteKey(JsonNode request, String region) {
        int days = request.hasNonNull("DeleteKeyInDays")
                ? request.path("DeleteKeyInDays").asInt() : 7;
        PaymentCryptographyKey key = service.deleteKey(
                requiredKey(request, region, KEY_IDENTIFIER), days);
        return Map.of("Key", keyObject(key));
    }

    /** Cancels the scheduled deletion of a key. */
    private Map<String, Object> restoreKey(JsonNode request, String region) {
        PaymentCryptographyKey key = service.restoreKey(
                requiredKey(request, region, KEY_IDENTIFIER));
        return Map.of("Key", keyObject(key));
    }

    /** Starts the use of a key. */
    private Map<String, Object> startKeyUsage(JsonNode request, String region) {
        PaymentCryptographyKey key = service.startKeyUsage(
                requiredKey(request, region, KEY_IDENTIFIER));
        return Map.of("Key", keyObject(key));
    }

    /** Stops the use of a key. */
    private Map<String, Object> stopKeyUsage(JsonNode request, String region) {
        PaymentCryptographyKey key = service.stopKeyUsage(
                requiredKey(request, region, KEY_IDENTIFIER));
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

    // ---- resource policies ----------------------------------------------------

    /**
     * Attaches or replaces the resource-based policy of a key.
     *
     * <p>The policy is a JSON document in a string, and a document that does not parse is
     * refused before it is attached. A policy that hands the key to everyone and carries no
     * condition is refused with the reference's own error, so the key is never left open by
     * a policy no one bounds.
     */
    private Map<String, Object> putResourcePolicy(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, RESOURCE_ARN);
        String policy = request.path("Policy").asText();
        JsonNode document = readPolicy(policy);
        List<JsonNode> statements = PolicyDocument.statementsOf(document);
        if (grantsPublicAccess(statements)) {
            throw new AwsException("PublicPolicyException",
                    "The resource policy grants public access to the key", 400);
        }
        if (!namesTheKey(grantsOf(statements), key.getArn())) {
            throw new AwsException("ValidationException",
                    "The resource policy names a resource that is not the key it is attached to", 400);
        }
        return policyAnswer(service.putResourcePolicy(key, policy));
    }

    /**
     * The policy document a request carries.
     *
     * <p>The policy is a JSON document in a string, and a document that does not parse, or
     * that carries anything after the document, is refused before it is attached: the whole
     * string is what the key keeps, so a string that is not one document is not a policy.
     *
     * @param policy the policy as it was given
     * @return the policy document
     */
    private static JsonNode readPolicy(String policy) {
        JsonNode document;
        try {
            document = POLICY_READER.readTree(policy);
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException",
                    "Policy is not a valid JSON policy document: " + e.getOriginalMessage(), 400);
        }
        if (document == null || !document.isObject()) {
            throw new AwsException("ValidationException",
                    "Policy is not a valid JSON policy document", 400);
        }
        return document;
    }

    /** The resource-based policy of a key, which a key without one does not have. */
    private Map<String, Object> getResourcePolicy(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, RESOURCE_ARN);
        return policyAnswer(service.resourcePolicy(key));
    }

    /** Removes the resource-based policy of a key. */
    private Map<String, Object> deleteResourcePolicy(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, RESOURCE_ARN);
        service.deleteResourcePolicy(key);
        return Map.of();
    }

    private static Map<String, Object> policyAnswer(PaymentCryptographyService.ResourcePolicy policy) {
        return Map.of("ResourceArn", policy.arn(), "Policy", policy.policy());
    }

    /**
     * Whether every statement of a policy names the key the policy is attached to.
     *
     * <p>The reference says the resource of a policy must be every resource or the ARN of
     * the key the policy is on, so a policy that names another key is refused.
     *
     * @param statements the statements of the policy
     * @param arn the ARN of the key the policy is attached to
     * @return whether the policy names the key
     */
    private static boolean namesTheKey(List<JsonNode> statements, String arn) {
        for (JsonNode statement : statements) {
            if (!statementNamesTheKey(statement, arn)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The statements of a policy that grant something.
     *
     * <p>The grammar a resource policy follows names an effect, a principal, an action and
     * a resource in every statement, and a document that carries no statement, or a
     * statement that names none of them, grants nothing, so it is refused rather than
     * stored on the key.
     *
     * @param statements the statements of the policy
     * @return the statements
     */
    private static List<JsonNode> grantsOf(List<JsonNode> statements) {
        if (statements.isEmpty()) {
            throw new AwsException("ValidationException",
                    "The resource policy carries no statement", 400);
        }
        for (JsonNode statement : statements) {
            if (!carriesTheElementsOfAGrant(statement)) {
                throw new AwsException("ValidationException",
                        "The resource policy carries a statement that names no effect, principal, "
                                + "action or resource", 400);
            }
        }
        return statements;
    }

    /** Whether a statement names the elements a grant is made of. */
    private static boolean carriesTheElementsOfAGrant(JsonNode statement) {
        String effect = statement.path("Effect").asText();
        return ("Allow".equalsIgnoreCase(effect) || "Deny".equalsIgnoreCase(effect))
                && (statement.hasNonNull("Principal") || statement.hasNonNull("NotPrincipal"))
                && (statement.hasNonNull("Action") || statement.hasNonNull("NotAction"))
                && statement.hasNonNull("Resource");
    }

    /** Whether a statement names the key, either alone or in a list of resources. */
    private static boolean statementNamesTheKey(JsonNode statement, String arn) {
        JsonNode resource = statement.path("Resource");
        if (resource.isTextual()) {
            return "*".equals(resource.asText()) || arn.equals(resource.asText());
        }
        if (resource.isArray()) {
            for (JsonNode entry : resource) {
                if (!"*".equals(entry.asText()) && !arn.equals(entry.asText())) {
                    return false;
                }
            }
            return resource.size() > 0;
        }
        return false;
    }

    /**
     * Whether a policy hands the key to every principal without a condition.
     *
     * <p>The reference refuses such a policy, so the service does too: an Allow statement
     * whose principal is everyone, and that carries no condition, is public access.
     *
     * @param statements the statements of the policy
     * @return whether the policy grants public access
     */
    private static boolean grantsPublicAccess(List<JsonNode> statements) {
        for (JsonNode statement : statements) {
            if (statementGrantsPublicAccess(statement)) {
                return true;
            }
        }
        return false;
    }

    private static boolean statementGrantsPublicAccess(JsonNode statement) {
        if (!"Allow".equalsIgnoreCase(statement.path("Effect").asText())) {
            return false;
        }
        if (!PolicyDocument.grantsToEveryone(statement) || statement.has("Condition")) {
            return false;
        }
        return true;
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
        if (key.getMultiRegionKeyType() != null) {
            out.put("MultiRegionKeyType", key.getMultiRegionKeyType());
            out.put("PrimaryRegion", key.getPrimaryRegion());
        }
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
        if (key.getUsageStopTimestamp() > 0) {
            out.put("UsageStopTimestamp", key.getUsageStopTimestamp());
        }
        if (key.getDeletePendingTimestamp() > 0) {
            out.put("DeletePendingTimestamp", key.getDeletePendingTimestamp());
        }
        if (key.getDeleteTimestamp() > 0) {
            out.put("DeleteTimestamp", key.getDeleteTimestamp());
        }
        if (key.getMultiRegionKeyType() != null) {
            out.put("MultiRegionKeyType", key.getMultiRegionKeyType());
            out.put("PrimaryRegion", key.getPrimaryRegion());
            if ("PRIMARY".equals(key.getMultiRegionKeyType())) {
                // The replication status and the defaults flag belong to the primary: a
                // replica answers with the kind of the key and its primary Region alone,
                // as the reference's own answer shows.
                Map<String, Object> status = new LinkedHashMap<>();
                for (String target : key.getReplicationRegions()) {
                    // The model's replication states are IN_PROGRESS, DELETE_IN_PROGRESS, FAILED
                    // and SYNCHRONIZED. A replica that is there is a key that is in sync.
                    status.put(target, Map.of("Status", "SYNCHRONIZED"));
                }
                out.put("ReplicationStatus", status);
                out.put("UsingDefaultReplicationRegions", key.isUsingDefaultReplicationRegions());
            }
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
                request.path("Enabled").asBoolean(true), tagsOf(request),
                stringsOf(request, "ReplicationRegions"));
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

    /**
     * The key with the regions the request names added to its replication.
     */
    private Map<String, Object> addKeyReplicationRegions(JsonNode request, String region) {
        PaymentCryptographyKey key = service.addKeyReplicationRegions(region,
                request.path("KeyIdentifier").asText(), stringsOf(request, "ReplicationRegions"));
        return Map.of("Key", keyObject(key));
    }

    /**
     * The key with the regions the request names taken away from its replication.
     */
    private Map<String, Object> removeKeyReplicationRegions(JsonNode request, String region) {
        PaymentCryptographyKey key = service.removeKeyReplicationRegions(region,
                request.path("KeyIdentifier").asText(), stringsOf(request, "ReplicationRegions"));
        return Map.of("Key", keyObject(key));
    }

    /**
     * The regions the account replicates a new key to by default.
     *
     * <p>The settings are the ones of the Region the request is in, so each Region has its
     * own.
     */
    private Map<String, Object> getDefaultKeyReplicationRegions(JsonNode request, String region) {
        return Map.of("EnabledReplicationRegions", service.defaultReplicationRegions(region));
    }

    /** Turns the default replication of the account on for the regions the request names. */
    private Map<String, Object> enableDefaultKeyReplicationRegions(JsonNode request, String region) {
        return Map.of("EnabledReplicationRegions",
                service.enableDefaultReplicationRegions(region,
                        stringsOf(request, "ReplicationRegions")));
    }

    /** Turns the default replication of the account off for the regions the request names. */
    private Map<String, Object> disableDefaultKeyReplicationRegions(JsonNode request, String region) {
        return Map.of("EnabledReplicationRegions",
                service.disableDefaultReplicationRegions(region,
                        stringsOf(request, "ReplicationRegions")));
    }

    /**
     * The strings of a request, such as the replication regions.
     *
     * <p>The shape check settles that the member is a list of names, so a request that names a
     * region that is not one is refused before this reads it.
     *
     * @param request the request the member is in
     * @param member the member to read
     */
    private static List<String> stringsOf(JsonNode request, String member) {
        JsonNode declared = request.get(member);
        if (declared == null || declared.isNull()) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : declared) {
            values.add(value.asText());
        }
        return values;
    }
}
