package io.floci.aws.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cognito.CognitoService;
import io.floci.aws.services.verifiedpermissions.model.EntityIdentifier;
import io.floci.aws.services.verifiedpermissions.model.IdentitySource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class VerifiedPermissionsTokenService {
    private final VerifiedPermissionsService service;
    private final CedarAuthorizationEvaluator evaluator;
    private final VerifiedPermissionsOidcSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final CognitoService cognitoService;

    @Inject
    public VerifiedPermissionsTokenService(VerifiedPermissionsService service,
                                           CedarAuthorizationEvaluator evaluator,
                                           VerifiedPermissionsOidcSignatureVerifier signatureVerifier,
                                           ObjectMapper objectMapper,
                                           CognitoService cognitoService) {
        this.service = service;
        this.evaluator = evaluator;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.cognitoService = cognitoService;
    }

    public PreparedTokenRequest prepare(JsonNode request, String region) {
        String store = VerifiedPermissionsService.requiredText(request, "policyStoreId");
        String identityToken = VerifiedPermissionsService.text(request, "identityToken", null);
        String accessToken = VerifiedPermissionsService.text(request, "accessToken", null);
        if ((identityToken == null || identityToken.isBlank()) && (accessToken == null || accessToken.isBlank())) {
            throw VerifiedPermissionsService.validation("You must specify identityToken, accessToken, or both.");
        }

        TokenInfo identity = identityToken == null ? null : validateToken(store, identityToken, TokenKind.IDENTITY, region);
        TokenInfo access = accessToken == null ? null : validateToken(store, accessToken, TokenKind.ACCESS, region);
        TokenInfo principalToken = identity != null ? identity : access;
        if (identity != null && access != null
                && (!identity.principal().equals(access.principal()) || !identity.source().identitySourceId().equals(access.source().identitySourceId()))) {
            throw VerifiedPermissionsService.validation("identityToken and accessToken must identify the same principal and identity source.");
        }

        ObjectNode prepared = request.deepCopy();
        prepared.remove(List.of("identityToken", "accessToken"));
        ObjectNode principal = prepared.putObject("principal");
        principal.put("entityType", principalToken.principal().entityType());
        principal.put("entityId", principalToken.principal().entityId());
        JsonNode identityClaims = identity == null ? null : identity.claims();
        List<EntityIdentifier> parents = mergeParents(identity, access);
        prepared.set("entities", evaluator.appendTokenPrincipal(prepared.get("entities"), principalToken.principal(), identityClaims, parents));
        if (access != null) {
            prepared.set("context", mergeAccessContext(prepared.get("context"), access.claims()));
        }
        return new PreparedTokenRequest(prepared, principalToken.principal());
    }

    private TokenInfo validateToken(String store, String token, TokenKind requestedKind, String region) {
        JsonNode claims = decodeClaims(token);
        String issuer = claims.path("iss").asText(null);
        if (issuer == null || issuer.isBlank()) {
            throw VerifiedPermissionsService.validation("Token has no issuer claim.");
        }
        List<IdentitySource> sources = service.identitySourcesForStore(store, region);
        // A Cognito identity source names a pool. When that pool is one of Floci's own, its
        // tokens carry Floci's issuer (CognitoService#getIssuer), not the AWS host stored in the
        // source's configuration, and are verified against the pool's signing key here: the OIDC
        // verifier only fetches public HTTPS JWKS, which an emulated pool never has.
        IdentitySource local = sources.stream()
                .filter(candidate -> issuer.equals(emulatedCognitoIssuer(candidate.configuration())))
                .findFirst().orElse(null);
        IdentitySource source = local != null ? local : sources.stream()
                .filter(candidate -> issuer.equals(VerifiedPermissionsService.identityIssuer(candidate.configuration())))
                .findFirst().orElseThrow(() -> VerifiedPermissionsService.validation("No identity source matches the token issuer."));
        if (local != null) {
            String poolId = cognitoPoolId(local.configuration());
            if (!cognitoService.verifiedIssuingPoolId(token).map(poolId::equals).orElse(false)) {
                throw VerifiedPermissionsService.validation("Token signature validation failed.");
            }
        } else {
            try {
                signatureVerifier.verify(token, issuer);
            } catch (VerifiedPermissionsOidcSignatureVerifier.VerificationException e) {
                throw VerifiedPermissionsService.validation("Token signature validation failed: " + e.getMessage());
            }
        }
        validateTimes(claims);
        JsonNode configuration = source.configuration();
        if (configuration.has("cognitoUserPoolConfiguration")) {
            return validateCognito(source, claims, requestedKind);
        }
        return validateOidc(source, claims, requestedKind);
    }

    private TokenInfo validateCognito(IdentitySource source, JsonNode claims, TokenKind requestedKind) {
        String expectedUse = requestedKind == TokenKind.IDENTITY ? "id" : "access";
        if (!expectedUse.equals(claims.path("token_use").asText())) {
            throw VerifiedPermissionsService.validation("The submitted token has the wrong token_use claim.");
        }
        JsonNode config = source.configuration().get("cognitoUserPoolConfiguration");
        JsonNode clients = config.path("clientIds");
        if (clients.isArray() && !clients.isEmpty()) {
            String actual = requestedKind == TokenKind.IDENTITY ? claims.path("aud").asText(null) : claims.path("client_id").asText(null);
            if (actual == null || !arrayContains(clients, actual)) {
                throw VerifiedPermissionsService.validation("The token client application isn't allowed by this identity source.");
            }
        }
        String sub = requiredClaim(claims, "sub");
        String poolId = config.path("userPoolArn").asText().substring(config.path("userPoolArn").asText().lastIndexOf('/') + 1);
        EntityIdentifier principal = new EntityIdentifier(source.principalEntityType(), poolId + "|" + sub);
        return new TokenInfo(source, claims, principal, groupParents(source, claims, poolId, "cognito:groups"));
    }

    private TokenInfo validateOidc(IdentitySource source, JsonNode claims, TokenKind requestedKind) {
        JsonNode config = source.configuration().get("openIdConnectConfiguration");
        JsonNode selection = config.path("tokenSelection");
        boolean accessConfigured = selection.has("accessTokenOnly");
        if ((requestedKind == TokenKind.ACCESS) != accessConfigured) {
            throw VerifiedPermissionsService.validation("The token type isn't enabled for this OIDC identity source.");
        }
        JsonNode tokenConfig = accessConfigured ? selection.get("accessTokenOnly") : selection.get("identityTokenOnly");
        JsonNode accepted = accessConfigured ? tokenConfig.path("audiences") : tokenConfig.path("clientIds");
        if (accepted.isArray() && !accepted.isEmpty() && !audienceMatches(claims.get("aud"), accepted)) {
            throw VerifiedPermissionsService.validation("The token audience isn't allowed by this identity source.");
        }
        String principalClaim = tokenConfig.path("principalIdClaim").asText("sub");
        String principalId = requiredClaim(claims, principalClaim);
        String prefix = config.path("entityIdPrefix").asText("");
        String composed = prefix.isEmpty() ? principalId : prefix + "|" + principalId;
        EntityIdentifier principal = new EntityIdentifier(source.principalEntityType(), composed);
        String groupClaim = config.path("groupConfiguration").path("groupClaim").asText(null);
        return new TokenInfo(source, claims, principal, groupClaim == null ? List.of() : groupParents(source, claims, prefix, groupClaim));
    }

    private List<EntityIdentifier> groupParents(IdentitySource source, JsonNode claims, String prefix, String claimName) {
        JsonNode groupConfiguration = source.configuration().has("cognitoUserPoolConfiguration")
                ? source.configuration().path("cognitoUserPoolConfiguration").path("groupConfiguration")
                : source.configuration().path("openIdConnectConfiguration").path("groupConfiguration");
        String groupType = groupConfiguration.path("groupEntityType").asText(null);
        if (groupType == null) {
            return List.of();
        }
        JsonNode groups = claims.get(claimName);
        if (groups == null || groups.isNull()) {
            return List.of();
        }
        List<EntityIdentifier> result = new ArrayList<>();
        if (groups.isArray()) {
            groups.forEach(group -> result.add(new EntityIdentifier(groupType, qualify(prefix, group.asText()))));
        } else if (groups.isTextual()) {
            result.add(new EntityIdentifier(groupType, qualify(prefix, groups.asText())));
        }
        return result;
    }

    private static String qualify(String prefix, String value) {
        return prefix == null || prefix.isEmpty() ? value : prefix + "|" + value;
    }

    private List<EntityIdentifier> mergeParents(TokenInfo identity, TokenInfo access) {
        LinkedHashSet<EntityIdentifier> parents = new LinkedHashSet<>();
        if (identity != null) {
            parents.addAll(identity.parents());
        }
        if (access != null) {
            parents.addAll(access.parents());
        }
        return new ArrayList<>(parents);
    }

    private ObjectNode mergeAccessContext(JsonNode existing, JsonNode claims) {
        ObjectNode root;
        try {
            if (existing == null || existing.isNull()) {
                root = objectMapper.createObjectNode();
            } else if (existing.has("cedarJson")) {
                JsonNode parsed = objectMapper.readTree(existing.path("cedarJson").asText());
                if (!parsed.isObject()) {
                    throw VerifiedPermissionsService.validation("context.cedarJson must encode an object.");
                }
                root = (ObjectNode) parsed.deepCopy();
            } else if (existing.has("contextMap")) {
                root = contextMapToRaw(existing.get("contextMap"));
            } else {
                throw VerifiedPermissionsService.validation("context must contain cedarJson or contextMap.");
            }
        } catch (IOException e) {
            throw VerifiedPermissionsService.validation("context.cedarJson isn't valid JSON.");
        }
        ObjectNode token = root.putObject("token");
        claims.fields().forEachRemaining(entry -> {
            if (!"cognito:groups".equals(entry.getKey()) && claimSupported(entry.getValue())) {
                token.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        ObjectNode union = objectMapper.createObjectNode();
        union.put("cedarJson", root.toString());
        return union;
    }

    private ObjectNode contextMapToRaw(JsonNode map) {
        ObjectNode result = objectMapper.createObjectNode();
        map.fields().forEachRemaining(entry -> result.set(entry.getKey(), rawAttribute(entry.getValue())));
        return result;
    }

    private JsonNode rawAttribute(JsonNode union) {
        if (!union.isObject() || union.size() != 1) {
            throw VerifiedPermissionsService.validation("Context attributes must contain one union member.");
        }
        Map.Entry<String, JsonNode> entry = union.fields().next();
        return switch (entry.getKey()) {
            case "boolean", "long", "string" -> entry.getValue().deepCopy();
            case "set" -> {
                ArrayNode array = objectMapper.createArrayNode();
                entry.getValue().forEach(v -> array.add(rawAttribute(v)));
                yield array;
            }
            case "record" -> contextMapToRaw(entry.getValue());
            default -> throw VerifiedPermissionsService.validation("Token context merge doesn't support attribute type " + entry.getKey() + ".");
        };
    }

    /** The pool id of a Cognito identity source, or null for an OIDC one. */
    private static String cognitoPoolId(JsonNode configuration) {
        JsonNode cognito = configuration.get("cognitoUserPoolConfiguration");
        if (cognito == null) {
            return null;
        }
        String arn = cognito.path("userPoolArn").asText("");
        int slash = arn.indexOf("userpool/");
        return slash < 0 ? null : arn.substring(slash + "userpool/".length());
    }

    /**
     * The issuer Floci's Cognito signs with for this source's pool, or null when the source does
     * not name one of Floci's pools: the full ARN must match, not only the pool id.
     */
    private String emulatedCognitoIssuer(JsonNode configuration) {
        String poolId = cognitoPoolId(configuration);
        if (poolId == null) {
            return null;
        }
        String configuredArn = configuration.path("cognitoUserPoolConfiguration").path("userPoolArn").asText();
        try {
            if (!configuredArn.equals(cognitoService.describeUserPool(poolId).getArn())) {
                return null;
            }
        } catch (AwsException e) {
            return null;
        }
        return cognitoService.getIssuer(poolId);
    }

    private JsonNode decodeClaims(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw VerifiedPermissionsService.validation("Token isn't a well-formed JWT.");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(pad(parts[1]));
            JsonNode claims = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (claims == null || !claims.isObject()) {
                throw VerifiedPermissionsService.validation("Token claims aren't a JSON object.");
            }
            return claims;
        } catch (IllegalArgumentException | IOException e) {
            throw VerifiedPermissionsService.validation("Token payload isn't valid base64url JSON.");
        }
    }

    private static void validateTimes(JsonNode claims) {
        long now = Instant.now().getEpochSecond();
        if (!claims.has("exp") || !claims.get("exp").canConvertToLong() || claims.get("exp").asLong() <= now) {
            throw VerifiedPermissionsService.validation("Token is expired or has no valid exp claim.");
        }
        if (claims.has("nbf") && claims.get("nbf").canConvertToLong() && claims.get("nbf").asLong() > now) {
            throw VerifiedPermissionsService.validation("Token isn't valid yet.");
        }
    }

    private static String requiredClaim(JsonNode claims, String name) {
        JsonNode value = claims.get(name);
        if (value == null || !value.isValueNode() || value.asText().isEmpty()) {
            throw VerifiedPermissionsService.validation("Token is missing required principal claim " + name + ".");
        }
        return value.asText();
    }

    private static boolean arrayContains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean audienceMatches(JsonNode actual, JsonNode accepted) {
        if (actual == null) {
            return false;
        }
        if (actual.isTextual()) {
            return arrayContains(accepted, actual.asText());
        }
        if (actual.isArray()) {
            for (JsonNode item : actual) {
                if (arrayContains(accepted, item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean claimSupported(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual() || value.isBoolean() || value.isIntegralNumber()) {
            return true;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (!(item.isTextual() || item.isBoolean() || item.isIntegralNumber())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static String pad(String value) {
        return switch (value.length() % 4) {
            case 2 -> value + "==";
            case 3 -> value + "=";
            default -> value;
        };
    }

    private enum TokenKind { IDENTITY, ACCESS }
    private record TokenInfo(IdentitySource source, JsonNode claims, EntityIdentifier principal,
                             List<EntityIdentifier> parents) {}
    public record PreparedTokenRequest(ObjectNode request, EntityIdentifier principal) {}
}
