package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AWS IoT Core custom authorizers: the records CreateAuthorizer and its peers manage, and the
 * account's default authorizer. The function ARN is checked for ARN syntax only, as on AWS;
 * {@link IotCustomAuthorizer} invokes the function.
 */
@ApplicationScoped
public class IotAuthorizerService {

    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w=,@-]+");
    private static final Pattern TOKEN_KEY_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final Pattern KEY_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9:_-]{1,128}");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final StorageBackend<String, IotAuthorizer> store;
    private final RegionResolver regionResolver;
    /** Guards every read-modify-write on the store, the default-authorizer flag included. */
    private final Object lock = new Object();

    @Inject
    public IotAuthorizerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("iot", "iot-authorizers.json", new TypeReference<Map<String, IotAuthorizer>>() {}),
                regionResolver);
    }

    IotAuthorizerService(StorageBackend<String, IotAuthorizer> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public IotAuthorizer createAuthorizer(String name, JsonNode body, String region) {
        requireNameLike("authorizerName", name, NAME_PATTERN);
        String functionArn = body.path("authorizerFunctionArn").asText(null);
        if (functionArn == null) {
            throw constraint("authorizerFunctionArn", "Member must not be null");
        }
        String status = status(body.path("status").asText(null));
        String tokenKeyName = tokenKeyName(body);
        Map<String, String> keys = keys(body);
        requireFunctionArn(name, functionArn);
        boolean signingDisabled = body.path("signingDisabled").asBoolean(false);
        if (signingDisabled && keys != null) {
            throw keysMustBeNull(name);
        }
        if (!signingDisabled) {
            if (tokenKeyName == null) {
                throw invalid("Token key name for authorizer " + name + " cannot be null");
            }
            if (keys == null || keys.isEmpty()) {
                throw invalid("Token signing keys map for authorizer " + name + " cannot be null or empty. "
                        + "There must be at least one and at most two token signing public keys in the map");
            }
            requireKeys(name, keys);
        }

        IotAuthorizer authorizer = new IotAuthorizer();
        authorizer.setAuthorizerName(name);
        authorizer.setAuthorizerArn(regionResolver.buildArn("iot", region, "authorizer/" + name));
        authorizer.setAuthorizerFunctionArn(functionArn);
        authorizer.setTokenKeyName(tokenKeyName);
        authorizer.setTokenSigningPublicKeys(keys);
        authorizer.setStatus(status == null ? "INACTIVE" : status);
        authorizer.setSigningDisabled(signingDisabled);
        authorizer.setEnableCachingForHttp(body.path("enableCachingForHttp").asBoolean(false));
        Instant now = Instant.now();
        authorizer.setCreationDate(now);
        authorizer.setLastModifiedDate(now);
        authorizer.setTags(IotDomainConfigurationService.parseTags(body.path("tags")));
        synchronized (lock) {
            if (store.get(key(region, name)).isPresent()) {
                throw alreadyExists("Authorizer " + name + " already exist for this account", authorizer);
            }
            store.put(key(region, name), authorizer);
        }
        return authorizer;
    }

    public IotAuthorizer describeAuthorizer(String name, String region) {
        requireNameLike("authorizerName", name, NAME_PATTERN);
        return store.get(key(region, name))
                .orElseThrow(() -> notFound("Authorizer " + name + " not found"));
    }

    /** A partial update: absent members stay, signing keys merge per key name. */
    public IotAuthorizer updateAuthorizer(String name, JsonNode body, String region) {
        requireNameLike("authorizerName", name, NAME_PATTERN);
        String functionArn = body.path("authorizerFunctionArn").asText(null);
        String status = status(body.path("status").asText(null));
        String tokenKeyName = tokenKeyName(body);
        Map<String, String> keys = keys(body);
        JsonNode caching = body.path("enableCachingForHttp");
        if (functionArn != null) {
            requireFunctionArn(name, functionArn);
        }
        synchronized (lock) {
            IotAuthorizer authorizer = describeAuthorizer(name, region);
            if (keys != null) {
                if (authorizer.isSigningDisabled()) {
                    throw keysMustBeNull(name);
                }
                requireKeys(name, keys);
                Map<String, String> merged = new LinkedHashMap<>(authorizer.getTokenSigningPublicKeys());
                merged.putAll(keys);
                if (merged.size() > 2) {
                    throw invalid("Number of signing keys for authorizer " + name + " must neither be zero nor greater than 2");
                }
                authorizer.setTokenSigningPublicKeys(merged);
            }
            if (functionArn != null) {
                authorizer.setAuthorizerFunctionArn(functionArn);
            }
            if (status != null) {
                authorizer.setStatus(status);
            }
            if (tokenKeyName != null) {
                authorizer.setTokenKeyName(tokenKeyName);
            }
            if (caching.isBoolean()) {
                authorizer.setEnableCachingForHttp(caching.booleanValue());
            }
            // ponytail: AWS bumps on any member sent; whether an unchanged value bumps it is unmeasured.
            if (functionArn != null || status != null || tokenKeyName != null || keys != null || caching.isBoolean()) {
                authorizer.setLastModifiedDate(Instant.now());
            }
            store.put(key(region, name), authorizer);
            return authorizer;
        }
    }

    public void deleteAuthorizer(String name, String region) {
        synchronized (lock) {
            IotAuthorizer authorizer = describeAuthorizer(name, region);
            // ponytail: which check wins for an ACTIVE default authorizer is unmeasured; ACTIVE goes first.
            if ("ACTIVE".equals(authorizer.getStatus())) {
                throw invalid("Cannot delete authorizer " + name + " in ACTIVE status. Update status to INACTIVE and delete.");
            }
            if (authorizer.isDefaultAuthorizer()) {
                throw new AwsException("DeleteConflictException",
                        "Cannot delete default authorizer " + name + ". Change default and retry delete.", 409);
            }
            store.delete(key(region, name));
        }
    }

    /** Newest first unless ascending; the marker is the offset of the next item. */
    public IotService.Page<IotAuthorizer> listAuthorizers(String region, String status, boolean ascending,
                                                          String marker, Integer pageSize) {
        if (pageSize != null && pageSize < 1) {
            throw constraint("pageSize", "Member must have value greater than or equal to 1");
        }
        if (pageSize != null && pageSize > 250) {
            throw constraint("pageSize", "Member must have value less than or equal to 250");
        }
        status(status);
        Comparator<IotAuthorizer> byCreation = Comparator.comparing(IotAuthorizer::getCreationDate)
                .thenComparing(IotAuthorizer::getAuthorizerName);
        List<IotAuthorizer> items = authorizers(region).stream()
                .filter(authorizer -> status == null || status.equals(authorizer.getStatus()))
                .sorted(ascending ? byCreation : byCreation.reversed())
                .toList();
        // ponytail: an offset, not AWS's encrypted token; SDKs pass it back opaquely either way.
        int start;
        try {
            start = marker == null || marker.isEmpty() ? 0 : Math.min(items.size(), Math.max(0, Integer.parseInt(marker)));
        } catch (NumberFormatException e) {
            throw invalid("Invalid/Malformed marker passed for listAuthorizers");
        }
        int end = pageSize == null ? items.size() : Math.min(items.size(), start + pageSize);
        return new IotService.Page<>(items.subList(start, end), end < items.size() ? Integer.toString(end) : null);
    }

    public IotAuthorizer setDefaultAuthorizer(String name, String region) {
        synchronized (lock) {
            IotAuthorizer authorizer = describeAuthorizer(name, region);
            if (authorizer.isDefaultAuthorizer()) {
                throw alreadyExists("Duplicate default authorizer " + name + " already configured for this account", authorizer);
            }
            defaultAuthorizer(region).ifPresent(previous -> saveDefault(previous, false, region));
            saveDefault(authorizer, true, region);
            return authorizer;
        }
    }

    public IotAuthorizer describeDefaultAuthorizer(String region) {
        synchronized (lock) {
            return defaultAuthorizer(region).orElseThrow(() -> notFound("Default authorizer not found"));
        }
    }

    public void clearDefaultAuthorizer(String region) {
        synchronized (lock) {
            saveDefault(describeDefaultAuthorizer(region), false, region);
        }
    }

    /** A missing authorizer, or one in another account or partition, has no tags to list, as on AWS; tagging it is a 404. */
    public Map<String, String> listTagsForResource(String resourceArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        return store.get(key(arn.region(), nameFromArn(arn)))
                .filter(authorizer -> resourceArn.equals(authorizer.getAuthorizerArn()))
                .map(IotAuthorizer::getTags).orElse(Map.of());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        updateTags(resourceArn, current -> current.putAll(tags));
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        updateTags(resourceArn, current -> tagKeys.forEach(current::remove));
    }

    private void updateTags(String resourceArn, Consumer<Map<String, String>> change) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String name = nameFromArn(arn);
        synchronized (lock) {
            IotAuthorizer authorizer = store.get(key(arn.region(), name))
                    .filter(stored -> resourceArn.equals(stored.getAuthorizerArn()))
                    .orElseThrow(() -> notFound("Authorizer " + name + " not found"));
            Map<String, String> tags = new TreeMap<>(authorizer.getTags());
            change.accept(tags);
            authorizer.setTags(tags);
            store.put(key(arn.region(), name), authorizer);
        }
    }

    private Optional<IotAuthorizer> defaultAuthorizer(String region) {
        return authorizers(region).stream().filter(IotAuthorizer::isDefaultAuthorizer).findFirst();
    }

    private void saveDefault(IotAuthorizer authorizer, boolean isDefault, String region) {
        authorizer.setDefaultAuthorizer(isDefault);
        store.put(key(region, authorizer.getAuthorizerName()), authorizer);
    }

    private List<IotAuthorizer> authorizers(String region) {
        String prefix = key(region, "");
        return store.scan(storeKey -> storeKey.startsWith(prefix));
    }

    private static String nameFromArn(AwsArnUtils.Arn arn) {
        return arn.resource().substring("authorizer/".length());
    }

    private static String status(String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw constraint("status", "Member must satisfy enum value set: [ACTIVE, INACTIVE]");
        }
        return status;
    }

    private static String tokenKeyName(JsonNode body) {
        String tokenKeyName = body.path("tokenKeyName").asText(null);
        if (tokenKeyName != null) {
            requireNameLike("tokenKeyName", tokenKeyName, TOKEN_KEY_NAME_PATTERN);
        }
        return tokenKeyName;
    }

    /** The request's key map in request order, or null when absent. */
    private static Map<String, String> keys(JsonNode body) {
        JsonNode node = body.path("tokenSigningPublicKeys");
        if (!node.isObject()) {
            return null;
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!KEY_NAME_PATTERN.matcher(entry.getKey()).matches()) {
                throw constraint("tokenSigningPublicKeys", "Map keys must satisfy constraint: [Member must have length "
                        + "less than or equal to 128, Member must have length greater than or equal to 1, "
                        + "Member must satisfy regular expression pattern: [a-zA-Z0-9:_-]+]");
            }
            keys.put(entry.getKey(), entry.getValue().asText(null));
        }
        return keys;
    }

    private static void requireKeys(String name, Map<String, String> keys) {
        if (keys.size() > 2) {
            throw invalid("Token signing keys map for authorizer " + name + " cannot contain more than 2 keys");
        }
        keys.forEach((keyName, pem) -> requireRsa2048(name, keyName, pem));
    }

    private static void requireRsa2048(String name, String keyName, String pem) {
        if (pem == null || pem.isEmpty()) {
            throw invalid("Token signing public keys for authorizer " + name + " cannot be null or empty");
        }
        // ponytail: a line scan stands in for AWS's PEM object parser: text before the header is skipped,
        // no header line is not a key, a header without its end line cannot convert, and a private key
        // header is told apart first.
        if (pem.contains("PRIVATE KEY")) {
            throw invalid("Cannot convert public key PEM for authorizer " + name + " to RSA key");
        }
        List<String> lines = pem.lines().toList();
        int begin = lines.indexOf("-----BEGIN PUBLIC KEY-----");
        if (begin < 0) {
            throw invalid("Authorizer " + name + " public key for key name " + keyName + " not a valid RSA key");
        }
        List<String> rest = lines.subList(begin + 1, lines.size());
        int end = rest.indexOf("-----END PUBLIC KEY-----");
        if (end < 0) {
            throw invalid("Cannot convert public key PEM for authorizer " + name + " to RSA key");
        }
        RSAPublicKey key;
        try {
            key = rsaPublicKey(pem);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw invalid("Authorizer " + name + " public key for key name " + keyName + " not a valid RSA key");
        }
        int bits = key.getModulus().bitLength();
        if (bits != 2048) {
            throw invalid("Authorizer " + name + " public key for key name " + keyName
                    + " invalid: Key must be 2048 bits but was " + bits + " bits");
        }
    }

    /** The key of a PEM whose header and end lines {@link #requireRsa2048} has checked. */
    static RSAPublicKey rsaPublicKey(String pem) throws GeneralSecurityException {
        List<String> lines = pem.lines().toList();
        List<String> body = lines.subList(lines.indexOf("-----BEGIN PUBLIC KEY-----") + 1, lines.size());
        byte[] der = Base64.getDecoder().decode(String.join("", body.subList(0, body.indexOf("-----END PUBLIC KEY-----"))));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static void requireFunctionArn(String name, String functionArn) {
        if (AwsArnUtils.resourceIfArnFor(functionArn, "lambda").filter(r -> r.startsWith("function:")).isEmpty()) {
            throw invalid("Lambda function arn for authorizer " + name + " is not in proper ARN syntax");
        }
    }

    /** AWS reports every violated constraint of the member, the pattern first, as the measured messages show. */
    private static void requireNameLike(String field, String value, Pattern pattern) {
        if (value == null) {
            throw constraint(field, "Member must not be null");
        }
        List<String> violations = new ArrayList<>();
        if (!pattern.matcher(value).matches()) {
            violations.add("Member must satisfy regular expression pattern: " + pattern.pattern());
        }
        if (value.isEmpty()) {
            violations.add("Member must have length greater than or equal to 1");
        }
        if (value.length() > 128) {
            violations.add("Member must have length less than or equal to 128");
        }
        if (!violations.isEmpty()) {
            throw constraints(field, violations);
        }
    }

    private static AwsException keysMustBeNull(String name) {
        return invalid("Token signing keys map must be null for authorizer " + name + " if using optional signature header");
    }

    private static AwsException alreadyExists(String message, IotAuthorizer authorizer) {
        return new AwsException("ResourceAlreadyExistsException", message, 409,
                Map.of("resourceId", authorizer.getAuthorizerName(), "resourceArn", authorizer.getAuthorizerArn()));
    }

    static AwsException constraint(String field, String constraint) {
        return constraints(field, List.of(constraint));
    }

    private static AwsException constraints(String field, List<String> constraints) {
        return invalid(constraints.size() + " validation error" + (constraints.size() == 1 ? "" : "s") + " detected: "
                + constraints.stream()
                        .map(constraint -> "Value at '" + field + "' failed to satisfy constraint: " + constraint)
                        .collect(Collectors.joining("; ")));
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static String key(String region, String name) {
        return "authorizer:" + region + ":" + name;
    }
}
