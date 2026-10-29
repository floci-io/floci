package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the many forms AWS Lambda accepts for a {@code FunctionName} path
 * parameter: bare name, partial ARN ({@code ACCT:function:NAME}), or full ARN
 * ({@code arn:aws:lambda:REGION:ACCT:function:NAME}), each optionally suffixed
 * with {@code :qualifier} (version or alias).
 */
public final class LambdaArnUtils {

    /**
     * FunctionName constraints from the Lambda API reference (CreateFunction, FunctionName):
     * {@code [a-zA-Z0-9-_]+}, with no dot. A bare name (no ARN prefix) is capped at 64
     * characters; the ARN and partial-ARN forms embed the same character class but are
     * bounded instead by {@link #MAX_TOTAL_LENGTH} on the full string.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-_]+");
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_TOTAL_LENGTH = 140;
    /** The NamespacedFunctionName shape used by Lambda's attachment and mapping APIs. */
    public static final Pattern NAMESPACED_FUNCTION_NAME_PATTERN = Pattern.compile(
            "^(arn:(aws[a-zA-Z-]*)?:lambda:)?(?<region>(eusc-)?[a-z]{2}((-gov)|(-iso([a-z]?)))?-[a-z]+-\\d{1}:)?"
                    + "(?<account>\\d{12}:)?(function:)?(?<name>[a-zA-Z0-9-_\\.]+)"
                    + "(?::(?<qualifier>\\$LATEST(\\.PUBLISHED)?|[a-zA-Z0-9-_]+))?$");
    /** The model's {@code FunctionName} pattern, quoted in the ValidationException a bad name gets. */
    private static final String NAME_REGEX = "(arn:(aws[a-zA-Z-]*)?:lambda:)?"
            + "((eusc-)?[a-z]{2}((-gov)|(-iso([a-z]?)))?-[a-z]+-\\d{1}:)?"
            + "(\\d{12}:)?(function:)?([a-zA-Z0-9-_]+)(:(\\$LATEST|[a-zA-Z0-9-_]+))?";
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\d{12}");
    private static final Pattern QUALIFIER_PATTERN = Pattern.compile("\\$LATEST|[a-zA-Z0-9-_]+");

    private static final String EVENT_SOURCE_MAPPING_PREFIX = "event-source-mapping:";

    /** The pattern AWS validates a durable execution ARN with, and quotes in its ValidationException. */
    public static final Pattern DURABLE_EXECUTION_ARN = Pattern.compile(
            "arn:([a-zA-Z0-9-]+):lambda:([a-zA-Z0-9-]+):(\\d{12}):function:([a-zA-Z0-9_-]+):"
                    + "(\\$LATEST(?:\\.PUBLISHED)?|[0-9]+)/durable-execution/([a-zA-Z0-9_-]+)/([a-zA-Z0-9_-]+)");

    private LambdaArnUtils() {}

    /** {@code arn:<partition>:lambda:<region>:<account>:event-source-mapping:<uuid>}. */
    public static String eventSourceMappingArn(String region, String accountId, String uuid) {
        return AwsArnUtils.Arn.of("lambda", region, accountId, EVENT_SOURCE_MAPPING_PREFIX + uuid).toString();
    }

    /**
     * The UUID of an event source mapping ARN, or {@code null} when {@code arn} names
     * some other Lambda resource.
     */
    public static String eventSourceMappingUuid(AwsArnUtils.Arn arn) {
        if (!"lambda".equals(arn.service()) || !arn.resource().startsWith(EVENT_SOURCE_MAPPING_PREFIX)) {
            return null;
        }
        return arn.resource().substring(EVENT_SOURCE_MAPPING_PREFIX.length());
    }

    /** {@code arn:<partition>:lambda:<region>:<account>:network-connector:<id>:<version>}. */
    public static String networkConnectorVersionArn(String connectorArn, long version) {
        return connectorArn + ":" + version;
    }

    /**
     * The full function ARN a {@code FunctionName} reference names, the way an IAM policy's
     * {@code Resource} names it. The reference is read as {@link #resolve} reads it, so a malformed
     * one is refused here. A full ARN comes back as given; a name or partial ARN is completed with
     * {@code partition} and {@code region}, and with {@code defaultAccount} unless it names its own
     * account. A qualifier is kept, since Lambda authorizes a qualified call against the qualified ARN.
     */
    public static String functionArn(String reference, String partition, String region, String defaultAccount) {
        ResolvedFunctionRef ref = resolve(reference);
        if (AwsArnUtils.looksLikeArn(reference)) {
            return reference;
        }
        String resource = ref.qualifier() == null
                ? "function:" + ref.name()
                : "function:" + ref.name() + ":" + ref.qualifier();
        String account = ref.account() != null ? ref.account() : defaultAccount;
        return new AwsArnUtils.Arn(partition, "lambda", region, account, resource).toString();
    }

