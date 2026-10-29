package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SignedMarkers;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.model.CodeSigningConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Code signing configurations, the resource CreateCodeSigningConfig manages.
 *
 * <p>Nothing is verified against these. Lambda's own signature checking is not emulated, so a
 * configuration is stored and reported back and never gates a deployment.
 */
@ApplicationScoped
public class LambdaCodeSigningConfigService {

    /**
     * Lowercase alphanumerics because the ARN is the stricter of the two patterns, not the id.
     * CodeSigningConfigId allows {@code csc-[a-zA-Z0-9-_\.]{17}}, while CodeSigningConfigArn ends
     * in {@code csc-[a-z0-9]{17}}. Widening this alphabet to match the id pattern would still mint
     * a conforming id and an ARN that no longer conforms.
     */
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 17;
    private static final int MAX_DESCRIPTION = 256;
    private static final int MAX_SIGNING_PROFILE_ARNS = 20;
    private static final int LIST_PAGE_SIZE = 10000;
    private static final Pattern CODE_SIGNING_CONFIG_ARN = Pattern.compile(
            "arn:(aws[a-zA-Z-]*)?:lambda:(eusc-)?[a-z]{2}((-gov)|(-iso([a-z]?)))?-[a-z]+-\\d{1}"
                    + ":\\d{12}:code-signing-config:csc-[a-z0-9]{17}");
    private static final int ARN_MAX_LENGTH = 200;

    /** The model documents Warn as the default when the request names no policy. */
    static final String DEFAULT_UNTRUSTED_ARTIFACT_POLICY = "Warn";
    private static final List<String> POLICIES = List.of("Warn", "Enforce");

    private static final DateTimeFormatter LAST_MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

    private final StorageBackend<String, CodeSigningConfig> store;
    private final RegionResolver regionResolver;
    private final SignedMarkers markers = new SignedMarkers();
    private final SecureRandom random = new SecureRandom();
    private final Object[] configLocks = new Object[64];

