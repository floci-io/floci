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

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateKey" -> handleCreateKey(request, region);
            case "GenerateRandom" -> handleGenerateRandom(request, region);
            case "GetPublicKey" -> handleGetPublicKey(request, region);
            case "DescribeKey" -> handleDescribeKey(request, region);
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
            case "DeleteAlias" -> handleDeleteAlias(request, region);
            case "ListAliases" -> handleListAliases(request, region);
            case "ScheduleKeyDeletion" -> handleScheduleKeyDeletion(request, region);
            case "CancelKeyDeletion" -> handleCancelKeyDeletion(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListResourceTags" -> handleListResourceTags(request, region);
            case "GetKeyPolicy" -> handleGetKeyPolicy(request, region);
            case "PutKeyPolicy" -> handlePutKeyPolicy(request, region);
            case "UpdateKeyDescription" -> handleUpdateKeyDescription(request, region);
            case "GetKeyRotationStatus" -> handleGetKeyRotationStatus(request, region);
            case "EnableKeyRotation" -> handleEnableKeyRotation(request, region);
            case "DisableKeyRotation" -> handleDisableKeyRotation(request, region);
            case "EnableKey" -> handleEnableKey(request, region);
            case "DisableKey" -> handleDisableKey(request, region);
            case "RotateKeyOnDemand" -> handleRotateKeyOnDemand(request, region);
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
        KmsKey key = service.createKey(description, keyUsage, keySpec, policy, tags, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("KeyMetadata", addKeyMetadata(key));
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

    private Response handleListRetirableGrants(JsonNode request, String region) {
        String retiringPrincipal = request.path("RetiringPrincipal").asText(null);
        String marker = request.path("Marker").isMissingNode() ? null : request.path("Marker").asText(null);
        Integer limit = request.path("Limit").isMissingNode() ? null : request.path("Limit").asInt();

        Map<String, Object> result = service.listRetirableGrants(retiringPrincipal, region, marker, limit);

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

        KmsGrant grant = service.createGrant(keyId, granteePrincipal, operations, retiringPrincipal, region);
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

    private Response handleEncrypt(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] plaintext = Base64.getDecoder().decode(request.path("Plaintext").asText());
        Map<String, String> context = readEncryptionContext(request.path("EncryptionContext"));
        byte[] ciphertext = service.encrypt(keyId, plaintext, context, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString(ciphertext));
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        return Response.ok(response).build();
    }

    private Response handleDecrypt(JsonNode request, String region) {
        byte[] ciphertext = Base64.getDecoder().decode(request.path("CiphertextBlob").asText());
        Map<String, String> context = readEncryptionContext(request.path("EncryptionContext"));
        KmsService.DecryptResult result = service.decryptAndResolveKey(ciphertext, context, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Plaintext", Base64.getEncoder().encodeToString(result.plaintext()));
        if (result.keyArn() != null) {
            response.put("KeyId", result.keyArn());
        }
        return Response.ok(response).build();
    }

    private Response handleGenerateDataKey(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        String spec = request.path("KeySpec").asText(null);
        int numberOfBytes = request.path("NumberOfBytes").asInt(0);
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
        String spec = request.path("KeySpec").asText(null);
        int numberOfBytes = request.path("NumberOfBytes").asInt(0);
        Map<String, String> context = readEncryptionContext(request.path("EncryptionContext"));

        Map<String, Object> result = service.generateDataKey(keyId, spec, numberOfBytes, context, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString((byte[]) result.get("CiphertextBlob")));
        response.put("KeyId", (String) result.get("KeyId"));
        return Response.ok(response).build();
    }

    private Response handleReEncrypt(JsonNode request, String region) {
        byte[] ciphertext = Base64.getDecoder().decode(request.path("CiphertextBlob").asText());
        String destKeyId = request.path("DestinationKeyId").asText();
        Map<String, String> sourceContext = readEncryptionContext(request.path("SourceEncryptionContext"));
        Map<String, String> destContext = readEncryptionContext(request.path("DestinationEncryptionContext"));

        KmsService.DecryptResult source = service.decryptAndResolveKey(ciphertext, sourceContext, region);
        byte[] newCiphertext = service.encrypt(destKeyId, source.plaintext(), destContext, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString(newCiphertext));
        response.put("KeyId", service.describeKey(destKeyId, region).getArn());
        response.put("SourceKeyId", source.keyArn());
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
        byte[] message = Base64.getDecoder().decode(request.path("Message").asText());
        String algorithm = request.path("SigningAlgorithm").asText("RSASSA_PSS_SHA_256");
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
        byte[] message = Base64.getDecoder().decode(request.path("Message").asText());
        byte[] signature = Base64.getDecoder().decode(request.path("Signature").asText());
        String algorithm = request.path("SigningAlgorithm").asText("RSASSA_PSS_SHA_256");
        KmsMessageType messageType = KmsMessageType.fromString(request.path("MessageType").asText("RAW"));

        boolean valid = service.verify(keyId, message, signature, algorithm, messageType, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        response.put("SignatureValid", valid);
        response.put("SigningAlgorithm", algorithm);
        return Response.ok(response).build();
    }

    private Response handleGenerateMac(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] message = Base64.getDecoder().decode(request.path("Message").asText());
        String algorithm = request.path("MacAlgorithm").asText();

        KmsService.GenerateMacResult result = service.generateMacAndResolveKey(keyId, message, algorithm, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", result.keyArn());
        response.put("Mac", Base64.getEncoder().encodeToString(result.mac()));
        response.put("MacAlgorithm", algorithm);
        return Response.ok(response).build();
    }

    private Response handleVerifyMac(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] message = Base64.getDecoder().decode(request.path("Message").asText());
        byte[] mac = Base64.getDecoder().decode(request.path("Mac").asText());
        String algorithm = request.path("MacAlgorithm").asText();

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

    private Response handleDeleteAlias(JsonNode request, String region) {
        service.deleteAlias(request.path("AliasName").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListAliases(JsonNode request, String region) {
        List<KmsAlias> aliases = service.listAliases(region);
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
        int numberOfBytes = request.path("NumberOfBytes").asInt(0);
        byte[] randomBytes = service.generateRandom(numberOfBytes);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Plaintext", Base64.getEncoder().encodeToString(randomBytes));
        return Response.ok(response).build();
    }

    private ObjectNode addKeyMetadata(KmsKey k) {
        ObjectNode keyMetadata = objectMapper.createObjectNode();
        keyMetadata.put("AWSAccountId", regionResolver.getAccountId());
        keyMetadata.put("KeyId", k.getKeyId());
        keyMetadata.put("Arn", k.getArn());
        keyMetadata.put("CreationDate", k.getCreationDate());
        keyMetadata.put("Enabled", k.isEnabled());
        keyMetadata.put("Description", k.getDescription());
        keyMetadata.put("KeyUsage", k.getKeyUsage().name());
        keyMetadata.put("KeyState", k.getKeyState());
        keyMetadata.put("Origin", "AWS_KMS");
        keyMetadata.put("KeyManager", "CUSTOMER");
        keyMetadata.put("CustomerMasterKeySpec", k.getKeySpec().name());
        keyMetadata.put("KeySpec", k.getKeySpec().name());
        addAlgorithms(k, keyMetadata);
        if (k.getDeletionDate() > 0) {
            keyMetadata.put("DeletionDate", k.getDeletionDate());
        }
        return keyMetadata;
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