    /**
     * Resolved components of a Lambda function reference.
     *
     * @param name      short function name (never null/blank)
     * @param qualifier version or alias, or null if absent
     * @param region    region extracted from a full ARN, or null for bare
     *                  name / partial ARN inputs
     * @param account   account named by a full or partial ARN, or null for a bare name
     */
    public record ResolvedFunctionRef(String name, String qualifier, String region, String account) {}

    /**
     * Parses Lambda's broader {@code NamespacedFunctionName} shape. Unlike {@link #resolve}, this
     * accepts dotted names, optional region/account prefixes without {@code function:}, and the
     * {@code function:NAME} namespace used by the code-signing attachment APIs.
     */
    public static ResolvedFunctionRef resolveNamespacedFunctionName(String input) {
        if (input == null || input.isEmpty() || input.length() > 256) {
            throw namespacedValidationFailure(input);
        }
        Matcher matcher = NAMESPACED_FUNCTION_NAME_PATTERN.matcher(input);
        if (!matcher.matches()) {
            throw namespacedValidationFailure(input);
        }
        if (matcher.group(1) != null) {
            if (!AwsArnUtils.isArn(input)) {
                throw invalid("Invalid ARN: " + input);
            }
            AwsArnUtils.Arn arn;
            try {
                arn = AwsArnUtils.parse(input);
            } catch (IllegalArgumentException e) {
                throw invalid("Invalid ARN: " + input);
            }
            if (!"lambda".equals(arn.service())) {
                throw invalid("Invalid ARN: " + input);
            }
            return new ResolvedFunctionRef(matcher.group("name"), matcher.group("qualifier"),
                    arn.region(), arn.accountId());
        }
        return new ResolvedFunctionRef(matcher.group("name"), matcher.group("qualifier"),
                stripTrailingColon(matcher.group("region")), stripTrailingColon(matcher.group("account")));
    }

    private static String stripTrailingColon(String value) {
        return value == null ? null : value.substring(0, value.length() - 1);
    }

    private static AwsException namespacedValidationFailure(String input) {
        return SmithyValidationErrors.validationException(
                "Value '" + input + "' at 'functionName' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: "
                        + NAMESPACED_FUNCTION_NAME_PATTERN.pattern());
    }

    /**
     * Parses a {@code FunctionName} path parameter. Throws {@link AwsException}, HTTP 400,
     * on any malformed input: {@code ValidationException} when the name fails the
     * {@code FunctionName} pattern or length constraint, {@code InvalidParameterValueException}
     * for other malformed forms (bad ARN structure, mismatched qualifiers, and the like).
     */
    public static ResolvedFunctionRef resolve(String input) {
        if (input == null || input.isBlank()) {
            throw invalid("FunctionName must not be blank");
        }
        if (input.length() > MAX_TOTAL_LENGTH) {
            throw validationFailure(input,
                    "Member must have length less than or equal to " + MAX_TOTAL_LENGTH);
        }

        if (AwsArnUtils.looksLikeArn(input)) {
            return parseFullArn(input);
        }
        if (input.contains(":function:")) {
            return parsePartialArn(input);
        }
        return parseNameWithOptionalQualifier(input);
    }

    /**
     * Resolves an input and reconciles any embedded qualifier with a
     * {@code ?Qualifier=} query-string value. If both are supplied and differ,
     * throws 400. Returns the effective qualifier (may be null).
     */
    public static ResolvedFunctionRef resolveWithQualifier(String input, String queryQualifier) {
        ResolvedFunctionRef ref = resolve(input);
        String embedded = ref.qualifier();
        String normalizedQuery = (queryQualifier == null || queryQualifier.isBlank()) ? null : queryQualifier;

        if (embedded != null && normalizedQuery != null && !embedded.equals(normalizedQuery)) {
            throw invalid("The derived qualifier from the function name does not match the specified qualifier.");
        }
        String effective = embedded != null ? embedded : normalizedQuery;
        if (effective != null && !QUALIFIER_PATTERN.matcher(effective).matches()) {
            throw invalid("Invalid qualifier: " + effective);
        }
        return new ResolvedFunctionRef(ref.name(), effective, ref.region(), ref.account());
    }

    /** Resolves the qualifier and requires a full ARN to name the request region. */
    public static ResolvedFunctionRef resolveWithRegion(String region, String input, String queryQualifier) {
        ResolvedFunctionRef ref = resolveWithQualifier(input, queryQualifier);
        enforceRegion(region, ref);
        return ref;
    }

