package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsGrant;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.*;

import static io.github.hectorvent.floci.core.common.ReservedTags.rejectUnknownReservedTags;

@ApplicationScoped
public class KmsJsonHandler {

    private final KmsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public KmsJsonHandler(KmsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    private static final Set<String> KEY_ID_OPERATIONS = Set.of(
            "GetPublicKey", "DescribeKey", "ReplicateKey", "CreateGrant", "ListGrants", "RevokeGrant", "Encrypt", "GenerateDataKey",
            "GenerateDataKeyWithoutPlaintext", "Sign", "Verify", "GenerateMac", "VerifyMac", "ScheduleKeyDeletion",
            "CancelKeyDeletion", "TagResource", "UntagResource", "ListResourceTags", "GetKeyPolicy", "PutKeyPolicy",
            "ListKeyPolicies", "UpdateKeyDescription", "GetKeyRotationStatus", "EnableKeyRotation",
            "DisableKeyRotation", "EnableKey", "DisableKey", "RotateKeyOnDemand", "GetParametersForImport",
            "ImportKeyMaterial", "DeleteImportedKeyMaterial");

    private static final Set<String> KEY_ONLY_OPERATIONS = Set.of(
            "ReplicateKey", "CreateGrant", "ListGrants", "RevokeGrant", "ScheduleKeyDeletion", "CancelKeyDeletion", "TagResource",
            "UntagResource", "ListResourceTags", "GetKeyPolicy", "PutKeyPolicy", "ListKeyPolicies",
            "UpdateKeyDescription", "GetKeyRotationStatus", "EnableKeyRotation", "DisableKeyRotation", "EnableKey",
            "DisableKey", "RotateKeyOnDemand", "GetParametersForImport", "ImportKeyMaterial",
            "DeleteImportedKeyMaterial");

    public Response handle(String action, JsonNode request, String region) {
        if (KEY_ID_OPERATIONS.contains(action)) {
            validateKeyIdMember(request, "KeyId");
            String keyId = request.path("KeyId").asText();
            if (KEY_ONLY_OPERATIONS.contains(action) && (keyId.startsWith("alias/") || keyId.contains(":alias/"))) {
                throw new AwsException("InvalidArnException", "Key Aliases are not supported for this operation.", 400);
            }
        } else if ("ReEncrypt".equals(action)) {
            validateKeyIdMember(request, "DestinationKeyId");
        } else if ("CreateAlias".equals(action) || "UpdateAlias".equals(action)) {
            validateKeyIdMember(request, "TargetKeyId");
        }
        return switch (action) {
            case "CreateKey" -> handleCreateKey(request, region);
            case "GenerateRandom" -> handleGenerateRandom(request, region);
            case "GetPublicKey" -> handleGetPublicKey(request, region);
            case "DescribeKey" -> handleDescribeKey(request, region);
            case "ReplicateKey" -> handleReplicateKey(request, region);
            case "ListKeys" -> handleListKeys(request, region);
            case "CreateGrant" -> handleCreateGrant(request, region);
            case "ListGrants" -> handleListGrants(request, region);
            case "ListRetirableGrants" -> handleListRetirableGrants(request, region);
            case "RevokeGrant" -> handleRevokeGrant(request, region);
            case "RetireGrant" -> handleRetireGrant(request, region);
            case "Encrypt" -> handleEncrypt(request, region);
            case "Decrypt" -> handleDecrypt(request, region);
            case "ReEncrypt" -> handleReEncrypt(request, region);
            case "GenerateDataKey" -> handleGenerateDataKey(request, region);
            case "GenerateDataKeyWithoutPlaintext" -> handleGenerateDataKeyWithoutPlaintext(request, region);
            case "Sign" -> handleSign(request, region);
            case "Verify" -> handleVerify(request, region);
            case "GenerateMac" -> handleGenerateMac(request, region);
            case "VerifyMac" -> handleVerifyMac(request, region);
            case "CreateAlias" -> handleCreateAlias(request, region);
            case "UpdateAlias" -> handleUpdateAlias(request, region);
            case "DeleteAlias" -> handleDeleteAlias(request, region);
            case "ListAliases" -> handleListAliases(request, region);
            case "ScheduleKeyDeletion" -> handleScheduleKeyDeletion(request, region);
            case "CancelKeyDeletion" -> handleCancelKeyDeletion(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListResourceTags" -> handleListResourceTags(request, region);
            case "GetKeyPolicy" -> handleGetKeyPolicy(request, region);
            case "PutKeyPolicy" -> handlePutKeyPolicy(request, region);
            case "ListKeyPolicies" -> handleListKeyPolicies(request, region);
            case "UpdateKeyDescription" -> handleUpdateKeyDescription(request, region);
            case "GetKeyRotationStatus" -> handleGetKeyRotationStatus(request, region);
            case "EnableKeyRotation" -> handleEnableKeyRotation(request, region);
            case "DisableKeyRotation" -> handleDisableKeyRotation(request, region);
            case "EnableKey" -> handleEnableKey(request, region);
            case "DisableKey" -> handleDisableKey(request, region);
            case "RotateKeyOnDemand" -> handleRotateKeyOnDemand(request, region);
            case "GetParametersForImport" -> handleGetParametersForImport(request, region);
            case "ImportKeyMaterial" -> handleImportKeyMaterial(request, region);
            case "DeleteImportedKeyMaterial" -> handleDeleteImportedKeyMaterial(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateKey(JsonNode request, String region) {
        String description = request.path("Description").asText(null);
        String keyUsage = request.path("KeyUsage").asText("ENCRYPT_DECRYPT");
        String keySpec = !request.path("KeySpec").isMissingNode()
                ? request.path("KeySpec").asText("SYMMETRIC_DEFAULT")
                : request.path("CustomerMasterKeySpec").asText("SYMMETRIC_DEFAULT");
        String policy = request.path("Policy").isMissingNode() ? null : request.path("Policy").asText(null);
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").forEach(t -> tags.put(t.path("TagKey").asText(), t.path("TagValue").asText()));
        rejectUnknownReservedTags(tags,"TagException");
        String origin = request.path("Origin").asText(null);
        boolean multiRegion = request.path("MultiRegion").asBoolean(false);
        KmsKey key = service.createKey(description, keyUsage, keySpec, policy, tags, origin, multiRegion, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("KeyMetadata", addKeyMetadata(key));
        return Response.ok(response).build();
    }

    private Response handleReplicateKey(JsonNode request, String region) {
        String description = request.path("Description").asText(null);
        String policy = request.path("Policy").isMissingNode() ? null : request.path("Policy").asText(null);
        String replicaRegion = requiredText(request, "ReplicaRegion");
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").forEach(t -> tags.put(t.path("TagKey").asText(), t.path("TagValue").asText()));
        rejectUnknownReservedTags(tags, "TagException");

        KmsKey replica = service.replicateKey(request.path("KeyId").asText(), description, policy,
                tags, replicaRegion, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ReplicaKeyMetadata", addKeyMetadata(replica));
        response.put("ReplicaPolicy", replica.getPolicy());
        ArrayNode replicaTags = response.putArray("ReplicaTags");
        addTags(replicaTags, replica);
        return Response.ok(response).build();
    }

    private Response handleGetPublicKey(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        KmsKey key = service.getPublicKey(keyId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", key.getArn());
        response.put("PublicKey", key.getPublicKeyEncoded());
        response.put("CustomerMasterKeySpec", key.getKeySpec().name());
        response.put("KeySpec", key.getKeySpec().name());
        response.put("KeyUsage", key.getKeyUsage().name());

        addAlgorithms(key, response);

        return Response.ok(response).build();
    }

    private Response handleDescribeKey(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        KmsKey key = service.describeKey(keyId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("KeyMetadata", addKeyMetadata(key));
        return Response.ok(response).build();
    }

    private Response handleListKeys(JsonNode request, String region) {
        List<KmsKey> keys = service.listKeys(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Keys");
        for (KmsKey k : keys) {
            ObjectNode entry = array.addObject();
            entry.put("KeyId", k.getKeyId());
            entry.put("KeyArn", k.getArn());
        }
        response.put("Truncated", false);
        return Response.ok(response).build();
    }

    private Response handleListGrants(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        String marker = request.path("Marker").isMissingNode() ? null : request.path("Marker").asText(null);
        Integer limit = request.path("Limit").isMissingNode() ? null : request.path("Limit").asInt();
        String grantId = request.path("GrantId").isMissingNode() ? null : request.path("GrantId").asText(null);
        String granteePrincipal = request.path("GranteePrincipal").isMissingNode() ? null : request.path("GranteePrincipal").asText(null);

        Map<String, Object> result = service.listGrants(keyId, region, marker, limit, grantId, granteePrincipal);
        return grantListResponse(result);
    }

    private Response handleListRetirableGrants(JsonNode request, String region) {
        String retiringPrincipal = request.path("RetiringPrincipal").asText(null);
        String marker = request.path("Marker").isMissingNode() ? null : request.path("Marker").asText(null);
        Integer limit = request.path("Limit").isMissingNode() ? null : request.path("Limit").asInt();

        Map<String, Object> result = service.listRetirableGrants(retiringPrincipal, region, marker, limit);

        return grantListResponse(result);
    }

    private Response grantListResponse(Map<String, Object> result) {
        ObjectNode response = objectMapper.createObjectNode();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) result.get("Grants");
        ArrayNode array = response.putArray("Grants");
        for (Map<String, Object> grant : grants) {
            ObjectNode entry = array.addObject();
            entry.put("GrantId", (String) grant.get("GrantId"));
            entry.put("KeyId", (String) grant.get("KeyId"));
            entry.put("GranteePrincipal", (String) grant.get("GranteePrincipal"));
            entry.put("CreationDate", ((Number) grant.get("CreationDate")).longValue());
            if (grant.get("Name") != null) {
                entry.put("Name", (String) grant.get("Name"));
            }
            if (grant.get("Constraints") != null) {
                entry.set("Constraints", objectMapper.valueToTree(grant.get("Constraints")));
            }
            ArrayNode operations = entry.putArray("Operations");
            @SuppressWarnings("unchecked")
            List<String> operationValues = (List<String>) grant.get("Operations");
            operationValues.forEach(operations::add);
            if (grant.get("RetiringPrincipal") != null) {
                entry.put("RetiringPrincipal", (String) grant.get("RetiringPrincipal"));
            }
        }
        response.put("Truncated", (boolean) result.get("Truncated"));
        if (Boolean.TRUE.equals(result.get("Truncated"))) {
            response.put("NextMarker", (String) result.get("NextMarker"));
        }
        return Response.ok(response).build();
    }

    private Response handleCreateGrant(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText(null);
        String granteePrincipal = request.path("GranteePrincipal").asText(null);
        List<String> operations = new java.util.ArrayList<>();
        request.path("Operations").forEach(operation -> operations.add(operation.asText()));
        String retiringPrincipal = request.path("RetiringPrincipal").isMissingNode()
                ? null : request.path("RetiringPrincipal").asText(null);
        String name = request.path("Name").isMissingNode() ? null : request.path("Name").asText(null);
        JsonNode constraintsNode = request.path("Constraints");
        if (!constraintsNode.isMissingNode() && !constraintsNode.isNull() && !constraintsNode.isObject()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'constraints' failed to satisfy constraint: "
                            + "Member must be a structure", 400);
        }
        Map<String, Object> constraints = constraintsNode.isObject()
                ? objectMapper.convertValue(constraintsNode, Map.class)
                : null;

        KmsGrant grant = service.createGrant(
                keyId, granteePrincipal, operations, retiringPrincipal, name, constraints, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GrantId", grant.getGrantId());
        response.put("GrantToken", grant.getGrantToken());
        return Response.ok(response).build();
    }

    private Response handleRevokeGrant(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText(null);
        String grantId = request.path("GrantId").asText(null);

        service.revokeGrant(keyId, grantId, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleRetireGrant(JsonNode request, String region) {
        String grantToken = request.path("GrantToken").isMissingNode()
                ? null : request.path("GrantToken").asText(null);
        String keyId = request.path("KeyId").isMissingNode()
                ? null : request.path("KeyId").asText(null);
        String grantId = request.path("GrantId").isMissingNode()
                ? null : request.path("GrantId").asText(null);

        service.retireGrant(grantToken, keyId, grantId, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // Blob fields arrive base64-encoded on the wire. A value that is not valid base64 is a
    // client deserialization error, not a server fault — without this the IllegalArgumentException
    // escapes to the dispatcher's generic catch and surfaces as 500 InternalFailure.
    private static byte[] decodeBlob(JsonNode request, String field) {
        try {
            return Base64.getDecoder().decode(request.path(field).asText());
        } catch (IllegalArgumentException e) {
            throw new AwsException("SerializationException", field + " is not valid base64.", 400);
        }
    }

    private Response handleEncrypt(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] plaintext = requireSizedBlob(request, "Plaintext", true, KmsService.MAX_PLAINTEXT_BYTES);
        Map<String, String> context = readEncryptionContext(request.path("EncryptionContext"));
        String algorithm = request.path("EncryptionAlgorithm").asText(null);

        KmsService.EncryptResult result = service.encrypt(keyId, plaintext, context, algorithm, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString(result.ciphertext()));
        response.put("KeyId", result.keyArn());
        response.put("EncryptionAlgorithm", result.encryptionAlgorithm());
        return Response.ok(response).build();
    }

    private Response handleDecrypt(JsonNode request, String region) {
        byte[] ciphertext = requireSizedBlob(request, "CiphertextBlob", false, KmsService.MAX_CIPHERTEXT_BYTES);
        Map<String, String> context = readEncryptionContext(request.path("EncryptionContext"));
        String requestKeyId = request.path("KeyId").asText(null);
        String algorithm = request.path("EncryptionAlgorithm").asText(null);

        KmsService.DecryptResult result = service.decryptAndResolveKey(ciphertext, context, region, requestKeyId, algorithm);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Plaintext", Base64.getEncoder().encodeToString(result.plaintext()));
        if (result.keyArn() != null) {
            response.put("KeyId", result.keyArn());
        }
        response.put("EncryptionAlgorithm", result.encryptionAlgorithm());
        return Response.ok(response).build();
    }

    private Response handleGenerateDataKey(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        String spec = readDataKeySpec(request);
        Integer numberOfBytes = readNumberOfBytes(request);
        Map<String, String> context = readEncryptionContext(request.path("EncryptionContext"));

        Map<String, Object> result = service.generateDataKey(keyId, spec, numberOfBytes, context, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Plaintext", Base64.getEncoder().encodeToString((byte[]) result.get("Plaintext")));
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString((byte[]) result.get("CiphertextBlob")));
        response.put("KeyId", (String) result.get("KeyId"));
        return Response.ok(response).build();
    }

    private Response handleGenerateDataKeyWithoutPlaintext(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        String spec = readDataKeySpec(request);
        Integer numberOfBytes = readNumberOfBytes(request);
        Map<String, String> context = readEncryptionContext(request.path("EncryptionContext"));

        Map<String, Object> result = service.generateDataKeyWithoutPlaintext(keyId, spec, numberOfBytes, context, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString((byte[]) result.get("CiphertextBlob")));
        response.put("KeyId", (String) result.get("KeyId"));
        return Response.ok(response).build();
    }

    private Response handleReEncrypt(JsonNode request, String region) {
        byte[] ciphertext = requireSizedBlob(request, "CiphertextBlob", false, KmsService.MAX_CIPHERTEXT_BYTES);
        String destKeyId = request.path("DestinationKeyId").asText();
        Map<String, String> sourceContext = readEncryptionContext(request.path("SourceEncryptionContext"));
        Map<String, String> destContext = readEncryptionContext(request.path("DestinationEncryptionContext"));
        String sourceAlgorithm = request.path("SourceEncryptionAlgorithm").asText(null);
        String destAlgorithm = request.path("DestinationEncryptionAlgorithm").asText(null);

        String sourceKeyId = request.path("SourceKeyId").asText(null);
        KmsService.DecryptResult source = service.decryptAndResolveKey(
                ciphertext, sourceContext, region, sourceKeyId, sourceAlgorithm);
        KmsService.EncryptResult destination = service.encrypt(
                destKeyId, source.plaintext(), destContext, destAlgorithm, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString(destination.ciphertext()));
        response.put("KeyId", destination.keyArn());
        response.put("SourceKeyId", source.keyArn());
        response.put("SourceEncryptionAlgorithm", source.encryptionAlgorithm());
        response.put("DestinationEncryptionAlgorithm", destination.encryptionAlgorithm());
        return Response.ok(response).build();
    }

    private static Map<String, String> readEncryptionContext(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        return result;
    }

    private Response handleSign(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] message = requireSizedBlob(request, "Message", true, KmsService.MAX_PLAINTEXT_BYTES);
        String algorithm = request.path("SigningAlgorithm").asText(null);
        KmsMessageType messageType = KmsMessageType.fromString(request.path("MessageType").asText("RAW"));

        byte[] signature = service.sign(keyId, message, algorithm, messageType, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        response.put("Signature", Base64.getEncoder().encodeToString(signature));
        response.put("SigningAlgorithm", algorithm);
        return Response.ok(response).build();
    }

    private Response handleVerify(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] signature = requireSizedBlob(request, "Signature", false, KmsService.MAX_CIPHERTEXT_BYTES);
        byte[] message = requireSizedBlob(request, "Message", true, KmsService.MAX_PLAINTEXT_BYTES);
        String algorithm = request.path("SigningAlgorithm").asText(null);
        KmsMessageType messageType = KmsMessageType.fromString(request.path("MessageType").asText("RAW"));

        service.verify(keyId, message, signature, algorithm, messageType, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        response.put("SignatureValid", true);
        response.put("SigningAlgorithm", algorithm);
        return Response.ok(response).build();
    }

    private Response handleGenerateMac(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] message = requireSizedBlob(request, "Message", true, KmsService.MAX_PLAINTEXT_BYTES);
        String algorithm = request.path("MacAlgorithm").asText(null);

        KmsService.GenerateMacResult result = service.generateMacAndResolveKey(keyId, message, algorithm, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", result.keyArn());
        response.put("Mac", Base64.getEncoder().encodeToString(result.mac()));
        response.put("MacAlgorithm", algorithm);
        return Response.ok(response).build();
    }

    private Response handleVerifyMac(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] mac = requireSizedBlob(request, "Mac", false, KmsService.MAX_CIPHERTEXT_BYTES);
        byte[] message = requireSizedBlob(request, "Message", true, KmsService.MAX_PLAINTEXT_BYTES);
        String algorithm = request.path("MacAlgorithm").asText(null);

        KmsService.VerifyMacResult result = service.verifyMacAndResolveKey(keyId, message, mac, algorithm, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", result.keyArn());
        response.put("MacAlgorithm", algorithm);
        response.put("MacValid", true);
        return Response.ok(response).build();
    }

    private Response handleCreateAlias(JsonNode request, String region) {
        service.createAlias(request.path("AliasName").asText(), request.path("TargetKeyId").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUpdateAlias(JsonNode request, String region) {
        service.updateAlias(request.path("AliasName").asText(), request.path("TargetKeyId").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteAlias(JsonNode request, String region) {
        service.deleteAlias(request.path("AliasName").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListAliases(JsonNode request, String region) {
        JsonNode keyIdNode = request.path("KeyId");
        String keyId = (keyIdNode.isMissingNode() || keyIdNode.isNull()) ? null : keyIdNode.asText();
        List<KmsAlias> aliases = service.listAliases(keyId, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Aliases");
        for (KmsAlias a : aliases) {
            ObjectNode entry = array.addObject();
            entry.put("AliasName", a.getAliasName());
            entry.put("AliasArn", a.getAliasArn());
            entry.put("TargetKeyId", a.getTargetKeyId());
            entry.put("CreationDate", a.getCreationDate());
        }
        response.put("Truncated", false);
        return Response.ok(response).build();
    }

    private Response handleScheduleKeyDeletion(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        int days = request.path("PendingWindowInDays").asInt(30);
        service.scheduleKeyDeletion(keyId, days, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        response.put("DeletionDate", service.describeKey(keyId, region).getDeletionDate());
        return Response.ok(response).build();
    }

    private Response handleCancelKeyDeletion(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        service.cancelKeyDeletion(keyId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").forEach(t -> tags.put(t.path("TagKey").asText(), t.path("TagValue").asText()));
        ReservedTags.rejectReservedTagsOnUpdate(tags);
        service.tagResource(keyId, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        java.util.List<String> keys = new java.util.ArrayList<>();
        request.path("TagKeys").forEach(k -> keys.add(k.asText()));
        service.untagResource(keyId, keys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListResourceTags(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        KmsKey key = service.describeKey(keyId, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Tags");
        key.getTags().forEach((k, v) -> {
            ObjectNode tag = array.addObject();
            tag.put("TagKey", k);
            tag.put("TagValue", v);
        });
        response.put("Truncated", false);
        return Response.ok(response).build();
    }

    private Response handleGetKeyPolicy(JsonNode request, String region) {
        Map<String, Object> result = service.getKeyPolicy(request.path("KeyId").asText(), region);
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handlePutKeyPolicy(JsonNode request, String region) {
        service.putKeyPolicy(
                request.path("KeyId").asText(),
                request.path("Policy").asText(),
                region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListKeyPolicies(JsonNode request, String region) {
        // Limit and Marker are accepted by simply not being read; see the service javadoc.
        Map<String, Object> result = service.listKeyPolicies(
                requiredText(request, "KeyId"),
                region);
        return Response.ok(objectMapper.valueToTree(result)).build();
    }

    private Response handleUpdateKeyDescription(JsonNode request, String region) {
        service.updateKeyDescription(
                request.path("KeyId").asText(),
                requiredText(request, "Description"),
                region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String requiredText(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
        return value.asText();
    }

    private Response handleGetKeyRotationStatus(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        boolean enabled = service.getKeyRotationStatus(keyId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyRotationEnabled", enabled);
        return Response.ok(response).build();
    }

    private Response handleEnableKeyRotation(JsonNode request, String region) {
        JsonNode rotationPeriod = request.path("RotationPeriodInDays");
        if (rotationPeriod.isNumber()) {
            validateRange("rotationPeriodInDays", rotationPeriod.asInt(), 90, 2560);
        }
        service.enableKeyRotation(request.path("KeyId").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDisableKeyRotation(JsonNode request, String region) {
        service.disableKeyRotation(request.path("KeyId").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleEnableKey(JsonNode request, String region) {
        service.enableKey(request.path("KeyId").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDisableKey(JsonNode request, String region) {
        service.disableKey(request.path("KeyId").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleRotateKeyOnDemand(JsonNode request, String region) {
        String keyId = service.rotateKeyOnDemand(request.path("KeyId").asText(), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", keyId);
        return Response.ok(response).build();
    }

    private Response handleGenerateRandom(JsonNode request, String region) {
        if (!request.path("Recipient").isMissingNode()) {
            throw new AwsException("ValidationException",
                    "Recipient is not supported for GenerateRandom without Nitro Enclave support.",
                    400);
        }
        if (!request.path("CustomKeyStoreId").isMissingNode()) {
            throw new AwsException("ValidationException",
                    "Custom key stores are not supported.",
                    400);
        }
        if (!request.path("NumberOfBytes").isNumber()) {
            throw new AwsException("ValidationException", "NumberOfBytes is required.", 400);
        }
        int numberOfBytes = request.path("NumberOfBytes").asInt();
        byte[] randomBytes = service.generateRandom(numberOfBytes);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Plaintext", Base64.getEncoder().encodeToString(randomBytes));
        return Response.ok(response).build();
    }

    private Response handleGetParametersForImport(JsonNode request, String region) {
        String keyId = requireKeyId(request);
        KmsService.ImportParameters parameters = service.getParametersForImport(
                keyId,
                request.path("WrappingAlgorithm").asText(null),
                request.path("WrappingKeySpec").asText(null),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", parameters.keyArn());
        response.put("PublicKey", parameters.publicKeyEncoded());
        response.put("ImportToken", parameters.importToken());
        response.put("ParametersValidTo", parameters.parametersValidTo());
        return Response.ok(response).build();
    }

    private Response handleImportKeyMaterial(JsonNode request, String region) {
        String keyId = requireKeyId(request);
        String importToken = request.path("ImportToken").asText(null);
        byte[] encryptedKeyMaterial = requireBlob(request, "EncryptedKeyMaterial");
        Long validTo = request.hasNonNull("ValidTo") ? request.path("ValidTo").asLong() : null;

        KmsKey key = service.importKeyMaterial(keyId, importToken, encryptedKeyMaterial,
                request.path("ExpirationModel").asText(null), validTo,
                request.path("ImportType").asText(null), region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", key.getArn());
        response.put("KeyMaterialId", key.getKeyMaterialId());
        return Response.ok(response).build();
    }

    private Response handleDeleteImportedKeyMaterial(JsonNode request, String region) {
        KmsKey key = service.deleteImportedKeyMaterial(requireKeyId(request), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", key.getArn());
        if (key.getKeyMaterialId() != null) {
            response.put("KeyMaterialId", key.getKeyMaterialId());
        }
        return Response.ok(response).build();
    }

    // The model check lists every KMS key spec, yet only the data key specs pass.
    private static String readDataKeySpec(JsonNode request) {
        String spec = request.path("KeySpec").asText(null);
        if (spec != null && !"AES_256".equals(spec) && !"AES_128".equals(spec)) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + spec
                    + "' at 'keySpec' failed to satisfy constraint: Member must satisfy enum value set: [RSA_2048, "
                    + "RSA_3072, RSA_4096, ECC_NIST_P256, ECC_NIST_P384, ECC_NIST_P521, ECC_SECG_P256K1, "
                    + "ECC_NIST_EDWARDS25519, SYMMETRIC_DEFAULT, HMAC_224, HMAC_256, HMAC_384, HMAC_512, SM2, "
                    + "ML_DSA_44, ML_DSA_65, ML_DSA_87]", 400);
        }
        return spec;
    }

    private static Integer readNumberOfBytes(JsonNode request) {
        JsonNode numberOfBytes = request.path("NumberOfBytes");
        if (!numberOfBytes.isNumber()) {
            return null;
        }
        validateRange("numberOfBytes", numberOfBytes.asInt(), 1, 1024);
        return numberOfBytes.asInt();
    }

    private static void validateRange(String member, int value, int min, int max) {
        if (value < min) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + value + "' at '"
                    + member + "' failed to satisfy constraint: Member must have value greater than or equal to " + min, 400);
        }
        if (max < value) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + value + "' at '"
                    + member + "' failed to satisfy constraint: Member must have value less than or equal to " + max, 400);
        }
    }

    private static void validateKeyIdMember(JsonNode request, String member) {
        String name = Character.toLowerCase(member.charAt(0)) + member.substring(1);
        JsonNode value = request.path(member);
        if (value.isMissingNode() || value.isNull()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at '" + name
                    + "' failed to satisfy constraint: Member must not be null", 400);
        }
        String keyId = value.asText();
        String pattern = "Value '" + keyId + "' at '" + name
                + "' failed to satisfy constraint: Member must satisfy regular expression pattern: ^\\p{ASCII}+$";
        if (keyId.isEmpty()) {
            throw new AwsException("ValidationException", "2 validation errors detected: Value '' at '" + name
                    + "' failed to satisfy constraint: Member must have length greater than or equal to 1; " + pattern, 400);
        }
        if (!keyId.chars().allMatch(c -> c < 128)) {
            throw new AwsException("ValidationException", "1 validation error detected: " + pattern, 400);
        }
    }

    // KMS hides the value of a sensitive member, even when it is null.
    private static byte[] requireSizedBlob(JsonNode request, String member, boolean sensitive, int max) {
        String name = Character.toLowerCase(member.charAt(0)) + member.substring(1);
        JsonNode value = request.path(member);
        if (value.isMissingNode() || value.isNull()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value " + (sensitive ? "" : "null ")
                    + "at '" + name + "' failed to satisfy constraint: Member must not be null", 400);
        }
        byte[] blob = decodeBlob(request, member);
        KmsService.validateBlobLength(name, blob, max);
        return blob;
    }

    private static String requireKeyId(JsonNode request) {
        String keyId = request.path("KeyId").asText(null);
        if (keyId == null || keyId.isBlank()) {
            throw new AwsException("ValidationException", "KeyId is required.", 400);
        }
        return keyId;
    }

    /**
     * A required blob member. {@link #decodeBlob} reads a missing member as an empty blob, which
     * would reach the operation as key material of the wrong length instead of the absent member
     * it actually is.
     */
    private static byte[] requireBlob(JsonNode request, String member) {
        if (!request.path(member).isTextual()) {
            throw new AwsException("ValidationException", member + " is required.", 400);
        }
        return decodeBlob(request, member);
    }

    private ObjectNode addKeyMetadata(KmsKey k) {
        ObjectNode keyMetadata = objectMapper.createObjectNode();
        keyMetadata.put("AWSAccountId", regionResolver.getAccountId());
        keyMetadata.put("KeyId", k.getKeyId());
        keyMetadata.put("Arn", k.getArn());
        keyMetadata.put("CreationDate", k.getCreationDate());
        keyMetadata.put("Enabled", k.isEnabled());
        keyMetadata.put("Description", k.getDescription() == null ? "" : k.getDescription());
        keyMetadata.put("KeyUsage", k.getKeyUsage().name());
        keyMetadata.put("KeyState", k.getKeyState());
        keyMetadata.put("Origin", k.getOrigin());
        if (k.getExpirationModel() != null) {
            keyMetadata.put("ExpirationModel", k.getExpirationModel());
        }
        if (k.getValidTo() > 0) {
            keyMetadata.put("ValidTo", k.getValidTo());
        }
        keyMetadata.put("KeyManager", "CUSTOMER");
        keyMetadata.put("CustomerMasterKeySpec", k.getKeySpec().name());
        keyMetadata.put("KeySpec", k.getKeySpec().name());
        keyMetadata.put("MultiRegion", k.isMultiRegion());
        if (k.isMultiRegion()) {
            keyMetadata.set("MultiRegionConfiguration", addMultiRegionConfiguration(k));
        }
        addAlgorithms(k, keyMetadata);
        if (k.getDeletionDate() > 0) {
            keyMetadata.put("DeletionDate", k.getDeletionDate());
        }
        return keyMetadata;
    }

    private ObjectNode addMultiRegionConfiguration(KmsKey key) {
        ObjectNode configuration = objectMapper.createObjectNode();
        configuration.put("MultiRegionKeyType", key.getMultiRegionKeyType());

        String primaryRegion = key.getMultiRegionPrimaryRegion();
        KmsKey primary = service.listKeys(primaryRegion).stream()
                .filter(candidate -> candidate.isMultiRegion())
                .filter(candidate -> key.getKeyId().equals(candidate.getKeyId()))
                .filter(candidate -> "PRIMARY".equals(candidate.getMultiRegionKeyType()))
                .findFirst()
                .orElseGet(() -> {
                    KmsKey fallback = new KmsKey();
                    fallback.setArn(regionResolver.buildArn("kms", primaryRegion, "key/" + key.getKeyId()));
                    return fallback;
                });
        ObjectNode primaryKey = configuration.putObject("PrimaryKey");
        primaryKey.put("Arn", primary.getArn());
        primaryKey.put("Region", primaryRegion);

        ArrayNode replicas = configuration.putArray("ReplicaKeys");
        for (KmsKey candidate : service.listAllMultiRegionKeys(key.getKeyId())) {
            if (!"PRIMARY".equals(candidate.getMultiRegionKeyType())) {
                ObjectNode replica = replicas.addObject();
                replica.put("Arn", candidate.getArn());
                replica.put("Region", regionFromArn(candidate.getArn()));
            }
        }
        return configuration;
    }

    private static String regionFromArn(String arn) {
        String[] parts = arn.split(":", 6);
        return parts.length > 3 ? parts[3] : "";
    }

    private static void addTags(ArrayNode array, KmsKey key) {
        key.getTags().forEach((keyName, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("TagKey", keyName);
            tag.put("TagValue", value);
        });
    }

    private ObjectNode errorResponse(String code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("__type", code);
        error.put("message", message);
        return error;
    }

    private void addAlgorithms(KmsKey key, ObjectNode response) {
        if (KmsKeyUsage.SIGN_VERIFY == key.getKeyUsage()) {
            ArrayNode signingAlgorithms = response.putArray("SigningAlgorithms");
            key.getKeySpec().getAlgorithm()
                    .stream()
                    .filter(algorithm -> algorithm.getKeyUsage() == KmsKeyUsage.SIGN_VERIFY)
                    .map(KmsKeySpec.Algorithm::getAlgName)
                    .filter(Objects::nonNull)
                    .forEach(signingAlgorithms::add);
        } else if (KmsKeyUsage.ENCRYPT_DECRYPT == key.getKeyUsage()) {
            if (key.getKeySpec().getKeyType() == KmsKeySpec.KeyType.RSA
                || key.getKeySpec().getKeyType() == KmsKeySpec.KeyType.SYMMETRIC) {
                ArrayNode encryptionAlgorithms = response.putArray("EncryptionAlgorithms");
                key.getKeySpec().getAlgorithm()
                        .stream()
                        .filter(algorithm -> algorithm.getKeyUsage() == KmsKeyUsage.ENCRYPT_DECRYPT)
                        .map(KmsKeySpec.Algorithm::getAlgName)
                        .filter(Objects::nonNull)
                        .forEach(encryptionAlgorithms::add);
            }
        } else if (KmsKeyUsage.GENERATE_VERIFY_MAC == key.getKeyUsage()
                && key.getKeySpec().getKeyType() == KmsKeySpec.KeyType.HMAC) {
                    response.putArray("MacAlgorithms").add(key.getKeySpec().getAlgorithm().getFirst().getAlgName());
            }
    }
}
