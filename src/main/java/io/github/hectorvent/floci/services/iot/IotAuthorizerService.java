package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iot.model.AuthorizerResult;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AWS IoT custom-authorizer registry and evaluation.
 *
 * <p>Stores authorizer configuration (control plane) and, given a connection's token,
 * invokes the authorizer Lambda via Floci's in-process {@link LambdaService}, then
 * evaluates the returned IAM-style policy with {@link IamPolicyEvaluator}. This is the
 * shared auth core used by both {@code TestInvokeAuthorizer} and the MQTT broker.
 */
@ApplicationScoped
public class IotAuthorizerService {

    private static final Logger LOG = Logger.getLogger(IotAuthorizerService.class);
    private static final String DEFAULT_KEY = "default";

    private final StorageBackend<String, IotAuthorizer> authorizerStore;
    private final StorageBackend<String, String> defaultStore;
    private final LambdaService lambdaService;
    private final IamPolicyEvaluator policyEvaluator;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotAuthorizerService(StorageFactory storageFactory,
                                LambdaService lambdaService,
                                IamPolicyEvaluator policyEvaluator,
                                RegionResolver regionResolver,
                                ObjectMapper objectMapper) {
        this.authorizerStore = storageFactory.create("iot", "iot-authorizers.json", new TypeReference<>() {});
        this.defaultStore = storageFactory.create("iot", "iot-default-authorizer.json", new TypeReference<>() {});
        this.lambdaService = lambdaService;
        this.policyEvaluator = policyEvaluator;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Connection-time inputs presented by a device (or by {@code TestInvokeAuthorizer}).
     */
    public record AuthInput(String token, String tokenSignature, String clientId,
                            String username, String password) {
    }

    // ──────────────────────────── Registry (control plane) ────────────────────────────

    public synchronized IotAuthorizer createAuthorizer(String authorizerName, String authorizerFunctionArn,
                                                       String tokenKeyName, Map<String, String> tokenSigningPublicKeys,
                                                       String status, boolean signingDisabled,
                                                       boolean enableCachingForHttp, String region) {
        if (authorizerName == null || authorizerName.isBlank()) {
            throw new AwsException("InvalidRequestException", "authorizerName is required.", 400);
        }
        if (authorizerFunctionArn == null || authorizerFunctionArn.isBlank()) {
            throw new AwsException("InvalidRequestException", "authorizerFunctionArn is required.", 400);
        }
        if (!signingDisabled && (tokenSigningPublicKeys == null || tokenSigningPublicKeys.isEmpty())) {
            throw new AwsException("InvalidRequestException",
                    "tokenSigningPublicKeys is required unless signingDisabled is true.", 400);
        }
        if (authorizerStore.get(authorizerName).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Authorizer " + authorizerName + " already exists.", 409);
        }
        Instant now = Instant.now();
        IotAuthorizer authorizer = new IotAuthorizer();
        authorizer.setAuthorizerName(authorizerName);
        authorizer.setAuthorizerArn(authorizerArn(region, authorizerName));
        authorizer.setAuthorizerFunctionArn(authorizerFunctionArn);
        authorizer.setTokenKeyName(tokenKeyName);
        if (tokenSigningPublicKeys != null) {
            authorizer.setTokenSigningPublicKeys(new HashMap<>(tokenSigningPublicKeys));
        }
        authorizer.setStatus(status == null || status.isBlank() ? "ACTIVE" : status);
        authorizer.setSigningDisabled(signingDisabled);
        authorizer.setEnableCachingForHttp(enableCachingForHttp);
        authorizer.setCreationDate(now);
        authorizer.setLastModifiedDate(now);
        authorizerStore.put(authorizerName, authorizer);
        LOG.infov("Created IoT authorizer {0}", authorizerName);
        return authorizer;
    }

    public IotAuthorizer getAuthorizer(String authorizerName) {
        return authorizerStore.get(authorizerName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Authorizer " + authorizerName + " does not exist.", 404));
    }

    public synchronized IotAuthorizer updateAuthorizer(String authorizerName, String authorizerFunctionArn,
                                                       String tokenKeyName, Map<String, String> tokenSigningPublicKeys,
                                                       String status) {
        IotAuthorizer authorizer = getAuthorizer(authorizerName);
        if (authorizerFunctionArn != null) {
            authorizer.setAuthorizerFunctionArn(authorizerFunctionArn);
        }
        if (tokenKeyName != null) {
            authorizer.setTokenKeyName(tokenKeyName);
        }
        if (tokenSigningPublicKeys != null) {
            authorizer.setTokenSigningPublicKeys(new HashMap<>(tokenSigningPublicKeys));
        }
        if (status != null && !status.isBlank()) {
            authorizer.setStatus(status);
        }
        authorizer.setLastModifiedDate(Instant.now());
        authorizerStore.put(authorizerName, authorizer);
        return authorizer;
    }

    public synchronized void deleteAuthorizer(String authorizerName) {
        IotAuthorizer authorizer = getAuthorizer(authorizerName);
        if (authorizerName.equals(getDefaultAuthorizerName().orElse(null))) {
            throw new AwsException("InvalidRequestException",
                    "Cannot delete the default authorizer; set a different default first.", 400);
        }
        authorizerStore.delete(authorizer.getAuthorizerName());
    }

    public IotPaging.Page<IotAuthorizer> listAuthorizers(String status, String marker,
                                                         Integer pageSize, boolean ascendingOrder) {
        List<IotAuthorizer> authorizers = new ArrayList<>(authorizerStore.scan(k -> true));
        if (status != null && !status.isBlank()) {
            authorizers.removeIf(a -> !status.equalsIgnoreCase(a.getStatus()));
        }
        authorizers.sort(Comparator.comparing(IotAuthorizer::getAuthorizerName));
        if (!ascendingOrder) {
            authorizers.sort(Comparator.comparing(IotAuthorizer::getAuthorizerName).reversed());
        }
        return IotPaging.paginate(authorizers, marker, pageSize);
    }

    public synchronized void setDefaultAuthorizer(String authorizerName) {
        getAuthorizer(authorizerName);
        defaultStore.put(DEFAULT_KEY, authorizerName);
    }

    public Optional<String> getDefaultAuthorizerName() {
        return defaultStore.get(DEFAULT_KEY);
    }

    /**
     * Resolves the authorizer to use for a connection: the named one if provided, otherwise
     * the account default. Throws when none is available or the resolved authorizer is inactive.
     */
    public IotAuthorizer resolveAuthorizer(String authorizerName) {
        String name = (authorizerName == null || authorizerName.isBlank())
                ? getDefaultAuthorizerName().orElse(null)
                : authorizerName;
        if (name == null) {
            throw new AwsException("InvalidRequestException",
                    "No authorizer specified and no default authorizer is set.", 400);
        }
        IotAuthorizer authorizer = getAuthorizer(name);
        if (!"ACTIVE".equalsIgnoreCase(authorizer.getStatus())) {
            throw new AwsException("InvalidRequestException",
                    "Authorizer " + name + " is not ACTIVE.", 400);
        }
        return authorizer;
    }

    // ──────────────────────────── Evaluation (shared with the broker) ────────────────────────────

    /**
     * Verifies the token signature, invokes the authorizer Lambda, and returns its parsed result.
     */
    public AuthorizerResult invokeAuthorizer(IotAuthorizer authorizer, String region, AuthInput input) {
        boolean signatureVerified = authorizer.isSigningDisabled()
                || verifyTokenSignature(authorizer, input.token(), input.tokenSignature());

        byte[] payload = buildEvent(input, signatureVerified);
        InvokeResult result = lambdaService.invoke(region, authorizer.getAuthorizerFunctionArn(),
                payload, InvocationType.RequestResponse);
        if (result.getFunctionError() != null) {
            throw new AwsException("InvalidResponseException",
                    "Authorizer function returned an error: " + result.getFunctionError(), 400);
        }
        return parseResponse(result.getPayload());
    }

    byte[] buildEvent(AuthInput input, boolean signatureVerified) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("token", input.token());
        event.put("signatureVerified", signatureVerified);
        ArrayNode protocols = event.putArray("protocols");
        protocols.add("mqtt");
        ObjectNode protocolData = event.putObject("protocolData");
        ObjectNode mqtt = protocolData.putObject("mqtt");
        if (input.username() != null) {
            mqtt.put("username", input.username());
        }
        if (input.password() != null) {
            mqtt.put("password", Base64.getEncoder()
                    .encodeToString(input.password().getBytes(StandardCharsets.UTF_8)));
        }
        if (input.clientId() != null) {
            mqtt.put("clientId", input.clientId());
        }
        try {
            return objectMapper.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "Failed to build authorizer event.", 400);
        }
    }