    @Inject
    public LambdaCodeSigningConfigService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
        this.store = storageFactory.create("lambda", "lambda-code-signing-configs.json",
                new TypeReference<Map<String, CodeSigningConfig>>() {});
        Arrays.setAll(configLocks, ignored -> new Object());
    }

    /** Serializes attachment and config deletion so neither can commit against stale state. */
    public <T> T withConfigLock(String arn, Supplier<T> operation) {
        synchronized (configLocks[Math.floorMod(arn.hashCode(), configLocks.length)]) {
            return operation.get();
        }
    }

    public CodeSigningConfig create(String region, String accountId, String description,
                                    CodeSigningConfig.AllowedPublishers allowedPublishers,
                                    CodeSigningConfig.CodeSigningPolicies policies) {
        validateDescription(description);
        validateAllowedPublishers(allowedPublishers);
        validatePolicies(policies);

        String id = "csc-" + randomId();
        CodeSigningConfig config = new CodeSigningConfig();
        config.setCodeSigningConfigId(id);
        config.setCodeSigningConfigArn(
                AwsArnUtils.Arn.of("lambda", region, accountId, "code-signing-config:" + id).toString());
        config.setDescription(description);
        config.setAllowedPublishers(allowedPublishers);
        config.setCodeSigningPolicies(policies != null ? policies : defaultPolicies());
        config.setLastModified(now());
        store.put(key(region, id), config);
        return config;
    }

    public CodeSigningConfig get(String region, String arn) {
        return store.get(key(region, idFromArn(arn)))
                .orElseThrow(() -> notFound(arn));
    }

    /**
     * Resolves a configuration for an API request and rejects an ARN from another account or
     * region, even when its trailing id happens to match a local record.
     */
    public CodeSigningConfig get(String region, String accountId, String arn) {
        CodeSigningConfig config = get(region, arn);
        AwsArnUtils.Arn requested = AwsArnUtils.parse(arn);
        if (!region.equals(requested.region())
                || !accountId.equals(requested.accountId())
                || !arn.equals(config.getCodeSigningConfigArn())) {
            throw notFound(arn);
        }
        return config;
    }

    /**
     * Validates and resolves a configuration named by a function attachment request. The Lambda
     * APIs use a distinct error code when the requested configuration does not exist, while the
     * configuration resource APIs keep {@code ResourceNotFoundException}.
     */
    public CodeSigningConfig requireForAttachment(String region, String accountId, String arn) {
        validateArn(arn);
        try {
            return get(region, accountId, arn);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            throw new AwsException("CodeSigningConfigNotFoundException", e.getMessage(), 404);
        }
    }

    /**
     * Applies only the members the request names. A member the request omits keeps its stored
     * value, which is what UpdateCodeSigningConfig does: every one of its inputs is optional.
     */
    public CodeSigningConfig update(String region, String arn, String description,
                                    CodeSigningConfig.AllowedPublishers allowedPublishers,
                                    CodeSigningConfig.CodeSigningPolicies policies) {
        CodeSigningConfig config = get(region, arn);
        // Every member is validated before any is applied. The store hands back the live instance,
        // so validating and setting in turn would leave an earlier member written when a later one
        // is rejected, and the request would have changed the configuration while answering 400.
        if (description != null) {
            validateDescription(description);
        }
        if (allowedPublishers != null) {
            validateAllowedPublishers(allowedPublishers);
        }
        if (policies != null) {
            validatePolicies(policies);
        }
        if (description != null) {
            config.setDescription(description);
        }
        if (allowedPublishers != null) {
            config.setAllowedPublishers(allowedPublishers);
        }
        if (policies != null) {
            config.setCodeSigningPolicies(policies);
        }
        config.setLastModified(now());
        store.put(key(region, config.getCodeSigningConfigId()), config);
        return config;
    }

    public void delete(String region, String arn) {
        String id = idFromArn(arn);
        if (store.get(key(region, id)).isEmpty()) {
            throw notFound(arn);
        }
        store.delete(key(region, id));
    }

    public List<CodeSigningConfig> list(String region) {
        String prefix = "csc::" + region + "::";
        return store.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(CodeSigningConfig::getCodeSigningConfigId))
                .toList();
    }

    public PaginatedResult<CodeSigningConfig> list(String region, int maxItems, String marker) {
        String scope = regionResolver.getAccountId() + "\n" + region + "\n";
        return LambdaPagination.paginate(list(region), CodeSigningConfig::getCodeSigningConfigId,
                maxItems, LIST_PAGE_SIZE, marker, markers, scope);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "The code signing configuration " + arn + " does not exist.", 404);
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new AwsException("InvalidParameterValueException",
                    "Description must be at most " + MAX_DESCRIPTION + " characters.", 400);
        }
    }

    /**
     * AllowedPublishers is required and carries between one and twenty signing profile ARNs. An
     * empty list is refused rather than stored: a configuration that allows no publisher at all is
     * not a shape the API accepts.
     */
    private static void validateAllowedPublishers(CodeSigningConfig.AllowedPublishers publishers) {
        List<String> arns = publishers == null ? null : publishers.getSigningProfileVersionArns();
        if (arns == null || arns.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "AllowedPublishers must name at least one signing profile version ARN.", 400);
        }
        if (arns.size() > MAX_SIGNING_PROFILE_ARNS) {
            throw new AwsException("InvalidParameterValueException",
                    "AllowedPublishers accepts at most " + MAX_SIGNING_PROFILE_ARNS
                            + " signing profile version ARNs.", 400);
        }
    }

    private static void validatePolicies(CodeSigningConfig.CodeSigningPolicies policies) {
        if (policies == null) {
            return;
        }
        String policy = policies.getUntrustedArtifactOnDeployment();
        if (policy != null && !POLICIES.contains(policy)) {
            throw new AwsException("InvalidParameterValueException",
                    "UntrustedArtifactOnDeployment must be one of " + POLICIES + ".", 400);
        }
        if (policy == null) {
            policies.setUntrustedArtifactOnDeployment(DEFAULT_UNTRUSTED_ARTIFACT_POLICY);
        }
    }

    private static CodeSigningConfig.CodeSigningPolicies defaultPolicies() {
        CodeSigningConfig.CodeSigningPolicies policies = new CodeSigningConfig.CodeSigningPolicies();
        policies.setUntrustedArtifactOnDeployment(DEFAULT_UNTRUSTED_ARTIFACT_POLICY);
        return policies;
    }

    /** The trailing segment of a code-signing-config ARN, or the value itself when it is an id. */
    static String idFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "CodeSigningConfigArn is required.", 400);
        }
        int colon = arn.lastIndexOf(':');
        return colon < 0 ? arn : arn.substring(colon + 1);
    }

    static void validateArn(String arn) {
        if (arn == null || arn.isBlank()
                || arn.length() > ARN_MAX_LENGTH
                || !CODE_SIGNING_CONFIG_ARN.matcher(arn).matches()) {
            throw SmithyValidationErrors.exception("InvalidParameterValueException",
                    SmithyValidationErrors.pattern("codeSigningConfigArn", arn, CODE_SIGNING_CONFIG_ARN.pattern()));
        }
    }

    private static String key(String region, String id) {
        return "csc::" + region + "::" + id;
    }

    private static String now() {
        return LAST_MODIFIED.format(Instant.now());
    }

    private String randomId() {
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }
}
