package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            case "GetPublicKey" -> handleGetPublicKey(request, region);
            case "DescribeKey" -> handleDescribeKey(request, region);
            case "ListKeys" -> handleListKeys(request, region);
            case "Encrypt" -> handleEncrypt(request, region);
            case "Decrypt" -> handleDecrypt(request, region);
            case "ReEncrypt" -> handleReEncrypt(request, region);
            case "GenerateDataKey" -> handleGenerateDataKey(request, region);
            case "GenerateDataKeyWithoutPlaintext" -> handleGenerateDataKeyWithoutPlaintext(request, region);
            case "Sign" -> handleSign(request, region);
            case "Verify" -> handleVerify(request, region);
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
            case "GetKeyRotationStatus" -> handleGetKeyRotationStatus(request, region);
            case "EnableKeyRotation" -> handleEnableKeyRotation(request, region);
            case "DisableKeyRotation" -> handleDisableKeyRotation(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateKey(JsonNode request, String region) {
        String description = request.path("Description").asText(null);
        String keyUsage = request.path("KeyUsage").asText("ENCRYPT_DECRYPT");
        String customerMasterKeySpec = !request.path("KeySpec").isMissingNode()
                ? request.path("KeySpec").asText("SYMMETRIC_DEFAULT")
                : request.path("CustomerMasterKeySpec").asText("SYMMETRIC_DEFAULT");
        String policy = request.path("Policy").isMissingNode() ? null : request.path("Policy").asText(null);
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").forEach(t -> tags.put(t.path("TagKey").asText(), t.path("TagValue").asText()));
        
        KmsKey key = service.createKey(description, keyUsage, customerMasterKeySpec, policy, tags, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("KeyMetadata", keyToNode(key));
        return Response.ok(response).build();
    }

    private Response handleGetPublicKey(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        KmsKey key = service.getPublicKey(keyId, region);
        
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", key.getArn());
        response.put("PublicKey", key.getPublicKeyEncoded());
        response.put("CustomerMasterKeySpec", key.getCustomerMasterKeySpec());
        response.put("KeyUsage", key.getKeyUsage());
        
        if ("SIGN_VERIFY".equals(key.getKeyUsage())) {
            ArrayNode algs = response.putArray("SigningAlgorithms");
            if (key.getCustomerMasterKeySpec().startsWith("RSA")) {
                algs.add("RSASSA_PSS_SHA_256");
                algs.add("RSASSA_PSS_SHA_384");
                algs.add("RSASSA_PSS_SHA_512");
                algs.add("RSASSA_PKCS1_V1_5_SHA_256");
                algs.add("RSASSA_PKCS1_V1_5_SHA_384");
                algs.add("RSASSA_PKCS1_V1_5_SHA_512");
            } else {
                algs.add("ECDSA_SHA_256");
                algs.add("ECDSA_SHA_384");
                algs.add("ECDSA_SHA_512");
            }
        } else {
            ArrayNode algs = response.putArray("EncryptionAlgorithms");
            if (key.getCustomerMasterKeySpec().startsWith("RSA")) {
                algs.add("RSAES_OAEP_SHA_1");
                algs.add("RSAES_OAEP_SHA_256");
            }
        }
        
        return Response.ok(response).build();
    }

    private Response handleDescribeKey(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        KmsKey key = service.describeKey(keyId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("KeyMetadata", keyToNode(key));
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
        String messageType = request.path("MessageType").asText("RAW");

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
        String messageType = request.path("MessageType").asText("RAW");

        boolean valid = service.verify(keyId, message, signature, algorithm, messageType, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        response.put("SignatureValid", valid);
        response.put("SigningAlgorithm", algorithm);
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

    private ObjectNode keyToNode(KmsKey k) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AWSAccountId", regionResolver.getAccountId());
        node.put("KeyId", k.getKeyId());
        node.put("Arn", k.getArn());
        node.put("CreationDate", k.getCreationDate());
        node.put("Enabled", k.isEnabled());
        node.put("Description", k.getDescription());
        node.put("KeyUsage", k.getKeyUsage());
        node.put("KeyState", k.getKeyState());
        node.put("Origin", "AWS_KMS");
        node.put("KeyManager", "CUSTOMER");
        node.put("CustomerMasterKeySpec", k.getCustomerMasterKeySpec());
        node.put("KeySpec", k.getCustomerMasterKeySpec());
        String macAlgo = KmsService.macAlgorithmFor(k.getCustomerMasterKeySpec());
        if (macAlgo != null) {
            node.putArray("MacAlgorithms").add(macAlgo);
        }
        if (k.getDeletionDate() > 0) {
            node.put("DeletionDate", k.getDeletionDate());
        }
        return node;
    }

    private ObjectNode errorResponse(String code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("__type", code);
        error.put("message", message);
        return error;
    }
}
