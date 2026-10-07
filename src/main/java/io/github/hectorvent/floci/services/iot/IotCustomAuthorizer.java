package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS IoT custom authentication: the event AWS IoT sends an authorizer's function, the invocation,
 * and the checks AWS makes on the function's answer, in AWS's order and wording. TestInvokeAuthorizer
 * and the MQTT CONNECT on the broker's plaintext listener, which the WebSocket bridge lands on, share
 * it.
 */
@ApplicationScoped
public class IotCustomAuthorizer {

    private static final Logger LOG = Logger.getLogger(IotCustomAuthorizer.class);
    static final String NAME_PARAM = "x-amz-customauthorizer-name";
    static final String SIGNATURE_PARAM = "x-amz-customauthorizer-signature";
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
    private static final Pattern PRINCIPAL_ID = Pattern.compile("([a-zA-Z0-9]){1,128}");
    private static final String POLICIES = "policyDocuments failed to satisfy constraint: ";
    private static final String TTL_RANGE = " failed to satisfy constraint: Member must be greater than 5 minutes and less than 24 hours.";

    private final IotAuthorizerService authorizers;
    private final LambdaService lambdaService;
    private final ObjectMapper objectMapper;
    private final ObjectReader strictReader;
    private final RegionResolver regionResolver;

    /**
     * What a WebSocket upgrade carried: the header names lower-cased, the raw query without its
     * {@code ?} ({@code ""} when there was none), the server name, null unless it came over TLS, and
     * the client's IP address, as the broker sees only the bridge's loopback socket.
     */
    record WebSocketUpgrade(Map<String, String> headers, String queryString, String serverName, String sourceIp) {
    }

    @Inject
    public IotCustomAuthorizer(IotAuthorizerService authorizers, LambdaService lambdaService, ObjectMapper objectMapper,
                               RegionResolver regionResolver) {
        this.authorizers = authorizers;
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
        this.strictReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.regionResolver = regionResolver;
    }

