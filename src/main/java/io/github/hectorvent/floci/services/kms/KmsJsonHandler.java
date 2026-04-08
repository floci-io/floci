package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
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

    @Inject
    public KmsJsonHandler(KmsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateKey" -> handleCreateKey(request, region);
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
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateKey(JsonNode request, String region) {
        String description = request.path("Description").asText(null);
        String policy = request.path("Policy").isMissingNode() ? null : request.path("Policy").asText(null);
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").forEach(t -> tags.put(t.path("TagKey").asText(), t.path("TagValue").asText()));
        KmsKey key = service.createKey(description, policy, tags, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("KeyMetadata", keyToNode(key));
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
        byte[] ciphertext = service.encrypt(keyId, plaintext, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString(ciphertext));
        response.put("KeyId", service.describeKey(keyId, region).getArn());
        return Response.ok(response).build();
    }

    private Response handleDecrypt(JsonNode request, String region) {
        byte[] ciphertext = Base64.getDecoder().decode(request.path("CiphertextBlob").asText());
        byte[] plaintext = service.decrypt(ciphertext, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Plaintext", Base64.getEncoder().encodeToString(plaintext));
        // In our mock, we don't strictly track which key was used for decryption from blob alone,
        // but we could extract it from our mock format "kms:keyId:..."
        String data = new String(ciphertext);
        if (data.startsWith("kms:")) {
            String keyId = data.split(":")[1];
            response.put("KeyId", service.describeKey(keyId, region).getArn());
        }
        return Response.ok(response).build();
    }

    private Response handleGenerateDataKey(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        String spec = request.path("KeySpec").asText(null);
        int numberOfBytes = request.path("NumberOfBytes").asInt(0);

        Map<String, Object> result = service.generateDataKey(keyId, spec, numberOfBytes, region);

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

        Map<String, Object> result = service.generateDataKey(keyId, spec, numberOfBytes, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString((byte[]) result.get("CiphertextBlob")));
        response.put("KeyId", (String) result.get("KeyId"));
        return Response.ok(response).build();
    }

    private Response handleReEncrypt(JsonNode request, String region) {
        byte[] ciphertext = Base64.getDecoder().decode(request.path("CiphertextBlob").asText());
        String destKeyId = request.path("DestinationKeyId").asText();

        byte[] plaintext = service.decrypt(ciphertext, region);
        byte[] newCiphertext = service.encrypt(destKeyId, plaintext, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("CiphertextBlob", Base64.getEncoder().encodeToString(newCiphertext));
        response.put("KeyId", service.describeKey(destKeyId, region).getArn());
        response.put("SourceKeyId", service.decryptToKeyArn(ciphertext, region));
        return Response.ok(response).build();
    }

    private Response handleSign(JsonNode request, String region) {
        String keyId = request.path("KeyId").asText();
        byte[] message = Base64.getDecoder().decode(request.path("Message").asText());
        String algorithm = request.path("SigningAlgorithm").asText("RSASSA_PSS_SHA_256");

        byte[] signature = service.sign(keyId, message, algorithm, region);

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

        boolean valid = service.verify(keyId, message, signature, algorithm, region);

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

    private ObjectNode keyToNode(KmsKey k) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AWSAccountId", "000000000000");
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