    static void enforceRegion(String region, ResolvedFunctionRef ref) {
        if (ref.region() != null && !ref.region().equals(region)) {
            throw invalid("Region '" + ref.region() + "' in ARN does not match request region '" + region + "'");
        }
    }

    private static ResolvedFunctionRef parseFullArn(String input) {
        // arn:aws:lambda:REGION:ACCT:function:NAME[:QUALIFIER]
        AwsArnUtils.Arn base;
        try {
            base = AwsArnUtils.parse(input);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid ARN: " + input);
        }
        if (!"lambda".equals(base.service())) {
            throw invalid("Invalid ARN: " + input);
        }
        if (base.region().isBlank()) {
            throw invalid("ARN missing region: " + input);
        }
        if (!ACCOUNT_PATTERN.matcher(base.accountId()).matches()) {
            throw invalid("ARN has invalid account id: " + input);
        }
        // resource is "function:NAME" or "function:NAME:QUALIFIER"
        String resource = base.resource();
        String[] resParts = resource.split(":", -1);
        if (resParts.length < 2 || resParts.length > 3) {
            throw invalid("Invalid ARN: " + input);
        }
        if (!"function".equals(resParts[0])) {
            throw invalid("ARN resource type must be 'function': " + input);
        }
        String name = resParts[1];
        validateNamePattern(name);
        String qualifier = resParts.length == 3 ? resParts[2] : null;
        if (qualifier != null) {
            validateQualifier(qualifier);
        }
        return new ResolvedFunctionRef(name, qualifier, base.region(), base.accountId());
    }

    private static ResolvedFunctionRef parsePartialArn(String input) {
        // ACCT:function:NAME[:QUALIFIER]
        String[] parts = input.split(":", -1);
        if (parts.length < 3 || parts.length > 4) {
            throw invalid("Invalid partial ARN: " + input);
        }
        if (!"function".equals(parts[1])) {
            throw invalid("Partial ARN resource type must be 'function': " + input);
        }
        String account = parts[0];
        if (!ACCOUNT_PATTERN.matcher(account).matches()) {
            throw invalid("Partial ARN has invalid account id: " + input);
        }
        String name = parts[2];
        validateNamePattern(name);
        String qualifier = parts.length == 4 ? parts[3] : null;
        if (qualifier != null) {
            validateQualifier(qualifier);
        }
        return new ResolvedFunctionRef(name, qualifier, null, account);
    }

    private static ResolvedFunctionRef parseNameWithOptionalQualifier(String input) {
        // NAME[:QUALIFIER]
        String[] parts = input.split(":", -1);
        if (parts.length > 2) {
            throw invalid("Invalid FunctionName: " + input);
        }
        String name = parts[0];
        validateBareName(name);
        String qualifier = parts.length == 2 ? parts[1] : null;
        if (qualifier != null) {
            validateQualifier(qualifier);
        }
        return new ResolvedFunctionRef(name, qualifier, null, null);
    }

    private static void validateNamePattern(String name) {
        if (name == null || name.isEmpty()) {
            throw invalid("FunctionName segment is empty");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw validationFailure(name, "Member must satisfy regular expression pattern: " + NAME_REGEX);
        }
    }

    private static void validateBareName(String name) {
        validateNamePattern(name);
        if (name.length() > MAX_NAME_LENGTH) {
            throw validationFailure(name, "Member must have length less than or equal to " + MAX_NAME_LENGTH);
        }
    }

    private static void validateQualifier(String qualifier) {
        if (qualifier.isEmpty()) {
            throw invalid("Qualifier segment is empty");
        }
        if (!QUALIFIER_PATTERN.matcher(qualifier).matches()) {
            throw invalid("Invalid qualifier: " + qualifier);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private static AwsException validationFailure(String value, String constraint) {
        return SmithyValidationErrors.validationException(
                "Value '" + value + "' at 'functionName' failed to satisfy "
                        + "constraint: " + constraint);
    }

    /** Unwraps an API Gateway invocation URI without discarding the target ARN's owner, region or qualifier. */
    public static String extractFunctionReferenceFromUri(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            String resource = AwsArnUtils.parse(uri).resource();
            String[] parts = resource.split("/");
            if (parts.length >= 4 && "path".equals(parts[0]) && "functions".equals(parts[2])) {
                return extractFunctionReferenceFromUri(String.join("/", Arrays.copyOfRange(parts, 3, parts.length)));
            }
            return uri.split("/", 2)[0];
        } catch (IllegalArgumentException e) {
            // Bare and partial function references are validated by the Lambda service.
            return uri;
        }
    }
}