    AuthorizerResult parseResponse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new AwsException("InvalidResponseException", "Authorizer returned an empty response.", 400);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new AwsException("InvalidResponseException", "Authorizer returned invalid JSON.", 400);
        }
        boolean authenticated = node.path("isAuthenticated").asBoolean(false);
        String principalId = node.path("principalId").isMissingNode() ? null : node.path("principalId").asText();
        List<String> policyDocuments = new ArrayList<>();
        JsonNode docs = node.path("policyDocuments");
        if (docs.isArray()) {
            for (JsonNode doc : docs) {
                policyDocuments.add(doc.isTextual() ? doc.asText() : doc.toString());
            }
        }
        Integer disconnectAfter = node.has("disconnectAfterInSeconds")
                ? node.get("disconnectAfterInSeconds").asInt() : null;
        Integer refreshAfter = node.has("refreshAfterInSeconds")
                ? node.get("refreshAfterInSeconds").asInt() : null;
        return new AuthorizerResult(authenticated, principalId, policyDocuments, disconnectAfter, refreshAfter);
    }

    /**
     * Evaluates whether the authorizer's returned policy allows the given IoT action on a resource.
     */
    public boolean isAllowed(AuthorizerResult result, String action, String resource, Map<String, String> conditionCtx) {
        if (result.policyDocuments() == null || result.policyDocuments().isEmpty()) {
            return false;
        }
        return policyEvaluator.simulateCustomPolicy(result.policyDocuments(), action, resource, conditionCtx)
                == IamPolicyEvaluator.Decision.ALLOW;
    }

    boolean verifyTokenSignature(IotAuthorizer authorizer, String token, String signatureB64) {
        if (token == null || signatureB64 == null || signatureB64.isBlank()) {
            return false;
        }
        Map<String, String> keys = authorizer.getTokenSigningPublicKeys();
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signatureB64);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String pem : keys.values()) {
            if (verifyWithKey(pem, token, signatureBytes)) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyWithKey(String pem, String token, byte[] signatureBytes) {
        try {
            String base64 = pem.replaceAll("-----BEGIN (?:RSA )?PUBLIC KEY-----", "")
                    .replaceAll("-----END (?:RSA )?PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(token.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            LOG.debugv("Token signature verification failed: {0}", e.getMessage());
            return false;
        }
    }

    private String authorizerArn(String region, String authorizerName) {
        return regionResolver.buildArn("iot", region, "authorizer/" + authorizerName);
    }
}