    /** TestInvokeAuthorizer: the request checks AWS makes before invoking, then the function's checked answer. */
    public ObjectNode testInvoke(String name, JsonNode request, String region) {
        String token = request.path("token").asText(null);
        String signature = request.path("tokenSignature").asText(null);
        String queryString = request.path("httpContext").path("queryString").asText(null);
        if ("".equals(token)) {
            throw IotAuthorizerService.constraint("token", "Member must have length greater than or equal to 1");
        }
        if (token != null && token.length() > 6144) {
            throw IotAuthorizerService.constraint("token", "Member must have length less than or equal to 6144");
        }
        if (signature != null && !BASE64.matcher(signature).matches()) {
            throw IotAuthorizerService.constraint("tokenSignature", "Member must satisfy regular expression pattern: " + BASE64);
        }
        if (signature != null && signature.length() > 2560) {
            throw IotAuthorizerService.constraint("tokenSignature", "Member must have length less than or equal to 2560");
        }
        if ("".equals(queryString)) {
            throw IotAuthorizerService.constraint("httpContext.queryString", "Member must have length greater than or equal to 1");
        }
        IotAuthorizer authorizer = authorizers.describeAuthorizer(name, region);
        ObjectNode protocolData = objectMapper.createObjectNode();
        for (String protocol : List.of("tls", "mqtt", "http")) {
            JsonNode context = request.path(protocol + "Context");
            if (context.isObject()) {
                // ponytail: a context goes to the function as sent, members in request order, which SDKs send in model order.
                // Nested members are not checked against the model's length and type constraints, only those measured above.
                protocolData.set(protocol, context);
            }
        }
        if ((token == null) != (signature == null)) {
            throw IotAuthorizerService.invalid("Request must provide both token and tokenSignature together.");
        }
        if (token != null) {
            if (!protocolData.isEmpty()) {
                throw IotAuthorizerService.invalid("Request cannot have token and Http/Mqtt/Tls context.");
            }
            if (authorizer.isSigningDisabled()) {
                throw IotAuthorizerService.invalid("Token and token signature provided but signing is disabled for the authorizer.");
            }
            return invoke(authorizer, event(null, verifiedToken(authorizer, token, signature)));
        }
        if (protocolData.isEmpty()) {
            throw IotAuthorizerService.invalid("Request must have Http/Mqtt/Tls context.");
        }
        if (queryString != null && !queryString.startsWith("?")) {
            throw IotAuthorizerService.invalid("Invalid arguments in the call");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        protocolData.path("http").path("headers").properties().forEach(header -> headers.put(header.getKey(), header.getValue().asText()));
        Map<String, String> params = params(protocolData.path("mqtt").path("username").asText(null), headers, queryString);
        return invoke(authorizer, event(protocolData, signedToken(authorizer, params)));
    }

    /** The authorizer an MQTT CONNECT names in its username query, the WebSocket URL query or an upgrade header; null when none. */
    static String authorizerName(String username, WebSocketUpgrade upgrade) {
        return params(username, upgrade).get(NAME_PARAM);
    }

    /**
     * The policies an MQTT CONNECT through the named authorizer is admitted with. None when the
     * authorizer is not ACTIVE, which never invokes the function, or when the function denies or
     * returns no policy; an {@link AwsException} when the authorizer does not exist, a signature
     * does not verify (the function is not invoked) or the answer is one TestInvokeAuthorizer rejects.
     */
    List<String> connectPolicies(String name, String clientId, String username, String password, WebSocketUpgrade upgrade) {
        // ponytail: the broker has no request context, so a connection finds authorizers in the default account and region.
        IotAuthorizer authorizer = authorizers.describeAuthorizer(name, regionResolver.getDefaultRegion());
        if (!"ACTIVE".equals(authorizer.getStatus())) {
            return List.of();
        }
        ObjectNode protocolData = objectMapper.createObjectNode();
        if (upgrade != null && upgrade.serverName() != null) {
            protocolData.putObject("tls").put("serverName", upgrade.serverName());
        }
        ObjectNode mqtt = protocolData.putObject("mqtt");
        if (username != null) {
            mqtt.put("username", username);
        }
        if (password != null) {
            // ponytail: Vert.x hands the password over as UTF-8 text, so bytes that are not UTF-8 reach the function altered.
            mqtt.put("password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
        }
        mqtt.put("clientId", clientId);
        if (upgrade != null) {
            ObjectNode http = protocolData.putObject("http");
            http.set("headers", objectMapper.valueToTree(upgrade.headers()));
            http.put("queryString", upgrade.queryString());
        }
        ObjectNode answer = invoke(authorizer, event(protocolData, signedToken(authorizer, params(username, upgrade))));
        if (!answer.path("isAuthenticated").booleanValue()) {
            return List.of();
        }
        List<String> policies = new ArrayList<>();
        answer.path("policyDocuments").forEach(policy -> policies.add(policy.asText()));
        return policies;
    }

    private ObjectNode event(ObjectNode protocolData, String token) {
        ObjectNode event = objectMapper.createObjectNode();
        if (protocolData != null) {
            event.set("protocolData", protocolData);
            ArrayNode protocols = event.putArray("protocols");
            for (String protocol : List.of("tls", "http", "mqtt")) {
                if (protocolData.has(protocol)) {
                    protocols.add(protocol);
                }
            }
        }
        if (token != null) {
            event.put("token", token);
        }
        event.put("signatureVerified", token != null);
        event.putObject("connectionMetadata").put("id", UUID.randomUUID().toString());
        return event;
    }

    /** Invokes the function and checks its answer as AWS does, returning the TestInvokeAuthorizer body. */
    private ObjectNode invoke(IotAuthorizer authorizer, ObjectNode event) {
        InvokeResult result = lambdaService.invokeArn(authorizer.getAuthorizerFunctionArn(),
                event.toString().getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);
        JsonNode answer = readJson(result.getPayload());
        if (result.getFunctionError() != null) {
            throw invalidResponse("Lambda function encountered error in execution: " + answer.path("errorMessage").asText()
                    + ": " + answer.path("errorType").asText());
        }
        if (!answer.path("isAuthenticated").isBoolean()) {
            throw invalidResponse("Lambda invocation result is in invalid format");
        }
        String principalId = answer.path("principalId").asText(null);
        if (principalId == null || !PRINCIPAL_ID.matcher(principalId).matches()) {
            throw invalidResponse(principalId + " at principalId failed to satisfy constraint: Member must satisfy " + PRINCIPAL_ID);
        }
        JsonNode policies = answer.path("policyDocuments");
        if (!policies.isArray()) {
            throw invalidResponse(POLICIES + "List of policies cannot be null.");
        }
        if (policies.size() > 10) {
            throw invalidResponse(POLICIES + "Number of policies allowed cannot exceed 10");
        }
        ArrayNode documents = objectMapper.createArrayNode();
        for (JsonNode policy : policies) {
            String document = policy.isTextual() ? policy.asText() : policy.toString();
            // ponytail: size counts characters and syntax means a JSON object; AWS's exact rules are unmeasured.
            if (document.length() > 2048) {
                throw invalidResponse(POLICIES + "Size of a policy document cannot exceeds hard limit of 2048");
            }
            if (!readJson(document.getBytes(StandardCharsets.UTF_8)).isObject()) {
                throw invalidResponse(POLICIES + "Policy document is Malformed: Invalid policy syntax.");
            }
            documents.add(document);
        }
        int refresh = ttl(answer, "refreshAfterInSeconds", 300);
        int disconnect = ttl(answer, "disconnectAfterInSeconds", 86400);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("disconnectAfterInSeconds", disconnect);
        response.put("isAuthenticated", answer.path("isAuthenticated").booleanValue());
        response.set("policyDocuments", documents);
        response.put("principalId", principalId);
        response.put("refreshAfterInSeconds", refresh);
        return response;
    }

    /** A TTL the function returned, or its default; AWS accepts 300 to 86400 inclusive whatever its message says. */
    private static int ttl(JsonNode answer, String field, int fallback) {
        JsonNode value = answer.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt() || value.asInt() < 300 || value.asInt() > 86400) {
            throw invalidResponse(value.asText() + " at " + field + TTL_RANGE);
        }
        return value.asInt();
    }

    private JsonNode readJson(byte[] json) {
        try {
            JsonNode node = strictReader.readTree(json);
            return node == null ? MissingNode.getInstance() : node;
        } catch (IOException | IllegalArgumentException ignored) {
            // Not JSON: the checks that follow report the answer the way AWS does.
            return MissingNode.getInstance();
        }
    }

    /** The verified token of a signed authorizer, or null for one with signing disabled. */
    private static String signedToken(IotAuthorizer authorizer, Map<String, String> params) {
        return authorizer.isSigningDisabled() ? null
                : verifiedToken(authorizer, params.get(authorizer.getTokenKeyName()), params.get(SIGNATURE_PARAM));
    }

    /** The token, once an SHA256withRSA signature over it verifies against one of the authorizer's keys. */
    private static String verifiedToken(IotAuthorizer authorizer, String token, String signature) {
        if (token == null || signature == null) {
            // AWS answers a signed authorizer without its token or signature with a null message.
            throw new AwsException("InvalidRequestException", null, 400);
        }
        for (String pem : authorizer.getTokenSigningPublicKeys().values()) {
            try {
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(IotAuthorizerService.rsaPublicKey(pem));
                verifier.update(token.getBytes(StandardCharsets.UTF_8));
                if (verifier.verify(Base64.getDecoder().decode(signature))) {
                    return token;
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                LOG.debugv("Token signature for authorizer {0} did not verify: {1}", authorizer.getAuthorizerName(), e.getMessage());
            }
        }
        throw IotAuthorizerService.invalid("Token signature mismatch for authorizer " + authorizer.getAuthorizerName());
    }

    private static Map<String, String> params(String username, WebSocketUpgrade upgrade) {
        return upgrade == null ? params(username, Map.of(), null) : params(username, upgrade.headers(), upgrade.queryString());
    }

    /**
     * The parameters a request carries for custom authentication: the query on the MQTT username,
     * then the query string, then the headers, the first one naming a parameter winning.
     */
    private static Map<String, String> params(String username, Map<String, String> headers, String queryString) {
        // ponytail: names match case-insensitively in every source; precedence between sources is unmeasured on AWS.
        Map<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int usernameQuery = username == null ? -1 : username.indexOf('?');
        if (usernameQuery >= 0) {
            addQuery(params, username.substring(usernameQuery + 1));
        }
        if (queryString != null) {
            addQuery(params, queryString.startsWith("?") ? queryString.substring(1) : queryString);
        }
        headers.forEach(params::putIfAbsent);
        return params;
    }

    private static void addQuery(Map<String, String> params, String query) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                params.putIfAbsent(urlDecoded(pair.substring(0, equals)), urlDecoded(pair.substring(equals + 1)));
            }
        }
    }

    private static String urlDecoded(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // A malformed escape is kept as sent: decoding it must not fail a CONNECT that names no authorizer.
            return value;
        }
    }

    private static AwsException invalidResponse(String message) {
        return new AwsException("InvalidResponseException", message, 400);
    }
}
