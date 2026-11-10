package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.auth.CredentialScope;
import io.floci.aws.core.common.auth.SigV4Canonicalization;
import io.floci.aws.core.common.auth.SigV4RequestValidator;
import io.floci.aws.services.apigateway.ApiGatewayService;
import io.floci.aws.services.apigateway.model.EndpointType;
import io.floci.aws.services.apigateway.model.RestApi;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import io.quarkus.vertx.http.HttpServer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Regional, same-account REST API requests under the state machine's IAM execution role. */
@ApplicationScoped
public class ApiGatewayTaskInvoker {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "connection", "content-md5", "expect", "host", "max-forwards",
            "proxy-authenticate", "server", "te", "transfer-encoding", "trailer", "upgrade", "via",
            "www-authenticate");
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final ApiGatewayService apiGateway;
    private final IamService iam;
    private final AssumeRolePolicyEvaluator trust;
    private final LambdaTaskAuthorizer authorizer;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final HttpServer httpServer;

    @Inject
    public ApiGatewayTaskInvoker(ApiGatewayService apiGateway, IamService iam,
                                 AssumeRolePolicyEvaluator trust, LambdaTaskAuthorizer authorizer, EmulatorConfig config,
                                 ObjectMapper mapper, HttpServer httpServer) {
        this.apiGateway = apiGateway;
        this.iam = iam;
        this.trust = trust;
        this.authorizer = authorizer;
        this.config = config;
        this.mapper = mapper;
        this.httpServer = httpServer;
    }

    @PreDestroy
    void close() {
        http.close();
    }

    public JsonNode invoke(JsonNode input, StateMachine machine, String credentialsRoleArn) throws Exception {
        if (!"IAM_ROLE".equals(input.path("AuthType").asText())
                || !"POST".equals(input.path("Method").asText())
                || input.has("QueryParameters") || input.has("AllowNullValues")) {
            throw unsupported("only REST POST with IAM_ROLE and JSON request body is supported");
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        String endpoint = input.path("ApiEndpoint").asText();
        int separator = endpoint.indexOf('.');
        String apiId = separator < 0 ? "" : endpoint.substring(0, separator);
        if (!apiId.matches("[a-z0-9]+")
                || !endpoint.equals(AwsEndpoints.executeApiHost(apiId, owner.region()))) {
            throw unsupported("endpoint must be a regional execute-api hostname in the execution region");
        }
        ApiGatewayService.ApiOwner apiOwner = apiGateway.findRestApiOwner(apiId)
                .orElseThrow(() -> failure(404, "API does not exist"));
        if (!owner.accountId().equals(apiOwner.accountId()) || !owner.region().equals(apiOwner.region())) {
            throw failure(403, "API belongs to a different account or region");
        }
        RestApi api = apiGateway.getRestApi(owner.region(), apiId);
        if (api.getEndpointConfiguration() != null
                && api.getEndpointConfiguration().getTypes().contains(EndpointType.PRIVATE)) {
            throw unsupported("private APIs are not supported by this integration");
        }
        if (api.getPolicy() != null && !api.getPolicy().isBlank()) {
            throw unsupported("API resource policies are not supported by this integration");
        }
        String stage = input.path("Stage").asText();
        String path = input.path("Path").asText();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!stage.matches("[A-Za-z0-9_-]+") || path.isBlank()
                || List.of(path.split("/", -1)).stream().anyMatch(part -> part.isEmpty()
                    || part.equals(".") || part.equals(".."))) {
            throw unsupported("a named stage and non-empty resource path are required");
        }
        String roleArn = credentialsRoleArn == null ? machine.getRoleArn() : credentialsRoleArn;
        authorize(machine, owner, apiId, stage, path, roleArn, credentialsRoleArn);
        AwsArnUtils.Arn roleId = AwsArnUtils.parse(roleArn);
        String roleName = roleId.resource().substring(roleId.resource().lastIndexOf('/') + 1);
        String principalId = iam.findRole(roleId.accountId(), roleName).map(IamRole::getRoleId).orElse(roleName);
        TreeMap<String, List<String>> headers = requestHeaders(input.path("Headers"));
        headers.putIfAbsent("content-type", List.of("application/json"));
        JsonNode body = input.path("RequestBody");
        if (!body.isObject() || containsNull(body)) {
            throw unsupported("request body must be a JSON object without null values");
        }
        byte[] payload = mapper.writeValueAsBytes(body);
        List<String> length = headers.remove("content-length");
        if (length != null && !length.equals(List.of(Integer.toString(payload.length)))) {
            throw unsupported("Content-Length must match the serialized request body");
        }
        URI listener = httpServer.getLocalBaseUri();
        if (listener == null) {
            throw new AslExecutor.FailStateException("ApiGateway.IllegalStateException", "API listener is not ready");
        }
        String encodedPath = List.of(path.split("/", -1)).stream()
                .map(SigV4Canonicalization::uriEncode).collect(Collectors.joining("/"));
        URI uri = listener.resolve("/restapis/" + apiId + "/" + stage + "/_user_request_/" + encodedPath);
        String accessKey = accessKey();
        String secret = randomSecret(30);
        String token = randomSecret(48);
        String sessionName = "StepFunctions-" + UUID.randomUUID();
        iam.registerSession(accessKey, secret, token, roleArn, Instant.now().plusSeconds(120),
                null, roleId.accountId(), sessionName, principalId + ":" + sessionName);
        try {
            sign(headers, uri, payload, owner.region(), accessKey, secret, token);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            headers.forEach((name, value) -> {
                if (!"host".equals(name)) {
                    value.forEach(item -> request.header(name, item));
                }
            });
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            String text = new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(response.statusCode(), text);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("StatusCode", response.statusCode());
            Response.Status status = Response.Status.fromStatusCode(response.statusCode());
            result.put("StatusText", status == null ? "" : status.getReasonPhrase());
            result.set("Headers", mapper.valueToTree(response.headers().map()));
            try {
                result.set("ResponseBody", mapper.readTree(text));
            } catch (IOException ignored) {
                result.put("ResponseBody", text);
            }
            return result;
        } catch (IOException error) {
            throw new AslExecutor.FailStateException("ApiGateway." + error.getClass().getSimpleName(),
                    "API request could not complete");
        } finally {
            iam.unregisterSession(roleId.accountId(), accessKey);
        }
    }

    private void authorize(StateMachine machine, AwsArnUtils.Arn owner,
                           String apiId, String stage, String path, String roleArn, String credentialsRoleArn) {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        AwsArnUtils.Arn executionRole = AwsArnUtils.parse(machine.getRoleArn());
        if (!"iam".equals(executionRole.service()) || !executionRole.region().isEmpty()
                || !executionRole.resource().startsWith("role/")
                || !executionRole.accountId().equals(owner.accountId())
                || !executionRole.partition().equals(owner.partition())) {
            throw failure(403, "Execution role must belong to the state machine account");
        }
        String name = executionRole.resource().substring(executionRole.resource().lastIndexOf('/') + 1);
        IamRole role = iam.findRole(executionRole.accountId(), name)
                .filter(found -> IamService.roleArnMatches(machine.getRoleArn(), found))
                .orElseThrow(() -> failure(403, "Execution role does not exist"));
        if (!trust.allowsService(role.getAssumeRolePolicyDocument(), ServicePrincipals.of("states"),
                machine.getStateMachineArn(), owner.accountId())) {
            throw failure(403, "Execution role does not trust Step Functions");
        }
        if (credentialsRoleArn != null) {
            authorizer.assumeRefusal(machine.getRoleArn(), credentialsRoleArn, machine.getStateMachineArn())
                    .ifPresent(reason -> { throw new AslExecutor.FailStateException("States.Permissions", reason); });
        }
        String resource = new AwsArnUtils.Arn(owner.partition(), "execute-api", owner.region(), owner.accountId(),
                apiId + "/" + stage + "/POST/" + path).toString();
        authorizer.refusal(roleArn, "execute-api:Invoke", resource)
                .ifPresent(reason -> { throw failure(403, reason); });
    }

    private static boolean containsNull(JsonNode value) {
        if (value.isNull()) {
            return true;
        }
        for (JsonNode child : value) {
            if (containsNull(child)) {
                return true;
            }
        }
        return false;
    }

    private static TreeMap<String, List<String>> requestHeaders(JsonNode input) {
        TreeMap<String, List<String>> result = new TreeMap<>();
        if (input.isMissingNode()) {
            return result;
        }
        if (!input.isObject()) {
            throw unsupported("headers must be an object of string arrays");
        }
        input.properties().forEach(entry -> {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            JsonNode values = entry.getValue();
            if (!entry.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || isReservedDefinitionHeader(name)
                    || !values.isArray() || values.isEmpty() || result.containsKey(name)) {
                throw unsupported("header name or value is not supported");
            }
            List<String> strings = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().chars().anyMatch(ch -> ch < 32 && ch != '\t' || ch == 127 || ch > 255)) {
                    throw unsupported("header name or value is not supported");
                }
                strings.add(value.asText());
            }
            result.put(name, strings);
        });
        return result;
    }

    static boolean isReservedDefinitionHeader(String lowerName) {
        return RESERVED_HEADERS.contains(lowerName) || lowerName.startsWith("x-amz-")
                || lowerName.startsWith("x-amzn-") || lowerName.startsWith("x-forwarded-");
    }

    private static void sign(TreeMap<String, List<String>> headers, URI uri, byte[] payload, String region,
                             String accessKey, String secret, String token) throws Exception {
        String stamp = AMZ_DATE.format(Instant.now());
        headers.put("host", List.of(uri.getAuthority()));
        headers.put("x-amz-date", List.of(stamp));
        headers.put("x-amz-security-token", List.of(token));
        String signed = String.join(";", headers.keySet());
        CredentialScope scope = new CredentialScope(accessKey, stamp.substring(0, 8), region, "execute-api");
        String canonical = "POST\n" + uri.getRawPath() + "\n\n"
                + SigV4Canonicalization.canonicalHeaders(signed, name -> headers.get(name).stream()
                        .map(SigV4RequestValidator::normalizeHeaderValue).collect(Collectors.joining(","))) + "\n" + signed + "\n"
                + Digests.sha256Hex(payload);
        String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope.credentialScope() + "\n"
                + Digests.sha256Hex(canonical);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(secret, scope.date(), region, "execute-api"), toSign));
        headers.put("authorization", List.of("AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope.credentialScope()
                + ", SignedHeaders=" + signed + ", Signature=" + signature));
    }

    private static String accessKey() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder value = new StringBuilder("ASIA");
        for (int index = 0; index < 16; index++) {
            value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static String randomSecret(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static AslExecutor.FailStateException failure(int status, String cause) {
        return new AslExecutor.FailStateException("ApiGateway." + status, cause);
    }

    private static AslExecutor.FailStateException unsupported(String feature) {
        return new AslExecutor.FailStateException("States.TaskFailed", "Unsupported apigateway:invoke feature: " + feature);
    }
}
